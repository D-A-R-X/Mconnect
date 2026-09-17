package com.manjugroups.m_connect.geotrack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTrackQueuePolicyTest {
    @Test
    fun `a stop for an unknown session is complete`() {
        assertTrue(GeoTrackQueuePolicy.isStopAlreadyGone(404))
    }

    @Test
    fun `transient and auth failures stay queued`() {
        for (code in listOf(400, 401, 403, 408, 429, 500, 502, 503)) {
            assertFalse("$code", GeoTrackQueuePolicy.isStopAlreadyGone(code))
        }
    }
}
