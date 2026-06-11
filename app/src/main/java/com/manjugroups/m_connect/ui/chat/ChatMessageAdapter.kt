package com.manjugroups.m_connect.ui.chat

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.ItemChatDateSeparatorBinding
import com.manjugroups.m_connect.databinding.ItemChatMessageReceivedBinding
import com.manjugroups.m_connect.databinding.ItemChatMessageSentBinding
import com.manjugroups.m_connect.network.MessageAttachmentData
import com.manjugroups.m_connect.network.MessageData
import com.manjugroups.m_connect.network.ReactionData
import java.text.SimpleDateFormat
import java.util.*

sealed class ChatItem {
    data class Message(val data: MessageData, val isMine: Boolean, val showAvatar: Boolean, val showName: Boolean) : ChatItem()
    data class DateSeparator(val date: String) : ChatItem()
}

class ChatMessageAdapter(
    private val onMessageReactionClick: (MessageData, View) -> Unit,
    private val onReactionPillClick: (MessageData, View) -> Unit,
    private val onAttachmentClick: (url: String, mime: String, storageId: String?, fileName: String?) -> Unit,
    private val onReplyClick: (messageId: String) -> Unit,
    private val onMessageTap: ((MessageData) -> Boolean)? = null
) : ListAdapter<ChatItem, RecyclerView.ViewHolder>(ChatItemDiffCallback()) {

    var selectionMode: Boolean = false
        private set
    private val selectedIds = mutableSetOf<String>()
    private val selectionTint = android.graphics.Color.parseColor("#1A0B61CA")

    fun isSelected(id: String?): Boolean = id != null && id in selectedIds

    fun selectedIdsSnapshot(): List<String> = selectedIds.toList()

    fun selectionCount(): Int = selectedIds.size

    fun setSelectionMode(enabled: Boolean) {
        if (selectionMode == enabled) return
        selectionMode = enabled
        if (!enabled) selectedIds.clear()
        notifyDataSetChanged()
    }

    fun toggleSelected(id: String): Int {
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
        notifyDataSetChanged()
        return selectedIds.size
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        notifyDataSetChanged()
    }

    /**
     * Unified long-press handler used by both text bubbles and media attachments.
     *
     * - In selection mode: behaves like a regular tap (toggle selection on this message).
     * - Outside selection mode: pops the emoji reaction sheet AND enters selection
     *   mode with this message pre-selected. That second part is what lets users
     *   subsequently tap other messages to multi-select WhatsApp-style — without
     *   it, only the long-pressed message would ever be a candidate for delete /
     *   forward / star.
     */
    private fun handleLongPress(data: MessageData, anchorView: View) {
        if (selectionMode) {
            data.id?.let { toggleSelected(it) }
            onMessageTap?.invoke(data)
            return
        }
        onMessageReactionClick(data, anchorView)
    }

    fun applySelectionVisual(rootView: View, messageId: String?) {
        val highlightedSelection = selectionMode && isSelected(messageId)
        val highlightedSearch = !selectionMode && messageId != null && messageId in searchHighlightIds
        val color = when {
            highlightedSelection -> selectionTint
            highlightedSearch -> searchTint
            else -> android.graphics.Color.TRANSPARENT
        }
        rootView.setBackgroundColor(color)
    }

    private val searchHighlightIds = mutableSetOf<String>()
    private val searchTint = android.graphics.Color.parseColor("#33FFD60A")

    fun setSearchHighlight(ids: Collection<String>) {
        if (searchHighlightIds.size == ids.size && searchHighlightIds.containsAll(ids)) return
        searchHighlightIds.clear()
        searchHighlightIds.addAll(ids)
        notifyDataSetChanged()
    }

    fun clearSearchHighlight() {
        if (searchHighlightIds.isEmpty()) return
        searchHighlightIds.clear()
        notifyDataSetChanged()
    }

    private var currentlyPlayingStorageId: String? = null
    private var currentlyPlayingProgress: Float = 0f
    private val audioBarsCache = java.util.WeakHashMap<String, List<View>>()
    private val audioBarBaseHeights = java.util.WeakHashMap<String, IntArray>()
    private val audioWaveAnimators = java.util.WeakHashMap<String, android.animation.ValueAnimator>()
    private val audioDurationCache = mutableMapOf<String, String>()
    private val audioPositionViews = java.util.WeakHashMap<String, TextView>()
    private val accentColor = android.graphics.Color.parseColor("#0B61CA")
    private val activeBarColor = android.graphics.Color.parseColor("#FFFFFF")
    private val mutedBarColor = android.graphics.Color.parseColor("#80FFFFFF")
    private val parentMessageCache = mutableMapOf<String, MessageData>()

    fun setParentMessageCache(map: Map<String, MessageData>) {
        // Avoid notifyDataSetChanged here — it forces a full rebind of every
        // visible message, which causes a visible flicker each time
        // enrichMessages or the poll loop refreshes parent quotes. Reply-quote
        // text resolves at bind time, so subsequent submitList() diffs (poll,
        // send, react) will naturally pick up the new cache values.
        parentMessageCache.clear()
        parentMessageCache.putAll(map)
    }

    fun setCurrentlyPlayingStorageId(id: String?) {
        if (currentlyPlayingStorageId == id) return
        // Stop any prior wave animator
        audioWaveAnimators.values.forEach { it.cancel() }
        audioWaveAnimators.clear()
        currentlyPlayingStorageId = id
        if (id == null) currentlyPlayingProgress = 0f
        notifyDataSetChanged()
    }

    fun setAudioPlaybackProgress(storageId: String, progress: Float, positionLabel: String? = null) {
        if (storageId != currentlyPlayingStorageId) return
        currentlyPlayingProgress = progress.coerceIn(0f, 1f)
        val bars = audioBarsCache[storageId] ?: return
        val activeCount = (bars.size * currentlyPlayingProgress).toInt().coerceIn(0, bars.size)
        bars.forEachIndexed { index, bar ->
            val target = if (index < activeCount) accentColor else mutedBarColor
            if (bar.tag != target) {
                bar.setBackgroundColor(target)
                bar.tag = target
            }
        }
        positionLabel?.let { audioPositionViews[storageId]?.text = it }
    }

    fun cacheAudioDuration(storageId: String, label: String) {
        audioDurationCache[storageId] = label
        audioPositionViews[storageId]?.text = label
    }

    companion object {
        const val TYPE_SENT = 0
        const val TYPE_RECEIVED = 1
        const val TYPE_DATE = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = getItem(position)) {
            is ChatItem.Message -> if (item.isMine) TYPE_SENT else TYPE_RECEIVED
            is ChatItem.DateSeparator -> TYPE_DATE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SENT -> SentViewHolder(ItemChatMessageSentBinding.inflate(inflater, parent, false))
            TYPE_RECEIVED -> ReceivedViewHolder(ItemChatMessageReceivedBinding.inflate(inflater, parent, false))
            TYPE_DATE -> DateViewHolder(ItemChatDateSeparatorBinding.inflate(inflater, parent, false))
            else -> throw IllegalArgumentException("Unknown view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChatItem.Message -> {
                if (holder is SentViewHolder) holder.bind(item)
                else if (holder is ReceivedViewHolder) holder.bind(item)
            }
            is ChatItem.DateSeparator -> (holder as DateViewHolder).bind(item)
        }
    }

    inner class SentViewHolder(val binding: ItemChatMessageSentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.Message) {
            val isDeleted = item.data.isDeleted == true
            resetBodyStyle(binding.tvMessageBody, isSent = true)
            binding.tvMessageTime.setTextColor(android.graphics.Color.parseColor("#CCFFFFFF"))
            binding.ivSeenStatus.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#80FFFFFF"))
            applyBubbleChrome(binding.bubbleFrame, binding.tvMessageTime, item, isSent = true)
            applyBodyMaxWidth(binding.tvMessageBody)
            binding.ivSeenStatus.visibility = if (isDeleted) View.GONE else View.VISIBLE
            // "Forwarded" tag — visible when this message was forwarded from
            // this device. Tracked locally because the backend schema has no
            // isForwarded field yet.
            binding.forwardedTagRow.visibility =
                if (!isDeleted && ForwardedMessageStore.isForwarded(binding.root.context, item.data.id)) View.VISIBLE
                else View.GONE
            if (isDeleted) {
                renderDeletedBubble(
                    body = binding.tvMessageBody,
                    timeView = binding.tvMessageTime,
                    creationTime = item.data.creationTime,
                    replyContainer = binding.replyContainer,
                    attachments = binding.attachmentsContainer,
                    reactions = binding.reactionsLayout,
                    isSent = true
                )
                binding.root.setOnLongClickListener { true }
                return
            }

            binding.tvMessageBody.text = item.data.body
            binding.tvMessageBody.isVisible = !item.data.body.isNullOrBlank()
            binding.tvMessageBody.alpha = 1f
            binding.tvMessageBody.typeface = androidx.core.content.res.ResourcesCompat
                .getFont(binding.root.context, R.font.inter_regular)
            binding.tvMessageTime.text = formatTime(item.data.creationTime)

            // Apply animated emoji style if body contains only 1-3 emojis
            item.data.body?.let { bodyText ->
                applyEmojiOnlyStyle(
                    body = binding.tvMessageBody,
                    bubbleFrame = binding.bubbleFrame,
                    timeView = binding.tvMessageTime,
                    tickIcon = binding.ivSeenStatus,
                    text = bodyText,
                    isMine = true
                )
            }

            bindReplyQuote(
                parentId = item.data.parentMessageId,
                container = binding.replyContainer,
                nameView = binding.tvReplyName,
                bodyView = binding.tvReplyBody,
                previewView = binding.ivReplyPreview,
                ownSenderId = item.data.senderId
            )

            applySelectionVisual(binding.root, item.data.id)
            binding.root.setOnClickListener {
                if (selectionMode) {
                    item.data.id?.let { toggleSelected(it) }
                    onMessageTap?.invoke(item.data)
                }
            }
            binding.root.setOnLongClickListener {
                handleLongPress(item.data, binding.bubbleFrame)
                true
            }

            binding.attachmentsContainer.removeAllViews()
            val sentLongPress: () -> Unit = {
                handleLongPress(item.data, binding.bubbleFrame)
            }
            item.data.attachments?.forEach { attachment ->
                binding.attachmentsContainer.addView(
                    createAttachmentView(
                        binding.attachmentsContainer,
                        attachment,
                        isMine = true,
                        creationTime = item.data.creationTime,
                        onLongPress = sentLongPress,
                    )
                )
            }
            val voiceOnly = isVoiceOnly(item.data)
            (binding.tvMessageTime.parent as? View)?.visibility =
                if (voiceOnly) View.GONE else View.VISIBLE

            bindReactions(binding.reactionsLayout, item.data.reactions) {
                onReactionPillClick(item.data, binding.reactionsLayout)
            }
        }
    }

    inner class ReceivedViewHolder(val binding: ItemChatMessageReceivedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.Message) {
            val isDeleted = item.data.isDeleted == true
            resetBodyStyle(binding.tvMessageBody, isSent = false)
            binding.tvMessageTime.setTextColor(android.graphics.Color.parseColor("#8E8E93"))
            applyBubbleChrome(binding.bubbleFrame, binding.tvMessageTime, item, isSent = false)
            applyBodyMaxWidth(binding.tvMessageBody)

            binding.tvSenderName.text = item.data.senderName
            binding.tvSenderName.visibility = if (item.showName) View.VISIBLE else View.GONE
            binding.avatarContainer.visibility = if (item.showAvatar) View.VISIBLE else View.GONE

            if (item.showAvatar) {
                val initials = item.data.senderName?.split(" ")
                    ?.filter { it.isNotBlank() }
                    ?.take(2)
                    ?.joinToString("") { it.first().uppercase() } ?: "U"
                binding.tvSenderAvatar.text = initials
                binding.ivSenderAvatar.setImageDrawable(null)
            }

            // "Forwarded" tag for received bubbles — same local store as the
            // sent side; the tag shows up only if THIS device originated the
            // forward. Cross-user forward detection waits on a backend flag.
            binding.forwardedTagRow.visibility =
                if (!isDeleted && ForwardedMessageStore.isForwarded(binding.root.context, item.data.id)) View.VISIBLE
                else View.GONE

            if (isDeleted) {
                renderDeletedBubble(
                    body = binding.tvMessageBody,
                    timeView = binding.tvMessageTime,
                    creationTime = item.data.creationTime,
                    replyContainer = binding.replyContainer,
                    attachments = binding.attachmentsContainer,
                    reactions = binding.reactionsLayout,
                    isSent = false
                )
                binding.root.setOnLongClickListener { true }
                return
            }

            binding.tvMessageBody.text = item.data.body
            binding.tvMessageBody.isVisible = !item.data.body.isNullOrBlank()
            binding.tvMessageBody.alpha = 1f
            binding.tvMessageBody.typeface = androidx.core.content.res.ResourcesCompat
                .getFont(binding.root.context, R.font.inter_regular)
            binding.tvMessageTime.text = formatTime(item.data.creationTime)

            // Apply animated emoji style if body contains only 1-3 emojis
            item.data.body?.let { bodyText ->
                applyEmojiOnlyStyle(
                    body = binding.tvMessageBody,
                    bubbleFrame = binding.bubbleFrame,
                    timeView = binding.tvMessageTime,
                    tickIcon = null,
                    text = bodyText,
                    isMine = false
                )
            }

            bindReplyQuote(
                parentId = item.data.parentMessageId,
                container = binding.replyContainer,
                nameView = binding.tvReplyName,
                bodyView = binding.tvReplyBody,
                previewView = binding.ivReplyPreview,
                ownSenderId = item.data.senderId
            )

            applySelectionVisual(binding.root, item.data.id)
            binding.root.setOnClickListener {
                if (selectionMode) {
                    item.data.id?.let { toggleSelected(it) }
                    onMessageTap?.invoke(item.data)
                }
            }
            binding.root.setOnLongClickListener {
                handleLongPress(item.data, binding.bubbleFrame)
                true
            }

            binding.attachmentsContainer.removeAllViews()
            val receivedLongPress: () -> Unit = {
                handleLongPress(item.data, binding.bubbleFrame)
            }
            item.data.attachments?.forEach { attachment ->
                binding.attachmentsContainer.addView(
                    createAttachmentView(
                        binding.attachmentsContainer,
                        attachment,
                        isMine = false,
                        creationTime = item.data.creationTime,
                        onLongPress = receivedLongPress,
                    )
                )
            }
            val voiceOnly = isVoiceOnly(item.data)
            (binding.tvMessageTime.parent as? View)?.visibility =
                if (voiceOnly) View.GONE else View.VISIBLE

            bindReactions(binding.reactionsLayout, item.data.reactions) {
                onReactionPillClick(item.data, binding.reactionsLayout)
            }
        }
    }

    private fun applyVoiceOnlyTimeStyle(timeView: TextView, isVoiceOnly: Boolean, isSent: Boolean) {
        if (isVoiceOnly) {
            timeView.setTextColor(android.graphics.Color.parseColor("#8E8E93"))
        } else {
            timeView.setTextColor(
                if (isSent) android.graphics.Color.parseColor("#CCFFFFFF")
                else android.graphics.Color.parseColor("#8E8E93")
            )
        }
    }

    private fun applyBodyMaxWidth(body: TextView) {
        val metrics = body.context.resources.displayMetrics
        body.maxWidth = (metrics.widthPixels * 0.62f).toInt()
    }

    private fun resetBodyStyle(body: TextView, isSent: Boolean) {
        body.clearAnimation()
        body.scaleX = 1f
        body.scaleY = 1f
        body.alpha = 1f
        body.textSize = 14f
        body.setTextColor(
            if (isSent) android.graphics.Color.parseColor("#FFFFFF")
            else android.graphics.Color.parseColor("#101828")
        )
        body.typeface = androidx.core.content.res.ResourcesCompat
            .getFont(body.context, R.font.inter_regular)
        body.setCompoundDrawables(null, null, null, null)
    }

    private fun applyBubbleChrome(
        bubbleFrame: FrameLayout,
        timeView: TextView,
        item: ChatItem.Message,
        isSent: Boolean
    ) {
        val isDeleted = item.data.isDeleted == true
        if (isVoiceOnly(item.data)) {
            bubbleFrame.background = null
            bubbleFrame.setPadding(0, 0, 0, 0)
        } else {
            bubbleFrame.setBackgroundResource(
                if (isSent) R.drawable.bg_chat_bubble_sent
                else R.drawable.bg_chat_bubble_received
            )
            val px10 = dp(bubbleFrame.context, 10)
            val px5 = dp(bubbleFrame.context, 5)
            bubbleFrame.setPadding(px10, px5, px10, px5)
        }
    }

    private fun isVoiceOnly(data: MessageData): Boolean {
        if (!data.body.isNullOrBlank()) return false
        val attachments = data.attachments ?: return false
        if (attachments.size != 1) return false
        return isAudioAttachment(attachments.first())
    }

    // True for any attachment that should render as a voice/audio bubble.
    // Web composer sends `voice_message_<ts>.webm` with `fileType=audio/webm`
    // (Chrome/Edge/Firefox) or `.m4a` + `audio/mp4` on Safari. `.webm` is
    // also a video container, so callers must short-circuit audio before
    // video — otherwise the webm voice note renders as a video preview.
    private fun isAudioAttachment(a: MessageAttachmentData): Boolean {
        val mime = a.fileType.orEmpty().lowercase()
        val name = a.fileName.orEmpty().lowercase()
        if (mime.startsWith("audio/")) return true
        return name.endsWith(".m4a") ||
            name.endsWith(".mp3") ||
            name.endsWith(".wav") ||
            name.endsWith(".aac") ||
            name.endsWith(".caf") ||
            name.endsWith(".ogg") ||
            name.endsWith(".opus") ||
            name.startsWith("voice-") ||
            name.startsWith("voice_message_")
    }

    private fun renderDeletedBubble(
        body: TextView,
        timeView: TextView,
        creationTime: Double?,
        replyContainer: View,
        attachments: ViewGroup,
        reactions: LinearLayout,
        isSent: Boolean
    ) {
        body.text = if (isSent) "You deleted this message" else "This message was deleted"
        body.isVisible = true
        body.alpha = 1f
        val textColor = if (isSent) "#D1E9FF" else "#8E8E93"
        body.setTextColor(android.graphics.Color.parseColor(textColor))
        body.textSize = 13f
        body.typeface = androidx.core.content.res.ResourcesCompat
            .getFont(body.context, R.font.inter_regular)
        body.setTypeface(body.typeface, android.graphics.Typeface.ITALIC)
        
        // Load a clean deleted/trash icon next to the text
        val deleteDrawable = androidx.core.content.res.ResourcesCompat.getDrawable(
            body.resources,
            R.drawable.ic_chat_delete,
            body.context.theme
        )?.mutate()?.apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                setTint(android.graphics.Color.parseColor(textColor))
            }
            setBounds(0, 0, dp(body.context, 16), dp(body.context, 16))
        }
        body.setCompoundDrawables(deleteDrawable, null, null, null)
        body.compoundDrawablePadding = dp(body.context, 6)

        timeView.text = ""
        (timeView.parent as? View)?.visibility = View.GONE
        replyContainer.visibility = View.GONE
        attachments.removeAllViews()
        reactions.removeAllViews()
        reactions.visibility = View.GONE
    }

    private fun bindReplyQuote(
        parentId: String?,
        container: View,
        nameView: TextView,
        bodyView: TextView,
        previewView: ImageView,
        ownSenderId: String?
    ) {
        if (parentId.isNullOrBlank()) {
            container.visibility = View.GONE
            return
        }
        val parent = findMessageInList(parentId)
        if (parent == null) {
            container.visibility = View.VISIBLE
            nameView.text = "Replied message"
            bodyView.text = "…"
            previewView.visibility = View.GONE
            container.setOnClickListener { onReplyClick(parentId) }
            return
        }
        container.visibility = View.VISIBLE
        nameView.text = if (parent.senderId == ownSenderId) "You" else parent.senderName

        val imageAttachment = parent.attachments?.firstOrNull { it.fileType?.startsWith("image/") == true }
        val previewText = getMessagePreviewText(parent)
        bodyView.text = previewText.ifBlank { "Attachment" }

        if (imageAttachment != null && parent.isDeleted != true) {
            previewView.visibility = View.VISIBLE
            previewView.load(imageAttachment.url ?: imageAttachment.storageId) {
                transformations(coil.transform.RoundedCornersTransformation(8f))
            }
        } else {
            previewView.visibility = View.GONE
        }
        container.setOnClickListener { onReplyClick(parentId) }
    }

    private fun findMessageInList(id: String): MessageData? {
        return currentList.filterIsInstance<ChatItem.Message>().find { it.data.id == id }?.data
            ?: parentMessageCache[id]
    }

    private fun bindReactions(layout: LinearLayout, reactions: List<ReactionData>?, onClick: () -> Unit) {
        layout.removeAllViews()
        if (reactions.isNullOrEmpty()) {
            layout.visibility = View.GONE
            layout.setOnClickListener(null)
            return
        }

        layout.visibility = View.VISIBLE
        layout.setOnClickListener { onClick() }
        reactions.forEach { reaction ->
            val tv = TextView(layout.context).apply {
                text = "${reaction.emoji} ${reaction.count ?: ""}"
                textSize = 10f
                setPadding(4, 0, 4, 0)
            }
            layout.addView(tv)
        }
    }

    private fun createAudioBubble(
        context: android.content.Context,
        url: String,
        attachment: MessageAttachmentData,
        isMine: Boolean,
        creationTime: Double? = null
    ): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(
                if (isMine) R.drawable.bg_audio_bubble_sent
                else R.drawable.bg_audio_bubble_received
            )
            setPadding(dp(context, 10), dp(context, 8), dp(context, 14), dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(
                (272 * context.resources.displayMetrics.density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(context, 4)
            }
        }

        val storageId = attachment.storageId
        val isPlaying = !storageId.isNullOrBlank() && storageId == currentlyPlayingStorageId

        // Color scheme flips between sent (blue bubble → white inner) and received
        // (white bubble → blue inner). Keeps both bubbles readable.
        val activeColor = if (isMine) activeBarColor else accentColor
        val mutedColor = if (isMine) mutedBarColor else android.graphics.Color.parseColor("#D0D5DD")
        val playBubbleBg = if (isMine) R.drawable.bg_audio_play_circle
        else R.drawable.bg_audio_play_circle_accent
        val playIconTint = if (isMine) accentColor
        else android.graphics.Color.WHITE
        val footerTextColor = if (isMine) activeBarColor
        else android.graphics.Color.parseColor("#475467")

        val playBtn = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 40), dp(context, 40))
            setImageResource(if (isPlaying) R.drawable.ic_chat_media_pause else R.drawable.ic_chat_media_play)
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            setBackgroundResource(playBubbleBg)
            imageTintList = android.content.res.ColorStateList.valueOf(playIconTint)
            isClickable = true
            isFocusable = true
            contentDescription = if (isPlaying) "Pause voice message" else "Play voice message"
        }

        val waveAndMeta = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
                // For received messages we'll mount the play button on the
                // right of the waveform (WhatsApp-style), so flip the side
                // margins to keep the wave row visually balanced.
                if (isMine) {
                    marginStart = dp(context, 12)
                    marginEnd = dp(context, 6)
                } else {
                    marginStart = dp(context, 6)
                    marginEnd = dp(context, 12)
                }
            }
        }

        val waveformRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 28)
            )
        }
        val barCount = 32
        val seed = (attachment.id ?: storageId ?: url).hashCode()
        val random = java.util.Random(seed.toLong())
        val bars = mutableListOf<View>()
        val baseHeights = IntArray(barCount)
        val activeCount = if (isPlaying) {
            (barCount * currentlyPlayingProgress).toInt().coerceIn(0, barCount)
        } else 0
        repeat(barCount) { index ->
            val h = 4 + random.nextInt(18)
            baseHeights[index] = dp(context, h)
            val color = if (index < activeCount) activeColor else mutedColor
            val bar = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 3), baseHeights[index]).apply {
                    marginEnd = dp(context, 2)
                }
                setBackgroundColor(color)
                tag = color
            }
            bars.add(bar)
            waveformRow.addView(bar)
        }
        if (!storageId.isNullOrBlank()) {
            audioBarsCache[storageId] = bars
            audioBarBaseHeights[storageId] = baseHeights
        }

        val durationText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
            text = audioDurationCache[storageId.orEmpty()] ?: "Voice message"
            setTextColor(footerTextColor)
            alpha = if (isMine) 0.85f else 1f
            textSize = 11f
        }
        if (!storageId.isNullOrBlank()) {
            audioPositionViews[storageId] = durationText
        }

        val timeText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(context, 6) }
            text = formatTime(creationTime)
            setTextColor(footerTextColor)
            alpha = if (isMine) 0.75f else 0.85f
            textSize = 10f
        }

        val tickIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 14), dp(context, 14)).apply {
                marginStart = dp(context, 3)
            }
            setImageResource(R.drawable.ic_chat_check_double)
            setColorFilter(footerTextColor)
            alpha = 0.75f
            visibility = if (isMine) View.VISIBLE else View.GONE
        }

        val footerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 4) }
            addView(durationText)
            addView(timeText)
            addView(tickIcon)
        }

        waveAndMeta.addView(waveformRow)
        waveAndMeta.addView(footerRow)

        // Sent: play button on the left (next to the avatar/bubble origin).
        // Received: play button on the right, mirroring WhatsApp's layout
        // for incoming voice notes.
        if (isMine) {
            container.addView(playBtn)
            container.addView(waveAndMeta)
        } else {
            container.addView(waveAndMeta)
            container.addView(playBtn)
        }

        val click = View.OnClickListener {
            onAttachmentClick(url, "audio/mp4", storageId, attachment.fileName)
        }
        playBtn.setOnClickListener(click)
        container.setOnClickListener(click)

        if (isPlaying && !storageId.isNullOrBlank()) {
            startWavePulse(storageId, bars, baseHeights)
        }

        return container
    }

    private fun createVideoPreview(
        context: android.content.Context,
        url: String,
        attachment: MessageAttachmentData,
        mime: String
    ): View {
        val container = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 4) }
            minimumWidth = dp(context, 200)
            background = androidx.core.content.ContextCompat.getDrawable(
                context, R.drawable.bg_chat_media_placeholder
            )
            clipToOutline = true
        }

        val thumb = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(context, 220),
                dp(context, 140)
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1F2937.toInt())
            load(url) {
                crossfade(true)
                placeholder(R.drawable.bg_chat_media_placeholder)
                decoderFactory(coil.decode.VideoFrameDecoder.Factory())
                transformations(coil.transform.RoundedCornersTransformation(dp(context, 10).toFloat()))
            }
        }
        container.addView(thumb)

        val play = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(context, 48),
                dp(context, 48),
                android.view.Gravity.CENTER
            )
            setImageResource(R.drawable.ic_chat_media_play)
            setBackgroundResource(R.drawable.bg_chat_video_play_overlay)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        }
        container.addView(play)

        container.setOnClickListener { onAttachmentClick(url, mime, attachment.storageId, attachment.fileName) }
        // Long-press is wired by createAttachmentView (which delegates to the
        // bubble's selection/reaction handler), so we leave the container
        // long-clickable here.
        return container
    }

    private fun createAttachmentView(
        parent: ViewGroup,
        attachment: MessageAttachmentData,
        isMine: Boolean = false,
        creationTime: Double? = null,
        onLongPress: (() -> Unit)? = null,
    ): View {
        val context = parent.context
        val url = attachment.url ?: ""
        val mime = attachment.fileType.orEmpty().lowercase()
        val fileName = attachment.fileName.orEmpty().lowercase()

        val isAudio = isAudioAttachment(attachment) && mime != "application/octet-stream"

        // NOTE: must be evaluated AFTER isAudio because `.webm` and `.mp4`
        // are valid containers for both audio and video; voice notes from the
        // web composer arrive as `voice_message_*.webm` which would otherwise
        // collide with the video-extension list below.
        val isVideo = !isAudio && mime != "application/octet-stream" && (
            mime.startsWith("video/") ||
                fileName.endsWith(".mp4") ||
                fileName.endsWith(".mov") ||
                fileName.endsWith(".webm") ||
                fileName.endsWith(".mkv") ||
                fileName.endsWith(".3gp") ||
                fileName.endsWith(".avi")
            )

        val attachmentView = if (mime.startsWith("image/")) {
            FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dp(context, 220),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(context, 4)
                }
                background = androidx.core.content.ContextCompat.getDrawable(
                    context, R.drawable.bg_chat_media_placeholder
                )
                clipToOutline = true

                val img = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                    minimumHeight = dp(context, 140)
                    maxHeight = dp(context, 320)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    if (!url.isNullOrBlank()) {
                        load(url) {
                            crossfade(true)
                            placeholder(R.drawable.bg_chat_media_placeholder)
                            error(R.drawable.bg_chat_media_placeholder)
                            transformations(
                                coil.transform.RoundedCornersTransformation(
                                    dp(context, 10).toFloat()
                                )
                            )
                        }
                    } else {
                        setImageResource(R.drawable.bg_chat_media_placeholder)
                    }
                }
                addView(img)
                setOnClickListener { onAttachmentClick(url, mime, attachment.storageId, attachment.fileName) }
            }
        } else if (isVideo) {
            createVideoPreview(context, url, attachment, mime)
        } else if (isAudio) {
            createAudioBubble(context, url, attachment, isMine, creationTime)
        } else {
            createDocumentBubble(context, url, mime, attachment, isMine)
        }

        // Wire long-press on the attachment view so the same selection/reaction
        // flow fires for voice notes, PDFs, images, videos, and docs — not just
        // text bubbles. Previously these views were either marked
        // isLongClickable=false or had setOnClickListener that suppressed the
        // event, blocking forward / react gestures on media-only messages.
        if (onLongPress != null) {
            attachmentView.isLongClickable = true
            attachmentView.setOnLongClickListener {
                onLongPress.invoke()
                true
            }
        }
        return attachmentView
    }

    /**
     * Renders a rich document (PDF / file) attachment card matching the design:
     * a red "PDF" badge, filename + size, and Open / Save as actions.
     * Bubble background flips for sent (blue gradient) vs received (white).
     */
    private fun createDocumentBubble(
        context: android.content.Context,
        url: String,
        mime: String,
        attachment: MessageAttachmentData,
        isMine: Boolean,
    ): View {
        val primaryText = if (isMine) android.graphics.Color.WHITE
        else android.graphics.Color.parseColor("#101828")
        val secondaryText = if (isMine) android.graphics.Color.parseColor("#CCFFFFFF")
        else android.graphics.Color.parseColor("#667085")
        val actionText = if (isMine) android.graphics.Color.WHITE
        else android.graphics.Color.parseColor("#0B61CA")
        val dividerBg = if (isMine) R.drawable.bg_chat_doc_divider_sent
        else R.drawable.bg_chat_doc_divider_received

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(
                if (isMine) R.drawable.bg_chat_doc_sent
                else R.drawable.bg_chat_doc_received
            )
            layoutParams = LinearLayout.LayoutParams(
                (260 * context.resources.displayMetrics.density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(context, 4) }
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 4))
        }

        // Top row: PDF badge + filename/size
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val ext = inferDocBadgeLabel(attachment.fileName, mime).uppercase(Locale.US)
        
        // Dynamic badge colors matching Media tab
        val badgeBgColor = when (ext) {
            "PDF" -> "#FFECEB"
            "DOC", "DOCX" -> "#EFF8FF"
            "XLS", "XLSX" -> "#ECFDF3"
            "MP4", "AVI", "MKV", "MOV", "WEBM" -> "#F5F3FF" // Video purple
            "MP3", "WAV", "M4A", "AAC", "OGG" -> "#FEF0C7" // Audio orange
            "JPG", "JPEG", "PNG", "GIF", "WEBP" -> "#FFF2F2" // Red-pink for image doc
            else -> "#F2F4F7"
        }
        val badgeTextColor = when (ext) {
            "PDF" -> "#F04438"
            "DOC", "DOCX" -> "#175CD3"
            "XLS", "XLSX" -> "#027A48"
            "MP4", "AVI", "MKV", "MOV", "WEBM" -> "#7C3AED"
            "MP3", "WAV", "M4A", "AAC", "OGG" -> "#D97706"
            "JPG", "JPEG", "PNG", "GIF", "WEBP" -> "#DF1C41"
            else -> "#667085"
        }

        val badge = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 40), dp(context, 40))
            setBackgroundResource(R.drawable.bg_home_new_action_circle)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor(badgeBgColor))
        }
        val badgeLabel = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            gravity = android.view.Gravity.CENTER
            text = ext
            setTextColor(Color.parseColor(badgeTextColor))
            textSize = 10f
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_semibold)
            includeFontPadding = false
        }
        badge.addView(badgeLabel)

        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(context, 10) }
        }
        val fileNameView = TextView(context).apply {
            text = attachment.fileName ?: "Document"
            setTextColor(primaryText)
            textSize = 13f
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_semibold)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            includeFontPadding = false
        }
        val metaView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 2) }
            text = formatDocMeta(attachment.fileName, mime, attachment.fileSize)
            setTextColor(secondaryText)
            textSize = 11f
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_regular)
            includeFontPadding = false
        }
        infoCol.addView(fileNameView)
        infoCol.addView(metaView)

        topRow.addView(badge)
        topRow.addView(infoCol)

        container.addView(topRow)

        // Divider above the action row
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 1),
            ).apply {
                topMargin = dp(context, 12)
                marginStart = dp(context, -12)
                marginEnd = dp(context, -12)
            }
            setBackgroundResource(dividerBg)
        }
        container.addView(divider)

        // Action row: Open | Save as
        val openClick = View.OnClickListener {
            onAttachmentClick(url, mime, attachment.storageId, attachment.fileName)
        }
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val openBtn = TextView(context).apply {
            text = "Open"
            gravity = android.view.Gravity.CENTER
            setTextColor(actionText)
            textSize = 13f
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_semibold)
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 36), 1f)
            isClickable = true
            isFocusable = true
            background = androidx.core.content.ContextCompat.getDrawable(
                context, android.R.color.transparent
            )
            setOnClickListener(openClick)
        }
        val vDivider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 1), dp(context, 24)).apply {
                topMargin = dp(context, 6)
            }
            setBackgroundResource(dividerBg)
        }
        val saveBtn = TextView(context).apply {
            text = "Save as…"
            gravity = android.view.Gravity.CENTER
            setTextColor(actionText)
            textSize = 13f
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_semibold)
            layoutParams = LinearLayout.LayoutParams(0, dp(context, 36), 1f)
            isClickable = true
            isFocusable = true
            // "Save as…" routes to the same open intent — the OS doc viewer
            // surfaces its own share/save actions. Keeping it on the same
            // callback avoids needing a new permission flow for this round.
            setOnClickListener(openClick)
        }
        actionsRow.addView(openBtn)
        actionsRow.addView(vDivider)
        actionsRow.addView(saveBtn)
        container.addView(actionsRow)

        container.setOnClickListener(openClick)
        return container
    }

    /** Returns "PDF", "DOC", "DOCX", "XLSX", "ZIP", etc. for the badge label. */
    private fun inferDocBadgeLabel(fileName: String?, mime: String): String {
        val nameExt = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (nameExt.isNotEmpty() && nameExt.length <= 5) return nameExt.uppercase()
        if (mime.contains("pdf")) return "PDF"
        if (mime.contains("word") || mime.contains("msword")) return "DOC"
        if (mime.contains("excel") || mime.contains("spreadsheet")) return "XLSX"
        if (mime.contains("zip") || mime.contains("compressed")) return "ZIP"
        return "FILE"
    }

    /** Returns "PDF · 58.5 KB" — design's meta line format. */
    private fun formatDocMeta(fileName: String?, mime: String, size: Long?): String {
        val badge = inferDocBadgeLabel(fileName, mime)
        val sizeText = if (size != null && size > 0) humanReadableSize(size) else null
        return if (sizeText != null) "$badge · $sizeText" else badge
    }

    private fun startWavePulse(storageId: String, bars: List<View>, baseHeights: IntArray) {
        audioWaveAnimators[storageId]?.cancel()
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
        }
        animator.addUpdateListener { anim ->
            val t = anim.animatedValue as Float
            bars.forEachIndexed { index, bar ->
                val phase = (index.toFloat() / bars.size + t) % 1f
                val pulse = 0.65f + 0.35f * kotlin.math.abs(kotlin.math.sin(phase * Math.PI * 2).toFloat())
                bar.scaleY = pulse
            }
        }
        animator.start()
        audioWaveAnimators[storageId] = animator

        if (bars.isNotEmpty()) {
            bars[0].addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    animator.cancel()
                }
            })
        }
    }

    private fun humanReadableSize(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return "Voice message"
        val kb = bytes / 1024.0
        return if (kb < 1024) String.format(java.util.Locale.US, "%.0f KB", kb)
        else String.format(java.util.Locale.US, "%.1f MB", kb / 1024)
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    class DateViewHolder(val binding: ItemChatDateSeparatorBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.DateSeparator) {
            binding.tvDateLabel.text = item.date
        }
    }

    private fun applyEmojiOnlyStyle(
        body: TextView,
        bubbleFrame: FrameLayout,
        timeView: TextView,
        tickIcon: View?,
        text: String,
        isMine: Boolean
    ) {
        val emojiCount = getEmojiCountIfOnlyEmojis(text)
        if (emojiCount in 1..3) {
            bubbleFrame.background = null
            bubbleFrame.setPadding(0, 0, 0, 0)
            timeView.setTextColor(android.graphics.Color.parseColor("#8E8E93"))
            if (tickIcon is ImageView) {
                tickIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#8E8E93"))
            }

            when (emojiCount) {
                1 -> {
                    body.textSize = 48f
                    val pulse = android.view.animation.ScaleAnimation(
                        0.9f, 1.1f,
                        0.9f, 1.1f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
                    ).apply {
                        duration = 700
                        repeatMode = android.view.animation.Animation.REVERSE
                        repeatCount = android.view.animation.Animation.INFINITE
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    }
                    body.startAnimation(pulse)
                }
                2 -> {
                    body.textSize = 36f
                }
                3 -> {
                    body.textSize = 28f
                }
            }
        }
    }

    private fun getEmojiCountIfOnlyEmojis(text: String): Int {
        if (text.isBlank()) return 0
        var emojiCount = 0
        var i = 0
        val len = text.length
        while (i < len) {
            val codePoint = text.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            if (isEmoji(codePoint)) {
                emojiCount++
            } else if (!isWhitespaceOrEmojiModifier(codePoint)) {
                return 0
            }
            i += charCount
        }
        return emojiCount
    }

    private fun isEmoji(codePoint: Int): Boolean {
        return (codePoint in 0x1F600..0x1F64F) || // Emoticons
               (codePoint in 0x1F300..0x1F5FF) || // Misc Symbols and Pictographs
               (codePoint in 0x1F680..0x1F6FF) || // Transport and Map
               (codePoint in 0x1F1E6..0x1F1FF) || // Regional Flags
               (codePoint in 0x2600..0x26FF) ||     // Misc Symbols
               (codePoint in 0x2700..0x27BF) ||     // Dingbats
               (codePoint in 0xFE00..0xFE0F) ||     // Variation Selectors
               (codePoint in 0x1F900..0x1F9FF) || // Supplemental Symbols and Pictographs
               (codePoint in 0x1FA70..0x1FAFF)    // Symbols and Pictographs Extended-A
    }

    private fun isWhitespaceOrEmojiModifier(codePoint: Int): Boolean {
        return Character.isWhitespace(codePoint) ||
               codePoint == 0x200D || // Zero Width Joiner
               (codePoint in 0x1F3FB..0x1F3FF) // Fitzpatrick skin tone modifiers
    }

    private fun formatTime(timestamp: Double?): String {
        if (timestamp == null) return ""
        val timeMillis = if (timestamp < 10000000000.0) (timestamp * 1000).toLong() else timestamp.toLong()
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timeMillis))
    }
}

class ChatItemDiffCallback : DiffUtil.ItemCallback<ChatItem>() {
    override fun areItemsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean {
        if (oldItem is ChatItem.Message && newItem is ChatItem.Message) {
            return oldItem.data.id == newItem.data.id
        }
        if (oldItem is ChatItem.DateSeparator && newItem is ChatItem.DateSeparator) {
            return oldItem.date == newItem.date
        }
        return false
    }

    override fun areContentsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean {
        return oldItem == newItem
    }
}
