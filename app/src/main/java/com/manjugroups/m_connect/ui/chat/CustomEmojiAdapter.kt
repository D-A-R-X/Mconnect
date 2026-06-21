package com.manjugroups.m_connect.ui.chat

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R

class CustomEmojiAdapter(
    private var emojis: List<String>,
    private val onEmojiSelected: (String) -> Unit
) : RecyclerView.Adapter<CustomEmojiAdapter.ViewHolder>() {

    fun updateList(newList: List<String>) {
        emojis = newList
        notifyDataSetChanged()
    }

    private fun dp(context: android.content.Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        val sizePx = dp(context, 44) // 44dp height/width for perfect sticker circles
        val view = TextView(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                sizePx
            ).apply {
                val margin = dp(context, 4)
                setMargins(margin, margin, margin, margin)
            }
            gravity = android.view.Gravity.CENTER
            textSize = 25f // Perfectly balanced emoji text size
            setTextColor(android.graphics.Color.BLACK)
            alpha = 1f
            setBackgroundResource(R.drawable.bg_emoji_item)
            elevation = dp(context, 2).toFloat()
            clipToOutline = false
            isClickable = true
            isFocusable = true
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
