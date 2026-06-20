package com.manjugroups.m_connect.ui.chat

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * ImageView with pinch-zoom, pan-when-zoomed, double-tap-reset, and a
 * single-tap callback the host can use to toggle preview chrome.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var onSingleTap: (() -> Unit)? = null

    private val matrixObj = Matrix()
    private val minScale = 1f
    private val maxScale = 6f
    private var scale = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = -1

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
                if (target != scale) {
                    scale = target
                    applyMatrix()
                }
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > 1.05f) {
                    scale = 1f
                    translateX = 0f
                    translateY = 0f
                } else {
                    scale = 2.5f
                }
                applyMatrix()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        post { resetToFit() }
    }

    /**
     * Re-run the fit-center matrix now. Useful when the drawable's
     * intrinsic dimensions weren't known at the moment
     * [setImageDrawable] was first called — e.g. Coil's
     * `CrossfadeDrawable` starts as a 0×0 placeholder and only
     * resolves to the real bitmap size mid-animation, so the host can
     * call this from a Coil `onSuccess` listener to re-center landscape
     * images that otherwise stick at identity matrix (small image at
     * top of the preview).
     */
    fun requestRecenter() {
        post { resetToFit() }
    }

    private fun resetToFit() {
        scale = 1f
        translateX = 0f
        translateY = 0f
        applyMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && scale > 1.05f && activePointerId != -1) {
                    val pIdx = event.findPointerIndex(activePointerId)
                    if (pIdx != -1) {
                        val x = event.getX(pIdx)
                        val y = event.getY(pIdx)
                        translateX += x - lastTouchX
                        translateY += y - lastTouchY
                        lastTouchX = x
                        lastTouchY = y
                        applyMatrix()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
            }
        }
        return true
    }

    private fun applyMatrix() {
        val drawable = drawable ?: return
        val dW = drawable.intrinsicWidth.toFloat().takeIf { it > 0 } ?: return
        val dH = drawable.intrinsicHeight.toFloat().takeIf { it > 0 } ?: return
        val vW = width.toFloat().takeIf { it > 0 } ?: return
        val vH = height.toFloat().takeIf { it > 0 } ?: return

        val fitScale = min(vW / dW, vH / dH)
        val totalScale = fitScale * scale
        val scaledW = dW * totalScale
        val scaledH = dH * totalScale

        // Clamp pan so the bitmap stays in view when zoomed in.
        val maxTx = max(0f, (scaledW - vW) / 2f)
        val maxTy = max(0f, (scaledH - vH) / 2f)
        translateX = translateX.coerceIn(-maxTx, maxTx)
        translateY = translateY.coerceIn(-maxTy, maxTy)

        val cx = (vW - scaledW) / 2f + translateX
        val cy = (vH - scaledH) / 2f + translateY

        matrixObj.reset()
        matrixObj.postScale(totalScale, totalScale)
        matrixObj.postTranslate(cx, cy)
        imageMatrix = matrixObj
    }
}
