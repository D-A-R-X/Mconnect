package com.manjugroups.m_connect.ui.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The floating bottom nav pill, styled as frosted glass.
 *
 * An earlier version did a genuine backdrop blur: every other frame it redrew
 * the page beneath the pill into a downscaled bitmap and box-blurred it.
 * Android has no cheap way to do that — `Window.setBackgroundBlurRadius` only
 * applies to dialog windows and `RenderEffect` blurs a view's own content, not
 * what is behind it — so the only route is a full *software* re-render of the
 * page view tree on the UI thread, once per captured frame. Over a page of
 * RecyclerViews and decoded images that alone cost more than the frame budget,
 * and it competed directly with the swipe animation and the thumb animator.
 *
 * So the blur is gone and the glass is painted: a vertical sheen gradient over
 * a translucent white body, plus a bright top edge. One gradient rect per
 * frame instead of a page render, and it holds up next to the real thing at
 * this size because the pill is small and the page behind it is near-flat.
 *
 * The view also owns the selection pill and the press-hold-and-scrub gesture,
 * because both need to read as part of the glass rather than as widgets
 * floating on top of it.
 */
class GlassNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private companion object {
        /** Glass body, top → bottom. Slightly translucent so the page tints it. */
        const val GLASS_TOP = 0xFFFFFFFF.toInt()
        const val GLASS_BOTTOM = 0xEDFFFFFF.toInt()

        /** Top edge highlight that sells the "pane of glass" read. */
        const val BORDER = 0x66FFFFFF

        const val THUMB = 0x141BCA0B

        /**
         * Vertical inset of the selection pill from the bar, in dp. Drives the
         * pill's height, and with it the capsule radius.
         */
        const val THUMB_INSET_DP = 16f

        /** Hold this long without moving and the bar arms for scrubbing. */
        const val HOLD_ARM_MS = 200L
    }

    // ── Painting ────────────────────────────────────────────────────────────

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = BORDER
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = THUMB }
    private val clipPath = Path()
    private val thumbRect = RectF()

    // ── Selection thumb ─────────────────────────────────────────────────────

    /** Animated by ObjectAnimator via the synthesized setters — keep public. */
    var thumbCenterX: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var thumbWidth: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var thumbScale: Float = 1f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    // ── Scrub gesture ───────────────────────────────────────────────────────

    /**
     * Press-hold-and-scrub across the bar. [onScrubMove] fires continuously so
     * the caller can preview the tab under the finger without committing;
     * nothing is actually navigated until [onScrubEnd].
     */
    interface ScrubListener {
        fun onScrubStart(x: Float)
        fun onScrubMove(x: Float)
        fun onScrubEnd(x: Float)
        fun onScrubCancel()
    }

    var scrubListener: ScrubListener? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var holdArmed = false
    private var scrubbing = false
    private val armHold = Runnable {
        holdArmed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    init {
        setWillNotDraw(false)
        // The pill draws its own fill, so any XML background would sit on top
        // of the glass. Elevation still needs an outline to cast a shadow.
        background = null
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        clipToOutline = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h <= 0) return
        // Built once per size rather than per frame.
        glassPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            GLASS_TOP, GLASS_BOTTOM,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(armHold)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = h / 2f

        clipPath.reset()
        clipPath.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)

        val saved = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(0f, 0f, w, h, glassPaint)

        if (thumbWidth > 0f) {
            val tw = thumbWidth * thumbScale
            // Inset from the bar so the pill sits inside the glass with a
            // visible margin, and — more importantly — so it stays clearly
            // wider than it is tall. At near-square proportions a capsule
            // radius just reads as a rounded box.
            val th = (h - dp(THUMB_INSET_DP)) * thumbScale
            thumbRect.set(
                thumbCenterX - tw / 2f,
                (h - th) / 2f,
                thumbCenterX + tw / 2f,
                (h + th) / 2f,
            )
            val tr = th / 2f
            canvas.drawRoundRect(thumbRect, tr, tr, thumbPaint)
        }

        canvas.restoreToCount(saved)

        borderPaint.strokeWidth = dp(1f)
        val inset = borderPaint.strokeWidth / 2f
        clipPath.reset()
        clipPath.addRoundRect(
            inset, inset, w - inset, h - inset,
            radius - inset, radius - inset, Path.Direction.CW,
        )
        canvas.drawPath(clipPath, borderPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    // ── Touch: let taps reach the tabs, steal only real scrubs ──────────────

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                holdArmed = false
                scrubbing = false
                removeCallbacks(armHold)
                postDelayed(armHold, HOLD_ARM_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // Once held, any drift starts scrubbing. Without a hold it
                // takes a deliberate, mostly-horizontal drag — otherwise a
                // sloppy tap would slide the selection out from under it.
                val moved = if (holdArmed) abs(dx) > touchSlop / 2 else abs(dx) > touchSlop
                if (moved && abs(dx) > abs(dy)) {
                    removeCallbacks(armHold)
                    scrubbing = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    scrubListener?.onScrubStart(ev.x)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(armHold)
                holdArmed = false
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            // Reached only when the touch landed on the bar's padding rather
            // than a tab; still worth tracking so a scrub can start there.
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                removeCallbacks(armHold)
                postDelayed(armHold, HOLD_ARM_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing && abs(ev.x - downX) > touchSlop) {
                    scrubbing = true
                    scrubListener?.onScrubStart(ev.x)
                }
                if (scrubbing) scrubListener?.onScrubMove(ev.x)
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(armHold)
                if (scrubbing) scrubListener?.onScrubEnd(ev.x)
                scrubbing = false
                holdArmed = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(armHold)
                if (scrubbing) scrubListener?.onScrubCancel()
                scrubbing = false
                holdArmed = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /** Index of the tab under [x], clamped to the bar's children. */
    fun tabIndexAt(x: Float): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (x >= child.left && x <= child.right) return i
            val center = (child.left + child.right) / 2f
            val distance = abs(x - center)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return min(max(best, 0), max(childCount - 1, 0))
    }
}
