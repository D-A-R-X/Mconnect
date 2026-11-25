package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvApprovalLoadPolicyTest {

    @Test
    fun `a read from moments ago is reused, an old or future one is not`() {
        val now = 1_000_000L
        assertTrue(SvApprovalLoadPolicy.isFresh(now - 2_000L, now))
        assertTrue(SvApprovalLoadPolicy.isFresh(now - SvApprovalLoadPolicy.SHARE_MS, now))
        assertFalse(SvApprovalLoadPolicy.isFresh(now - SvApprovalLoadPolicy.SHARE_MS - 1, now))
        assertFalse(SvApprovalLoadPolicy.isFresh(now + 5_000L, now)) // clock went back
        assertFalse(SvApprovalLoadPolicy.isFresh(0L, now)) // never read
    }

    @Test
    fun `busy retries come quickly at first, then back off, then stop`() {
        assertEquals(1_500L, SvApprovalLoadPolicy.retryDelayMs(0, null))
        assertEquals(4_000L, SvApprovalLoadPolicy.retryDelayMs(1, null))
        assertEquals(10_000L, SvApprovalLoadPolicy.retryDelayMs(2, null))
        assertEquals(20_000L, SvApprovalLoadPolicy.retryDelayMs(3, null))
        assertNull(SvApprovalLoadPolicy.retryDelayMs(SvApprovalLoadPolicy.MAX_AUTO_RETRIES, null))
        assertNull(SvApprovalLoadPolicy.retryDelayMs(-1, null))
    }

    @Test
    fun `server Retry-After wins but is clamped`() {
        assertEquals(5_000L, SvApprovalLoadPolicy.retryDelayMs(0, 5))
        assertEquals(1_500L, SvApprovalLoadPolicy.retryDelayMs(0, 0)) // zero header ignored
        assertEquals(60_000L, SvApprovalLoadPolicy.retryDelayMs(1, 3_600))
        assertEquals(1_500L, SvApprovalLoadPolicy.retryDelayMs(0, -3)) // bad header ignored
    }

    @Test
    fun `only busy codes are retried quietly`() {
        listOf(429, 502, 503, 504).forEach { assertTrue(CpListBusyPolicy.isServerBusy(it)) }
        listOf(400, 401, 403, 404, 500, null).forEach { assertFalse(CpListBusyPolicy.isServerBusy(it)) }
    }
}
