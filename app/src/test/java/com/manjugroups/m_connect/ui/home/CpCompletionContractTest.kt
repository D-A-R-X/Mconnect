package com.manjugroups.m_connect.ui.home

import com.manjugroups.m_connect.network.JointCpWorkflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpCompletionContractTest {
    @Test
    fun `legacy success response may omit status`() {
        assertTrue(isCompatibleCpCompletionStatus(null))
        assertTrue(isCompatibleCpCompletionStatus("  "))
    }

    @Test
    fun `terminal and approval statuses are accepted`() {
        assertTrue(isCompatibleCpCompletionStatus("completed"))
        assertTrue(isCompatibleCpCompletionStatus("Pending_GM_Approval"))
        assertTrue(isCompatibleCpCompletionStatus("postponed"))
        assertTrue(isCompatibleCpCompletionStatus("cancelled"))
    }

    @Test
    fun `nonterminal status remains a contract failure`() {
        assertFalse(isCompatibleCpCompletionStatus("scheduled"))
        assertFalse(isCompatibleCpCompletionStatus("in_progress"))
    }

    @Test
    fun `joint reviewer completion uses the freshly authorized revision`() {
        assertEquals(
            7L,
            jointCpReviewRevision(
                JointCpWorkflow(
                    actorRole = "reviewer",
                    actorReady = true,
                    canCompleteReview = true,
                    outcomeRevision = 7,
                ),
            ),
        )
        assertEquals(
            8L,
            jointCpReviewRevision(
                JointCpWorkflow(
                    actorRole = "reviewer",
                    actorReady = null,
                    canCompleteReview = true,
                    outcomeRevision = 8,
                ),
            ),
        )
    }

    @Test
    fun `joint outcome owner or unready reviewer cannot complete review`() {
        assertNull(jointCpReviewRevision(JointCpWorkflow(actorRole = "outcome_owner", canCompleteReview = true, outcomeRevision = 7)))
        assertNull(jointCpReviewRevision(JointCpWorkflow(actorRole = "reviewer", canCompleteReview = false, outcomeRevision = 7)))
        assertNull(jointCpReviewRevision(JointCpWorkflow(actorRole = "reviewer", actorReady = false, canCompleteReview = true, outcomeRevision = 7)))
    }

    @Test
    fun `actor role is derived from authoritative participant ids when omitted`() {
        val verified = verifiedJointCpWorkflowForActor(
            JointCpWorkflow(
                outcomeOwnerStaffId = "bdo",
                reviewerStaffId = "senior-manager",
                canRequestOtp = true,
            ),
            "bdo",
        )

        assertEquals("outcome_owner", verified.actorRole)
        assertTrue(verified.canRequestOtp)
        assertFalse(verified.canReview)
    }

    @Test
    fun `contradictory server role disables every joint mutation`() {
        val verified = verifiedJointCpWorkflowForActor(
            JointCpWorkflow(
                actorRole = "outcome_owner",
                outcomeOwnerStaffId = "bdo",
                reviewerStaffId = "senior-manager",
                canRequestOtp = true,
                canSubmitOutcome = true,
                canReview = true,
                canCompleteReview = true,
            ),
            "senior-manager",
        )

        assertNull(verified.actorRole)
        assertFalse(verified.canRequestOtp)
        assertFalse(verified.canSubmitOutcome)
        assertFalse(verified.canReview)
        assertFalse(verified.canCompleteReview)
    }

    @Test
    fun `reviewer can never inherit otp or owner outcome permissions`() {
        val verified = verifiedJointCpWorkflowForActor(
            JointCpWorkflow(
                actorRole = "reviewer",
                outcomeOwnerStaffId = "bdo",
                reviewerStaffId = "senior-manager",
                canRequestOtp = true,
                canSubmitOutcome = true,
                canReview = true,
                canCompleteReview = true,
            ),
            "senior-manager",
        )

        assertEquals("reviewer", verified.actorRole)
        assertFalse(verified.canRequestOtp)
        assertFalse(verified.canSubmitOutcome)
        assertTrue(verified.canReview)
        assertTrue(verified.canCompleteReview)
    }
}
