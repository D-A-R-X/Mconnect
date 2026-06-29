package com.manjugroups.m_connect.ui.issues

import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentIssuesBinding
import com.manjugroups.m_connect.databinding.ItemIssueCardBinding
import com.manjugroups.m_connect.ui.common.navigateUp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IssuesFragment : Fragment() {

    private var _binding: FragmentIssuesBinding? = null
    private val binding get() = _binding!!

    // Local issue data model with timestamp
    data class IssueData(
        val title: String,
        val description: String,
        val audioPath: String?,
        val audioDurationMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val issuesList = mutableListOf<IssueData>()

    // Global audio player tracking for list cards with waveform progress
    private var activeMediaPlayer: MediaPlayer? = null
    private var activeAudioPath: String? = null
    private var activePlayButton: ImageView? = null
    private var activeWaveformLayout: LinearLayout? = null
    private var activeCurrentTimeText: TextView? = null
    private var activeTotalTimeText: TextView? = null
    private var activeDurationMs: Long = 0L

    private val playHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            activeMediaPlayer?.let { player ->
                if (player.isPlaying) {
                    val currentPos = player.currentPosition
                    val duration = player.duration.coerceAtLeast(1)
                    
                    // Update Waveform bars
                    activeWaveformLayout?.let { waveform ->
                        val barCount = waveform.childCount
                        val progressPercent = (currentPos * 100) / duration
                        val activeBarIndex = (progressPercent * barCount) / 100
                        for (i in 0 until barCount) {
                            val bar = waveform.getChildAt(i)
                            if (i <= activeBarIndex) {
                                bar.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#0B61CA"))
                            } else {
                                bar.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E2E8F0"))
                            }
                        }
                    }

                    // Format elapsed time
                    val elapsedSec = currentPos / 1000
                    activeCurrentTimeText?.text = String.format(
                        Locale.US,
                        "%02d:%02d",
                        elapsedSec / 60, elapsedSec % 60
                    )

                    playHandler.postDelayed(this, 150)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIssuesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Status bar margin / padding inset handling to make battery/clock visible
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootIssuesLayout) { v, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(
                v.paddingLeft,
                topInset,
                v.paddingRight,
                v.paddingBottom
            )
            insets
        }

        // Back navigation
        binding.btnIssuesBack.setOnClickListener {
            stopActivePlayback()
            navigateUp()
        }

        // Show Create Issue Bottom Sheet
        binding.btnCreateIssue.setOnClickListener {
            val bottomSheet = CreateIssueBottomSheet()
            bottomSheet.setOnIssueCreatedListener(object : CreateIssueBottomSheet.OnIssueCreatedListener {
                override fun onIssueCreated(
                    title: String,
                    description: String,
                    audioPath: String?,
                    audioDurationMs: Long
                ) {
                    issuesList.add(0, IssueData(title, description, audioPath, audioDurationMs))
                    renderIssues()
                }
            })
            bottomSheet.show(parentFragmentManager, "CreateIssueBottomSheet")
        }

        // Search issues filtering
        binding.etSearchIssues.setOnEditorActionListener { _, _, _ ->
            performSearch(binding.etSearchIssues.text.toString().trim())
            true
        }

        // Listen to text changes for real-time search filtering
        binding.etSearchIssues.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                performSearch(s.toString().trim())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Initial render
        renderIssues()
    }

    private fun renderIssues(filterList: List<IssueData> = issuesList) {
        if (_binding == null) return

        if (filterList.isEmpty()) {
            binding.layoutEmptyState.visibility = View.VISIBLE
            binding.scrollViewIssues.visibility = View.GONE
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.scrollViewIssues.visibility = View.VISIBLE

            binding.issuesContainer.removeAllViews()
            val inflater = LayoutInflater.from(requireContext())
            val sdf = SimpleDateFormat("Today, hh:mm a", Locale.US)

            for (issue in filterList) {
                val cardBinding = ItemIssueCardBinding.inflate(inflater, binding.issuesContainer, false)

                cardBinding.tvIssueCardTitle.text = issue.title
                cardBinding.tvIssueTime.text = sdf.format(Date(issue.timestamp))

                if (issue.description.isNotEmpty()) {
                    cardBinding.tvIssueCardDesc.text = issue.description
                    cardBinding.layoutDescTag.visibility = View.VISIBLE
                } else {
                    cardBinding.layoutDescTag.visibility = View.GONE
                }

                // Setup audio note player if attached
                val path = issue.audioPath
                if (path != null && File(path).exists()) {
                    cardBinding.layoutAudioPlayer.visibility = View.VISIBLE
                    
                    // Format duration
                    val totalSec = issue.audioDurationMs / 1000
                    cardBinding.tvAudioTotalTime.text = String.format(
                        Locale.US,
                        "%02d:%02d",
                        totalSec / 60, totalSec % 60
                    )
                    cardBinding.tvAudioCurrentTime.text = "00:00"

                    // If this specific card matches the currently playing path, restore UI state
                    if (activeAudioPath == path && activeMediaPlayer?.isPlaying == true) {
                        cardBinding.btnPlayAudio.setImageResource(R.drawable.ic_chat_pause)
                        activePlayButton = cardBinding.btnPlayAudio
                        activeWaveformLayout = cardBinding.layoutWaveform
                        activeCurrentTimeText = cardBinding.tvAudioCurrentTime
                        activeTotalTimeText = cardBinding.tvAudioTotalTime
                    } else {
                        cardBinding.btnPlayAudio.setImageResource(R.drawable.ic_chat_media_play)
                        // Reset waveform bars tint
                        val waveform = cardBinding.layoutWaveform
                        for (i in 0 until waveform.childCount) {
                            waveform.getChildAt(i).backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E2E8F0"))
                        }
                    }

                    cardBinding.btnPlayAudio.setOnClickListener {
                        togglePlayAudio(
                            path,
                            issue.audioDurationMs,
                            cardBinding.btnPlayAudio,
                            cardBinding.layoutWaveform,
                            cardBinding.tvAudioCurrentTime,
                            cardBinding.tvAudioTotalTime
                        )
                    }
                } else {
                    cardBinding.layoutAudioPlayer.visibility = View.GONE
                }

                binding.issuesContainer.addView(cardBinding.root)
            }
        }
    }

    private fun performSearch(query: String) {
        if (query.isEmpty()) {
            renderIssues(issuesList)
        } else {
            val filtered = issuesList.filter {
                it.title.contains(query, ignoreCase = true) ||
                        it.description.contains(query, ignoreCase = true)
            }
            renderIssues(filtered)
        }
    }

    private fun togglePlayAudio(
        path: String,
        durationMs: Long,
        playButton: ImageView,
        waveformLayout: LinearLayout,
        currentTimeText: TextView,
        totalTimeText: TextView
    ) {
        if (activeAudioPath == path && activeMediaPlayer != null) {
            // Already active. Either pause or resume.
            activeMediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    playButton.setImageResource(R.drawable.ic_chat_media_play)
                    playHandler.removeCallbacks(progressRunnable)
                } else {
                    player.start()
                    playButton.setImageResource(R.drawable.ic_chat_pause)
                    playHandler.post(progressRunnable)
                }
            }
            return
        }

        // Play new audio: stop previous one first
        stopActivePlayback()

        runCatching {
            val player = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
                setOnCompletionListener {
                    stopActivePlayback()
                }
            }

            activeMediaPlayer = player
            activeAudioPath = path
            activePlayButton = playButton
            activeWaveformLayout = waveformLayout
            activeCurrentTimeText = currentTimeText
            activeTotalTimeText = totalTimeText
            activeDurationMs = durationMs

            playButton.setImageResource(R.drawable.ic_chat_pause)
            playHandler.post(progressRunnable)
        }.onFailure {
            Toast.makeText(requireContext(), "Failed to play audio note", Toast.LENGTH_SHORT).show()
            stopActivePlayback()
        }
    }

    private fun stopActivePlayback() {
        playHandler.removeCallbacks(progressRunnable)
        runCatching {
            activeMediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        }
        activeMediaPlayer = null

        activePlayButton?.setImageResource(R.drawable.ic_chat_media_play)
        
        // Reset waveform bars tint
        activeWaveformLayout?.let { waveform ->
            for (i in 0 until waveform.childCount) {
                waveform.getChildAt(i).backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E2E8F0"))
            }
        }
        
        // Reset timer text back
        activeCurrentTimeText?.text = "00:00"

        activeAudioPath = null
        activePlayButton = null
        activeWaveformLayout = null
        activeCurrentTimeText = null
        activeTotalTimeText = null
        activeDurationMs = 0L
    }

    override fun onResume() {
        super.onResume()
        // Hide bottom tab bar & make status bar white background with dark icons
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(Color.TRANSPARENT, true, fullBleed = true)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopActivePlayback()
        _binding = null
    }
}
