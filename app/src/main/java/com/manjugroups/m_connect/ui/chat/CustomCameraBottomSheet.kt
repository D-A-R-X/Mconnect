package com.manjugroups.m_connect.ui.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CustomCameraBottomSheet : BottomSheetDialogFragment() {

    interface CameraResultListener {
        fun onMediaCaptured(uri: Uri, isVideo: Boolean)
        fun onGalleryClicked()
    }

    private var listener: CameraResultListener? = null
    fun setListener(listener: CameraResultListener) {
        this.listener = listener
    }

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var isRecording = false
    private var orientationEventListener: android.view.OrientationEventListener? = null

    private var cameraLensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_AUTO // AUTO, ON, OFF
    private var activeMode = Mode.PHOTO // PHOTO, VIDEO
    private var activeZoom = 1.0f // 0.5, 1.0, 2.0

    private var camera: Camera? = null
    


    private val mediaActionSound = android.media.MediaActionSound()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var recordingSeconds = 0
    private val recordingProgressRunnable = object : Runnable {
        override fun run() {
            recordingSeconds++
            val minutes = recordingSeconds / 60
            val seconds = recordingSeconds % 60
            tvSubtitle.text = String.format("Recording... %02d:%02d", minutes, seconds)
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun safeToast(message: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
    }

    private fun playSound(soundId: Int) {
        runCatching {
            mediaActionSound.play(soundId)
        }
    }

    // UI elements
    private lateinit var viewFinder: PreviewView
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnFlash: TextView
    private lateinit var btnCapture: View
    private lateinit var imgShutter: ImageView
    private lateinit var btnClose: View
    private lateinit var btnGallery: View
    private lateinit var btnSwitch: View
    


    private lateinit var tabPhoto: View
    private lateinit var tvTabPhoto: TextView
    private lateinit var dotTabPhoto: View

    private lateinit var tabVideo: View
    private lateinit var tvTabVideo: TextView
    private lateinit var dotTabVideo: View

    private lateinit var tvZoom05: TextView
    private lateinit var tvZoom1x: TextView
    private lateinit var tvZoom2x: TextView

    // Post-capture review overlay
    private lateinit var reviewOverlay: View
    private lateinit var imgReviewPhoto: ImageView
    private lateinit var videoReview: VideoView
    private lateinit var btnReviewRetake: View
    private lateinit var btnReviewConfirm: View
    private lateinit var tvReviewConfirm: TextView
    private lateinit var cameraHeader: View
    private lateinit var modeTabs: View
    private lateinit var bottomControls: View
    private lateinit var bottomTip: View
    private var reviewUri: Uri? = null
    private var reviewIsVideo = false

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            switchToVideoMode()
        } else {
            safeToast("Audio permission required for recording video")
            switchToPhotoMode()
        }
    }

    private var pendingCameraMode: Mode = Mode.PHOTO

    enum class Mode { PHOTO, VIDEO }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.CustomCameraBottomSheetTheme)
        mediaActionSound.load(android.media.MediaActionSound.SHUTTER_CLICK)
        mediaActionSound.load(android.media.MediaActionSound.START_VIDEO_RECORDING)
        mediaActionSound.load(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = false // Keep fixed for camera preview stability
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_custom_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Bind views
        viewFinder = view.findViewById(R.id.viewFinder)
        tvTitle = view.findViewById(R.id.tvCameraSheetTitle)
        tvSubtitle = view.findViewById(R.id.tvCameraSheetSubtitle)
        btnFlash = view.findViewById(R.id.btnCameraFlash)
        btnCapture = view.findViewById(R.id.btnCameraCapture)
        imgShutter = view.findViewById(R.id.imgCaptureShutter)
        btnClose = view.findViewById(R.id.btnCameraClose)
        btnGallery = view.findViewById(R.id.btnCameraGallery)
        btnSwitch = view.findViewById(R.id.btnCameraSwitch)
        tabPhoto = view.findViewById(R.id.tabPhotoMode)
        tvTabPhoto = view.findViewById(R.id.tvTabPhoto)
        dotTabPhoto = view.findViewById(R.id.dotTabPhoto)

        tabVideo = view.findViewById(R.id.tabVideoMode)
        tvTabVideo = view.findViewById(R.id.tvTabVideo)
        dotTabVideo = view.findViewById(R.id.dotTabVideo)

        tvZoom05 = view.findViewById(R.id.tvZoom05)
        tvZoom1x = view.findViewById(R.id.tvZoom1x)
        tvZoom2x = view.findViewById(R.id.tvZoom2x)

        reviewOverlay = view.findViewById(R.id.reviewOverlay)
        imgReviewPhoto = view.findViewById(R.id.imgReviewPhoto)
        videoReview = view.findViewById(R.id.videoReview)
        // Render the review clip's surface above the live camera preview's
        // surface (both are SurfaceViews); regular views — the Retake/Use
        // bar — still composite on top. Must be set before the surface is
        // created, i.e. while the VideoView is still GONE.
        videoReview.setZOrderMediaOverlay(true)
        btnReviewRetake = view.findViewById(R.id.btnReviewRetake)
        btnReviewConfirm = view.findViewById(R.id.btnReviewConfirm)
        tvReviewConfirm = view.findViewById(R.id.tvReviewConfirm)
        cameraHeader = view.findViewById(R.id.cameraHeader)
        modeTabs = view.findViewById(R.id.modeTabs)
        bottomControls = view.findViewById(R.id.bottomControls)
        bottomTip = view.findViewById(R.id.bottomTip)

        // Set listeners
        btnClose.setOnClickListener { dismiss() }
        btnGallery.setOnClickListener {
            listener?.onGalleryClicked()
            dismiss()
        }
        btnSwitch.setOnClickListener {
            cameraLensFacing = if (cameraLensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            startCamera()
        }
        btnFlash.setOnClickListener { toggleFlash() }
        btnCapture.setOnClickListener { handleCapture() }

        tabPhoto.setOnClickListener { switchToPhotoMode() }
        tabVideo.setOnClickListener { checkAudioPermissionAndSwitchToVideo() }

        tvZoom05.setOnClickListener { setZoom(0.5f, tvZoom05) }
        tvZoom1x.setOnClickListener { setZoom(1.0f, tvZoom1x) }
        tvZoom2x.setOnClickListener { setZoom(2.0f, tvZoom2x) }

        btnReviewRetake.setOnClickListener { exitReview(discard = true) }
        btnReviewConfirm.setOnClickListener { confirmReview() }

        // Apply initial visual state based on mode
        if (pendingCameraMode == Mode.VIDEO) {
            switchToVideoMode()
        } else {
            switchToPhotoMode()
        }

        // Set up OrientationEventListener to handle orientation changes
        orientationEventListener = object : android.view.OrientationEventListener(requireContext()) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> android.view.Surface.ROTATION_270
                    in 135 until 225 -> android.view.Surface.ROTATION_180
                    in 225 until 315 -> android.view.Surface.ROTATION_90
                    else -> android.view.Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
                try {
                    videoCapture?.targetRotation = rotation
                } catch (e: Exception) {
                    // Ignore if target rotation not supported
                }
            }
        }
        orientationEventListener?.enable()

        // Start Camera
        startCamera()
    }

    fun setInitialMode(isVideo: Boolean) {
        pendingCameraMode = if (isVideo) Mode.VIDEO else Mode.PHOTO
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        cameraProvider.unbindAll()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(cameraLensFacing)
            .build()

        val rotation = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                requireContext().display?.rotation ?: viewFinder.display?.rotation ?: android.view.Surface.ROTATION_0
            } else {
                val windowManager = requireContext().getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
                windowManager.defaultDisplay.rotation
            }
        } catch (e: Exception) {
            viewFinder.display?.rotation ?: android.view.Surface.ROTATION_0
        }

        preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also {
                it.surfaceProvider = viewFinder.surfaceProvider
            }

        try {
            if (activeMode == Mode.PHOTO) {
                imageCapture = ImageCapture.Builder()
                    .setFlashMode(flashMode)
                    .setTargetRotation(rotation)
                    .build()
                    
                camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } else {
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                    .build()
                videoCapture = VideoCapture.Builder(recorder)
                    .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
                    .build().apply {
                        targetRotation = rotation
                    }
                camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture!!
                )
            }
            
            // Re-apply current zoom
            applyZoomRatio()

            // Re-apply current torch state if Flash is on
            val enableTorch = (flashMode == ImageCapture.FLASH_MODE_ON && activeMode == Mode.PHOTO)
            camera?.cameraControl?.enableTorch(enableTorch)

        } catch (exc: Exception) {
            Toast.makeText(requireContext(), "Failed to bind camera", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleFlash() {
        if (activeMode == Mode.VIDEO) {
            Toast.makeText(requireContext(), "Flash not supported in Video mode", Toast.LENGTH_SHORT).show()
            return
        }
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
        btnFlash.text = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "⚡A"
            ImageCapture.FLASH_MODE_ON -> "⚡On"
            else -> "⚡Off"
        }
        imageCapture?.flashMode = flashMode

        val enableTorch = (flashMode == ImageCapture.FLASH_MODE_ON)
        camera?.cameraControl?.enableTorch(enableTorch)
    }

    private fun setZoom(zoom: Float, selectedPill: TextView) {
        activeZoom = zoom
        applyZoomRatio()

        // Reset zoom pills styling
        val pills = listOf(tvZoom05, tvZoom1x, tvZoom2x)
        pills.forEach { pill ->
            if (pill == selectedPill) {
                pill.setBackgroundResource(R.drawable.bg_home_new_action_circle)
                pill.backgroundTintList = ContextCompat.getColorStateList(requireContext(), android.R.color.white)
                pill.setTextColor(Color.parseColor("#101828"))
                pill.typeface = android.graphics.Typeface.DEFAULT_BOLD
            } else {
                pill.background = null
                pill.setTextColor(Color.WHITE)
                pill.typeface = android.graphics.Typeface.DEFAULT
            }
        }
    }

    private fun applyZoomRatio() {
        camera?.cameraControl?.setZoomRatio(activeZoom)
    }

    private fun switchToPhotoMode() {
        activeMode = Mode.PHOTO
        isRecording = false

        tvTitle.text = "Camera"
        tvSubtitle.text = "Take a photo"
        imgShutter.setImageResource(R.drawable.ic_chat_camera)
        btnCapture.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#38A612"))
        
        tvTabPhoto.setTextColor(Color.parseColor("#38A612"))
        dotTabPhoto.visibility = View.VISIBLE
        tvTabVideo.setTextColor(Color.parseColor("#667085"))
        dotTabVideo.visibility = View.INVISIBLE

        bindCameraUseCases()
    }

    private fun checkAudioPermissionAndSwitchToVideo() {
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            switchToVideoMode()
        } else {
            audioPermissionLauncher.launch(permission)
        }
    }

    private fun switchToVideoMode() {
        activeMode = Mode.VIDEO

        tvTitle.text = "Video"
        tvSubtitle.text = "Record a video"
        imgShutter.setImageResource(R.drawable.ic_chat_video)
        btnCapture.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#38A612"))
        
        tvTabVideo.setTextColor(Color.parseColor("#38A612"))
        dotTabVideo.visibility = View.VISIBLE
        tvTabPhoto.setTextColor(Color.parseColor("#667085"))
        dotTabPhoto.visibility = View.INVISIBLE

        bindCameraUseCases()
    }

    private fun handleCapture() {
        if (activeMode == Mode.PHOTO) {
            takePhoto()
        } else {
            toggleVideoRecording()
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val photoDir = File(requireContext().cacheDir, "chat_photos").apply { mkdirs() }
        val photoFile = File(photoDir, "photo_${System.currentTimeMillis()}.jpg")

        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = (cameraLensFacing == CameraSelector.LENS_FACING_FRONT)
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
            .setMetadata(metadata)
            .build()

        playSound(android.media.MediaActionSound.SHUTTER_CLICK)

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    // Don't return the photo yet — show it in the review
                    // overlay so the user can confirm (tick) or retake.
                    enterReview(Uri.fromFile(photoFile), isVideo = false)
                }

                override fun onError(exception: ImageCaptureException) {
                    safeToast("Failed to capture photo")
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun toggleVideoRecording() {
        val videoCapture = videoCapture ?: return
        if (isRecording) {
            // Stop recording
            playSound(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
            mainHandler.removeCallbacks(recordingProgressRunnable)
            activeRecording?.stop()
            activeRecording = null
            isRecording = false
            imgShutter.setImageResource(R.drawable.ic_chat_video)
            btnCapture.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#38A612"))
        } else {
            // Start recording
            val videoDir = File(requireContext().cacheDir, "chat_videos").apply { mkdirs() }
            val videoFile = File(videoDir, "video_${System.currentTimeMillis()}.mp4")

            val outputOptions = FileOutputOptions.Builder(videoFile).build()

            isRecording = true
            imgShutter.setImageResource(R.drawable.ic_trip_stop_white)
            btnCapture.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F04438"))

            playSound(android.media.MediaActionSound.START_VIDEO_RECORDING)

            activeRecording = videoCapture.output
                .prepareRecording(requireContext(), outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            recordingSeconds = 0
                            tvSubtitle.text = "Recording... 00:00"
                            mainHandler.removeCallbacks(recordingProgressRunnable)
                            mainHandler.postDelayed(recordingProgressRunnable, 1000)
                        }
                        is VideoRecordEvent.Finalize -> {
                            mainHandler.removeCallbacks(recordingProgressRunnable)
                            if (!recordEvent.hasError()) {
                                // Reset the shutter back to a clean video-ready
                                // state so a Retake resumes correctly, then show
                                // the captured clip in the review overlay.
                                isRecording = false
                                imgShutter.setImageResource(R.drawable.ic_chat_video)
                                btnCapture.backgroundTintList =
                                    android.content.res.ColorStateList.valueOf(Color.parseColor("#38A612"))
                                enterReview(Uri.fromFile(videoFile), isVideo = true)
                            } else {
                                safeToast("Failed to save video")
                                isRecording = false
                                imgShutter.setImageResource(R.drawable.ic_chat_video)
                                btnCapture.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#38A612"))
                                tvSubtitle.text = "Record a video"
                            }
                        }
                    }
                }
        }
    }

    // ── Post-capture review ──────────────────────────────────────────────

    /** Freeze on the just-captured media with Retake / Use actions instead
     *  of returning it straight away. */
    private fun enterReview(uri: Uri, isVideo: Boolean) {
        if (!isAdded) return
        reviewUri = uri
        reviewIsVideo = isVideo

        // Kill the torch so it doesn't stay lit behind the overlay.
        camera?.cameraControl?.enableTorch(false)

        // Repurpose the header and hide the live-camera chrome.
        tvTitle.text = "Preview"
        tvSubtitle.text = if (isVideo) "Use this video or retake" else "Use this photo or retake"
        btnFlash.visibility = View.INVISIBLE
        modeTabs.visibility = View.GONE
        bottomControls.visibility = View.GONE
        bottomTip.visibility = View.GONE

        tvReviewConfirm.text = if (isVideo) "Use Video" else "Use Photo"
        if (isVideo) {
            // The live CameraX preview and the VideoView are both
            // SurfaceViews; some devices refuse to hand the reviewed clip a
            // playable surface while the camera pipeline is still streaming
            // underneath ("Can't play this video."). Release the camera for
            // the duration of the review — exitReview rebinds it.
            cameraProvider?.unbindAll()
            viewFinder.visibility = View.INVISIBLE

            imgReviewPhoto.visibility = View.GONE
            videoReview.visibility = View.VISIBLE
            // Consume playback errors ourselves: without a listener VideoView
            // pops the system "Can't play this video." dialog. Retry once
            // (the surface may not have been ready on the first attempt);
            // the recorded FILE itself is fine — Finalize succeeded — so the
            // tick still hands over a working video even if preview fails.
            var retriedPlayback = false
            videoReview.setOnErrorListener { _, what, extra ->
                android.util.Log.e(
                    "CustomCamera", "Review playback error what=$what extra=$extra"
                )
                if (!retriedPlayback) {
                    retriedPlayback = true
                    videoReview.postDelayed({
                        if (isAdded && reviewIsVideo && reviewOverlay.visibility == View.VISIBLE) {
                            videoReview.setVideoURI(uri)
                        }
                    }, 200)
                } else if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        "Preview unavailable — the video is saved; tap ✓ to use it",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                true
            }
            videoReview.setOnPreparedListener { mp ->
                mp.isLooping = true
                videoReview.start()
            }
            videoReview.setVideoURI(uri)
        } else {
            videoReview.visibility = View.GONE
            imgReviewPhoto.visibility = View.VISIBLE
            // Coil downsamples to the view, avoiding OOM on full-res captures.
            imgReviewPhoto.load(uri)
        }
        reviewOverlay.visibility = View.VISIBLE
    }

    /** Leave the review overlay. [discard]=true throws the capture away and
     *  resumes the live camera; false leaves the file in place for confirm. */
    private fun exitReview(discard: Boolean) {
        runCatching { if (videoReview.isPlaying) videoReview.stopPlayback() }
        videoReview.visibility = View.GONE
        imgReviewPhoto.visibility = View.GONE
        imgReviewPhoto.setImageDrawable(null)
        reviewOverlay.visibility = View.GONE

        // Video reviews release the camera (see enterReview) — bring the
        // live preview back before restoring the chrome.
        if (reviewIsVideo) {
            viewFinder.visibility = View.VISIBLE
            bindCameraUseCases()
        }

        // Restore the live-camera chrome.
        tvTitle.text = if (activeMode == Mode.VIDEO) "Video" else "Camera"
        tvSubtitle.text = if (activeMode == Mode.VIDEO) "Record a video" else "Take a photo"
        btnFlash.visibility = View.VISIBLE
        modeTabs.visibility = View.VISIBLE
        bottomControls.visibility = View.VISIBLE
        bottomTip.visibility = View.VISIBLE

        if (discard) {
            reviewUri?.path?.let { runCatching { File(it).delete() } }
            // Re-light the torch if flash was ON in photo mode.
            val enableTorch = (flashMode == ImageCapture.FLASH_MODE_ON && activeMode == Mode.PHOTO)
            camera?.cameraControl?.enableTorch(enableTorch)
        }
        reviewUri = null
    }

    /** Confirm (tick): hand the reviewed media to the caller and close. */
    private fun confirmReview() {
        val uri = reviewUri ?: return
        runCatching { if (videoReview.isPlaying) videoReview.stopPlayback() }
        listener?.onMediaCaptured(uri, reviewIsVideo)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        runCatching {
            if (this::videoReview.isInitialized && videoReview.isPlaying) videoReview.stopPlayback()
        }
        orientationEventListener?.disable()
        orientationEventListener = null
        cameraExecutor.shutdown()
        mainHandler.removeCallbacks(recordingProgressRunnable)
        mediaActionSound.release()
    }


}
