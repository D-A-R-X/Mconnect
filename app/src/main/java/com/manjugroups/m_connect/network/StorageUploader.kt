package com.manjugroups.m_connect.network

import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.util.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

/** Shared uploader preserving the existing business-facing `storageId`. */
object StorageUploader {

    data class Result(
        val storageId: String?,
        val errorMessage: String?,
        val isNetworkError: Boolean = false,
    ) {
        val isSuccess: Boolean get() = !storageId.isNullOrBlank()
    }

    private sealed interface PreferredResult {
        data class Success(val storageId: String) : PreferredResult
        data object Unavailable : PreferredResult
        data class Failed(val message: String, val networkError: Boolean = false) : PreferredResult
    }

    suspend fun upload(
        api: ApiService,
        token: String,
        file: File,
        attempts: Int = 3,
        contentType: String = "image/jpeg",
        purpose: MobileStoragePurpose = MobileStoragePurpose.MOBILE_GENERIC,
        compressImages: Boolean = true,
        imageMaxEdge: Int = 1600,
        imageQuality: Int = 80,
        imageSkipBelowBytes: Long = 500_000L,
    ): Result {
        val isImage = contentType.startsWith("image/", ignoreCase = true)
        val uploadFile = if (compressImages && isImage) {
            runCatching {
                withContext(Dispatchers.IO) {
                    ImageCompressor.compress(
                        source = file,
                        maxEdge = imageMaxEdge,
                        quality = imageQuality,
                        skipBelowBytes = imageSkipBelowBytes,
                    )
                }
            }.getOrDefault(file)
        } else {
            file
        }
        val ownsTemp = uploadFile !== file

        return try {
            val mime = runCatching { contentType.toMediaType() }.getOrNull()
                ?: "application/octet-stream".toMediaType()
            uploadRequestBodyResult(
                api = api,
                token = token,
                body = uploadFile.asRequestBody(mime),
                fileName = uploadFile.name,
                contentType = mime.toString(),
                purpose = purpose,
                attempts = attempts,
            )
        } finally {
            if (ownsTemp) runCatching { uploadFile.delete() }
        }
    }

    /** Migration bridge for existing callers that already prepared a body. */
    suspend fun uploadRequestBody(
        api: ApiService,
        token: String,
        body: RequestBody,
        fileName: String = defaultFileName(body.contentType()?.toString()),
        contentType: String = body.contentType()?.toString() ?: "application/octet-stream",
        purpose: MobileStoragePurpose = MobileStoragePurpose.MOBILE_GENERIC,
        attempts: Int = 3,
    ): StorageUploadResponse {
        val result = uploadRequestBodyResult(
            api = api,
            token = token,
            body = body,
            fileName = fileName,
            contentType = contentType,
            purpose = purpose,
            attempts = attempts,
        )
        return StorageUploadResponse(
            success = result.isSuccess,
            storageId = result.storageId,
            error = result.errorMessage,
        )
    }

    private suspend fun uploadRequestBodyResult(
        api: ApiService,
        token: String,
        body: RequestBody,
        fileName: String,
        contentType: String,
        purpose: MobileStoragePurpose,
        attempts: Int,
    ): Result {
        val safeAttempts = attempts.coerceAtLeast(1)
        val sizeBytes = runCatching { body.contentLength() }.getOrDefault(-1L)
        val maxBytes = minOf(BuildConfig.STORAGE_MAX_FILE_BYTES, purpose.maxBytes)
        if (sizeBytes > maxBytes) {
            return Result(null, "File is too large. Maximum is ${maxBytes / (1024 * 1024)} MB.")
        }
        if (contentType.equals("image/svg+xml", ignoreCase = true) ||
            fileName.endsWith(".svg", ignoreCase = true)
        ) {
            return Result(null, "SVG files are not supported.")
        }

        if (BuildConfig.STORAGE_UPLOADS_ENABLED && sizeBytes >= 0L) {
            when (
                val preferred = preferredUpload(
                    token = token,
                    body = body,
                    fileName = fileName,
                    contentType = contentType,
                    sizeBytes = sizeBytes,
                    purpose = purpose,
                    attempts = safeAttempts,
                )
            ) {
                is PreferredResult.Success -> return Result(preferred.storageId, null)
                is PreferredResult.Failed -> return Result(null, preferred.message, preferred.networkError)
                PreferredResult.Unavailable -> Unit
            }
        }

        return compatibilityUpload(
            api = api,
            token = token,
            body = body,
            fileName = fileName,
            purpose = purpose,
            attempts = safeAttempts,
        )
    }

    private suspend fun preferredUpload(
        token: String,
        body: RequestBody,
        fileName: String,
        contentType: String,
        sizeBytes: Long,
        purpose: MobileStoragePurpose,
        attempts: Int,
        storageApi: MobileStorageApi = MobileStorageApi.create(),
    ): PreferredResult {
        val idempotencyKey = "mobile-upload-${UUID.randomUUID()}"
        var contract: CreateStorageUploadResponse? = null
        repeat(attempts) { attempt ->
            if (contract != null) return@repeat
            try {
                val response = storageApi.createUpload(
                    token = token,
                    idempotencyKey = idempotencyKey,
                    body = CreateStorageUploadRequest(
                        fileName = fileName,
                        contentType = contentType,
                        sizeBytes = sizeBytes,
                        purpose = purpose.wireValue,
                    ),
                )
                if (response.code() == 404 || response.code() == 503) return PreferredResult.Unavailable
                if (response.isSuccessful) {
                    contract = response.body()
                    return@repeat
                }
                if (response.code() in 400..499) {
                    return PreferredResult.Failed(httpMessage(response.code(), "Upload creation"))
                }
            } catch (error: IOException) {
                if (attempt == attempts - 1) {
                    return PreferredResult.Failed(networkMessage(error), networkError = true)
                }
            }
            if (contract == null && attempt < attempts - 1) delay(backoff(attempt))
        }

        val created = contract ?: return PreferredResult.Failed("Storage service is temporarily unavailable.")
        val fileId = created.fileId?.takeIf(String::isNotBlank)
            ?: return PreferredResult.Failed(created.error ?: "Storage service did not return a file ID.")
        val storageId = created.storageId?.takeIf(String::isNotBlank)
            ?: return PreferredResult.Failed(created.error ?: "Storage service did not return a storage ID.")
        val uploadUrl = created.uploadUrl?.takeIf(String::isNotBlank)
            ?: return PreferredResult.Failed(created.error ?: "Storage service did not return an upload URL.")
        if (!created.success || !created.method.equals("PUT", ignoreCase = true)) {
            return PreferredResult.Failed(created.error ?: "Storage upload contract is invalid.")
        }
        if (created.maxSizeBytes != null && sizeBytes > created.maxSizeBytes) {
            bestEffortAbort(storageApi, token, fileId)
            return PreferredResult.Failed("File is larger than the server limit.")
        }

        var putSucceeded = false
        repeat(attempts) { attempt ->
            if (putSucceeded) return@repeat
            try {
                val response = storageApi.putUploadBytes(uploadUrl, created.requiredHeaders, body)
                if (response.isSuccessful) {
                    putSucceeded = true
                    return@repeat
                }
                if (response.code() in 400..499) {
                    bestEffortAbort(storageApi, token, fileId)
                    return PreferredResult.Failed(httpMessage(response.code(), "File upload"))
                }
            } catch (error: IOException) {
                if (attempt == attempts - 1) {
                    bestEffortAbort(storageApi, token, fileId)
                    return PreferredResult.Failed(networkMessage(error), networkError = true)
                }
            }
            if (!putSucceeded && attempt < attempts - 1) delay(backoff(attempt))
        }
        if (!putSucceeded) {
            bestEffortAbort(storageApi, token, fileId)
            return PreferredResult.Failed("File upload failed. Please retry.")
        }

        repeat(attempts) { attempt ->
            try {
                val response = storageApi.completeUpload(
                    token = token,
                    fileId = fileId,
                    body = CompleteStorageUploadRequest(storageId = storageId),
                )
                if (response.isSuccessful) {
                    val completed = response.body()
                    val completedId = completed?.storageId?.takeIf(String::isNotBlank)
                    if (completed?.success == true && completed.status == "ready" && completedId == storageId) {
                        return PreferredResult.Success(storageId)
                    }
                    return PreferredResult.Failed(completed?.error ?: "Storage completion was not confirmed.")
                }
                if (response.code() in 400..499) {
                    return PreferredResult.Failed(httpMessage(response.code(), "Upload completion"))
                }
            } catch (error: IOException) {
                if (attempt == attempts - 1) {
                    return PreferredResult.Failed(networkMessage(error), networkError = true)
                }
            }
            if (attempt < attempts - 1) delay(backoff(attempt))
        }
        return PreferredResult.Failed("Storage completion is pending. Please retry.")
    }

    private suspend fun compatibilityUpload(
        api: ApiService,
        token: String,
        body: RequestBody,
        fileName: String,
        purpose: MobileStoragePurpose,
        attempts: Int,
    ): Result {
        var lastError: String? = null
        var lastWasNetwork = false
        repeat(attempts) { attempt ->
            try {
                val response = api.uploadStorageFile(token, purpose.wireValue, fileName, body)
                val id = response.storageId
                if (response.success && !id.isNullOrBlank()) return Result(id, null)
                lastError = response.error ?: "Server did not return a file ID."
                lastWasNetwork = false
            } catch (error: HttpException) {
                if (error.code() in 400..499) return Result(null, httpMessage(error.code(), "Upload"))
                lastError = httpMessage(error.code(), "Upload")
                lastWasNetwork = false
            } catch (error: SocketTimeoutException) {
                lastError = "Connection timed out."
                lastWasNetwork = true
            } catch (error: IOException) {
                lastError = "Network error. Check your connection."
                lastWasNetwork = true
            } catch (error: Exception) {
                lastError = error.message ?: "Unexpected error."
                lastWasNetwork = false
            }
            if (attempt < attempts - 1) delay(backoff(attempt))
        }
        return Result(null, lastError, isNetworkError = lastWasNetwork)
    }

    private suspend fun bestEffortAbort(api: MobileStorageApi, token: String, fileId: String) {
        runCatching { api.abortUpload(token, fileId) }
    }

    private fun defaultFileName(contentType: String?): String {
        val extension = when (contentType?.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "application/pdf" -> "pdf"
            else -> "bin"
        }
        return "mobile-upload-${UUID.randomUUID()}.$extension"
    }

    private fun backoff(attempt: Int): Long = 1_500L * (attempt + 1)

    private fun networkMessage(error: IOException): String =
        if (error is SocketTimeoutException) "Connection timed out."
        else "Network error. Check your connection."

    private fun httpMessage(code: Int, stage: String): String = when (code) {
        400 -> "$stage request was invalid."
        401 -> "Session could not be verified for this upload."
        413 -> "File is too large."
        415 -> "Unsupported file type."
        503 -> "Storage service is temporarily unavailable."
        else -> "$stage failed (HTTP $code)."
    }
}
