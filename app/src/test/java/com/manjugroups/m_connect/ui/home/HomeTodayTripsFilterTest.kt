package com.manjugroups.m_connect.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTodayTripsFilterTest {

    @Test
    fun `terminal CP statuses leave Today's Trip, including postponed`() {
        listOf("completed", "complete", "done", "closed", "cancelled", "canceled", "postponed")
            .forEach { assertTrue(it, isHiddenFromTodayTrips(it)) }
        // Server casing / padding must not resurrect a closed card.
        assertTrue(isHiddenFromTodayTrips(" Postponed "))
    }

    @Test
    fun `live and held CPs stay on Today's Trip`() {
        // Held for GM approval and joint pending review render as their own
        // non-actionable states on Home; they are not finished.
        listOf(
            "scheduled", "in_progress", "in-progress", "arrived",
            "pending_gm_approval", "pending_review",
        ).forEach { assertFalse(it, isHiddenFromTodayTrips(it)) }
        assertFalse(isHiddenFromTodayTrips(null))
        assertFalse(isHiddenFromTodayTrips(""))
    }

    @Test
    fun `home CP merge asks for the route maximum instead of its default 10`() {
        assertEquals(200, HOME_CP_MERGE_LIMIT)
    }
}
