package com.manjugroups.m_connect.ui.lens

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import com.manjugroups.m_connect.R

/**
 * Camera overlay: dims everything outside an oval face guide and draws one
 * ring segment per pose around it. The preview is mirrored, so the person's
 * left is the screen's left: left poses sit on the left of the ring, right
 * poses on the right, straight on top.
 */
class LensFaceGuideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Segment { PENDING, ACTIVE, HOLDING, DONE, SKIPPED }

    private val segments = LensPose.entries.associateWith { Segment.PENDING }.toMutableMap()
    private var faceOk = false

    private val density = resources.displayMetrics.density
    private val oval = RectF()
    private val ring = RectF()
    private val cutout = Path()

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val successColor = attrColor(R.attr.colorSuccess, Color.parseColor("#12B76A"))
    private val accentColor = attrColor(R.attr.colorButtonPrimary, Color.parseColor("#0B61CA"))
    private var pulse = 0f
    private val pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** Oval in view coordinates (for placing the prompt and arrows). */
    val guideRect: RectF get() = RectF(oval)

    fun setSegment(pose: LensPose, state: Segment) {
        if (segments[pose] == state) return
        segments[pose] = state
        invalidate()
    }

    fun setFaceOk(ok: Boolean) {
        if (faceOk == ok) return
        faceOk = ok
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulseAnim.start()
    }

    override fun onDetachedFromWindow() {
        pulseAnim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val ow = w * 0.66f
        val oh = ow * 1.32f
        val cx = w / 2f
        val cy = h * 0.44f
        oval.set(cx - ow / 2, cy - oh / 2, cx + ow / 2, cy + oh / 2)
        val pad = 14f * density
        ring.set(oval.left - pad, oval.top - pad, oval.right + pad, oval.bottom + pad)
        cutout.reset()
        cutout.fillType = Path.FillType.EVEN_ODD
        cutout.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        cutout.addOval(oval, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(cutout, dimPaint)
        outlinePaint.color = if (faceOk) Color.argb(230, 255, 255, 255) else Color.argb(110, 255, 255, 255)
        canvas.drawOval(oval, outlinePaint)
        for ((pose, state) in segments) {
            val (start, sweep) = arcFor(pose)
            segmentPaint.color = when (state) {
                Segment.DONE -> successColor
                Segment.HOLDING -> blend(accentColor, successColor, pulse)
                Segment.ACTIVE -> withAlpha(Color.WHITE, (140 + 115 * pulse).toInt())
                Segment.SKIPPED -> Color.argb(45, 255, 255, 255)
                Segment.PENDING -> Color.argb(80, 255, 255, 255)
            }
            canvas.drawArc(ring, start, sweep, false, segmentPaint)
        }
    }

    /** Canvas angles: 0 = 3 o'clock, clockwise. Screen-left = person's left. */
    private fun arcFor(pose: LensPose): Pair<Float, Float> = when (pose) {
        LensPose.STRAIGHT -> 245f to 50f
        LensPose.SLIGHT_LEFT -> 185f to 50f
        LensPose.MORE_LEFT -> 125f to 50f
        LensPose.SLIGHT_RIGHT -> 305f to 50f
        LensPose.MORE_RIGHT -> 5f to 50f
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    private fun attrColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) tv.data else fallback
    }
}
