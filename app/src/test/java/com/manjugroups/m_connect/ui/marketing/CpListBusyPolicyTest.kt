package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpListBusyPolicyTest {

    @Test
    fun busyCodesAreOnlyTheRetryLaterOnes() {
        listOf(429, 502, 503, 504).forEach { assertTrue("$it", CpListBusyPolicy.isServerBusy(it)) }
        listOf(null, 200, 400, 401, 403, 404, 500).forEach {
            assertFalse("$it", CpListBusyPolicy.isServerBusy(it))
        }
    }

    @Test
    fun retriesBackOffAndStop() {
        assertEquals(10_000L, CpListBusyPolicy.retryDelayMs(0, null))
        assertEquals(20_000L, CpListBusyPolicy.retryDelayMs(1, null))
        assertEquals(40_000L, CpListBusyPolicy.retryDelayMs(2, null))
        assertEquals(60_000L, CpListBusyPolicy.retryDelayMs(3, null))
        assertEquals(60_000L, CpListBusyPolicy.retryDelayMs(4, null))
        assertNull(CpListBusyPolicy.retryDelayMs(CpListBusyPolicy.MAX_AUTO_RETRIES, null))
        assertNull(CpListBusyPolicy.retryDelayMs(-1, null))
    }

    @Test
    fun retryAfterHeaderIsHonouredWithinBounds() {
        assertEquals(30_000L, CpListBusyPolicy.retryDelayMs(0, 30))
        assertEquals(5_000L, CpListBusyPolicy.retryDelayMs(0, 1))
        assertEquals(120_000L, CpListBusyPolicy.retryDelayMs(0, 3600))
        assertEquals(10_000L, CpListBusyPolicy.retryDelayMs(0, 0))
        assertNull(CpListBusyPolicy.retryDelayMs(CpListBusyPolicy.MAX_AUTO_RETRIES, 30))
    }

    @Test
    fun onlyThePlainListIsSavedOrShownFromCache() {
        assertTrue(CpListBusyPolicy.isPlainQuery(null, null, null, null, null, null, null, null))
        assertTrue(CpListBusyPolicy.isPlainQuery("", " ", null, null, null, null, null, null))
        assertFalse(CpListBusyPolicy.isPlainQuery("rama", null, null, null, null, null, null, null))
        assertFalse(CpListBusyPolicy.isPlainQuery(null, "scheduled", null, null, null, null, null, null))
        assertFalse(CpListBusyPolicy.isPlainQuery(null, null, null, null, "2026-11-01", null, null, null))
        assertFalse(CpListBusyPolicy.isPlainQuery(null, null, null, null, null, null, "staff1", null))
    }

    @Test
    fun cacheKeyIsPerStaffAndScope() {
        assertEquals("cp_visits_list_s1_all", CpListBusyPolicy.cacheKey("s1", "all"))
        assertTrue(CpListBusyPolicy.cacheKey("s1", "my") != CpListBusyPolicy.cacheKey("s2", "my"))
        assertNull(CpListBusyPolicy.cacheKey(null, "my"))
        assertNull(CpListBusyPolicy.cacheKey(" ", "my"))
    }

    @Test
    fun filterOptionsAreReReadOnlyWhenScopeDatesChangeOrTheyAge() {
        val key = CpListBusyPolicy.filterOptionsKey("all", null, null)
        assertTrue(CpListBusyPolicy.filterOptionsStale(null, 0L, key, 1_000L))
        assertFalse(CpListBusyPolicy.filterOptionsStale(key, 1_000L, key, 1_000L + 60_000L))
        assertTrue(
            CpListBusyPolicy.filterOptionsStale(
                key, 1_000L, key, 1_000L + CpListBusyPolicy.FILTER_OPTIONS_TTL_MS,
            ),
        )
        assertTrue(
            CpListBusyPolicy.filterOptionsStale(
                key, 1_000L, CpListBusyPolicy.filterOptionsKey("my", null, null), 2_000L,
            ),
        )
    }

    @Test
    fun busyMessageSaysWhenItRetries() {
        assertEquals(
            "The server is busy right now. Retrying in 10s…",
            CpListBusyPolicy.busyMessage(10_000L),
        )
        assertEquals(
            "The server is busy right now. Pull down to try again.",
            CpListBusyPolicy.busyMessage(null),
        )
    }
}
