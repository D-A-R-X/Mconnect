package com.manjugroups.m_connect.auth

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide signal that the saved session token is no longer valid
 * server-side — an authenticated 401 from the MMS API, which can happen
 * when:
 *
 *   - The token expired naturally
 *   - HR / admin revoked the session from the web
 *   - The build was pointed at a different Convex deployment (dev
 *     vs prod) where the token was never minted
 *
 * Producers (network interceptor) emit on this flow; consumers
 * (MainActivity, splash) collect and force a re-login flow. This
 * avoids leaving the app in a "everything 401s" stuck state where
 * the user has no way back to a working state short of force-stop +
 * relaunch.
 *
 * The flow uses [MutableSharedFlow] with replay=0 — a 401 that fires
 * while no activity is listening is dropped (no point queuing a stale
 * logout signal for a future cold start; the next start re-validates
 * the session anyway).
 */
object SessionInvalidationBus {
    private val _signals = MutableSharedFlow<SessionInvalidationSignal>(extraBufferCapacity = 1)
    val signals: SharedFlow<SessionInvalidationSignal> = _signals.asSharedFlow()

    /**
     * Called by the OkHttp interceptor on a 401 response. Non-blocking;
     * if nothing is listening the emit is dropped silently.
     */
    fun reportUnauthorized(authorizationHeader: String?) {
        val failedToken = SessionInvalidationToken.extract(authorizationHeader) ?: return
        _signals.tryEmit(SessionInvalidationSignal(failedToken))
    }
}

class SessionInvalidationSignal internal constructor(
    private val failedToken: String,
) {
    fun matchesCurrentToken(currentToken: String?): Boolean =
        !currentToken.isNullOrBlank() && failedToken == currentToken
}

internal object SessionInvalidationToken {
    fun extract(authorizationHeader: String?): String? {
        val parts = authorizationHeader?.trim()?.split(Regex("\\s+"), limit = 2)
            ?: return null
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        return parts[1].trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * Prevents public login routes and secondary services from revoking the MMS
 * session. Only the host that issued the bearer token is authoritative.
 */
internal object SessionInvalidationPolicy {
    fun shouldInvalidate(
        responseCode: Int,
        authorizationHeader: String?,
        requestHost: String,
        sessionAuthorityHost: String,
        requestPath: String = "",
        responseBody: String? = null,
    ): Boolean {
        if (responseCode != 401 || authorizationHeader.isNullOrBlank()) return false
        if (!requestHost.equals(sessionAuthorityHost, ignoreCase = true)) return false

        val token = SessionInvalidationToken.extract(authorizationHeader) ?: return false
        if (AuthBypass.isBypassToken(token)) return false

        if (requestPath.trimEnd('/').equals("/api/auth/validate-session", ignoreCase = true)) {
            return true
        }
        return SessionInvalidationResponse.isTerminal(responseBody)
    }
}

/** Distinguishes an expired/revoked session from an endpoint-specific 401. */
internal object SessionInvalidationResponse {
    private val terminalCodes = setOf(
        "invalid_session",
        "session_invalid",
        "session_expired",
        "session_revoked",
        "session_inactive",
        "authentication_required",
    )

    private val terminalPhrases = listOf(
        "invalid or expired session",
        "session expired",
        "invalid session",
        "session is invalid",
        "session revoked",
        "session has been revoked",
        "session inactive",
        "session is inactive",
        "signed in on another device",
        "authorization header with bearer token is required",
        "not authenticated",
    )

    fun isTerminal(responseBody: String?): Boolean {
        if (responseBody.isNullOrBlank()) return false
        val values = runCatching {
            buildList { collectRelevantValues(JsonParser.parseString(responseBody), this) }
        }.getOrElse { listOf(responseBody) }

        return values.any { raw ->
            val normalized = raw.trim().lowercase()
            val code = normalized.replace('-', '_').replace(' ', '_')
            code in terminalCodes || terminalPhrases.any(normalized::contains)
        }
    }

    private fun collectRelevantValues(element: JsonElement, values: MutableList<String>) {
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                if (key.equals("error", true) || key.equals("message", true) ||
                    key.equals("reason", true) || key.equals("code", true)
                ) {
                    if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                        values += value.asString
                    } else if (value.isJsonObject || value.isJsonArray) {
                        collectRelevantValues(value, values)
                    }
                } else if (value.isJsonObject) {
                    collectRelevantValues(value, values)
                }
            }
            element.isJsonArray -> element.asJsonArray.forEach { collectRelevantValues(it, values) }
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> values += element.asString
        }
    }
}
