package com.manjugroups.m_connect.ui.lens

import com.google.gson.JsonParser

/**
 * Staff-facing wording for the /api/lens/enroll outcomes. The server already
 * sends a sentence in `error`; these are the fallbacks (old/odd replies) and
 * the per-photo reasons, so a person always learns what to do next.
 */
object LensErrors {

    /** What the screen should offer after a failure. */
    enum class Action { RETAKE, RETRY, CONTACT_ADMIN, FIX }

    data class Outcome(val code: String, val message: String, val action: Action, val failed: List<FailedPhoto>)

    /**
     * Lens was down or not configured, but the server kept the photos on the
     * staff profile ("Photos saved…"): not a failure for the person enrolling.
     */
    fun savedForLater(outcome: Outcome): Boolean =
        (outcome.code.equals("LENS_NOT_CONFIGURED", true) || outcome.code.equals("LENS_NOT_READY", true)) &&
            outcome.message.trim().startsWith("Photos saved", ignoreCase = true)

    data class FailedPhoto(val pose: LensPose?, val code: String, val message: String)

    /** A bare 404 (no JSON code) means the Lens routes aren't deployed on the server yet. */
    const val NOT_DEPLOYED_MESSAGE =
        "Lens enrollment isn't set up on the server yet. Ask the admin to deploy it."

    fun messageFor(code: String?): String = when (code?.uppercase()) {
        "FACE_MATCHES_ANOTHER_PERSON" -> "This face is already enrolled for another staff member. Contact admin."
        "FACE_DOES_NOT_MATCH_EXISTING_ENROLLMENT" ->
            "These photos don't match this staff member's existing enrollment. Contact admin, or re-enroll with Replace."
        "NO_USABLE_FACE" -> "None of the photos could be used. Record again in good light, looking at the camera."
        "PHOTOS_SHOW_DIFFERENT_PEOPLE" ->
            "The photos don't match each other. Record again alone in the frame, turning only slightly."
        "NO_EMPLOYEE_CODE" -> "This staff member has no employee code, so they can't be enrolled."
        "STAFF_NOT_FOUND" -> "This staff member wasn't found. Refresh the list and try again."
        "FORBIDDEN" -> "You don't have permission to enroll faces."
        "INVALID_REQUEST" -> "The photos couldn't be sent. Record again."
        "LENS_NOT_CONFIGURED" -> "Lens enrollment is not configured yet. Contact admin."
        "LENS_NOT_DEPLOYED" -> NOT_DEPLOYED_MESSAGE
        "LENS_REJECTED" -> "Lens rejected the request. Contact admin."
        "LENS_NOT_READY" -> "The face service is starting up. Try again in a few seconds."
        else -> "Enrollment failed. Try again."
    }

    /** Per-photo reason (Lens `failed[].reason` prefix) as a short sentence. */
    fun photoMessageFor(code: String?): String = when (code?.uppercase()) {
        "NO_FACE" -> "No clear face, or the head was turned too far."
        "BLURRY_OR_POOR_QUALITY_FACE" -> "Too blurred."
        "FACE_TOO_SMALL" -> "The face was too small. Hold the phone closer."
        "NOT_SAME_PERSON" -> "This photo doesn't match the others."
        "TOO_SIMILAR_TO_ANOTHER_PERSON" -> "Too similar to another enrolled person."
        "INVALID_IMAGE" -> "The photo couldn't be read."
        else -> "This photo couldn't be used."
    }

    fun actionFor(httpStatus: Int, code: String?): Action = when (code?.uppercase()) {
        "FACE_MATCHES_ANOTHER_PERSON", "FACE_DOES_NOT_MATCH_EXISTING_ENROLLMENT",
        "LENS_NOT_CONFIGURED", "LENS_NOT_DEPLOYED", "LENS_REJECTED", "FORBIDDEN", "NO_EMPLOYEE_CODE" -> Action.CONTACT_ADMIN
        "NO_USABLE_FACE", "PHOTOS_SHOW_DIFFERENT_PEOPLE", "INVALID_REQUEST", "PHOTO_NOT_USED" -> Action.RETAKE
        "LENS_NOT_READY" -> Action.RETRY
        "STAFF_NOT_FOUND" -> Action.FIX
        else -> when {
            httpStatus == 503 || httpStatus == 502 || httpStatus == 504 || httpStatus == 429 -> Action.RETRY
            httpStatus >= 500 -> Action.RETRY
            httpStatus == 422 -> Action.RETAKE
            httpStatus == 409 || httpStatus == 403 -> Action.CONTACT_ADMIN
            else -> Action.FIX
        }
    }

    /**
     * Reads an error body `{ error, code, failed: [{ pose, code, message }] }`.
     * The server's sentence wins; otherwise our wording for the code; and a
     * 409 never echoes another person's identity (we only show our sentence).
     */
    fun parse(httpStatus: Int, body: String?): Outcome {
        val json = runCatching { JsonParser.parseString(body.orEmpty()) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
        fun str(key: String): String? = json?.get(key)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        val code = str("code") ?: when (httpStatus) {
            503 -> "LENS_NOT_READY"
            403 -> "FORBIDDEN"
            404 -> "LENS_NOT_DEPLOYED"
            else -> "UNKNOWN"
        }
        val serverText = str("error")
        val message = when {
            // Never risk showing another staff member's name/code from a 409.
            code.equals("FACE_MATCHES_ANOTHER_PERSON", true) -> messageFor(code)
            serverText != null -> serverText
            else -> messageFor(code)
        }
        val failed = json?.get("failed")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { el ->
            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            fun s(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
            val c = s("code") ?: "UNKNOWN"
            FailedPhoto(
                pose = LensPose.fromKey(s("pose")),
                code = c,
                message = s("message")?.takeIf { it.isNotBlank() } ?: photoMessageFor(c),
            )
        }.orEmpty()
        return Outcome(code, message, actionFor(httpStatus, code), failed)
    }
}

/**
 * Production Convex answers 429 TooManyConcurrentRequests (and the edge 502/503/504)
 * for a moment under load. Lens list/load calls retry those quietly before any
 * "server is busy" message reaches the screen.
 */
object LensBusyRetry {
    private val BUSY = setOf(429, 502, 503, 504)

    fun isBusy(code: Int): Boolean = code in BUSY

    /** For calls that return the body (non-2xx throws HttpException). */
    suspend fun <T> call(delaysMs: List<Long> = listOf(800L, 2_000L), block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val busy = (e is retrofit2.HttpException && isBusy(e.code())) || e is java.io.IOException
                if (!busy || attempt >= delaysMs.size) throw e
                kotlinx.coroutines.delay(delaysMs[attempt++])
            }
        }
    }

    /** For calls that return a Response (busy codes come back as a Response). */
    suspend fun <T> response(
        delaysMs: List<Long> = listOf(800L, 2_000L),
        block: suspend () -> retrofit2.Response<T>,
    ): retrofit2.Response<T> {
        var attempt = 0
        while (true) {
            val res = call(delaysMs) { block() }
            if (!isBusy(res.code()) || attempt >= delaysMs.size) return res
            kotlinx.coroutines.delay(delaysMs[attempt++])
        }
    }
}
