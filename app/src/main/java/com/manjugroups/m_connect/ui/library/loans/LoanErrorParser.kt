package com.manjugroups.m_connect.ui.library.loans

import com.google.gson.JsonParser
import retrofit2.HttpException

/**
 * Pulls a clean human-readable message out of the convex HTTP error
 * payload that the loans + advances create endpoints return on
 * failure. Convex throws look like:
 *
 *   {"success":false,"error":"Uncaught Error: Cannot create a new
 *    advance — employee already has an active advance (LOAN-000051).
 *    Wait until it is settled, cancelled, or rejected.\n    at
 *    assertNoBlocking ..."}
 *
 * The raw string is unfit for a UI dialog. This helper:
 *   1. JSON-parses the body and reads `.error` (falls back to the
 *      raw body if parsing fails).
 *   2. Strips the "Uncaught Error:" prefix.
 *   3. Drops the stack trace ("at frameworkInternals…") that starts
 *      after the first `\n    at` marker.
 *   4. Recognises the "already has an active …" case and returns a
 *      short, action-oriented sentence the user can act on.
 */
object LoanErrorParser {

    fun friendlyMessage(e: HttpException): String {
        val raw = runCatching {
            e.response()?.errorBody()?.string()
        }.getOrNull().orEmpty()

        val errorText = parseConvexError(raw).ifBlank { e.message() }
        val cleaned = stripStackTrace(stripUncaughtPrefix(errorText)).trim()

        // Specific case the user flagged — an active or pending loan
        // /advance already exists and the backend is blocking the new
        // create. Surface the short call-to-action instead of the
        // convex paragraph.
        val lower = cleaned.lowercase()
        if (lower.contains("already has an active advance")) {
            return "You already have an active salary advance. Wait until it is settled, cancelled, or rejected."
        }
        if (lower.contains("already has an active loan")) {
            return "You already have an active loan. Wait until it is settled, cancelled, or rejected."
        }
        if (lower.contains("already has a pending advance")) {
            return "You already have a pending advance request awaiting approval."
        }
        if (lower.contains("already has a pending loan")) {
            return "You already have a pending loan request awaiting approval."
        }

        return cleaned.ifBlank { "Something went wrong. Please try again." }
    }

    private fun parseConvexError(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val obj = JsonParser.parseString(body).asJsonObject
            obj.get("error")?.asString
                ?: obj.get("message")?.asString
                ?: ""
        }.getOrDefault("")
    }

    private fun stripUncaughtPrefix(msg: String): String {
        // Convex stamps "Uncaught Error: " (and sometimes
        // "Uncaught: ") on top of every thrown error. Strip it once.
        val patterns = listOf("Uncaught Error: ", "Uncaught: ")
        var out = msg
        for (p in patterns) {
            if (out.startsWith(p, ignoreCase = true)) {
                out = out.removePrefix(p)
                break
            }
        }
        return out
    }

    private fun stripStackTrace(msg: String): String {
        // Trim everything from the first "\n    at " line — that's
        // the start of the convex JS stack which has no UI value.
        val markers = listOf("\n    at ", "\n  at ", "\nat ")
        var earliest = -1
        for (m in markers) {
            val idx = msg.indexOf(m)
            if (idx >= 0 && (earliest == -1 || idx < earliest)) earliest = idx
        }
        return if (earliest >= 0) msg.substring(0, earliest) else msg
    }
}
