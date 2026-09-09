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
            GeoTrackApi.DIRECT_CURRENT_SESSION_URL,
            GeoTrackApi.DIRECT_STOP_URL,
            GeoTrackApi.DIRECT_LIVE_URL,
            GeoTrackApi.DIRECT_DAY_STATUS_URL,
            GeoTrackApi.DIRECT_TIMELINE_URL,
            GeoTrackApi.DIRECT_TRIPS_URL,
            GeoTrackApi.DIRECT_NEARBY_STAFF_URL,
            GeoTrackApi.DIRECT_SESSION_ROUTE_URL,
            GeoTrackApi.DIRECT_PLACE_SEARCH_URL,
            GeoTrackApi.DIRECT_ROUTE_URL,
            GeoTrackApi.DIRECT_GEOCODE_URL,
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
    fun `session start and end use direct attendance contract without staff id`() {
        val start = gson.toJsonTree(
            DirectTrackingStartRequest(
                deviceId = "device-1",
                contextId = "attendance-1",
                startedAt = 1_787_391_000_000,
            ),
        ).asJsonObject
        assertEquals("attendance", start["contextType"].asString)
        assertEquals("mconnect", start["source"].asString)
        assertEquals("attendance_punch_in", start["trigger"].asString)
        assertFalse(start.has("staffId"))

        val end = gson.toJsonTree(
            DirectTrackingStopRequest(
                sessionId = "session-1",
                endedAt = 1_787_391_100_000,
            ),
        ).asJsonObject
        assertEquals("session-1", end["sessionId"].asString)
        assertEquals("attendance_punch_out", end["reason"].asString)
        assertFalse(end.has("staffId"))
    }
}
