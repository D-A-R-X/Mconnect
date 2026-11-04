package com.manjugroups.m_connect.util

import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Assembles camera-scanned page photos into a single PDF for document upload
 * (Loan Desk "scan & upload"). One photo = one PDF page, EXIF rotation baked
 * in, downscaled to [MAX_EDGE_PX] so a 4-page scan stays a few hundred KB
 * instead of tens of MB on field networks.
 */
object ScanPdfBuilder {

    // Long enough to keep small print on statements and slips legible.
    private const val MAX_EDGE_PX = 2000

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
                val bitmap = DocumentImageNormalizer.decode(photo, MAX_EDGE_PX) ?: continue
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
}
