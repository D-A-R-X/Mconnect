package com.manjugroups.m_connect.geotrack

import com.manjugroups.m_connect.network.ApiService

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
        val hasOpenSession = attendance?.hasOpenSession == true ||
            dayResp?.hasOpenSession == true
        return isClockedInForToday(firstPunchIn, hasOpenSession)
    }
}
