package com.manjugroups.m_connect.geotrack

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class AttendanceDayBoundaryTest {
    @Test
    fun `India day changes exactly at midnight`() {
        val beforeMidnight = Instant.parse("2026-09-07T18:29:59Z").toEpochMilli()
        val midnight = Instant.parse("2026-09-07T18:30:00Z").toEpochMilli()

        assertEquals("2026-09-07", AttendanceDayBoundary.dateKey(beforeMidnight))
        assertEquals(1_000L, AttendanceDayBoundary.millisUntilNextDay(beforeMidnight))
        assertEquals("2026-09-08", AttendanceDayBoundary.dateKey(midnight))
        assertEquals(24 * 60 * 60 * 1_000L, AttendanceDayBoundary.millisUntilNextDay(midnight))
    }
}
