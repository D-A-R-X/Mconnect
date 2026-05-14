package com.manjugroups.m_connect.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentChatMessagesBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ChannelIdRequest
import com.manjugroups.m_connect.network.ConversationIdRequest
import com.manjugroups.m_connect.network.MessageAttachmentUpload
import com.manjugroups.m_connect.network.MessageData
import com.manjugroups.m_connect.network.SendMessageRequest
import com.manjugroups.m_connect.network.TypingRequest
import com.manjugroups.m_connect.network.ReactionRequest
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.network.DeleteMessageRequest
import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.io.File
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import android.animation.ValueAnimator
import android.animation.ArgbEvaluator

class ChatMessagesFragment : Fragment(), ChatMessageActionsFragment.Callback {

    private var _binding: FragmentChatMessagesBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()

    private var channelId: String? = null
    private var conversationId: String? = null
    private var chatTitle: String = ""
    private var chatSubtitle: String = "Last seen recently"
    private var chatMuted: Boolean = false
    private var myStaffId: String = ""
    private var otherStaffId: String? = null
    private var latestMessageTime: Double = 0.0
    private var currentTypingText: String? = null
    private var presencePollCounter = 0
    private val messages = mutableListOf<MessageData>()
    private val originalBodyCache = mutableMapOf<String, MessageData>()
    private val audioDurationLocalCache = mutableMapOf<String, String>()
    private val staffNameCache = mutableMapOf<String, String>()
    private val pendingAttachments = mutableListOf<PendingAttachment>()

    private val subtitleColorMuted = android.graphics.Color.parseColor("#667085")
    private val subtitleColorTyping = android.graphics.Color.parseColor("#12B76A")
    private val subtitleColorRecording = android.graphics.Color.parseColor("#F04438")

    private lateinit var chatAdapter: ChatMessageAdapter
    private lateinit var mentionAdapter: MentionAdapter

    private var replyingToMessage: MessageData? = null

    private var pollJob: Job? = null
    private var typingDebounceJob: Job? = null
    private var isSendingMessage = false
    private var isAttachmentMenuOpen = false
    private var hasLoadedMessages = false
    private var isEmojiPanelVisible = false

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    private val recordingHandler = Handler(Looper.getMainLooper())
    private var recordTouchStartX = 0f
    private var recordCancelRequested = false

    private val recordAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                toast("Audio recording permission required")
            }
        }

    private val pickAttachmentsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            handlePickedAttachments(uris)
        }

    companion object {
        private const val MAX_ATTACHMENT_COUNT = 5
        private const val MAX_ATTACHMENT_SIZE_BYTES = 15L * 1024L * 1024L
        private const val MIN_RECORDING_MS = 800L
        private const val SLIDE_TO_CANCEL_DP = 80f

        fun forChannel(id: String, name: String) = ChatMessagesFragment().apply {
            arguments = Bundle().apply {
                putString("channelId", id)
                putString("title", name)
            }
        }

        fun forConversation(id: String, name: String) = ChatMessagesFragment().apply {
            arguments = Bundle().apply {
                putString("conversationId", id)
                putString("title", name)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        channelId = arguments?.getString("channelId")
        conversationId = arguments?.getString("conversationId")
        chatTitle = arguments?.getString("title") ?: "Chat"
        myStaffId = session.staffId.orEmpty()

        binding.tvChatTitle.text = chatTitle
        binding.tvChatSubtitle.text = chatSubtitle
        
        setupAdapters()
        setupSelectionToolbar()
        setupSwipeToReply()
        renderPendingAttachments()
        updateSendIcon()

        // Hydrate header (title/subtitle/avatar) from on-disk snapshot so the
        // chat header doesn't flicker from arguments → API result.
        hydrateHeaderFromCache()

        // Hydrate from local cache before hitting the network so the screen
        // paints instantly when re-entering a chat.
        if (messages.isEmpty()) {
            val cached = ChatMessageCache.load(requireContext().applicationContext, cacheKey())
            if (cached.isNotEmpty()) {
                messages.addAll(cached)
                latestMessageTime = messages.maxOfOrNull { it.creationTime ?: 0.0 } ?: 0.0
            }
        }

        if (messages.isEmpty()) {
            SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
            binding.rvMessages.visibility = View.GONE
        } else {
            binding.skeletonContainer.visibility = View.GONE
            binding.rvMessages.visibility = View.VISIBLE
            renderMessages(scrollToBottom = true)
        }
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.titleGroup.setOnClickListener { openContactInfo() }
        binding.btnSearch.setOnClickListener { showInlineSearch() }
        setupInlineSearch()
        binding.btnChatHeaderMenu.setOnClickListener { showChatHeaderMenu(it) }
        
        binding.btnSend.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    recordTouchStartX = event.rawX
                    recordCancelRequested = false
                    if (!canSendNow()) {
                        checkRecordingPermission()
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isRecording) {
                        val deltaX = recordTouchStartX - event.rawX
                        val threshold = dpToPx(SLIDE_TO_CANCEL_DP.toInt()).toFloat()
                        if (deltaX > threshold && !recordCancelRequested) {
                            recordCancelRequested = true
                            updateRecordingHintCancel()
                        } else if (deltaX <= threshold && recordCancelRequested) {
                            recordCancelRequested = false
                            updateRecordingHintNormal()
                        }
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isRecording) {
                        stopRecording(send = !recordCancelRequested)
                        recordCancelRequested = false
                        true
                    } else if (canSendNow()) {
                        sendMessage()
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) {
                        stopRecording(send = false)
                        recordCancelRequested = false
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        
        binding.btnAttach.setOnClickListener {
            showAttachGrid()
        }

        binding.btnInputCamera.setOnClickListener {
            launchCamera()
        }

        binding.btnCancelReply.setOnClickListener {
            cancelReply()
        }

        setupEmojiPicker()

        binding.btnEmoji.setOnClickListener { toggleEmojiPanel() }
        binding.btnCloseEmojiPanel.setOnClickListener { hideEmojiPanel() }

        binding.etMessage.addTextChangedListener(typingWatcher)
        binding.etMessage.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && isEmojiPanelVisible) hideEmojiPanel()
        }

        applyKeyboardAndSystemInsets(view)

        viewLifecycleOwner.lifecycleScope.launch {
            if (myStaffId.isBlank()) {
                runCatching {
                    api.validateSession(session.bearerToken)
                }.onSuccess { response ->
                    myStaffId = response.user?.staffId.orEmpty()
                }
            }
            refreshChatMetadata()
            // If cache hydrated the list, don't force-scroll again after the
            // server refresh — that double scroll is the visible "glitch" on
            // re-entering a chat. Only scroll on a true cold open.
            loadInitialMessages(scrollToBottom = messages.isEmpty())
            markRead()
        }
    }

    private var exoPlayer: ExoPlayer? = null
    private var currentlyPlayingUrl: String? = null
    private var currentlyPlayingStorageId: String? = null
    private var audioProgressJob: Job? = null

    private fun setupAdapters() {
        chatAdapter = ChatMessageAdapter(
            onMessageReactionClick = { message: MessageData, anchor: View -> showReactionPopup(message, anchor) },
            onReactionPillClick = { message: MessageData, anchor: View -> showReactionRemovePopup(message, anchor) },
            onAttachmentClick = { url: String, mime: String, storageId: String? ->
                handleAttachmentClick(url, mime, storageId)
            },
            onReplyClick = { messageId -> scrollToMessage(messageId) },
            onMessageTap = { _ ->
                updateSelectionToolbar()
                true
            }
        )
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
            itemAnimator = null
            setHasFixedSize(false)
        }

        mentionAdapter = MentionAdapter { person: MentionPerson ->
            insertMention(person)
        }
        binding.rvMentions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = mentionAdapter
        }
    }

    private fun scrollToMessage(messageId: String) {
        val index = chatAdapter.currentList.indexOfFirst { it is ChatItem.Message && it.data.id == messageId }
        if (index != -1) {
            binding.rvMessages.smoothScrollToPosition(index)
            highlightMessage(index)
        } else {
            toast("Message not found in current view")
        }
    }

    private fun highlightMessage(index: Int) {
        val checkRunnable = object : Runnable {
            var attempts = 0
            override fun run() {
                val viewHolder = binding.rvMessages.findViewHolderForAdapterPosition(index)
                if (viewHolder != null) {
                    val viewToHighlight = viewHolder.itemView
                    val colorFrom = android.graphics.Color.parseColor("#400B61CA")
                    val colorTo = android.graphics.Color.TRANSPARENT
                    val animator = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
                    animator.duration = 2000
                    animator.addUpdateListener { anim ->
                        viewToHighlight.setBackgroundColor(anim.animatedValue as Int)
                    }
                    animator.start()
                } else if (attempts < 20) {
                    attempts++
                    binding.rvMessages.postDelayed(this, 100)
                }
            }
        }
        binding.rvMessages.postDelayed(checkRunnable, 100)
    }

    private fun handleAttachmentClick(url: String, mime: String, storageId: String?) {
        if (url.isBlank() && storageId.isNullOrBlank()) {
            toast("Attachment unavailable")
            return
        }
        if (url.isBlank()) {
            resolveStorageUrl(storageId!!) { resolved ->
                if (resolved.isNullOrBlank()) {
                    toast("Unable to load attachment")
                } else {
                    routeAttachment(resolved, mime, storageId)
                }
            }
        } else {
            routeAttachment(url, mime, storageId)
        }
    }

    private fun routeAttachment(url: String, mime: String, storageId: String? = null) {
        val lowerMime = mime.lowercase(Locale.getDefault())
        val urlLower = url.lowercase(Locale.getDefault())
        val isVideo = lowerMime.startsWith("video/") ||
            urlLower.endsWith(".mp4") ||
            urlLower.endsWith(".mov") ||
            urlLower.endsWith(".webm") ||
            urlLower.endsWith(".mkv") ||
            urlLower.endsWith(".3gp") ||
            urlLower.endsWith(".avi")
        when {
            lowerMime.startsWith("image/") -> showImagePreview(url)
            lowerMime.startsWith("audio/") -> playVoiceMessage(url, mime, storageId)
            isVideo -> showVideoPreview(url)
            else -> openAttachmentUrl(url)
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun showVideoPreview(url: String) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.popup_video_preview, null)
        val popup = PopupWindow(
            view,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        )

        val playerView = view.findViewById<androidx.media3.ui.PlayerView>(R.id.videoPlayerView)
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(requireContext()).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            playWhenReady = true
            prepare()
        }
        playerView.player = player

        val cleanup = {
            playerView.player = null
            runCatching { player.stop() }
            player.release()
        }
        view.findViewById<View>(R.id.btnVideoBack).setOnClickListener { popup.dismiss() }
        view.findViewById<View>(R.id.btnVideoClose).setOnClickListener { popup.dismiss() }
        view.findViewById<View>(R.id.btnVideoDownload).setOnClickListener {
            saveMediaUrlToGallery(url, mediaKind = MediaKind.VIDEO)
        }
        view.findViewById<View>(R.id.btnVideoForward).setOnClickListener {
            popup.dismiss()
            forwardMediaUrl(url)
        }

        // Tap on the player toggles our overlay chrome so the video isn't
        // blocked by the back/download/forward buttons. PlayerView's own
        // playback controls keep their internal show/hide behavior.
        val videoControls = view.findViewById<View>(R.id.videoControls)
        var videoControlsVisible = true
        playerView.setOnClickListener {
            videoControlsVisible = !videoControlsVisible
            videoControls.animate().cancel()
            videoControls.animate()
                .alpha(if (videoControlsVisible) 1f else 0f)
                .setDuration(180L)
                .withStartAction { if (videoControlsVisible) videoControls.visibility = View.VISIBLE }
                .withEndAction { if (!videoControlsVisible) videoControls.visibility = View.GONE }
                .start()
        }
        popup.setOnDismissListener { cleanup() }

        popup.showAtLocation(binding.root, Gravity.CENTER, 0, 0)
    }

    private fun resolveStorageUrl(storageId: String, onResolved: (String?) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val resolved = runCatching {
                api.getStorageUrl(session.bearerToken, storageId)
            }.getOrNull()?.url
            onResolved(resolved)
        }
    }

    private fun showImagePreview(url: String) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.popup_image_preview, null)
        val popup = PopupWindow(
            view,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        )

        val zoomImg = view.findViewById<ZoomableImageView>(R.id.ivPreview)
        zoomImg.load(url) { crossfade(true) }

        val controls = view.findViewById<View>(R.id.previewControls)
        var controlsVisible = true
        zoomImg.onSingleTap = {
            controlsVisible = !controlsVisible
            controls.animate().cancel()
            controls.animate()
                .alpha(if (controlsVisible) 1f else 0f)
                .setDuration(180L)
                .withStartAction { if (controlsVisible) controls.visibility = View.VISIBLE }
                .withEndAction { if (!controlsVisible) controls.visibility = View.GONE }
                .start()
        }

        view.findViewById<View>(R.id.btnBack).setOnClickListener { popup.dismiss() }
        view.findViewById<View>(R.id.btnPreviewClose).setOnClickListener { popup.dismiss() }
        view.findViewById<View>(R.id.btnPreviewDownload).setOnClickListener {
            saveMediaUrlToGallery(url, mediaKind = MediaKind.IMAGE)
        }
        view.findViewById<View>(R.id.btnPreviewForward).setOnClickListener {
            popup.dismiss()
            forwardMediaUrl(url)
        }

        popup.showAtLocation(binding.root, Gravity.CENTER, 0, 0)
    }

    private enum class MediaKind { IMAGE, VIDEO }

    private fun forwardMediaUrl(url: String) {
        if (url.isBlank()) { toast("Nothing to forward"); return }
        val synthetic = com.manjugroups.m_connect.network.MessageData(
            id = "forward-${System.currentTimeMillis()}",
            creationTime = System.currentTimeMillis().toDouble(),
            body = url,
            senderId = myStaffId,
            senderName = session.userName,
            channelId = null,
            conversationId = null,
            isDeleted = false,
            isEdited = false,
            replyCount = 0,
            parentMessageId = null
        )
        openForwardPicker(listOf(synthetic))
    }

    private fun saveMediaUrlToGallery(url: String, mediaKind: MediaKind) {
        if (url.isBlank()) { toast("Nothing to save"); return }
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val bytes = java.net.URL(url).openStream().use { it.readBytes() }
                    val ctx = context?.applicationContext ?: return@runCatching false
                    val resolver = ctx.contentResolver
                    val mime = when (mediaKind) {
                        MediaKind.IMAGE -> "image/jpeg"
                        MediaKind.VIDEO -> "video/mp4"
                    }
                    val ext = if (mediaKind == MediaKind.IMAGE) "jpg" else "mp4"
                    val name = "Mconnect-${System.currentTimeMillis()}.$ext"
                    val collection = if (mediaKind == MediaKind.IMAGE) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            android.provider.MediaStore.Images.Media.getContentUri(
                                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                            )
                        else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            android.provider.MediaStore.Video.Media.getContentUri(
                                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                            )
                        else android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                        val rel = if (mediaKind == MediaKind.IMAGE) {
                            android.os.Environment.DIRECTORY_PICTURES + "/Mconnect"
                        } else {
                            android.os.Environment.DIRECTORY_MOVIES + "/Mconnect"
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, rel)
                        }
                    }
                    val uri = resolver.insert(collection, values) ?: return@runCatching false
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    true
                }.getOrDefault(false)
            }
            if (_binding != null) toast(if (ok) "Saved to gallery" else "Couldn't save")
        }
    }

    private fun showImageSendPreview(images: List<PendingAttachment>) {
        if (images.isEmpty()) return
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.popup_image_send_preview, null)
        val popup = PopupWindow(
            view,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        ).apply {
            isFocusable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#0F0F1A")))
        }

        val editView = view.findViewById<MediaEditView>(R.id.ivPreviewMain)
        val stripContainer = view.findViewById<LinearLayout>(R.id.thumbStripContainer)
        val etCaption = view.findViewById<android.widget.EditText>(R.id.etPreviewCaption)
        val btnSend = view.findViewById<View>(R.id.btnPreviewSend)
        val btnBack = view.findViewById<View>(R.id.btnPreviewBack)
        val editToolBar = view.findViewById<LinearLayout>(R.id.editToolBar)
        val tvEditModeLabel = view.findViewById<android.widget.TextView>(R.id.tvEditModeLabel)
        val btnEditApply = view.findViewById<View>(R.id.btnEditApply)
        val btnEditCancel = view.findViewById<View>(R.id.btnEditCancel)
        val drawToolBar = view.findViewById<LinearLayout>(R.id.drawToolBar)
        val cropToolBar = view.findViewById<LinearLayout>(R.id.cropToolBar)
        val drawColorRow = view.findViewById<LinearLayout>(R.id.drawColorRow)
        setupDrawToolbar(view, editView, drawColorRow)
        setupCropToolbar(view, editView)

        // Per-image edit state: we replace the file URI when the user commits
        // edits, so the sent attachment has the flattened bitmap.
        val workingImages = images.toMutableList()
        var activeIndex = 0

        fun loadActiveBitmap() {
            val active = workingImages[activeIndex]
            viewLifecycleOwner.lifecycleScope.launch {
                val bm = withContext(Dispatchers.IO) {
                    runCatching {
                        requireContext().contentResolver.openInputStream(active.uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    }.getOrNull()
                }
                if (_binding == null || bm == null) return@launch
                editView.setBitmap(bm)
            }
        }

        fun showActive() {
            loadActiveBitmap()
            for (i in 0 until stripContainer.childCount) {
                val v = stripContainer.getChildAt(i)
                v.alpha = if (i == activeIndex) 1f else 0.55f
                v.setBackgroundResource(
                    if (i == activeIndex) R.drawable.bg_thumb_strip_selected
                    else 0
                )
            }
        }

        fun showEditToolbar(label: String) {
            tvEditModeLabel.text = label
            editToolBar.visibility = View.VISIBLE
        }

        fun hideEditToolbar() {
            editToolBar.visibility = View.GONE
        }

        images.forEachIndexed { index, attachment ->
            val thumb = ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(52), dpToPx(52)).apply {
                    marginEnd = dpToPx(6)
                }
                setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
                scaleType = ImageView.ScaleType.CENTER_CROP
                load(attachment.uri) {
                    transformations(coil.transform.RoundedCornersTransformation(dpToPx(8).toFloat()))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    activeIndex = index
                    showActive()
                }
            }
            stripContainer.addView(thumb)
        }
        showActive()

        view.findViewById<View>(R.id.btnPreviewCrop)?.setOnClickListener {
            editView.mode = MediaEditView.Mode.CROP
            showEditToolbar("Crop")
            cropToolBar.visibility = View.VISIBLE
            drawToolBar.visibility = View.GONE
        }
        view.findViewById<View>(R.id.btnPreviewDraw)?.setOnClickListener {
            editView.mode = MediaEditView.Mode.DRAW
            showEditToolbar("Draw — tap Done when finished")
            drawToolBar.visibility = View.VISIBLE
            cropToolBar.visibility = View.GONE
        }
        view.findViewById<View>(R.id.btnPreviewText)?.setOnClickListener {
            showAddTextSheet { text ->
                if (text.isNotEmpty()) {
                    editView.addText(text)
                    showEditToolbar("Drag text to position")
                }
            }
        }
        btnEditCancel.setOnClickListener {
            if (editView.mode == MediaEditView.Mode.CROP) editView.cancelCrop()
            editView.mode = MediaEditView.Mode.NONE
            hideEditToolbar()
            cropToolBar.visibility = View.GONE
            drawToolBar.visibility = View.GONE
        }
        btnEditApply.setOnClickListener {
            if (editView.mode == MediaEditView.Mode.CROP) editView.applyCrop()
            editView.mode = MediaEditView.Mode.NONE
            hideEditToolbar()
            cropToolBar.visibility = View.GONE
            drawToolBar.visibility = View.GONE
        }

        view.findViewById<View>(R.id.btnPreviewUndo)?.setOnClickListener {
            if (!editView.undo()) toast("Nothing to undo")
        }
        view.findViewById<View>(R.id.btnPreviewDownload)?.setOnClickListener {
            val first = workingImages.firstOrNull() ?: return@setOnClickListener
            savePendingAttachmentToGallery(first)
        }

        btnBack.setOnClickListener { popup.dismiss() }

        btnSend.setOnClickListener {
            val caption = etCaption.text?.toString()?.trim().orEmpty()
            // Flatten any current edits into the active image before sending.
            val edited = editView.getResult()
            if (edited != null) {
                val saved = persistEditedBitmap(edited)
                if (saved != null) workingImages[activeIndex] = saved
            }
            popup.dismiss()
            pendingAttachments.addAll(workingImages)
            if (caption.isNotEmpty()) {
                binding.etMessage.setText(caption)
            }
            renderPendingAttachments()
            updateSendIcon()
            sendMessage()
        }

        popup.showAtLocation(binding.root, Gravity.CENTER, 0, 0)
    }

    private fun playVoiceMessage(url: String, mime: String = "audio/mp4", storageId: String? = null) {
        if (currentlyPlayingUrl == url && exoPlayer?.isPlaying == true) {
            exoPlayer?.pause()
            stopAudioProgressPolling()
            chatAdapter.setCurrentlyPlayingStorageId(null)
            return
        }

        if (currentlyPlayingUrl == url && exoPlayer != null) {
            exoPlayer?.play()
            chatAdapter.setCurrentlyPlayingStorageId(storageId)
            startAudioProgressPolling(storageId)
            return
        }

        exoPlayer?.release()
        stopAudioProgressPolling()
        val mimeHint = resolveAudioMime(url, mime)
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMimeType(mimeHint)
            .build()

        exoPlayer = ExoPlayer.Builder(requireContext()).build().apply {
            setMediaItem(mediaItem)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        currentlyPlayingUrl = null
                        currentlyPlayingStorageId = null
                        stopAudioProgressPolling()
                        if (!storageId.isNullOrBlank()) {
                            chatAdapter.setAudioPlaybackProgress(storageId, 1f, "Played")
                        }
                        chatAdapter.setCurrentlyPlayingStorageId(null)
                    } else if (playbackState == Player.STATE_READY && !storageId.isNullOrBlank()) {
                        val totalMs = exoPlayer?.duration ?: 0L
                        if (totalMs > 0) {
                            chatAdapter.cacheAudioDuration(storageId, formatAudioMillis(totalMs))
                        }
                    }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        chatAdapter.setCurrentlyPlayingStorageId(storageId)
                        startAudioProgressPolling(storageId)
                    } else {
                        stopAudioProgressPolling()
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    toast("Error playing audio: ${error.message}")
                    currentlyPlayingUrl = null
                    currentlyPlayingStorageId = null
                    stopAudioProgressPolling()
                    chatAdapter.setCurrentlyPlayingStorageId(null)
                }
            })
            prepare()
            playWhenReady = true
        }
        currentlyPlayingUrl = url
        currentlyPlayingStorageId = storageId
        chatAdapter.setCurrentlyPlayingStorageId(storageId)
    }

    private fun startAudioProgressPolling(storageId: String?) {
        audioProgressJob?.cancel()
        if (storageId.isNullOrBlank()) return
        audioProgressJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                val player = exoPlayer ?: break
                val duration = player.duration.takeIf { it > 0L } ?: 0L
                val position = player.currentPosition.coerceAtLeast(0L)
                val progress = if (duration > 0L) {
                    (position.toFloat() / duration).coerceIn(0f, 1f)
                } else 0f
                val label = if (duration > 0L) {
                    "${formatAudioMillis(position)} / ${formatAudioMillis(duration)}"
                } else {
                    formatAudioMillis(position)
                }
                chatAdapter.setAudioPlaybackProgress(storageId, progress, label)
                delay(60L)
            }
        }
    }

    private fun stopAudioProgressPolling() {
        audioProgressJob?.cancel()
        audioProgressJob = null
    }

    private fun formatAudioMillis(millis: Long): String {
        val totalSec = (millis / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun probeAudioDurations(messageList: List<MessageData>) {
        val targets = messageList
            .flatMap { it.attachments.orEmpty() }
            .mapNotNull { att ->
                val sid = att.storageId ?: return@mapNotNull null
                if (audioDurationLocalCache.containsKey(sid)) return@mapNotNull null
                val mime = att.fileType.orEmpty().lowercase()
                val name = att.fileName.orEmpty().lowercase()
                val isAudio = mime.startsWith("audio/") ||
                    name.endsWith(".m4a") || name.endsWith(".mp3") ||
                    name.endsWith(".wav") || name.endsWith(".aac") ||
                    name.endsWith(".caf") || name.startsWith("voice-")
                if (isAudio) Triple(sid, att.url, mime) else null
            }
        if (targets.isEmpty()) return

        val semaphore = kotlinx.coroutines.sync.Semaphore(permits = 3)
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            coroutineScope {
                targets.forEach { (sid, urlOrNull, _) ->
                    launch {
                        semaphore.withPermit {
                            val url = urlOrNull?.takeIf { it.isNotBlank() } ?: runCatching {
                                api.getStorageUrl(session.bearerToken, sid).url
                            }.getOrNull()
                            if (url.isNullOrBlank()) return@withPermit
                            val durationMs = probeMediaDuration(url) ?: return@withPermit
                            val label = formatAudioMillis(durationMs)
                            audioDurationLocalCache[sid] = label
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                if (_binding != null) {
                                    chatAdapter.cacheAudioDuration(sid, label)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun probeMediaDuration(url: String): Long? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, hashMapOf())
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolveAudioMime(url: String, declared: String): String {
        val lower = url.lowercase(Locale.getDefault())
        return when {
            lower.endsWith(".m4a") || lower.endsWith(".aac") ||
                declared.equals("audio/aac", ignoreCase = true) ||
                declared.equals("audio/mp4", ignoreCase = true) ||
                declared.equals("audio/x-m4a", ignoreCase = true) ||
                declared.equals("audio/mpeg", ignoreCase = true) -> "audio/mp4"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".ogg") || lower.endsWith(".opus") -> "audio/ogg"
            lower.endsWith(".webm") -> "audio/webm"
            lower.endsWith(".wav") -> "audio/wav"
            declared.startsWith("audio/", ignoreCase = true) -> declared
            else -> "audio/mp4"
        }
    }

    private fun setupSwipeToReply() {
        val swipeCallback = SwipeToReplyCallback(requireContext()) { position ->
            val item = chatAdapter.currentList.getOrNull(position)
            if (item is ChatItem.Message) {
                showReplyUI(item.data)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvMessages)
    }

    private fun showReplyUI(message: MessageData) {
        replyingToMessage = message
        binding.replyPreviewCard.visibility = View.VISIBLE
        binding.tvReplyName.text = if (message.senderId == myStaffId) "You" else message.senderName
        binding.tvReplyBody.text = message.body ?: "Attachment"
        binding.etMessage.requestFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etMessage, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun cancelReply() {
        replyingToMessage = null
        binding.replyPreviewCard.visibility = View.GONE
    }

    private fun setupEmojiPicker() {
        val emojiPicker = EmojiPickerView(requireContext()).apply {
            emojiGridColumns = 9
            setOnEmojiPickedListener { emoji ->
                val cursorPos = binding.etMessage.selectionStart.coerceAtLeast(0)
                binding.etMessage.text?.insert(cursorPos, emoji.emoji)
                binding.etMessage.setSelection(cursorPos + emoji.emoji.length)
            }
        }
        binding.emojiPickerContainer.addView(emojiPicker)
    }

    private fun toggleEmojiPanel() {
        if (isEmojiPanelVisible) hideEmojiPanel() else showEmojiPanel()
    }

    private fun showEmojiPanel() {
        isEmojiPanelVisible = true
        binding.emojiPanel.visibility = View.VISIBLE
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMessage.windowToken, 0)
    }

    private fun hideEmojiPanel() {
        isEmojiPanelVisible = false
        binding.emojiPanel.visibility = View.GONE
    }

    private fun showReactionPopup(message: MessageData, anchor: View) {
        val popupView = LayoutInflater.from(requireContext()).inflate(R.layout.reaction_popup, null)
        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 10f
            isOutsideTouchable = true
        }

        popupView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                popup.dismiss()
                true
            } else false
        }

        val emojis = listOf("❤️", "👍", "😂", "😮", "😢", "🙏")
        val emojiIds = listOf(
            R.id.reactEmoji1, R.id.reactEmoji2, R.id.reactEmoji3,
            R.id.reactEmoji4, R.id.reactEmoji5, R.id.reactEmoji6
        )

        emojis.forEachIndexed { index, emoji ->
            popupView.findViewById<TextView>(emojiIds[index]).setOnClickListener {
                onReact(message.id ?: "", emoji)
                popup.dismiss()
            }
        }

        popupView.findViewById<TextView>(R.id.btnMoreReactions).setOnClickListener {
            popup.dismiss()
            val actions = ChatMessageActionsFragment.newInstance(message.id ?: "", message.body ?: "")
            actions.setCallback(this)
            actions.show(childFragmentManager, "MessageActions")
        }

        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        anchor.post {
            if (!isAdded || anchor.isAttachedToWindow.not()) return@post
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            val popupX = location[0] + (anchor.width - popupView.measuredWidth) / 2
            val popupY = location[1] - popupView.measuredHeight - dpToPx(8)
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, popupX.coerceAtLeast(dpToPx(8)), popupY)
        }
    }

    private fun showReactionRemovePopup(message: MessageData, anchor: View) {
        val reactions = message.reactions?.filter { (it.count ?: 0) > 0 }
        if (reactions.isNullOrEmpty()) return

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.popup_reaction_details, null)
        val container = view.findViewById<LinearLayout>(R.id.reactionDetailsContainer)
        val popup = PopupWindow(
            view,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 12f
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        reactions.forEach { reaction ->
            val emoji = reaction.emoji.orEmpty()
            val staffIds = reaction.staffIds.orEmpty()
            val mine = reaction.mine == true || (myStaffId.isNotBlank() && staffIds.contains(myStaffId))
            val others = staffIds.filter { it != myStaffId }
            val count = reaction.count ?: staffIds.size

            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_reaction_detail_row, container, false)
            row.findViewById<TextView>(R.id.tvReactionEmoji).text = emoji

            val nameText = buildString {
                if (mine) append("You")
                if (others.isNotEmpty()) {
                    if (isNotEmpty()) append(" and ")
                    val displayNames = others.map { staffNameCache[it] ?: "Member" }
                    append(displayNames.joinToString(", "))
                }
                if (isEmpty()) append("$count reaction" + if (count > 1) "s" else "")
            }
            row.findViewById<TextView>(R.id.tvReactionName).text = nameText
            row.findViewById<TextView>(R.id.tvReactionHint).text =
                if (mine) "Tap to remove" else "Reacted"
            row.setOnClickListener {
                if (mine) {
                    onReact(message.id ?: "", emoji)
                    popup.dismiss()
                }
            }
            container.addView(row)

            // Lazy-load staff names for ids we don't already have
            others.filter { !staffNameCache.containsKey(it) }.forEach { sid ->
                viewLifecycleOwner.lifecycleScope.launch {
                    runCatching { api.getStaffDetail(session.bearerToken, sid).staff }
                        .getOrNull()?.name?.let { name ->
                            staffNameCache[sid] = name
                            row.findViewById<TextView>(R.id.tvReactionName).text = buildString {
                                if (mine) append("You")
                                val updated = others.map { staffNameCache[it] ?: "Member" }
                                if (updated.isNotEmpty()) {
                                    if (isNotEmpty()) append(" and ")
                                    append(updated.joinToString(", "))
                                }
                            }
                        }
                }
            }
        }

        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        anchor.post {
            if (!isAdded || anchor.isAttachedToWindow.not()) return@post
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            val popupX = location[0] + (anchor.width - view.measuredWidth) / 2
            val popupY = location[1] - view.measuredHeight - dpToPx(8)
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, popupX.coerceAtLeast(dpToPx(8)), popupY)
        }
    }

    override fun onReply(messageId: String) {
        val message = messages.find { it.id == messageId }
        if (message != null) {
            showReplyUI(message)
        }
    }

    override fun onReact(messageId: String, emoji: String) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index == -1) return
        
        val message = messages[index]
        
        // Find existing reaction by this user
        val existingReaction = message.reactions?.find { 
            it.mine == true || (it.staffIds != null && myStaffId.isNotBlank() && it.staffIds.contains(myStaffId)) 
        }

        val newReactions = message.reactions?.toMutableList() ?: mutableListOf()
        var added = false
        var removed = false
        var removedEmoji = ""

        if (existingReaction != null) {
            if (existingReaction.emoji == emoji) {
                // Remove existing
                val existingIndex = newReactions.indexOf(existingReaction)
                val updatedReaction = existingReaction.copy(count = (existingReaction.count ?: 1) - 1, mine = false)
                if (updatedReaction.count!! <= 0) {
                    newReactions.removeAt(existingIndex)
                } else {
                    newReactions[existingIndex] = updatedReaction
                }
                removed = true
                removedEmoji = emoji
            } else {
                // Replace: remove old
                val existingIndex = newReactions.indexOf(existingReaction)
                val updatedOldReaction = existingReaction.copy(count = (existingReaction.count ?: 1) - 1, mine = false)
                if (updatedOldReaction.count!! <= 0) {
                    newReactions.removeAt(existingIndex)
                } else {
                    newReactions[existingIndex] = updatedOldReaction
                }
                removed = true
                removedEmoji = existingReaction.emoji ?: ""
                
                // Replace: add new
                val newTargetReaction = newReactions.find { it.emoji == emoji }
                if (newTargetReaction != null) {
                    val targetIndex = newReactions.indexOf(newTargetReaction)
                    newReactions[targetIndex] = newTargetReaction.copy(count = (newTargetReaction.count ?: 0) + 1, mine = true)
                } else {
                    newReactions.add(com.manjugroups.m_connect.network.ReactionData(emoji = emoji, count = 1, mine = true, staffIds = listOf(myStaffId)))
                }
                added = true
            }
        } else {
            // Add new
            val newTargetReaction = newReactions.find { it.emoji == emoji }
            if (newTargetReaction != null) {
                val targetIndex = newReactions.indexOf(newTargetReaction)
                newReactions[targetIndex] = newTargetReaction.copy(count = (newTargetReaction.count ?: 0) + 1, mine = true)
            } else {
                newReactions.add(com.manjugroups.m_connect.network.ReactionData(emoji = emoji, count = 1, mine = true, staffIds = listOf(myStaffId)))
            }
            added = true
        }

        // Apply optimistic UI update locally
        val updatedMessage = message.copy(reactions = newReactions)
        messages[index] = updatedMessage
        renderMessages(scrollToBottom = false)

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                if (removed && !added) {
                    api.removeReaction(session.bearerToken, ReactionRequest(messageId, removedEmoji))
                } else if (removed && added) {
                    api.removeReaction(session.bearerToken, ReactionRequest(messageId, removedEmoji))
                    api.addReaction(session.bearerToken, ReactionRequest(messageId, emoji))
                } else if (added && !removed) {
                    api.addReaction(session.bearerToken, ReactionRequest(messageId, emoji))
                }
            }.onFailure {
                toast("Unable to update reaction")
                // Revert on failure (simplified to reload)
                loadInitialMessages(scrollToBottom = false)
            }
        }
    }

    override fun onCopy(text: String) {
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Chat Message", text)
        clipboard.setPrimaryClip(clip)
        toast("Message copied")
    }

    override fun onDelete(messageId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.deleteMessage(session.bearerToken, DeleteMessageRequest(messageId))
            }.onSuccess {
                purgeMessageFromCache(listOf(messageId))
                loadInitialMessages(scrollToBottom = false)
            }.onFailure {
                toast("Unable to delete message")
            }
        }
    }

    private fun purgeMessageFromCache(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        val ids = messageIds.toHashSet()
        val removed = messages.removeAll { it.id in ids }
        originalBodyCache.keys.removeAll(ids)
        if (removed) {
            renderMessages(scrollToBottom = false)
        }
        persistMessageCache()
    }

    override fun onForward(messageId: String) {
        val msg = messages.firstOrNull { it.id == messageId } ?: return
        openForwardPicker(listOf(msg))
    }

    private var inlineSearchMatches: List<String> = emptyList()
    private var inlineSearchCursor: Int = -1

    private fun cacheKey(): String? =
        channelId?.let { "channel-$it" } ?: conversationId?.let { "conversation-$it" }

    private fun persistMessageCache() {
        val key = cacheKey() ?: return
        val ctx = context?.applicationContext ?: return
        ChatMessageCache.save(ctx, key, messages.toList())
    }

    private fun showChatHeaderMenu(anchor: View) {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.popup_chat_conversation_menu, null)
        val popup = PopupWindow(
            view,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 14f
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        view.findViewById<TextView>(R.id.tvConvMenuMute).text =
            if (chatMuted) "Unmute" else "Set as silent"
        view.findViewById<View>(R.id.convMenuMute).setOnClickListener {
            popup.dismiss()
            toggleConversationMute(!chatMuted)
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val x = (loc[0] + anchor.width - view.measuredWidth).coerceAtLeast(16)
        val y = loc[1] + anchor.height + 8
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    private fun toggleConversationMute(mute: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                if (channelId != null) {
                    api.setChannelMute(
                        session.bearerToken,
                        com.manjugroups.m_connect.network.SetMuteRequest(
                            channelId = channelId,
                            muted = mute
                        )
                    )
                } else if (conversationId != null) {
                    api.setConversationMute(
                        session.bearerToken,
                        com.manjugroups.m_connect.network.SetMuteRequest(
                            conversationId = conversationId,
                            muted = mute
                        )
                    )
                } else null
            }
            if (_binding == null) return@launch
            if (result.isSuccess && result.getOrNull() != null) {
                chatMuted = mute
                toast(if (mute) "Muted this chat" else "Unmuted this chat")
            } else {
                toast("Couldn't update mute")
            }
        }
    }

    private fun setupInlineSearch() {
        binding.btnInlineSearchClose.setOnClickListener { hideInlineSearch() }
        binding.etInlineSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                runInlineSearch(s?.toString().orEmpty())
            }
        })
        binding.btnInlineSearchPrev.setOnClickListener { stepInlineSearch(-1) }
        binding.btnInlineSearchNext.setOnClickListener { stepInlineSearch(+1) }
    }

    private fun showInlineSearch() {
        if (_binding == null) return
        binding.headerContainer.visibility = View.GONE
        binding.inlineSearchBar.visibility = View.VISIBLE
        binding.etInlineSearch.setText("")
        binding.etInlineSearch.requestFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etInlineSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideInlineSearch() {
        if (_binding == null) return
        binding.inlineSearchBar.visibility = View.GONE
        binding.headerContainer.visibility = View.VISIBLE
        chatAdapter.clearSearchHighlight()
        inlineSearchMatches = emptyList()
        inlineSearchCursor = -1
        binding.tvInlineSearchCount.visibility = View.GONE
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etInlineSearch.windowToken, 0)
    }

    private fun runInlineSearch(query: String) {
        if (_binding == null) return
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            chatAdapter.clearSearchHighlight()
            inlineSearchMatches = emptyList()
            inlineSearchCursor = -1
            binding.tvInlineSearchCount.visibility = View.GONE
            return
        }
        val needle = trimmed.lowercase(Locale.getDefault())
        val matches = messages
            .asSequence()
            .filter { it.isDeleted != true }
            .filter { (it.body ?: "").lowercase(Locale.getDefault()).contains(needle) }
            .mapNotNull { it.id }
            .toList()
        inlineSearchMatches = matches
        inlineSearchCursor = if (matches.isEmpty()) -1 else matches.lastIndex
        chatAdapter.setSearchHighlight(matches)
        binding.tvInlineSearchCount.visibility = View.VISIBLE
        updateInlineSearchCountLabel()
        if (matches.isNotEmpty()) scrollToMessage(matches.last())
    }

    private fun stepInlineSearch(delta: Int) {
        if (inlineSearchMatches.isEmpty()) return
        inlineSearchCursor = (inlineSearchCursor + delta).let {
            (it + inlineSearchMatches.size) % inlineSearchMatches.size
        }
        scrollToMessage(inlineSearchMatches[inlineSearchCursor])
        updateInlineSearchCountLabel()
    }

    private fun updateInlineSearchCountLabel() {
        if (_binding == null) return
        binding.tvInlineSearchCount.text = if (inlineSearchMatches.isEmpty()) {
            "No matches"
        } else {
            "${inlineSearchCursor + 1} / ${inlineSearchMatches.size}"
        }
    }

    override fun onSelectMore(messageId: String) {
        if (!chatAdapter.selectionMode) {
            chatAdapter.setSelectionMode(true)
        }
        chatAdapter.toggleSelected(messageId)
        updateSelectionToolbar()
    }

    private fun setupSelectionToolbar() {
        binding.btnSelectionCancel.setOnClickListener { exitSelectionMode() }
        binding.btnSelectionDelete.setOnClickListener {
            val ids = chatAdapter.selectedIdsSnapshot()
            if (ids.isEmpty()) { exitSelectionMode(); return@setOnClickListener }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Delete ${ids.size} message${if (ids.size == 1) "" else "s"}?")
                .setMessage("This will permanently delete the selected messages.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        var failures = 0
                        val deleted = mutableListOf<String>()
                        ids.forEach { id ->
                            runCatching {
                                api.deleteMessage(
                                    session.bearerToken,
                                    com.manjugroups.m_connect.network.DeleteMessageRequest(id)
                                )
                            }.onSuccess { deleted += id }
                                .onFailure { failures++ }
                        }
                        purgeMessageFromCache(deleted)
                        exitSelectionMode()
                        if (failures > 0) toast("Deleted with $failures error(s)")
                        loadInitialMessages(scrollToBottom = false)
                    }
                }
                .show()
        }
        binding.btnSelectionForward.setOnClickListener {
            val ids = chatAdapter.selectedIdsSnapshot()
            val msgs = ids.mapNotNull { id -> messages.firstOrNull { it.id == id } }
            if (msgs.isEmpty()) { exitSelectionMode(); return@setOnClickListener }
            openForwardPicker(msgs)
        }
    }

    private fun updateSelectionToolbar() {
        if (_binding == null) return
        val count = chatAdapter.selectionCount()
        if (chatAdapter.selectionMode && count > 0) {
            binding.selectionToolbar.visibility = View.VISIBLE
            binding.headerContainer.visibility = View.GONE
            binding.tvSelectionCount.text = "$count selected"
        } else if (chatAdapter.selectionMode && count == 0) {
            exitSelectionMode()
        } else {
            binding.selectionToolbar.visibility = View.GONE
            binding.headerContainer.visibility = View.VISIBLE
        }
    }

    private fun exitSelectionMode() {
        chatAdapter.setSelectionMode(false)
        updateSelectionToolbar()
    }

    override fun onInfo(messageId: String) {
        val msg = messages.firstOrNull { it.id == messageId } ?: return
        showMessageInfoDialog(msg)
    }

    private fun showMessageInfoDialog(msg: MessageData) {
        val sentMs = msg.creationTime?.let {
            if (it < 10_000_000_000.0) (it * 1000).toLong() else it.toLong()
        } ?: 0L
        ChatMessageInfoFragment.newInstance(
            body = msg.body.orEmpty(),
            sentMillis = sentMs,
            sender = msg.senderName,
            isMine = msg.senderId == myStaffId,
            isEdited = msg.isEdited == true,
            replyCount = msg.replyCount ?: 0,
            attachmentCount = msg.attachments?.size ?: 0
        ).show(childFragmentManager, "ChatMessageInfo")
    }

    private fun openForwardPicker(toForward: List<MessageData>) {
        if (toForward.isEmpty()) return
        val picker = ChatForwardPickerFragment.newInstance(
            toForward.mapNotNull { it.id }
        )
        picker.setListener(object : ChatForwardPickerFragment.Listener {
            override fun onForwardTo(
                targetConversationId: String?,
                targetChannelId: String?,
                targetName: String
            ) {
                val bodies = toForward.mapNotNull { it.body?.takeIf { b -> b.isNotBlank() } }
                if (bodies.isEmpty()) {
                    toast("Nothing to forward")
                    return
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    var failures = 0
                    bodies.forEach { body ->
                        runCatching {
                            api.sendMessage(
                                session.bearerToken,
                                com.manjugroups.m_connect.network.SendMessageRequest(
                                    channelId = targetChannelId,
                                    conversationId = targetConversationId,
                                    body = body
                                )
                            )
                        }.onFailure { failures++ }
                    }
                    if (failures == 0) toast("Forwarded to $targetName")
                    else toast("Forwarded with $failures error(s)")
                    exitSelectionMode()
                }
            }
        })
        picker.show(childFragmentManager, "ChatForwardPicker")
    }

    private fun openAttachmentUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast("Unable to open attachment")
        }
    }

    private fun insertMention(person: MentionPerson) {
        val text = binding.etMessage.text.toString()
        val atIndex = text.lastIndexOf("@")
        if (atIndex >= 0) {
            val newText = text.substring(0, atIndex + 1) + person.username + " "
            binding.etMessage.setText(newText)
            binding.etMessage.setSelection(newText.length)
        }
        binding.mentionsCard.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(android.graphics.Color.WHITE, true, fullBleed = false)
        }
        startPolling()
    }

    override fun onPause() {
        pollJob?.cancel()
        pollJob = null
        typingDebounceJob?.cancel()
        typingDebounceJob = null
        super.onPause()
    }

    private var attachTilesWired = false

    private fun showAttachGrid() = toggleAttachPanel()

    private fun toggleAttachPanel() {
        if (_binding == null) return
        if (binding.attachPanel.visibility == View.VISIBLE) {
            hideAttachPanel()
        } else {
            openAttachPanel()
        }
    }

    private fun openAttachPanel() {
        if (_binding == null) return
        // Drop the soft keyboard so the panel sits directly under the input.
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMessage.windowToken, 0)

        binding.emojiPanel.visibility = View.GONE
        binding.attachPanel.visibility = View.VISIBLE
        binding.ivAttachIcon.setImageResource(R.drawable.ic_sheet_close)
        wireAttachTiles()
    }

    private fun hideAttachPanel() {
        if (_binding == null) return
        binding.attachPanel.visibility = View.GONE
        binding.ivAttachIcon.setImageResource(R.drawable.ic_chat_plus)
    }

    private fun wireAttachTiles() {
        if (attachTilesWired || _binding == null) return
        val panel = binding.attachPanel
        fun tile(id: Int, onClick: () -> Unit) {
            panel.findViewById<View>(id)?.setOnClickListener {
                hideAttachPanel()
                onClick()
            }
        }
        tile(R.id.tileAttachImage) { pickAttachmentsLauncher.launch(arrayOf("image/*")) }
        tile(R.id.tileAttachVideo) { pickAttachmentsLauncher.launch(arrayOf("video/*")) }
        tile(R.id.tileAttachAudio) { pickAttachmentsLauncher.launch(arrayOf("audio/*")) }
        tile(R.id.tileAttachLocation) { toast("Location sharing coming soon") }
        tile(R.id.tileAttachDocument) { pickAttachmentsLauncher.launch(arrayOf("*/*")) }
        tile(R.id.tileAttachContact) { launchContactPicker() }
        tile(R.id.tileAttachCamera) { launchCamera() }
        attachTilesWired = true
    }

    private val cameraImageUri: android.net.Uri? get() = pendingCameraUri
    private var pendingCameraUri: android.net.Uri? = null
    private var pendingCameraMode: CameraMode = CameraMode.PHOTO

    private enum class CameraMode { PHOTO, VIDEO }

    private val takePictureLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { saved ->
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (saved == true && uri != null) {
                val resolver = requireContext().contentResolver
                val mime = resolver.getType(uri) ?: "image/jpeg"
                val size = runCatching {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull() ?: 0L
                val name = "Photo-${System.currentTimeMillis()}.jpg"
                val pending = PendingAttachment(uri = uri, fileName = name, fileType = mime, fileSize = size)
                showImageSendPreview(listOf(pending))
            }
        }

    private val captureVideoLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CaptureVideo()) { saved ->
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (saved == true && uri != null) {
                val resolver = requireContext().contentResolver
                val mime = resolver.getType(uri) ?: "video/mp4"
                val size = runCatching {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull() ?: 0L
                val name = "Video-${System.currentTimeMillis()}.mp4"
                val pending = PendingAttachment(uri = uri, fileName = name, fileType = mime, fileSize = size)
                pendingAttachments += pending
                renderPendingAttachments()
                updateSendIcon()
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCameraForMode(pendingCameraMode) else toast("Camera permission required")
        }

    private fun launchCamera() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_camera_choice, null)
        dialog.setContentView(sheet)
        sheet.findViewById<View>(R.id.btnCameraPhoto).setOnClickListener {
            dialog.dismiss()
            pendingCameraMode = CameraMode.PHOTO
            ensureCameraPermissionThen { launchCameraForMode(CameraMode.PHOTO) }
        }
        sheet.findViewById<View>(R.id.btnCameraVideo).setOnClickListener {
            dialog.dismiss()
            pendingCameraMode = CameraMode.VIDEO
            ensureCameraPermissionThen { launchCameraForMode(CameraMode.VIDEO) }
        }
        dialog.show()
    }

    private fun ensureCameraPermissionThen(action: () -> Unit) {
        val permission = android.Manifest.permission.CAMERA
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), permission)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            cameraPermissionLauncher.launch(permission)
        }
    }

    private fun launchCameraForMode(mode: CameraMode) {
        when (mode) {
            CameraMode.PHOTO -> actuallyLaunchCamera()
            CameraMode.VIDEO -> actuallyLaunchVideoCamera()
        }
    }

    private fun actuallyLaunchCamera() {
        val photoDir = File(requireContext().cacheDir, "chat_photos").apply { mkdirs() }
        val photoFile = File(photoDir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            photoFile
        )
        pendingCameraUri = uri
        runCatching { takePictureLauncher.launch(uri) }
            .onFailure { toast("Unable to open camera") }
    }

    private fun actuallyLaunchVideoCamera() {
        val videoDir = File(requireContext().cacheDir, "chat_videos").apply { mkdirs() }
        val videoFile = File(videoDir, "video_${System.currentTimeMillis()}.mp4")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            videoFile
        )
        pendingCameraUri = uri
        runCatching { captureVideoLauncher.launch(uri) }
            .onFailure { toast("Unable to open video recorder") }
    }

    private val pickContactLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            handlePickedContact(uri)
        }

    private fun launchContactPicker() {
        val intent = android.content.Intent(android.content.Intent.ACTION_PICK).apply {
            type = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
        }
        runCatching { pickContactLauncher.launch(intent) }
            .onFailure { toast("No contacts app available") }
    }

    private fun handlePickedContact(uri: android.net.Uri) {
        if (!isAdded) return
        val projection = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE,
            android.provider.ContactsContract.CommonDataKinds.Phone.LABEL
        )
        val resolver = requireContext().contentResolver
        val (name, number, label) = runCatching {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val n = cursor.getString(0).orEmpty()
                    val num = cursor.getString(1).orEmpty()
                    val type = cursor.getInt(2)
                    val custom = cursor.getString(3)
                    val typeLabel = android.provider.ContactsContract.CommonDataKinds.Phone
                        .getTypeLabel(resources, type, custom).toString()
                    Triple(n, num, typeLabel)
                } else null
            }
        }.getOrNull() ?: return.also { toast("Unable to read contact") }

        if (number.isBlank()) {
            toast("This contact has no phone number")
            return
        }

        showContactConfirmDialog(name.ifBlank { "Contact" }, number, label)
    }

    private fun showContactConfirmDialog(name: String, number: String, label: String) {
        val message = buildString {
            append("Send this contact?\n\n")
            append("• ").append(name).append('\n')
            append("• ").append(label).append(": ").append(number)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Share contact")
            .setMessage(message)
            .setPositiveButton("Send") { _, _ -> sendContactMessage(name, number, label) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendContactMessage(name: String, number: String, label: String) {
        val text = buildString {
            append("📇 Contact\n")
            append(name).append('\n')
            append(label).append(": ").append(number)
        }
        val existing = binding.etMessage.text?.toString().orEmpty()
        val composed = if (existing.isBlank()) text else existing.trim() + "\n\n" + text
        binding.etMessage.setText(composed)
        binding.etMessage.setSelection(composed.length)
        sendMessage()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                pollForMessages()
                pollTyping()
                presencePollCounter++
                if (presencePollCounter % 6 == 0) {
                    refreshPresence()
                }
                delay(2_500)
            }
        }
    }

    private suspend fun refreshChatMetadata() {
        var photoUrl: String? = null
        var initials = "U"
        runCatching {
            when {
                channelId != null -> {
                    val channel = api.getChannel(session.bearerToken, channelId!!).channel
                    if (channel?.name?.isNotBlank() == true) {
                        chatTitle = channel.name
                    }
                    chatMuted = channel?.muted == true
                    val memberCount = channel?.memberCount ?: 0
                    val channelType = channel?.type?.replaceFirstChar { it.uppercase() } ?: "Channel"
                    chatSubtitle = if (memberCount > 0) {
                        "$memberCount members • $channelType"
                    } else {
                        channelType
                    }
                    initials = chatTitle.take(1).uppercase()
                }

                conversationId != null -> {
                    val conversation =
                        api.getConversation(session.bearerToken, conversationId!!).conversation
                    chatMuted = conversation?.muted == true

                    val participant = conversation?.participants
                        ?.firstOrNull { it.id != null && it.id != myStaffId }
                    otherStaffId = participant?.id

                    if (conversation?.displayName?.isNotBlank() == true) {
                        chatTitle = conversation.displayName
                    } else if (participant?.name?.isNotBlank() == true) {
                        chatTitle = participant.name
                    }

                    initials = chatTitle.split(" ")
                        .filter { it.isNotBlank() }
                        .take(2)
                        .joinToString("") { it.first().uppercase() }.ifBlank { "U" }

                    chatSubtitle = buildPresenceSubtitle(
                        staffId = otherStaffId,
                        fallbackStamp = conversation?.lastMessageAt
                    )

                    val staffId = otherStaffId
                    if (staffId != null) {
                        runCatching {
                            val staffResp = api.getStaffDetail(session.bearerToken, staffId)
                            photoUrl = staffResp.staff?.photo
                        }
                    }
                }
            }
        }

        if (_binding != null) {
            binding.tvChatTitle.visibility = View.VISIBLE
            binding.tvChatSubtitle.visibility = View.VISIBLE
            binding.tvChatTitle.text = chatTitle
            applySubtitleState()

            binding.tvHeaderAvatarInitials.text = initials
            if (photoUrl != null) {
                binding.ivHeaderAvatar.load(photoUrl) {
                    crossfade(true)
                }
                binding.tvHeaderAvatarInitials.visibility = View.GONE
            } else {
                binding.ivHeaderAvatar.setImageDrawable(null)
                binding.tvHeaderAvatarInitials.visibility = View.VISIBLE
            }

            // Persist the snapshot so the next entry hydrates instantly.
            context?.applicationContext?.let { ctx ->
                ChatMetadataCache.saveChatSnapshot(
                    ctx,
                    cacheKey(),
                    ChatMetadataCache.ChatSnapshot(
                        title = chatTitle,
                        subtitle = chatSubtitle,
                        photoUrl = photoUrl,
                        initials = initials,
                        muted = chatMuted,
                        otherStaffId = otherStaffId,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun hydrateHeaderFromCache() {
        if (_binding == null) return
        val ctx = context?.applicationContext ?: return
        val snap = ChatMetadataCache.loadChatSnapshot(ctx, cacheKey()) ?: return
        snap.title?.takeIf { it.isNotBlank() }?.let {
            chatTitle = it
            binding.tvChatTitle.text = it
            binding.tvChatTitle.visibility = View.VISIBLE
        }
        snap.subtitle?.takeIf { it.isNotBlank() }?.let {
            chatSubtitle = it
        }
        applySubtitleState()
        chatMuted = snap.muted
        snap.otherStaffId?.takeIf { it.isNotBlank() }?.let { otherStaffId = it }
        snap.initials?.takeIf { it.isNotBlank() }?.let { binding.tvHeaderAvatarInitials.text = it }
        val photo = snap.photoUrl
        if (!photo.isNullOrBlank()) {
            binding.ivHeaderAvatar.load(photo) { crossfade(false) }
            binding.tvHeaderAvatarInitials.visibility = View.GONE
        } else {
            binding.tvHeaderAvatarInitials.visibility = View.VISIBLE
        }
    }

    private suspend fun buildPresenceSubtitle(
        staffId: String?,
        fallbackStamp: Long?
    ): String {
        if (staffId.isNullOrBlank()) return formatLastSeen(fallbackStamp)
        return runCatching {
            val presence = api.getPresence(session.bearerToken, staffId = staffId).presence
            if (presence?.status == "online") {
                "Online"
            } else {
                formatLastSeen(presence?.lastSeenAt ?: fallbackStamp)
            }
        }.getOrDefault(formatLastSeen(fallbackStamp))
    }

    private suspend fun refreshPresence() {
        val staffId = otherStaffId ?: return
        if (channelId != null) return
        val updated = buildPresenceSubtitle(staffId, fallbackStamp = null)
        if (chatSubtitle != updated) {
            chatSubtitle = updated
            applySubtitleState()
        }
    }

    private fun applySubtitleState() {
        if (_binding == null) return
        when {
            isRecording -> {
                // recordingTimerTask drives the text + color directly
            }
            !currentTypingText.isNullOrBlank() -> {
                binding.tvChatSubtitle.text = currentTypingText
                binding.tvChatSubtitle.setTextColor(subtitleColorTyping)
            }
            else -> {
                binding.tvChatSubtitle.text = chatSubtitle
                binding.tvChatSubtitle.setTextColor(subtitleColorMuted)
            }
        }
    }

    private fun formatLastSeen(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0L) return "Last seen recently"
        val now = Calendar.getInstance()
        val past = Calendar.getInstance().apply { timeInMillis = timestamp }
        val sameDay = now.get(Calendar.YEAR) == past.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == past.get(Calendar.DAY_OF_YEAR)
        val time = SimpleDateFormat("h:mma", Locale.getDefault()).format(Date(timestamp))
        return if (sameDay) "Last seen Today $time" else {
            val day = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestamp))
            "Last seen $day $time"
        }
    }

    private fun openContactInfo() {
        val fragment = ChatContactInfoFragment.newInstance(
            channelId = channelId,
            conversationId = conversationId,
            title = chatTitle,
            otherStaffId = otherStaffId
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun handlePickedAttachments(uris: List<Uri>) {
        if (!isAdded) return
        val resolver = requireContext().contentResolver
        val existingKeys = pendingAttachments.map { it.uri.toString() }.toMutableSet()
        val availableSlots = MAX_ATTACHMENT_COUNT - pendingAttachments.size

        if (availableSlots <= 0) {
            toast("You can attach up to $MAX_ATTACHMENT_COUNT files")
            return
        }

        val accepted = mutableListOf<PendingAttachment>()
        uris.forEach { uri ->
            if (accepted.size >= availableSlots) return@forEach
            val key = uri.toString()
            if (existingKeys.contains(key)) return@forEach

            val meta = readAttachmentMeta(uri) ?: return@forEach
            if (meta.fileSize > MAX_ATTACHMENT_SIZE_BYTES) {
                toast("${meta.fileName} is larger than 15 MB")
                return@forEach
            }

            runCatching {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            accepted += meta
            existingKeys += key
        }

        if (accepted.isEmpty()) return

        val allImages = accepted.all { it.fileType.startsWith("image/") }
        if (allImages) {
            showImageSendPreview(accepted)
        } else {
            pendingAttachments += accepted
            renderPendingAttachments()
            updateSendIcon()
        }
    }

    private fun readAttachmentMeta(uri: Uri): PendingAttachment? {
        val resolver = requireContext().contentResolver
        var fileName: String? = null
        var fileSize: Long? = null

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) fileName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) fileSize = cursor.getLong(sizeIndex)
                }
            }

        val safeName = fileName?.takeIf { it.isNotBlank() } ?: "Attachment"
        val safeSize = fileSize ?: 0L
        val mimeType = resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }

        return PendingAttachment(
            uri = uri,
            fileName = safeName,
            fileType = mimeType,
            fileSize = safeSize
        )
    }

    private fun renderPendingAttachments() {
        if (_binding == null) return
        binding.selectedAttachmentsContainer.removeAllViews()
        binding.selectedAttachmentsScroll.visibility =
            if (pendingAttachments.isEmpty()) View.GONE else View.VISIBLE

        pendingAttachments.forEachIndexed { index, attachment ->
            binding.selectedAttachmentsContainer.addView(
                createPendingAttachmentView(attachment).apply {
                    val params = layoutParams as LinearLayout.LayoutParams
                    if (index < pendingAttachments.lastIndex) {
                        params.marginEnd = dpToPx(8)
                    }
                    layoutParams = params
                }
            )
        }
    }

    private fun createPendingAttachmentView(attachment: PendingAttachment): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.bg_chat_action_card)
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))

            if (attachment.fileType.startsWith("image/")) {
                addView(ImageView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    load(attachment.uri) {
                        transformations(coil.transform.RoundedCornersTransformation(dpToPx(4).toFloat()))
                    }
                })
            } else {
                addView(buildAttachmentBadge(attachment.fileType))
            }

            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(100), // Fixed width for name preview
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dpToPx(10)
                    marginEnd = dpToPx(10)
                }

                addView(TextView(requireContext()).apply {
                    text = attachment.fileName
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(resolveColor(R.attr.colorForegroundPrimary))
                    textSize = 12f
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_semibold)
                })

                addView(TextView(requireContext()).apply {
                    text = humanFileSize(attachment.fileSize)
                    setTextColor(resolveColor(R.attr.colorForegroundMuted))
                    textSize = 11f
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_regular)
                })
            })

            addView(ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24))
                setImageResource(R.drawable.ic_sheet_close)
                setColorFilter(resolveColor(R.attr.colorForegroundPrimary))
                setBackgroundResource(R.drawable.bg_home_new_action_circle)
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                isClickable = true
                isFocusable = true
                contentDescription = "Remove attachment"
                setOnClickListener {
                    pendingAttachments.remove(attachment)
                    renderPendingAttachments()
                    updateSendIcon()
                }
            })
        }
    }

    private suspend fun loadInitialMessages(scrollToBottom: Boolean) {
        runCatching {
            // Match the web client's `initialNumItems=30` — smaller payload
            // means faster first paint on a cold chat open. Older messages
            // load on scroll-to-top via the existing pagination path.
            if (channelId != null) {
                api.getChannelMessages(
                    token = session.bearerToken,
                    channelId = channelId!!,
                    numItems = 30
                )
            } else {
                api.getConversationMessages(
                    token = session.bearerToken,
                    conversationId = conversationId!!,
                    numItems = 30
                )
            }
        }.onSuccess { response ->
            val mainMessages = (response.page ?: response.messages ?: emptyList()).reversed()

            // Snapshot original bodies from main feed
            mainMessages.forEach { msg ->
                val id = msg.id ?: return@forEach
                if (msg.isDeleted != true && !originalBodyCache.containsKey(id)) {
                    originalBodyCache[id] = msg
                }
            }

            // First-paint: render the main feed immediately
            val byId = linkedMapOf<String, MessageData>()
            mainMessages.forEach { msg -> msg.id?.let { byId[it] = msg } }
            val mergedInitial = byId.values
                .sortedBy { it.creationTime ?: 0.0 }
                .toList()

            // If cache already produced an identical list, skip the second
            // render — that re-bind is exactly what made re-entry feel glitchy.
            val cacheMatchesServer = messages.size == mergedInitial.size &&
                messages.zip(mergedInitial).all { (cached, fresh) -> cached == fresh }

            messages.clear()
            messages.addAll(mergedInitial)
            latestMessageTime = messages.maxOfOrNull { it.creationTime ?: 0.0 } ?: 0.0

            val initialParentMap = mutableMapOf<String, MessageData>()
            mergedInitial.filter { it.isDeleted == true }.forEach { msg ->
                msg.id?.let { initialParentMap[it] = originalBodyCache[it] ?: msg }
            }
            chatAdapter.setParentMessageCache(initialParentMap)
            if (!cacheMatchesServer) {
                renderMessages(scrollToBottom = scrollToBottom)
            } else {
                // Still need to persist the freshly-stamped cache (timestamps
                // get refreshed) and let the poll loop handle future deltas.
                persistMessageCache()
            }

            // Background: fetch thread replies + missing parents + audio durations,
            // then re-render with the enriched list.
            viewLifecycleOwner.lifecycleScope.launch {
                enrichMessages(mainMessages, byId)
            }
        }.onFailure {
            renderMessages(scrollToBottom = false)
            toast("Unable to load messages")
        }
    }

    private suspend fun enrichMessages(
        mainMessages: List<MessageData>,
        byId: MutableMap<String, MessageData>
    ) {
        // Per api-docs.md: top-level message lists already hide thread replies,
        // and each message is enriched server-side with attachments/reactions/
        // mentions. Only fan out reply requests for messages that actually
        // have replies (replyCount > 0) so we don't trigger N redundant HTTP
        // round-trips on every chat open — that fan-out was the residual
        // flicker/delay source.
        val threadedParentIds = mainMessages
            .filter { (it.replyCount ?: 0) > 0 }
            .mapNotNull { it.id }

        val replyMessages = if (threadedParentIds.isEmpty()) emptyList() else coroutineScope {
            threadedParentIds.map { parentId ->
                async {
                    runCatching {
                        api.getMessageReplies(session.bearerToken, parentId)
                    }.getOrNull()
                }
            }.awaitAll().flatMap { resp -> resp?.page ?: resp?.messages ?: emptyList() }
        }

        if (_binding == null) return

        var listDidChange = false
        replyMessages.forEach { msg ->
            val id = msg.id ?: return@forEach
            if (msg.isDeleted != true && !originalBodyCache.containsKey(id)) {
                originalBodyCache[id] = msg
            }
            if (!byId.containsKey(id)) {
                byId[id] = msg
                listDidChange = true
            }
        }

        if (listDidChange) {
            val merged = byId.values
                .sortedBy { it.creationTime ?: 0.0 }
                .toList()
            messages.clear()
            messages.addAll(merged)
            latestMessageTime = messages.maxOfOrNull { it.creationTime ?: 0.0 } ?: latestMessageTime
            renderMessages(scrollToBottom = false)
        }

        // Resolve parent-of-reply quotes — only for newly-fetched replies whose
        // parent isn't already known. With threads gated above, this collapses
        // to "do nothing" for the common no-thread case.
        val missingParentIds = byId.values
            .mapNotNull { it.parentMessageId }
            .filter { it.isNotBlank() && !byId.containsKey(it) }
            .distinct()
        val parentMap = mutableMapOf<String, MessageData>()
        byId.values.filter { it.isDeleted == true }.forEach { msg ->
            msg.id?.let { parentMap[it] = originalBodyCache[it] ?: msg }
        }

        if (missingParentIds.isNotEmpty()) {
            val parents = coroutineScope {
                missingParentIds.map { pid ->
                    async {
                        runCatching {
                            api.getMessage(session.bearerToken, pid)
                        }.getOrNull()?.message
                    }
                }.awaitAll().filterNotNull()
            }
            parents.forEach { p ->
                val pid = p.id ?: return@forEach
                if (p.isDeleted != true && !originalBodyCache.containsKey(pid)) {
                    originalBodyCache[pid] = p
                }
                parentMap[pid] = originalBodyCache[pid] ?: p
            }
        }
        if (_binding != null) {
            chatAdapter.setParentMessageCache(parentMap)
            probeAudioDurations(byId.values.toList())
        }
    }

    private suspend fun pollForMessages() {
        val channel = channelId
        val conversation = conversationId
        if (channel == null && conversation == null) return

        runCatching {
            api.pollMessages(
                token = session.bearerToken,
                channelId = channel,
                conversationId = conversation,
                after = latestMessageTime
            )
        }.onSuccess { response ->
            if (!response.success || response.messages.isEmpty()) return@onSuccess

            var appended = false
            response.messages.forEach { incoming ->
                val existingIndex = messages.indexOfFirst { it.id == incoming.id }
                if (existingIndex >= 0) {
                    messages[existingIndex] = incoming
                } else {
                    messages.add(incoming)
                    appended = true
                }
            }

            latestMessageTime = messages.maxOfOrNull { it.creationTime ?: 0.0 } ?: latestMessageTime
            renderMessages(scrollToBottom = appended)
            if (appended) {
                markRead()
            }
        }
    }

    private suspend fun pollTyping() {
        val channel = channelId
        val conversation = conversationId
        if (channel == null && conversation == null) return

        runCatching {
            api.getTyping(
                token = session.bearerToken,
                channelId = channel,
                conversationId = conversation
            )
        }.onSuccess { response ->
            if (_binding == null) return@onSuccess
            val typingEntries = response.typing
                .filter { it.staffId != null && it.staffId != myStaffId }

            val names = typingEntries
                .mapNotNull { it.staffName?.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            currentTypingText = if (names.isEmpty()) {
                null
            } else {
                val isVoiceTyping = typingEntries.any { entry ->
                    val expires = entry.expiresAt ?: 0L
                    expires > 0L && (expires - System.currentTimeMillis()) > 8_000L
                }
                val verb = if (isVoiceTyping) "recording voice" else "typing"
                when (names.size) {
                    1 -> "${names[0]} is $verb..."
                    2 -> "${names[0]} and ${names[1]} are $verb..."
                    else -> "${names[0]} and ${names.size - 1} others are $verb..."
                }
            }
            applySubtitleState()
        }
    }

    private val recordingTimerTask = object : Runnable {
        override fun run() {
            if (isRecording && _binding != null) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                val seconds = (elapsed / 1000) % 60
                val minutes = (elapsed / (1000 * 60)) % 60
                val timeStr = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                binding.tvChatSubtitle.text = "Recording voice... $timeStr"
                binding.tvChatSubtitle.setTextColor(subtitleColorRecording)
                recordingHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun updateRecordingUI() {
        if (_binding == null) return
        if (isRecording) {
            binding.etMessage.isEnabled = false
            updateRecordingHintNormal()
            binding.ivSendIcon.setImageResource(R.drawable.ic_send)
            recordingHandler.post(recordingTimerTask)
            broadcastRecordingTyping()
        } else {
            recordingHandler.removeCallbacks(recordingTimerTask)
            binding.etMessage.isEnabled = true
            binding.etMessage.hint = "Message ..."
            applySubtitleState()
            updateSendIcon()
        }
    }

    private fun updateRecordingHintNormal() {
        if (_binding == null) return
        binding.etMessage.hint = "◀  Slide to cancel"
    }

    private fun updateRecordingHintCancel() {
        if (_binding == null) return
        binding.etMessage.hint = "Release to cancel"
    }

    private fun broadcastRecordingTyping() {
        val channel = channelId
        val conversation = conversationId
        if (channel == null && conversation == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.setTyping(
                    token = session.bearerToken,
                    body = TypingRequest(channelId = channel, conversationId = conversation)
                )
            }
        }
    }

    private fun renderMessages(scrollToBottom: Boolean) {
        if (_binding == null) return
        if (binding.rvMessages.visibility != View.VISIBLE) {
            binding.rvMessages.visibility = View.VISIBLE
        }
        if (binding.skeletonContainer.visibility != View.GONE) {
            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
        }
        hasLoadedMessages = true
        val chatItems = mutableListOf<ChatItem>()
        var lastDayKey: String? = null

        messages
            .forEach { message ->
                val createdMillis = message.creationTime?.toLong() ?: 0L
                val dayKey = dayKey(createdMillis)
                if (dayKey != lastDayKey && createdMillis > 0L) {
                    chatItems.add(ChatItem.DateSeparator(friendlyDateLabel(createdMillis)))
                    lastDayKey = dayKey
                }

                val isMine = message.senderId == myStaffId
                chatItems.add(ChatItem.Message(
                    data = message,
                    isMine = isMine,
                    showAvatar = !isMine && channelId != null,
                    showName = !isMine && channelId != null
                ))
            }

        chatAdapter.submitList(chatItems) {
            if (scrollToBottom && chatItems.isNotEmpty()) {
                binding.rvMessages.scrollToPosition(chatItems.size - 1)
            }
        }
        persistMessageCache()
    }

    private fun dayKey(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
    }

    private fun friendlyDateLabel(timestamp: Long): String {
        val now = Calendar.getInstance()
        val past = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = now.get(Calendar.YEAR) == past.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == past.get(Calendar.DAY_OF_YEAR)
        if (today) return "Today"
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = yesterday.get(Calendar.YEAR) == past.get(Calendar.YEAR) &&
            yesterday.get(Calendar.DAY_OF_YEAR) == past.get(Calendar.DAY_OF_YEAR)
        if (isYesterday) return "Yesterday"
        return SimpleDateFormat("dd MMMM", Locale.getDefault()).format(Date(timestamp))
    }

    private fun buildAttachmentBadge(fileType: String): TextView {
        return TextView(requireContext()).apply {
            text = attachmentBadgeText(fileType)
            gravity = android.view.Gravity.CENTER
            minWidth = dpToPx(34)
            minHeight = dpToPx(34)
            setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
            setBackgroundResource(R.drawable.bg_chat_avatar_circle)
            setTextColor(resolveColor(R.attr.colorAccentPrimary))
            textSize = 10f
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_bold)
        }
    }

    private fun attachmentBadgeText(fileType: String): String {
        val normalized = fileType.lowercase(Locale.getDefault())
        return when {
            normalized.startsWith("image/") -> "IMG"
            normalized.contains("pdf") -> "PDF"
            normalized.contains("sheet") || normalized.contains("excel") -> "XLS"
            normalized.contains("word") || normalized.contains("document") -> "DOC"
            else -> "FILE"
        }
    }

    private fun handleSendOrMic() {
        if (canSendNow()) {
            sendMessage()
        } else {
            if (isRecording) {
                stopRecording(send = true)
            } else {
                checkRecordingPermission()
            }
        }
    }

    private fun checkRecordingPermission() {
        val permission = android.Manifest.permission.RECORD_AUDIO
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            recordAudioPermissionLauncher.launch(permission)
        }
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        runCatching {
            audioFile = File(requireContext().cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            mediaRecorder = MediaRecorder(requireContext()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            updateRecordingUI()
        }.onFailure {
            toast("Failed to start recording")
            isRecording = false
            updateRecordingUI()
        }
    }

    private fun stopRecording(send: Boolean) {
        if (!isRecording) return
        val elapsed = System.currentTimeMillis() - recordingStartTime
        val stopped = runCatching {
            mediaRecorder?.apply {
                stop()
                release()
            }
        }.isSuccess
        mediaRecorder = null
        isRecording = false

        val file = audioFile
        if (!send || !stopped || file == null || !file.exists()) {
            file?.delete()
            audioFile = null
            updateRecordingUI()
            return
        }
        if (elapsed < MIN_RECORDING_MS) {
            file.delete()
            audioFile = null
            toast("Hold to record voice message")
            updateRecordingUI()
            return
        }

        val uri = Uri.fromFile(file)
        val pending = PendingAttachment(
            uri = uri,
            fileName = "Voice-${System.currentTimeMillis()}.m4a",
            fileType = "audio/mp4",
            fileSize = file.length()
        )
        pendingAttachments.add(pending)
        audioFile = null
        sendMessage()
        updateRecordingUI()
    }

    private fun canSendNow(): Boolean {
        val text = binding.etMessage.text?.toString().orEmpty().trim()
        return text.isNotEmpty() || pendingAttachments.isNotEmpty()
    }

    private fun updateSendIcon() {
        if (_binding == null) return
        binding.ivSendIcon.setImageResource(
            if (canSendNow()) R.drawable.ic_send else R.drawable.ic_chat_mic
        )
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty() && pendingAttachments.isEmpty()) return
        if (isSendingMessage) return

        val pendingSnapshot = pendingAttachments.toList()
        val previousText = text
        val parentId = replyingToMessage?.id

        binding.etMessage.setText("")
        if (isEmojiPanelVisible) hideEmojiPanel()
        pendingAttachments.clear()
        cancelReply()
        renderPendingAttachments()
        setComposerBusy(true)
        updateSendIcon()

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val uploadedAttachments = uploadPendingAttachments(pendingSnapshot)
                api.sendMessage(
                    token = session.bearerToken,
                    body = SendMessageRequest(
                        channelId = channelId,
                        conversationId = conversationId,
                        body = previousText,
                        parentMessageId = parentId,
                        attachments = uploadedAttachments
                    )
                )
            }.onSuccess { response ->
                if (!response.success) {
                    restoreComposer(previousText, pendingSnapshot)
                    toast("Failed to send message")
                    return@onSuccess
                }
                appendSentMessage(response.messageId)
                markRead()
            }.onFailure {
                restoreComposer(previousText, pendingSnapshot)
                toast("Network error while sending")
            }

            setComposerBusy(false)
            updateSendIcon()
        }
    }

    private suspend fun appendSentMessage(messageId: String?) {
        if (messageId.isNullOrBlank()) {
            loadInitialMessages(scrollToBottom = true)
            return
        }
        runCatching {
            api.getMessage(session.bearerToken, messageId)
        }.onSuccess { resp ->
            val sent = resp.message
            if (sent == null) {
                loadInitialMessages(scrollToBottom = true)
                return@onSuccess
            }
            val existingIndex = messages.indexOfFirst { it.id == sent.id }
            if (existingIndex >= 0) {
                messages[existingIndex] = sent
            } else {
                messages.add(sent)
            }
            latestMessageTime = messages.maxOfOrNull { it.creationTime ?: 0.0 } ?: latestMessageTime
            renderMessages(scrollToBottom = true)
        }.onFailure {
            loadInitialMessages(scrollToBottom = true)
        }
    }

    private suspend fun uploadPendingAttachments(
        attachments: List<PendingAttachment>
    ): List<com.manjugroups.m_connect.network.MessageAttachmentUpload> {
        return attachments.map { attachment ->
            val bytes = requireContext().contentResolver.openInputStream(attachment.uri)?.use {
                it.readBytes()
            } ?: error("Unable to read ${attachment.fileName}")

            val response = api.uploadStorageFile(
                token = session.bearerToken,
                body = bytes.toRequestBody(attachment.fileType.toMediaTypeOrNull())
            )

            if (!response.success || response.storageId.isNullOrBlank()) {
                error(response.error ?: "Upload failed for ${attachment.fileName}")
            }

            com.manjugroups.m_connect.network.MessageAttachmentUpload(
                storageId = response.storageId,
                fileName = attachment.fileName,
                fileType = attachment.fileType,
                fileSize = attachment.fileSize
            )
        }
    }

    private fun restoreComposer(
        text: String,
        attachments: List<PendingAttachment>
    ) {
        if (_binding == null) return
        binding.etMessage.setText(text)
        binding.etMessage.setSelection(binding.etMessage.text?.length ?: 0)
        pendingAttachments.clear()
        pendingAttachments.addAll(attachments)
        renderPendingAttachments()
        updateSendIcon()
    }

    private fun setComposerBusy(isBusy: Boolean) {
        isSendingMessage = isBusy
        if (_binding == null) return
        binding.btnSend.isEnabled = !isBusy
        binding.btnAttach.isEnabled = !isBusy
        binding.etMessage.isEnabled = !isBusy
        binding.btnSend.alpha = if (isBusy) 0.6f else 1f
        binding.btnAttach.alpha = if (isBusy) 0.6f else 1f
    }

    private fun markRead() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                when {
                    channelId != null -> api.markChannelRead(
                        session.bearerToken,
                        ChannelIdRequest(channelId!!)
                    )

                    conversationId != null -> api.markConversationRead(
                        session.bearerToken,
                        ConversationIdRequest(conversationId!!)
                    )

                    else -> null
                }
            }
        }
    }

    private fun sendTypingSignal() {
        val channel = channelId
        val conversation = conversationId
        if ((channel == null && conversation == null) || binding.etMessage.text.isNullOrBlank()) {
            return
        }

        typingDebounceJob?.cancel()
        typingDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(350)
            runCatching {
                api.setTyping(
                    token = session.bearerToken,
                    body = TypingRequest(
                        channelId = channel,
                        conversationId = conversation
                    )
                )
            }
        }
    }

    private val typingWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            val text = s?.toString().orEmpty()
            if (text.endsWith("@")) {
                showMentionsPopup()
            } else if (!text.contains("@")) {
                binding.mentionsCard.visibility = View.GONE
            }
            
            if (!s.isNullOrBlank()) {
                sendTypingSignal()
            }
            updateSendIcon()
        }

        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun showMentionsPopup() {
        binding.mentionsCard.visibility = View.VISIBLE
        val mockPeople = listOf(
            MentionPerson("1", "Dr. Who", "drwho"),
            MentionPerson("2", "Emmett Brown", "docbrown"),
            MentionPerson("3", "James Cole", "jcole"),
            MentionPerson("4", "Titor", "titor")
        )
        mentionAdapter.submitList(mockPeople)
    }

    private fun humanFileSize(sizeBytes: Long): String {
        return Formatter.formatShortFileSize(requireContext(), sizeBytes)
    }

    private fun applyKeyboardAndSystemInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = ime.bottom.coerceAtLeast(sys.bottom)

            binding.bottomBar.setPadding(
                dpToPx(12),
                dpToPx(12),
                dpToPx(12),
                dpToPx(12) + bottomInset
            )

            binding.rvMessages.setPadding(
                binding.rvMessages.paddingLeft,
                binding.rvMessages.paddingTop,
                binding.rvMessages.paddingRight,
                0
            )

            if (ime.bottom > sys.bottom) {
                binding.rvMessages.post {
                    binding.rvMessages.scrollToPosition(chatAdapter.itemCount.coerceAtLeast(1) - 1)
                }
            }
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                binding.rvMessages.post {
                    binding.rvMessages.scrollToPosition(chatAdapter.itemCount.coerceAtLeast(1) - 1)
                }
            }
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun resolveColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    override fun onDestroyView() {
        stopAudioProgressPolling()
        exoPlayer?.release()
        exoPlayer = null
        SkeletonUtils.stopAll()
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(true)
            main.setTopBarAppearance(android.graphics.Color.parseColor("#0B61CA"), false, fullBleed = false)
        }
        binding.etMessage.removeTextChangedListener(typingWatcher)
        pollJob?.cancel()
        pollJob = null
        typingDebounceJob?.cancel()
        typingDebounceJob = null
        super.onDestroyView()
        _binding = null
    }

    private data class PendingAttachment(
        val uri: Uri,
        val fileName: String,
        val fileType: String,
        val fileSize: Long
    )

    /**
     * Persists an in-memory edited bitmap to a temp cache file and returns a
     * PendingAttachment that points to it, so the regular send pipeline picks
     * it up. Returns null if the write fails.
     */
    private fun persistEditedBitmap(bitmap: Bitmap): PendingAttachment? {
        return try {
            val dir = java.io.File(requireContext().cacheDir, "chat_edits").apply { mkdirs() }
            val file = java.io.File(dir, "edit_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { os ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, os)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            PendingAttachment(
                uri = uri,
                fileName = "Edit-${System.currentTimeMillis()}.jpg",
                fileType = "image/jpeg",
                fileSize = file.length()
            )
        } catch (_: Exception) {
            null
        }
    }

    private val drawColorPalette = intArrayOf(
        android.graphics.Color.parseColor("#FFFFFF"),
        android.graphics.Color.parseColor("#101828"),
        android.graphics.Color.parseColor("#F04438"),
        android.graphics.Color.parseColor("#F79009"),
        android.graphics.Color.parseColor("#EAB308"),
        android.graphics.Color.parseColor("#12B76A"),
        android.graphics.Color.parseColor("#0B61CA"),
        android.graphics.Color.parseColor("#7A5AF8"),
        android.graphics.Color.parseColor("#EC4899")
    )

    private fun setupDrawToolbar(
        root: View,
        editView: MediaEditView,
        colorRow: LinearLayout
    ) {
        val swatchSize = dpToPx(28)
        val gap = dpToPx(6)
        val swatchViews = mutableListOf<View>()
        drawColorPalette.forEachIndexed { idx, color ->
            val swatch = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
                    if (idx > 0) marginStart = gap
                }
                background = androidx.core.content.ContextCompat
                    .getDrawable(requireContext(), R.drawable.bg_edit_color_swatch)
                    ?.mutate()
                    ?.also { (it as? android.graphics.drawable.GradientDrawable)?.setColor(color) }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    editView.brushColor = color
                    swatchViews.forEachIndexed { i, v ->
                        v.scaleX = if (i == idx) 1.3f else 1f
                        v.scaleY = if (i == idx) 1.3f else 1f
                    }
                }
            }
            colorRow.addView(swatch)
            swatchViews += swatch
        }
        // Select white by default
        swatchViews.firstOrNull()?.apply { scaleX = 1.3f; scaleY = 1.3f }

        val penBtn = root.findViewById<android.widget.TextView>(R.id.btnBrushPen)
        val hlBtn = root.findViewById<android.widget.TextView>(R.id.btnBrushHighlight)
        val mkBtn = root.findViewById<android.widget.TextView>(R.id.btnBrushMarker)
        val brushButtons = listOf(
            penBtn to MediaEditView.BrushType.PEN,
            hlBtn to MediaEditView.BrushType.HIGHLIGHTER,
            mkBtn to MediaEditView.BrushType.MARKER
        )
        fun applyBrushSelection(selected: MediaEditView.BrushType) {
            editView.brushType = selected
            brushButtons.forEach { (btn, type) ->
                val on = type == selected
                btn.background = if (on)
                    androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_edit_chip_selected)
                else null
                btn.setTextColor(
                    if (on) android.graphics.Color.parseColor("#101828")
                    else android.graphics.Color.parseColor("#CFDBEC")
                )
            }
        }
        brushButtons.forEach { (btn, type) -> btn.setOnClickListener { applyBrushSelection(type) } }

        val sBtn = root.findViewById<android.widget.TextView>(R.id.btnSizeSmall)
        val mBtn = root.findViewById<android.widget.TextView>(R.id.btnSizeMedium)
        val lBtn = root.findViewById<android.widget.TextView>(R.id.btnSizeLarge)
        val sizeButtons = listOf(sBtn to 6f, mBtn to 12f, lBtn to 22f)
        fun applySizeSelection(selectedWidth: Float) {
            editView.brushStrokeWidth = selectedWidth
            sizeButtons.forEach { (btn, w) ->
                val on = w == selectedWidth
                btn.background = if (on)
                    androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_edit_chip_selected)
                else null
                btn.setTextColor(
                    if (on) android.graphics.Color.parseColor("#101828")
                    else android.graphics.Color.parseColor("#CFDBEC")
                )
            }
        }
        sizeButtons.forEach { (btn, w) -> btn.setOnClickListener { applySizeSelection(w) } }
        applySizeSelection(12f)
    }

    private fun setupCropToolbar(root: View, editView: MediaEditView) {
        val freeBtn = root.findViewById<android.widget.TextView>(R.id.btnRatioFree)
        val sqBtn = root.findViewById<android.widget.TextView>(R.id.btnRatioSquare)
        val r43Btn = root.findViewById<android.widget.TextView>(R.id.btnRatio43)
        val r169Btn = root.findViewById<android.widget.TextView>(R.id.btnRatio169)
        val rotateBtn = root.findViewById<View>(R.id.btnCropRotate)
        val ratioButtons = listOf(
            freeBtn to (null as Float?),
            sqBtn to 1f,
            r43Btn to (4f / 3f),
            r169Btn to (16f / 9f)
        )
        fun applyRatio(selected: Float?) {
            editView.cropAspectRatio = selected
            ratioButtons.forEach { (btn, ratio) ->
                val on = ratio == selected
                btn.background = if (on)
                    androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_edit_chip_selected)
                else null
                btn.setTextColor(
                    if (on) android.graphics.Color.parseColor("#101828")
                    else android.graphics.Color.parseColor("#CFDBEC")
                )
            }
        }
        ratioButtons.forEach { (btn, ratio) -> btn.setOnClickListener { applyRatio(ratio) } }
        rotateBtn.setOnClickListener { editView.rotateCw90() }
    }

    private fun showAddTextSheet(onAdd: (String) -> Unit) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val sheet = layoutInflater.inflate(R.layout.bottom_sheet_add_text, null)
        dialog.setContentView(sheet)
        val input = sheet.findViewById<android.widget.EditText>(R.id.etAddText)
        sheet.findViewById<View>(R.id.btnAddTextCancel).setOnClickListener { dialog.dismiss() }
        sheet.findViewById<View>(R.id.btnAddTextDone).setOnClickListener {
            val text = input.text?.toString()?.trim().orEmpty()
            dialog.dismiss()
            onAdd(text)
        }
        dialog.setOnShowListener {
            input.requestFocus()
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun savePendingAttachmentToGallery(attachment: PendingAttachment) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val ctx = context?.applicationContext ?: return@runCatching false
                    val resolver = ctx.contentResolver
                    val isVideo = attachment.fileType.startsWith("video/")
                    val collection = if (isVideo) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            android.provider.MediaStore.Video.Media.getContentUri(
                                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                            )
                        else android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            android.provider.MediaStore.Images.Media.getContentUri(
                                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                            )
                        else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, attachment.fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, attachment.fileType)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val rel = (if (isVideo) android.os.Environment.DIRECTORY_MOVIES
                            else android.os.Environment.DIRECTORY_PICTURES) + "/Mconnect"
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, rel)
                        }
                    }
                    val dest = resolver.insert(collection, values) ?: return@runCatching false
                    resolver.openOutputStream(dest)?.use { out ->
                        resolver.openInputStream(attachment.uri)?.use { it.copyTo(out) }
                    }
                    true
                }.getOrDefault(false)
            }
            if (_binding != null) toast(if (ok) "Saved to gallery" else "Couldn't save")
        }
    }
}
