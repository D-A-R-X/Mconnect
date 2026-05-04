package com.manjugroups.m_connect.ui.hr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceFlowViewModelTest {

    @Test
    fun formatMinutesForToday_returnsExpectedClockFormat() {
        val formatted = AttendanceFlowViewModel.formatMinutesForToday(135)
        assertEquals("02:15 Hrs", formatted)
    }

    @Test
    fun formatMinutesForPeriod_returnsExpectedPeriodFormat() {
        val formatted = AttendanceFlowViewModel.formatMinutesForPeriod(485)
        assertEquals("08:05:00 hrs", formatted)
    }

    @Test
    fun buildRangeLabel_withoutPunches_returnsFallback() {
        val label = AttendanceFlowViewModel.buildRangeLabel(
            firstPunchIn = null,
            lastPunchOut = null,
            hasOpenSession = false,
        )
        assertEquals("-- - --", label)
    }

    @Test
    fun buildRangeLabel_openSession_showsOpenEndedRange() {
        val label = AttendanceFlowViewModel.buildRangeLabel(
            firstPunchIn = "2026-04-24T09:00:00+05:30",
            lastPunchOut = null,
            hasOpenSession = true,
        )
        assertTrue(label.endsWith(" - --"))
    }

    @Test
    fun shouldTreatAsClockedIn_firstPunchWithoutPunchOut_returnsTrue() {
        val isClockedIn = AttendanceFlowViewModel.shouldTreatAsClockedIn(
            firstPunchIn = "2026-04-24T11:23:00+05:30",
            hasOpenSession = false,
        )

        assertTrue(isClockedIn)
    }

    @Test
    fun shouldTreatAsClockedIn_firstPunchWithIntermediatePunchOut_returnsTrue() {
        val isClockedIn = AttendanceFlowViewModel.shouldTreatAsClockedIn(
            firstPunchIn = "2026-04-24T11:23:00+05:30",
            hasOpenSession = false,
        )

        assertTrue(isClockedIn)
    }

    @Test
    fun shouldTreatAsClockedIn_withoutFirstPunchOrOpenSession_returnsFalse() {
        val isClockedIn = AttendanceFlowViewModel.shouldTreatAsClockedIn(
            firstPunchIn = null,
            hasOpenSession = false,
        )

        assertEquals(false, isClockedIn)
    }
}
