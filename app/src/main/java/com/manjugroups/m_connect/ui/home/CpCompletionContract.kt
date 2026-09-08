package com.manjugroups.m_connect.ui.home

import com.manjugroups.m_connect.network.JointCpWorkflow
import java.util.Locale

private val TERMINAL_CP_COMPLETION_STATUSES = setOf(
    "completed",
    "pending_gm_approval",
    "postponed",
    "cancelled",
    "canceled",
)

/** Accepts both the enriched response and the legacy success-only contract. */
internal fun isCompatibleCpCompletionStatus(status: String?): Boolean {
    val normalized = status?.trim()?.lowercase(Locale.US)?.takeIf(String::isNotEmpty)
    return normalized == null || normalized in TERMINAL_CP_COMPLETION_STATUSES
}

/** Uses only a freshly fetched reviewer workflow for the final mutation. */
internal fun jointCpReviewRevision(workflow: JointCpWorkflow?): Long? = workflow
    ?.takeIf {
        it.actorRole.equals("reviewer", ignoreCase = true) &&
            it.actorReady != false &&
            it.canCompleteReview
    }
    ?.outcomeRevision

/**
 * Treat participant IDs as the authoritative actor mapping and reject any
 * contradictory role/permission response. This prevents a reviewer from ever
 * receiving OTP/outcome controls because of a stale or inverted actorRole.
 */
internal fun verifiedJointCpWorkflowForActor(
    workflow: JointCpWorkflow,
    currentStaffId: String?,
): JointCpWorkflow {
    val actorId = currentStaffId?.trim()?.takeIf(String::isNotEmpty)
    val ownerId = workflow.outcomeOwnerStaffId?.trim()?.takeIf(String::isNotEmpty)
    val reviewerId = workflow.reviewerStaffId?.trim()?.takeIf(String::isNotEmpty)
    val declaredRole = workflow.actorRole?.trim()?.lowercase(Locale.US)
    val expectedRole = when {
        actorId == null || ownerId == null || reviewerId == null || ownerId == reviewerId -> null
        actorId == ownerId -> "outcome_owner"
        actorId == reviewerId -> "reviewer"
        else -> null
    }
    val contractValid = expectedRole != null &&
        (declaredRole == null || declaredRole == expectedRole)

    if (!contractValid) {
        return workflow.copy(
            actorRole = null,
            canRequestOtp = false,
            canSubmitOutcome = false,
            canReview = false,
            canCompleteReview = false,
        )
    }

    return workflow.copy(
        actorRole = expectedRole,
        canRequestOtp = workflow.canRequestOtp && expectedRole == "outcome_owner",
        canSubmitOutcome = workflow.canSubmitOutcome && expectedRole == "outcome_owner",
        canReview = workflow.canReview && expectedRole == "reviewer",
        canCompleteReview = workflow.canCompleteReview && expectedRole == "reviewer",
    )
}
