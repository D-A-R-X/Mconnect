package com.manjugroups.m_connect.ui.chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
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
            binding.tvBody.text = item.data.body
            binding.tvBody.visibility = if (item.data.body.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvTime.text = formatTime(item.data.creationTime)
            binding.root.setOnLongClickListener {
                onMessageLongClick(item.data)
                true
            }
            
            binding.attachmentsContainer.removeAllViews()
            val attachments = item.data.attachments ?: emptyList()
            if (attachments.isNotEmpty()) {
                binding.attachmentsContainer.visibility = View.VISIBLE
                addMessageAttachments(binding.attachmentsContainer, attachments, true)
            } else {
                binding.attachmentsContainer.visibility = View.GONE
            }
        }
    }

    inner class ReceivedViewHolder(val binding: ItemChatMessageReceivedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.Message) {
            binding.tvBody.text = item.data.body
            binding.tvBody.visibility = if (item.data.body.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvTime.text = formatTime(item.data.creationTime)
            binding.tvSenderName.text = item.data.senderName
            binding.tvSenderName.visibility = if (item.showName) View.VISIBLE else View.GONE
            binding.avatarContainer.visibility = if (item.showAvatar) View.VISIBLE else View.GONE
            
            if (item.showAvatar) {
                val initials = item.data.senderName?.split(" ")?.filter { it.isNotBlank() }?.take(2)?.joinToString("") { it.first().uppercase() } ?: "U"
                binding.tvAvatarInitial.text = initials
            }

            binding.root.setOnLongClickListener {
                onMessageLongClick(item.data)
                true
            }

            binding.attachmentsContainer.removeAllViews()
            val attachments = item.data.attachments ?: emptyList()
            if (attachments.isNotEmpty()) {
                binding.attachmentsContainer.visibility = View.VISIBLE
                addMessageAttachments(binding.attachmentsContainer, attachments, false)
            } else {
                binding.attachmentsContainer.visibility = View.GONE
            }
        }
    }

    inner class DateViewHolder(val binding: ItemChatDateSeparatorBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.DateSeparator) {
            binding.tvDate.text = item.date
        }
    }

    private fun addMessageAttachments(
        parent: LinearLayout,
        attachments: List<MessageAttachmentData>,
        isMine: Boolean
    ) {
        val context = parent.context
        attachments.forEachIndexed { index, attachment ->
            val mime = attachment.fileType.orEmpty().lowercase(Locale.getDefault())
            val view = when {
                mime.startsWith("image/") -> createImageAttachmentView(context, attachment)
                mime.startsWith("video/") -> createVideoAttachmentView(context, attachment)
                else -> createMessageAttachmentView(context, attachment, isMine)
            }
            parent.addView(view.apply {
                val currentParams = layoutParams as? LinearLayout.LayoutParams
                val params = LinearLayout.LayoutParams(
                    currentParams?.width ?: LinearLayout.LayoutParams.WRAP_CONTENT,
                    currentParams?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = if (index == 0) 0 else dpToPx(context, 6)
                params.bottomMargin = dpToPx(context, 4)
                layoutParams = params
            })
        }
    }

    private fun createImageAttachmentView(context: android.content.Context, attachment: MessageAttachmentData): View {
        val width = dpToPx(context, 240)
        val height = dpToPx(context, 180)
        val cornerPx = dpToPx(context, 12).toFloat()

        val container = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(width, height)
            isClickable = !attachment.url.isNullOrBlank()
            isFocusable = isClickable
            if (isClickable) setOnClickListener { onAttachmentClick(attachment.url!!) }
        }

        val image = ImageView(context).apply {
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
        
        val url = attachment.url
        if (!url.isNullOrBlank()) {
            image.load(url) {
                crossfade(true)
                placeholder(R.drawable.bg_chat_media_placeholder)
                error(R.drawable.ic_chat_file)
                transformations(coil.transform.RoundedCornersTransformation(cornerPx))
            }
            container.addView(image)
        } else {
            // If URL is missing, show a text overlay or just the placeholder
            val tv = TextView(context).apply {
                text = "Attachment: ${attachment.fileName ?: "Image"}\n(Processing...)"
                setTextColor(android.graphics.Color.GRAY)
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dpToPx(context, 16), 0, dpToPx(context, 16), 0)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            container.addView(image)
            container.addView(tv)
        }
        
        return container
    }

    private fun createVideoAttachmentView(context: android.content.Context, attachment: MessageAttachmentData): View {
        val width = dpToPx(context, 240)
        val height = dpToPx(context, 180)
        val cornerPx = dpToPx(context, 12).toFloat()

        val container = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(width, height)
            isClickable = !attachment.url.isNullOrBlank()
            isFocusable = isClickable
            if (isClickable) setOnClickListener { onAttachmentClick(attachment.url!!) }
        }

        val image = ImageView(context).apply {
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
            image.load(url, videoFrameImageLoader(context)) {
                crossfade(true)
                transformations(coil.transform.RoundedCornersTransformation(cornerPx))
            }
        }
        container.addView(image)

        val play = FrameLayout(context).apply {
            val size = dpToPx(context, 48)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            setBackgroundResource(R.drawable.bg_chat_video_play_circle)
        }
        play.addView(ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(context, 22), dpToPx(context, 22), Gravity.CENTER
            )
            setImageResource(R.drawable.ic_chat_media_play)
        })
        container.addView(play)
        return container
    }

    private var cachedVideoFrameLoader: coil.ImageLoader? = null
    private fun videoFrameImageLoader(context: android.content.Context): coil.ImageLoader {
        cachedVideoFrameLoader?.let { return it }
        val loader = coil.ImageLoader.Builder(context)
            .components { add(coil.decode.VideoFrameDecoder.Factory()) }
            .build()
        cachedVideoFrameLoader = loader
        return loader
    }

    private fun createMessageAttachmentView(
        context: android.content.Context,
        attachment: MessageAttachmentData,
        isMine: Boolean
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(context, 2), dpToPx(context, 2), dpToPx(context, 2), dpToPx(context, 2))
            isClickable = !attachment.url.isNullOrBlank()
            isFocusable = isClickable
            if (isClickable) {
                setOnClickListener { onAttachmentClick(attachment.url!!) }
            }

            addView(buildAttachmentBadge(context, attachment.fileType.orEmpty()))

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dpToPx(context, 8)
                }

                addView(TextView(context).apply {
                    text = attachment.fileName ?: "Attachment"
                    maxLines = 1
                    setTextColor(
                        if (isMine) android.graphics.Color.WHITE
                        else android.graphics.Color.BLACK
                    )
                    textSize = 13f
                    typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
                })

                addView(TextView(context).apply {
                    text = buildString {
                        append(readableAttachmentLabel(attachment.fileType.orEmpty()))
                        attachment.fileSize?.takeIf { it > 0 }?.let {
                            append(" • ")
                            append(android.text.format.Formatter.formatShortFileSize(context, it))
                        }
                    }
                    setTextColor(
                        if (isMine) android.graphics.Color.parseColor("#B3FFFFFF")
                        else android.graphics.Color.parseColor("#667085")
                    )
                    textSize = 11f
                    typeface = ResourcesCompat.getFont(context, R.font.inter_regular)
                })
            })
        }
    }

    private fun buildAttachmentBadge(context: android.content.Context, fileType: String): TextView {
        return TextView(context).apply {
            text = attachmentBadgeText(fileType)
            gravity = Gravity.CENTER
            minWidth = dpToPx(context, 34)
            minHeight = dpToPx(context, 34)
            setPadding(dpToPx(context, 6), dpToPx(context, 6), dpToPx(context, 6), dpToPx(context, 6))
            setBackgroundResource(R.drawable.bg_chat_avatar_circle)
            setTextColor(resolveColor(context, R.attr.colorAccentPrimary))
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

    private fun dpToPx(context: android.content.Context, dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    private fun resolveColor(context: android.content.Context, attr: Int): Int {
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun formatTime(timestamp: Double?): String {
        if (timestamp == null) return ""
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp.toLong()))
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
