package com.manjugroups.m_connect.network

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageUploaderMetadataTest {

    @Test
    fun reencodedImageUsesJpegContentType() {
        assertEquals("image/jpeg", effectiveUploadContentType("image/png", true))
    }

    @Test
    fun skippedCompressionKeepsOriginalContentType() {
        assertEquals("image/webp", effectiveUploadContentType("image/webp", false))
    }
}
