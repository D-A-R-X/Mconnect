package com.manjugroups.m_connect.ui.chat

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.content.ContentUris
import android.provider.MediaStore
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.ui.chat.ChatMessagesFragment.PendingAttachment
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.common.MediaItem

class MediaPreviewBottomSheet : BottomSheetDialogFragment() {

    interface MediaPreviewListener {
        fun onMediaSend(attachments: List<PendingAttachment>, caption: String)
        fun onAddMoreClicked()
        fun onPreviewCancelled()
    }

    private var listener: MediaPreviewListener? = null
    private val selectedAttachments = mutableListOf<PendingAttachment>()
    private var currentAttachment: PendingAttachment? = null
    private var isSent = false

    private var isMediaListVisible = false
    private val localMediaItems = mutableListOf<LocalPreviewItem>()
    private var localMediaAdapter: LocalPreviewAdapter? = null

    private var exoPlayer: ExoPlayer? = null

    private val updateSeekBarHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            val view = view ?: return
            val player = exoPlayer ?: return
            val videoSeekBar = view.findViewById<android.widget.SeekBar>(R.id.videoSeekBar) ?: return
            val tvVideoTime = view.findViewById<android.widget.TextView>(R.id.tvVideoTime) ?: return

            if (player.isPlaying) {
                val current = player.currentPosition
                val duration = player.duration
                if (duration > 0) {
                    videoSeekBar.max = duration.toInt()
                    videoSeekBar.progress = current.toInt()
                    tvVideoTime.text = "${formatDurationMs(current)} / ${formatDurationMs(duration)}"
                }
                updateSeekBarHandler.postDelayed(this, 250)
            }
        }
    }

    fun setListener(listener: MediaPreviewListener) {
        this.listener = listener
    }

    fun setAttachment(attachment: PendingAttachment) {
        this.selectedAttachments.clear()
        this.selectedAttachments.add(attachment)
        this.currentAttachment = attachment
    }

    fun setAttachments(attachments: List<PendingAttachment>) {
        this.selectedAttachments.clear()
        this.selectedAttachments.addAll(attachments)
        this.currentAttachment = attachments.firstOrNull()
    }

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
                behavior.isDraggable = false
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { dialog ->
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = false
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_media_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Intercept system back press/gesture to dismiss preview and return to camera
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    dismiss()
                }
            }
        )

        val imgPreview = view.findViewById<ImageView>(R.id.imgPreviewMain)
        val imgPlayIcon = view.findViewById<ImageView>(R.id.imgVideoPlayIcon)
        val etCaption = view.findViewById<EditText>(R.id.etPreviewCaption)
        val tvUserName = view.findViewById<TextView>(R.id.tvPreviewUserName)
        val btnSend = view.findViewById<View>(R.id.btnPreviewSend)
        val btnAdd = view.findViewById<View>(R.id.btnPreviewAdd)

        // Set user name from SessionManager
        val session = SessionManager(requireContext())
        tvUserName.text = session.userName ?: "User"

        // Load initial preview
        currentAttachment?.let { showAttachmentPreview(it) }

        btnSend.setOnClickListener {
            val caption = etCaption.text?.toString()?.trim().orEmpty()
            if (selectedAttachments.isNotEmpty()) {
                isSent = true
                listener?.onMediaSend(selectedAttachments, caption)
            }
            dismiss()
        }

        btnAdd.setOnClickListener {
            toggleLocalMediaList(view)
        }
    }

    private fun showAttachmentPreview(media: PendingAttachment) {
        currentAttachment = media
        val view = view ?: return
        val imgPreview = view.findViewById<ImageView>(R.id.imgPreviewMain) ?: return
        val imgPlayIcon = view.findViewById<ImageView>(R.id.imgVideoPlayIcon) ?: return
        val videoPreview = view.findViewById<androidx.media3.ui.PlayerView>(R.id.videoPreviewMain) ?: return
        val videoTouchOverlay = view.findViewById<View>(R.id.videoTouchOverlay) ?: return
        val videoControlBar = view.findViewById<View>(R.id.videoControlBar) ?: return

        // Stop any active video playback and hide VideoView & Controls
        updateSeekBarHandler.removeCallbacks(updateSeekBarRunnable)
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        videoPreview.player = null
        videoPreview.visibility = View.GONE
        videoTouchOverlay.visibility = View.GONE
        videoControlBar.visibility = View.GONE

        // Make sure image preview is visible
        imgPreview.visibility = View.VISIBLE

        val isVideo = media.fileType.startsWith("video/")
        imgPreview.load(media.uri) {
            crossfade(true)
            placeholder(R.drawable.bg_chat_media_placeholder)
            error(R.drawable.bg_chat_media_placeholder)
            if (isVideo) {
                decoderFactory(coil.decode.VideoFrameDecoder.Factory())
            }
        }
        imgPlayIcon.visibility = if (isVideo) View.VISIBLE else View.GONE

        // Set click listeners for play icon and image preview to trigger play video
        if (isVideo) {
            val playClickListener = View.OnClickListener {
                playVideo(media)
            }
            imgPlayIcon.setOnClickListener(playClickListener)
            imgPreview.setOnClickListener(playClickListener)
        } else {
            imgPlayIcon.setOnClickListener(null)
            imgPreview.setOnClickListener(null)
        }
    }

    private fun playVideo(media: PendingAttachment) {
        val view = view ?: return
        val imgPreview = view.findViewById<ImageView>(R.id.imgPreviewMain) ?: return
        val imgPlayIcon = view.findViewById<ImageView>(R.id.imgVideoPlayIcon) ?: return
        val videoPreview = view.findViewById<androidx.media3.ui.PlayerView>(R.id.videoPreviewMain) ?: return
        val videoTouchOverlay = view.findViewById<View>(R.id.videoTouchOverlay) ?: return
        val videoControlBar = view.findViewById<View>(R.id.videoControlBar) ?: return
        val videoSeekBar = view.findViewById<android.widget.SeekBar>(R.id.videoSeekBar) ?: return
        val btnRewind = view.findViewById<View>(R.id.btnVideoRewind) ?: return
        val btnPlayPause = view.findViewById<ImageView>(R.id.btnVideoPlayPause) ?: return
        val btnFastForward = view.findViewById<View>(R.id.btnVideoFastForward) ?: return
        val tvVideoTime = view.findViewById<android.widget.TextView>(R.id.tvVideoTime) ?: return
        val btnVolumeToggle = view.findViewById<ImageView>(R.id.btnVideoVolumeToggle) ?: return
        val volumeSeekBar = view.findViewById<android.widget.SeekBar>(R.id.volumeSeekBar) ?: return

        // Hide static preview and center play icon
        imgPreview.visibility = View.GONE
        imgPlayIcon.visibility = View.GONE

        // Show VideoView, touch overlay, and control bar
        videoPreview.visibility = View.VISIBLE
        videoTouchOverlay.visibility = View.VISIBLE
        videoControlBar.visibility = View.VISIBLE

        // Determine Uri (if mock URL from picsum, play the Google APIs sample video)
        val videoUri = if (media.uri.toString().contains("picsum.photos")) {
            Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4")
        } else {
            media.uri
        }

        // Initialize ExoPlayer
        exoPlayer?.release()
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(requireContext()).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(videoUri))
            playWhenReady = true
            prepare()
        }
        exoPlayer = player
        videoPreview.player = player

        // Setup volume controls
        val audioManager = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)

        volumeSeekBar.max = maxVolume
        volumeSeekBar.progress = currentVolume
        updateVolumeIcon(currentVolume, maxVolume)

        volumeSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, progress, 0)
                    updateVolumeIcon(progress, maxVolume)
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        var lastVolume = if (currentVolume > 0) currentVolume else maxVolume / 2
        btnVolumeToggle.setOnClickListener {
            val vol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            if (vol > 0) {
                lastVolume = vol
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
                volumeSeekBar.progress = 0
                updateVolumeIcon(0, maxVolume)
            } else {
                audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, lastVolume, 0)
                volumeSeekBar.progress = lastVolume
                updateVolumeIcon(lastVolume, maxVolume)
            }
        }

        // Setup Video Seek Listener
        videoSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player.seekTo(progress.toLong())
                    val dur = player.duration
                    tvVideoTime.text = "${formatDurationMs(progress.toLong())} / ${formatDurationMs(dur)}"
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {
                updateSeekBarHandler.removeCallbacks(updateSeekBarRunnable)
            }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                if (player.isPlaying) {
                    updateSeekBarHandler.post(updateSeekBarRunnable)
                }
            }
        })

        // Rewind & Fast Forward Click Listeners
        btnRewind.setOnClickListener {
            val target = (player.currentPosition - 10000).coerceAtLeast(0)
            player.seekTo(target)
            videoSeekBar.progress = target.toInt()
        }
        btnFastForward.setOnClickListener {
            val dur = player.duration
            val target = (player.currentPosition + 10000).coerceAtMost(dur)
            player.seekTo(target)
            videoSeekBar.progress = target.toInt()
        }

        // Play/Pause toggle in Bar
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        btnPlayPause.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                imgPlayIcon.visibility = View.VISIBLE
            } else {
                player.play()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                imgPlayIcon.visibility = View.GONE
                updateSeekBarHandler.post(updateSeekBarRunnable)
            }
        }

        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY) {
                    btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                    val dur = player.duration
                    if (dur > 0) {
                        videoSeekBar.max = dur.toInt()
                        videoSeekBar.progress = player.currentPosition.toInt()
                        tvVideoTime.text = "${formatDurationMs(player.currentPosition)} / ${formatDurationMs(dur)}"
                    }
                    updateSeekBarHandler.post(updateSeekBarRunnable)
                } else if (state == androidx.media3.common.Player.STATE_ENDED) {
                    resetVideoState()
                }
            }
        })

        // Tap on main overlay also toggles Play/Pause
        videoTouchOverlay.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                imgPlayIcon.visibility = View.VISIBLE
            } else {
                player.play()
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                imgPlayIcon.visibility = View.GONE
                updateSeekBarHandler.post(updateSeekBarRunnable)
            }
        }
    }

    private fun resetVideoState() {
        val view = view ?: return
        val imgPreview = view.findViewById<ImageView>(R.id.imgPreviewMain) ?: return
        val imgPlayIcon = view.findViewById<ImageView>(R.id.imgVideoPlayIcon) ?: return
        val videoPreview = view.findViewById<androidx.media3.ui.PlayerView>(R.id.videoPreviewMain) ?: return
        val videoTouchOverlay = view.findViewById<View>(R.id.videoTouchOverlay) ?: return
        val videoControlBar = view.findViewById<View>(R.id.videoControlBar) ?: return

        updateSeekBarHandler.removeCallbacks(updateSeekBarRunnable)
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        videoPreview.player = null
        videoPreview.visibility = View.GONE
        videoTouchOverlay.visibility = View.GONE
        videoControlBar.visibility = View.GONE

        imgPreview.visibility = View.VISIBLE
        val isVideo = currentAttachment?.fileType?.startsWith("video/") == true
        imgPlayIcon.visibility = if (isVideo) View.VISIBLE else View.GONE
    }

    private fun formatDurationMs(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun updateVolumeIcon(volume: Int, maxVolume: Int) {
        val view = view ?: return
        val btnVolume = view.findViewById<ImageView>(R.id.btnVideoVolumeToggle) ?: return
        if (volume == 0) {
            btnVolume.setColorFilter(Color.parseColor("#EF4444")) // Red for mute
        } else {
            btnVolume.setColorFilter(Color.WHITE) // White for active
        }
    }

    private fun toggleLocalMediaList(view: View) {
        val rvList = view.findViewById<RecyclerView>(R.id.rvPreviewLocalMedia) ?: return
        val imgAddIcon = view.findViewById<ImageView>(R.id.imgPreviewAddIcon) ?: return

        if (isMediaListVisible) {
            isMediaListVisible = false
            rvList.visibility = View.GONE
            imgAddIcon.setImageResource(R.drawable.ic_add_plus)
        } else {
            if (hasStoragePermission()) {
                isMediaListVisible = true
                rvList.visibility = View.VISIBLE
                imgAddIcon.setImageResource(R.drawable.ic_sheet_close)
                loadGalleryMedia(rvList)
            } else {
                listener?.onAddMoreClicked()
                dismiss()
            }
        }
    }

    private fun hasStoragePermission(): Boolean {
        val context = context ?: return false
        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.any {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun loadGalleryMedia(recyclerView: RecyclerView) {
        val mediaList = queryGalleryMedia()
        localMediaItems.clear()
        localMediaItems.addAll(mediaList)

        if (localMediaAdapter == null) {
            recyclerView.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            localMediaAdapter = LocalPreviewAdapter(localMediaItems) { clickedItem ->
                handleGalleryItemClick(clickedItem)
            }
            recyclerView.adapter = localMediaAdapter
        } else {
            localMediaAdapter?.notifyDataSetChanged()
        }
    }

    private fun handleGalleryItemClick(clickedItem: LocalPreviewItem) {
        val index = localMediaItems.indexOf(clickedItem)
        if (index == -1) return

        val uriStr = clickedItem.uri.toString()
        val existingIndex = selectedAttachments.indexOfFirst { it.uri.toString() == uriStr }

        if (existingIndex != -1) {
            selectedAttachments.removeAt(existingIndex)
            if (currentAttachment?.uri?.toString() == uriStr) {
                if (selectedAttachments.isNotEmpty()) {
                    showAttachmentPreview(selectedAttachments.last())
                } else {
                    dismiss()
                    return
                }
            }
        } else {
            val meta = if (clickedItem.isMock) {
                val name = if (clickedItem.isVideo) "Sample_Video.mp4" else "Sample_Mock_${clickedItem.mockUrl?.substringAfterLast('/')}.jpg"
                val type = if (clickedItem.isVideo) "video/mp4" else "image/jpeg"
                PendingAttachment(
                    uri = clickedItem.uri,
                    fileName = name,
                    fileType = type,
                    fileSize = 102400L
                )
            } else {
                readAttachmentMeta(clickedItem.uri)
            }

            if (meta != null) {
                val maxSize = 15L * 1024L * 1024L
                if (meta.fileSize > maxSize) {
                    Toast.makeText(requireContext(), "${meta.fileName} is larger than 15 MB", Toast.LENGTH_SHORT).show()
                    return
                }
                selectedAttachments.add(meta)
                showAttachmentPreview(meta)
            }
        }

        // Sync local list selection status
        for (i in localMediaItems.indices) {
            val item = localMediaItems[i]
            val isSel = selectedAttachments.any { it.uri.toString() == item.uri.toString() }
            localMediaItems[i] = item.copy(isSelected = isSel)
        }
        localMediaAdapter?.notifyDataSetChanged()
    }

    private fun queryGalleryMedia(): List<LocalPreviewItem> {
        val list = mutableListOf<LocalPreviewItem>()
        val context = context ?: return list

        val imagesUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val imagesProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.MIME_TYPE
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(imagesUri, imagesProjection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                var count = 0
                while (cursor.moveToNext() && count < 25) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(imagesUri, id)
                    list.add(LocalPreviewItem(uri = contentUri, isVideo = false, isSelected = false))
                    count++
                }
            }
        }

        val videosUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val videosProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION
        )

        runCatching {
            context.contentResolver.query(videosUri, videosProjection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                var count = 0
                while (cursor.moveToNext() && count < 10) {
                    val id = cursor.getLong(idCol)
                    val durationMs = if (durationCol != -1) cursor.getLong(durationCol) else 0L
                    val durationStr = formatDuration(durationMs)
                    val contentUri = ContentUris.withAppendedId(videosUri, id)
                    list.add(LocalPreviewItem(uri = contentUri, isVideo = true, durationStr = durationStr, isSelected = false))
                    count++
                }
            }
        }

        val sortedList = list.sortedByDescending { it.uri.lastPathSegment?.toLongOrNull() ?: 0L }.toMutableList()

        // Append the exact same mock list items as ChatMessagesFragment.kt
        val mockItems = listOf(
            LocalPreviewItem(
                uri = Uri.parse("https://picsum.photos/id/82/300/300"),
                isVideo = false,
                isSelected = false,
                isMock = true,
                mockUrl = "https://picsum.photos/id/82/300/300"
            ),
            LocalPreviewItem(
                uri = Uri.parse("https://picsum.photos/id/1016/300/300"),
                isVideo = false,
                isSelected = false,
                isMock = true,
                mockUrl = "https://picsum.photos/id/1016/300/300"
            ),
            LocalPreviewItem(
                uri = Uri.parse("https://picsum.photos/id/1015/300/300"),
                isVideo = false,
                isSelected = false,
                isMock = true,
                mockUrl = "https://picsum.photos/id/1015/300/300"
            ),
            LocalPreviewItem(
                uri = Uri.parse("https://picsum.photos/id/1018/300/300"),
                isVideo = true,
                durationStr = "0:54",
                isSelected = false,
                isMock = true,
                mockUrl = "https://picsum.photos/id/1018/300/300"
            ),
            LocalPreviewItem(
                uri = Uri.parse("https://picsum.photos/id/1043/300/300"),
                isVideo = false,
                isSelected = false,
                isMock = true,
                mockUrl = "https://picsum.photos/id/1043/300/300"
            ),
            LocalPreviewItem(
                uri = Uri.parse("https://picsum.photos/id/1025/300/300"),
                isVideo = false,
                isSelected = false,
                isMock = true,
                mockUrl = "https://picsum.photos/id/1025/300/300"
            )
        )
        sortedList.addAll(mockItems)

        // Set the selection status based on selectedAttachments
        return sortedList.map { item ->
            val isSel = selectedAttachments.any { it.uri.toString() == item.uri.toString() }
            item.copy(isSelected = isSel)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }

    private fun readAttachmentMeta(uri: Uri): PendingAttachment? {
        val context = context ?: return null
        val resolver = context.contentResolver
        var name = "File-${System.currentTimeMillis()}"
        var mime = resolver.getType(uri) ?: "application/octet-stream"
        var size = 0L

        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        }

        if (mime == "application/octet-stream" || mime.isBlank()) {
            val ext = name.substringAfterLast('.', "").lowercase()
            mime = when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "3gp" -> "video/3gpp"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                else -> mime
            }
        }

        return PendingAttachment(uri, name, mime, size)
    }

    override fun onDismiss(dialog: DialogInterface) {
        updateSeekBarHandler.removeCallbacks(updateSeekBarRunnable)
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        super.onDismiss(dialog)
        if (!isSent) {
            listener?.onPreviewCancelled()
        }
    }

    override fun onStop() {
        updateSeekBarHandler.removeCallbacks(updateSeekBarRunnable)
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        super.onStop()
    }

    data class LocalPreviewItem(
        val uri: Uri,
        val isVideo: Boolean,
        val durationStr: String? = null,
        val isSelected: Boolean,
        val isMock: Boolean = false,
        val mockUrl: String? = null
    )

    private inner class LocalPreviewAdapter(
        private val items: List<LocalPreviewItem>,
        private val onItemClick: (LocalPreviewItem) -> Unit
    ) : RecyclerView.Adapter<LocalPreviewAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val borderContainer: View = view.findViewById(R.id.borderContainer)
            val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
            val badgeSelected: View = view.findViewById(R.id.badgeSelected)
            val videoBadge: View = view.findViewById(R.id.videoBadge)
            val tvVideoDuration: TextView = view.findViewById(R.id.tvVideoDuration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_local_media, parent, false)
            
            // Apply clipToOutline to the imageContainer thumbnail shape
            val imageContainer = view.findViewById<View>(R.id.imageContainer)
            if (imageContainer != null) {
                imageContainer.background = androidx.core.content.ContextCompat.getDrawable(
                    parent.context, R.drawable.bg_chat_local_media_thumbnail_shape
                )
                imageContainer.clipToOutline = true
            }

            // Adjust width and margins programmatically to avoid match_parent stretch
            val density = parent.context.resources.displayMetrics.density
            val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                lp.width = (70 * density).toInt()
                lp.topMargin = (2 * density).toInt()
                lp.bottomMargin = (2 * density).toInt()
                view.layoutParams = lp
            }
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val loadUrl: Any = if (item.isMock && !item.mockUrl.isNullOrEmpty()) item.mockUrl else item.uri
            
            holder.ivThumbnail.load(loadUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_chat_media_placeholder)
                error(R.drawable.bg_chat_media_placeholder)
            }

            if (item.isSelected) {
                holder.borderContainer.background = androidx.core.content.ContextCompat.getDrawable(
                    holder.itemView.context, R.drawable.bg_chat_local_media_border
                )
                holder.badgeSelected.visibility = View.VISIBLE
            } else {
                holder.borderContainer.background = null
                holder.badgeSelected.visibility = View.GONE
            }

            if (item.isVideo) {
                holder.videoBadge.visibility = View.VISIBLE
                holder.tvVideoDuration.text = item.durationStr ?: "0:00"
            } else {
                holder.videoBadge.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount() = items.size
    }
}
