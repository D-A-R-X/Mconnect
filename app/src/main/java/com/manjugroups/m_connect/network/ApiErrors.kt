package com.manjugroups.m_connect.network

import com.google.gson.JsonParser
import okhttp3.Interceptor
import retrofit2.HttpException
import java.io.IOException

/**
 * Readable server errors everywhere.
 *
 * The backend answers failures with `{ success: false, error: "..." }` and a
 * 4xx/5xx status. Retrofit turns that into `HttpException("HTTP 500 ")` (HTTP/2
 * has no reason phrase), so screens that show `e.message` said only
 * "Error: HTTP 500" and the reviewer never learned what to fix.
 *
 * [interceptor] copies the server's own error text into the response's reason
 * phrase (the body is left untouched, so screens that parse `errorBody()`
 * still work). Every `e.message` then reads "HTTP 500 <what went wrong>", and
 * [userMessage] gives the text alone.
 */
object ApiErrors {

    private const val PEEK_BYTES = 64L * 1024L
    private const val MAX_LENGTH = 400

    val interceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (response.isSuccessful) return@Interceptor response
        val text = runCatching { extract(response.peekBody(PEEK_BYTES).string()) }.getOrNull()
        if (text.isNullOrBlank()) response else response.newBuilder().message(text).build()
    }

    /** The `error` / `message` text of a JSON error body, cleaned of Convex noise. */
    fun extract(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val json = runCatching { JsonParser.parseString(body) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val raw = listOf("error", "message").firstNotNullOfOrNull { key ->
            json.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }
        return clean(raw)
    }

    /**
     * "[CONVEX M(x:y)] [Request ID: abc] Server Error Uncaught Error: Real text
     *  at handler (../convex/x.ts:12:3)" → "Real text".
     */
    fun clean(raw: String?): String? {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.indexOf("Uncaught Error:").takeIf { it >= 0 }?.let {
            text = text.substring(it + "Uncaught Error:".length)
        }
        text = text
            .replace(Regex("""\[CONVEX [^\]]*]"""), "")
            .replace(Regex("""\[Request ID: [^\]]*]"""), "")
            .replace(Regex("""^\s*Server Error\s*"""), "")
        // Drop the stack trace ("    at handler (...)") and anything after it.
        text = text.split(Regex("""\n|\s+at\s+\S+\s+\(""")).first()
        text = text.replace(Regex("""^(Uncaught\s+)?(Convex)?Error:\s*"""), "").trim()
        if (text.isEmpty()) return null
        return if (text.length > MAX_LENGTH) text.take(MAX_LENGTH - 1).trimEnd() + "…" else text
    }
}

/** What to show the user for a failed call: the server's reason, not "HTTP 500". */
fun Throwable.userMessage(fallback: String = "Something went wrong. Please try again."): String =
    when (this) {
        is HttpException -> response()?.message()?.trim()?.takeIf { it.isNotEmpty() }
            ?: when (code()) {
                401 -> "Your session expired. Please sign in again."
                403 -> "You don't have permission to do this."
                404 -> "This feature isn't available on the server yet."
                else -> "Server error (${code()}). Please try again."
            }
        is IOException -> "No internet connection. Please try again."
        else -> message?.takeIf { it.isNotBlank() } ?: fallback
    }
