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

    // ── Notices: one readable sentence + a kind for any failure or message ──

    enum class NoticeKind { ERROR, WARNING, OFFLINE, SUCCESS, INFO }

    data class Notice(val text: String, val kind: NoticeKind)

    /** Plain wording per HTTP status when the server sent no reason. */
    fun forStatus(code: Int): Notice = when (code) {
        400 -> Notice("The server couldn't accept this request. Check the details and try again.", NoticeKind.WARNING)
        401 -> Notice("Your session expired. Sign in again.", NoticeKind.WARNING)
        403 -> Notice("You don't have permission to do this.", NoticeKind.WARNING)
        404 -> Notice("Not found. It may have been removed or isn't available yet.", NoticeKind.WARNING)
        405 -> Notice("This action isn't supported by the server yet. Update the app.", NoticeKind.WARNING)
        408 -> Notice("The server took too long to respond. Try again.", NoticeKind.OFFLINE)
        409 -> Notice("This was changed by someone else. Refresh and try again.", NoticeKind.WARNING)
        410 -> Notice("This is no longer available.", NoticeKind.WARNING)
        413 -> Notice("That file is too large to upload.", NoticeKind.WARNING)
        415 -> Notice("That file type isn't supported.", NoticeKind.WARNING)
        422 -> Notice("Some details are missing or invalid. Check the form and try again.", NoticeKind.WARNING)
        426 -> Notice("Update the app to keep using this feature.", NoticeKind.WARNING)
        429 -> Notice("Too many requests. Wait a moment and try again.", NoticeKind.WARNING)
        500 -> Notice("Something went wrong on the server. Try again.", NoticeKind.ERROR)
        502, 503 -> Notice("The server is busy right now. Try again shortly.", NoticeKind.ERROR)
        504 -> Notice("The server took too long to respond. Try again.", NoticeKind.OFFLINE)
        in 400..499 -> Notice("The request couldn't be completed ($code). Try again.", NoticeKind.WARNING)
        in 500..599 -> Notice("Server error ($code). Try again shortly.", NoticeKind.ERROR)
        else -> Notice("Something went wrong. Try again.", NoticeKind.ERROR)
    }

    /** Readable notice for any failed call. */
    fun noticeFor(error: Throwable, fallback: String? = null): Notice {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        chain.firstNotNullOfOrNull { it as? HttpException }?.let { http ->
            val code = http.code()
            // A stock HTTP/1.1 phrase ("Internal Server Error") says nothing
            // the plain wording doesn't; only a real server reason is shown.
            val reason = http.response()?.message()?.trim()
                ?.takeIf { it.isNotEmpty() && it.lowercase() !in stockReasonPhrases }
            return if (reason.isNullOrBlank()) forStatus(code) else Notice(reason, forStatus(code).kind)
        }
        networkNotice(chain)?.let { return it }
        val message = error.message?.trim().orEmpty()
        if (message.isNotEmpty()) return noticeForText(message)
        return Notice(fallback ?: "Something went wrong. Try again.", NoticeKind.ERROR)
    }

    private fun networkNotice(chain: List<Throwable>): Notice? {
        for (t in chain) {
            when (t) {
                is java.net.SocketTimeoutException ->
                    return Notice(TIMEOUT, NoticeKind.OFFLINE)
                is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
                    return Notice(OFFLINE, NoticeKind.OFFLINE)
                is javax.net.ssl.SSLException ->
                    return Notice(SSL, NoticeKind.OFFLINE)
                is IOException ->
                    return Notice(CONNECTION_LOST, NoticeKind.OFFLINE)
            }
        }
        return null
    }

    private val stockReasonPhrases = setOf(
        "ok", "bad request", "unauthorized", "forbidden", "not found", "method not allowed",
        "request timeout", "conflict", "gone", "payload too large", "request entity too large",
        "unsupported media type", "unprocessable entity", "unprocessable content", "upgrade required",
        "too many requests", "internal server error", "bad gateway", "service unavailable",
        "gateway timeout", "response.error()",
    )

    private const val TIMEOUT ="The server took too long to respond. Try again."
    private const val OFFLINE = "No internet connection. Check your network and try again."
    private const val SSL = "Secure connection failed. Check the phone's date and time, then try again."
    private const val CONNECTION_LOST = "Connection lost. Check your network and try again."

    private val httpPattern = Regex("""\(?\bHTTP\s+(\d{3})\b\)?[\s:.\-]*""", RegexOption.IGNORE_CASE)
    private val bareErrorPrefix = Regex("""(?i)^(error|failed|failure|exception|request failed|server returned|server error)$""")
    private val leadingErrorLabel = Regex("""(?i)^(error|exception)\s*:\s*""")

    /**
     * Cleans any message a screen wants to show and picks its kind:
     * "Error: HTTP 500 Leave overlaps" → "Leave overlaps" (error);
     * "HTTP 503" → "The server is busy right now…"; "java.net.SocketTimeout…"
     * → "The server took too long…"; JSON/Convex noise → the real reason.
     */
    fun noticeForText(raw: String): Notice {
        var text = raw.trim()
        if (text.startsWith("{")) extract(text)?.let { text = it }
        if (text.contains("[CONVEX") || text.contains("Uncaught Error")) clean(text)?.let { text = it }

        offlineNotice(text.lowercase())?.let { return it }

        httpPattern.find(text)?.let { match ->
            val code = match.groupValues[1].toIntOrNull() ?: 0
            val before = text.substring(0, match.range.first).trim().trimEnd(':', '-', '.').trim()
            val after = text.substring(match.range.last + 1).trim()
                .takeUnless { it.trim('.', ')', ' ').isEmpty() }.orEmpty()
                .takeUnless { it.lowercase() in stockReasonPhrases }.orEmpty()
            val prefixOnly = before.isEmpty() || bareErrorPrefix.matches(before)
            val reason = when {
                after.isNotEmpty() && prefixOnly -> after
                after.isNotEmpty() -> "$before: $after"
                prefixOnly -> return forStatus(code)
                else -> "$before. ${forStatus(code).text}"
            }
            return Notice(capitalise(reason), forStatus(code).kind)
        }
        text = text.replace(leadingErrorLabel, "").trim()
        if (text.isEmpty()) return Notice("Something went wrong. Try again.", NoticeKind.ERROR)
        return Notice(capitalise(text), classify(text.lowercase()))
    }

    private fun offlineNotice(lower: String): Notice? = when {
        "sockettimeoutexception" in lower || lower == "timeout" || "timed out" in lower ->
            Notice(TIMEOUT, NoticeKind.OFFLINE)
        "unable to resolve host" in lower || "unknownhostexception" in lower ||
            "no address associated" in lower || "failed to connect to" in lower ||
            "connectexception" in lower || "network is unreachable" in lower ->
            Notice(OFFLINE, NoticeKind.OFFLINE)
        "sslhandshake" in lower || "sslexception" in lower ->
            Notice(SSL, NoticeKind.OFFLINE)
        "connection reset" in lower || "unexpected end of stream" in lower ||
            "software caused connection abort" in lower || "broken pipe" in lower ->
            Notice(CONNECTION_LOST, NoticeKind.OFFLINE)
        else -> null
    }

    private val offlineWords = listOf(
        "no internet", "offline", "no network", "network error", "check your network", "took too long",
    )
    private val errorWords = listOf(
        "fail", "error", "couldn't", "could not", "unable", "went wrong", "not saved", "crash", "exception",
    )
    private val warningWords = listOf(
        "please", "required", "select ", "enter ", "must", "invalid", "not allowed", "denied", "permission",
        "expired", "already", "not found", "cannot", "can't", "too many", "limit", "exceed", "missing",
        "need", "allow", "only ", "not available", "isn't available", "wait", "too far", "outside",
    )
    private val successWords = listOf(
        "saved", "applied", "created", "updated", "approved", "submitted", "sent", "uploaded", "deleted",
        "removed", "added", "copied", "done", "success", "marked", "assigned", "completed", "cancelled",
        "canceled", "rejected", "verified", "confirmed", "started", "stopped", "scheduled", "postponed",
        "punched", "clocked", "shared", "downloaded", "restored", "accepted", "logged",
    )

    private fun classify(lower: String): NoticeKind = when {
        offlineWords.any { it in lower } -> NoticeKind.OFFLINE
        errorWords.any { it in lower } -> NoticeKind.ERROR
        warningWords.any { it in lower } -> NoticeKind.WARNING
        successWords.any { it in lower } -> NoticeKind.SUCCESS
        else -> NoticeKind.INFO
    }

    private fun capitalise(text: String): String =
        text.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

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
    ApiErrors.noticeFor(this, fallback).text
