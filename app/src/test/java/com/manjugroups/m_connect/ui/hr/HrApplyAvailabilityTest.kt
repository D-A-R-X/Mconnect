package com.manjugroups.m_connect.ui.hr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HrApplyAvailabilityTest {

    // --- Permission ------------------------------------------------------------

    @Test
    fun permission_notLoaded_staysAvailable() {
        assertTrue(HrApplyAvailability.permission(false, 0.0, 0).available)
    }

    @Test
    fun permission_unknownNumbers_staysAvailable() {
        assertTrue(HrApplyAvailability.permission(true, null, null).available)
    }

    @Test
    fun permission_hoursLeft_available() {
        assertTrue(HrApplyAvailability.permission(true, 1.5, 2).available)
    }

    @Test
    fun permission_noHoursLeft_unavailableWithHoursReason() {
        val s = HrApplyAvailability.permission(true, 0.0, 2)
        assertFalse(s.available)
        assertEquals(HrApplyAvailability.REASON_PERMISSION_HOURS, s.reason)
    }

    @Test
    fun permission_noRequestsLeft_unavailableWithRequestsReason() {
        val s = HrApplyAvailability.permission(true, 1.0, 0)
        assertFalse(s.available)
        assertEquals(HrApplyAvailability.REASON_PERMISSION_REQUESTS, s.reason)
    }

    // --- Leave (web-configured unpaid limit) -----------------------------------

    @Test
    fun leave_notLoaded_staysAvailable() {
        assertTrue(HrApplyAvailability.leave(false, 2.0, 0.0).available)
    }

    @Test
    fun leave_noLimitOrOldServer_staysAvailable() {
        // null limit = unlimited; missing fields (old server) arrive as null too.
        assertTrue(HrApplyAvailability.leave(true, null, null).available)
        assertTrue(HrApplyAvailability.leave(true, null, 0.0).available)
    }

    @Test
    fun leave_limitButUnknownRemaining_staysAvailable() {
        assertTrue(HrApplyAvailability.leave(true, 2.0, null).available)
    }

    @Test
    fun leave_daysLeft_available() {
        assertTrue(HrApplyAvailability.leave(true, 2.0, 0.5).available)
    }

    @Test
    fun leave_limitUsedUp_unavailableWithReason() {
        val s = HrApplyAvailability.leave(true, 2.0, 0.0)
        assertFalse(s.available)
        assertEquals(HrApplyAvailability.REASON_LEAVE, s.reason)
    }

    // --- Comp Off ----------------------------------------------------------------

    @Test
    fun compOff_unavailableOnlyWhenLoadedAndEmpty() {
        assertTrue(HrApplyAvailability.compOffAvailable(creditsLoaded = false, creditCount = 0))
        assertTrue(HrApplyAvailability.compOffAvailable(creditsLoaded = true, creditCount = 2))
        assertFalse(HrApplyAvailability.compOffAvailable(creditsLoaded = true, creditCount = 0))
    }
}
