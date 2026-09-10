package com.manjugroups.m_connect.ui.home

import com.google.gson.Gson
import com.manjugroups.m_connect.network.GeoTrackResponse
import com.manjugroups.m_connect.network.JointCpWorkflow
import com.manjugroups.m_connect.network.JointCpWorkflowResponse
import com.manjugroups.m_connect.network.JointCpParticipant
import com.manjugroups.m_connect.network.JointCpSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpCompletionContractTest {
    @Test
    fun `cp id keeps legacy trip in cp flow when trip type metadata is missing`() {
        assertTrue(isCpBackedTrip("cp-visit-1"))
        assertFalse(isCpBackedTrip(null))
        assertFalse(isCpBackedTrip("  "))
    }

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
    fun `repeat completion response uses authoritative effective status`() {
        val response = Gson().fromJson(
            """{
                "success": true,
                "fieldVisitId": "field-1",
                "clientPlaceVisitId": "cp-1",
                "status": "completed",
                "effectiveStatus": "pending_gm_approval",
                "alreadyCompleted": true,
                "visit": {
                    "_id": "cp-1",
                    "status": "completed",
                    "effectiveStatus": "pending_gm_approval",
                    "outcome": "old_client_visited"
                },
                "fieldVisit": {
                    "_id": "field-1",
                    "status": "completed",
                    "completedAt": 1757410000000
                },
                "completionProof": { "distanceMeters": 42 }
            }""".trimIndent(),
            GeoTrackResponse::class.java,
        )

        assertTrue(response.alreadyCompleted == true)
        assertEquals("field-1", response.fieldVisitId)
        assertEquals("completed", response.fieldVisit?.status)
        assertEquals("old_client_visited", response.visit?.outcome)
        assertEquals(
            "pending_gm_approval",
            resolvedCpCompletionStatus(
                response.effectiveStatus,
                response.status,
                response.visit?.effectiveStatus,
                response.visit?.status,
            ),
        )
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
    fun `workflow response maps effective template snapshots and owner controls`() {
        val response = Gson().fromJson(
            """{
                "success": true,
                "workflow": {
                    "state": "in_progress",
                    "actorRole": "outcome_owner",
                    "outcomeOwnerStaffId": "staff-low",
                    "outcomeOwnerName": "Lower staff",
                    "outcomeOwnerTemplateName": "Sales Level 3",
                    "outcomeOwnerTemplateLevel": 3,
                    "reviewerStaffId": "staff-high",
                    "reviewerName": "Higher staff",
                    "reviewerTemplateName": "Sales Level 4",
                    "reviewerTemplateLevel": 4,
                    "canRequestOtp": true,
                    "canSubmitOutcome": true,
                    "canReview": false,
                    "canCompleteReview": false
                }
            }""".trimIndent(),
            JointCpWorkflowResponse::class.java,
        )
        val verified = verifiedJointCpWorkflowForActor(response.workflow!!, "staff-low")

        assertEquals("Sales Level 3", verified.outcomeOwnerTemplateName)
        assertEquals(3, verified.outcomeOwnerTemplateLevel)
        assertEquals("Sales Level 4", verified.reviewerTemplateName)
        assertEquals(4, verified.reviewerTemplateLevel)
        assertTrue(verified.canRequestOtp)
        assertTrue(verified.canSubmitOutcome)
        assertFalse(verified.canReview)
        assertFalse(verified.canCompleteReview)
    }

    @Test
    fun `joint outcome owner or unready reviewer cannot complete review`() {
        assertNull(jointCpReviewRevision(JointCpWorkflow(actorRole = "outcome_owner", canCompleteReview = true, outcomeRevision = 7)))
        assertNull(jointCpReviewRevision(JointCpWorkflow(actorRole = "reviewer", canCompleteReview = false, outcomeRevision = 7)))
        assertNull(jointCpReviewRevision(JointCpWorkflow(actorRole = "reviewer", actorReady = false, canCompleteReview = true, outcomeRevision = 7)))
    }

    @Test
    fun `reviewer with omitted readiness must receive proximity action`() {
        assertTrue(
            jointCpReviewerNeedsReadiness(
                JointCpWorkflow(
                    state = "in_progress",
                    actorRole = "reviewer",
                    actorReady = null,
                    canReview = false,
                ),
            ),
        )
        assertFalse(
            jointCpReviewerNeedsReadiness(
                JointCpWorkflow(
                    state = "in_progress",
                    actorRole = "reviewer",
                    actorReady = true,
                    canReview = false,
                ),
            ),
        )
    }

    @Test
    fun `submitted outcome never asks reviewer to repeat readiness`() {
        assertFalse(
            jointCpReviewerNeedsReadiness(
                JointCpWorkflow(
                    state = "pending_review",
                    actorRole = "reviewer",
                    actorReady = null,
                    canReview = true,
                ),
            ),
        )
    }

    @Test
    fun `stale otp permission never hides owner preflight`() {
        assertTrue(
            jointCpOwnerNeedsArrivalPreflight(
                workflow = JointCpWorkflow(
                    state = "in_progress",
                    actorRole = "outcome_owner",
                    canRequestOtp = false,
                ),
                alreadyArrived = false,
            ),
        )
        assertFalse(
            jointCpOwnerNeedsArrivalPreflight(
                workflow = JointCpWorkflow(
                    state = "pending_review",
                    actorRole = "outcome_owner",
                    canRequestOtp = false,
                ),
                alreadyArrived = false,
            ),
        )
    }

    @Test
    fun `joint proximity codes retain server limits in staff messages`() {
        assertEquals(
            "Partner location is older than 60 seconds. Keep both phones on this visit and try again.",
            jointCpApiErrorMessage(
                code = "PARTNER_LOCATION_STALE",
                raw = null,
                requiredRadiusMeters = 100.0,
                maximumAccuracyMeters = 30.0,
                maximumLocationAgeMs = 60_000,
                fallback = "fallback",
            ),
        )
        assertEquals(
            "GPS accuracy is too low. Turn on precise location, move to an open area, and retry with 30 metres accuracy or better.",
            jointCpApiErrorMessage(
                code = "LOCATION_ACCURACY_LOW",
                raw = null,
                requiredRadiusMeters = 100.0,
                maximumAccuracyMeters = 30.0,
                maximumLocationAgeMs = 60_000,
                fallback = "fallback",
            ),
        )
        assertEquals(
            "Both Joint CP staff must be within 100 metres to continue.",
            jointCpApiErrorMessage(
                code = "PARTNER_TOO_FAR",
                raw = null,
                requiredRadiusMeters = 100.0,
                maximumAccuracyMeters = 30.0,
                maximumLocationAgeMs = 60_000,
                fallback = "fallback",
            ),
        )
    }

    @Test
    fun `joint radius follows server during rollout and defaults to one hundred`() {
        assertEquals(50, jointCpRadiusMeters(JointCpWorkflow(requiredRadiusMeters = 50.0)))
        assertEquals(100, jointCpRadiusMeters(JointCpWorkflow(requiredRadiusMeters = 100.0)))
        assertEquals(100, jointCpRadiusMeters(null))
    }

    @Test
    fun `accepted joint presence refreshes only while an actor is active`() {
        assertTrue(
            shouldRefreshJointCpPresence(
                JointCpWorkflow(actorRole = "outcome_owner", canRequestOtp = true),
                visitStarted = true,
            ),
        )
        assertTrue(
            shouldRefreshJointCpPresence(
                JointCpWorkflow(actorRole = "reviewer", actorReady = true),
                visitStarted = true,
            ),
        )
        assertFalse(
            shouldRefreshJointCpPresence(
                JointCpWorkflow(actorRole = "reviewer", actorReady = false),
                visitStarted = true,
            ),
        )
        assertFalse(
            shouldRefreshJointCpPresence(
                JointCpWorkflow(state = "completed", actorRole = "reviewer", actorReady = true),
                visitStarted = true,
            ),
        )
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
