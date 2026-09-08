package com.manjugroups.m_connect.geotrack

import com.manjugroups.m_connect.network.SessionData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceTrackingGateTest {
    @Test
    fun `cold tracking start requires authoritative open session`() {
        assertTrue(AttendanceTrackingGate.mayStartTracking(true))
        assertFalse(AttendanceTrackingGate.mayStartTracking(false))
        assertFalse(AttendanceTrackingGate.mayStartTracking(null))
    }

    @Test
    fun `verified running tracking survives only unknown attendance`() {
        assertTrue(AttendanceTrackingGate.mayContinueTracking(true))
        assertTrue(AttendanceTrackingGate.mayContinueTracking(null))
        assertFalse(AttendanceTrackingGate.mayContinueTracking(false))
    }

    @Test
    fun `open summary flag allows trip start`() {
        assertTrue(AttendanceTrackingGate.hasOpenSession(true, emptyList()))
    }

    @Test
    fun `open session row overrides stale false summary`() {
        val sessions = listOf(
            SessionData(
                punchInTime = "2026-09-01T09:00:00.000Z",
                punchOutTime = null,
                source = "biometric",
            ),
        )

        assertTrue(AttendanceTrackingGate.hasOpenSession(false, sessions))
    }

    @Test
    fun `blank punch out also represents an open session`() {
        val sessions = listOf(
            SessionData(
                punchInTime = "2026-09-01T09:00:00.000Z",
                punchOutTime = " ",
                source = "mobile",
            ),
        )

        assertTrue(AttendanceTrackingGate.hasOpenSession(null, sessions))
    }

    @Test
    fun `closed sessions do not allow a new trip`() {
        val sessions = listOf(
            SessionData(
                punchInTime = "2026-09-01T09:00:00.000Z",
                punchOutTime = "2026-09-01T18:00:00.000Z",
                source = "mobile",
            ),
        )

        assertFalse(AttendanceTrackingGate.hasOpenSession(false, sessions))
    }

    @Test
    fun `missing punch in cannot create an open session`() {
        val sessions = listOf(
            SessionData(
                punchInTime = null,
                punchOutTime = null,
                source = "mobile",
            ),
        )

        assertFalse(AttendanceTrackingGate.hasOpenSession(null, sessions))
    }

    @Test
    fun `biometric punch out keeps mobile work session active`() {
        val sessions = listOf(
            SessionData(
                punchInTime = "2026-09-08T09:00:00.000+05:30",
                punchOutTime = "2026-09-08T13:00:00.000+05:30",
                source = "biometric",
                punchOutSource = "biometric",
            ),
        )

        assertTrue(
            AttendanceTrackingGate.isMobileWorkSessionActive(
                firstPunchIn = sessions.first().punchInTime,
                hasOpenSession = false,
                sessions = sessions,
            ),
        )
    }

    @Test
    fun `explicit mobile clock out ends mobile work session`() {
        val sessions = listOf(
            SessionData(
                punchInTime = "2026-09-08T09:00:00.000+05:30",
                punchOutTime = "2026-09-08T18:00:00.000+05:30",
                source = "biometric",
                punchOutSource = "mobile",
            ),
        )

        assertFalse(
            AttendanceTrackingGate.isMobileWorkSessionActive(
                firstPunchIn = sessions.first().punchInTime,
                hasOpenSession = false,
                sessions = sessions,
            ),
        )
    }

    @Test
    fun `legacy biometric close without out source remains active`() {
        val sessions = listOf(
            SessionData(
                punchInTime = "2026-09-08T09:00:00.000+05:30",
                punchOutTime = "2026-09-08T13:00:00.000+05:30",
                source = "mobile",
                punchOutSource = null,
            ),
        )

        assertTrue(
            AttendanceTrackingGate.isMobileWorkSessionActive(
                firstPunchIn = sessions.first().punchInTime,
                hasOpenSession = false,
                sessions = sessions,
            ),
        )
    }

    @Test
    fun `later punch in reopens after mobile clock out`() {
        val sessions = listOf(
            SessionData(
                punchInTime = "2026-09-08T09:00:00.000+05:30",
                punchOutTime = "2026-09-08T12:00:00.000+05:30",
                source = "mobile",
                punchOutSource = "mobile",
            ),
            SessionData(
                punchInTime = "2026-09-08T13:00:00.000+05:30",
                punchOutTime = null,
                source = "biometric",
            ),
        )

        assertTrue(
            AttendanceTrackingGate.isMobileWorkSessionActive(
                firstPunchIn = sessions.first().punchInTime,
                hasOpenSession = true,
                sessions = sessions,
            ),
        )
    }
}
