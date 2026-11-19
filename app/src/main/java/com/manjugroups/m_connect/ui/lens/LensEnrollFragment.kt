package com.manjugroups.m_connect.ui.lens

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.manjugroups.m_connect.ui.common.AppBottomSheets
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentLensEnrollBinding
import com.manjugroups.m_connect.databinding.ItemLensPoseBinding
import com.manjugroups.m_connect.ui.common.Floater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Lens face enrollment for one staff member.
 *
 * Capture mode: front camera with on-device face detection. The person is
 * guided through straight → left → right head turns; each pose is captured
 * automatically from the sharpest still frame (no shutter button).
 * Review mode: the 3–5 photos, retake any pose, optional Replace, upload.
 */
class LensEnrollFragment : Fragment() {

    private var _binding: FragmentLensEnrollBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LensEnrollViewModel by viewModels()
    private lateinit var session: SessionManager

    private val staffId by lazy { requireArguments().getString(ARG_STAFF_ID).orEmpty() }
    private val staffName by lazy { requireArguments().getString(ARG_NAME).orEmpty() }
    private val employeeCode by lazy { requireArguments().getString(ARG_CODE).orEmpty() }
    private val existingSamples by lazy { requireArguments().getInt(ARG_EXISTING, 0) }
    /** Lens Test build only: capture + review, photos saved on the phone, nothing uploaded. */
    private val dryRun by lazy { requireArguments().getBoolean(ARG_DRY_RUN, false) }
    /** Retake of one saved photo (from the Saved photos screen). */
    private val singlePose by lazy { LensPose.fromKey(requireArguments().getString(ARG_SINGLE_POSE)) }
    private val startReplace by lazy { requireArguments().getBoolean(ARG_REPLACE, false) }
    private val resultKey by lazy { requireArguments().getString(ARG_RESULT_KEY) ?: RESULT_KEY }
    /** Back camera by default: an admin photographs the staff member. */
    private var useFrontCamera = false

    private var cameraExecutor: ExecutorService? = null
    private var engine: LensCaptureEngine? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraBound = false
    private var cameraStarting = false

    private val live = MutableStateFlow<LensLiveState?>(null)
    private var arrowAnim: ObjectAnimator? = null
    private var arrowSide = 0
    private var optionalSkipJob: Job? = null
    private var lastTarget: LensPose? = null
    private var enrolled = false
    private val thumbs = mutableMapOf<LensPose, Pair<LensCapturedPhoto, Bitmap>>()

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            maybeStartCamera()
        } else {
            Floater.warning(getString(R.string.lens_camera_permission_needed))
            finish()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLensEnrollBinding.inflate(inflater, container, false)
        return binding.root
    }

    /** Status bar follows the visible mode: dark camera vs white review. */
    private var topBarReviewing: Boolean? = null

    private fun applyTopBar(reviewing: Boolean, force: Boolean = false) {
        if (!force && topBarReviewing == reviewing) return
        topBarReviewing = reviewing
        val main = activity as? com.manjugroups.m_connect.MainActivity ?: return
        if (reviewing) main.setTopBarAppearance(android.graphics.Color.WHITE, true, fullBleed = true)
        else main.setTopBarAppearance(android.graphics.Color.BLACK, false, fullBleed = true)
    }

    override fun onResume() {
        super.onResume()
        applyTopBar(topBarReviewing ?: false, force = true)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // No keyboard on this screen (e.g. still open from the staff search).
        (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken ?: requireActivity().window.decorView.windowToken, 0)
        session = SessionManager(requireContext())
        if (!dryRun && (!LensStaffFragment.isAvailable(session) || staffId.isBlank())) {
            parentFragmentManager.popBackStack()
            return
        }
        // Both top bars pad themselves by the status-bar inset (full-bleed).
        com.manjugroups.m_connect.ui.common.StatusBarInsets.padHeader(binding.lensCaptureTop)
        com.manjugroups.m_connect.ui.common.StatusBarInsets.padHeader(binding.lensReviewHeader, fixedHeight = true)
        viewModel.init(existingSamples, startReplace, singlePose)

        val title = if (employeeCode.isNotBlank()) "$staffName · $employeeCode" else staffName
        binding.tvLensCaptureName.text = title
        binding.tvLensReviewName.text = title
        binding.btnLensClose.setOnClickListener { confirmLeave() }
        binding.btnLensReviewClose.setOnClickListener { confirmLeave() }
        binding.btnLensReview.setOnClickListener { viewModel.review() }
        binding.btnLensSkip.setOnClickListener { viewModel.state.value.target?.let(viewModel::skip) }
        binding.btnLensRecordAgain.setOnClickListener { viewModel.recordAgain() }
        binding.swLensReplace.setOnCheckedChangeListener { _, checked -> viewModel.setReplace(checked) }
        binding.btnLensUpload.setOnClickListener { onUploadClicked() }
        binding.btnLensSwitchCamera.setOnClickListener { switchCamera() }
        binding.lensGuide.mirroredPreview = useFrontCamera
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })

        cameraExecutor = Executors.newSingleThreadExecutor()
        engine = LensCaptureEngine(object : LensCaptureEngine.Listener {
            override fun onLiveState(state: LensLiveState) {
                live.value = state
            }

            override fun onCaptured(photo: LensCapturedPhoto) {
                viewModel.onCaptured(photo)
                _binding?.root?.post { celebrateCapture() }
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { live.collect(::renderLive) }
            }
        }

        if (hasCameraPermission()) maybeStartCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    // ── Camera ────────────────────────────────────────────────────────────

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun maybeStartCamera() {
        if (_binding == null || cameraBound || cameraStarting) return
        if (viewModel.state.value.reviewing || !hasCameraPermission()) return
        cameraStarting = true
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            cameraStarting = false
            val b = _binding ?: return@addListener
            if (viewModel.state.value.reviewing) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build()
                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .build(),
                    )
                    .build()
                preview.setSurfaceProvider(b.lensPreview.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val exec = cameraExecutor ?: return@addListener
                val eng = engine ?: return@addListener
                analysis.setAnalyzer(exec, eng)
                provider.unbindAll()
                var selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) {
                    // Phone without that camera: fall back to the other one.
                    useFrontCamera = !useFrontCamera
                    selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                }
                b.lensGuide.mirroredPreview = useFrontCamera
                provider.bindToLifecycle(viewLifecycleOwner, selector, preview, analysis)
                eng.pending = viewModel.state.value.pending
                if (dryRun && eng.frameLog == null) {
                    // Lens Test: per-frame log for tuning (adb pull .../files/lens-test/frames.log).
                    eng.frameLog = java.io.File(requireContext().getExternalFilesDir(null), "lens-test/frames.log").also {
                        it.parentFile?.mkdirs()
                        it.writeText("")
                    }
                }
                eng.paused = false
                cameraBound = true
            } catch (e: Exception) {
                Floater.error(getString(R.string.lens_camera_failed))
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun switchCamera() {
        if (cameraStarting) return
        useFrontCamera = !useFrontCamera
        arrowSide = Int.MIN_VALUE // re-point the arrow for the new preview
        engine?.resetPoseState()
        runCatching { cameraProvider?.unbindAll() }
        cameraBound = false
        maybeStartCamera()
    }

    private fun stopCamera() {
        engine?.paused = true
        if (!cameraBound) return
        runCatching { cameraProvider?.unbindAll() }
        cameraBound = false
    }

    // ── Rendering ─────────────────────────────────────────────────────────

    private fun render(state: LensEnrollUiState) {
        val b = _binding ?: return
        engine?.pending = state.pending
        b.lensCapture.isVisible = !state.reviewing
        b.lensReview.isVisible = state.reviewing
        applyTopBar(state.reviewing)
        b.root.keepScreenOn = !state.reviewing
        if (state.reviewing) {
            stopCamera()
            stopArrow()
            optionalSkipJob?.cancel()
            renderReview(state)
            return
        }
        maybeStartCamera()

        val total = LensPose.entries.count { it !in state.skipped }
        b.tvLensCaptureStep.text = getString(R.string.lens_step_count, state.photos.size, total)
        b.btnLensReview.isEnabled = state.photos.isNotEmpty() && state.retakeOnly == null
        b.btnLensReview.isVisible = state.retakeOnly == null && state.single == null
        val target = state.target
        b.btnLensSkip.isVisible = target != null && !target.required && state.retakeOnly == null && state.single == null

        paintSegments(state, live.value)
        if (target != lastTarget) {
            lastTarget = target
            scheduleOptionalSkip(target, state.retakeOnly != null || state.single != null)
        }
        renderPrompt(target, live.value)
    }

    // Hint hysteresis: a new hint shows only after it has held for HINT_HOLD_MS,
    // so the line doesn't flicker between reasons frame to frame.
    private var shownHint: LensHint? = null
    private var candidateHint: LensHint? = null
    private var candidateSince = 0L
    private var hintShownOnce = false
    private var debugView: android.widget.TextView? = null

    private fun stableHint(hint: LensHint?): LensHint? {
        val now = android.os.SystemClock.uptimeMillis()
        if (hint != candidateHint) {
            candidateHint = hint
            candidateSince = now
        }
        if (!hintShownOnce || hint == shownHint || now - candidateSince >= HINT_HOLD_MS) {
            shownHint = hint
            hintShownOnce = true
        }
        return shownHint
    }

    /** Lens Test build only: a live line of what the detector sees. */
    private fun renderDebug(state: LensLiveState?) {
        if (!dryRun) return
        val b = _binding ?: return
        val tv = debugView ?: android.widget.TextView(requireContext()).also { v ->
            v.setTextColor(android.graphics.Color.WHITE)
            v.textSize = 11f
            v.typeface = android.graphics.Typeface.MONOSPACE
            v.setBackgroundColor(0x99000000.toInt())
            val pad = (6 * resources.displayMetrics.density).toInt()
            v.setPadding(pad, pad / 2, pad, pad / 2)
            b.lensCapture.addView(
                v,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP,
                ).apply { topMargin = (110 * resources.displayMetrics.density).toInt() },
            )
            debugView = v
        }
        tv.text = state?.debug ?: "no frames yet"
    }

    private fun renderLive(state: LensLiveState?) {
        val b = _binding ?: return
        val vm = viewModel.state.value
        if (vm.reviewing) return
        renderDebug(state)
        val hint = stableHint(
            when {
                state == null -> null
                !state.faceFound -> LensHint.NO_FACE
                else -> state.hint
            },
        )
        if (hint != null) {
            if (b.tvLensHint.text != hint.text) b.tvLensHint.text = hint.text
            b.tvLensHint.visibility = View.VISIBLE
        } else {
            b.tvLensHint.visibility = View.INVISIBLE
        }
        paintSegments(vm, state)
        b.lensGuide.setProgress(state?.target, state?.progress ?: 0f)
        renderPrompt(vm.target, state)
    }

    /** Ring segments from the captured set plus the pose being held right now. */
    private fun paintSegments(state: LensEnrollUiState, live: LensLiveState?) {
        val b = _binding ?: return
        val target = state.target
        for (pose in LensPose.entries) {
            b.lensGuide.setSegment(
                pose,
                when {
                    pose in state.photos -> LensFaceGuideView.Segment.DONE
                    pose in state.skipped -> LensFaceGuideView.Segment.SKIPPED
                    pose == live?.holding -> LensFaceGuideView.Segment.HOLDING
                    pose == target -> LensFaceGuideView.Segment.ACTIVE
                    else -> LensFaceGuideView.Segment.PENDING
                },
            )
        }
    }

    private fun renderPrompt(target: LensPose?, live: LensLiveState?) {
        val b = _binding ?: return
        val yaw = live?.yaw ?: 0f
        val onLeftSide = live?.faceFound == true && yaw > LensCapturePolicy.STRAIGHT_MAX
        val text = when (target) {
            null -> getString(R.string.lens_prompt_done)
            LensPose.STRAIGHT -> getString(R.string.lens_prompt_straight)
            LensPose.SLIGHT_LEFT -> getString(R.string.lens_prompt_slight_left)
            LensPose.MORE_LEFT -> getString(R.string.lens_prompt_more_left)
            LensPose.SLIGHT_RIGHT, LensPose.MORE_RIGHT ->
                if (onLeftSide) getString(R.string.lens_prompt_back_to_centre)
                else getString(if (target == LensPose.SLIGHT_RIGHT) R.string.lens_prompt_slight_right else R.string.lens_prompt_more_right)
        }
        if (b.tvLensPrompt.text != text) b.tvLensPrompt.text = text
        // Arrow toward the target until the head is inside its range.
        val side = when {
            target == null || target == LensPose.STRAIGHT -> 0
            live?.poseNow == target -> 0
            // Front preview is mirrored (person's left = screen left); the back
            // camera's isn't (person's left = screen right).
            target.targetYaw > 0 -> if (useFrontCamera) -1 else 1
            else -> if (useFrontCamera) 1 else -1
        }
        setArrow(side)
    }

    private fun setArrow(side: Int) {
        if (side == arrowSide) return
        arrowSide = side
        stopArrow()
        val b = _binding ?: return
        b.ivLensArrowLeft.alpha = 0f
        b.ivLensArrowRight.alpha = 0f
        if (side == 0) return
        val view = if (side < 0) b.ivLensArrowLeft else b.ivLensArrowRight
        view.alpha = 1f
        val distance = 14f * resources.displayMetrics.density * side
        arrowAnim = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, distance).apply {
            duration = 650
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopArrow() {
        arrowAnim?.cancel()
        arrowAnim = null
        _binding?.ivLensArrowLeft?.translationX = 0f
        _binding?.ivLensArrowRight?.translationX = 0f
    }

    /** Optional poses give up after a while instead of trapping the person. */
    private fun scheduleOptionalSkip(target: LensPose?, retake: Boolean) {
        optionalSkipJob?.cancel()
        if (target == null || target.required || retake) return
        optionalSkipJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(OPTIONAL_TIMEOUT_MS)
            val s = viewModel.state.value
            if (!s.reviewing && s.target == target && live.value?.holding != target) viewModel.skip(target)
        }
    }

    private fun celebrateCapture() {
        val b = _binding ?: return
        b.lensFlash.animate().cancel()
        b.lensFlash.alpha = 0.75f
        b.lensFlash.animate().alpha(0f).setDuration(260).start()
        val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        b.root.performHapticFeedback(feedback)
    }

    // ── Review ────────────────────────────────────────────────────────────

    /** Poses shown on the review screen: all of them, or the one being retaken. */
    private fun gridPoses(state: LensEnrollUiState): List<LensPose> =
        state.single?.let { listOf(it) } ?: LensPose.guidedOrder

    private fun succeeded(state: LensEnrollUiState): Boolean {
        val done = state.upload as? LensUploadState.Done ?: return false
        if (state.single != null) {
            return done.response.lensStatus == "lens_pending" || (done.response.addedSamples ?: 0) > 0
        }
        return done.response.needsRetake != true && (done.response.addedSamples ?: 0) >= 3
    }

    private fun renderReview(state: LensEnrollUiState) {
        val b = _binding ?: return
        val uploading = state.upload is LensUploadState.Uploading
        val done = state.upload as? LensUploadState.Done
        val failed = state.upload as? LensUploadState.Failed
        val succeeded = succeeded(state)
        // Lens down / not configured, but the server kept the photos on the profile.
        val savedPending = failed != null && LensErrors.savedForLater(failed.outcome)
        val single = state.single

        renderPoseGrid(state, locked = uploading || succeeded || savedPending)

        // No switch: "Retake all" is the replace (decided on the saved photos
        // screen); here it's only a note. Other enrolls add to what's there.
        b.lensReplaceRow.isVisible = state.replace && existingSamples > 0 && !succeeded && single == null
        b.tvLensReplaceBody.text = getString(R.string.lens_replace_note, existingSamples)
        b.swLensReplace.isVisible = false

        // Result card.
        when {
            done != null && single != null -> {
                val pose = single.label.lowercase()
                if (done.response.lensStatus == "lens_pending") {
                    showResult(
                        getString(R.string.lens_result_saved_title), R.attr.colorWarning,
                        done.response.message ?: getString(R.string.lens_retake_saved_body, pose),
                    )
                } else {
                    showResult(
                        getString(R.string.lens_retake_added_title), R.attr.colorSuccess,
                        done.response.message ?: getString(R.string.lens_retake_added_body, pose),
                    )
                }
            }
            done != null -> {
                val added = done.response.addedSamples ?: 0
                val total = done.response.faceSamples ?: added
                if (succeeded) {
                    showResult(
                        getString(R.string.lens_result_success_title), R.attr.colorSuccess,
                        getString(R.string.lens_result_success_body, added, staffName.ifBlank { "This staff member" }, total),
                    )
                } else {
                    showResult(
                        getString(R.string.lens_result_retake_title), R.attr.colorWarning,
                        getString(R.string.lens_result_retake_body, added),
                    )
                }
            }
            savedPending -> showResult(
                getString(R.string.lens_result_saved_title), R.attr.colorWarning,
                getString(R.string.lens_result_saved_body, staffName.ifBlank { "this staff member" }),
            )
            failed != null -> showResult(getString(R.string.lens_result_failed_title), R.attr.colorError, failed.outcome.message)
            else -> b.lensResultCard.isVisible = false
        }

        b.lensUploadProgress.isVisible = uploading
        b.btnLensRecordAgain.isVisible = !uploading && !succeeded && !savedPending
        b.btnLensUpload.isEnabled = !uploading && (succeeded || savedPending || state.requiredDone)
        b.btnLensUpload.text = when {
            uploading -> getString(R.string.lens_uploading)
            succeeded || savedPending -> getString(R.string.lens_done)
            failed?.outcome?.action == LensErrors.Action.RETRY -> getString(R.string.lens_retry)
            failed?.outcome?.action == LensErrors.Action.CONTACT_ADMIN -> getString(R.string.lens_done)
            dryRun -> "Save ${state.photos.size} test photos"
            single != null -> getString(R.string.lens_retake_upload)
            !state.requiredDone -> getString(R.string.lens_upload_needs_required)
            else -> getString(R.string.lens_upload, state.photos.size)
        }
        val changed = (done != null && ((done.response.addedSamples ?: 0) > 0 || (single != null && succeeded))) || savedPending
        if (changed && !enrolled) {
            enrolled = true
            // The screen behind refreshes even if the person closes now.
            setFragmentResult(resultKey, Bundle().apply { putString(ARG_STAFF_ID, staffId) })
        }
    }

    private fun showResult(title: String, colorAttr: Int, body: String) {
        val b = _binding ?: return
        val extra = viewModel.state.value.otherFailures
        b.lensResultCard.isVisible = true
        b.tvLensResultTitle.text = title
        b.tvLensResultTitle.setTextColor(attrColor(colorAttr))
        b.tvLensResultBody.text = if (extra.isEmpty()) {
            body
        } else {
            body + "\n\n" + extra.joinToString("\n") { "• $it" }
        }
    }

    private fun renderPoseGrid(state: LensEnrollUiState, locked: Boolean) {
        val b = _binding ?: return
        val grid = b.lensPoseGrid
        val poses = gridPoses(state)
        if (grid.childCount != poses.size) {
            grid.removeAllViews()
            val gap = (8 * resources.displayMetrics.density).toInt()
            poses.forEachIndexed { index, _ ->
                val item = ItemLensPoseBinding.inflate(layoutInflater, grid, false)
                item.root.layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(index / 2), GridLayout.spec(index % 2, 1f),
                ).apply {
                    width = 0
                    setMargins(if (index % 2 == 0) 0 else gap / 2, gap, if (index % 2 == 0) gap / 2 else 0, 0)
                }
                grid.addView(item.root)
            }
        }
        poses.forEachIndexed { index, pose ->
            val item = ItemLensPoseBinding.bind(grid.getChildAt(index))
            val photo = state.photos[pose]
            item.tvLensPoseLabel.text = if (pose.required) pose.label else "${pose.label} · ${getString(R.string.lens_optional)}"
            val failure = state.failedByPose[pose]
            item.tvLensPoseStatus.text = when {
                failure != null -> failure
                photo != null -> qualityLine(photo)
                pose in state.skipped -> getString(R.string.lens_optional)
                else -> getString(R.string.lens_pose_missing)
            }
            item.tvLensPoseStatus.setTextColor(
                attrColor(if (failure != null) R.attr.colorError else R.attr.colorForegroundSecondary),
            )
            item.ivLensPoseOk.isVisible = photo != null && failure == null
            item.tvLensPoseEmpty.isVisible = photo == null
            item.btnLensPoseRetake.isEnabled = !locked
            item.btnLensPoseRetake.text = if (photo == null) getString(R.string.lens_capture_pose) else getString(R.string.lens_retake)
            item.btnLensPoseRetake.setOnClickListener { viewModel.retake(pose) }
            bindThumb(item, pose, photo)
        }
    }

    private fun qualityLine(photo: LensCapturedPhoto): String {
        val quality = when {
            photo.score >= 0.75 -> "Excellent"
            photo.score >= 0.6 -> "Good"
            else -> "Usable"
        }
        return "$quality · face ${photo.faceWidthPx} px"
    }

    private fun bindThumb(item: ItemLensPoseBinding, pose: LensPose, photo: LensCapturedPhoto?) {
        if (photo == null) {
            thumbs.remove(pose)
            item.ivLensPose.setImageDrawable(null)
            return
        }
        val cached = thumbs[pose]
        if (cached != null && cached.first === photo) {
            item.ivLensPose.setImageBitmap(cached.second)
            return
        }
        item.ivLensPose.setImageDrawable(null)
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                // Face crops are smaller than the full frame: sample less.
                val sample = when {
                    photo.width > 1000 -> 4
                    photo.width > 500 -> 2
                    else -> 1
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size, opts)
            } ?: return@launch
            thumbs[pose] = photo to bitmap
            if (viewModel.state.value.photos[pose] === photo) {
                val b = _binding ?: return@launch
                val index = gridPoses(viewModel.state.value).indexOf(pose)
                if (index < 0) return@launch
                ItemLensPoseBinding.bind(b.lensPoseGrid.getChildAt(index) ?: return@launch).ivLensPose.setImageBitmap(bitmap)
            }
        }
    }

    private fun onUploadClicked() {
        if (dryRun) {
            saveDryRun()
            return
        }
        val s = viewModel.state.value
        val done = s.upload as? LensUploadState.Done
        val failed = s.upload as? LensUploadState.Failed
        when {
            succeeded(s) -> finish()
            done != null -> viewModel.recordAgain()
            failed != null && LensErrors.savedForLater(failed.outcome) -> finish()
            failed?.outcome?.action == LensErrors.Action.CONTACT_ADMIN -> finish()
            failed?.outcome?.action == LensErrors.Action.RETAKE -> viewModel.recordAgain()
            else -> viewModel.upload(session.bearerToken, staffId)
        }
    }

    private fun confirmLeave() {
        val s = viewModel.state.value
        val unsaved = s.photos.isNotEmpty() && s.upload !is LensUploadState.Done
        if (s.upload is LensUploadState.Uploading) return
        if (!unsaved) {
            finish()
            return
        }
        AppBottomSheets.showConfirm(
            ctx = requireContext(),
            title = "Discard photos?",
            message = "The captured photos haven't been enrolled yet.",
            confirmLabel = "Discard",
            destructive = true,
            cancelLabel = "Keep photos",
        ) { finish() }
    }

    private fun finish() {
        stopCamera()
        if (parentFragmentManager.backStackEntryCount == 0) activity?.finish()
        else parentFragmentManager.popBackStack()
    }

    /**
     * Lens Test build: write each pose's JPEG (exactly what would be uploaded)
     * plus a line of capture stats to the app's files dir, then start again.
     * Pull with: adb pull /sdcard/Android/data/<pkg>/files/lens-test
     */
    private fun saveDryRun() {
        val photos = viewModel.state.value.photos
        if (photos.isEmpty()) return
        val dir = java.io.File(requireContext().getExternalFilesDir(null), "lens-test/" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()))
        runCatching {
            dir.mkdirs()
            val stats = StringBuilder()
            photos.forEach { (pose, p) ->
                java.io.File(dir, "${pose.key}.jpg").writeBytes(p.jpeg)
                stats.appendLine("${pose.key}: ${p.width}x${p.height} face=${p.faceWidthPx}px score=${"%.3f".format(p.score)} bytes=${p.jpeg.size}")
            }
            java.io.File(dir, "stats.txt").writeText(stats.toString())
        }.onSuccess {
            Floater.success("Saved ${photos.size} test photos (not uploaded)")
            viewModel.recordAgain()
        }.onFailure {
            Floater.error("Couldn't save the test photos")
        }
    }

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onDestroyView() {
        stopArrow()
        optionalSkipJob?.cancel()
        stopCamera()
        engine?.close()
        engine = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
        thumbs.clear()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val RESULT_KEY = "lens_enrolled"
        private const val ARG_STAFF_ID = "staffId"
        private const val ARG_NAME = "name"
        private const val ARG_CODE = "employeeCode"
        private const val ARG_EXISTING = "existingSamples"
        private const val ARG_DRY_RUN = "dryRun"
        private const val ARG_SINGLE_POSE = "singlePose"
        private const val ARG_REPLACE = "replace"
        private const val ARG_RESULT_KEY = "resultKey"
        private const val OPTIONAL_TIMEOUT_MS = 10_000L
        private const val HINT_HOLD_MS = 450L

        /**
         * Full head turn. [replace] starts with "Replace earlier photos" on
         * ("Retake all"). [resultKey]: fragment-result key sent when photos changed.
         */
        fun newInstance(
            staffId: String,
            name: String,
            employeeCode: String?,
            existingSamples: Int,
            replace: Boolean = false,
            resultKey: String = RESULT_KEY,
        ) = LensEnrollFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_STAFF_ID, staffId)
                putString(ARG_NAME, name)
                putString(ARG_CODE, employeeCode)
                putInt(ARG_EXISTING, existingSamples)
                putBoolean(ARG_REPLACE, replace)
                putString(ARG_RESULT_KEY, resultKey)
            }
        }

        /** Retake of one saved photo: the camera asks for just [pose]. */
        fun newSinglePose(staffId: String, name: String, employeeCode: String?, pose: LensPose, resultKey: String) =
            LensEnrollFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_STAFF_ID, staffId)
                    putString(ARG_NAME, name)
                    putString(ARG_CODE, employeeCode)
                    putInt(ARG_EXISTING, 0)
                    putString(ARG_SINGLE_POSE, pose.key)
                    putString(ARG_RESULT_KEY, resultKey)
                }
            }

        /** Lens Test build: no login, no upload (see saveDryRun). */
        fun newDryRun() = LensEnrollFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_STAFF_ID, "lens-test")
                putString(ARG_NAME, "Capture test")
                putString(ARG_CODE, "TEST")
                putInt(ARG_EXISTING, 0)
                putBoolean(ARG_DRY_RUN, true)
            }
        }
    }
}
