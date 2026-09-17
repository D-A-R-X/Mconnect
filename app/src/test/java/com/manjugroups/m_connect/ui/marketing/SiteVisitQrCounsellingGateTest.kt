package com.manjugroups.m_connect.ui.marketing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A DSV reached On Counselling without the client's QR being scanned: the
 * outcome form unlocked at On Site and advanced the visit itself. Counselling
 * must start only from the QR scan.
 */
class SiteVisitQrCounsellingGateTest {

    @Test
    fun `outcome is not recordable before counselling starts`() {
        for (status in listOf("scheduled", "client_started", "picked_up", "on_site", "on-site")) {
            assertFalse(status, siteVisitOutcomeCanBeRecorded(status, outcome = null))
        }
    }

    @Test
    fun `outcome is recordable once the QR scan started counselling`() {
        // The normal on-counselling stage must keep working.
        for (status in listOf("on_counselling", "on-counselling", "picked_from_site", "dropped", "completed")) {
            assertTrue(status, siteVisitOutcomeCanBeRecorded(status, outcome = null))
        }
    }

    @Test
    fun `a recorded outcome is never re-opened`() {
        assertFalse(siteVisitOutcomeCanBeRecorded("on_counselling", outcome = "not_interested"))
    }

    @Test
    fun `server refusal before counselling reads as scan the QR first`() {
        val raw = "Invalid transition: cannot set outcome from status \"on_site\". " +
            "Allowed: on_counselling, picked_from_site, dropped, completed."
        assertEquals(SITE_VISIT_SCAN_QR_FIRST_MESSAGE, siteVisitOutcomeUserMessage(raw))
        assertEquals(
            SITE_VISIT_SCAN_QR_FIRST_MESSAGE,
            siteVisitOutcomeUserMessage("Invalid transition: cannot set outcome from status \"scheduled\". Allowed: x"),
        )
    }

    @Test
    fun `other errors pass through unchanged`() {
        val raw = "not_interested requires at least one reason"
        assertEquals(raw, siteVisitOutcomeUserMessage(raw))
        val later = "Invalid transition: cannot set outcome from status \"cancelled\". Allowed: x"
        assertEquals(later, siteVisitOutcomeUserMessage(later))
    }
}
