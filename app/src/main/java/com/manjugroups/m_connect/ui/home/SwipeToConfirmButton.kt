package com.manjugroups.m_connect.ui.home

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.manjugroups.m_connect.R

/**
 * Slide-to-confirm widget. Drag the thumb left→right past the threshold to fire
 * [onConfirmed]. Snaps back if released too early. Stays locked at the right
 * edge after confirm; call [reset] to re-arm.
 */
class SwipeToConfirmButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var onConfirmed: (() -> Unit)? = null
    var label: CharSequence
        get() = labelView.text
        set(value) { labelView.text = value }

    private val thumbSizePx: Int = dp(40)
    private val thumbInsetPx: Int = dp(4)
    private val thumb: FrameLayout
    private val thumbIcon: ImageView
    private val endIcon: ImageView
    private val labelView: TextView

    private var dragStartX = 0f
    private var thumbStartX = 0f
    private var isDragging = false
    private var isLocked = false

    init {
        background = ContextCompat.getDrawable(context, R.drawable.bg_swipe_track)
        clipToPadding = false
        clipChildren = false
        setPadding(thumbInsetPx, thumbInsetPx, thumbInsetPx, thumbInsetPx)

        labelView = TextView(context).apply {
            text = "Swipe to Complete Trip"
            setTextColor(Color.parseColor("#19B900"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            try {
                typeface = androidx.core.content.res.ResourcesCompat
                    .getFont(context, R.font.inter_medium)
            } catch (_: Exception) { /* fallback to default */ }
        }
        addView(
            labelView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )

        thumb = FrameLayout(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_swipe_thumb)
            elevation = dp(2).toFloat()
        }
        thumbIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_swipe_double_chevron_figma)
        }
        thumb.addView(
            thumbIcon,
            LayoutParams(dp(14), dp(13), Gravity.CENTER)
        )
        addView(
            thumb,
            LayoutParams(thumbSizePx, thumbSizePx, Gravity.START or Gravity.CENTER_VERTICAL)
        )

        endIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_swipe_double_chevron_figma)
        }
        addView(
            endIcon,
            LayoutParams(dp(14), dp(13), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(16)
            }
        )
        thumb.bringToFront()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isLocked || !isEnabled) return super.onTouchEvent(event)

        val travel = (width - thumbSizePx - 2 * thumbInsetPx).coerceAtLeast(0)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Start drag only when finger lands on the thumb area.
                val onThumb = event.x in thumb.x..(thumb.x + thumbSizePx)
                if (!onThumb) return false
                isDragging = true
                dragStartX = event.x
                thumbStartX = thumb.translationX
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                val dx = event.x - dragStartX
                val newTx = (thumbStartX + dx).coerceIn(0f, travel.toFloat())
                thumb.translationX = newTx
                labelView.alpha = 1f - (newTx / travel.coerceAtLeast(1)).coerceIn(0f, 1f)
                endIcon.alpha = labelView.alpha
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return false
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val confirmed = thumb.translationX >= travel * CONFIRM_THRESHOLD
                if (confirmed) {
                    isLocked = true
                    thumb.animate()
                        .translationX(travel.toFloat())
                        .setDuration(120L)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction { onConfirmed?.invoke() }
                        .start()
                    labelView.animate().alpha(0f).setDuration(120L).start()
                    endIcon.animate().alpha(0f).setDuration(120L).start()
                } else {
                    thumb.animate()
                        .translationX(0f)
                        .setDuration(180L)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    labelView.animate().alpha(1f).setDuration(180L).start()
                    endIcon.animate().alpha(1f).setDuration(180L).start()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun reset(newLabel: CharSequence? = null) {
        if (newLabel != null) labelView.text = newLabel
        isLocked = false
        thumb.animate().translationX(0f).setDuration(160L).start()
        labelView.animate().alpha(1f).setDuration(160L).start()
        endIcon.animate().alpha(1f).setDuration(160L).start()
        isEnabled = true
    }

    fun lockAsBusy(message: CharSequence) {
        isLocked = true
        labelView.text = message
        labelView.alpha = 1f
        endIcon.alpha = 0f
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CONFIRM_THRESHOLD = 0.85f
    }
}
