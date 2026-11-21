package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpSavedDecisionTest {
    @Test fun `completed travel does not hide an active CP outcome but terminals and review stay unchanged`() {
        assertEquals("arrived", cpOutcomeCaptureStatus("in_progress", "completed"))
        assertEquals("arrived", cpOutcomeCaptureStatus("scheduled", "done"))
        assertEquals("pending_review", cpOutcomeCaptureStatus("in_progress", "pending_review"))
        assertEquals("completed", cpOutcomeCaptureStatus("completed", "completed"))
        assertEquals("pending_gm_approval", cpOutcomeCaptureStatus("pending_gm_approval", "pending_gm_approval"))
        assertFalse(isCpDecisionCommitted("in_progress"))
        assertTrue(isCpDecisionCommitted(" PENDING-GM-APPROVAL "))
        assertTrue(isCpDecisionCommitted("cancelled"))
    }
    @Test
    fun `pending SV link is setup rather than a confirmed outcome`() {
        for (status in listOf(null, "scheduled", "in_progress", "arrived", "on_site")) {
            assertNull(status, confirmedCpOutcome(status, null, "sv-precreated"))
        }
    }

    @Test
    fun `stale or draft outcome does not silently close an active visit`() {
        for (status in listOf(null, "scheduled", "in_progress", "arrived", "pending_review")) {
            assertNull(status, confirmedCpOutcome(status, "interested", "sv-precreated"))
        }
    }

    @Test
    fun `committed outcome survives reopening before final trip close`() {
        for (status in listOf("completed", "postponed", "pending_gm_approval", " PENDING-GM-APPROVAL ")) {
            assertEquals("interested", confirmedCpOutcome(status, " interested ", "sv1"))
        }
    }

    @Test
    fun `only actually completed legacy conversions can use linkage fallback`() {
        assertEquals("converted_to_site_visit", confirmedCpOutcome("completed", null, "sv1"))
        assertEquals("converted_to_booking", confirmedCpOutcome("done", " ", null, "booking1"))
        assertNull(confirmedCpOutcome("pending_gm_approval", null, "sv1"))
        assertNull(confirmedCpOutcome("completed", null, " ", " "))
    }

    @Test
    fun `special type decisions are preserved after a real save`() {
        for (outcome in listOf("old_client_visited", "gift_distributed", "collection_done", "not_collected", "rejected")) {
            assertEquals(outcome, confirmedCpOutcome("completed", outcome))
            assertNull(confirmedCpOutcome("in_progress", outcome))
        }
    }
}
