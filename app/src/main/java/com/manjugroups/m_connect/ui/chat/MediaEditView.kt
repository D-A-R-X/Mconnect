package com.manjugroups.m_connect.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Drop-in replacement for the pre-send preview ImageView that supports three
 * lightweight edit modes — Crop, Draw, Text — plus an undo stack. Edits are
 * tracked in view-space and flattened to bitmap-space when [getResult] is
 * called for the actual send.
 */
class MediaEditView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { NONE, CROP, DRAW, TEXT }

    var mode: Mode = Mode.NONE
        set(value) {
            field = value
            if (value == Mode.CROP) initCropRect()
            invalidate()
        }

    enum class BrushType { PEN, HIGHLIGHTER, MARKER }

    var brushColor: Int = Color.WHITE
    var brushStrokeWidth: Float = 10f
    var brushType: BrushType = BrushType.PEN
    var textColor: Int = Color.WHITE
    var textSizePx: Float = 64f

    /** When non-null, crop drag enforces width/height = ratio. */
    var cropAspectRatio: Float? = null
        set(value) {
            field = value
            if (mode == Mode.CROP) {
                initCropRect()
                invalidate()
            }
        }

    private var baseBitmap: Bitmap? = null
    private val drawnPaths = mutableListOf<DrawnPath>()
    private val textOverlays = mutableListOf<TextOverlay>()
    private val edits = ArrayDeque<Edit>()

    private var cropRect: RectF? = null
    private var currentPath: Path? = null
    private var currentPathPaint: Paint? = null

    private var dragMode: DragMode = DragMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var draggedTextIdx = -1

    fun setBitmap(bitmap: Bitmap?) {
        baseBitmap = bitmap
        drawnPaths.clear()
        textOverlays.clear()
        edits.clear()
        cropRect = null
        mode = Mode.NONE
        invalidate()
    }

    fun hasBitmap(): Boolean = baseBitmap != null

    fun addText(text: String) {
        if (text.isBlank()) return
        val overlay = TextOverlay(
            x = width / 2f,
            y = height / 2f,
            content = text,
            color = textColor,
            size = textSizePx
        )
        textOverlays += overlay
        edits.addLast(Edit.TextEdit(overlay))
        mode = Mode.TEXT
        invalidate()
    }

    fun applyCrop() {
        val rect = cropRect ?: run { mode = Mode.NONE; return }
        rect.sort()
        val bm = baseBitmap ?: return
        val displayed = displayedBitmapRect() ?: return
        val sx = bm.width.toFloat() / displayed.width()
        val sy = bm.height.toFloat() / displayed.height()
        val left = ((rect.left - displayed.left) * sx).coerceIn(0f, bm.width.toFloat())
        val top = ((rect.top - displayed.top) * sy).coerceIn(0f, bm.height.toFloat())
        val right = ((rect.right - displayed.left) * sx).coerceIn(left + 1f, bm.width.toFloat())
        val bottom = ((rect.bottom - displayed.top) * sy).coerceIn(top + 1f, bm.height.toFloat())
        val w = (right - left).toInt().coerceAtLeast(1)
        val h = (bottom - top).toInt().coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(bm, left.toInt(), top.toInt(), w, h)
        edits.addLast(Edit.CropEdit(previous = bm, droppedPaths = drawnPaths.toList(), droppedTexts = textOverlays.toList()))
        baseBitmap = cropped
        drawnPaths.clear()
        textOverlays.clear()
        cropRect = null
        mode = Mode.NONE
        invalidate()
    }

    fun cancelCrop() {
        cropRect = null
        mode = Mode.NONE
        invalidate()
    }

    fun undo(): Boolean {
        val last = edits.removeLastOrNull() ?: return false
        when (last) {
            is Edit.PathEdit -> drawnPaths.remove(last.path)
            is Edit.TextEdit -> textOverlays.remove(last.overlay)
            is Edit.CropEdit -> {
                baseBitmap = last.previous
                drawnPaths.clear()
                drawnPaths.addAll(last.droppedPaths)
                textOverlays.clear()
                textOverlays.addAll(last.droppedTexts)
            }
        }
        invalidate()
        return true
    }

    fun canUndo(): Boolean = edits.isNotEmpty()

    /** Flatten the base bitmap + paths + texts into a single new bitmap. */
    fun getResult(): Bitmap? {
        val src = baseBitmap ?: return null
        val displayed = displayedBitmapRect() ?: return src
        val sx = src.width.toFloat() / displayed.width()
        val sy = src.height.toFloat() / displayed.height()
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, null)
        c.save()
        c.scale(sx, sy)
        c.translate(-displayed.left, -displayed.top)
        drawnPaths.forEach { dp ->
            c.drawPath(dp.path, paintForPath(dp))
        }
        textOverlays.forEach { t ->
            c.drawText(t.content, t.x, t.y, paintForText(t))
        }
        c.restore()
        return out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bm = baseBitmap ?: return
        val displayed = displayedBitmapRect() ?: return
        canvas.drawBitmap(bm, null, displayed, null)

        drawnPaths.forEach { dp ->
            canvas.drawPath(dp.path, paintForPath(dp))
        }
        currentPath?.let { p ->
            currentPathPaint?.let { paint -> canvas.drawPath(p, paint) }
        }
        textOverlays.forEach { t ->
            canvas.drawText(t.content, t.x, t.y, paintForText(t))
        }

        if (mode == Mode.CROP) {
            cropRect?.let { rect ->
                val dim = Paint().apply { color = 0x88000000.toInt() }
                canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dim)
                canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dim)
                canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dim)
                canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dim)
                val border = Paint().apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                    isAntiAlias = true
                }
                canvas.drawRect(rect, border)
                val handle = Paint().apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                val r = 14f
                listOf(
                    rect.left to rect.top,
                    rect.right to rect.top,
                    rect.left to rect.bottom,
                    rect.right to rect.bottom
                ).forEach { (x, y) -> canvas.drawCircle(x, y, r, handle) }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (mode) {
            Mode.DRAW -> { handleDrawTouch(event); true }
            Mode.CROP -> { handleCropTouch(event); true }
            Mode.TEXT -> { handleTextTouch(event); true }
            Mode.NONE -> false
        }
    }

    private fun handleDrawTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path().apply { moveTo(event.x, event.y) }
                currentPathPaint = paintForPath(
                    DrawnPath(currentPath!!, brushColor, brushStrokeWidth, brushType)
                )
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath?.lineTo(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentPath?.let {
                    val dp = DrawnPath(it, brushColor, brushStrokeWidth, brushType)
                    drawnPaths += dp
                    edits.addLast(Edit.PathEdit(dp))
                }
                currentPath = null
                currentPathPaint = null
                invalidate()
            }
        }
    }

    private fun handleCropTouch(event: MotionEvent) {
        val rect = cropRect ?: return
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = detectCropDragMode(event.x, event.y, rect)
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                val bounds = displayedBitmapRect() ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
                val ratio = cropAspectRatio
                when (dragMode) {
                    DragMode.TL -> {
                        rect.left = (rect.left + dx).coerceIn(bounds.left, rect.right - 40f)
                        rect.top = (rect.top + dy).coerceIn(bounds.top, rect.bottom - 40f)
                        if (ratio != null) {
                            val newW = rect.width()
                            val newH = newW / ratio
                            rect.top = (rect.bottom - newH).coerceAtLeast(bounds.top)
                        }
                    }
                    DragMode.TR -> {
                        rect.right = (rect.right + dx).coerceIn(rect.left + 40f, bounds.right)
                        rect.top = (rect.top + dy).coerceIn(bounds.top, rect.bottom - 40f)
                        if (ratio != null) {
                            val newW = rect.width()
                            val newH = newW / ratio
                            rect.top = (rect.bottom - newH).coerceAtLeast(bounds.top)
                        }
                    }
                    DragMode.BL -> {
                        rect.left = (rect.left + dx).coerceIn(bounds.left, rect.right - 40f)
                        rect.bottom = (rect.bottom + dy).coerceIn(rect.top + 40f, bounds.bottom)
                        if (ratio != null) {
                            val newW = rect.width()
                            val newH = newW / ratio
                            rect.bottom = (rect.top + newH).coerceAtMost(bounds.bottom)
                        }
                    }
                    DragMode.BR -> {
                        rect.right = (rect.right + dx).coerceIn(rect.left + 40f, bounds.right)
                        rect.bottom = (rect.bottom + dy).coerceIn(rect.top + 40f, bounds.bottom)
                        if (ratio != null) {
                            val newW = rect.width()
                            val newH = newW / ratio
                            rect.bottom = (rect.top + newH).coerceAtMost(bounds.bottom)
                        }
                    }
                    DragMode.MOVE -> {
                        val w = rect.width()
                        val h = rect.height()
                        rect.left = (rect.left + dx).coerceIn(bounds.left, bounds.right - w)
                        rect.top = (rect.top + dy).coerceIn(bounds.top, bounds.bottom - h)
                        rect.right = rect.left + w
                        rect.bottom = rect.top + h
                    }
                    DragMode.NONE -> Unit
                }
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
            }
        }
    }

    private fun handleTextTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggedTextIdx = -1
                for (i in textOverlays.indices.reversed()) {
                    val t = textOverlays[i]
                    val w = paintForText(t).measureText(t.content)
                    val rect = RectF(t.x - 24f, t.y - t.size, t.x + w + 24f, t.y + 24f)
                    if (rect.contains(event.x, event.y)) {
                        draggedTextIdx = i
                        break
                    }
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggedTextIdx in textOverlays.indices) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    val t = textOverlays[draggedTextIdx]
                    t.x += dx
                    t.y += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggedTextIdx = -1
            }
        }
    }

    private fun initCropRect() {
        val displayed = displayedBitmapRect() ?: return
        val ratio = cropAspectRatio
        if (ratio == null) {
            val inset = min(displayed.width(), displayed.height()) * 0.08f
            cropRect = RectF(
                displayed.left + inset,
                displayed.top + inset,
                displayed.right - inset,
                displayed.bottom - inset
            )
        } else {
            val maxW = displayed.width() * 0.9f
            val maxH = displayed.height() * 0.9f
            var w = maxW
            var h = w / ratio
            if (h > maxH) {
                h = maxH
                w = h * ratio
            }
            val cx = displayed.centerX()
            val cy = displayed.centerY()
            cropRect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        }
    }

    fun rotateCw90() {
        val bm = baseBitmap ?: return
        val m = android.graphics.Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, m, true)
        edits.addLast(
            Edit.CropEdit(
                previous = bm,
                droppedPaths = drawnPaths.toList(),
                droppedTexts = textOverlays.toList()
            )
        )
        baseBitmap = rotated
        drawnPaths.clear()
        textOverlays.clear()
        if (mode == Mode.CROP) initCropRect()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (mode == Mode.CROP && cropRect == null) initCropRect()
    }

    private fun detectCropDragMode(x: Float, y: Float, rect: RectF): DragMode {
        val touch = 64f
        val nearLeft = abs(x - rect.left) < touch
        val nearRight = abs(x - rect.right) < touch
        val nearTop = abs(y - rect.top) < touch
        val nearBottom = abs(y - rect.bottom) < touch
        return when {
            nearLeft && nearTop -> DragMode.TL
            nearRight && nearTop -> DragMode.TR
            nearLeft && nearBottom -> DragMode.BL
            nearRight && nearBottom -> DragMode.BR
            rect.contains(x, y) -> DragMode.MOVE
            else -> DragMode.NONE
        }
    }

    private fun displayedBitmapRect(): RectF? {
        val bm = baseBitmap ?: return null
        if (width == 0 || height == 0 || bm.width == 0 || bm.height == 0) return null
        val viewRatio = width.toFloat() / height
        val bmRatio = bm.width.toFloat() / bm.height
        return if (bmRatio > viewRatio) {
            val dh = width / bmRatio
            val top = (height - dh) / 2f
            RectF(0f, top, width.toFloat(), top + dh)
        } else {
            val dw = height * bmRatio
            val left = (width - dw) / 2f
            RectF(left, 0f, left + dw, height.toFloat())
        }
    }

    private fun paintForPath(dp: DrawnPath): Paint = Paint().apply {
        color = dp.color
        style = Paint.Style.STROKE
        isAntiAlias = true
        when (dp.brushType) {
            BrushType.PEN -> {
                strokeWidth = dp.strokeWidth
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            BrushType.HIGHLIGHTER -> {
                strokeWidth = dp.strokeWidth * 2.4f
                strokeCap = Paint.Cap.SQUARE
                strokeJoin = Paint.Join.BEVEL
                alpha = 110
            }
            BrushType.MARKER -> {
                strokeWidth = dp.strokeWidth * 1.6f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                maskFilter = android.graphics.BlurMaskFilter(2.5f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
        }
    }

    private fun paintForText(t: TextOverlay): Paint = Paint().apply {
        color = t.color
        textSize = t.size
        isAntiAlias = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(8f, 0f, 0f, 0x99000000.toInt())
    }

    private data class DrawnPath(
        val path: Path,
        val color: Int,
        val strokeWidth: Float,
        val brushType: BrushType
    )

    private data class TextOverlay(
        var x: Float,
        var y: Float,
        val content: String,
        val color: Int,
        val size: Float
    )

    private sealed class Edit {
        class PathEdit(val path: DrawnPath) : Edit()
        class TextEdit(val overlay: TextOverlay) : Edit()
        class CropEdit(
            val previous: Bitmap,
            val droppedPaths: List<DrawnPath>,
            val droppedTexts: List<TextOverlay>
        ) : Edit()
    }

    private enum class DragMode { NONE, TL, TR, BL, BR, MOVE }
}
