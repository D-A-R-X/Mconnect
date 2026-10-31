package com.manjugroups.m_connect.ui.hr

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

internal object HrApplicationError {
    fun message(error: Throwable, application: String): String {
        if (error is CancellationException) throw error
        if (error is HttpException) {
            val body = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            val json = body?.let { runCatching { JsonParser.parseString(it) }.getOrNull() }
            val detail = json?.takeIf { it.isJsonObject }?.asJsonObject?.let {
                text(it.get("error")) ?: text(it.get("message"))
            }
            if (!detail.isNullOrBlank()) return detail
            return when (error.code()) {
                400, 422 -> "Please check the ${application.lowercase()} details and try again."
                401 -> "Your session expired. Please sign in again."
                403 -> "You are not allowed to submit this ${application.lowercase()} request."
                409 -> "A conflicting request already exists. Please check your request history."
                in 500..599 -> "$application service is temporarily unavailable. Please try again shortly."
                else -> "Could not submit the ${application.lowercase()} request. Please try again."
            }
        }
        return when (error) {
            is SocketTimeoutException -> "Request timed out. Check your request history before submitting again."
            is IOException -> "Could not connect. Check your connection and try again."
            else -> error.message?.takeIf { it.isNotBlank() } ?: "Could not submit the request. Please try again."
        }
    }

    private fun text(value: JsonElement?): String? {
        if (value == null || value.isJsonNull) return null
        if (value.isJsonObject) return text(value.asJsonObject.get("message"))
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString.trim().takeIf { it.isNotEmpty() }
    }
}
