package com.manjugroups.m_connect.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import coil.load
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentChatMessagesBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ChannelIdRequest
import com.manjugroups.m_connect.network.ConversationIdRequest
import com.manjugroups.m_connect.network.MessageAttachmentData
import com.manjugroups.m_connect.network.MessageAttachmentUpload
import com.manjugroups.m_connect.network.MessageData
import com.manjugroups.m_connect.network.SendMessageRequest
import com.manjugroups.m_connect.network.TypingRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatMessagesFragment : Fragment() {

    private var _binding: FragmentChatMessagesBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()

    private var channelId: String? = null
    private var conversationId: String? = null
    private var chatTitle: String = ""
    private var chatSubtitle: String = "Last seen recently"
    private var myStaffId: String = ""
    private var otherStaffId: String? = null
    private var latestMessageTime: Double = 0.0
    private val messages = mutableListOf<MessageData>()
    private val pendingAttachments = mutableListOf<PendingAttachment>()

    private var pollJob: Job? = null
    private var typingDebounceJob: Job? = null
    private var isSendingMessage = false

    private val pickAttachmentsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            handlePickedAttachments(uris)
        }

    companion object {
        private const val MAX_ATTACHMENT_COUNT = 5
        private const val MAX_ATTACHMENT_SIZE_BYTES = 15L * 1024L * 1024L

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
        updateHeaderAvatar(chatTitle)
        renderPendingAttachments()
        updateSendIcon()

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.headerContainer.setOnClickListener { openContactInfo() }
        binding.btnInfo.setOnClickListener { openContactInfo() }
        binding.btnSend.setOnClickListener { handleSendOrMic() }
        binding.btnAttach.setOnClickListener { openAttachmentPicker() }
        binding.etMessage.addTextChangedListener(typingWatcher)

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
            loadInitialMessages(scrollToBottom = true)
            markRead()
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
        startPolling()
    }

    override fun onPause() {
        pollJob?.cancel()
        pollJob = null
        typingDebounceJob?.cancel()
        typingDebounceJob = null
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                pollForMessages()
                pollTyping()
                delay(2_500)
            }
        }
    }

    private suspend fun refreshChatMetadata() {
        runCatching {
            when {
                channelId != null -> {
                    val channel = api.getChannel(session.bearerToken, channelId!!).channel
                    if (channel?.name?.isNotBlank() == true) {
                        chatTitle = channel.name
                    }
                    val memberCount = channel?.memberCount ?: 0
                    val channelType = channel?.type?.replaceFirstChar { it.uppercase() } ?: "Channel"
                    chatSubtitle = if (memberCount > 0) {
                        "$memberCount members • $channelType"
                    } else {
                        channelType
                    }
                }

                conversationId != null -> {
                    val conversation =
                        api.getConversation(session.bearerToken, conversationId!!).conversation
                    if (conversation?.displayName?.isNotBlank() == true) {
                        chatTitle = conversation.displayName
                    }
                    otherStaffId = conversation?.participants
                        ?.firstOrNull { it.id != null && it.id != myStaffId }
                        ?.id
                    val seenStamp = conversation?.lastMessageAt
                    chatSubtitle = formatLastSeen(seenStamp)
                }
            }
        }

        if (_binding != null) {
            binding.tvChatTitle.text = chatTitle
            binding.tvChatSubtitle.text = chatSubtitle
            updateHeaderAvatar(chatTitle)
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

    private fun updateHeaderAvatar(title: String) {
        if (_binding == null) return
        val initials = title.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "C" }
        binding.tvChatAvatar.text = initials
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

    private fun openAttachmentPicker() {
        if (isSendingMessage) return
        pickAttachmentsLauncher.launch(arrayOf("*/*"))
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
        pendingAttachments += accepted
        renderPendingAttachments()
        updateSendIcon()
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
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.bg_chat_action_card)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(10), dpToPx(10))

            addView(buildAttachmentBadge(attachment.fileType))

            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dpToPx(10)
                    marginEnd = dpToPx(10)
                }

                addView(TextView(requireContext()).apply {
                    text = attachment.fileName
                    maxLines = 1
                    setTextColor(resolveColor(R.attr.colorForegroundPrimary))
                    textSize = 12f
                    typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
                })

                addView(TextView(requireContext()).apply {
                    text = humanFileSize(attachment.fileSize)
                    setTextColor(resolveColor(R.attr.colorForegroundMuted))
                    textSize = 11f
                    typeface = ResourcesCompat.getFont(context, R.font.inter_regular)
                })
            })

            addView(TextView(requireContext()).apply {
                text = "x"
                gravity = Gravity.CENTER
                minWidth = dpToPx(20)
                setTextColor(resolveColor(R.attr.colorForegroundMuted))
                textSize = 13f
                typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
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
            if (channelId != null) {
                api.getChannelMessages(
                    token = session.bearerToken,
                    channelId = channelId!!,
                    numItems = 50
                )
            } else {
                api.getConversationMessages(
                    token = session.bearerToken,
                    conversationId = conversationId!!,
                    numItems = 50
                )
            }
        }.onSuccess { response ->
            messages.clear()
            messages.addAll((response.page ?: response.messages ?: emptyList()).reversed())
            latestMessageTime = messages.maxOfOrNull { it.creationTime ?: 0.0 } ?: 0.0
            renderMessages(scrollToBottom = scrollToBottom)
        }.onFailure {
            renderMessages(scrollToBottom = false)
            toast("Unable to load messages")
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
            val names = response.typing
                .mapNotNull { it.staffName?.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            binding.tvTypingIndicator.text = when (names.size) {
                0 -> ""
                1 -> "${names.first()} is typing..."
                2 -> "${names[0]} and ${names[1]} are typing..."
                else -> "${names[0]}, ${names[1]} and others are typing..."
            }
            binding.tvTypingIndicator.visibility =
                if (names.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun renderMessages(scrollToBottom: Boolean) {
        binding.messagesContainer.removeAllViews()
        binding.tvEmptyMessages.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE

        val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        var lastDayKey: String? = null

        messages
            .filterNot { it.isDeleted == true }
            .forEach { message ->
                val createdMillis = message.creationTime?.toLong() ?: 0L
                val dayKey = dayKey(createdMillis)
                if (dayKey != lastDayKey && createdMillis > 0L) {
                    binding.messagesContainer.addView(buildDateSeparator(createdMillis))
                    lastDayKey = dayKey
                }

                val isMine = message.senderId == myStaffId
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(6)
                    }
                    gravity = if (isMine) Gravity.END else Gravity.START
                }

                if (!isMine && channelId != null) {
                    row.addView(TextView(requireContext()).apply {
                        text = message.senderName ?: "Unknown"
                        setTextColor(ContextCompat.getColor(context, R.color.chat_blue_top))
                        textSize = 11f
                        typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
                        setPadding(dpToPx(10), 0, dpToPx(10), dpToPx(2))
                    })
                }

                val bubble = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = if (isMine) dpToPx(60) else 0
                        marginEnd = if (isMine) 0 else dpToPx(60)
                    }
                    minimumWidth = dpToPx(56)
                    setBackgroundResource(
                        if (isMine) R.drawable.bg_chat_bubble_sent else R.drawable.bg_chat_bubble_received
                    )
                    setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(7))
                }

                addMessageAttachments(bubble, message.attachments.orEmpty(), isMine)

                if (!message.body.isNullOrBlank()) {
                    bubble.addView(TextView(requireContext()).apply {
                        text = message.body.orEmpty()
                        setTextColor(
                            if (isMine) ContextCompat.getColor(context, R.color.lt_foreground_inverse)
                            else ContextCompat.getColor(context, R.color.chat_text_primary)
                        )
                        textSize = 15.5f
                        setLineSpacing(0f, 1.05f)
                        typeface = ResourcesCompat.getFont(context, R.font.inter_regular)
                    })
                }

                bubble.addView(buildBubbleTimeRow(createdMillis, isMine, timeFormatter))
                row.addView(bubble)

                binding.messagesContainer.addView(row)
            }

        if (scrollToBottom) {
            binding.scrollMessages.post {
                binding.scrollMessages.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun buildDateSeparator(timestamp: Long): View {
        val container = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
                bottomMargin = dpToPx(8)
            }
        }
        val pill = TextView(requireContext()).apply {
            text = friendlyDateLabel(timestamp)
            setBackgroundResource(R.drawable.bg_chat_date_pill)
            setPadding(dpToPx(14), dpToPx(3), dpToPx(14), dpToPx(3))
            setTextColor(ContextCompat.getColor(context, R.color.chat_text_primary))
            textSize = 12f
            typeface = ResourcesCompat.getFont(context, R.font.inter_regular)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        container.addView(pill)
        return container
    }

    private fun buildBubbleTimeRow(
        timestamp: Long,
        isMine: Boolean,
        formatter: SimpleDateFormat
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(2)
            }

            addView(TextView(requireContext()).apply {
                text = if (timestamp > 0L) formatter.format(Date(timestamp)) else ""
                textSize = 11f
                setTextColor(
                    if (isMine) ContextCompat.getColor(context, R.color.lt_foreground_inverse)
                    else resolveColor(R.attr.colorForegroundMuted)
                )
                alpha = if (isMine) 0.85f else 0.6f
                typeface = ResourcesCompat.getFont(context, R.font.inter_regular)
            })

            if (isMine) {
                addView(ImageView(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(14), dpToPx(14)).apply {
                        marginStart = dpToPx(4)
                    }
                    setImageResource(R.drawable.ic_chat_check_double)
                    alpha = 0.85f
                })
            }
        }
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
        return SimpleDateFormat("EEE, dd MMM", Locale.getDefault()).format(Date(timestamp))
    }

    private fun addMessageAttachments(
        parent: LinearLayout,
        attachments: List<MessageAttachmentData>,
        isMine: Boolean
    ) {
        attachments.forEachIndexed { index, attachment ->
            val mime = attachment.fileType.orEmpty().lowercase(Locale.getDefault())
            val view = when {
                mime.startsWith("image/") -> createImageAttachmentView(attachment)
                mime.startsWith("video/") -> createVideoAttachmentView(attachment)
                else -> createMessageAttachmentView(attachment, isMine)
            }
            parent.addView(view.apply {
                val params = layoutParams as LinearLayout.LayoutParams
                params.topMargin = if (index == 0) 0 else dpToPx(6)
                params.bottomMargin = dpToPx(4)
                layoutParams = params
            })
        }
    }

    private fun createImageAttachmentView(attachment: MessageAttachmentData): View {
        val width = dpToPx(238)
        val height = dpToPx(178)
        val cornerPx = dpToPx(8).toFloat()

        val container = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(width, height)
            isClickable = !attachment.url.isNullOrBlank()
            isFocusable = isClickable
            if (isClickable) setOnClickListener { openAttachment(attachment) }
        }

        val image = ImageView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_chat_media_placeholder)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, cornerPx)
                }
            }
        }
        attachment.url?.takeIf { it.isNotBlank() }?.let { url ->
            image.load(url) {
                crossfade(true)
                transformations(coil.transform.RoundedCornersTransformation(cornerPx))
            }
        }
        container.addView(image)
        return container
    }

    private fun createVideoAttachmentView(attachment: MessageAttachmentData): View {
        val width = dpToPx(238)
        val height = dpToPx(178)
        val cornerPx = dpToPx(8).toFloat()

        val container = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(width, height)
            isClickable = !attachment.url.isNullOrBlank()
            isFocusable = isClickable
            if (isClickable) setOnClickListener { openAttachment(attachment) }
        }

        val image = ImageView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bg_chat_media_placeholder)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, cornerPx)
                }
            }
        }
        attachment.url?.takeIf { it.isNotBlank() }?.let { url ->
            image.load(url, videoFrameImageLoader()) {
                crossfade(true)
                transformations(coil.transform.RoundedCornersTransformation(cornerPx))
            }
        }
        container.addView(image)

        // Play overlay
        val play = FrameLayout(requireContext()).apply {
            val size = dpToPx(48)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            setBackgroundResource(R.drawable.bg_chat_video_play_circle)
        }
        play.addView(ImageView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(22), dpToPx(22), Gravity.CENTER
            )
            setImageResource(R.drawable.ic_chat_media_play)
        })
        container.addView(play)
        return container
    }

    private var cachedVideoFrameLoader: coil.ImageLoader? = null
    private fun videoFrameImageLoader(): coil.ImageLoader {
        cachedVideoFrameLoader?.let { return it }
        val loader = coil.ImageLoader.Builder(requireContext())
            .components { add(coil.decode.VideoFrameDecoder.Factory()) }
            .build()
        cachedVideoFrameLoader = loader
        return loader
    }

    private fun createMessageAttachmentView(
        attachment: MessageAttachmentData,
        isMine: Boolean
    ): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
            isClickable = !attachment.url.isNullOrBlank()
            isFocusable = isClickable
            if (isClickable) {
                setOnClickListener { openAttachment(attachment) }
            }

            addView(buildAttachmentBadge(attachment.fileType.orEmpty()))

            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dpToPx(8)
                }

                addView(TextView(requireContext()).apply {
                    text = attachment.fileName ?: "Attachment"
                    maxLines = 1
                    setTextColor(
                        if (isMine) ContextCompat.getColor(context, R.color.lt_foreground_inverse)
                        else ContextCompat.getColor(context, R.color.chat_text_primary)
                    )
                    textSize = 13f
                    typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
                })

                addView(TextView(requireContext()).apply {
                    text = buildString {
                        append(readableAttachmentLabel(attachment.fileType.orEmpty()))
                        attachment.fileSize?.takeIf { it > 0 }?.let {
                            append(" • ")
                            append(humanFileSize(it))
                        }
                    }
                    setTextColor(
                        if (isMine) ContextCompat.getColor(context, R.color.lt_foreground_inverse)
                        else ContextCompat.getColor(context, R.color.chat_text_secondary)
                    )
                    alpha = if (isMine) 0.85f else 1f
                    textSize = 11f
                    typeface = ResourcesCompat.getFont(context, R.font.inter_regular)
                })
            })
        }
    }

    private fun buildAttachmentBadge(fileType: String): TextView {
        return TextView(requireContext()).apply {
            text = attachmentBadgeText(fileType)
            gravity = Gravity.CENTER
            minWidth = dpToPx(34)
            minHeight = dpToPx(34)
            setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
            setBackgroundResource(R.drawable.bg_chat_avatar_circle)
            setTextColor(resolveColor(R.attr.colorAccentPrimary))
            textSize = 10f
            typeface = ResourcesCompat.getFont(context, R.font.inter_bold)
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

    private fun readableAttachmentLabel(fileType: String): String {
        val normalized = fileType.lowercase(Locale.getDefault())
        return when {
            normalized.startsWith("image/") -> "Image"
            normalized.contains("pdf") -> "PDF"
            normalized.contains("sheet") || normalized.contains("excel") -> "Spreadsheet"
            normalized.contains("word") || normalized.contains("document") -> "Document"
            else -> "Attachment"
        }
    }

    private fun openAttachment(attachment: MessageAttachmentData) {
        val url = attachment.url ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast("Unable to open attachment")
        }
    }

    private fun handleSendOrMic() {
        if (canSendNow()) {
            sendMessage()
        }
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
        binding.etMessage.setText("")
        pendingAttachments.clear()
        renderPendingAttachments()
        binding.tvTypingIndicator.visibility = View.GONE
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
                        attachments = uploadedAttachments
                    )
                )
            }.onSuccess { response ->
                if (!response.success) {
                    restoreComposer(previousText, pendingSnapshot)
                    toast("Failed to send message")
                    return@onSuccess
                }
                loadInitialMessages(scrollToBottom = true)
                markRead()
            }.onFailure {
                restoreComposer(previousText, pendingSnapshot)
                toast("Network error while sending")
            }

            setComposerBusy(false)
            updateSendIcon()
        }
    }

    private suspend fun uploadPendingAttachments(
        attachments: List<PendingAttachment>
    ): List<MessageAttachmentUpload> {
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

            MessageAttachmentUpload(
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
            if (!s.isNullOrBlank()) {
                sendTypingSignal()
            }
            updateSendIcon()
        }

        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun humanFileSize(sizeBytes: Long): String {
        return Formatter.formatShortFileSize(requireContext(), sizeBytes)
    }

    /**
     * MainActivity uses edge-to-edge (setDecorFitsSystemWindows(false)) which
     * disables the system's `adjustResize` behaviour. The activity's root listener
     * already pads `fragmentContainer` by the visible-keyboard height
     * (`ime.bottom - sys.bottom`), so the chat fragment's vertical LinearLayout
     * naturally compresses and the bottom toolbar lifts with the keyboard.
     *
     * We only do two extra things here:
     *   1) Auto-scroll the message list to the latest message when the keyboard
     *      opens so the focused field stays visible above the IME.
     *   2) Forward the same scroll-to-bottom hint when the layout height changes
     *      (which is what edge-to-edge resize looks like to the fragment).
     */
    private fun applyKeyboardAndSystemInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (ime > 0) {
                binding.scrollMessages.post {
                    binding.scrollMessages.fullScroll(View.FOCUS_DOWN)
                }
            }
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                binding.scrollMessages.post {
                    binding.scrollMessages.fullScroll(View.FOCUS_DOWN)
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
        (activity as? MainActivity)?.setTabBarVisible(true)
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
}
