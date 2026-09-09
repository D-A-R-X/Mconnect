package com.manjugroups.m_connect.ui.home

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpOutcomeContractTest {

    @Test
    fun `ordinary outcome requires matching value and terminal parent status`() {
        assertNull(cpOutcomeConfirmationError("interested", "completed", "interested", false))
        assertNull(cpOutcomeConfirmationError("interested", "pending-gm-approval", "interested", false))
        assertTrue(
            cpOutcomeConfirmationError("interested", "scheduled", "interested", false)
                ?.contains("status was not finalized") == true,
        )
    }

    @Test
    fun `ordinary outcome rejects a different saved value`() {
        assertTrue(
            cpOutcomeConfirmationError("not_interested", "completed", "interested", false)
                ?.contains("did not confirm") == true,
        )
    }

    @Test
    fun `joint outcome accepts review states but not an untouched visit`() {
        assertNull(cpOutcomeConfirmationError("interested", "pending_review", "interested", true))
        assertNull(cpOutcomeConfirmationError("interested", "completed", "interested", true))
        assertTrue(
            cpOutcomeConfirmationError("interested", "scheduled", "interested", true)
                ?.contains("status was not finalized") == true,
        )
    }

    @Test
    fun `conversion requires matching terminal outcome and linked record`() {
        assertNull(
            cpConversionConfirmationError(
                "converted_to_booking",
                "completed",
                "converted_to_booking",
                "booking-1",
                "booking-1",
            ),
        )
        assertTrue(
            cpConversionConfirmationError(
                "converted_to_site_visit",
                "completed",
                "converted_to_site_visit",
                "sv-1",
                null,
            )?.contains("not linked") == true,
        )
    }

    @Test
    fun `site visit outcome requires matching value and terminal status`() {
        assertNull(siteVisitOutcomeConfirmationError("interested", "completed", "interested"))
        assertNull(siteVisitOutcomeConfirmationError("postponed", "completed", "follow_up"))
        assertTrue(
            siteVisitOutcomeConfirmationError("interested", "on_counselling", "interested")
                ?.contains("not finalized") == true,
        )
        assertTrue(
            siteVisitOutcomeConfirmationError("interested", "completed", "not_interested")
                ?.contains("did not confirm") == true,
        )
    }
}
