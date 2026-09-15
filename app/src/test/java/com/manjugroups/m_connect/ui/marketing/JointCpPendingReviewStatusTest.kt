package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.JointCpParticipant
import com.manjugroups.m_connect.network.JointCpSummary
import com.manjugroups.m_connect.network.JointCpWorkflow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Joint CP is not finished when one participant's leg finishes.
 *
 * Reported from the field: the card showed "Completed" before the higher-level
 * reviewer had entered any remark. The owner's participant leg flips to
 * "completed" the moment they submit their outcome for review, and that
 * outranked every other signal in the status resolver — so the row read as done
 * and offered no way back into the workflow, while the reviewer had not even
 * looked at it.
 *
 * The visit closes only when the reviewer adds their remark and completes.
 */
class JointCpPendingReviewStatusTest {

    private val owner = "staff_junior"
    private val reviewer = "staff_senior"

    private fun joint(
        workflowState: String?,
        ownerLegStatus: String = "completed",
    ) = JointCpSummary(
        participants = listOf(
            JointCpParticipant(
                staffId = owner,
                staffName = "Junior",
                status = ownerLegStatus,
                workflowRole = "outcome_owner",
            ),
            JointCpParticipant(
                staffId = reviewer,
                staffName = "Senior",
                status = "completed",
                workflowRole = "reviewer",
            ),
        ),
        totalCount = 2,
        workflow = workflowState?.let {
            JointCpWorkflow(
                state = it,
                outcomeOwnerStaffId = owner,
                reviewerStaffId = reviewer,
            )
        },
    )

    private fun resolve(
        joint: JointCpSummary?,
        staffId: String?,
        cpStatus: String? = "in_progress",
    ) = resolveParticipantCpEffectiveStatus(
        serverEffectiveStatus = null,
        cpStatus = cpStatus,
        parentFieldVisitStatus = "in-progress",
        joint = joint,
        currentStaffId = staffId,
    )

    @Test
    fun `a submitted-but-unreviewed joint CP is not completed for the owner`() {
        // This is the reported bug: the owner's own leg is done, so the card
        // said Completed.
        assertEquals(JOINT_PENDING_REVIEW, resolve(joint("pending_review"), owner))
    }

    @Test
    fun `nor for the reviewer`() {
        assertEquals(JOINT_PENDING_REVIEW, resolve(joint("pending_review"), reviewer))
    }

    @Test
    fun `it stays pending even before the owner submits`() {
        assertEquals(
            JOINT_PENDING_REVIEW,
            resolve(joint("in_progress", ownerLegStatus = "arrived"), owner),
        )
    }

    @Test
    fun `a completed workflow does report completed`() {
        assertEquals("completed", resolve(joint("completed"), owner))
        assertEquals("completed", resolve(joint("completed"), reviewer))
    }

    @Test
    fun `a cancelled workflow is not held pending`() {
        // Terminal is terminal — a cancelled Joint CP must not sit in a
        // permanent "Pending Review" state that nobody can clear.
        assertEquals("completed", resolve(joint("cancelled"), owner))
    }

    @Test
    fun `an authoritative terminal CP status still wins`() {
        // The CP row itself being closed outranks the workflow: a cancelled or
        // completed parent must never be reopened as pending.
        assertEquals("cancelled", resolve(joint("pending_review"), owner, cpStatus = "cancelled"))
        assertEquals("completed", resolve(joint("pending_review"), owner, cpStatus = "completed"))
    }

    @Test
    fun `a payload with no workflow keeps the previous behaviour`() {
        // An older deployment, or a list shape that omits the workflow, must not
        // strand a genuinely finished visit in a pending state.
        assertEquals("completed", resolve(joint(workflowState = null), owner))
    }

    @Test
    fun `a non-joint CP is untouched`() {
        assertEquals("completed", resolve(null, owner, cpStatus = "completed"))
    }

    @Test
    fun `workflow state matching ignores case and padding`() {
        assertEquals("completed", resolve(joint(" Completed "), owner))
    }
}
