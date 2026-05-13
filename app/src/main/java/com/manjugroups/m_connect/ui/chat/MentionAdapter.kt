package com.manjugroups.m_connect.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.databinding.ItemMentionPersonBinding

data class MentionPerson(val id: String, val name: String, val username: String)

class MentionAdapter(private val onPersonClick: (MentionPerson) -> Unit) : RecyclerView.Adapter<MentionAdapter.ViewHolder>() {

    private var items = listOf<MentionPerson>()

    fun submitList(newItems: List<MentionPerson>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(ItemMentionPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val person = items[position]
        holder.binding.tvName.text = person.name
        holder.binding.tvUsername.text = "@${person.username}"
        val initials = person.name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
        holder.binding.tvAvatarInitial.text = initials
        holder.itemView.setOnClickListener { onPersonClick(person) }
    }

    override fun getItemCount() = items.size

    class ViewHolder(val binding: ItemMentionPersonBinding) : RecyclerView.ViewHolder(binding.root)
}
