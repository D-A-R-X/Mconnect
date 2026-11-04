package com.manjugroups.m_connect.ui.hr

import com.manjugroups.m_connect.network.SessionData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceSessionsTest {
    private fun session(out: String?, auto: Boolean? = null) = SessionData(
        punchInTime = "2026-10-16T05:19:00.000Z",
        punchOutTime = out,
        source = "biometric",
        autoClosedAtDayEnd = auto,
    )

    @Test fun midnightCloseIsNotARealPunch() {
        // 23:59:59 IST on the day, as the midnight job writes it (16 Oct report).
        val legacy = session("2026-10-16T18:29:59.000Z")
        assertTrue(AttendanceSessions.isSyntheticClose(legacy, "2026-10-16"))
        assertNull(AttendanceSessions.realPunchOut(legacy, "2026-10-16"))

        val flagged = session("2026-10-16T18:29:00.000Z", auto = true)
        assertTrue(AttendanceSessions.isSyntheticClose(flagged, null))
        assertNull(AttendanceSessions.realPunchOut(flagged, "2026-10-16"))
    }

    @Test fun realPunchOutsAreKept() {
        val real = session("2026-10-16T05:12:00.000Z")
        assertFalse(AttendanceSessions.isSyntheticClose(real, "2026-10-16"))
        assertEquals("2026-10-16T05:12:00.000Z", AttendanceSessions.realPunchOut(real, "2026-10-16"))
        // 23:59:59 IST of a different day is not this day's midnight close.
        assertFalse(AttendanceSessions.isSyntheticClose(session("2026-10-17T18:29:59.000Z"), "2026-10-16"))
        // Open session: nothing to hide.
        assertFalse(AttendanceSessions.isSyntheticClose(session(null), "2026-10-16"))
        assertFalse(AttendanceSessions.isSyntheticClose(session(""), "2026-10-16"))
    }
}
