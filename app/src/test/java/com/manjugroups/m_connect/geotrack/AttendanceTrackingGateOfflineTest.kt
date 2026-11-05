package com.manjugroups.m_connect.geotrack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttendanceTrackingGateOfflineTest {
    private val today = "2026-11-05"

    @Test fun anOpenSessionAlwaysStarts() {
        assertTrue(AttendanceTrackingGate.mayStartTrackingOffline(true, false, null, today))
    }

    @Test fun offlineResumesOnlyTodaysConfirmedShift() {
        // No network, still clocked in on this phone, confirmed today: keep recording.
        assertTrue(AttendanceTrackingGate.mayStartTrackingOffline(null, true, today, today))
        // Confirmed yesterday: a new day never starts from local state (no midnight tracking).
        assertFalse(AttendanceTrackingGate.mayStartTrackingOffline(null, true, "2026-11-04", today))
        // Clocked out on this phone (tracking flag cleared).
        assertFalse(AttendanceTrackingGate.mayStartTrackingOffline(null, false, today, today))
        // Never confirmed.
        assertFalse(AttendanceTrackingGate.mayStartTrackingOffline(null, true, null, today))
    }

    @Test fun anExplicitCloseNeverStarts() {
        assertFalse(AttendanceTrackingGate.mayStartTrackingOffline(false, true, today, today))
    }
}
