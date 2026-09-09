package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertEquals
import org.junit.Test

class CpVisitActivityDateTest {

    @Test
    fun `server effective status wins over conflicting legacy rows`() {
        assertEquals(
            "completed",
            resolveServerCpEffectiveStatus("completed", "scheduled", "in_progress"),
        )
    }

    @Test
    fun `missing server effective status keeps legacy lifecycle fallback`() {
        assertEquals(
            "arrived",
            resolveServerCpEffectiveStatus(null, "in_progress", "arrived"),
        )
    }

    @Test
    fun `completed cp is attributed to participant start day`() {
        assertEquals(
            "2026-09-07",
            resolveCpActivityDate(
                scheduledDate = "2026-09-05",
                serverActivityDate = "2026-09-08",
                cpCompletedAt = 1_788_859_800_000,
                fieldVisitCompletedAt = null,
                participantStartedAt = java.time.OffsetDateTime
                    .parse("2026-09-07T23:55:00+05:30")
                    .toInstant()
                    .toEpochMilli(),
            ),
        )
    }

    @Test
    fun `field visit start is used when participant start is absent`() {
        assertEquals(
            "2026-09-06",
            resolveCpActivityDate(
                scheduledDate = "2026-09-08",
                serverActivityDate = "2026-09-05",
                cpCompletedAt = null,
                fieldVisitCompletedAt = 1_788_600_000_000,
                fieldVisitStartedAt = java.time.OffsetDateTime
                    .parse("2026-09-06T00:05:00+05:30")
                    .toInstant()
                    .toEpochMilli(),
            ),
        )
    }

    @Test
    fun `legacy cp without start timestamp remains on assigned day`() {
        assertEquals(
            "2026-09-05",
            resolveCpActivityDate(
                scheduledDate = "2026-09-05",
                serverActivityDate = "2026-09-05",
                cpCompletedAt = 1_788_600_000_000,
                fieldVisitCompletedAt = 1_788_600_000_000,
            ),
        )
    }
}
