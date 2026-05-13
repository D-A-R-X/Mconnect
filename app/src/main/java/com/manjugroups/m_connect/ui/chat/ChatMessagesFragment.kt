package com.manjugroups.m_connect.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
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
import com.manjugroups.m_connect.network.DeleteMessageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatMessagesFragment : Fragment(), ChatMessageActionsFragment.Callback {

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

    private lateinit var chatAdapter: ChatMessageAdapter
    private lateinit var mentionAdapter: MentionAdapter

    private var replyingToMessage: MessageData? = null

    private var pollJob: Job? = null
    private var typingDebounceJob: Job? = null
    private var isSendingMessage = false
    private var isAttachmentMenuOpen = false

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
        
        setupAdapters()
        setupSwipeToReply()
        renderPendingAttachments()
        updateSendIcon()

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.headerContainer.setOnClickListener { openContactInfo() }
        binding.btnSearch.setOnClickListener { toast("Search feature coming soon") }
        binding.btnSend.setOnClickListener { handleSendOrMic() }
        
        binding.btnAttach.setOnClickListener { 
            toggleAttachmentMenu()
        }

        binding.menuAttachFile.setOnClickListener {
            toggleAttachmentMenu()
            pickAttachmentsLauncher.launch(arrayOf("*/*"))
        }
        binding.menuAttachMedia.setOnClickListener {
            toggleAttachmentMenu()
            pickAttachmentsLauncher.launch(arrayOf("image/*", "video/*"))
        }
        binding.menuAttachCamera.setOnClickListener {
            toggleAttachmentMenu()
            toast("Camera feature coming soon")
        }

        binding.btnCancelReply.setOnClickListener {
            cancelReply()
        }

        binding.btnEmoji.setOnClickListener {
            toast("Emoji picker coming soon")
        }

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

    private fun setupAdapters() {
        chatAdapter = ChatMessageAdapter(
            onMessageLongClick = { message: MessageData -> showMessageActions(message) },
            onAttachmentClick = { url: String -> openAttachmentUrl(url) }
        )
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }

        mentionAdapter = MentionAdapter { person: MentionPerson ->
            insertMention(person)
        }
        binding.rvMentions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = mentionAdapter
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

    private fun showMessageActions(message: MessageData) {
        val actions = ChatMessageActionsFragment.newInstance(message.id ?: "", message.body ?: "")
        actions.setCallback(this)
        actions.show(childFragmentManager, "MessageActions")
    }

    override fun onReply(messageId: String) {
        val message = messages.find { it.id == messageId }
        if (message != null) {
            showReplyUI(message)
        }
    }

    override fun onReact(messageId: String, emoji: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.addReaction(session.bearerToken, ReactionRequest(messageId, emoji))
            }.onSuccess {
                loadInitialMessages(scrollToBottom = false)
            }.onFailure {
                toast("Unable to add reaction")
            }
        }
    }

    override fun onCopy(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Chat Message", text)
        clipboard.setPrimaryClip(clip)
        toast("Message copied")
    }

    override fun onDelete(messageId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.deleteMessage(session.bearerToken, DeleteMessageRequest(messageId))
            }.onSuccess {
                loadInitialMessages(scrollToBottom = false)
            }.onFailure {
                toast("Unable to delete message")
            }
        }
    }

    override fun onForward(messageId: String) {
        toast("Forwarding coming soon")
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
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    private fun toggleAttachmentMenu() {
        isAttachmentMenuOpen = !isAttachmentMenuOpen
        binding.attachmentMenu.visibility = if (isAttachmentMenuOpen) View.VISIBLE else View.GONE
        
        // Rotate the plus icon
        binding.btnAttach.animate()
            .rotation(if (isAttachmentMenuOpen) 45f else 0f)
            .setDuration(200)
            .start()
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
            gravity = android.view.Gravity.CENTER_VERTICAL
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
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_semibold)
                })

                addView(TextView(requireContext()).apply {
                    text = humanFileSize(attachment.fileSize)
                    setTextColor(resolveColor(R.attr.colorForegroundMuted))
                    textSize = 11f
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_regular)
                })
            })

            addView(TextView(requireContext()).apply {
                text = "x"
                gravity = android.view.Gravity.CENTER
                minWidth = dpToPx(20)
                setTextColor(resolveColor(R.attr.colorForegroundMuted))
                textSize = 13f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_bold)
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

            // We hide indicator for now per reference design, or we can overlay it
        }
    }

    private fun renderMessages(scrollToBottom: Boolean) {
        val chatItems = mutableListOf<ChatItem>()
        var lastDayKey: String? = null

        messages
            .filterNot { it.isDeleted == true }
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
            toast("Recording coming soon")
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
        val parentId = replyingToMessage?.id

        binding.etMessage.setText("")
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
            
            binding.bottomBar.setPadding(
                dpToPx(12),
                dpToPx(12),
                dpToPx(12),
                dpToPx(12) + sys.bottom
            )
            
            if (ime.bottom > 0) {
                binding.rvMessages.post {
                    binding.rvMessages.smoothScrollToPosition(chatAdapter.itemCount.coerceAtLeast(1) - 1)
                }
            }
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                binding.rvMessages.post {
                    binding.rvMessages.smoothScrollToPosition(chatAdapter.itemCount.coerceAtLeast(1) - 1)
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
}
