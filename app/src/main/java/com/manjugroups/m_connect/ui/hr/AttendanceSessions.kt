package com.manjugroups.m_connect.ui.hr

import com.manjugroups.m_connect.network.SessionData

/**
 * A session left open is closed by the midnight job at 23:59:59 IST. That
 * close is not a punch anybody made, so it must not appear as "11:59 pm Punch
 * Out" or stretch the day's range. Same rule as the web (isSyntheticClose in
 * convex/lib/attendanceTime.ts): flagged autoClosedAtDayEnd, or exactly
 * `<date>T18:29:59.000Z` on older rows.
 */
internal object AttendanceSessions {
    const val AUTO_CLOSED_NOTE =
        "No punch-out recorded. The session was automatically closed at the end of the day."

    fun isSyntheticClose(session: SessionData, date: String?): Boolean {
        val out = session.punchOutTime?.takeIf { it.isNotBlank() } ?: return false
        return session.autoClosedAtDayEnd == true ||
            (!date.isNullOrBlank() && out == "${date}T18:29:59.000Z")
    }

    /** The session's punch-out, unless it is the midnight job's close. */
    fun realPunchOut(session: SessionData, date: String?): String? =
        session.punchOutTime?.takeIf { it.isNotBlank() && !isSyntheticClose(session, date) }
}
