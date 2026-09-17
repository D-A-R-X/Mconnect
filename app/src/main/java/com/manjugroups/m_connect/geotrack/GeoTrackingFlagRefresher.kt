package com.manjugroups.m_connect.geotrack

import android.content.Context
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import kotlinx.coroutines.CancellationException

/**
 * Keeps [SessionManager.geoTrackingEnabled] in step with the server.
 *
 * The flag used to be written only at login. Staff whose GeoTrack was switched
 * on after they signed in (or whose login response said false) stayed "off" on
 * the phone forever: [GeoTrackBootstrapSync] saw the stale false, ended the
 * session and never started tracking, so the web showed their punch-in location
 * but never Online or a route — until they logged out and back in.
 *
 * `/api/auth/validate-session` returns the same presented user as login,
 * including the current `geoTrackingEnabled`.
 */
object GeoTrackingFlagRefresher {
    private const val MIN_INTERVAL_MS = 2 * 60_000L

    @Volatile private var lastAttemptAt = 0L

    /**
     * The flag to store, or null to leave it unchanged. Only an authoritative
     * success with an explicit value may change it: a failed call or a payload
     * without the field must never switch tracking off.
     */
    internal fun resolve(success: Boolean, serverValue: Boolean?): Boolean? =
        if (success) serverValue else null

    /** @return true when the stored flag changed. */
    suspend fun refresh(
        context: Context,
        force: Boolean = false,
        api: ApiService = ApiService.create(),
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val session = SessionManager(context.applicationContext)
        if (!session.isLoggedIn || session.bearerToken.isBlank()) return false
        // External fleet agencies have no staff record and never track.
        if (session.isExternalFleetAgencyOperator) return false
        if (!force && now - lastAttemptAt < MIN_INTERVAL_MS) return false
        lastAttemptAt = now

        val response = try {
            api.validateSessionFlags(session.bearerToken)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        val next = resolve(response.success, response.user?.geoTrackingEnabled) ?: return false
        if (next == session.geoTrackingEnabled) return false
        session.geoTrackingEnabled = next
        return true
    }
}
