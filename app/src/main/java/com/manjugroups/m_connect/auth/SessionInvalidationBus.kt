package com.manjugroups.m_connect.auth

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
    private val _signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val signals: SharedFlow<Unit> = _signals.asSharedFlow()

    /**
     * Called by the OkHttp interceptor on a 401 response. Non-blocking;
     * if nothing is listening the emit is dropped silently.
     */
    fun reportUnauthorized() {
        _signals.tryEmit(Unit)
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
    ): Boolean {
        if (responseCode != 401 || authorizationHeader.isNullOrBlank()) return false
        if (!requestHost.equals(sessionAuthorityHost, ignoreCase = true)) return false

        val token = authorizationHeader.removePrefix("Bearer ").trim()
        return token.isNotEmpty() && !AuthBypass.isBypassToken(token)
    }
}
