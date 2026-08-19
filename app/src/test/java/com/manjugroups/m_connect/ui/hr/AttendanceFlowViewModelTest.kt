package com.manjugroups.m_connect.ui.hr

import com.manjugroups.m_connect.ui.hr.AttendanceFlowViewModel.PendingPunchLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceFlowViewModelTest {

    // --- Offline pending-punch overlay (the offline-display fix) ---------------
    // These lock in the behavior that makes an OFFLINE clock-in show as present
    // instead of "absent / not clocked in", and that a stale queue never
    // overrides fresher server truth.

    private fun merge(base: AttendanceFlowState, vararg pending: PendingPunchLite) =
        AttendanceFlowViewModel.mergePendingPunch(base, pending.toList())

    @Test
    fun mergePendingPunch_noQueue_leavesStateUntouched() {
        val base = AttendanceFlowState(hasLoaded = true, isClockedIn = false)
        assertEquals(base, AttendanceFlowViewModel.mergePendingPunch(base, emptyList()))
    }

    @Test
    fun mergePendingPunch_offlineClockInOnEmptyState_readsAsClockedIn() {
        // Cold start while offline: server gave nothing (empty default), but a
        // punch-in is sitting in the queue. It must read as clocked-in/present.
        val base = AttendanceFlowState() // all defaults: not clocked in
        val merged = merge(base, PendingPunchLite(isPunchIn = true, "2026-08-19T09:05:00.000+05:30"))

        assertTrue("queued punch-in should mark today clocked-in", merged.isClockedIn)
        assertTrue(merged.hasClockedInToday)
        assertFalse(merged.clockedOutOnMobile)
        assertTrue(merged.hasLoaded)
        assertEquals("2026-08-19T09:05:00.000+05:30", merged.firstPunchInIso)
    }

    @Test
    fun mergePendingPunch_queuedPunchOutAfterServerPunchIn_readsAsClockedOut() {
        val base = AttendanceFlowState(
            hasLoaded = true,
            isClockedIn = true,
            hasClockedInToday = true,
            firstPunchInIso = "2026-08-19T09:00:00.000+05:30",
        )
        val merged = merge(base, PendingPunchLite(isPunchIn = false, "2026-08-19T18:00:00.000+05:30"))

        assertFalse(merged.isClockedIn)
        assertTrue(merged.clockedOutOnMobile)
        assertTrue("having punched in today stays sticky", merged.hasClockedInToday)
        // The earlier server punch-in time is preserved, not overwritten.
        assertEquals("2026-08-19T09:00:00.000+05:30", merged.firstPunchInIso)
    }

    @Test
    fun mergePendingPunch_staleQueueOlderThanServer_doesNotOverrideLiveState() {
        // Server already knows a later punch-out; a stale queued punch-in from
        // earlier must NOT flip the live "clocked out" state back on.
        val base = AttendanceFlowState(
            hasLoaded = true,
            isClockedIn = false,
            clockedOutOnMobile = true,
            hasClockedInToday = true,
            firstPunchInIso = "2026-08-19T09:00:00.000+05:30",
            lastPunchOutIso = "2026-08-19T18:00:00.000+05:30",
        )
        val merged = merge(base, PendingPunchLite(isPunchIn = true, "2026-08-19T08:00:00.000+05:30"))

        assertFalse("stale queued punch-in must not re-open the session", merged.isClockedIn)
        assertTrue(merged.clockedOutOnMobile)
    }

    @Test
    fun mergePendingPunch_inThenOutSameDay_latestEventWins() {
        val base = AttendanceFlowState()
        val merged = merge(
            base,
            PendingPunchLite(isPunchIn = true, "2026-08-19T09:00:00.000+05:30"),
            PendingPunchLite(isPunchIn = false, "2026-08-19T17:30:00.000+05:30"),
        )

        assertFalse(merged.isClockedIn)
        assertTrue(merged.clockedOutOnMobile)
        assertTrue(merged.hasClockedInToday)
    }


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
