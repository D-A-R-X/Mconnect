package com.manjugroups.m_connect.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionProofUploadContractTest {

    @Test
    fun compressedImageIsAlwaysUploadedAsJpeg() {
        val metadata = collectionProofUploadMetadata("image/png", imageCompressedToJpeg = true)

        assertEquals("image/jpeg", metadata.contentType)
        assertEquals("jpg", metadata.extension)
    }

    @Test
    fun pdfKeepsPdfMetadata() {
        val metadata = collectionProofUploadMetadata("application/pdf", imageCompressedToJpeg = false)

        assertEquals("application/pdf", metadata.contentType)
        assertEquals("pdf", metadata.extension)
    }

    @Test
    fun unknownBinaryDoesNotPretendToBeAnImage() {
        val metadata = collectionProofUploadMetadata(null, imageCompressedToJpeg = false)

        assertEquals("application/octet-stream", metadata.contentType)
        assertEquals("bin", metadata.extension)
    }
}
