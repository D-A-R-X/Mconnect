package com.manjugroups.m_connect.network

import com.manjugroups.m_connect.util.ImageCompressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Shared photo upload helper for `POST api/storage/upload`.
 *
 * Punch selfies and odometer photos are uploaded from field locations with
 * weak/flaky mobile networks, so a single attempt is not enough: one transient
 * connect failure or timeout used to kill the whole clock-in. This helper
 * retries transient failures (IO errors, timeouts, 5xx) with a short backoff
 * and reports the real cause instead of swallowing it, so the user sees
 * "connection timed out" rather than a generic "please try again".
 *
 * 4xx responses are NOT retried — an expired session or rejected payload
 * won't fix itself, and the 401 watchdog interceptor already handles logout.
 */
object StorageUploader {

    data class Result(
        val storageId: String?,
        val errorMessage: String?,
        // True when the upload failed because the device couldn't reach the
        // server (timeout / no connection) rather than a server rejection
        // (4xx/5xx). Lets callers queue the work offline instead of failing.
        val isNetworkError: Boolean = false,
    ) {
        val isSuccess: Boolean get() = !storageId.isNullOrBlank()
    }

    suspend fun upload(
        api: ApiService,
        token: String,
        file: File,
        attempts: Int = 3,
        contentType: String = "image/jpeg",
        // Photos are downscaled before upload to spare field networks; set false
        // for the rare case a caller needs the exact original bytes preserved.
        compressImages: Boolean = true,
    ): Result {
        // Shrink photos once, up front — never per retry. Only image content is
        // touched (PDFs / other docs pass through). Compression is best-effort:
        // ImageCompressor returns the original on any failure. A distinct temp is
        // deleted after the upload; the caller's own file is never removed.
        val isImage = contentType.startsWith("image/", ignoreCase = true)
        val uploadFile = if (compressImages && isImage) {
            runCatching { withContext(Dispatchers.IO) { ImageCompressor.compress(file) } }
                .getOrDefault(file)
        } else {
            file
        }
        val ownsTemp = uploadFile !== file

        var lastError: String? = null
        // Tracks whether the most recent failure was a connectivity failure
        // (timeout / no network) vs a server rejection, so the caller can queue
        // the punch offline instead of losing it.
        var lastWasNetwork = false
        try {
            repeat(attempts) { attempt ->
                try {
                    val mime = runCatching { contentType.toMediaType() }.getOrNull()
                        ?: "application/octet-stream".toMediaType()
                    val body = uploadFile.asRequestBody(mime)
                    val response = api.uploadStorageFile(token, body)
                    val id = response.storageId
                    if (!id.isNullOrBlank()) return Result(id, null)
                    lastError = response.error ?: "Server did not return a file ID."
                    lastWasNetwork = false
                } catch (e: HttpException) {
                    if (e.code() in 400..499) {
                        val message = when (e.code()) {
                            401 -> "Session expired. Please log in again."
                            413 -> "Photo is too large."
                            else -> "Upload rejected (HTTP ${e.code()})."
                        }
                        return Result(null, message)
                    }
                    lastError = "Server error (HTTP ${e.code()})."
                    lastWasNetwork = false
                } catch (e: SocketTimeoutException) {
                    lastError = "Connection timed out."
                    lastWasNetwork = true
                } catch (e: IOException) {
                    lastError = "Network error. Check your connection."
                    lastWasNetwork = true
                } catch (e: Exception) {
                    lastError = e.message ?: "Unexpected error."
                    lastWasNetwork = false
                }
                if (attempt < attempts - 1) delay(1500L * (attempt + 1))
            }
            return Result(null, lastError, isNetworkError = lastWasNetwork)
        } finally {
            // Clean up only the compressed temp we created, never the caller's file.
            if (ownsTemp) runCatching { uploadFile.delete() }
        }
    }
}
