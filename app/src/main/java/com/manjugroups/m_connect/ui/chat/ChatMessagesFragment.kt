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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private var chatSubtitle: String = "tap here for contact info"
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

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.headerContainer.setOnClickListener { openContactInfo() }
        binding.btnInfo.setOnClickListener { openContactInfo() }
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnAttach.setOnClickListener { openAttachmentPicker() }
        binding.etMessage.addTextChangedListener(typingWatcher)

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
                    chatSubtitle = "tap here for contact info"
                }
            }
        }

        if (_binding != null) {
            binding.tvChatTitle.text = chatTitle
            binding.tvChatSubtitle.text = chatSubtitle
            updateHeaderAvatar(chatTitle)
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
                    typeface = resources.getFont(R.font.inter_semibold)
                })

                addView(TextView(requireContext()).apply {
                    text = humanFileSize(attachment.fileSize)
                    setTextColor(resolveColor(R.attr.colorForegroundMuted))
                    textSize = 11f
                    typeface = resources.getFont(R.font.inter_regular)
                })
            })

            addView(TextView(requireContext()).apply {
                text = "x"
                gravity = Gravity.CENTER
                minWidth = dpToPx(20)
                setTextColor(resolveColor(R.attr.colorForegroundMuted))
                textSize = 13f
                typeface = resources.getFont(R.font.inter_bold)
                setOnClickListener {
                    pendingAttachments.remove(attachment)
                    renderPendingAttachments()
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

        val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())

        messages
            .filterNot { it.isDeleted == true }
            .forEach { message ->
                val isMine = message.senderId == myStaffId
                val row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(8)
                    }
                    gravity = if (isMine) Gravity.END else Gravity.START
                }

                if (!isMine && channelId != null) {
                    row.addView(TextView(requireContext()).apply {
                        text = message.senderName ?: "Unknown"
                        setTextColor(resolveColor(R.attr.colorAccentPrimary))
                        textSize = 11f
                        typeface = resources.getFont(R.font.inter_semibold)
                        setPadding(dpToPx(8), 0, dpToPx(8), dpToPx(3))
                    })
                }

                val bubble = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = if (isMine) dpToPx(68) else 0
                        marginEnd = if (isMine) 0 else dpToPx(68)
                    }
                    minimumWidth = dpToPx(52)
                    setBackgroundResource(
                        if (isMine) R.drawable.bg_chat_bubble_sent else R.drawable.bg_chat_bubble_received
                    )
                    setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
                }

                if (!message.body.isNullOrBlank()) {
                    bubble.addView(TextView(requireContext()).apply {
                        text = message.body.orEmpty()
                        setTextColor(resolveColor(R.attr.colorForegroundPrimary))
                        textSize = 14f
                        setLineSpacing(0f, 1.05f)
                        typeface = resources.getFont(R.font.inter_regular)
                    })
                }

                addMessageAttachments(bubble, message.attachments.orEmpty())
                row.addView(bubble)

                row.addView(TextView(requireContext()).apply {
                    text = message.creationTime?.let { timeFormatter.format(Date(it.toLong())) }.orEmpty()
                    setTextColor(resolveColor(R.attr.colorForegroundMuted))
                    gravity = if (isMine) Gravity.END else Gravity.START
                    setPadding(dpToPx(8), dpToPx(3), dpToPx(8), 0)
                    textSize = 10f
                    typeface = resources.getFont(R.font.geist_mono_regular)
                })

                binding.messagesContainer.addView(row)
            }

        if (scrollToBottom) {
            binding.scrollMessages.post {
                binding.scrollMessages.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun addMessageAttachments(
        parent: LinearLayout,
        attachments: List<MessageAttachmentData>
    ) {
        attachments.forEachIndexed { index, attachment ->
            parent.addView(createMessageAttachmentView(attachment).apply {
                val params = layoutParams as LinearLayout.LayoutParams
                params.topMargin = if (index == 0) dpToPx(8) else dpToPx(6)
                layoutParams = params
            })
        }
    }

    private fun createMessageAttachmentView(attachment: MessageAttachmentData): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundResource(R.drawable.bg_chat_action_card)
            setPadding(dpToPx(10), dpToPx(10), dpToPx(12), dpToPx(10))
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
                    marginStart = dpToPx(10)
                }

                addView(TextView(requireContext()).apply {
                    text = attachment.fileName ?: "Attachment"
                    maxLines = 1
                    setTextColor(resolveColor(R.attr.colorForegroundPrimary))
                    textSize = 12f
                    typeface = resources.getFont(R.font.inter_semibold)
                })

                addView(TextView(requireContext()).apply {
                    text = buildString {
                        append(readableAttachmentLabel(attachment.fileType.orEmpty()))
                        attachment.fileSize?.takeIf { it > 0 }?.let {
                            append(" • ")
                            append(humanFileSize(it))
                        }
                    }
                    setTextColor(resolveColor(R.attr.colorForegroundMuted))
                    textSize = 11f
                    typeface = resources.getFont(R.font.inter_regular)
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
            setBackgroundResource(R.drawable.bg_chat_avatar)
            setTextColor(resolveColor(R.attr.colorAccentPrimary))
            textSize = 10f
            typeface = resources.getFont(R.font.inter_bold)
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
        }

        override fun afterTextChanged(s: Editable?) = Unit
    }

    private fun humanFileSize(sizeBytes: Long): String {
        return Formatter.formatShortFileSize(requireContext(), sizeBytes)
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
