package com.manjugroups.m_connect.network

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileStorageFilesTest {
    @Test
    fun `bare storage id uses stable MFPL file resolver`() {
        assertEquals(
            "https://mg.theairix.com/api/storage/files/kg2-example",
            MobileStorageFiles.resolve("kg2-example"),
        )
    }

    @Test
    fun `legacy serve URL is converted to stable resolver`() {
        assertEquals(
            "https://mg.theairix.com/api/storage/files/kg2-example",
            MobileStorageFiles.resolve(
                "https://api-mfpl.theairix.com/api/storage/serve?storageId=kg2-example",
            ),
        )
    }

    @Test
    fun `legacy convex storage URL is converted to stable resolver`() {
        assertEquals(
            "https://mg.theairix.com/api/storage/files/64ceeb75-bfb4-4ed7-aabb-ae5f4297190a",
            MobileStorageFiles.resolve(
                "https://convex-mfpl.theairix.com/api/storage/64ceeb75-bfb4-4ed7-aabb-ae5f4297190a",
            ),
        )
    }

    @Test
    fun `external signed URL is kept unchanged`() {
        val url = "https://object-store.example/file.jpg?signature=short-lived"
        assertEquals(url, MobileStorageFiles.resolve(url))
    }
}
