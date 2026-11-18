package com.manjugroups.m_connect.ui.lens

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Face crop for the photo sent to Lens: the whole head with a small margin,
 * never the shirt and waist. Pure math (no Android) so it is unit-tested.
 *
 * Lens rule: never cut the chin or forehead. The box is the face box grown to
 * about 1.9x its width and 2.3x its height, moved up 10% of the face height
 * (hair and forehead), made 4:5 portrait by growing the short side, then
 * kept inside the frame (it slides; it only shrinks when the frame itself is
 * smaller, and never below the face plus a small margin).
 */
object LensFaceCrop {

    data class Box(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
    }

    const val WIDTH_FACTOR = 1.9f
    const val HEIGHT_FACTOR = 2.3f
    const val SHIFT_UP = 0.10f
    /** Portrait width / height. */
    const val ASPECT = 0.8f
    /** Smallest box when the frame is tight: face plus this margin on each side. */
    private const val MIN_MARGIN = 0.08f
    /** Crops narrower than this are upscaled to [UPSCALE_TO] px wide. */
    const val UPSCALE_BELOW = 360
    const val UPSCALE_TO = 480

    fun cropBox(
        faceLeft: Float, faceTop: Float, faceWidth: Float, faceHeight: Float,
        imageWidth: Int, imageHeight: Int,
    ): Box {
        if (imageWidth <= 0 || imageHeight <= 0) return Box(0, 0, max(0, imageWidth), max(0, imageHeight))
        if (faceWidth <= 0f || faceHeight <= 0f) return Box(0, 0, imageWidth, imageHeight)
        val cx = faceLeft + faceWidth / 2f
        val cy = faceTop + faceHeight / 2f - SHIFT_UP * faceHeight

        var w = faceWidth * WIDTH_FACTOR
        var h = faceHeight * HEIGHT_FACTOR
        // 4:5 portrait by growing the short side (never cuts the head).
        if (w / h < ASPECT) w = h * ASPECT else h = w / ASPECT

        // Frame smaller than the box: shrink to the frame, then back toward
        // 4:5 by trimming the long side, but never below face + margin.
        val minW = faceWidth * (1f + 2 * MIN_MARGIN)
        val minH = faceHeight * (1f + 2 * MIN_MARGIN)
        w = min(w, imageWidth.toFloat())
        h = min(h, imageHeight.toFloat())
        if (w / h > ASPECT) w = max(h * ASPECT, min(w, minW)) else h = max(w / ASPECT, min(h, minH))
        w = min(w, imageWidth.toFloat())
        h = min(h, imageHeight.toFloat())

        val width = w.roundToInt().coerceIn(1, imageWidth)
        val height = h.roundToInt().coerceIn(1, imageHeight)
        val left = (cx - width / 2f).roundToInt().coerceIn(0, imageWidth - width)
        val top = (cy - height / 2f).roundToInt().coerceIn(0, imageHeight - height)
        return Box(left, top, width, height)
    }

    /** Output size: native pixels, or upscaled to [UPSCALE_TO] wide when the crop is tiny. */
    fun outputSize(cropWidth: Int, cropHeight: Int): Pair<Int, Int> {
        if (cropWidth <= 0 || cropHeight <= 0) return cropWidth to cropHeight
        if (cropWidth >= UPSCALE_BELOW) return cropWidth to cropHeight
        val scale = UPSCALE_TO.toFloat() / cropWidth
        return UPSCALE_TO to (cropHeight * scale).roundToInt()
    }
}
