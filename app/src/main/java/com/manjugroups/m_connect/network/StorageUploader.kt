package com.manjugroups.m_connect.network

import kotlinx.coroutines.delay
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

    data class Result(val storageId: String?, val errorMessage: String?) {
        val isSuccess: Boolean get() = !storageId.isNullOrBlank()
    }

    suspend fun upload(
        api: ApiService,
        token: String,
        file: File,
        attempts: Int = 3,
        contentType: String = "image/jpeg",
    ): Result {
        var lastError: String? = null
        repeat(attempts) { attempt ->
            try {
                val mime = runCatching { contentType.toMediaType() }.getOrNull()
                    ?: "application/octet-stream".toMediaType()
                val body = file.asRequestBody(mime)
                val response = api.uploadStorageFile(token, body)
                val id = response.storageId
                if (!id.isNullOrBlank()) return Result(id, null)
                lastError = response.error ?: "Server did not return a file ID."
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
            } catch (e: SocketTimeoutException) {
                lastError = "Connection timed out."
            } catch (e: IOException) {
                lastError = "Network error. Check your connection."
            } catch (e: Exception) {
                lastError = e.message ?: "Unexpected error."
            }
            if (attempt < attempts - 1) delay(1500L * (attempt + 1))
        }
        return Result(null, lastError)
    }
}
