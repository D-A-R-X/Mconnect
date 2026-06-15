package com.manjugroups.m_connect.ui.chat

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CustomEmojiAdapter(
    private var emojis: List<String>,
    private val onEmojiSelected: (String) -> Unit
) : RecyclerView.Adapter<CustomEmojiAdapter.ViewHolder>() {

    fun updateList(newList: List<String>) {
        emojis = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = android.view.Gravity.CENTER
            textSize = 30f // Make emojis nice and large!
            setPadding(0, 10, 0, 10)
            isClickable = true
            isFocusable = true
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val emoji = emojis[position]
        (holder.itemView as TextView).text = emoji
        holder.itemView.setOnClickListener {
            // Tactile scale pop animation!
            holder.itemView.animate()
                .scaleX(1.3f)
                .scaleY(1.3f)
                .setDuration(90)
                .withEndAction {
                    holder.itemView.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(90)
                        .withEndAction {
                            onEmojiSelected(emoji)
                        }
                        .start()
                }
                .start()
        }
    }

    override fun getItemCount(): Int = emojis.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
