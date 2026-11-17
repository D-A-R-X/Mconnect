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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
            parentFragmentManager.popBackStack()
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
        session = SessionManager(requireContext())
        if (!LensStaffFragment.isAvailable(session) || staffId.isBlank()) {
            parentFragmentManager.popBackStack()
            return
        }
        // Both top bars pad themselves by the status-bar inset (full-bleed).
        com.manjugroups.m_connect.ui.common.StatusBarInsets.padHeader(binding.lensCaptureTop)
        com.manjugroups.m_connect.ui.common.StatusBarInsets.padHeader(binding.lensReviewHeader, fixedHeight = true)
        viewModel.initReplace(existingSamples)

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
                provider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                eng.pending = viewModel.state.value.pending
                eng.paused = false
                cameraBound = true
            } catch (e: Exception) {
                Floater.error(getString(R.string.lens_camera_failed))
            }
        }, ContextCompat.getMainExecutor(requireContext()))
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
        b.btnLensReview.isEnabled = state.requiredDone && state.retakeOnly == null
        b.btnLensReview.isVisible = state.retakeOnly == null
        val target = state.target
        b.btnLensSkip.isVisible = target != null && !target.required && state.retakeOnly == null

        paintSegments(state, live.value)
        if (target != lastTarget) {
            lastTarget = target
            scheduleOptionalSkip(target, state.retakeOnly != null)
        }
        renderPrompt(target, live.value)
    }

    private fun renderLive(state: LensLiveState?) {
        val b = _binding ?: return
        val vm = viewModel.state.value
        if (vm.reviewing) return
        val hint = when {
            state == null -> null
            !state.faceFound -> LensHint.NO_FACE
            else -> state.hint
        }
        if (hint != null) {
            b.tvLensHint.text = hint.text
            b.tvLensHint.visibility = View.VISIBLE
        } else {
            b.tvLensHint.visibility = View.INVISIBLE
        }
        b.lensGuide.setFaceOk(state?.faceFound == true && state.hint == null)
        paintSegments(vm, state)
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
            target.targetYaw > 0 -> -1 // person's left = screen left (mirrored preview)
            else -> 1
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

    private fun renderReview(state: LensEnrollUiState) {
        val b = _binding ?: return
        val uploading = state.upload is LensUploadState.Uploading
        val done = state.upload as? LensUploadState.Done
        val failed = state.upload as? LensUploadState.Failed
        val succeeded = done != null && done.response.needsRetake != true && (done.response.addedSamples ?: 0) >= 3

        renderPoseGrid(state, locked = uploading || succeeded)

        b.lensReplaceRow.isVisible = existingSamples > 0 && !succeeded
        b.tvLensReplaceBody.text = getString(R.string.lens_replace_body, existingSamples)
        if (b.swLensReplace.isChecked != state.replace) b.swLensReplace.isChecked = state.replace
        b.swLensReplace.isEnabled = !uploading

        // Result card.
        when {
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
            failed != null -> showResult(getString(R.string.lens_result_failed_title), R.attr.colorError, failed.outcome.message)
            else -> b.lensResultCard.isVisible = false
        }

        b.lensUploadProgress.isVisible = uploading
        b.btnLensRecordAgain.isVisible = !uploading && !succeeded
        b.btnLensUpload.isEnabled = !uploading && (succeeded || state.requiredDone)
        b.btnLensUpload.text = when {
            uploading -> getString(R.string.lens_uploading)
            succeeded -> getString(R.string.lens_done)
            failed?.outcome?.action == LensErrors.Action.RETRY -> getString(R.string.lens_retry)
            failed?.outcome?.action == LensErrors.Action.CONTACT_ADMIN -> getString(R.string.lens_done)
            else -> getString(R.string.lens_upload, state.photos.size)
        }
        if (done != null && (done.response.addedSamples ?: 0) > 0 && !enrolled) {
            enrolled = true
            // The list behind refreshes its badge even if the person closes now.
            setFragmentResult(RESULT_KEY, Bundle().apply { putString(ARG_STAFF_ID, staffId) })
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
        val poses = LensPose.guidedOrder
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
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeByteArray(photo.jpeg, 0, photo.jpeg.size, opts)
            } ?: return@launch
            thumbs[pose] = photo to bitmap
            if (viewModel.state.value.photos[pose] === photo) {
                val b = _binding ?: return@launch
                val index = LensPose.guidedOrder.indexOf(pose)
                ItemLensPoseBinding.bind(b.lensPoseGrid.getChildAt(index) ?: return@launch).ivLensPose.setImageBitmap(bitmap)
            }
        }
    }

    private fun onUploadClicked() {
        val s = viewModel.state.value
        val done = s.upload as? LensUploadState.Done
        val failed = s.upload as? LensUploadState.Failed
        when {
            done != null && done.response.needsRetake != true && (done.response.addedSamples ?: 0) >= 3 -> finish()
            done != null -> viewModel.recordAgain()
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Discard photos?")
            .setMessage("The captured photos haven't been enrolled yet.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Discard") { _, _ -> finish() }
            .show()
    }

    private fun finish() {
        stopCamera()
        parentFragmentManager.popBackStack()
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
        private const val OPTIONAL_TIMEOUT_MS = 10_000L

        fun newInstance(staffId: String, name: String, employeeCode: String?, existingSamples: Int) =
            LensEnrollFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_STAFF_ID, staffId)
                    putString(ARG_NAME, name)
                    putString(ARG_CODE, employeeCode)
                    putInt(ARG_EXISTING, existingSamples)
                }
            }
    }
}
