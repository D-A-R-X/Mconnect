package com.manjugroups.m_connect.ui.library.loans

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A simple canvas-based view that lets the user draw a signature
 * with their finger. Supports clearing and exporting to Bitmap.
 */
class SignaturePadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.parseColor("#1A3C8D")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val paths = mutableListOf<Path>()
    private var currentPath = Path()
    private var hasDrawn = false

    // An already-saved signature shown as a read-only preview (e.g. the
    // staff member's profile digital sign). Cleared the moment the user
    // starts drawing a new one.
    private var loadedBitmap: Bitmap? = null

    var onSignatureChanged: ((Boolean) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        loadedBitmap?.let { bmp ->
            val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bmp, null, dst, null)
        }
        for (path in paths) {
            canvas.drawPath(path, paint)
        }
        canvas.drawPath(currentPath, paint)
    }

    /** Show a previously-saved signature image as a read-only preview. */
    fun loadBitmap(bitmap: Bitmap?) {
        loadedBitmap = bitmap
        invalidate()
    }

    /** True only if the user physically drew (not just a loaded preview). */
    fun hasUserDrawn(): Boolean = hasDrawn

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // Drawing a new signature replaces any loaded preview.
                loadedBitmap = null
                currentPath = Path()
                currentPath.moveTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                paths.add(currentPath)
                currentPath = Path()
                hasDrawn = true
                onSignatureChanged?.invoke(true)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun clear() {
        paths.clear()
        currentPath = Path()
        hasDrawn = false
        loadedBitmap = null
        onSignatureChanged?.invoke(false)
        invalidate()
    }

    // A loaded preview counts as "not empty" — the user can submit it as-is.
    fun isEmpty(): Boolean = !hasDrawn && loadedBitmap == null

    fun getSignatureBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        draw(canvas)
        return bitmap
    }
}
