package com.manjugroups.m_connect.ui.home

import com.manjugroups.m_connect.network.JointCpWorkflow
import com.manjugroups.m_connect.network.JointCpParticipant
import com.manjugroups.m_connect.network.JointCpSummary
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

    @Test
    fun `legacy joint primary and companion recover owner and reviewer ids`() {
        val summary = JointCpSummary(
            leadStaffId = "bdo",
            participants = listOf(
                JointCpParticipant(staffId = "bdo", staffName = "BDO", isPrimary = true),
                JointCpParticipant(staffId = "manager", staffName = "Manager"),
            ),
        )
        val reviewer = resolvedJointCpWorkflowForActor(
            JointCpWorkflow(actorRole = "reviewer", canReview = true),
            currentStaffId = "manager",
            joint = summary,
        )

        assertEquals("bdo", reviewer.outcomeOwnerStaffId)
        assertEquals("manager", reviewer.reviewerStaffId)
        assertEquals("reviewer", reviewer.actorRole)
        assertTrue(reviewer.canReview)
        assertFalse(reviewer.canRequestOtp)
    }

    @Test
    fun `opaque backend ids never leak into joint cp errors`() {
        assertEquals(
            "Could not complete review",
            jointCpUserMessage("k2g8m1v4b6p9q3w7x5z0c2n8", "Could not complete review"),
        )
        assertEquals(
            "Could not complete review",
            jointCpUserMessage(
                "Visit k2g8m1v4b6p9q3w7x5z0c2n8 could not be resolved",
                "Could not complete review",
            ),
        )
        assertEquals(
            "Both staff must be within 50 metres",
            jointCpUserMessage("Both staff must be within 50 metres", "fallback"),
        )
    }

    @Test
    fun `joint submit timeout is confirmed only by review state and revision`() {
        assertTrue(
            isJointCpSubmissionConfirmed(
                JointCpWorkflow(state = "pending_review", outcomeRevision = 4),
                expectedOutcomeRevision = 4,
            ),
        )
        assertTrue(
            isJointCpSubmissionConfirmed(
                JointCpWorkflow(state = "completed", outcomeRevision = 5),
                expectedOutcomeRevision = 4,
            ),
        )
        assertFalse(
            isJointCpSubmissionConfirmed(
                JointCpWorkflow(state = "outcome_submitted", outcomeRevision = 4),
                expectedOutcomeRevision = 4,
            ),
        )
        assertFalse(
            isJointCpSubmissionConfirmed(
                JointCpWorkflow(state = "pending_review", outcomeRevision = 3),
                expectedOutcomeRevision = 4,
            ),
        )
    }

    @Test
    fun `joint completion readback confirms both participant credits when returned`() {
        assertTrue(
            isJointCpCompletionConfirmed(
                JointCpWorkflow(
                    state = "completed",
                    creditedStaffIds = listOf("bdo", "manager"),
                ),
                setOf("bdo", "manager"),
            ),
        )
        assertFalse(
            isJointCpCompletionConfirmed(
                JointCpWorkflow(
                    state = "completed",
                    creditedStaffIds = listOf("manager"),
                ),
                setOf("bdo", "manager"),
            ),
        )
        assertFalse(
            isJointCpCompletionConfirmed(
                JointCpWorkflow(state = "pending_review"),
                setOf("bdo", "manager"),
            ),
        )
    }
}
