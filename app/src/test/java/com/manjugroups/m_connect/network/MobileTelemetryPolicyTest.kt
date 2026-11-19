package com.manjugroups.m_connect.network

import org.junit.Assert.*
import org.junit.Test

class MobileTelemetryPolicyTest {
    @Test fun `routes remove identifiers query fragments and encoded personal data`() {
        assertEquals("/api/hr/staff/:id/:id", MobileTelemetryPolicy.route("/api/hr/staff/Surya/9884693351?otp=1234#secret"))
        assertEquals("/api/:id/:id", MobileTelemetryPolicy.route("/api/user%40example.com/secretToken"))
        assertEquals("/api/hr/attendance/punch-in", MobileTelemetryPolicy.route("/api/hr/attendance/punch-in"))
        assertEquals("/api/marketing/clientPlaceVisits/:id", MobileTelemetryPolicy.route("/api/marketing/clientPlaceVisits/k97mw73m5j81reyfb2kvqz8hc98ey4jh"))
        assertTrue(MobileTelemetryPolicy.route("/secret/".repeat(100)).length < 80)
    }
    @Test fun `only known services and fixed methods are monitored`() {
        assertEquals("mms", MobileTelemetryPolicy.service("API-MFPL.THEAIRIX.COM", "api-mfpl.theairix.com"))
        assertEquals("geotrack", MobileTelemetryPolicy.service("api-geo.theairix.com", "api-mfpl.theairix.com"))
        listOf("us.i.posthog.com", "storage-private.example.com", "api-mfpl.theairix.com.attacker.test").forEach {
            assertNull(MobileTelemetryPolicy.service(it, "api-mfpl.theairix.com"))
        }
        assertEquals("OTHER", MobileTelemetryPolicy.method("private-data"))
    }
    @Test fun `symbols cannot carry messages paths or personal values`() {
        assertEquals("java.lang.IllegalStateException", MobileTelemetryPolicy.symbol("java.lang.IllegalStateException"))
        listOf("Bearer token", "user@example.com", "/home/user/file", "55555555").forEach {
            assertEquals("Unknown", MobileTelemetryPolicy.symbol(it))
        }
    }
    @Test fun `healthy requests sampled one in twenty`() {
        val budget = MobileTelemetryBudget()
        val accepted = (1..100).count { budget.accept("request-$it", true, true, 10_000L) }
        assertEquals(5, accepted)
        assertEquals(5, budget.timings)
    }
    @Test fun `slow requests do not need healthy sampling but remain deduplicated`() {
        val budget = MobileTelemetryBudget()
        assertTrue(budget.accept("slow", true, false, 1_000))
        assertFalse(budget.accept("slow", true, false, 2_000))
        assertTrue(budget.accept("slow", true, false, 601_000))
    }
    @Test fun `daily limits reserve separate capacity for errors`() {
        val budget = MobileTelemetryBudget()
        assertEquals(20, (1..1000).count { budget.accept("timing-$it", true, false, 1_000) })
        assertEquals(40, (1..1000).count { budget.accept("error-$it", false, false, 1_000) })
    }
    @Test fun `persisted budgets survive restart and reset next UTC day`() {
        val budget = MobileTelemetryBudget(day = 0, errors = 40, timings = 20)
        assertFalse(budget.accept("new", false, false, 1_000))
        assertFalse(budget.accept("new", true, false, 1_000))
        assertTrue(budget.accept("new", false, false, 86_400_000))
        assertEquals(1, budget.errors)
        assertEquals(0, budget.timings)
    }
    @Test fun `exceptions have a separate persistent cap within the error budget`() {
        val budget = MobileTelemetryBudget()
        assertEquals(8, (1..100).count { budget.accept("crash-$it", false, false, 1_000, exception = true) })
        assertEquals(32, (1..100).count { budget.accept("http-$it", false, false, 1_000) })
        val restarted = MobileTelemetryBudget(day = 0, exceptions = 8)
        assertFalse(restarted.accept("next", false, false, 1_000, exception = true))
        assertTrue(restarted.accept("next", false, false, 86_400_000, exception = true))
    }
    @Test fun `telemetry failures back off without short retry loops`() {
        assertEquals(600_000L, MobileTelemetryPolicy.retryDelayMs(null, 1, null))
        assertEquals(1_200_000L, MobileTelemetryPolicy.retryDelayMs(503, 2, 1))
        assertEquals(3_600_000L, MobileTelemetryPolicy.retryDelayMs(429, 1, 3600))
        assertEquals(21_600_000L, MobileTelemetryPolicy.retryDelayMs(401, 1, null))
        assertEquals(21_600_000L, MobileTelemetryPolicy.retryDelayMs(500, 500, Long.MAX_VALUE))
    }
    @Test fun `bounded batching uses minute spacing not per-request flushing`() {
        assertEquals(60, MobileTelemetryPolicy.MAX_QUEUE)
        assertEquals(20, MobileTelemetryPolicy.BATCH_SIZE)
        assertTrue(MobileTelemetryPolicy.FLUSH_MS >= 60_000)
        assertTrue(MobileTelemetryPolicy.MAX_AGE_MS <= 3 * 86_400_000L)
        assertEquals("https://us.i.posthog.com/batch/", PostHogConfig.BATCH_URL)
    }
}
