package com.manjugroups.m_connect.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private val onMessageLongClick: (MessageData) -> Unit,
    private val onAttachmentClick: (String) -> Unit
) : ListAdapter<ChatItem, RecyclerView.ViewHolder>(ChatItemDiffCallback()) {

    companion object {
        private const val TYPE_SENT = 0
        private const val TYPE_RECEIVED = 1
        private const val TYPE_DATE = 2
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
            binding.tvMessageBody.text = item.data.body
            binding.tvMessageBody.isVisible = !item.data.body.isNullOrBlank()
            binding.tvMessageTime.text = formatTime(item.data.creationTime)
            
            val parentId = item.data.parentMessageId
            if (!parentId.isNullOrBlank()) {
                val parent = findMessageInList(parentId)
                if (parent != null) {
                    binding.replyContainer.visibility = View.VISIBLE
                    binding.tvReplyName.text = if (parent.senderId == item.data.senderId) "You" else parent.senderName
                    binding.tvReplyBody.text = parent.body ?: "Attachment"
                } else {
                    binding.replyContainer.visibility = View.GONE
                }
            } else {
                binding.replyContainer.visibility = View.GONE
            }

            binding.root.setOnLongClickListener {
                onMessageLongClick(item.data)
                true
            }
            
            binding.attachmentsContainer.removeAllViews()
            item.data.attachments?.forEach { attachment ->
                binding.attachmentsContainer.addView(createAttachmentView(binding.attachmentsContainer, attachment))
            }

            bindReactions(binding.reactionsLayout, item.data.reactions)
        }
    }

    inner class ReceivedViewHolder(val binding: ItemChatMessageReceivedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.Message) {
            binding.tvMessageBody.text = item.data.body
            binding.tvMessageBody.isVisible = !item.data.body.isNullOrBlank()
            binding.tvMessageTime.text = formatTime(item.data.creationTime)
            binding.tvSenderName.text = item.data.senderName
            binding.tvSenderName.visibility = if (item.showName) View.VISIBLE else View.GONE
            binding.avatarContainer.visibility = if (item.showAvatar) View.VISIBLE else View.GONE
            
            if (item.showAvatar) {
                val initials = item.data.senderName?.split(" ")?.filter { it.isNotBlank() }?.take(2)?.joinToString("") { it.first().uppercase() } ?: "U"
                binding.tvSenderAvatar.text = initials
            }

            val parentId = item.data.parentMessageId
            if (!parentId.isNullOrBlank()) {
                val parent = findMessageInList(parentId)
                if (parent != null) {
                    binding.replyContainer.visibility = View.VISIBLE
                    binding.tvReplyName.text = parent.senderName
                    binding.tvReplyBody.text = parent.body ?: "Attachment"
                } else {
                    binding.replyContainer.visibility = View.GONE
                }
            } else {
                binding.replyContainer.visibility = View.GONE
            }

            binding.root.setOnLongClickListener {
                onMessageLongClick(item.data)
                true
            }

            binding.attachmentsContainer.removeAllViews()
            item.data.attachments?.forEach { attachment ->
                binding.attachmentsContainer.addView(createAttachmentView(binding.attachmentsContainer, attachment))
            }

            bindReactions(binding.reactionsLayout, item.data.reactions)
        }
    }

    private fun findMessageInList(id: String): MessageData? {
        return currentList.filterIsInstance<ChatItem.Message>().find { it.data.id == id }?.data
    }

    private fun bindReactions(layout: LinearLayout, reactions: List<ReactionData>?) {
        layout.removeAllViews()
        if (reactions.isNullOrEmpty()) {
            layout.visibility = View.GONE
            return
        }

        layout.visibility = View.VISIBLE
        reactions.forEach { reaction ->
            val tv = TextView(layout.context).apply {
                text = "${reaction.emoji} ${reaction.count ?: ""}"
                textSize = 10f
                setPadding(4, 0, 4, 0)
            }
            layout.addView(tv)
        }
    }

    private fun createAttachmentView(parent: ViewGroup, attachment: MessageAttachmentData): View {
        val context = parent.context
        val url = attachment.url ?: ""
        val mime = attachment.fileType.orEmpty().lowercase()
        
        return if (mime.startsWith("image/")) {
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    (200 * context.resources.displayMetrics.density).toInt(),
                    (150 * context.resources.displayMetrics.density).toInt()
                ).apply {
                    bottomMargin = (4 * context.resources.displayMetrics.density).toInt()
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                load(url) {
                    crossfade(true)
                    placeholder(R.drawable.bg_chat_media_placeholder)
                }
                setOnClickListener { onAttachmentClick(url) }
            }
        } else {
            // Document style
            TextView(context).apply {
                text = attachment.fileName ?: "File"
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_chat_file, 0, 0, 0)
                compoundDrawablePadding = 8
                setPadding(12, 8, 12, 8)
                setBackgroundResource(R.drawable.bg_chat_action_card)
                setOnClickListener { onAttachmentClick(url) }
            }
        }
    }

    class DateViewHolder(val binding: ItemChatDateSeparatorBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.DateSeparator) {
            binding.tvDateLabel.text = item.date
        }
    }

    private fun formatTime(timestamp: Double?): String {
        if (timestamp == null) return ""
        val timeMillis = if (timestamp < 10000000000.0) (timestamp * 1000).toLong() else timestamp.toLong()
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMillis))
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
