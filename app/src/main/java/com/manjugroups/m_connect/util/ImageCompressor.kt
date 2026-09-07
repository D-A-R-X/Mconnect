package com.manjugroups.m_connect.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Shrinks a camera/gallery photo before upload so field flows don't push
 * multi-MB originals over flaky mobile networks. Downscales the longest edge
 * to [maxEdge], honours the EXIF orientation, and re-encodes as JPEG at
 * [quality]. Everything is best-effort: any failure returns the original file
 * untouched, so a compression hiccup can never block an upload.
 *
 * Centralised so every image upload in the app (punch selfies, CP/SV photos,
 * daily-log, collection, odometer, booking docs) gets the same treatment —
 * StorageUploader runs it automatically for image content types.
 */
object ImageCompressor {

    /**
     * @param source the file to compress (a temp copy the caller owns).
     * @param maxEdge longest-edge cap in pixels. 1600 keeps ID documents /
     *   text legible while still cutting a 4000px camera shot down ~2.5x.
     * @param quality JPEG quality (0-100).
     * @param skipBelowBytes files already at/under this size are returned as-is
     *   — they're either already compressed or too small to be worth re-encoding
     *   (also avoids a second lossy pass on sites that pre-compress).
     * @return a NEW compressed file, or [source] itself when skipped/failed.
     *   Callers can tell "was a temp created" via `result !== source`.
     */
    fun compress(
        source: File,
        maxEdge: Int = 1600,
        quality: Int = 80,
        skipBelowBytes: Long = 500_000L,
    ): File {
        return try {
            if (!source.exists() || source.length() <= skipBelowBytes) return source

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return source

            // Coarse subsample on decode to keep peak memory down, then a precise
            // scale to the exact target edge.
            var sample = 1
            while (bounds.outWidth / sample > maxEdge * 2 || bounds.outHeight / sample > maxEdge * 2) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            val decoded = BitmapFactory.decodeFile(source.absolutePath, decodeOpts) ?: return source

            val rotated = applyExifRotation(source, decoded)

            val scale = minOf(
                1f,
                maxEdge.toFloat() / rotated.width.toFloat(),
                maxEdge.toFloat() / rotated.height.toFloat(),
            )
            val finalBitmap = if (scale < 1f) {
                val w = (rotated.width * scale).toInt().coerceAtLeast(1)
                val h = (rotated.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(rotated, w, h, true).also {
                    if (it !== rotated) rotated.recycle()
                }
            } else {
                rotated
            }

            val target = File(source.parentFile, "cmp_${System.currentTimeMillis()}.jpg")
            val encoded = try {
                FileOutputStream(target).use { output ->
                    finalBitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        quality.coerceIn(1, 100),
                        output,
                    )
                }
            } finally {
                finalBitmap.recycle()
            }
            if (!encoded || target.length() <= 0L || target.length() >= source.length()) {
                target.delete()
                return source
            }
            target
        } catch (_: Throwable) {
            source
        }
    }

    private fun applyExifRotation(source: File, bitmap: Bitmap): Bitmap {
        val degrees = try {
            val exif = ExifInterface(source.absolutePath)
            when (exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
