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
            detail(error)?.let { return it }
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
            else -> clean(error.message) ?: "Could not submit the request. Please try again."
        }
    }

    /**
     * The server may have saved the request even though the reply never
     * arrived cleanly (timeout, or a 5xx that carries no reason). Callers
     * check the request history before telling the user it failed.
     */
    fun isAmbiguous(error: Throwable): Boolean = when (error) {
        is HttpException -> error.code() in 500..599 && detail(error) == null
        is SocketTimeoutException -> true
        else -> false
    }

    /**
     * A server reason as a person should read it. Convex wraps a thrown
     * Error as "Uncaught Error: <reason>\n    at handler (…)", sometimes behind
     * "[CONVEX M(…)] [Request ID: …] Server Error". Returns null when nothing
     * readable is left (blank, markup, or a bare "Server Error").
     */
    fun clean(raw: String?): String? {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.startsWith("<")) return null
        // The reason is after the last wrapper, on the first line.
        WRAPPERS.forEach { marker ->
            val at = text.lastIndexOf(marker)
            if (at >= 0) text = text.substring(at + marker.length)
        }
        text = text.lineSequence().firstOrNull().orEmpty()
            .replace(REQUEST_TAGS, "")
            .removeSuffix("Called by client")
            .trim()
        if (text.startsWith("Error:")) text = text.removePrefix("Error:").trim()
        if (text.isEmpty() || text.equals("Server Error", ignoreCase = true)) return null
        return text
    }

    private val WRAPPERS = listOf("Uncaught ConvexError:", "Uncaught Error:", "ConvexError:", "Uncaught:")
    private val REQUEST_TAGS = Regex("""\[(CONVEX [^\]]*|Request ID: [^\]]*)\]""")

    private fun detail(error: HttpException): String? {
        // errorBody() can be read once; cache it on the exception's response.
        val body = bodies.getOrPut(error) {
            runCatching { error.response()?.errorBody()?.string() }.getOrNull().orEmpty()
        }
        val json = runCatching { JsonParser.parseString(body) }.getOrNull()
        if (json != null && json.isJsonObject) {
            return clean(text(json.asJsonObject.get("error")) ?: text(json.asJsonObject.get("message")))
        }
        // A plain-text reason (not an HTML gateway page).
        return clean(body.takeIf { json == null || json.isJsonPrimitive })
    }

    private val bodies = java.util.Collections.synchronizedMap(java.util.WeakHashMap<HttpException, String>())

    private fun text(value: JsonElement?): String? {
        if (value == null || value.isJsonNull) return null
        if (value.isJsonObject) return text(value.asJsonObject.get("message"))
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString.trim().takeIf { it.isNotEmpty() }
    }
}
