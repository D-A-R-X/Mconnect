package com.manjugroups.m_connect.network

import com.google.gson.Gson
import com.manjugroups.m_connect.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.PUT

class MobileStorageContractTest {
    @Test
    fun `production storage configuration is isolated from MMS API`() {
        assertEquals("https://mg.theairix.com/", BuildConfig.STORAGE_BASE_URL)
        assertTrue(BuildConfig.STORAGE_UPLOADS_ENABLED)
        assertEquals(104_857_600L, BuildConfig.STORAGE_MAX_FILE_BYTES)
        assertFalse(BuildConfig.BASE_URL == BuildConfig.STORAGE_BASE_URL)
    }

    @Test
    fun `create payload keeps exact server field names and purpose`() {
        val json = Gson().toJson(
            CreateStorageUploadRequest(
                fileName = "attendance.jpg",
                contentType = "image/jpeg",
                sizeBytes = 42,
                purpose = MobileStoragePurpose.ATTENDANCE_PHOTO.wireValue,
            )
        )

        assertEquals(
            "{\"fileName\":\"attendance.jpg\",\"contentType\":\"image/jpeg\",\"sizeBytes\":42,\"purpose\":\"attendance.photo\"}",
            json,
        )
        assertEquals(10L * 1024 * 1024, MobileStoragePurpose.ATTENDANCE_PHOTO.maxBytes)
        assertEquals(100L * 1024 * 1024, MobileStoragePurpose.STAFF_DOCUMENT.maxBytes)
    }

    @Test
    fun `preferred route annotations match handoff`() {
        val methods = MobileStorageApi::class.java.methods.associateBy { it.name }
        assertEquals("api/storage/uploads", methods.getValue("createUpload").getAnnotation(POST::class.java).value)
        assertEquals(
            "api/storage/uploads/{fileId}/complete",
            methods.getValue("completeUpload").getAnnotation(POST::class.java).value,
        )
        assertEquals(
            "api/storage/uploads/{fileId}",
            methods.getValue("abortUpload").getAnnotation(DELETE::class.java).value,
        )
        assertTrue(methods.getValue("putUploadBytes").isAnnotationPresent(PUT::class.java))
        assertTrue(
            methods.getValue("putUploadBytes").parameterAnnotations
                .flatten()
                .any { it is HeaderMap },
        )
    }
}
