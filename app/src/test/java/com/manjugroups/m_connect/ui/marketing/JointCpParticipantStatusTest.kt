package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.JointCpParticipant
import com.manjugroups.m_connect.network.JointCpSummary
import com.manjugroups.m_connect.network.JointCpWorkflow
import com.manjugroups.m_connect.ui.home.isJointCpSubmissionConfirmed
import com.manjugroups.m_connect.ui.home.jointCpAwaitingReviewerTrip
import com.manjugroups.m_connect.ui.home.jointCpOutcomeOwnerCanEnterOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Joint CP flow defects found in the end-to-end audit.
 */
class JointCpParticipantStatusTest {

    private val owner = "junior"
    private val reviewer = "senior"

    private fun joint(
        state: String = "awaiting_both_trips",
        ownerLeg: String = "in_progress",
        reviewerLeg: String = "scheduled",
        reviewerFieldVisitId: String? = "fv_senior",
        ownerTrip: String? = null,
        reviewerTrip: String? = null,
    ) = JointCpSummary(
        participants = listOf(
            JointCpParticipant(staffId = owner, status = ownerLeg, fieldVisitId = "fv_junior", workflowRole = "outcome_owner"),
            JointCpParticipant(staffId = reviewer, status = reviewerLeg, fieldVisitId = reviewerFieldVisitId, workflowRole = "reviewer"),
        ),
        workflow = JointCpWorkflow(
            state = state,
            outcomeOwnerStaffId = owner,
            reviewerStaffId = reviewer,
            ownerTripStatus = ownerTrip,
            reviewerTripStatus = reviewerTrip,
        ),
    )

    private fun status(joint: JointCpSummary, staff: String, otp: Long? = null) =
        resolveParticipantCpEffectiveStatus(
            serverEffectiveStatus = "in_progress", // derived from the OWNER's field visit
            cpStatus = "in_progress",
            parentFieldVisitStatus = "arrived",
            joint = joint,
            currentStaffId = staff,
            arrivalOtpVerifiedAt = otp,
            parentFieldVisitId = "fv_junior",
        )

    @Test
    fun `reviewer still sees Start after the junior starts and arrives`() {
        // Regression: the reviewer read the owner's progress and never got Start Trip.
        assertEquals("scheduled", status(joint(), reviewer, otp = 1_789_000_000_000L))
    }

    @Test
    fun `reviewer sees their own started trip`() {
        assertEquals("in_progress", status(joint(reviewerLeg = "in_progress", reviewerTrip = "in_progress"), reviewer))
    }

    @Test
    fun `owner still sees their own arrival from the parent trip`() {
        assertEquals("arrived", status(joint(), owner, otp = 1_789_000_000_000L))
    }

    @Test
    fun `reviewer never falls back to the owner's field visit id`() {
        val noLegId = joint(reviewerFieldVisitId = null)
        assertEquals("cp_1", resolveCpFieldVisitId("cp_1", "fv_junior", noLegId, reviewer))
        assertEquals("fv_junior", resolveCpFieldVisitId("cp_1", "fv_parent", noLegId, owner))
    }

    @Test
    fun `owner cannot enter the outcome until the reviewer starts`() {
        val waiting = JointCpWorkflow(state = "awaiting_both_trips", actorRole = "outcome_owner")
        assertTrue(jointCpAwaitingReviewerTrip(waiting))
        assertFalse(jointCpOutcomeOwnerCanEnterOutcome(waiting))
        assertTrue(jointCpOutcomeOwnerCanEnterOutcome(waiting.copy(state = "awaiting_owner_outcome")))
    }

    @Test
    fun `a direct completion without review is not a confirmed submit`() {
        assertFalse(isJointCpSubmissionConfirmed(JointCpWorkflow(state = "completed", outcomeRevision = 5), 4))
        assertTrue(
            isJointCpSubmissionConfirmed(
                JointCpWorkflow(state = "completed", outcomeRevision = 5, submittedAt = 1L, reviewedAt = 2L),
                4,
            ),
        )
    }
}
