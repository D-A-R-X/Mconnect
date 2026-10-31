package com.manjugroups.m_connect.ui.hr

import com.manjugroups.m_connect.network.TodayShift
import com.manjugroups.m_connect.network.TodayShiftDay
import com.manjugroups.m_connect.network.TodayShiftResponse
import com.manjugroups.m_connect.network.TodayShiftSchedule
import org.junit.Assert.*
import org.junit.Test

class PermissionShiftTimingTest {
    private val shift = PermissionShiftTiming("10:00", "19:00")

    @Test fun morningBoundaryIsAccepted() { assertNull(shift.error("10:00", "11:00")) }
    @Test fun eveningBoundaryIsAccepted() { assertNull(shift.error("18:00", "19:00")) }
    @Test fun midShiftIsRejected() { assertNotNull(shift.error("12:00", "13:00")) }
    @Test fun outsideShiftIsRejected() {
        assertNotNull(shift.error("10:00", "23:29"))
        assertNotNull(shift.error("09:00", "19:00"))
    }
    @Test fun reversedAndMalformedTimesAreRejected() {
        assertNotNull(shift.error("19:00", "10:00"))
        assertNotNull(shift.error("25:00", "19:00"))
    }
    @Test fun weekoffIsRejected() { assertNotNull(shift.copy(isWorkDay = false).error(null, null)) }
    @Test fun assignedSelectedWeekdayWinsOverDefault() {
        val response = TodayShiftResponse(success = true, date = "2026-10-28", shift = TodayShift(
            schedule = TodayShiftSchedule(wednesday = TodayShiftDay(true, "10:00", "19:00")),
        ))
        assertEquals(shift, PermissionShiftTiming.resolve(response, "2026-10-28"))
    }
    @Test fun defaultsAreOnlyUsedAfterSuccessfulLookup() {
        assertEquals(PermissionShiftTiming("09:30", "18:30"), PermissionShiftTiming.resolve(TodayShiftResponse(success = true), "2026-10-28"))
        assertFalse(PermissionShiftTiming.resolve(TodayShiftResponse(success = true), "2026-11-01").isWorkDay)
    }
    @Test(expected = IllegalArgumentException::class) fun failedResponseIsNotDefaultShift() {
        PermissionShiftTiming.resolve(TodayShiftResponse(success = false), "2026-10-28")
    }
    @Test(expected = IllegalArgumentException::class) fun staleDateCannotSetAnotherDatesShift() {
        PermissionShiftTiming.resolve(TodayShiftResponse(success = true, date = "2026-10-29"), "2026-10-28")
    }
    @Test fun defaultDurationFitsShortShift() {
        assertEquals("11:00", shift.defaultEndTime())
        assertEquals("10:30", PermissionShiftTiming("10:00", "10:30").defaultEndTime())
    }
    @Test fun overnightShiftIsNotMisrepresentedAsDayShift() {
        assertNotNull(PermissionShiftTiming("22:00", "06:00").error(null, null))
    }
}
