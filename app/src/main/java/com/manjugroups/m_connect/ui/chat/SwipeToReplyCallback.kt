package com.manjugroups.m_connect.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import kotlin.math.abs
import kotlin.math.min

class SwipeToReplyCallback(
    context: Context,
    private val onSwipe: (Int) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

    private val density = context.resources.displayMetrics.density
    private val replyIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_chat_reply)
    private val iconSize = (24 * density).toInt()
    private val iconMargin = (24 * density).toInt()
    private val activationThreshold = 56f * density
    private val maxTravel = 96f * density
    private val rubberBandResistance = 0.55f

    private var triggeredHaptic = false
    private var lastAbsDx = 0f
    private var committedSwipe = false
    private var lastSwipedPosition = RecyclerView.NO_POSITION

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

    override fun getSwipeDirs(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int = when (viewHolder.itemViewType) {
        ChatMessageAdapter.TYPE_SENT -> ItemTouchHelper.LEFT
        ChatMessageAdapter.TYPE_RECEIVED -> ItemTouchHelper.RIGHT
        else -> 0
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 1f
    override fun getSwipeEscapeVelocity(defaultValue: Float): Float = Float.MAX_VALUE
    override fun getSwipeVelocityThreshold(defaultValue: Float): Float = Float.MAX_VALUE

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
        val absDx = abs(dX)
        val direction = if (dX >= 0) 1f else -1f
        val rubberBanded = min(absDx, activationThreshold) +
            (absDx - activationThreshold).coerceAtLeast(0f) * rubberBandResistance
        val clampedDx = min(rubberBanded, maxTravel) * direction

        if (isCurrentlyActive) {
            if (absDx >= activationThreshold && !triggeredHaptic) {
                triggeredHaptic = true
                committedSwipe = true
                itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
            lastAbsDx = absDx
            lastSwipedPosition = viewHolder.absoluteAdapterPosition
        }

        replyIcon?.let { icon ->
            val top = itemView.top + (itemView.height - iconSize) / 2
            val bottom = top + iconSize
            val left: Int
            val right: Int
            if (dX > 0) {
                left = itemView.left + iconMargin
                right = left + iconSize
            } else {
                right = itemView.right - iconMargin
                left = right - iconSize
            }
            icon.setBounds(left, top, right, bottom)
            val progress = (absDx / activationThreshold).coerceIn(0f, 1f)
            icon.alpha = (progress * 255).toInt()
            val scale = 0.7f + 0.3f * progress
            val saveCount = c.save()
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            c.scale(scale, scale, cx, cy)
            icon.draw(c)
            c.restoreToCount(saveCount)
        }

        super.onChildDraw(c, recyclerView, viewHolder, clampedDx, dY, actionState, isCurrentlyActive)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        val position = lastSwipedPosition
        val shouldFire = committedSwipe || lastAbsDx >= activationThreshold
        triggeredHaptic = false
        committedSwipe = false
        lastAbsDx = 0f
        lastSwipedPosition = RecyclerView.NO_POSITION
        if (shouldFire && position != RecyclerView.NO_POSITION) {
            onSwipe(position)
        }
    }
}
