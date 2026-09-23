package com.manjugroups.m_connect.ui.home

import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.JointCpSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpOutcomeContractTest {

    // A Joint CP created through a category path carries that category in
    // cpType, not "joint_cp". Deciding jointness from cpType alone made the
    // sheet demand a terminal parent status the parent must not reach yet, and
    // a save that had in fact succeeded reported "status was not finalized".
    @Test
    fun `joint visit is recognised from participants even when cpType is a category`() {
        val bookingJoint = CpVisitDetail(
            id = "cp1",
            cpType = "booking_cp",
            jointCpCategory = "booking_cp",
        )
        assertTrue(bookingJoint.isJointCpVisit())

        val svCumCpJoint = CpVisitDetail(
            id = "cp2",
            cpType = "sv_cum_cp",
            joint = JointCpSummary(totalCount = 2),
        )
        assertTrue(svCumCpJoint.isJointCpVisit())
    }

    @Test
    fun `plain cp visit is not treated as joint`() {
        val plain = CpVisitDetail(id = "cp3", cpType = "collection_cp")
        assertFalse(plain.isJointCpVisit())
    }

    @Test
    fun `joint cp type value tolerates hyphens spaces and case`() {
        assertTrue(isJointCpTypeValue("joint_cp"))
        assertTrue(isJointCpTypeValue("joint-cp"))
        assertTrue(isJointCpTypeValue("Joint CP"))
        assertTrue(isJointCpTypeValue("  JOINT_CP  "))
        assertFalse(isJointCpTypeValue("booking_cp"))
        assertFalse(isJointCpTypeValue(null))
    }

    // The end of the flow the user hit: a joint visit whose parent is still
    // open must save cleanly.
    @Test
    fun `joint not-interested save is accepted while the parent stays open`() {
        val visit = CpVisitDetail(
            id = "cp4",
            cpType = "booking_cp",
            jointCpCategory = "booking_cp",
        )
        assertNull(
            cpOutcomeConfirmationError(
                "not_interested",
                "scheduled",
                "not_interested",
                visit.isJointCpVisit(),
            ),
        )
    }

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
    fun `joint owner outcome accepts a saved draft before parent status changes`() {
        assertNull(cpOutcomeConfirmationError("interested", "pending_review", "interested", true))
        assertNull(cpOutcomeConfirmationError("interested", "completed", "interested", true))
        assertNull(cpOutcomeConfirmationError("interested", "scheduled", "interested", true))
        assertTrue(
            cpOutcomeConfirmationError("interested", "scheduled", "follow_up", true)
                ?.contains("did not confirm") == true,
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
