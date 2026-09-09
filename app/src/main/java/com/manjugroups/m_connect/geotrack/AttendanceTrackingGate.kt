package com.manjugroups.m_connect.geotrack

import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.SessionData
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Decides whether the staff member counts as "clocked in for today" so trip
 * starts and GeoTrack tracking are allowed.
 *
 * The check is **source-agnostic** — any session in `staffAttendance.sessions`
 * with `source = mobile | biometric | manual | csv-import` will produce a
 * non-blank `firstPunchIn` on the server, and that's all we require. So a
 * biometric punch at the office gate enables trip starts and tracking exactly
 * the same way as an in-app punch from the Home tab.
 *
 *  - `hasOpenSession == true`: there's an in-progress session right now (no
 *    punch-out yet) — definitely clocked in.
 *  - `firstPunchIn != null` (and session may have closed): they punched in
 *    today, even if it was via biometric. We still want trips/tracking to
 *    work as long as the day is open.
 */
object AttendanceTrackingGate {
    /** A cold service start must never rely on stale local tracking state. */
    fun mayStartTracking(openSession: Boolean?): Boolean = openSession == true

    /** Once verified and running, a temporary outage must not break the route. */
    fun mayContinueTracking(openSession: Boolean?): Boolean = openSession != false

    /**
     * Resolve the live attendance state from both representations returned by
     * the attendance APIs. Some responses contain the canonical open session
     * row before their denormalized `hasOpenSession` flag catches up.
     */
    fun hasOpenSession(
        hasOpenSession: Boolean?,
        sessions: List<SessionData>?,
    ): Boolean {
        return hasOpenSession == true || sessions.orEmpty().any { session ->
            !session.punchInTime.isNullOrBlank() && session.punchOutTime.isNullOrBlank()
        }
    }

    fun isClockedInForToday(
        firstPunchIn: String?,
        hasOpenSession: Boolean,
    ): Boolean {
        return hasOpenSession || !firstPunchIn.isNullOrBlank()
    }

    /**
     * Resolve the attendance state that the mobile app should use for CP/SV
     * starts and GeoTrack. A biometric punch-out closes the raw attendance
     * pair, but the backend deliberately keeps the mobile work session alive;
     * only an explicit mobile Clock Out ends it before day finalization.
     */
    fun isMobileWorkSessionActive(
        firstPunchIn: String?,
        hasOpenSession: Boolean?,
        sessions: List<SessionData>?,
    ): Boolean {
        if (hasOpenSession(hasOpenSession, sessions)) return true
        if (firstPunchIn.isNullOrBlank()) return false
        return !wasClockedOutOnMobile(sessions.orEmpty())
    }

    /** True when the newest relevant attendance event is a mobile Clock Out. */
    fun wasClockedOutOnMobile(sessions: List<SessionData>): Boolean {
        var latestMobileOutMs: Long? = null
        var latestOtherActivityMs: Long? = null

        sessions.forEach { session ->
            session.punchInTime?.takeIf { it.isNotBlank() }?.let { iso ->
                parseMillis(iso)?.let { timestamp ->
                    if (latestOtherActivityMs == null || timestamp > latestOtherActivityMs!!) {
                        latestOtherActivityMs = timestamp
                    }
                }
            }
            session.punchOutTime?.takeIf { it.isNotBlank() }?.let { iso ->
                parseMillis(iso)?.let { timestamp ->
                    if (session.punchOutSource.equals("mobile", ignoreCase = true)) {
                        if (latestMobileOutMs == null || timestamp > latestMobileOutMs!!) {
                            latestMobileOutMs = timestamp
                        }
                    } else if (latestOtherActivityMs == null || timestamp > latestOtherActivityMs!!) {
                        // Missing punchOutSource is intentionally treated as
                        // non-mobile for legacy biometric rows.
                        latestOtherActivityMs = timestamp
                    }
                }
            }
        }

        val mobileOut = latestMobileOutMs ?: return false
        return latestOtherActivityMs == null || mobileOut >= latestOtherActivityMs!!
    }

    suspend fun isClockedInForToday(
        token: String,
        api: ApiService = ApiService.create(),
    ): Boolean {
        val todayResp = runCatching { api.getMyAttendanceToday(token) }.getOrNull()
        val attendance = if (todayResp?.success == true) todayResp.attendance else null
        val dayResp = runCatching { api.getDaySessions(token) }.getOrNull()
        // Prefer day-sessions because it always returns the canonical
        // `firstPunchIn` even when the very first session was biometric and
        // already closed (e.g. someone punched in then out at the gate).
        val firstPunchIn = dayResp?.firstPunchIn?.takeIf { it.isNotBlank() }
            ?: attendance?.firstPunchIn?.takeIf { !it.isNullOrBlank() }
        val hasOpenSession = hasOpenSession(
            attendance?.hasOpenSession,
            attendance?.sessions,
        ) || hasOpenSession(
            dayResp?.hasOpenSession,
            dayResp?.sessions,
        )
        return isClockedInForToday(firstPunchIn, hasOpenSession)
    }

    /**
     * Live "is a session open RIGHT NOW?" check, used **only** to bound
     * GeoTrack location collection to the clock-in → clock-out window.
     *
     * Biometric out-events close their raw attendance pair but do not end the
     * mobile work session on the backend. An explicit mobile Clock Out does.
     *
     * Returns:
     *  - `true`  → the mobile work session is active; keep tracking.
     *  - `false` → mobile clocked out, or not punched in yet; stop.
     *  - `null`  → couldn't determine (network/server error). Callers must NOT
     *    stop tracking on null — doing so would drop a legitimate in-window
     *    journey during a transient outage. Buffered points sync later.
     */
    suspend fun hasOpenSessionNow(
        token: String,
        api: ApiService = ApiService.create(),
    ): Boolean? {
        if (token.isBlank()) return null
        val todayResp = runCatching { api.getMyAttendanceToday(token) }.getOrNull()
        val dayResp = runCatching { api.getDaySessions(token) }.getOrNull()
        val todayOk = todayResp?.success == true
        val dayOk = dayResp?.success == true
        // Neither endpoint answered authoritatively → unknown, don't act.
        if (!todayOk && !dayOk) return null
        val attendance = todayResp?.attendance.takeIf { todayOk }
        val validDayResp = dayResp.takeIf { dayOk }
        val sessions = validDayResp?.sessions?.takeIf { it.isNotEmpty() }
            ?: attendance?.sessions
        val rawOpenSession = hasOpenSession(
            attendance?.hasOpenSession,
            attendance?.sessions,
        ) || hasOpenSession(
            validDayResp?.hasOpenSession,
            validDayResp?.sessions,
        )
        val firstPunchIn = validDayResp?.firstPunchIn?.takeIf { it.isNotBlank() }
            ?: attendance?.firstPunchIn
        return isMobileWorkSessionActive(firstPunchIn, rawOpenSession, sessions)
    }

    private fun parseMillis(iso: String): Long? {
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(iso)?.time
            } catch (_: Exception) {
                // Try the next server timestamp shape.
            }
        }
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(iso.substringBefore('Z'))?.time
        } catch (_: Exception) {
            null
        }
    }
}
