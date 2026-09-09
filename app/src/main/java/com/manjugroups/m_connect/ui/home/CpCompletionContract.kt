package com.manjugroups.m_connect.ui.home

import com.manjugroups.m_connect.network.JointCpSummary
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

internal fun isJointCpSubmissionConfirmed(
    workflow: JointCpWorkflow?,
    expectedOutcomeRevision: Long?,
): Boolean {
    val state = workflow?.state?.trim()?.lowercase(Locale.US)?.replace('-', '_')
    if (state !in setOf("pending_review", "reviewing", "completed")) return false
    if (expectedOutcomeRevision == null) return true
    return workflow?.outcomeRevision?.let { it >= expectedOutcomeRevision } == true
}

internal fun isJointCpCompletionConfirmed(
    workflow: JointCpWorkflow?,
    expectedCreditedStaffIds: Set<String>,
): Boolean {
    if (!workflow?.state.equals("completed", ignoreCase = true)) return false
    val credits = workflow?.creditedStaffIds?.map(String::trim)?.filter(String::isNotEmpty)
        ?: return true
    return credits.toSet().containsAll(expectedCreditedStaffIds)
}

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

/**
 * Recovers legacy Joint summaries that identify the lower-level primary leg
 * but omit the newer explicit workflow IDs. Explicit server IDs always win.
 */
internal fun resolvedJointCpWorkflowForActor(
    workflow: JointCpWorkflow,
    currentStaffId: String?,
    joint: JointCpSummary?,
): JointCpWorkflow {
    val participants = joint?.participants.orEmpty()
    val explicitOwner = workflow.outcomeOwnerStaffId?.trim()?.takeIf(String::isNotEmpty)
    val owner = explicitOwner
        ?: participants.firstOrNull { it.workflowRole.equals("outcome_owner", true) }
            ?.staffId?.trim()?.takeIf(String::isNotEmpty)
        ?: joint?.leadStaffId?.trim()?.takeIf(String::isNotEmpty)
        ?: participants.firstOrNull { it.isPrimary }?.staffId?.trim()?.takeIf(String::isNotEmpty)
    val explicitReviewer = workflow.reviewerStaffId?.trim()?.takeIf(String::isNotEmpty)
    val reviewer = explicitReviewer
        ?: participants.firstOrNull { it.workflowRole.equals("reviewer", true) }
            ?.staffId?.trim()?.takeIf(String::isNotEmpty)
        ?: participants.firstOrNull { participant ->
            val id = participant.staffId?.trim()
            !id.isNullOrEmpty() && id != owner
        }?.staffId?.trim()

    val enriched = workflow.copy(
        outcomeOwnerStaffId = owner,
        outcomeOwnerName = workflow.outcomeOwnerName
            ?: participants.firstOrNull { it.staffId?.trim() == owner }?.staffName,
        reviewerStaffId = reviewer,
        reviewerName = workflow.reviewerName
            ?: participants.firstOrNull { it.staffId?.trim() == reviewer }?.staffName,
    )
    return verifiedJointCpWorkflowForActor(enriched, currentStaffId)
}

internal fun jointCpUserMessage(raw: String?, fallback: String): String {
    val cleaned = raw
        ?.substringBefore('\n')
        ?.removePrefix("Uncaught Error:")
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: return fallback
    val opaqueId = Regex("^[a-z0-9]{20,}$", RegexOption.IGNORE_CASE)
    val containsOpaqueId = Regex("\\b[a-z][a-z0-9]{19,}\\b", RegexOption.IGNORE_CASE)
    return if (opaqueId.matches(cleaned) || containsOpaqueId.containsMatchIn(cleaned)) fallback else cleaned
}
