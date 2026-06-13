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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
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

    private var cameraLensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_AUTO // AUTO, ON, OFF
    private var activeMode = Mode.PHOTO // PHOTO, VIDEO
    private var activeZoom = 1.0f // 0.5, 1.0, 2.0

    private var camera: Camera? = null

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

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            switchToVideoMode()
        } else {
            Toast.makeText(requireContext(), "Audio permission required for recording video", Toast.LENGTH_SHORT).show()
            switchToPhotoMode()
        }
    }

    private var pendingCameraMode: Mode = Mode.PHOTO

    enum class Mode { PHOTO, VIDEO }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.CustomCameraBottomSheetTheme)
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

        // Apply initial visual state based on mode
        if (pendingCameraMode == Mode.VIDEO) {
            switchToVideoMode()
        } else {
            switchToPhotoMode()
        }

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

        preview = Preview.Builder().build().also {
            it.surfaceProvider = viewFinder.surfaceProvider
        }

        try {
            if (activeMode == Mode.PHOTO) {
                imageCapture = ImageCapture.Builder()
                    .setFlashMode(flashMode)
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
                videoCapture = VideoCapture.withOutput(recorder)
                camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    videoCapture
                )
            }
            
            // Re-apply current zoom
            applyZoomRatio()

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
        btnCapture.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#12B76A"))
        
        tvTabPhoto.setTextColor(Color.parseColor("#12B76A"))
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
        btnCapture.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#12B76A"))
        
        tvTabVideo.setTextColor(Color.parseColor("#12B76A"))
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

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = Uri.fromFile(photoFile)
                    listener?.onMediaCaptured(uri, isVideo = false)
                    dismiss()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(requireContext(), "Failed to capture photo", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun toggleVideoRecording() {
        val videoCapture = videoCapture ?: return
        if (isRecording) {
            // Stop recording
            activeRecording?.stop()
            activeRecording = null
            isRecording = false
            imgShutter.setImageResource(R.drawable.ic_chat_video)
            btnCapture.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#12B76A"))
        } else {
            // Start recording
            val videoDir = File(requireContext().cacheDir, "chat_videos").apply { mkdirs() }
            val videoFile = File(videoDir, "video_${System.currentTimeMillis()}.mp4")

            val outputOptions = FileOutputOptions.Builder(videoFile).build()

            isRecording = true
            imgShutter.setImageResource(R.drawable.ic_trip_stop_white)
            btnCapture.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F04438"))

            activeRecording = videoCapture.output
                .prepareRecording(requireContext(), outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            tvSubtitle.text = "Recording... 00:00"
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (!recordEvent.hasError()) {
                                val uri = Uri.fromFile(videoFile)
                                listener?.onMediaCaptured(uri, isVideo = true)
                                dismiss()
                            } else {
                                Toast.makeText(requireContext(), "Failed to save video", Toast.LENGTH_SHORT).show()
                                isRecording = false
                                imgShutter.setImageResource(R.drawable.ic_chat_video)
                                btnCapture.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#12B76A"))
                                tvSubtitle.text = "Record a video"
                            }
                        }
                    }
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
    }
}
