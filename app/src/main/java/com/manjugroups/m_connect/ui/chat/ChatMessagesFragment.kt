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
import coil.transform.CircleCropTransformation
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import android.widget.EditText
import com.manjugroups.m_connect.network.StaffData
import com.manjugroups.m_connect.network.StartDmRequest
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
import com.manjugroups.m_connect.ui.common.navigateUp
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
    private var otherStaffPhone: String? = null
    private var chatPhotoUrl: String? = null
    private var latestMessageTime: Double = 0.0
    private var currentTypingText: String? = null
    private var presencePollCounter = 0
    private val messages = mutableListOf<MessageData>()
    private val originalBodyCache = mutableMapOf<String, MessageData>()
    private val audioDurationLocalCache = mutableMapOf<String, String>()
    private val staffNameCache = mutableMapOf<String, String>()
    private val pendingAttachments = mutableListOf<PendingAttachment>()
    private val localAttachmentUriMap = mutableMapOf<String, String>()

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
    private var isEmojiPickerInitialized = false
    private var isDocumentPickerMode = false

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

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            loadLocalMediaList()
        }

    private var localMediaAdapter: LocalMediaAdapter? = null

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

        // Show skeleton by default until cache or server loads
        SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
        binding.rvMessages.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            if (messages.isEmpty()) {
                val cached = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ChatMessageCache.load(requireContext().applicationContext, cacheKey())
                }
                if (cached.isNotEmpty() && _binding != null) {
                    messages.addAll(cached)
                    latestMessageTime = messages.maxOfOrNull { it.creationTime ?: 0.0 } ?: 0.0
                    renderMessages(scrollToBottom = true)
                }
            } else {
                renderMessages(scrollToBottom = true)
            }
        }
        binding.btnBack.setOnClickListener { navigateUp() }
        binding.titleGroup.setOnClickListener { openContactInfo() }
        binding.btnSearch.setOnClickListener { showInlineSearch() }
        binding.btnPhone.setOnClickListener {
            val phone = otherStaffPhone
            if (!phone.isNullOrBlank()) {
                dialPhone(phone)
            } else {
                toast("Call feature unavailable (no phone number found)")
            }
        }
        binding.btnPhone.visibility = if (channelId != null) View.GONE else View.VISIBLE
        setupInlineSearch()
        binding.btnChatHeaderMenu.setOnClickListener { showChatHeaderMenu(it) }
        
        binding.btnSend.setOnClickListener {
            if (canSendNow()) {
                sendMessage()
            }
        }

        binding.btnSend.setOnTouchListener { _, event ->
            if (canSendNow()) {
                false
            } else {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        recordTouchStartX = event.rawX
                        recordCancelRequested = false
                        checkRecordingPermission()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isRecording) {
                            val deltaX = recordTouchStartX - event.rawX
                            val threshold = dpToPx(SLIDE_TO_CANCEL_DP.toInt()).toFloat()

                            // Slide constraint: limit sliding so it stops exactly at the trash can
                            val maxSlide = if (binding.animatingMicContainer.left > 0) {
                                binding.animatingMicContainer.left - binding.trashCanContainer.left
                            } else {
                                binding.recordingOverlay.width - dpToPx(80)
                            }.coerceAtLeast(0)
                            val currentSlide = deltaX.coerceIn(0f, maxSlide.toFloat())

                            // Slide the mic icon left
                            binding.animatingMicContainer.translationX = -currentSlide

                            // Fade the "Slide to cancel" text container with distance
                            val fadeRatio = 1f - (currentSlide / threshold).coerceIn(0f, 1f)
                            binding.slideCancelContainer.alpha = fadeRatio
                            binding.slideCancelContainer.translationX = -currentSlide * 0.3f // Slight parallax movement

                            // Cross-fade recording status container (fading out) and trash can container (fading in)
                            binding.recordingStatusContainer.alpha = fadeRatio
                            binding.trashCanContainer.alpha = 1f - fadeRatio

                            if (currentSlide >= threshold) {
                                if (!recordCancelRequested) {
                                    recordCancelRequested = true
                                    updateRecordingHintCancel()

                                    // Scale up, rotate, and highlight trash icon red
                                    binding.ivTrash.animate().cancel()
                                    binding.ivTrash.animate()
                                        .scaleX(1.3f)
                                        .scaleY(1.3f)
                                        .rotation(-20f)
                                        .setDuration(150)
                                        .start()
                                    binding.ivTrash.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#F04438"))
                                }
                            } else {
                                if (recordCancelRequested) {
                                    recordCancelRequested = false
                                    updateRecordingHintNormal()

                                    // Revert trash can size, rotation, and color
                                    binding.ivTrash.animate().cancel()
                                    binding.ivTrash.animate()
                                        .scaleX(1f)
                                        .scaleY(1f)
                                        .rotation(0f)
                                        .setDuration(150)
                                        .start()
                                    binding.ivTrash.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#98A2B3"))
                                }
                            }
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isRecording) {
                            if (recordCancelRequested) {
                                animateDropToTrash()
                            } else {
                                stopRecording(send = true)
                                hideRecordingOverlay()
                            }
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (isRecording) {
                            if (recordCancelRequested) {
                                animateDropToTrash()
                            } else {
                                stopRecording(send = false)
                                hideRecordingOverlay()
                            }
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
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

        binding.btnEmoji.setOnClickListener { toggleEmojiPanel() }
        binding.btnCloseEmojiPanel.setOnClickListener { hideEmojiPanel() }

        binding.etMessage.addTextChangedListener(typingWatcher)
        binding.etMessage.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (isEmojiPanelVisible) hideEmojiPanel()
                if (binding.attachPanel.visibility == View.VISIBLE) hideAttachPanel()
            }
        }
        // Tapping the input even when it already has focus should still
        // dismiss any open share panel so the keyboard slot isn't doubled up.
        binding.etMessage.setOnClickListener {
            if (binding.attachPanel.visibility == View.VISIBLE) hideAttachPanel()
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
            launch {
                refreshChatMetadata()
            }
            launch {
                // If cache hydrated the list, don't force-scroll again after the
                // server refresh — that double scroll is the visible "glitch" on
                // re-entering a chat. Only scroll on a true cold open.
                loadInitialMessages(scrollToBottom = messages.isEmpty())
                markRead()
            }
        }
    }

    private var exoPlayer: ExoPlayer? = null
    private var currentlyPlayingUrl: String? = null
    private var currentlyPlayingStorageId: String? = null
    private var audioProgressJob: Job? = null

    private fun setupAdapters() {
        chatAdapter = ChatMessageAdapter(
            onMessageReactionClick = { message: MessageData, anchor: View ->
                val bodyText = if (!message.body.isNullOrBlank()) {
                    message.body
                } else {
                    val att = message.attachments?.firstOrNull()
                    if (att != null) {
                        val mime = att.fileType.orEmpty().lowercase()
                        when {
                            mime.startsWith("image/") -> "📷 Photo"
                            mime.startsWith("video/") -> "🎥 Video"
                            mime.startsWith("audio/") -> "🎙️ Voice message"
                            else -> "📁 ${att.fileName ?: "Attachment"}"
                        }
                    } else {
                        ""
                    }
                }
                val actions = ChatMessageActionsFragment.newInstance(message.id ?: "", bodyText)
                actions.setCallback(this)
                actions.show(childFragmentManager, "MessageActions")
            },
            onReactionPillClick = { message: MessageData, anchor: View -> showReactionRemovePopup(message, anchor) },
            onAttachmentClick = { url: String, mime: String, storageId: String?, fileName: String? ->
                handleAttachmentClick(url, mime, storageId, fileName)
            },
            onReplyClick = { messageId -> scrollToMessage(messageId) },
            onMessageTap = { _ ->
                updateSelectionToolbar()
                true
            },
            onContactClick = { name, phone ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val formattedPhone = phone.replace("[^0-9]".toRegex(), "")
                    var foundStaff: StaffData? = null
                    runCatching {
                        api.getStaff(session.bearerToken, status = "active")
                    }.onSuccess { response ->
                        foundStaff = response.staff.find { staff ->
                            val sPhone = staff.phone?.replace("[^0-9]".toRegex(), "").orEmpty()
                            sPhone.isNotEmpty() && (sPhone == formattedPhone || formattedPhone.endsWith(sPhone) || sPhone.endsWith(formattedPhone))
                        }
                    }

                    if (foundStaff == null) {
                        runCatching {
                            api.getStaff(session.bearerToken, status = "active")
                        }.onSuccess { response ->
                            foundStaff = response.staff.find { staff ->
                                staff.name?.equals(name, ignoreCase = true) == true
                            }
                        }
                    }

                    val otherStaffId = foundStaff?.id
                    if (otherStaffId == null) {
                        toast("Cannot message: contact is not registered in the app")
                        return@launch
                    }

                    runCatching {
                        api.startDm(session.bearerToken, StartDmRequest(otherStaffId))
                    }.onSuccess { response ->
                        val conversationId = response.conversationId
                        if (!response.success || conversationId == null) {
                            toast("Unable to start direct message")
                            return@onSuccess
                        }
                        
                        parentFragmentManager.beginTransaction()
                            .replace(
                                R.id.fragmentContainer,
                                ChatMessagesFragment.forConversation(
                                    id = conversationId,
                                    name = foundStaff?.name ?: name
                                )
                            )
                            .addToBackStack(null)
                            .commit()
                    }.onFailure {
                        toast("Unable to start direct message")
                    }
                }
            },
            onSaveAsClick = { url, mime, storageId, fileName ->
                handleSaveAsClick(url, mime, storageId, fileName)
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

    private fun handleAttachmentClick(url: String, mime: String, storageId: String?, fileName: String?) {
        if (url.isBlank() && storageId.isNullOrBlank()) {
            toast("Attachment unavailable")
            return
        }

        // Try local playing/viewing from cached URIs or files to eliminate loading lag
        if (!fileName.isNullOrBlank()) {
            val cachedUri = localAttachmentUriMap[fileName]
            if (!cachedUri.isNullOrBlank()) {
                routeAttachment(cachedUri, mime, storageId, fileName)
                return
            }

            val localVideoFile = java.io.File(java.io.File(requireContext().cacheDir, "chat_videos"), fileName)
            if (localVideoFile.exists()) {
                routeAttachment(android.net.Uri.fromFile(localVideoFile).toString(), mime, storageId, fileName)
                return
            }

            val localPhotoFile = java.io.File(java.io.File(requireContext().cacheDir, "chat_photos"), fileName)
            if (localPhotoFile.exists()) {
                routeAttachment(android.net.Uri.fromFile(localPhotoFile).toString(), mime, storageId, fileName)
                return
            }

            val localEditFile = java.io.File(java.io.File(requireContext().cacheDir, "chat_edits"), fileName)
            if (localEditFile.exists()) {
                routeAttachment(android.net.Uri.fromFile(localEditFile).toString(), mime, storageId, fileName)
                return
            }
        }

        if (url.isBlank()) {
            resolveStorageUrl(storageId!!) { resolved ->
                if (resolved.isNullOrBlank()) {
                    toast("Unable to load attachment")
                } else {
                    routeAttachment(resolved, mime, storageId, fileName)
                }
            }
        } else {
            routeAttachment(url, mime, storageId, fileName)
        }
    }

    private fun handleSaveAsClick(url: String, mime: String, storageId: String?, fileName: String?) {
        if (url.isBlank() && storageId.isNullOrBlank()) {
            toast("Attachment unavailable")
            return
        }
        
        val name = fileName ?: "document_${System.currentTimeMillis()}"

        val doSave = { resolvedUrl: String ->
            val ctx = context
            if (ctx != null) {
                val isRemote = resolvedUrl.startsWith("http://", true) || resolvedUrl.startsWith("https://", true)
                if (isRemote) {
                    toast("Downloading…")
                    try {
                        val downloadManager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                        val request = android.app.DownloadManager.Request(Uri.parse(resolvedUrl)).apply {
                            setTitle(name)
                            setDescription("Downloading document")
                            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name)
                            setMimeType(mime)
                        }
                        downloadManager.enqueue(request)
                    } catch (e: Exception) {
                        saveDocumentManual(resolvedUrl, name, mime)
                    }
                } else {
                    saveDocumentManual(resolvedUrl, name, mime)
                }
            }
        }

        if (url.isBlank()) {
            resolveStorageUrl(storageId!!) { resolved ->
                if (resolved.isNullOrBlank()) {
                    toast("Unable to resolve document URL")
                } else {
                    doSave(resolved)
                }
            }
        } else {
            doSave(url)
        }
    }

    private fun saveDocumentManual(url: String, name: String, mime: String) {
        toast("Downloading…")
        viewLifecycleOwner.lifecycleScope.launch {
            val fileUriString = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = java.net.URL(url).openStream().use { it.readBytes() }
                    val ctx = context?.applicationContext ?: return@runCatching null
                    val resolver = ctx.contentResolver

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val collection = android.provider.MediaStore.Downloads.getContentUri(
                            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                        )
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(collection, values) ?: return@runCatching null
                        resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        
                        values.clear()
                        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        uri.toString()
                    } else {
                        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        )
                        val file = File(downloadsDir, name)
                        file.parentFile?.mkdirs()
                        file.writeBytes(bytes)
                        
                        android.media.MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf(mime), null)
                        Uri.fromFile(file).toString()
                    }
                }.getOrNull()
            }
            if (_binding != null) {
                toast(if (fileUriString != null) "Saved to Downloads" else "Couldn't save document")
            }
        }
    }

    private fun routeAttachment(url: String, mime: String, storageId: String? = null, fileName: String? = null) {
        val lowerMime = mime.lowercase(Locale.getDefault())
        val nameLower = fileName?.lowercase(Locale.getDefault()) ?: ""
        val urlLower = url.lowercase(Locale.getDefault())

        val resolvedMime = if (lowerMime == "application/octet-stream" && nameLower.isNotEmpty()) {
            when {
                nameLower.endsWith(".mp4") || nameLower.endsWith(".mov") || nameLower.endsWith(".webm") || nameLower.endsWith(".mkv") || nameLower.endsWith(".3gp") || nameLower.endsWith(".avi") -> "video/mp4"
                nameLower.endsWith(".jpg") || nameLower.endsWith(".jpeg") || nameLower.endsWith(".png") || nameLower.endsWith(".gif") || nameLower.endsWith(".webp") -> "image/jpeg"
                nameLower.endsWith(".mp3") || nameLower.endsWith(".wav") || nameLower.endsWith(".m4a") || nameLower.endsWith(".aac") || nameLower.endsWith(".ogg") -> "audio/mp4"
                nameLower.endsWith(".pdf") -> "application/pdf"
                else -> lowerMime
            }
        } else {
            lowerMime
        }

        val isVideo = resolvedMime.startsWith("video/") ||
            urlLower.endsWith(".mp4") ||
            urlLower.endsWith(".mov") ||
            urlLower.endsWith(".webm") ||
            urlLower.endsWith(".mkv") ||
            urlLower.endsWith(".3gp") ||
            urlLower.endsWith(".avi") ||
            nameLower.endsWith(".mp4") ||
            nameLower.endsWith(".mov") ||
            nameLower.endsWith(".webm") ||
            nameLower.endsWith(".mkv") ||
            nameLower.endsWith(".3gp") ||
            nameLower.endsWith(".avi")

        when {
            resolvedMime.startsWith("image/") -> showImagePreview(url)
            resolvedMime.startsWith("audio/") -> playVoiceMessage(url, resolvedMime, storageId)
            isVideo -> showVideoPreview(url)
            else -> openAttachmentUrl(url, resolvedMime)
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

        // Swap mock picsum image URLs for a real video to prevent indefinite loading spinner in ExoPlayer
        val resolvedUrl = if (url.contains("picsum.photos")) {
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
        } else {
            url
        }

        val playerView = view.findViewById<androidx.media3.ui.PlayerView>(R.id.videoPlayerView)
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(requireContext()).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(resolvedUrl))
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
        val previewSheet = MediaPreviewBottomSheet().apply {
            setAttachments(images)
            setListener(object : MediaPreviewBottomSheet.MediaPreviewListener {
                override fun onMediaSend(attachments: List<PendingAttachment>, caption: String) {
                    if (attachments.isNotEmpty()) {
                        pendingAttachments.addAll(attachments)
                        if (caption.isNotEmpty()) {
                            binding.etMessage.setText(caption)
                        }
                        renderPendingAttachments()
                        updateSendIcon()
                        sendMessage()
                    }
                }

                override fun onAddMoreClicked() {
                    if (isAdded && !requireActivity().isFinishing) {
                        isDocumentPickerMode = false
                        pickAttachmentsLauncher.launch(arrayOf("image/*", "video/*"))
                    }
                }

                override fun onPreviewCancelled() {
                    if (isAdded && !requireActivity().isFinishing) {
                        launchCamera()
                    }
                }
            })
        }
        previewSheet.show(parentFragmentManager, "media_preview")
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
                    name.endsWith(".caf") || name.endsWith(".ogg") ||
                    name.endsWith(".opus") ||
                    name.startsWith("voice-") || name.startsWith("voice_message_")
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
        binding.tvReplyBody.text = getMessagePreviewText(message).ifBlank { "Attachment" }
        binding.etMessage.requestFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etMessage, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun cancelReply() {
        replyingToMessage = null
        binding.replyPreviewCard.visibility = View.GONE
    }

    private fun setupEmojiPicker() {
        if (isEmojiPickerInitialized) return
        val context = context ?: return

        // Inflate custom emoji picker layout
        val pickerLayout = LayoutInflater.from(context).inflate(
            R.layout.layout_custom_emoji_picker,
            binding.emojiPickerContainer,
            false
        )
        binding.emojiPickerContainer.addView(pickerLayout)

        val etEmojiSearch = pickerLayout.findViewById<android.widget.EditText>(R.id.etEmojiSearch)
        val btnSearchClear = pickerLayout.findViewById<ImageView>(R.id.btnSearchClear)
        val categoryTabsContainer = pickerLayout.findViewById<LinearLayout>(R.id.categoryTabsContainer)
        val rvEmojis = pickerLayout.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvEmojis)

        // Set up 7-column Grid Recycler
        val columns = 7
        val adapter = CustomEmojiAdapter(emptyList()) { emoji ->
            val cursorPos = binding.etMessage.selectionStart.coerceAtLeast(0)
            binding.etMessage.text?.insert(cursorPos, emoji)
            binding.etMessage.setSelection(cursorPos + emoji.length)
            CustomEmojiData.addFavorite(context, emoji)
        }
        rvEmojis.layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, columns)
        rvEmojis.adapter = adapter

        var activeCategory = "Recents"

        fun loadCategory(category: String) {
            activeCategory = category
            
            // Highlight selected tab, reset others
            for (i in 0 until categoryTabsContainer.childCount) {
                val tabView = categoryTabsContainer.getChildAt(i) as? ViewGroup ?: continue
                val tvName = tabView.findViewById<TextView>(R.id.tvCategoryName) ?: continue
                if (tvName.text == category) {
                    tvName.setBackgroundResource(R.drawable.bg_emoji_tab_selected)
                    tvName.setTextColor(android.graphics.Color.parseColor("#0B61CA"))
                } else {
                    tvName.setBackgroundResource(R.drawable.bg_emoji_tab_unselected)
                    tvName.setTextColor(android.graphics.Color.parseColor("#475467"))
                }
            }

            val list = if (category == "Recents") {
                CustomEmojiData.getFavorites(context)
            } else {
                CustomEmojiData.categories[category] ?: emptyList()
            }
            adapter.updateList(list)
            rvEmojis.scrollToPosition(0)
        }

        // Dynamically build category tabs
        val categoriesList = listOf("Recents") + CustomEmojiData.categories.keys.toList()
        categoriesList.forEach { category ->
            val tabView = LayoutInflater.from(context).inflate(
                R.layout.item_emoji_category_tab,
                categoryTabsContainer,
                false
            ) as ViewGroup
            val tvName = tabView.findViewById<TextView>(R.id.tvCategoryName)
            tvName.text = category
            tabView.setOnClickListener {
                etEmojiSearch.setText("")
                loadCategory(category)
            }
            categoryTabsContainer.addView(tabView)
        }

        // Search TextWatcher for keyword-based filtering
        etEmojiSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty().trim()
                if (query.isNotEmpty()) {
                    btnSearchClear.visibility = View.VISIBLE
                    // Clear tab highlights during search
                    for (i in 0 until categoryTabsContainer.childCount) {
                        val tabView = categoryTabsContainer.getChildAt(i) as? ViewGroup ?: continue
                        val tvName = tabView.findViewById<TextView>(R.id.tvCategoryName) ?: continue
                        tvName.setBackgroundResource(R.drawable.bg_emoji_tab_unselected)
                        tvName.setTextColor(android.graphics.Color.parseColor("#475467"))
                    }
                    val results = CustomEmojiData.searchEmojis(query)
                    adapter.updateList(results)
                } else {
                    btnSearchClear.visibility = View.GONE
                    loadCategory(activeCategory)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnSearchClear.setOnClickListener {
            etEmojiSearch.setText("")
        }

        // Load default category (Recents) on start
        loadCategory("Recents")

        isEmojiPickerInitialized = true
    }

    private fun toggleEmojiPanel() {
        if (isEmojiPanelVisible) hideEmojiPanel() else showEmojiPanel()
    }

    private fun showEmojiPanel() {
        setupEmojiPicker()
        isEmojiPanelVisible = true
        binding.emojiPanel.visibility = View.VISIBLE
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.etMessage.windowToken, 0)
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun hideEmojiPanel() {
        isEmojiPanelVisible = false
        binding.emojiPanel.visibility = View.GONE
        ViewCompat.requestApplyInsets(binding.root)
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
                context?.let { ctx -> 
                    DeletedMessagesTracker.markAsDeleted(ctx, messageId)
                    val msg = messages.find { it.id == messageId }
                    if (msg != null) {
                        val timestamp = getNormalizedTimestamp(msg.creationTime).toLong()
                        val chatId = conversationId ?: channelId
                        DeletedMessagesTracker.markPreviewDeleted(ctx, chatId, timestamp)
                    }
                }
                purgeMessageFromCache(listOf(messageId))
                loadInitialMessages(scrollToBottom = true)
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
            renderMessages(scrollToBottom = true)
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
        if (_binding == null) return
        val key = cacheKey() ?: return
        val ctx = context?.applicationContext ?: return
        val messagesCopy = messages.toList()
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            ChatMessageCache.save(ctx, key, messagesCopy)
        }
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

    private fun firstSelectedMessage(): MessageData? {
        val firstId = chatAdapter.selectedIdsSnapshot().firstOrNull() ?: return null
        return messages.firstOrNull { it.id == firstId }
    }

    private fun setupSelectionToolbar() {
        binding.btnSelectionCancel.setOnClickListener { exitSelectionMode() }

        // Reply / Star / Info act on the FIRST selected message. Reply + Info
        // route through the same handlers the old per-message bottom sheet used,
        // so existing flows light up from the toolbar without duplication.
        binding.btnSelectionReply.setOnClickListener {
            val first = firstSelectedMessage()
            if (first?.id == null) { exitSelectionMode(); return@setOnClickListener }
            val id = first.id
            exitSelectionMode()
            onReply(id)
        }
        binding.btnSelectionInfo.setOnClickListener {
            val first = firstSelectedMessage()
            if (first?.id == null) { exitSelectionMode(); return@setOnClickListener }
            val id = first.id
            exitSelectionMode()
            onInfo(id)
        }
        binding.btnSelectionStar.setOnClickListener {
            // Starred-messages backend isn't wired yet. The affordance is in
            // place so the toolbar matches the design; hook up to a Convex
            // flag once the backend endpoint exists.
            toast("Starred messages — coming soon")
        }

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
                        val deletedMessages = mutableListOf<MessageData>()
                        ids.forEach { id ->
                            runCatching {
                                api.deleteMessage(
                                    session.bearerToken,
                                    com.manjugroups.m_connect.network.DeleteMessageRequest(id)
                                )
                            }.onSuccess {
                                deleted += id
                                context?.let { ctx -> DeletedMessagesTracker.markAsDeleted(ctx, id) }
                                val msg = messages.find { it.id == id }
                                if (msg != null) {
                                    deletedMessages.add(msg)
                                }
                            }.onFailure { failures++ }
                        }
                        context?.let { ctx ->
                            val maxTimestamp = deletedMessages.maxOfOrNull { getNormalizedTimestamp(it.creationTime).toLong() } ?: 0L
                            if (maxTimestamp > 0) {
                                val chatId = conversationId ?: channelId
                                DeletedMessagesTracker.markPreviewDeleted(ctx, chatId, maxTimestamp)
                            }
                        }
                        purgeMessageFromCache(deleted)
                        exitSelectionMode()
                        if (failures > 0) toast("Deleted with $failures error(s)")
                        loadInitialMessages(scrollToBottom = true)
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
                // Forward EVERY selected message regardless of type. Old code
                // only forwarded `body`-bearing messages, which silently dropped
                // voice notes, PDFs, images and other attachment-only messages.
                if (toForward.isEmpty()) {
                    toast("Nothing to forward")
                    return
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    var failures = 0
                    var skipped = 0
                    val newMessageIds = mutableListOf<String>()
                    toForward.forEach { msg ->
                        val body = msg.body?.trim().orEmpty()
                        val attachments = msg.attachments
                            ?.mapNotNull { att ->
                                val sid = att.storageId?.takeIf { it.isNotBlank() }
                                    ?: return@mapNotNull null
                                com.manjugroups.m_connect.network.MessageAttachmentUpload(
                                    storageId = sid,
                                    fileName = att.fileName.orEmpty().ifBlank { "attachment" },
                                    fileType = att.fileType.orEmpty().ifBlank { "application/octet-stream" },
                                    fileSize = att.fileSize ?: 0L,
                                )
                            }
                            ?.takeIf { it.isNotEmpty() }
                        // Skip messages that have neither text nor attachments
                        // (deleted placeholders, system notes etc.).
                        if (body.isEmpty() && attachments == null) {
                            skipped++
                            return@forEach
                        }
                        runCatching {
                            api.sendMessage(
                                session.bearerToken,
                                com.manjugroups.m_connect.network.SendMessageRequest(
                                    channelId = targetChannelId,
                                    conversationId = targetConversationId,
                                    body = body,
                                    attachments = attachments,
                                )
                            )
                        }.onSuccess { resp ->
                            resp.messageId?.takeIf { it.isNotBlank() }
                                ?.let { newMessageIds.add(it) }
                        }.onFailure { failures++ }
                    }
                    // Stamp every successful forward into the local
                    // ForwardedMessageStore so the resulting bubble renders
                    // with a "Forwarded" tag when the server echoes it back.
                    if (newMessageIds.isNotEmpty()) {
                        ForwardedMessageStore.markForwarded(requireContext(), newMessageIds)
                    }
                    val sent = toForward.size - skipped - failures
                    when {
                        failures == 0 && skipped == 0 -> toast("Forwarded to $targetName")
                        failures == 0 -> toast("Forwarded $sent to $targetName ($skipped skipped)")
                        else -> toast("Forwarded $sent to $targetName ($failures error(s))")
                    }
                    exitSelectionMode()
                }
            }
        })
        picker.show(childFragmentManager, "ChatForwardPicker")
    }

    private fun openAttachmentUrl(url: String, mime: String = "") {
        if (url.isBlank()) { toast("Unable to open attachment"); return }
        val isRemote = url.startsWith("http://", true) || url.startsWith("https://", true)
        if (!isRemote) {
            // Local content/file URI — launch the chooser directly.
            launchAttachmentChooser(Uri.parse(url), mime)
            return
        }
        // Remote URL — Android's default handler for https is the browser,
        // which renders the file inline instead of asking which app to open
        // it with. Download to cache and offer the system "Open with" sheet
        // via FileProvider so users get their installed PDF / Office viewers.
        val ctx = context?.applicationContext ?: return
        toast("Opening…")
        viewLifecycleOwner.lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.cacheDir, "chat_documents").apply { mkdirs() }
                    val name = pickCachedAttachmentName(url, mime)
                    val out = File(dir, name)
                    if (!out.exists() || out.length() == 0L) {
                        java.net.URL(url).openStream().use { input ->
                            out.outputStream().use { input.copyTo(it) }
                        }
                    }
                    out
                }.getOrNull()
            }
            if (_binding == null) return@launch
            if (cached == null) { toast("Unable to open attachment"); return@launch }
            val uri = runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    cached,
                )
            }.getOrNull()
            if (uri == null) { toast("Unable to open attachment"); return@launch }
            launchAttachmentChooser(uri, mime)
        }
    }

    private fun launchAttachmentChooser(uri: Uri, mime: String) {
        val resolvedMime = mime.takeIf { it.isNotBlank() }
            ?: runCatching {
                val ext = uri.lastPathSegment?.substringAfterLast('.', "")
                if (!ext.isNullOrBlank()) {
                    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase(Locale.US))
                } else null
            }.getOrNull()
            ?: "*/*"
        runCatching {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(view, "Open with"))
        }.onFailure { toast("Unable to open attachment") }
    }

    private fun pickCachedAttachmentName(url: String, mime: String): String {
        val raw = runCatching { Uri.parse(url).lastPathSegment.orEmpty() }.getOrDefault("")
        val sanitised = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.')
        if (sanitised.isNotBlank() && sanitised.contains('.')) return sanitised
        val ext = (sanitised.substringAfterLast('.', "").ifBlank {
            android.webkit.MimeTypeMap.getSingleton()
                .getExtensionFromMimeType(mime.substringBefore(';').trim()) ?: ""
        }).lowercase(Locale.US)
        val base = sanitised.substringBeforeLast('.', sanitised)
            .ifBlank { "attachment-${System.currentTimeMillis()}" }
        return if (ext.isNotBlank()) "$base.$ext" else base
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
        // Take a swing at the offline outbox every time the user opens
        // / returns to this chat — most of the time it's empty; when
        // it isn't, we send what's pending in queued order.
        flushPendingMessages()
        registerChatNetworkCallback()
    }

    override fun onPause() {
        pollJob?.cancel()
        pollJob = null
        typingDebounceJob?.cancel()
        typingDebounceJob = null
        unregisterChatNetworkCallback()
        super.onPause()
    }

    private var chatNetworkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /** Listen for connectivity gain → immediately flush the chat outbox
     *  so the user doesn't have to wait for the next manual screen open. */
    private fun registerChatNetworkCallback() {
        if (chatNetworkCallback != null) return
        val cm = requireContext().getSystemService(android.net.ConnectivityManager::class.java)
            ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                if (_binding == null) return
                requireActivity().runOnUiThread { flushPendingMessages() }
            }
        }
        try {
            val req = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, cb)
            chatNetworkCallback = cb
        } catch (_: Exception) { /* permission / API edge case — ignore */ }
    }

    private fun unregisterChatNetworkCallback() {
        val cb = chatNetworkCallback ?: return
        try {
            requireContext().getSystemService(android.net.ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        chatNetworkCallback = null
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
        wireAttachTiles()
        checkStoragePermissionsAndLoadMedia()

        val panel = binding.attachPanel
        panel.animate().cancel()
        panel.visibility = View.VISIBLE
        if (panel.height > 0) {
            panel.translationY = panel.height.toFloat()
        } else {
            panel.translationY = (280f * resources.displayMetrics.density)
        }
        panel.alpha = 0f
        panel.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        binding.ivAttachIcon.setImageResource(R.drawable.ic_sheet_close)
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun hideAttachPanel() {
        if (_binding == null) return
        val panel = binding.attachPanel
        if (panel.visibility != View.VISIBLE) {
            binding.ivAttachIcon.setImageResource(R.drawable.ic_chat_plus)
            return
        }
        panel.animate().cancel()
        panel.animate()
            .translationY(panel.height.toFloat())
            .alpha(0f)
            .setDuration(180L)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                if (_binding != null) {
                    panel.visibility = View.GONE
                    panel.translationY = 0f
                    panel.alpha = 1f
                    ViewCompat.requestApplyInsets(binding.root)
                }
            }
            .start()
        binding.ivAttachIcon.setImageResource(R.drawable.ic_chat_plus)
        ViewCompat.requestApplyInsets(binding.root)
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
        tile(R.id.tileAttachImage) {
            isDocumentPickerMode = false
            pickAttachmentsLauncher.launch(arrayOf("image/*"))
        }
        tile(R.id.tileAttachVideo) {
            isDocumentPickerMode = false
            pickAttachmentsLauncher.launch(arrayOf("video/*"))
        }
        tile(R.id.tileAttachAudio) {
            isDocumentPickerMode = false
            pickAttachmentsLauncher.launch(arrayOf("audio/*"))
        }
        tile(R.id.tileAttachLocation) { launchLocationShare() }
        tile(R.id.tileAttachDocument) {
            isDocumentPickerMode = true
            pickAttachmentsLauncher.launch(arrayOf("*/*"))
        }
        tile(R.id.tileAttachContact) { showCustomContactPickerSheet() }
        tile(R.id.tileAttachCamera) { launchCamera() }
        attachTilesWired = true
    }

    private fun launchCamera() {
        ensureCameraPermissionThen {
            val cameraSheet = CustomCameraBottomSheet().apply {
                setListener(object : CustomCameraBottomSheet.CameraResultListener {
                    override fun onMediaCaptured(uri: android.net.Uri, isVideo: Boolean) {
                        val resolver = requireContext().contentResolver
                        if (isVideo) {
                            val mime = resolver.getType(uri) ?: "video/mp4"
                            val size = runCatching {
                                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                            }.getOrNull() ?: 0L
                            val name = uri.lastPathSegment ?: "Video-${System.currentTimeMillis()}.mp4"
                            val pending = PendingAttachment(uri = uri, fileName = name, fileType = mime, fileSize = size)
                            showImageSendPreview(listOf(pending))
                        } else {
                            val mime = resolver.getType(uri) ?: "image/jpeg"
                            val size = runCatching {
                                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                            }.getOrNull() ?: 0L
                            val name = uri.lastPathSegment ?: "Photo-${System.currentTimeMillis()}.jpg"
                            val pending = PendingAttachment(uri = uri, fileName = name, fileType = mime, fileSize = size)
                            showImageSendPreview(listOf(pending))
                        }
                    }

                    override fun onGalleryClicked() {
                        isDocumentPickerMode = false
                        pickAttachmentsLauncher.launch(arrayOf("image/*", "video/*"))
                    }
                })
            }
            cameraSheet.show(parentFragmentManager, "custom_camera")
        }
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera() else toast("Camera permission required")
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

    private val locationPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
            if (fineGranted || coarseGranted) {
                showLocationShareSheet()
            } else {
                toast("Location permission is required to share your site telemetry")
            }
        }

    private fun launchLocationShare() {
        val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = android.Manifest.permission.ACCESS_COARSE_LOCATION
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), fine) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), coarse) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showLocationShareSheet()
        } else {
            locationPermissionLauncher.launch(arrayOf(fine, coarse))
        }
    }

    private fun showLocationShareSheet() {
        val sheet = LocationShareBottomSheet().apply {
            setListener(object : LocationShareBottomSheet.LocationShareListener {
                override fun onLocationShared(locationString: String) {
                    sendLocationMessage(locationString)
                }
            })
        }
        sheet.show(parentFragmentManager, "location_share_sheet")
    }

    private fun sendLocationMessage(locationString: String) {
        binding.etMessage.setText(locationString)
        sendMessage()
    }

    private fun showCustomContactPickerSheet() {
        if (!isAdded) return
        val context = requireContext()
        val content = layoutInflater.inflate(R.layout.bottom_sheet_multi_people_picker, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)

        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            val params = sheet.layoutParams
            params.height = (resources.displayMetrics.heightPixels * 0.9f).toInt()
            sheet.layoutParams = params
            sheet.setBackgroundResource(R.drawable.bg_bottom_sheet)
            androidx.core.view.ViewCompat.setElevation(sheet, 0f)
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isDraggable = true
            }
        }

        dialog.setOnShowListener { dialogInterface ->
            val d = dialogInterface as BottomSheetDialog
            d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { s ->
                s.setBackgroundResource(R.drawable.bg_bottom_sheet)
                androidx.core.view.ViewCompat.setElevation(s, 0f)
            }
        }

        val titleView = content.findViewById<TextView>(R.id.tvSheetTitle)
        val closeBtn = content.findViewById<View>(R.id.btnSheetClose)
        val searchField = content.findViewById<EditText>(R.id.etSearchPeople)
        val peopleCard = content.findViewById<LinearLayout>(R.id.peopleCard)
        val emptyView = content.findViewById<TextView>(R.id.tvEmptyPeople)
        val doneBtn = content.findViewById<FrameLayout>(R.id.btnDone)
        val doneLabel = content.findViewById<TextView>(R.id.tvDoneLabel)
        val countView = content.findViewById<TextView>(R.id.tvSelectedCount)

        titleView.text = "Share Contacts"
        doneLabel.text = "Share"
        countView.text = "0 selected"

        val selectedContacts = mutableSetOf<StaffData>()
        var allPeople = emptyList<StaffData>()
        var onlineStaffIds = emptySet<String>()

        closeBtn.setOnClickListener { dialog.dismiss() }

        fun updateDoneButton() {
            val count = selectedContacts.size
            countView.text = "$count selected"
            val enabled = count > 0
            doneBtn.isClickable = enabled
            doneBtn.isFocusable = enabled
            doneBtn.setBackgroundResource(
                if (enabled) R.drawable.bg_sheet_start_button
                else R.drawable.bg_sheet_start_button_disabled
            )
        }

        fun bindPeople(staffList: List<StaffData>) {
            peopleCard.removeAllViews()
            if (staffList.isEmpty()) {
                emptyView.text = "No matching people"
                emptyView.visibility = View.VISIBLE
                peopleCard.visibility = View.GONE
                return
            }
            emptyView.visibility = View.GONE
            peopleCard.visibility = View.VISIBLE

            peopleCard.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            val dividerDrawable = android.graphics.drawable.GradientDrawable().apply {
                setSize(0, (resources.displayMetrics.density * 0.5f).toInt().coerceAtLeast(1))
                setColor(ContextCompat.getColor(context, R.color.chat_separator))
            }
            peopleCard.dividerDrawable = dividerDrawable

            staffList.forEachIndexed { index, member ->
                val row = layoutInflater.inflate(R.layout.item_chat_sheet_person, peopleCard, false)
                row.tag = member

                val tvName = row.findViewById<TextView>(R.id.tvName)
                val tvSubtitle = row.findViewById<TextView>(R.id.tvSubtitle)
                val radio = row.findViewById<View>(R.id.radioButton)
                val avatarCheck = row.findViewById<View>(R.id.avatarCheck)
                val onlineDot = row.findViewById<View>(R.id.onlineDot)

                tvName.text = member.name ?: "User"
                tvSubtitle.text = listOfNotNull(member.designation, member.department)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" • ")
                    ?: member.phone ?: ""

                val initials = initialsFor(member.name ?: "User")
                bindAvatar(
                    row.findViewById(R.id.avatarContainer),
                    row.findViewById(R.id.tvAvatar),
                    row.findViewById(R.id.ivAvatarPhoto),
                    member.photo,
                    initials,
                    index + (member.name?.length ?: 0)
                )

                val isSel = selectedContacts.any { it.id == member.id }
                radio.setBackgroundResource(
                    if (isSel) R.drawable.bg_sheet_radio_on
                    else R.drawable.bg_sheet_radio_off
                )
                avatarCheck.visibility = if (isSel) View.VISIBLE else View.GONE
                onlineDot.visibility = if (onlineStaffIds.contains(member.id)) View.VISIBLE else View.GONE

                row.setOnClickListener {
                    val alreadySel = selectedContacts.any { it.id == member.id }
                    if (alreadySel) {
                        selectedContacts.removeAll { it.id == member.id }
                    } else {
                        selectedContacts.add(member)
                    }

                    val newSel = selectedContacts.any { it.id == member.id }
                    radio.setBackgroundResource(
                        if (newSel) R.drawable.bg_sheet_radio_on
                        else R.drawable.bg_sheet_radio_off
                    )
                    avatarCheck.visibility = if (newSel) View.VISIBLE else View.GONE

                    updateDoneButton()
                }

                peopleCard.addView(row)
            }
        }

        fun filterPeople(query: String) {
            var visibleCount = 0
            val trimmedQuery = query.trim()
            for (i in 0 until peopleCard.childCount) {
                val child = peopleCard.getChildAt(i)
                val member = child.tag as? StaffData
                if (member == null) continue
                
                val matches = if (trimmedQuery.isEmpty()) {
                    true
                } else {
                    (member.name ?: "").contains(trimmedQuery, ignoreCase = true) ||
                    (member.designation ?: "").contains(trimmedQuery, ignoreCase = true) ||
                    (member.department ?: "").contains(trimmedQuery, ignoreCase = true) ||
                    (member.phone ?: "").contains(trimmedQuery)
                }
                
                if (matches) {
                    child.visibility = View.VISIBLE
                    visibleCount++
                } else {
                    child.visibility = View.GONE
                }
            }
            
            if (visibleCount == 0) {
                emptyView.text = "No matching people"
                emptyView.visibility = View.VISIBLE
                peopleCard.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                peopleCard.visibility = View.VISIBLE
            }
        }

        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterPeople(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        doneBtn.setOnClickListener {
            if (selectedContacts.isEmpty()) return@setOnClickListener
            selectedContacts.forEach { contact ->
                val contactName = contact.name ?: "Contact"
                val contactPhone = contact.phone ?: ""
                val contactLabel = "Mobile"
                sendContactMessageDirect(contactName, contactPhone, contactLabel)
            }
            dialog.dismiss()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.getOnlineStaff(session.bearerToken)
            }.onSuccess { resp ->
                onlineStaffIds = resp.online?.mapNotNull { it?.staffId }?.toSet().orEmpty()
            }

            runCatching {
                api.getStaff(session.bearerToken, status = "active")
            }.onSuccess { response ->
                val currentStaffId = session.staffId
                allPeople = response.staff.filter { it.id != null && it.id != currentStaffId }
                bindPeople(allPeople)
            }.onFailure {
                toast("Unable to load contacts list")
            }
        }

        dialog.show()
    }

    private fun sendContactMessageDirect(name: String, number: String, label: String) {
        val text = buildString {
            append("📇 Contact\n")
            append(name).append('\n')
            append(label).append(": ").append(number)
        }
        sendDirectMessage(text)
    }

    private fun sendDirectMessage(text: String) {
        val originalText = binding.etMessage.text?.toString() ?: ""
        binding.etMessage.setText(text)
        sendMessage()
        if (originalText.isNotEmpty()) {
            binding.etMessage.setText(originalText)
        }
    }

    private fun initialsFor(name: String): String =
        name.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { name.take(1).uppercase(Locale.getDefault()) }

    private fun avatarPalette(seed: Int): Pair<Int, Int> {
        return when (seed.mod(4)) {
            0 -> resolveColor(R.attr.colorAccentLight) to resolveColor(R.attr.colorAccentPrimary)
            1 -> resolveColor(R.attr.colorInfoLight) to resolveColor(R.attr.colorInfo)
            2 -> resolveColor(R.attr.colorSuccessLight) to resolveColor(R.attr.colorSuccess)
            else -> resolveColor(R.attr.colorWarningLight) to resolveColor(R.attr.colorWarning)
        }
    }

    private fun bindAvatar(container: View, label: TextView, text: String, seed: Int) {
        val palette = avatarPalette(seed)
        val bg = container.background
        if (bg != null) {
            bg.mutate().setTint(palette.first)
        } else {
            container.setBackgroundColor(palette.first)
        }
        label.setTextColor(palette.second)
        label.text = text
    }

    private fun bindAvatar(
        container: View,
        label: TextView,
        ivPhoto: ImageView,
        photoUrl: String?,
        text: String,
        seed: Int
    ) {
        val resolved = com.manjugroups.m_connect.ui.common.ProfilePhotos.resolve(photoUrl)
        if (!resolved.isNullOrBlank()) {
            ivPhoto.visibility = View.VISIBLE
            label.visibility = View.GONE
            ivPhoto.load(resolved) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        } else {
            ivPhoto.visibility = View.GONE
            label.visibility = View.VISIBLE
            bindAvatar(container, label, text, seed)
        }
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
            launch { refreshPresence() }
            var refreshCounter = 0
            while (true) {
                pollForMessages()
                pollTyping()
                presencePollCounter++
                refreshCounter++
                if (presencePollCounter % 6 == 0) {
                    launch { refreshPresence() }
                    runCatching {
                        api.presenceHeartbeat(
                            session.bearerToken,
                            com.manjugroups.m_connect.network.PresenceHeartbeatRequest("online")
                        )
                    }
                }
                // The `pollMessages` endpoint only returns rows created after
                // `latestMessageTime`, so updates to existing messages
                // (deletes from the web, edits, reactions) never reach the
                // client. Periodically re-fetch the most recent window and
                // overwrite local copies whose server state has changed.
                if (refreshCounter % 4 == 0) {
                    syncRecentMessagesForUpdates()
                }
                delay(2_500)
            }
        }
    }

    private suspend fun syncRecentMessagesForUpdates() {
        val channel = channelId
        val conversation = conversationId
        if (channel == null && conversation == null) return

        runCatching {
            if (channel != null) {
                api.getChannelMessages(session.bearerToken, channel, numItems = 50)
            } else {
                api.getConversationMessages(session.bearerToken, conversation!!, numItems = 50)
            }
        }.onSuccess { response ->
            if (!response.success) return@onSuccess
            val incoming = response.page ?: response.messages ?: return@onSuccess
            if (incoming.isEmpty()) return@onSuccess

            var changed = false
            incoming.forEach { server ->
                val sid = server.id ?: return@forEach
                val idx = messages.indexOfFirst { it.id == sid }
                if (idx >= 0 && messages[idx] != server) {
                    messages[idx] = server
                    changed = true
                }
            }
            if (changed && _binding != null) {
                renderMessages(scrollToBottom = false)
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

                    // Use the photo embedded in the conversation
                    // response first — server already resolved the
                    // storage id to a public URL, so the avatar
                    // renders without waiting for a second round-trip.
                    photoUrl = participant?.photo?.takeIf { it.isNotBlank() }

                    val staffId = otherStaffId
                    if (staffId != null) {
                        runCatching {
                            val staffResp = api.getStaffDetail(session.bearerToken, staffId)
                            val staffPhoto = staffResp.staff?.photo
                            if (!staffPhoto.isNullOrBlank()) {
                                photoUrl = com.manjugroups.m_connect.ui.common.ProfilePhotos.resolve(staffPhoto)
                            }
                            otherStaffPhone = staffResp.staff?.phone
                        }
                    }
                }
            }
            chatPhotoUrl = photoUrl
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
                    transformations(CircleCropTransformation())
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
        val photo = com.manjugroups.m_connect.ui.common.ProfilePhotos.resolve(snap.photoUrl)
        chatPhotoUrl = photo
        if (!photo.isNullOrBlank()) {
            binding.ivHeaderAvatar.load(photo) {
                crossfade(false)
                transformations(CircleCropTransformation())
            }
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

        var updated = chatSubtitle
        runCatching {
            val presence = api.getPresence(session.bearerToken, staffId = staffId).presence
            if (presence != null) {
                val isOnline = presence.status == "online"
                val lastSeenAt = presence.lastSeenAt
                chatAdapter.updateOtherParticipantPresence(isOnline, lastSeenAt)

                updated = if (isOnline) {
                    "Online"
                } else {
                    formatLastSeen(lastSeenAt)
                }
            }
        }.onFailure {
            updated = formatLastSeen(null)
        }

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
            otherStaffId = otherStaffId,
            photoUrl = chatPhotoUrl
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

            // Override MIME type for image/video/audio files picked via Document Picker
            // so they are sent and rendered as document cards instead of inline media
            val mime = meta.fileType.lowercase(Locale.US)
            val finalMeta = if (isDocumentPickerMode && (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/"))) {
                meta.copy(fileType = "application/octet-stream")
            } else {
                meta
            }

            accepted += finalMeta
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
        syncLocalMediaSelection()
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
                .sortedBy { getNormalizedTimestamp(it.creationTime) }
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
            if (!cacheMatchesServer || messages.isEmpty()) {
                renderMessages(scrollToBottom = scrollToBottom || !cacheMatchesServer)
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
                .sortedBy { getNormalizedTimestamp(it.creationTime) }
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
                binding.tvRecordingTimer.text = timeStr
                recordingHandler.postDelayed(this, 1000)
            }
        }
    }

    private var micPulseAnimator: android.animation.AnimatorSet? = null

    private fun startMicPulseAnimation() {
        if (_binding == null) return
        micPulseAnimator?.cancel()

        val scaleX = android.animation.ObjectAnimator.ofFloat(binding.animatingMicContainer, "scaleX", 1.0f, 1.15f, 1.0f)
        val scaleY = android.animation.ObjectAnimator.ofFloat(binding.animatingMicContainer, "scaleY", 1.0f, 1.15f, 1.0f)

        scaleX.repeatCount = android.animation.ValueAnimator.INFINITE
        scaleY.repeatCount = android.animation.ValueAnimator.INFINITE

        micPulseAnimator = android.animation.AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 1000
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopMicPulseAnimation() {
        micPulseAnimator?.cancel()
        micPulseAnimator = null
        if (_binding != null) {
            binding.animatingMicContainer.animate().cancel()
            binding.animatingMicContainer.scaleX = 1f
            binding.animatingMicContainer.scaleY = 1f
        }
    }

    private fun updateRecordingUI() {
        if (_binding == null) return
        if (isRecording) {
            binding.etMessage.isEnabled = false

            // Show the custom recording overlay and animating mic
            binding.recordingOverlay.visibility = View.VISIBLE
            binding.recordingOverlay.alpha = 1f
            binding.animatingMicContainer.visibility = View.VISIBLE
            binding.animatingMicContainer.translationX = 0f
            binding.animatingMicContainer.translationY = 0f
            binding.animatingMicContainer.scaleX = 1f
            binding.animatingMicContainer.scaleY = 1f
            binding.animatingMicContainer.alpha = 1f
            binding.animatingMicContainer.setBackgroundResource(R.drawable.bg_chat_rec_mic_circle)

            binding.slideCancelContainer.alpha = 1f
            binding.slideCancelContainer.translationX = 0f

            // Initialize recording status container and trash container alphas/visibilities
            binding.recordingStatusContainer.visibility = View.VISIBLE
            binding.recordingStatusContainer.alpha = 1f
            binding.trashCanContainer.visibility = View.VISIBLE
            binding.trashCanContainer.alpha = 0f

            // Set up red blinking dot animation
            binding.ivRecordingDot.visibility = View.VISIBLE
            binding.ivRecordingDot.alpha = 1f
            binding.ivRecordingDot.animate().cancel()

            val blinkListener = object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (isRecording && _binding != null) {
                        val nextAlpha = if (binding.ivRecordingDot.alpha < 0.5f) 1f else 0.2f
                        binding.ivRecordingDot.animate()
                            .alpha(nextAlpha)
                            .setDuration(500)
                            .setListener(this)
                            .start()
                    }
                }
            }
            binding.ivRecordingDot.animate()
                .alpha(0.2f)
                .setDuration(500)
                .setListener(blinkListener)
                .start()

            // Start/Reset the timer display in the overlay
            binding.tvRecordingTimer.text = "00:00"

            updateRecordingHintNormal()
            recordingHandler.post(recordingTimerTask)
            startMicPulseAnimation()
            broadcastRecordingTyping()
        } else {
            stopMicPulseAnimation()
            binding.animatingMicContainer.setBackgroundResource(R.drawable.bg_chat_send_circle)
            recordingHandler.removeCallbacks(recordingTimerTask)
            binding.ivRecordingDot.animate().cancel()
            binding.recordingStatusContainer.visibility = View.GONE
            binding.trashCanContainer.visibility = View.GONE
            binding.etMessage.isEnabled = true
            binding.etMessage.hint = "Message ..."
            applySubtitleState()
            updateSendIcon()
        }
    }

    private fun updateRecordingHintNormal() {
        if (_binding == null) return
        binding.tvSlideCancel.text = "◀  Slide to cancel"
    }

    private fun updateRecordingHintCancel() {
        if (_binding == null) return
        binding.tvSlideCancel.text = "Release to cancel"
    }

    private fun animateDropToTrash() {
        if (_binding == null) return

        stopMicPulseAnimation()

        // Calculate translation needed to reach the trash can
        val targetTx = (binding.trashCanContainer.left - binding.animatingMicContainer.left).toFloat()

        binding.animatingMicContainer.animate().cancel()
        binding.animatingMicContainer.animate()
            .translationX(targetTx)
            .scaleX(0.2f)
            .scaleY(0.2f)
            .alpha(0f)
            .setDuration(250)
            .withEndAction {
                // Revert trash can to normal size and rotation, then do a shake animation
                binding.ivTrash.animate()
                    .rotation(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .start()

                val shakeAnimator = ValueAnimator.ofFloat(0f, 15f, -15f, 10f, -10f, 5f, -5f, 0f).apply {
                    duration = 400
                    addUpdateListener { anim ->
                        if (_binding != null) {
                            val value = anim.animatedValue as Float
                            binding.ivTrash.translationX = dpToPx(value.toInt()).toFloat() / 2f
                        }
                    }
                }
                shakeAnimator.start()

                // Play deletion/trash error sound
                val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_SYSTEM, 90)
                toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 150)

                stopRecording(send = false)
                hideRecordingOverlay()
            }
            .start()
    }

    private fun hideRecordingOverlay() {
        if (_binding == null) return
        binding.recordingOverlay.animate().cancel()
        binding.recordingOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                if (_binding != null) {
                    binding.recordingOverlay.visibility = View.GONE
                    binding.recordingOverlay.alpha = 1f
                    binding.animatingMicContainer.visibility = View.GONE
                    binding.animatingMicContainer.translationX = 0f
                    binding.animatingMicContainer.scaleX = 1f
                    binding.animatingMicContainer.scaleY = 1f
                    binding.animatingMicContainer.alpha = 1f
                    binding.ivTrash.translationX = 0f
                    binding.ivTrash.scaleX = 1f
                    binding.ivTrash.scaleY = 1f
                    binding.ivTrash.rotation = 0f
                    binding.ivTrash.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#98A2B3"))
                    
                    // Reset trash container and recording status container alpha/visibility
                    binding.trashCanContainer.alpha = 0f
                    binding.recordingStatusContainer.alpha = 1f
                    binding.recordingStatusContainer.visibility = View.GONE
                }
            }
            .start()
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

        messages.sortBy { getNormalizedTimestamp(it.creationTime) }

        messages
            .forEach { message ->
                val createdMillis = getNormalizedTimestamp(message.creationTime).toLong()
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
                binding.rvMessages.post {
                    if (_binding != null) {
                        binding.rvMessages.scrollToPosition(chatItems.size - 1)
                    }
                }
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

    private fun getNormalizedTimestamp(timestamp: Double?): Double {
        if (timestamp == null) return 0.0
        return if (timestamp < 10000000000.0) timestamp * 1000.0 else timestamp
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
            val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_SYSTEM, 100)
            toneGenerator.startTone(android.media.ToneGenerator.TONE_CDMA_CONFIRM, 150)
        }
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

        val pendingSnapshot = pendingAttachments.toList()
        val previousText = text
        val parentId = replyingToMessage?.id

        // Cache local URI mapping to load attachments instantly without network buffering
        pendingSnapshot.forEach { attachment ->
            localAttachmentUriMap[attachment.fileName] = attachment.uri.toString()
        }

        // Clear composer state IMMEDIATELY (WhatsApp-style) — the user
        // can keep typing the next message without waiting on the
        // network. The optimistic bubble is rendered below; the real
        // server send runs in the background and replaces it on success.
        binding.etMessage.setText("")
        pendingAttachments.clear()
        cancelReply()
        renderPendingAttachments()
        updateSendIcon()

        // Build the optimistic message — a local-only MessageData with a
        // UUID id + senderId=self + creationTime=now. The adapter reads
        // localPendingId to flag this bubble as "sending…" until either
        // the API confirms (replace) or stays unsent (leave as-is for
        // the retry queue to handle later).
        val localId = "local-" + java.util.UUID.randomUUID().toString()
        val nowEpochMicro = System.currentTimeMillis().toDouble()
        val myStaffId = session.staffId
        val myName = session.userName
        val optimistic = com.manjugroups.m_connect.network.MessageData(
            id = localId,
            creationTime = nowEpochMicro,
            body = previousText,
            senderId = myStaffId,
            senderName = myName,
            channelId = channelId,
            conversationId = conversationId,
            isDeleted = false,
            isEdited = false,
            replyCount = 0,
            parentMessageId = parentId,
            attachments = pendingSnapshot.map {
                // Local preview attachments so the optimistic bubble
                // shows the picked file immediately. The real upload
                // happens below; on success the server-confirmed message
                // replaces these with proper storage IDs + URLs.
                com.manjugroups.m_connect.network.MessageAttachmentData(
                    id = "temp_attachment_${it.fileName}",
                    storageId = it.fileName,
                    fileName = it.fileName,
                    fileType = it.fileType,
                    fileSize = it.fileSize,
                    url = it.uri.toString()
                )
            }.ifEmpty { null },
            reactions = null,
            localPendingId = localId,
        )
        messages.add(optimistic)
        renderMessages(scrollToBottom = true)

        // Persist into the offline queue FIRST so a process-death
        // between here and the API call doesn't lose the message.
        // Attachment-bearing sends still take the legacy path (one-shot
        // upload + send) — for plain text we use the queue.
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            if (pendingSnapshot.isEmpty()) {
                ChatPendingQueue.enqueue(
                    context = ctx,
                    localId = localId,
                    conversationId = conversationId,
                    channelId = channelId,
                    body = previousText,
                    parentMessageId = parentId,
                )
            }
            val result = runCatching {
                val uploadedAttachments = uploadPendingAttachments(pendingSnapshot)
                api.sendMessage(
                    token = session.bearerToken,
                    body = SendMessageRequest(
                        channelId = channelId,
                        conversationId = conversationId,
                        body = previousText,
                        parentMessageId = parentId,
                        attachments = uploadedAttachments,
                    ),
                )
            }
            result.onSuccess { response ->
                if (!response.success) {
                    markOptimisticFailed(localId)
                    toast("Will retry when online")
                    return@onSuccess
                }
                // Replace the optimistic bubble with the server-confirmed
                // version + drop the queue row.
                if (pendingSnapshot.isEmpty()) {
                    ChatPendingQueue.confirmSent(ctx, localId)
                }
                replaceOptimisticWithServer(localId, response.messageId)
                markRead()
            }.onFailure {
                // Network down / 5xx / etc — leave the row in the queue.
                // onResume + the NetworkCallback below will drain it once
                // connectivity is back. The UI shows pending until then.
                markOptimisticFailed(localId)
            }
        }
    }

    /** Find the optimistic placeholder and replace it with the real
     *  server message (fetched by id) so the bubble's metadata
     *  (creationTime, reactions, etc.) matches the persisted row. */
    private suspend fun replaceOptimisticWithServer(localId: String, serverMessageId: String?) {
        val idx = messages.indexOfFirst { it.localPendingId == localId }
        if (idx < 0) {
            if (serverMessageId != null) appendSentMessage(serverMessageId)
            return
        }
        if (serverMessageId.isNullOrBlank()) {
            // Still flip pending off so the bubble doesn't show "sending"
            // forever — the next message poll will replace it with the
            // real row.
            messages[idx] = messages[idx].copy(localPendingId = null)
            renderMessages(scrollToBottom = false)
            return
        }
        runCatching { api.getMessage(session.bearerToken, serverMessageId) }
            .onSuccess { resp ->
                val sent = resp.message
                if (sent != null) {
                    messages[idx] = sent
                    latestMessageTime = messages.maxOfOrNull { it.creationTime ?: 0.0 }
                        ?: latestMessageTime
                    renderMessages(scrollToBottom = false)
                } else {
                    messages[idx] = messages[idx].copy(localPendingId = null)
                    renderMessages(scrollToBottom = false)
                }
            }
            .onFailure {
                messages[idx] = messages[idx].copy(localPendingId = null)
                renderMessages(scrollToBottom = false)
            }
    }

    /** Mark the optimistic bubble as "failed but pending" so the adapter
     *  can render a retry indicator. Leaves the queue row in place. */
    private fun markOptimisticFailed(localId: String) {
        val idx = messages.indexOfFirst { it.localPendingId == localId }
        if (idx >= 0) {
            messages[idx] = messages[idx].copy(hasFailed = true)
            renderMessages(scrollToBottom = false)
        }
    }

    /** Drain the offline outbox via ChatPendingQueue and update the UI
     *  for any optimistic bubbles that finally land. */
    private fun flushPendingMessages() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                ChatPendingQueue.flush(ctx) { localId, serverMessageId ->
                    replaceOptimisticWithServer(localId, serverMessageId)
                }
            } catch (_: Exception) { /* network blip — try next time */ }
        }
    }

    private suspend fun appendSentMessage(messageId: String?, tempId: String? = null) {
        if (messageId.isNullOrBlank()) {
            if (tempId != null) {
                messages.removeAll { it.id == tempId }
                renderMessages(scrollToBottom = false)
            }
            loadInitialMessages(scrollToBottom = true)
            return
        }
        runCatching {
            api.getMessage(session.bearerToken, messageId)
        }.onSuccess { resp ->
            val sent = resp.message
            if (sent == null) {
                if (tempId != null) {
                    messages.removeAll { it.id == tempId }
                    renderMessages(scrollToBottom = false)
                }
                loadInitialMessages(scrollToBottom = true)
                return@onSuccess
            }
            val existingIndexByTemp = if (tempId != null) messages.indexOfFirst { it.id == tempId } else -1
            val existingIndexById = messages.indexOfFirst { it.id == sent.id }
            
            if (existingIndexById >= 0) {
                messages[existingIndexById] = sent
                if (existingIndexByTemp >= 0) {
                    messages.removeAt(existingIndexByTemp)
                }
            } else {
                if (existingIndexByTemp >= 0) {
                    messages[existingIndexByTemp] = sent
                } else {
                    messages.add(sent)
                }
            }
            latestMessageTime = messages.maxOfOrNull { it.creationTime ?: 0.0 } ?: latestMessageTime
            renderMessages(scrollToBottom = true)
        }.onFailure {
            if (tempId != null) {
                messages.removeAll { it.id == tempId }
                renderMessages(scrollToBottom = false)
            }
            loadInitialMessages(scrollToBottom = true)
        }
    }

    private suspend fun uploadPendingAttachments(
        attachments: List<PendingAttachment>
    ): List<com.manjugroups.m_connect.network.MessageAttachmentUpload> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        attachments.map { attachment ->
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
        val currentText = binding.etMessage.text?.toString().orEmpty()
        val newText = if (currentText.isBlank()) text else "$text\n$currentText"
        binding.etMessage.setText(newText)
        binding.etMessage.setSelection(binding.etMessage.text?.length ?: 0)
        pendingAttachments.addAll(attachments)
        renderPendingAttachments()
        updateSendIcon()
    }

    private fun setComposerBusy(isBusy: Boolean) {
        // Keep it empty to avoid blocking the input field and send button,
        // allowing smooth WhatsApp-style concurrent background sending.
    }

    private fun markRead() {
        if (view == null) return
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
        if (view == null) return

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
            
            val isPanelVisible = _binding != null && (
                binding.attachPanel.visibility == View.VISIBLE || 
                binding.emojiPanel.visibility == View.VISIBLE
            )

            val bottomInset = if (isPanelVisible) {
                0
            } else {
                ime.bottom.coerceAtLeast(sys.bottom)
            }

            if (_binding != null) {
                val topPadding = 0
                val bottomPadding = bottomInset
                val sidePadding = 0

                binding.bottomContainer.setPadding(
                    sidePadding,
                    topPadding,
                    sidePadding,
                    bottomPadding
                )

                val sysBottom = sys.bottom
                val panelPaddingBottom = if (ime.bottom > sys.bottom) {
                    ime.bottom
                } else {
                    sys.bottom
                }
                binding.attachPanel.setPadding(0, 0, 0, panelPaddingBottom)
                binding.emojiPanel.setPadding(0, 0, 0, panelPaddingBottom)

                val basePanelHeight = dpToPx(280)
                val totalPanelHeight = basePanelHeight + panelPaddingBottom

                binding.emojiPanel.layoutParams = binding.emojiPanel.layoutParams.apply {
                    height = totalPanelHeight
                }
                binding.attachPanel.layoutParams = binding.attachPanel.layoutParams.apply {
                    height = totalPanelHeight
                }
                binding.emojiPanel.requestLayout()
                binding.attachPanel.requestLayout()

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
            }
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                if (_binding != null) {
                    binding.rvMessages.post {
                        binding.rvMessages.scrollToPosition(chatAdapter.itemCount.coerceAtLeast(1) - 1)
                    }
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

    data class PendingAttachment(
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

    private fun dialPhone(phone: String) {
        val digits = phone.filter { it.isDigit() || it == '+' }
        if (digits.isBlank()) return
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$digits")
        }
        runCatching { startActivity(intent) }
    }

    private data class LocalMediaItem(
        val uri: android.net.Uri,
        val name: String,
        val size: Long,
        val mimeType: String,
        val isVideo: Boolean,
        val durationStr: String? = null,
        val isMock: Boolean = false,
        val mockUrl: String? = null
    )

    private inner class LocalMediaAdapter(
        val items: List<LocalMediaItem>,
        private val onSelectionChanged: (LocalMediaItem, Boolean) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<LocalMediaAdapter.ViewHolder>() {

        val selectedItems = mutableSetOf<LocalMediaItem>()

        inner class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val borderContainer: View = view.findViewById(R.id.borderContainer)
            val imageContainer: View = view.findViewById(R.id.imageContainer)
            val ivThumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
            val badgeSelected: View = view.findViewById(R.id.badgeSelected)
            val videoBadge: View = view.findViewById(R.id.videoBadge)
            val tvVideoDuration: TextView = view.findViewById(R.id.tvVideoDuration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_local_media, parent, false)
            val imageContainer = view.findViewById<View>(R.id.imageContainer)
            imageContainer.background = androidx.core.content.ContextCompat.getDrawable(parent.context, R.drawable.bg_chat_local_media_thumbnail_shape)
            imageContainer.clipToOutline = true
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val isSelected = selectedItems.contains(item)

            if (item.isMock) {
                holder.ivThumbnail.load(item.mockUrl)
            } else {
                holder.ivThumbnail.load(item.uri)
            }

            if (isSelected) {
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
                val currentlySelected = selectedItems.contains(item)
                if (currentlySelected) {
                    selectedItems.remove(item)
                } else {
                    selectedItems.add(item)
                }
                notifyItemChanged(position)
                onSelectionChanged(item, !currentlySelected)
            }
        }

        override fun getItemCount() = items.size

        fun clearSelection() {
            selectedItems.clear()
            notifyDataSetChanged()
        }
    }

    private fun checkStoragePermissionsAndLoadMedia() {
        val context = context ?: return
        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val hasAnyPermission = permissions.any {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (hasAnyPermission) {
            loadLocalMediaList()
        } else {
            storagePermissionLauncher.launch(permissions)
        }
    }

    private fun loadLocalMediaList() {
        val list = mutableListOf<LocalMediaItem>()
        list.addAll(queryLocalMedia())

        val mockItems = listOf(
            LocalMediaItem(
                uri = android.net.Uri.EMPTY,
                name = "Sample_1.jpg",
                size = 102400L,
                mimeType = "image/jpeg",
                isVideo = false,
                isMock = true,
                mockUrl = "https://picsum.photos/id/82/300/300"
            ),
            LocalMediaItem(
                uri = android.net.Uri.EMPTY,
                name = "Sample_2.jpg",
                size = 204800L,
                mimeType = "image/jpeg",
                isVideo = false,
                isMock = true,
                mockUrl = "https://picsum.photos/id/1016/300/300"
            ),
            LocalMediaItem(
                uri = android.net.Uri.EMPTY,
                name = "Sample_3.jpg",
                size = 307200L,
                mimeType = "image/jpeg",
                isVideo = false,
                isMock = true,
                mockUrl = "https://picsum.photos/id/1015/300/300"
            ),
            LocalMediaItem(
                uri = android.net.Uri.EMPTY,
                name = "Sample_Video.mp4",
                size = 1536000L,
                mimeType = "video/mp4",
                isVideo = true,
                durationStr = "0:54",
                isMock = true,
                mockUrl = "https://picsum.photos/id/1018/300/300"
            ),
            LocalMediaItem(
                uri = android.net.Uri.EMPTY,
                name = "Sample_4.jpg",
                size = 409600L,
                mimeType = "image/jpeg",
                isVideo = false,
                isMock = true,
                mockUrl = "https://picsum.photos/id/1043/300/300"
            ),
            LocalMediaItem(
                uri = android.net.Uri.EMPTY,
                name = "Sample_5.jpg",
                size = 512000L,
                mimeType = "image/jpeg",
                isVideo = false,
                isMock = true,
                mockUrl = "https://picsum.photos/id/1025/300/300"
            )
        )
        list.addAll(mockItems)

        val recyclerView = binding.attachPanel.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvLocalMedia)
        if (recyclerView != null) {
            recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, 5)
            recyclerView.isNestedScrollingEnabled = false
            val adapter = LocalMediaAdapter(list) { item, selected ->
                handleLocalMediaSelection(item, selected)
            }
            localMediaAdapter = adapter
            recyclerView.adapter = adapter
        }
    }

    private fun queryLocalMedia(): List<LocalMediaItem> {
        val list = mutableListOf<LocalMediaItem>()
        val context = context ?: return list

        val imagesUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val imagesProjection = arrayOf(
            android.provider.MediaStore.Images.Media._ID,
            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
            android.provider.MediaStore.Images.Media.SIZE,
            android.provider.MediaStore.Images.Media.MIME_TYPE
        )

        val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(imagesUri, imagesProjection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.MIME_TYPE)

                var count = 0
                while (cursor.moveToNext() && count < 20) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)
                    val size = cursor.getLong(sizeCol)
                    val mime = cursor.getString(mimeCol)
                    val contentUri = android.content.ContentUris.withAppendedId(imagesUri, id)
                    list.add(LocalMediaItem(uri = contentUri, name = name, size = size, mimeType = mime, isVideo = false))
                    count++
                }
            }
        }.onFailure { e ->
            android.util.Log.e("ChatMessages", "Error querying images", e)
        }

        val videosUri = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val videosProjection = arrayOf(
            android.provider.MediaStore.Video.Media._ID,
            android.provider.MediaStore.Video.Media.DISPLAY_NAME,
            android.provider.MediaStore.Video.Media.SIZE,
            android.provider.MediaStore.Video.Media.MIME_TYPE,
            android.provider.MediaStore.Video.Media.DURATION
        )

        runCatching {
            context.contentResolver.query(videosUri, videosProjection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.MIME_TYPE)
                val durationCol = cursor.getColumnIndex(android.provider.MediaStore.Video.Media.DURATION)

                var count = 0
                while (cursor.moveToNext() && count < 20) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol)
                    val size = cursor.getLong(sizeCol)
                    val mime = cursor.getString(mimeCol)
                    val durationMs = if (durationCol != -1) cursor.getLong(durationCol) else 0L
                    val durationStr = formatDuration(durationMs)
                    val contentUri = android.content.ContentUris.withAppendedId(videosUri, id)
                    list.add(LocalMediaItem(uri = contentUri, name = name, size = size, mimeType = mime, isVideo = true, durationStr = durationStr))
                    count++
                }
            }
        }.onFailure { e ->
            android.util.Log.e("ChatMessages", "Error querying videos", e)
        }

        return list.sortedByDescending { it.uri.hashCode() }
    }

    private fun formatDuration(ms: Long): String {
        val sec = (ms / 1000) % 60
        val min = (ms / (1000 * 60)) % 60
        val hr = (ms / (1000 * 60 * 60))
        return if (hr > 0) {
            String.format("%d:%02d:%02d", hr, min, sec)
        } else {
            String.format("%d:%02d", min, sec)
        }
    }

    private fun handleLocalMediaSelection(item: LocalMediaItem, selected: Boolean) {
        val uriStr = if (item.isMock) item.mockUrl.orEmpty() else item.uri.toString()
        if (selected) {
            if (pendingAttachments.none { it.uri.toString() == uriStr }) {
                val uri = if (item.isMock) android.net.Uri.parse(item.mockUrl) else item.uri
                val pending = PendingAttachment(
                    uri = uri,
                    fileName = item.name,
                    fileType = item.mimeType,
                    fileSize = item.size
                )
                pendingAttachments.add(pending)
                renderPendingAttachments()
                updateSendIcon()
            }
        } else {
            val toRemove = pendingAttachments.find { it.uri.toString() == uriStr }
            if (toRemove != null) {
                pendingAttachments.remove(toRemove)
                renderPendingAttachments()
                updateSendIcon()
            }
        }
    }

    private fun syncLocalMediaSelection() {
        val adapter = localMediaAdapter ?: return
        val currentUris = pendingAttachments.map { it.uri.toString() }.toSet()
        val adapterItemsToRemove = adapter.selectedItems.filter { item ->
            val uriStr = if (item.isMock) item.mockUrl.orEmpty() else item.uri.toString()
            !currentUris.contains(uriStr)
        }
        if (adapterItemsToRemove.isNotEmpty()) {
            adapter.selectedItems.removeAll(adapterItemsToRemove)
            adapter.notifyDataSetChanged()
        }
    }
}

