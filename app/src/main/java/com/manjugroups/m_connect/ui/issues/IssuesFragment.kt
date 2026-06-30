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
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentIssuesBinding
import com.manjugroups.m_connect.databinding.ItemIssueCardBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.IssueItem
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
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

    private val api = ApiService.create()
    private val session by lazy { SessionManager(requireContext()) }

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

        // Load saved issues
        loadIssues()

        // Show Create Issue Bottom Sheet
        binding.btnCreateIssue.setOnClickListener {
            val bottomSheet = CreateIssueBottomSheet()
            bottomSheet.setOnIssueCreatedListener(object : CreateIssueBottomSheet.OnIssueCreatedListener {
                override fun onIssueCreated(
                    projectId: String,
                    title: String,
                    description: String,
                    audioPath: String?,
                    audioDurationMs: Long
                ) {
                    submitIssueToBackend(projectId, title, description, audioPath, audioDurationMs)
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

    /** Upload the optional voice note, then create the issue on the backend
     *  and reload the list from the server. */
    private fun submitIssueToBackend(
        projectId: String,
        title: String,
        description: String,
        audioPath: String?,
        audioDurationMs: Long,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var audioStorageId: String? = null
                var audioSize: Long? = null
                if (!audioPath.isNullOrBlank()) {
                    val file = File(audioPath)
                    if (file.exists()) {
                        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                        audioSize = bytes.size.toLong()
                        val up = api.uploadStorageFile(
                            session.bearerToken,
                            bytes.toRequestBody("audio/mp4".toMediaTypeOrNull()),
                        )
                        if (up.success) audioStorageId = up.storageId
                    }
                }
                val resp = api.createProjectIssue(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.CreateProjectIssueRequest(
                        projectId = projectId,
                        title = title,
                        description = description.ifBlank { null },
                        audioStorageId = audioStorageId,
                        audioFileName = audioStorageId?.let { "voice.m4a" },
                        audioFileType = audioStorageId?.let { "audio/mp4" },
                        audioFileSize = audioSize,
                        audioDurationSeconds = audioDurationMs.takeIf { it > 0 }?.let { it / 1000 },
                    ),
                )
                if (_binding == null) return@launch
                if (resp.success) {
                    Toast.makeText(requireContext(), "Issue submitted", Toast.LENGTH_SHORT).show()
                    loadIssues()
                } else {
                    Toast.makeText(requireContext(), resp.error ?: "Couldn't submit issue", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Toast.makeText(
                    requireContext(),
                    "Couldn't submit issue: ${e.message ?: "network error"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Cache the current list locally so it survives offline / restarts. */
    private fun saveIssues() {
        val context = context ?: return
        val sharedPrefs = context.getSharedPreferences("issues_prefs", android.content.Context.MODE_PRIVATE)
        val json = com.google.gson.Gson().toJson(issuesList)
        sharedPrefs.edit().putString("saved_issues", json).apply()
    }

    /** Load issues from the backend (own issues, newest first); fall back to the
     *  local cache when offline. Audio plays from the resolved remote URL. */
    private fun loadIssues() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = try {
                val resp = api.listMyIssues(session.bearerToken)
                if (resp.success) {
                    issuesList.clear()
                    issuesList.addAll(resp.issues.map { it.toIssueData() })
                    saveIssues()
                    true
                } else false
            } catch (e: Exception) {
                false
            }
            if (_binding == null) return@launch
            if (!ok) loadIssuesFromCache()
            renderIssues()
        }
    }

    private fun IssueItem.toIssueData() = IssueData(
        title = title ?: "",
        description = description ?: "",
        audioPath = audioUrl,            // remote URL — MediaPlayer streams it
        audioDurationMs = audioDurationMs ?: 0L,
        timestamp = createdAt ?: System.currentTimeMillis(),
    )

    private fun loadIssuesFromCache() {
        val context = context ?: return
        val sharedPrefs = context.getSharedPreferences("issues_prefs", android.content.Context.MODE_PRIVATE)
        val json = sharedPrefs.getString("saved_issues", null)
        if (json != null) {
            val type = object : com.google.gson.reflect.TypeToken<List<IssueData>>() {}.type
            val loadedList: List<IssueData> = com.google.gson.Gson().fromJson(json, type)
            issuesList.clear()
            issuesList.addAll(loadedList)
        }
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
            val sdf = SimpleDateFormat("hh:mm a", Locale.US)

            for (issue in filterList) {
                val cardBinding = ItemIssueCardBinding.inflate(inflater, binding.issuesContainer, false)

                cardBinding.tvIssueCardTitle.text = issue.title
                cardBinding.tvIssueTime.text = "Today, " + sdf.format(Date(issue.timestamp))



                // Setup audio note player if attached (remote URL from the
                // backend, or a local file for not-yet-synced cached items).
                val path = issue.audioPath
                if (!path.isNullOrBlank() && (path.startsWith("http") || File(path).exists())) {
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
            val player = MediaPlayer()
            player.setDataSource(path)
            player.setOnCompletionListener { stopActivePlayback() }

            fun onReady(p: MediaPlayer) {
                activeMediaPlayer = p
                activeAudioPath = path
                activePlayButton = playButton
                activeWaveformLayout = waveformLayout
                activeCurrentTimeText = currentTimeText
                activeTotalTimeText = totalTimeText
                activeDurationMs = durationMs
                p.start()
                playButton.setImageResource(R.drawable.ic_chat_pause)
                playHandler.post(progressRunnable)
            }

            if (path.startsWith("http")) {
                // Remote URL — prepare off the main thread to avoid an ANR while
                // buffering, then start when ready.
                player.setOnPreparedListener { if (_binding != null) onReady(it) }
                player.setOnErrorListener { _, _, _ ->
                    stopActivePlayback(); true
                }
                player.prepareAsync()
            } else {
                player.prepare()
                onReady(player)
            }
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
