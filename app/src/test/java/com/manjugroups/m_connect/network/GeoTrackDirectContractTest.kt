package com.manjugroups.m_connect.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTrackDirectContractTest {
    private val gson = Gson()

    @Test
    fun `tracking write routes use direct Airix host`() {
        listOf(
            GeoTrackApi.DIRECT_LOCATION_BATCH_URL,
            GeoTrackApi.DIRECT_HEARTBEAT_URL,
            GeoTrackApi.DIRECT_TAMPER_URL,
            GeoTrackApi.DIRECT_START_URL,
            GeoTrackApi.DIRECT_STOP_URL,
        ).forEach { url ->
            assertTrue(url.startsWith("https://api-geo.theairix.com/api/"))
            assertFalse(url.contains("api-mfpl"))
        }
    }

    @Test
    fun `location batch serializes stable delivery identity`() {
        val body = PushBatchRequest(
            sessionId = "session-1",
            deviceId = "device-1",
            requestId = "batch-1",
            points = listOf(
                LocationPoint(
                    pointId = "point-1",
                    deviceSequence = 7,
                    lat = 13.0,
                    lng = 80.0,
                    accuracy = 5f,
                    speed = 1f,
                    bearing = 2f,
                    activity = "WALKING",
                    activityConfidence = 90,
                    isMock = false,
                    batteryPct = 75,
                    networkType = "wifi",
                    gpsEnabled = true,
                    airplaneMode = false,
                    recordedAt = 1_787_391_000_000,
                ),
            ),
        )

        val json = gson.toJsonTree(body).asJsonObject
        assertEquals("batch-1", json["requestId"].asString)
        assertEquals("point-1", json["points"].asJsonArray[0].asJsonObject["pointId"].asString)
        assertEquals(7L, json["points"].asJsonArray[0].asJsonObject["deviceSequence"].asLong)
    }

    @Test
    fun `stop request remains an empty json object`() {
        assertEquals("{}", gson.toJson(DirectTrackingStopRequest()))
    }
}
