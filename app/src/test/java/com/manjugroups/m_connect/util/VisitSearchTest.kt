package com.manjugroups.m_connect.util

import com.manjugroups.m_connect.network.TodayVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisitSearchTest {
    private val visit = TodayVisit(
        id = "visit-1",
        clientPlaceId = "place-1",
        scheduledDate = "2026-09-10",
        status = "scheduled",
        placeName = "Client",
        leadPhone = "9000000001",
    )

    @Test
    fun `formatted Indian phone becomes the ten digit server query`() {
        assertEquals("9840032837", VisitSearch.serverQuery("+91 98400-32837"))
        assertEquals("9840032837", VisitSearch.serverQuery("91 98400 32837"))
        assertEquals("98400", VisitSearch.serverQuery("98400"))
    }

    @Test
    fun `text query stays unchanged apart from outside whitespace`() {
        assertEquals("Sasi Kumar", VisitSearch.serverQuery("  Sasi Kumar  "))
    }

    @Test
    fun `matching server search preserves row absent from compact phone field`() {
        assertTrue(
            VisitSearch.matchesLoadedServerResult(
                visit = visit,
                rawQuery = "9840032837",
                loadedServerQuery = "9840032837",
            ),
        )
        assertFalse(
            VisitSearch.matchesLoadedServerResult(
                visit = visit,
                rawQuery = "9840032837",
                loadedServerQuery = "9000000001",
            ),
        )
    }
}
