package com.manjugroups.m_connect.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernDialerCallControllerTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `incoming and ended payloads require their matching type and call id`() {
        assertTrue(
            ModernDialerCallController.isIncomingCall(
                mapOf("type" to ModernDialerCallController.TYPE_INCOMING, "callId" to "call-1"),
            ),
        )
        assertFalse(ModernDialerCallController.isIncomingCall(mapOf("type" to "other", "callId" to "call-1")))
        assertFalse(ModernDialerCallController.isIncomingCall(mapOf("type" to ModernDialerCallController.TYPE_INCOMING)))

        assertTrue(
            ModernDialerCallController.isEndedCall(
                mapOf("type" to ModernDialerCallController.TYPE_ENDED, "callId" to "call-1"),
            ),
        )
        assertFalse(ModernDialerCallController.isEndedCall(mapOf("type" to "other", "callId" to "call-1")))
        assertFalse(ModernDialerCallController.isEndedCall(mapOf("type" to ModernDialerCallController.TYPE_ENDED)))
    }

    @Test
    fun `expiry accepts epoch seconds epoch millis and ISO instants`() {
        assertTrue(ModernDialerCallController.isExpired("1799999999", now))
        assertFalse(ModernDialerCallController.isExpired("1800000001", now))
        assertTrue(ModernDialerCallController.isExpired("1799999999999", now))
        assertFalse(ModernDialerCallController.isExpired("1800000000001", now))
        assertTrue(ModernDialerCallController.isExpired("2027-01-15T07:59:59Z", now))
        assertFalse(ModernDialerCallController.isExpired("2027-01-15T08:00:01Z", now))
    }

    @Test
    fun `missing or malformed expiry does not discard a call`() {
        assertFalse(ModernDialerCallController.isExpired(null, now))
        assertFalse(ModernDialerCallController.isExpired("", now))
        assertFalse(ModernDialerCallController.isExpired("not-a-time", now))
    }

    @Test
    fun `ring timeout respects a short expiry and caps missing or distant expiry`() {
        assertTrue(ModernDialerCallController.timeoutMillis("1800000030", now) == 30_000L)
        assertTrue(ModernDialerCallController.timeoutMillis(null, now) == 60_000L)
        assertTrue(ModernDialerCallController.timeoutMillis("1999999999", now) == 60_000L)
        assertTrue(ModernDialerCallController.timeoutMillis("1", now) == 1_000L)
    }
}
