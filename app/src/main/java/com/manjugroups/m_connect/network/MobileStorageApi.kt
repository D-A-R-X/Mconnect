package com.manjugroups.m_connect.network

import com.manjugroups.m_connect.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

/** MFPL-authenticated control plane for external mobile storage uploads. */
interface MobileStorageApi {
    @POST("api/storage/uploads")
    suspend fun createUpload(
        @Header("Authorization") token: String,
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateStorageUploadRequest,
    ): Response<CreateStorageUploadResponse>

    @PUT
    suspend fun putUploadBytes(
        @Url uploadUrl: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    @POST("api/storage/uploads/{fileId}/complete")
    suspend fun completeUpload(
        @Header("Authorization") token: String,
        @Path("fileId", encoded = false) fileId: String,
        @Body body: CompleteStorageUploadRequest,
    ): Response<CompleteStorageUploadResponse>

    @DELETE("api/storage/uploads/{fileId}")
    suspend fun abortUpload(
        @Header("Authorization") token: String,
        @Path("fileId", encoded = false) fileId: String,
    ): Response<AbortStorageUploadResponse>

    companion object {
        fun create(): MobileStorageApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(BuildConfig.STORAGE_BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MobileStorageApi::class.java)
        }
    }
}

enum class MobileStoragePurpose(val wireValue: String, val maxBytes: Long) {
    ATTENDANCE_PHOTO("attendance.photo", 10L * 1024 * 1024),
    STAFF_DOCUMENT("staff.document", 100L * 1024 * 1024),
    CHAT_ATTACHMENT("chat.attachment", 100L * 1024 * 1024),
    PROJECT_MEDIA("project.media", 100L * 1024 * 1024),
    MOBILE_GENERIC("mobile.generic", 100L * 1024 * 1024),
}

data class CreateStorageUploadRequest(
    val fileName: String,
    val contentType: String,
    val sizeBytes: Long,
    val purpose: String,
)

data class CreateStorageUploadResponse(
    val success: Boolean = false,
    val fileId: String? = null,
    val storageId: String? = null,
    val uploadUrl: String? = null,
    val method: String? = null,
    val requiredHeaders: Map<String, String> = emptyMap(),
    val maxSizeBytes: Long? = null,
    val expiresIn: Long? = null,
    val purpose: String? = null,
    val error: String? = null,
)

data class CompleteStorageUploadRequest(
    val storageId: String,
    val sha256: String? = null,
)

data class CompleteStorageUploadResponse(
    val success: Boolean = false,
    val fileId: String? = null,
    val status: String? = null,
    val storageId: String? = null,
    val error: String? = null,
)

data class AbortStorageUploadResponse(
    val success: Boolean = false,
    val status: String? = null,
    val error: String? = null,
)
