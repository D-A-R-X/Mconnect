package com.manjugroups.m_connect.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Turns a picked or captured document photo into an upload-ready JPEG:
 * EXIF rotation applied, long edge capped (sharp enough to read a bank
 * statement, small enough for field networks), transparency flattened onto
 * white, and HEIC / WebP / PNG converted to JPEG so every reviewer can open
 * it. Never throws: null means the image could not be decoded.
 */
object DocumentImageNormalizer {

    const val DEFAULT_MAX_EDGE_PX = 2400
    private const val JPEG_QUALITY = 88

    fun toJpeg(source: File, output: File, maxEdgePx: Int = DEFAULT_MAX_EDGE_PX): File? = runCatching {
        val bitmap = decode(source, maxEdgePx) ?: return null
        try {
            FileOutputStream(output).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return null
            }
        } finally {
            bitmap.recycle()
        }
        output.takeIf { it.length() > 0 }
    }.getOrNull()

    /** Decode with sampling + EXIF rotation, long edge capped at [maxEdgePx], opaque. */
    fun decode(source: File, maxEdgePx: Int = DEFAULT_MAX_EDGE_PX): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxEdgePx || bounds.outHeight / (sample * 2) >= maxEdgePx) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val scale = minOf(1f, maxEdgePx.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            exifTransform(source)?.let { postConcat(it) }
        }
        val transformed = if (matrix.isIdentity) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        }
        if (!transformed.hasAlpha()) return transformed
        // PNG / WebP with transparency would turn black in a JPEG.
        Bitmap.createBitmap(transformed.width, transformed.height, Bitmap.Config.ARGB_8888).also { opaque ->
            Canvas(opaque).apply {
                drawColor(Color.WHITE)
                drawBitmap(transformed, 0f, 0f, null)
            }
            transformed.recycle()
        }
    }.getOrNull()

    private fun exifTransform(source: File): Matrix? {
        val orientation = runCatching {
            ExifInterface(source.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
                else -> return null
            }
        }
    }
}
