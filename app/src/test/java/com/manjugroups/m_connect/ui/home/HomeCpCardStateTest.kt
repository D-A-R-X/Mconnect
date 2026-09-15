package com.manjugroups.m_connect.ui.home

import com.manjugroups.m_connect.ui.marketing.JOINT_PENDING_REVIEW
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Home's trip card must never fall back to "Start Trip" for a visit the staff
 * member has already finished.
 *
 * Reported from the field: after completing the OTP and the outcome, Home still
 * said "Start Trip". Home's card ran a `when` over needsCpDetails / isInProgress
 * / isFleetOutcomePending / isCompleted / !canStartTrip and then a bare `else`
 * that rendered Start Trip — so ANY status outside those sets landed on Start.
 *
 * Two statuses fell through that hole:
 *   • pending_joint_review — Joint CP outcome submitted, awaiting the reviewer
 *   • pending_gm_approval  — outcome recorded out of geofence, held for the GM
 *
 * Both mean "done, waiting on someone else". These tests pin the classification
 * the card branches on, so a future status cannot silently reopen the hole.
 */
class HomeCpCardStateTest {

    // Mirrors HomeFragment's sets verbatim.
    private fun isCompleted(status: String) =
        status in setOf("completed", "complete", "done", "closed")

    private fun isInProgress(status: String) = status in setOf(
        "in-progress", "in_progress", "ongoing", "started", "active", "arrived",
        "on_site", "on-site", "on_counselling", "on-counselling", "picked_from_site",
    )

    private fun isJointPendingReview(status: String) = status == JOINT_PENDING_REVIEW

    private fun isPendingGmApproval(status: String) =
        status == "pending_gm_approval" || status == "pending-gm-approval"

    /** True when the card would have shown "Start Trip" before the fix. */
    private fun fallsThroughToStartTrip(raw: String): Boolean {
        val status = raw.lowercase(Locale.US)
        return !isCompleted(status) &&
            !isInProgress(status) &&
            !isJointPendingReview(status) &&
            !isPendingGmApproval(status)
    }

    @Test
    fun `a joint CP awaiting review does not offer Start Trip`() {
        assertTrue(isJointPendingReview(JOINT_PENDING_REVIEW))
        assertFalse(fallsThroughToStartTrip(JOINT_PENDING_REVIEW))
    }

    @Test
    fun `a CP held for GM approval does not offer Start Trip`() {
        assertFalse(fallsThroughToStartTrip("pending_gm_approval"))
        assertFalse(fallsThroughToStartTrip("pending-gm-approval"))
    }

    @Test
    fun `neither is mistaken for completed or in progress`() {
        // They must hit their OWN branches: treating them as completed would
        // hide the action, and as in-progress would offer "Complete Trip" on a
        // visit that is already finished.
        listOf(JOINT_PENDING_REVIEW, "pending_gm_approval").forEach {
            assertFalse(it, isCompleted(it))
            assertFalse(it, isInProgress(it))
        }
    }

    @Test
    fun `a genuinely unstarted visit still offers Start Trip`() {
        listOf("scheduled", "assigned", "pending").forEach {
            assertTrue(it, fallsThroughToStartTrip(it))
        }
    }

    @Test
    fun `live and finished visits keep their own branches`() {
        assertTrue(isInProgress("arrived"))
        assertTrue(isCompleted("completed"))
        assertFalse(fallsThroughToStartTrip("arrived"))
        assertFalse(fallsThroughToStartTrip("completed"))
    }
}
