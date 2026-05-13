package com.manjugroups.m_connect.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.ItemChatBinding

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
    val isOnline: Boolean = false
) {
    enum class Kind { DIRECT, CHANNEL }
}

class ChatListAdapter(
    private val onItemClick: (ChatListItem) -> Unit,
    private val onItemLongClick: (View, ChatListItem) -> Unit,
    private val avatarBinder: (View, TextView, String, Int) -> Unit,
    private val timestampBinder: (TextView, Long?) -> Unit
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
            
            avatarBinder(binding.avatarContainer, binding.tvChatAvatar, item.avatarText, item.avatarSeed)
            timestampBinder(binding.tvChatTime, item.timestamp)
            
            if (item.unreadCount > 0) {
                binding.tvUnread.text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
                binding.unreadContainer.visibility = View.VISIBLE
            } else {
                binding.unreadContainer.visibility = View.GONE
            }

            binding.onlineDot.visibility = if (item.isOnline) View.VISIBLE else View.GONE

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick(it, item)
                true
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
