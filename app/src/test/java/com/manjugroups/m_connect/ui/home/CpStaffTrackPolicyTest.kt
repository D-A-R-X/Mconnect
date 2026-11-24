package com.manjugroups.m_connect.ui.home

import com.google.gson.Gson
import com.manjugroups.m_connect.network.CpLiveStatusResponse
import com.manjugroups.m_connect.network.CpStaffTracksResponse
import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.JointCpParticipant
import com.manjugroups.m_connect.network.JointCpSummary
import com.manjugroups.m_connect.network.CpVisitFieldVisit
import com.manjugroups.m_connect.network.CpVisitStaff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpStaffTrackPolicyTest {

    private val min = 60_000L

    @Test
    fun `window runs to now while open and to completion once closed`() {
        assertNull(CpStaffTrackPolicy.window(null, null, 5 * min))
        assertEquals(CpStaffTrackPolicy.Window(min, 5 * min, open = true), CpStaffTrackPolicy.window(min, null, 5 * min))
        assertEquals(CpStaffTrackPolicy.Window(min, 3 * min, open = false), CpStaffTrackPolicy.window(min, 3 * min, 5 * min))
    }

    @Test
    fun `live fix shows only while tracking or inside the trip window`() {
        val window = CpStaffTrackPolicy.Window(10 * min, 20 * min, open = false)
        assertTrue(CpStaffTrackPolicy.liveFixVisible(13.0, 80.0, 90 * min, trackingActive = true, window = null))
        assertTrue(CpStaffTrackPolicy.liveFixVisible(13.0, 80.0, 15 * min, trackingActive = false, window = window))
        // An off-duty fix from another time is never shown as their location.
        assertFalse(CpStaffTrackPolicy.liveFixVisible(13.0, 80.0, 90 * min, trackingActive = false, window = window))
        assertFalse(CpStaffTrackPolicy.liveFixVisible(13.0, 80.0, 15 * min, trackingActive = false, window = null))
        assertFalse(CpStaffTrackPolicy.liveFixVisible(0.0, 0.0, 15 * min, trackingActive = true, window = window))
        assertFalse(CpStaffTrackPolicy.liveFixVisible(13.0, 80.0, null, trackingActive = true, window = window))
    }

    @Test
    fun `another staff route is read directly only with all-staff tracking access`() {
        assertTrue(CpStaffTrackPolicy.canReadRouteDirectly("a", "a", viewerCanViewAllTracking = false))
        assertFalse(CpStaffTrackPolicy.canReadRouteDirectly("b", "a", viewerCanViewAllTracking = false))
        assertTrue(CpStaffTrackPolicy.canReadRouteDirectly("b", "a", viewerCanViewAllTracking = true))
        assertFalse(CpStaffTrackPolicy.canReadRouteDirectly("b", null, viewerCanViewAllTracking = false))
    }

    @Test
    fun `viewer is involved only when listed`() {
        assertTrue(CpStaffTrackPolicy.viewerIsInvolved("a", listOf("a", "b")))
        assertFalse(CpStaffTrackPolicy.viewerIsInvolved("c", listOf("a", "b")))
        assertFalse(CpStaffTrackPolicy.viewerIsInvolved(null, listOf("a")))
    }

    @Test
    fun `joint cp involves both participants, primary first`() {
        val cp = CpVisitDetail(
            assignedStaffId = "a",
            joint = JointCpSummary(
                participants = listOf(
                    JointCpParticipant(staffId = "b", staffName = "Bala", isPrimary = false, startedAt = 5L),
                    JointCpParticipant(staffId = "a", staffName = "Arun", isPrimary = true, routeColor = "#2563EB"),
                ),
            ),
        )
        val staff = CpStaffTrackPolicy.involvedStaff(cp)
        assertEquals(listOf("a", "b"), staff.map { it.staffId })
        assertEquals("#2563EB", staff.first().routeColor)
        assertEquals(5L, staff.last().startedAt)
    }

    @Test
    fun `single cp involves the assigned staff with their field visit window`() {
        val cp = CpVisitDetail(
            assignedStaffId = "a",
            assignedStaff = CpVisitStaff(name = "Arun"),
            fieldVisit = CpVisitFieldVisit(startedAt = 10L, completedAt = 20L, distanceMeters = 1500.0),
        )
        val staff = CpStaffTrackPolicy.involvedStaff(cp).single()
        assertEquals("Arun", staff.name)
        assertEquals(10L, staff.startedAt)
        assertEquals(1500.0, staff.recordedDistanceMeters!!, 0.0)
        assertTrue(CpStaffTrackPolicy.involvedStaff(CpVisitDetail()).isEmpty())
    }

    @Test
    fun `server payload maps path, distance and live fix`() {
        val now = 30 * min
        val json = """
            {"success":true,"data":{"visitId":"v","staff":[
              {"staffId":"a","staffName":"Arun","isPrimary":true,"startedAt":${10 * min},"completedAt":null,
               "routeAvailable":true,"distanceMeters":1200,
               "routePoints":[{"lat":13.0,"lng":80.2,"recordedAt":${11 * min}},
                              {"lat":13.01,"lng":80.2,"recordedAt":${15 * min}},
                              {"lat":13.02,"lng":80.2,"recordedAt":${20 * min}}],
               "live":{"lat":13.02,"lng":80.2,"lastSeen":${29 * min},"trackingActive":true}},
              {"staffId":"b","staffName":"Bala","startedAt":null,"routeAvailable":false,"routePoints":[],"live":null}
            ]}}
        """.trimIndent()
        val rows = Gson().fromJson(json, CpStaffTracksResponse::class.java).data!!.staff!!
        val a = CpStaffTrackPolicy.fromServer(rows[0], now)!!
        assertEquals(CpStaffTrack.PathState.SHOWN, a.pathState)
        assertTrue(a.tripInProgress)
        assertNotNull(a.live)
        assertTrue(a.distanceMeters!! > 2000) // ~2.2 km along the cleaned path
        val b = CpStaffTrackPolicy.fromServer(rows[1], now)!!
        assertEquals(CpStaffTrack.PathState.NOT_STARTED, b.pathState)
        assertNull(b.live)
        assertFalse(b.tripInProgress)
    }

    @Test
    fun `geo live rows accept RFC3339 timestamps`() {
        val json = """{"success":true,"data":[{"staffId":"a","lat":13.0,"lng":80.2,
            "lastSeen":"2026-11-24T10:15:30.123456Z","trackingActive":true}]}"""
        val row = Gson().fromJson(json, CpLiveStatusResponse::class.java).data!!.single()
        assertEquals(1795515330123L, row.lastSeen)
    }

    private fun pt(lat: Double, atMs: Long?) = com.manjugroups.m_connect.geotrack.TrackCleaner.Point(lat, 80.2, atMs)

    @Test
    fun `path splits at an overnight gap and at a jump, never joining them`() {
        val day1 = 0L
        val pieces = CpStaffTrackPolicy.splitAtGaps(
            listOf(
                pt(13.00, day1), pt(13.01, day1 + 4 * min), pt(13.02, day1 + 8 * min),
                // overnight: 14 h later, 20 km away → a gap, not a road
                pt(12.84, day1 + 14 * 60 * min), pt(12.85, day1 + 14 * 60 * min + 3 * min),
                // 55 km in 30 s → a jump
                pt(13.35, day1 + 14 * 60 * min + 3 * min + 30_000), pt(13.36, day1 + 14 * 60 * min + 8 * min),
            ),
        )
        assertEquals(listOf(3, 2, 2), pieces.map { it.size })
        // A long stop in one place is not a gap.
        assertEquals(1, CpStaffTrackPolicy.splitAtGaps(listOf(pt(13.0, 0L), pt(13.0001, 3 * 60 * min))).size)
    }

    @Test
    fun `distance counts only the drawn segments`() {
        val segments = CpStaffTrackPolicy.splitAtGaps(
            listOf(pt(13.00, 0L), pt(13.01, 4 * min), pt(12.80, 14 * 60 * min), pt(12.81, 14 * 60 * min + 4 * min)),
        ).map { CpStaffTrack.Segment(it) }
        val meters = CpStaffTrackPolicy.segmentsDistanceMeters(segments)!!
        // two ~1.1 km rides; the ~23 km overnight hop is not counted
        assertTrue(meters in 2000.0..2500.0)
    }

    @Test
    fun `snapped pieces inside the window are drawn, gap pieces never`() {
        val window = CpStaffTrackPolicy.Window(10 * min, 20 * min, open = false)
        val road = listOf(pt(13.0, null), pt(13.01, null))
        val segments = CpStaffTrackPolicy.snappedSegments(
            listOf(
                CpSnapPiece("snapped", 11 * min, 12 * min, road),
                CpSnapPiece("gap", 12 * min, 13 * min, road),
                CpSnapPiece("estimated", 13 * min, 14 * min, road),
                CpSnapPiece("snapped", 30 * min, 40 * min, road), // after the trip
            ),
            window,
        )
        assertEquals(listOf(false, true), segments.map { it.dashed })
    }

    @Test
    fun `a trip open since an earlier day shows today's travel only`() {
        val nov22 = 1795350273286L // 22 Nov 2026 17:54 IST
        val nov24 = nov22 + 2 * 24 * 60 * min - 11 * 60 * min // 24 Nov 06:54 IST
        val open = CpStaffTrackPolicy.Window(nov22, nov24, open = true)
        assertTrue(CpStaffTrackPolicy.isTodayOnly(open, nov24))
        // Same-day trips, finished trips and no window are drawn as they are.
        assertFalse(CpStaffTrackPolicy.isTodayOnly(CpStaffTrackPolicy.Window(nov24 - 60 * min, nov24, open = true), nov24))
        assertFalse(CpStaffTrackPolicy.isTodayOnly(open.copy(open = false), nov24))
        assertFalse(CpStaffTrackPolicy.isTodayOnly(null, nov24))
        // No recorded start is also today only.
        assertTrue(CpStaffTrackPolicy.isTodayOnly(CpStaffTrackPolicy.Window(nov24 - min, nov24, open = true, startKnown = false), nov24))

        fun track(state: CpStaffTrack.PathState, meters: Double?, todayOnly: Boolean) = CpStaffTrack(
            staffId = "a", name = "A", isPrimary = true, routeColor = null, segments = emptyList(),
            distanceMeters = meters, live = null, pathState = state, tripInProgress = true,
            windowStartMs = nov22, windowEndMs = nov24, todayOnly = todayOnly,
        )
        // The path window starts at today's IST midnight, not 22 Nov.
        assertEquals(CpStaffTrackPolicy.istStartOfDay(nov24), track(CpStaffTrack.PathState.SHOWN, 1.0, true).pathWindow!!.start)
        assertEquals(nov22, track(CpStaffTrack.PathState.SHOWN, 1.0, false).pathWindow!!.start)
        assertEquals("12.4 km travelled today", CpStaffTrackPolicy.travelLabel(track(CpStaffTrack.PathState.SHOWN, 12_400.0, true)))
        assertEquals("3.4 km travelled", CpStaffTrackPolicy.travelLabel(track(CpStaffTrack.PathState.SHOWN, 3_420.0, false)))
        // No travel today → no distance text at all (no multi-day totals).
        assertNull(CpStaffTrackPolicy.travelLabel(track(CpStaffTrack.PathState.NO_POINTS, null, true)))
        assertEquals(listOf("2026-11-22", "2026-11-23", "2026-11-24"), CpStaffTrackPolicy.istDates(open))
    }

    @Test
    fun `trip start comes from the visit, viewer's own joint leg first`() {
        val cp = CpVisitDetail(
            fieldVisit = CpVisitFieldVisit(startedAt = 100L),
            joint = JointCpSummary(
                participants = listOf(
                    JointCpParticipant(staffId = "a", isPrimary = true, startedAt = 100L),
                    JointCpParticipant(staffId = "b", startedAt = 200L),
                ),
            ),
        )
        assertEquals(200L, CpStaffTrackPolicy.tripStartedAt(cp, "b"))
        assertEquals(100L, CpStaffTrackPolicy.tripStartedAt(cp, "manager"))
        assertNull(CpStaffTrackPolicy.tripStartedAt(CpVisitDetail(), "manager"))
        val nov22 = 1795350273286L
        assertEquals("05:54 PM", CpStaffTrackPolicy.clockLabel(nov22, nov22 + 60 * min))
        assertEquals("22 Nov, 05:54 PM", CpStaffTrackPolicy.clockLabel(nov22, nov22 + 2 * 24 * 60 * min))
    }

    @Test
    fun `motion maps movement mode first, then activity, then speed`() {
        assertEquals(CpStaffTrack.Motion.IDLE, CpStaffTrackPolicy.motionFrom("stationary", "IN_VEHICLE", 20.0))
        assertEquals(CpStaffTrack.Motion.WALKING, CpStaffTrackPolicy.motionFrom("walking", null, null))
        assertEquals(CpStaffTrack.Motion.MOVING, CpStaffTrackPolicy.motionFrom("two_wheeler", null, null))
        assertEquals(CpStaffTrack.Motion.MOVING, CpStaffTrackPolicy.motionFrom("vehicle", null, null))
        assertNull(CpStaffTrackPolicy.motionFrom("anomaly", "IN_VEHICLE", 20.0))
        assertEquals(CpStaffTrack.Motion.IDLE, CpStaffTrackPolicy.motionFrom(null, "STILL", null))
        assertEquals(CpStaffTrack.Motion.WALKING, CpStaffTrackPolicy.motionFrom(null, "ON_FOOT", null))
        assertEquals(CpStaffTrack.Motion.MOVING, CpStaffTrackPolicy.motionFrom(null, "IN_VEHICLE", null))
        assertEquals(CpStaffTrack.Motion.MOVING, CpStaffTrackPolicy.motionFrom(null, "TILTING", 9.0))
        assertEquals(CpStaffTrack.Motion.WALKING, CpStaffTrackPolicy.motionFrom(null, "UNKNOWN", 1.2))
        assertEquals(CpStaffTrack.Motion.IDLE, CpStaffTrackPolicy.motionFrom(null, null, 0.1))
        // Nothing usable → nothing shown, never a guess.
        assertNull(CpStaffTrackPolicy.motionFrom(null, "UNKNOWN", null))
        assertNull(CpStaffTrackPolicy.motionFrom(null, null, 80.0))
    }

    @Test
    fun `motion shows only while the trip runs and the fix is live and fresh`() {
        val now = 100 * min
        val fresh = CpStaffTrack.LiveFix(13.0, 80.0, now - 2 * min, true, CpStaffTrack.Motion.MOVING)
        fun track(inProgress: Boolean, live: CpStaffTrack.LiveFix?) = CpStaffTrack(
            staffId = "a", name = "A", isPrimary = true, routeColor = null, segments = emptyList(),
            distanceMeters = null, live = live, pathState = CpStaffTrack.PathState.NO_POINTS,
            tripInProgress = inProgress, windowStartMs = null, windowEndMs = null,
        )
        assertEquals(CpStaffTrack.Motion.MOVING, CpStaffTrackPolicy.motionToShow(track(true, fresh), now))
        // Before the trip starts or after it completes: no status.
        assertNull(CpStaffTrackPolicy.motionToShow(track(false, fresh), now))
        // Stale or off-duty fix, or no fix: no status.
        assertNull(CpStaffTrackPolicy.motionToShow(track(true, fresh.copy(lastSeenMs = now - 30 * min)), now))
        assertNull(CpStaffTrackPolicy.motionToShow(track(true, fresh.copy(trackingActive = false)), now))
        assertNull(CpStaffTrackPolicy.motionToShow(track(true, null), now))
        assertNull(CpStaffTrackPolicy.motionToShow(track(true, fresh.copy(motion = null)), now))
    }

    @Test
    fun `live rows carry motion hints`() {
        val json = """{"success":true,"data":[{"staffId":"a","lat":13.0,"lng":80.2,
            "lastSeen":"2026-11-24T10:15:30Z","trackingActive":true,"movementMode":"walking","activity":"ON_FOOT","speed":1.1}]}"""
        val row = Gson().fromJson(json, CpLiveStatusResponse::class.java).data!!.single()
        assertEquals(CpStaffTrack.Motion.WALKING, CpStaffTrackPolicy.motionFrom(row.movementMode, row.activity, row.speed))
    }

    @Test
    fun `running is decided by trip status, not by a recorded start`() {
        val now = 1795350273286L + 36 * 60 * min // 24 Nov 05:54 IST
        // Enroute with no recorded start → today's IST day, open, start unknown.
        val w = CpStaffTrackPolicy.tripWindow(null, null, "enroute", now)!!
        assertTrue(w.open)
        assertFalse(w.startKnown)
        assertEquals(CpStaffTrackPolicy.istStartOfDay(now), w.start)
        assertEquals(now, w.end)
        assertTrue(CpStaffTrackPolicy.tripWindow(null, null, "In-Progress", now)!!.open)
        // Scheduled and never started → no window.
        assertNull(CpStaffTrackPolicy.tripWindow(null, null, "scheduled", now))
        // A recorded start still works with no status at all.
        assertTrue(CpStaffTrackPolicy.tripWindow(now - 60 * min, null, null, now)!!.open)
        // Completed (by status) is not running even without completedAt.
        assertFalse(CpStaffTrackPolicy.tripWindow(now - 60 * min, null, "completed", now)!!.open)
        assertEquals(
            CpStaffTrack.PathState.START_UNKNOWN,
            CpStaffTrackPolicy.initialPathState(null, "completed"),
        )
        assertEquals(CpStaffTrack.PathState.NOT_STARTED, CpStaffTrackPolicy.initialPathState(null, "scheduled"))
    }

    @Test
    fun `long windows are read one IST day at a time, never over 48 h`() {
        val start = 1795350273286L // 22 Nov 17:54 IST
        val end = start + 60 * 60 * min // 25 Nov 05:54 IST
        val chunks = CpStaffTrackPolicy.dayChunks(CpStaffTrackPolicy.Window(start, end, open = true))
        assertEquals(4, chunks.size) // 22, 23, 24, 25 Nov
        assertEquals(start, chunks.first().first)
        assertEquals(end, chunks.last().second)
        chunks.forEach { (from, to) -> assertTrue(to - from <= 24 * 60 * min) }
        chunks.zipWithNext().forEach { (a, b) -> assertEquals(a.second + 1, b.first) }
        // Older days beyond the cap are dropped, newest kept.
        val week = CpStaffTrackPolicy.dayChunks(CpStaffTrackPolicy.Window(start, start + 7 * 24 * 60 * min, open = true))
        assertEquals(CpStaffTrackPolicy.MAX_SNAP_DAYS, week.size)
    }

    @Test
    fun `server row with a running status but no start uses today's window`() {
        val now = 1795350273286L + 36 * 60 * min
        val dto = Gson().fromJson(
            """{"staffId":"a","staffName":"Hajesh","routeAvailable":true,"routePoints":[],
               "live":{"lat":13.0,"lng":80.2,"lastSeen":${now - min},"trackingActive":true,"movementMode":"stationary"}}""",
            com.manjugroups.m_connect.network.CpStaffTrackDto::class.java,
        )
        val involved = CpInvolvedStaff("a", "Hajesh", true, null, null, null, null, status = "enroute")
        val track = CpStaffTrackPolicy.fromServer(dto, now, involved)!!
        assertTrue(track.tripInProgress)
        assertFalse(track.startKnown)
        assertEquals(CpStaffTrack.Motion.IDLE, CpStaffTrackPolicy.motionToShow(track, now))
    }

    @Test
    fun `labels`() {
        val now = 100 * min
        val fmt: (Long) -> String = { "T" }
        assertEquals("No recent location", CpStaffTrackPolicy.locationLabel(null, now, fmt))
        assertEquals(
            "Live · 2 min ago",
            CpStaffTrackPolicy.locationLabel(CpStaffTrack.LiveFix(1.0, 1.0, 98 * min, true), now, fmt),
        )
        assertEquals(
            "Last seen T",
            CpStaffTrackPolicy.locationLabel(CpStaffTrack.LiveFix(1.0, 1.0, 50 * min, true), now, fmt),
        )
        assertEquals("850 m travelled", CpStaffTrackPolicy.distanceLabel(850.0))
        assertEquals("3.4 km travelled", CpStaffTrackPolicy.distanceLabel(3420.0))
        assertEquals("Distance —", CpStaffTrackPolicy.distanceLabel(null))
    }
}
