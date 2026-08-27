package com.manjugroups.m_connect.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Assembles camera-scanned page photos into a single PDF for document upload
 * (Loan Desk "scan & upload"). One photo = one PDF page, EXIF rotation baked
 * in, downscaled to [MAX_EDGE_PX] so a 4-page scan stays a few hundred KB
 * instead of tens of MB on field networks.
 */
object ScanPdfBuilder {

    private const val MAX_EDGE_PX = 1600
    private const val JPEG_QUALITY = 80

    /**
     * Build a PDF at [output] from [pages] (JPEG/PNG files, in order).
     * Returns the output file, or null when no page could be decoded.
     * Never throws — a failure returns null so the caller can fall back.
     */
    fun build(pages: List<File>, output: File): File? = runCatching {
        val document = PdfDocument()
        var pageNumber = 0
        try {
            for (photo in pages) {
                val bitmap = decodeDownscaled(photo) ?: continue
                pageNumber += 1
                val pageInfo = PdfDocument.PageInfo
                    .Builder(bitmap.width, bitmap.height, pageNumber)
                    .create()
                val page = document.startPage(pageInfo)
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                document.finishPage(page)
                bitmap.recycle()
            }
            if (pageNumber == 0) return null
            FileOutputStream(output).use { document.writeTo(it) }
        } finally {
            document.close()
        }
        output
    }.getOrNull()

    /** Decode with sampling + EXIF rotation, capped at [MAX_EDGE_PX]. */
    private fun decodeDownscaled(source: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            bounds.outWidth / sample > MAX_EDGE_PX * 2 ||
            bounds.outHeight / sample > MAX_EDGE_PX * 2
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, opts) ?: return null
        val rotated = applyExifRotation(source, decoded)

        val scale = minOf(
            1f,
            MAX_EDGE_PX.toFloat() / rotated.width.toFloat(),
            MAX_EDGE_PX.toFloat() / rotated.height.toFloat(),
        )
        if (scale >= 1f) return rotated
        val w = (rotated.width * scale).toInt().coerceAtLeast(1)
        val h = (rotated.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(rotated, w, h, true).also {
            if (it !== rotated) rotated.recycle()
        }
    }

    private fun applyExifRotation(source: File, bitmap: Bitmap): Bitmap {
        val degrees = runCatching {
            when (
                ExifInterface(source.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
