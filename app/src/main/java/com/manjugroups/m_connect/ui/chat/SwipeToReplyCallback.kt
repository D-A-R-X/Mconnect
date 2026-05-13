package com.manjugroups.m_connect.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R

class SwipeToReplyCallback(
    context: Context,
    private val onSwipe: (Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

    private val replyIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_chat_reply)
    private val iconMargin = (16 * context.resources.displayMetrics.density).toInt()
    private var swipedPosition = -1

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // We don't want to actually remove the item
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.5f

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val itemView = viewHolder.itemView
        
        // Limit swipe distance
        val maxSwipe = (80 * recyclerView.context.resources.displayMetrics.density)
        val clampedDX = dX.coerceIn(0f, maxSwipe)

        if (isCurrentlyActive && clampedDX >= maxSwipe * 0.8f && swipedPosition != viewHolder.adapterPosition) {
            swipedPosition = viewHolder.adapterPosition
            onSwipe(swipedPosition)
        }

        if (!isCurrentlyActive) {
            swipedPosition = -1
        }

        // Draw reply icon
        replyIcon?.let {
            val iconSize = it.intrinsicHeight
            val halfIcon = iconSize / 2
            val top = itemView.top + (itemView.height - iconSize) / 2
            val bottom = top + iconSize
            
            val left = itemView.left + iconMargin
            val right = left + iconSize
            
            it.setBounds(left, top, right, bottom)
            it.alpha = (clampedDX / maxSwipe * 255).toInt().coerceIn(0, 255)
            it.draw(c)
        }

        super.onChildDraw(c, recyclerView, viewHolder, clampedDX, dY, actionState, isCurrentlyActive)
    }
}
