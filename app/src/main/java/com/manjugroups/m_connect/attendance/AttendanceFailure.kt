package com.manjugroups.m_connect.attendance

import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.stream.MalformedJsonException
import com.manjugroups.m_connect.network.StorageUploader
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

internal sealed interface AttendanceFailure {
    data object Offline : AttendanceFailure
    data class Rejected(val message: String) : AttendanceFailure

    /**
     * The punch the staff tried is already in place on the server: they
     * clocked in (or out) elsewhere while this screen was open, e.g. another
     * tap, the gate, or another device. Not an error; the screen just catches up.
     */
    enum class AlreadyRecorded(val clockedIn: Boolean, val message: String) {
        CLOCKED_IN(true, "You're already clocked in. Your attendance session is active."),
        CLOCKED_OUT(false, "You're already clocked out. No active punch-in found for today."),
    }

    companion object {
        const val UNCONFIRMED = "Could not confirm your punch. Check today's attendance before trying again. Contact HR if it is still missing."

        /** The server's (or our own) message for a punch that is already recorded, if it is one. */
        fun alreadyRecorded(text: String?): AlreadyRecorded? = when {
            text.isNullOrBlank() -> null
            alreadyClockedIn.containsMatchIn(text) -> AlreadyRecorded.CLOCKED_IN
            alreadyClockedOut.containsMatchIn(text) -> AlreadyRecorded.CLOCKED_OUT
            else -> null
        }

        fun uploadFailure(result: StorageUploader.Result): AttendanceFailure =
            result.failureCause?.let(::route)
                ?: if (result.isNetworkError) Offline else Rejected(serverMessage(result.errorMessage))

        fun route(error: Throwable): AttendanceFailure {
            val causes = generateSequence(error) { it.cause }.take(16).toList()
            causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
            val notConnectivity = causes.any {
                it is HttpException || it is JsonParseException || it is MalformedJsonException ||
                    it is EOFException || it is ProtocolException || it is SSLException ||
                    it is FileNotFoundException
            }
            if (!notConnectivity && causes.any { it is IOException } &&
                causes.none { it.message?.trim()?.equals("Canceled", ignoreCase = true) == true ||
                    it.message?.trim()?.equals("Cancelled", ignoreCase = true) == true }
            ) {
                return Offline
            }
            val http = causes.filterIsInstance<HttpException>().firstOrNull()
            val detail = http?.let {
                val body = runCatching { it.response()?.errorBody()?.string() }.getOrNull()
                body?.let { raw -> runCatching { JsonParser.parseString(raw) }.getOrNull() }
                    ?.takeIf { json -> json.isJsonObject }?.asJsonObject
                    ?.let { json -> text(json.get("message")) ?: text(json.get("error")) }
            }
            return Rejected(serverMessage(detail, http?.code()))
        }

        fun serverMessage(detail: String?, status: Int? = null): String {
            val message = detail?.trim()?.takeIf { it.isNotEmpty() && it.length <= 400 }
            if (message != null && !diagnostic.containsMatchIn(message)) return message
            // The backend wraps these in "Uncaught Error: ... at ..." (HTTP 500);
            // keep their meaning instead of the generic unconfirmed text.
            alreadyRecorded(detail?.take(2_000))?.let { return it.message }
            return when (status) {
                401 -> "Your session expired. Please sign in again."
                403 -> "You are not allowed to record this punch. Please contact HR."
                else -> UNCONFIRMED
            }
        }

        private val alreadyClockedIn = Regex(
            "already (?:punched|clocked) in|punch out first",
            RegexOption.IGNORE_CASE,
        )
        private val alreadyClockedOut = Regex(
            "no active punch-?in|already (?:punched|clocked) out",
            RegexOption.IGNORE_CASE,
        )

        private val diagnostic = Regex(
            "Uncaught|\\b\\w*(?:Error|Exception)\\b|is not defined|not a function|" +
                "Cannot read properties|Traceback|\\[CONVEX|<!doctype|<html|" +
                "(?m)^\\s*at\\s|\\.(?:kt|java|ts|js):\\d+",
            RegexOption.IGNORE_CASE,
        )

        private fun text(value: JsonElement?): String? {
            if (value == null || value.isJsonNull) return null
            if (value.isJsonObject) return text(value.asJsonObject.get("message"))
            return value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}

internal class AttendanceSubmissionGate {
    private val active = AtomicBoolean(false)
    val isActive: Boolean get() = active.get()
    fun tryBegin(): Boolean = active.compareAndSet(false, true)
    fun finish() { active.set(false) }
}

internal fun canSubmitAttendance(hasPhoto: Boolean, hasLocation: Boolean, isSubmitting: Boolean): Boolean =
    hasPhoto && hasLocation && !isSubmitting
