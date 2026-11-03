package com.manjugroups.m_connect.ui.hr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class HrApplicationDatesTest {
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    // 3 Nov 2026, 10:00 IST (04:30 UTC).
    private val now = 1_793_680_200_000L

    @Test fun yesterdayIsPastTodayAndLaterAreNot() {
        val today = HrApplicationDates.startOfToday(now, ist)
        assertTrue(HrApplicationDates.isPastDay(today - 1, now, ist))
        assertFalse(HrApplicationDates.isPastDay(today, now, ist))
        assertFalse(HrApplicationDates.isPastDay(now, now, ist))
        assertFalse(HrApplicationDates.isPastDay(today + 86_400_000L, now, ist))
    }

    @Test fun isoDatesFollowTheSameRule() {
        assertEquals("2026-11-03", HrApplicationDates.todayIso(now, ist))
        assertTrue(HrApplicationDates.isPastIsoDate("2026-11-01", now, ist))
        assertFalse(HrApplicationDates.isPastIsoDate("2026-11-03", now, ist))
        assertFalse(HrApplicationDates.isPastIsoDate("2026-12-01", now, ist))
        assertFalse(HrApplicationDates.isPastIsoDate("not-a-date", now, ist))
    }
}
