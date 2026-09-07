package com.manjugroups.m_connect.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileDialerApiCoordinatorTest {
    @Test
    fun `same call operation produces stable UUID`() {
        val first = MobileDialerApiCoordinator.idempotencyKey(
            "call-1",
            "pickup",
            "device-1",
            "event-1",
        )
        val replay = MobileDialerApiCoordinator.idempotencyKey(
            "call-1",
            "pickup",
            "device-1",
            "event-1",
        )

        assertEquals(first, replay)
        assertTrue(UUID_PATTERN.matches(first))
    }

    @Test
    fun `different action cannot replay pickup key`() {
        val pickup = MobileDialerApiCoordinator.idempotencyKey(
            "call-1",
            "pickup",
            "device-1",
            "event-1",
        )
        val reject = MobileDialerApiCoordinator.idempotencyKey(
            "call-1",
            "reject",
            "device-1",
            "event-1",
        )

        assertNotEquals(pickup, reject)
    }

    private companion object {
        val UUID_PATTERN = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )
    }
}
