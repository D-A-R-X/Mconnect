package com.manjugroups.m_connect.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernDialerWebViewBridgeTest {
    private val startedAt = 1_800_000_010_000L

    @Test
    fun `inactive call rejects terminal events`() {
        assertFalse(isCurrentDialerEvent(false, "call-1", "call-1", startedAt, startedAt))
    }

    @Test
    fun `mismatched provider call id is stale`() {
        assertFalse(isCurrentDialerEvent(true, "call-2", "call-1", startedAt, startedAt))
    }

    @Test
    fun `replayed diagnostic timestamp is stale even when provider reuses current id`() {
        assertFalse(
            isCurrentDialerEvent(
                true,
                "call-2",
                "call-2",
                startedAt,
                startedAt - 10_000L,
            ),
        )
    }

    @Test
    fun `current diagnostic and non diagnostic terminal event are accepted`() {
        assertTrue(isCurrentDialerEvent(true, "call-2", "call-2", startedAt, startedAt + 1_000L))
        assertTrue(isCurrentDialerEvent(true, "call-2", "call-2", startedAt, null))
    }
}
