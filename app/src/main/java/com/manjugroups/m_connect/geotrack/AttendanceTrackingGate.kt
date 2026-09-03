package com.manjugroups.m_connect.geotrack

import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.SessionData

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
     * This is deliberately stricter than [isClockedInForToday]: that gate
     * stays true for the rest of the day after the first punch (so trips / CP
     * cards keep working through a mid-day break), but GeoTrack must capture
     * a staffer's travel ONLY between punch-in and punch-out. So here we look
     * at the open-session flag alone — a closed day (clocked out) returns
     * false even though they punched in earlier.
     *
     * Returns:
     *  - `true`  → an open session exists; keep tracking.
     *  - `false` → no open session (clocked out, or not punched in yet); stop.
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
        return hasOpenSession(
            todayResp?.attendance?.hasOpenSession,
            todayResp?.attendance?.sessions,
        ) || hasOpenSession(
            dayResp?.hasOpenSession,
            dayResp?.sessions,
        )
    }
}
