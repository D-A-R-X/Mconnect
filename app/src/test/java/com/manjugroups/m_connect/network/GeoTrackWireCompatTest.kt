package com.manjugroups.m_connect.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The GeoTrack models must parse what the Go tracking service actually sends.
 *
 * These endpoints were built against a backend that emitted epoch millis and
 * `_id`. Tracking moved to the Go service (api-geo), which marshals Go
 * `time.Time` as RFC 3339 and names the keys `tripId` / `sessionId` / `state`.
 * Gson cannot read an RFC 3339 string into a `Long`, so it threw and the whole
 * response was discarded — the Live map and the attendance route strip came
 * back empty with no error shown, which is what "geo tracking is not working"
 * looked like from the field.
 *
 * Both shapes are accepted so the app works against either backend, and the
 * service can be corrected later without needing an app release to match.
 */
class GeoTrackWireCompatTest {

    private val gson = Gson()

    // 2026-09-12T06:14:22.184Z
    private val expectedMillis = 1789193662184L

    @Test
    fun `timeline point accepts an RFC 3339 recordedAt`() {
        val json = """
            {"lat":13.08,"lng":80.27,"speed":4.2,"activity":"WALKING",
             "recordedAt":"2026-09-12T06:14:22.184Z"}
        """.trimIndent()
        val point = gson.fromJson(json, TimelinePoint::class.java)
        assertEquals(expectedMillis, point.recordedAt)
        assertEquals(13.08, point.lat, 0.0001)
    }

    @Test
    fun `timeline point still accepts plain epoch millis`() {
        val json = """
            {"lat":13.08,"lng":80.27,"speed":0.0,"activity":"STILL",
             "recordedAt":1789193662184}
        """.trimIndent()
        assertEquals(expectedMillis, gson.fromJson(json, TimelinePoint::class.java).recordedAt)
    }

    @Test
    fun `a trip parses from the Go service shape`() {
        // tripId instead of _id, RFC 3339 timestamps.
        val json = """
            {"tripId":"trip_9","staffId":"k57","status":"completed",
             "startedAt":"2026-09-12T06:14:22.184Z",
             "endedAt":"2026-09-12T07:14:22.184Z",
             "distanceMeters":41230,"durationSeconds":3600}
        """.trimIndent()
        val trip = gson.fromJson(json, GeoTrip::class.java)
        assertEquals("trip_9", trip.id)
        assertEquals(expectedMillis, trip.startedAt)
        assertEquals(expectedMillis + 3_600_000L, trip.endedAt)
        assertEquals(41230, trip.distanceMeters)
    }

    @Test
    fun `a trip still parses from the original shape`() {
        val json = """
            {"_id":"trip_9","staffId":"k57","startedAt":1789193662184,
             "endedAt":null,"distanceMeters":120}
        """.trimIndent()
        val trip = gson.fromJson(json, GeoTrip::class.java)
        assertEquals("trip_9", trip.id)
        assertEquals(expectedMillis, trip.startedAt)
        assertNull(trip.endedAt)
    }

    @Test
    fun `a session parses from the Go service shape`() {
        // sessionId instead of _id, state instead of sessionState.
        val json = """
            {"sessionId":"sess_1","staffId":"k57","state":"active",
             "contextType":"cp_trip","contextId":"j97",
             "startedAt":"2026-09-12T06:14:22.184Z"}
        """.trimIndent()
        val session = gson.fromJson(json, TrackingSession::class.java)
        assertEquals("sess_1", session.id)
        assertEquals("active", session.sessionState)
        assertEquals("cp_trip", session.contextType)
        assertEquals(expectedMillis, session.startedAt)
    }

    @Test
    fun `a trip stop parses RFC 3339 arrival and departure`() {
        val json = """
            {"sequence":1,"lat":13.08,"lng":80.27,
             "arrivedAt":"2026-09-12T06:14:22.184Z",
             "departedAt":"2026-09-12T07:14:22.184Z","durationMinutes":60}
        """.trimIndent()
        val stop = gson.fromJson(json, GeoTripStop::class.java)
        assertEquals(expectedMillis, stop.arrivedAt)
        assertEquals(60, stop.durationMinutes)
    }

    @Test
    fun `Go's variable fractional seconds are handled`() {
        // Go trims trailing zeros and can emit more than three digits, so a
        // fixed SSS pattern alone would fail on most real timestamps.
        listOf(
            "2026-09-12T06:14:22.184376Z" to expectedMillis,
            "2026-09-12T06:14:22.1Z" to 1789193662100L,
            "2026-09-12T06:14:22Z" to 1789193662000L,
        ).forEach { (raw, expected) ->
            val json = """{"lat":0.0,"lng":0.0,"speed":0.0,"activity":"X","recordedAt":"$raw"}"""
            assertEquals(raw, expected, gson.fromJson(json, TimelinePoint::class.java).recordedAt)
        }
    }

    @Test
    fun `an offset timestamp keeps its real instant`() {
        // +05:30 must not be read as UTC — that is a 5.5 hour error, the same
        // class of bug that once showed collection entries 5:30 early.
        val json = """
            {"lat":0.0,"lng":0.0,"speed":0.0,"activity":"X",
             "recordedAt":"2026-09-12T11:44:22.184+05:30"}
        """.trimIndent()
        assertEquals(expectedMillis, gson.fromJson(json, TimelinePoint::class.java).recordedAt)
    }

    @Test
    fun `a null or absent timestamp does not blow up the whole response`() {
        val json = """{"_id":"t","staffId":"k57","startedAt":1789193662184}"""
        val trip = gson.fromJson(json, GeoTrip::class.java)
        assertNull(trip.endedAt)
        assertEquals(0, trip.distanceMeters)
    }
}
