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
    fun `completed cp remains attributed to assigned day when completed later`() {
        assertEquals(
            "2026-09-05",
            resolveCpActivityDate(
                scheduledDate = "2026-09-05",
                serverActivityDate = "2026-09-08",
                cpCompletedAt = 1_788_859_800_000,
                fieldVisitCompletedAt = null,
            ),
        )
    }

    @Test
    fun `completed cp remains attributed to assigned day when completed earlier`() {
        assertEquals(
            "2026-09-08",
            resolveCpActivityDate(
                scheduledDate = "2026-09-08",
                serverActivityDate = "2026-09-05",
                cpCompletedAt = null,
                fieldVisitCompletedAt = 1_788_600_000_000,
            ),
        )
    }

    @Test
    fun `same day completion remains attributed once`() {
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
