package com.manjugroups.m_connect.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.manjugroups.m_connect.databinding.ItemChatBinding
import com.manjugroups.m_connect.R

data class ChatListItem(
    val id: String,
    val kind: Kind,
    val title: String,
    val subtitle: String,
    val timestamp: Long?,
    val unreadCount: Int,
    val avatarText: String,
    val avatarSeed: Int,
    val isMuted: Boolean,
    val isOnline: Boolean = false,
    val isFavourite: Boolean = false,
    val previewIconRes: Int? = null,
    val photoUrl: String? = null,
    // Group-dm conversations render and filter as groups even though their
    // kind stays DIRECT (they open as conversations).
    val isGroup: Boolean = false,
) {
    enum class Kind { DIRECT, CHANNEL }
}

class ChatListAdapter(
    private val onItemClick: (ChatListItem) -> Unit,
    private val onItemLongClick: (View, ChatListItem) -> Unit,
    private val avatarBinder: (View, TextView, android.widget.ImageView, String, Int, String?) -> Unit,
    private val timestampBinder: (TextView, Long?) -> Unit,
    private val isSelectedProvider: (ChatListItem) -> Boolean = { false },
    private val onAvatarClick: ((ChatListItem) -> Unit)? = null
) : ListAdapter<ChatListItem, ChatListAdapter.ViewHolder>(ChatListItemDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemChatBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class ViewHolder(val binding: ItemChatBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatListItem) {
            binding.tvChatName.text = item.title
            binding.tvChatLastMsg.text = item.subtitle
            
            if (item.previewIconRes != null) {
                binding.ivPreviewIcon.visibility = View.VISIBLE
                binding.ivPreviewIcon.setImageResource(item.previewIconRes)
                binding.ivPreviewIcon.setColorFilter(
                    androidx.core.content.ContextCompat.getColor(binding.root.context, R.color.chat_text_secondary)
                )
                if (item.previewIconRes == R.drawable.ic_chat_msg_deleted) {
                    binding.tvChatLastMsg.setTypeface(binding.tvChatLastMsg.typeface, android.graphics.Typeface.ITALIC)
                } else {
                    binding.tvChatLastMsg.setTypeface(binding.tvChatLastMsg.typeface, android.graphics.Typeface.NORMAL)
                }
            } else {
                binding.ivPreviewIcon.visibility = View.GONE
                binding.tvChatLastMsg.setTypeface(binding.tvChatLastMsg.typeface, android.graphics.Typeface.NORMAL)
            }

            // Find the inner FrameLayout that has the background in item_chat.xml
            val avatarContainer = binding.avatarContainer as ViewGroup
            if (avatarContainer.childCount > 0) {
                val avatarFrame = avatarContainer.getChildAt(0)
                avatarBinder(
                    avatarFrame,
                    binding.tvChatAvatar,
                    binding.ivChatAvatarImage,
                    item.avatarText,
                    item.avatarSeed,
                    item.photoUrl
                )
            }
            // Photo overlay — when the server gave us a participant
            // photo URL, load it on top of the initial circle. Coil
            // crossfades it in so the initial briefly shows then fades
            // into the actual picture. When there's no photo (channels,
            // staff with no upload), the ImageView stays gone and the
            // initial does its existing job.
            val photo = item.photoUrl
            if (!photo.isNullOrBlank()) {
                binding.ivChatAvatarPhoto.visibility = View.VISIBLE
                binding.ivChatAvatarPhoto.load(photo) { crossfade(true) }
            } else {
                binding.ivChatAvatarPhoto.visibility = View.GONE
                binding.ivChatAvatarPhoto.setImageDrawable(null)
            }
            timestampBinder(binding.tvChatTime, item.timestamp)

            if (item.unreadCount > 0) {
                binding.tvUnread.text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
                binding.unreadContainer.visibility = View.VISIBLE
            } else {
                binding.unreadContainer.visibility = View.GONE
            }

            binding.onlineDot.visibility = if (item.isOnline) View.VISIBLE else View.GONE

            binding.root.setBackgroundColor(
                if (isSelectedProvider(item)) android.graphics.Color.parseColor("#1A0B61CA")
                else android.graphics.Color.TRANSPARENT
            )

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick(it, item)
                true
            }
            if (onAvatarClick != null) {
                binding.avatarContainer.setOnClickListener { onAvatarClick.invoke(item) }
            } else {
                binding.avatarContainer.setOnClickListener(null)
            }
        }
    }
}

class ChatListItemDiffCallback : DiffUtil.ItemCallback<ChatListItem>() {
    override fun areItemsTheSame(oldItem: ChatListItem, newItem: ChatListItem): Boolean {
        return oldItem.id == newItem.id && oldItem.kind == newItem.kind
    }

    override fun areContentsTheSame(oldItem: ChatListItem, newItem: ChatListItem): Boolean {
        return oldItem == newItem
    }
}
