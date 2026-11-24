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
