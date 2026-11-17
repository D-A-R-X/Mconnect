package com.manjugroups.m_connect.geotrack

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTrackStopGuardTest {
    @Test
    fun `two concurrent ends of one session send one request`() = runTest {
        val sessionId = "s-${System.nanoTime()}"
        var serverOpen = true
        var requests = 0
        val end: suspend () -> Unit = {
            GeoTrackStopGuard.serialized {
                // Same shape as the real paths: resolve, skip if already ended, POST.
                val active = sessionId.takeIf { serverOpen }
                if (active != null && !GeoTrackStopGuard.wasEnded(active)) {
                    requests++
                    delay(50)
                    serverOpen = false
                    GeoTrackStopGuard.markEnded(active)
                }
            }
        }
        listOf(async { end() }, async { end() }).awaitAll()
        assertEquals(1, requests)
    }

    @Test
    fun `an ended session is remembered and then forgotten`() {
        val sessionId = "s-${System.nanoTime()}"
        assertFalse(GeoTrackStopGuard.wasEnded(sessionId, now = 1_000L))
        GeoTrackStopGuard.markEnded(sessionId, now = 1_000L)
        assertTrue(GeoTrackStopGuard.wasEnded(sessionId, now = 2_000L))
        assertFalse(GeoTrackStopGuard.wasEnded(sessionId, now = 1_000L + 7 * 60 * 60 * 1000L))
    }
}
