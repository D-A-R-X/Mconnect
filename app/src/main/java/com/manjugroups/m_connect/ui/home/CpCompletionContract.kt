package com.manjugroups.m_connect.ui.home

import com.manjugroups.m_connect.network.JointCpSummary
import com.manjugroups.m_connect.network.JointCpWorkflow
import java.util.Locale
import kotlin.math.roundToInt

private val TERMINAL_CP_COMPLETION_STATUSES = setOf(
    "completed",
    "pending_gm_approval",
    "postponed",
    "cancelled",
    "canceled",
)

/** A CP identity is authoritative even when a legacy list row omits tripType. */
internal fun isCpBackedTrip(cpVisitId: String?): Boolean = !cpVisitId.isNullOrBlank()

/** Accepts both the enriched response and the legacy success-only contract. */
internal fun isCompatibleCpCompletionStatus(status: String?): Boolean {
    val normalized = status?.trim()?.lowercase(Locale.US)?.takeIf(String::isNotEmpty)
    return normalized == null || normalized in TERMINAL_CP_COMPLETION_STATUSES
}

/** Prefer the authoritative CP state returned by the repeat-safe completion route. */
internal fun resolvedCpCompletionStatus(
    effectiveStatus: String?,
    status: String?,
    visitEffectiveStatus: String?,
    visitStatus: String?,
): String? = sequenceOf(effectiveStatus, status, visitEffectiveStatus, visitStatus)
    .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    .firstOrNull()

/** Uses only a freshly fetched reviewer workflow for the final mutation. */
internal fun jointCpReviewRevision(workflow: JointCpWorkflow?): Long? = workflow
    ?.takeIf {
        it.actorRole.equals("reviewer", ignoreCase = true) &&
            it.actorReady != false &&
            it.canCompleteReview
    }
    ?.outcomeRevision

/** Missing readiness is not proof that the reviewer has confirmed proximity. */
internal fun jointCpReviewerNeedsReadiness(workflow: JointCpWorkflow): Boolean {
    val state = workflow.state?.trim()?.lowercase(Locale.US)?.replace('-', '_')
    return workflow.actorRole.equals("reviewer", ignoreCase = true) &&
        workflow.actorReady != true &&
        !workflow.canReview &&
        state !in setOf("pending_review", "reviewing", "completed")
}

/** A stale permission snapshot must not hide the owner's authoritative preflight. */
internal fun jointCpOwnerNeedsArrivalPreflight(
    workflow: JointCpWorkflow,
    alreadyArrived: Boolean,
): Boolean {
    val state = workflow.state?.trim()?.lowercase(Locale.US)?.replace('-', '_')
    return !alreadyArrived &&
        workflow.actorRole.equals("outcome_owner", ignoreCase = true) &&
        state !in setOf("pending_review", "reviewing", "completed")
}

internal fun jointCpRadiusMeters(workflow: JointCpWorkflow?): Int = workflow
    ?.requiredRadiusMeters
    ?.takeIf { it.isFinite() && it > 0.0 }
    ?.roundToInt()
    ?.coerceAtLeast(1)
    ?: 100

internal fun shouldRefreshJointCpPresence(
    workflow: JointCpWorkflow?,
    visitStarted: Boolean,
): Boolean {
    if (!visitStarted || workflow == null) return false
    val state = workflow.state?.trim()?.lowercase(Locale.US)?.replace('-', '_')
    if (state == "completed" || state == "cancelled") return false
    return when (workflow.actorRole?.trim()?.lowercase(Locale.US)) {
        "outcome_owner" -> workflow.canRequestOtp || workflow.canSubmitOutcome
        "reviewer" -> workflow.actorReady == true || workflow.canReview || workflow.canCompleteReview
        else -> false
    }
}

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

internal fun jointCpApiErrorMessage(
    code: String?,
    raw: String?,
    requiredRadiusMeters: Double?,
    maximumAccuracyMeters: Double?,
    maximumLocationAgeMs: Long?,
    fallback: String,
): String {
    val radius = (requiredRadiusMeters ?: 100.0).roundToInt()
    val accuracy = (maximumAccuracyMeters ?: 30.0).roundToInt()
    val ageSeconds = ((maximumLocationAgeMs ?: 60_000L) / 1_000L).coerceAtLeast(1L)
    return when (code?.trim()?.uppercase(Locale.US)) {
        "PARTNER_LOCATION_STALE" ->
            "Partner location is older than $ageSeconds seconds. Keep both phones on this visit and try again."
        "LOCATION_ACCURACY_LOW" ->
            "GPS accuracy is too low. Turn on precise location, move to an open area, and retry with $accuracy metres accuracy or better."
        "PARTNER_TOO_FAR" ->
            "Both Joint CP staff must be within $radius metres to continue."
        else -> jointCpUserMessage(raw, fallback)
    }
}
