package com.manjugroups.m_connect.ui.issues

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.SheetCreateIssueBinding
import java.io.File

class CreateIssueBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetCreateIssueBinding? = null
    private val binding get() = _binding!!

    // Callback listener for issue creation
    interface OnIssueCreatedListener {
        fun onIssueCreated(title: String, description: String, audioPath: String?, audioDurationMs: Long)
    }

    private var listener: OnIssueCreatedListener? = null

    fun setOnIssueCreatedListener(listener: OnIssueCreatedListener) {
        this.listener = listener
    }

    // Audio recording & playback variables
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var audioFile: File? = null
    
    private var isRecording = false
    private var isPlayingPreview = false
    private var recordingStartTime = 0L
    private var audioDurationMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / 60000) % 60
                binding.tvAudioTimer.text = String.format("%02d:%02d", minutes, seconds)
                handler.postDelayed(this, 500)
            }
        }
    }

    // Permission launcher for record audio
    private val requestRecordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecordingFlow()
        } else {
            Toast.makeText(requireContext(), "Permission to record audio is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                // Fix background appearing at bottom/corners
                bottomSheet.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                
                // No drop shadow as requested
                bottomSheet.elevation = 0f
            }
        }
        return dialog
    }

    override fun getTheme(): Int {
        return R.style.CustomCameraBottomSheetTheme
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetCreateIssueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize UI status
        binding.tvAudioStatus.text = "Tap microphone to record voice note"

        // Handle microphone recording trigger
        binding.btnRecordAudio.setOnClickListener {
            checkPermissionAndRecord()
        }

        // Handle preview playback
        binding.btnPlayPreview.setOnClickListener {
            togglePreviewPlayback()
        }

        // Handle delete recording
        binding.btnDeletePreview.setOnClickListener {
            deleteRecordedAudio()
        }

        // Handle submit
        binding.btnSubmitIssue.setOnClickListener {
            submitIssue()
        }
    }

    private fun checkPermissionAndRecord() {
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            startRecordingFlow()
        } else {
            requestRecordAudioPermissionLauncher.launch(permission)
        }
    }

    private fun startRecordingFlow() {
        if (!isRecording) {
            // Start recording
            runCatching {
                audioFile = File(requireContext().cacheDir, "issue_voice_${System.currentTimeMillis()}.m4a")
                mediaRecorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    MediaRecorder(requireContext())
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(audioFile?.absolutePath)
                    prepare()
                    start()
                }

                isRecording = true
                recordingStartTime = System.currentTimeMillis()
                binding.tvAudioStatus.text = "Recording voice note..."
                binding.tvAudioStatus.setTextColor(android.graphics.Color.parseColor("#EF4444")) // Red
                binding.tvAudioTimer.visibility = View.VISIBLE
                binding.tvAudioTimer.text = "00:00"
                binding.ivRecordIcon.setImageResource(R.drawable.ic_chat_stop)
                handler.post(timerRunnable)
            }.onFailure {
                Toast.makeText(requireContext(), "Failed to start audio recording", Toast.LENGTH_SHORT).show()
                resetRecordingState()
            }
        } else {
            // Stop recording
            runCatching {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
            }
            mediaRecorder = null
            isRecording = false
            audioDurationMs = System.currentTimeMillis() - recordingStartTime
            handler.removeCallbacks(timerRunnable)

            binding.tvAudioStatus.text = "Voice note attached"
            binding.tvAudioStatus.setTextColor(android.graphics.Color.parseColor("#16A34A")) // Green
            binding.ivRecordIcon.setImageResource(R.drawable.ic_chat_mic)
            binding.btnRecordAudio.visibility = View.GONE
            binding.btnPlayPreview.visibility = View.VISIBLE
            binding.btnDeletePreview.visibility = View.VISIBLE
        }
    }

    private fun togglePreviewPlayback() {
        val file = audioFile
        if (file == null || !file.exists()) return

        if (!isPlayingPreview) {
            runCatching {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener {
                        stopPreviewPlayback()
                    }
                }
                isPlayingPreview = true
                binding.btnPlayPreview.setImageResource(R.drawable.ic_chat_pause)
                binding.tvAudioStatus.text = "Playing voice note..."
            }.onFailure {
                Toast.makeText(requireContext(), "Failed to play audio preview", Toast.LENGTH_SHORT).show()
                stopPreviewPlayback()
            }
        } else {
            stopPreviewPlayback()
        }
    }

    private fun stopPreviewPlayback() {
        runCatching {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        }
        mediaPlayer = null
        isPlayingPreview = false
        binding.btnPlayPreview.setImageResource(R.drawable.ic_chat_media_play)
        binding.tvAudioStatus.text = "Voice note attached"
    }

    private fun deleteRecordedAudio() {
        stopPreviewPlayback()
        audioFile?.delete()
        audioFile = null
        audioDurationMs = 0L
        resetRecordingState()
    }

    private fun resetRecordingState() {
        isRecording = false
        mediaRecorder = null
        handler.removeCallbacks(timerRunnable)
        binding.tvAudioStatus.text = "Tap microphone to record voice note"
        binding.tvAudioStatus.setTextColor(android.graphics.Color.parseColor("#64748B")) // Slate/Gray
        binding.tvAudioTimer.visibility = View.GONE
        binding.ivRecordIcon.setImageResource(R.drawable.ic_chat_mic)
        binding.btnRecordAudio.visibility = View.VISIBLE
        binding.btnPlayPreview.visibility = View.GONE
        binding.btnDeletePreview.visibility = View.GONE
    }

    private fun submitIssue() {
        val title = binding.etIssueTitle.text.toString().trim()
        val desc = binding.etIssueDesc.text.toString().trim()

        if (title.isEmpty()) {
            Toast.makeText(requireContext(), "Title is compulsory", Toast.LENGTH_SHORT).show()
            return
        }

        stopPreviewPlayback()
        listener?.onIssueCreated(title, desc, audioFile?.absolutePath, audioDurationMs)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(timerRunnable)
        runCatching {
            mediaRecorder?.release()
            mediaPlayer?.release()
        }
        _binding = null
    }
}
