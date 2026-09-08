package com.manjugroups.m_connect.ui.home

import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.GeoTrackResponse
import com.manjugroups.m_connect.network.JointCpWorkflow
import com.manjugroups.m_connect.network.SetOutcomeRequest
import java.util.Locale

private val TERMINAL_OUTCOME_STATUSES = setOf(
    "completed",
    "complete",
    "done",
    "closed",
    "pending_gm_approval",
    "postponed",
    "cancelled",
    "canceled",
    "rejected",
)

private val JOINT_OUTCOME_STATES = setOf(
    "outcome_submitted",
    "pending_review",
    "reviewing",
    "completed",
)

private fun String?.contractValue(): String =
    this?.trim()?.lowercase(Locale.US)?.replace('-', '_').orEmpty()

internal fun cpOutcomeConfirmationError(
    expectedOutcome: String,
    status: String?,
    actualOutcome: String?,
    jointCp: Boolean,
): String? {
    val expected = expectedOutcome.contractValue()
    val actual = actualOutcome.contractValue()
    if (actual != expected) {
        return "The server did not confirm the saved CP outcome. Refresh the visit and try again."
    }

    val normalizedStatus = status.contractValue()
    val allowed = if (jointCp) JOINT_OUTCOME_STATES else TERMINAL_OUTCOME_STATUSES
    if (normalizedStatus !in allowed) {
        return "The outcome was received, but the CP status was not finalized. Refresh the visit and try again."
    }
    return null
}

internal fun cpConversionConfirmationError(
    expectedOutcome: String,
    status: String?,
    actualOutcome: String?,
    expectedLinkedId: String,
    actualLinkedId: String?,
): String? {
    cpOutcomeConfirmationError(
        expectedOutcome = expectedOutcome,
        status = status,
        actualOutcome = actualOutcome,
        jointCp = false,
    )?.let { return it }
    if (actualLinkedId?.trim() != expectedLinkedId.trim()) {
        return "The conversion was created, but it was not linked to the CP. Refresh the visit before retrying."
    }
    return null
}

private fun GeoTrackResponse.responseVisitState(): Pair<String?, String?> =
    (visit?.status ?: cpVisitStatus ?: status) to (visit?.outcome ?: outcome)

private fun CpVisitDetail.visitState(): Pair<String?, String?> = status to outcome

private fun JointCpWorkflow.workflowState(): Pair<String?, String?> = state to outcome

/**
 * Records a CP outcome and proves that the authoritative CP/workflow read
 * accepted it before the UI emits completion or closes the field trip.
 */
internal suspend fun GeoTrackApi.setCpVisitOutcomeConfirmed(
    token: String,
    request: SetOutcomeRequest,
    actingStaffId: String?,
    jointCp: Boolean,
): GeoTrackResponse {
    val response = setCpVisitOutcome(
        token,
        request.copy(actingStaffId = request.actingStaffId ?: actingStaffId),
    )
    if (!response.success) return response

    if (jointCp) {
        val workflowResponse = runCatching { getJointCpWorkflow(token, request.id) }
            .getOrElse {
                return response.copy(
                    success = false,
                    error = "The outcome was sent, but Joint CP confirmation could not be loaded. Refresh the visit before retrying.",
                )
            }
        val workflow = workflowResponse.workflow
        val error = if (!workflowResponse.success || workflow == null) {
            workflowResponse.error
                ?: "The outcome was sent, but Joint CP confirmation was not returned. Refresh the visit before retrying."
        } else {
            val (state, outcome) = workflow.workflowState()
            cpOutcomeConfirmationError(request.outcome, state, outcome, jointCp = true)
        }
        return if (error == null) {
            response.copy(
                status = response.status ?: workflow?.state,
                outcome = response.outcome ?: workflow?.outcome,
            )
        } else {
            response.copy(success = false, error = error)
        }
    }

    var (status, outcome) = response.responseVisitState()
    if (cpOutcomeConfirmationError(request.outcome, status, outcome, jointCp = false) != null) {
        val detailResponse = runCatching { getCpVisitDetail(token, request.id) }
            .getOrElse {
                return response.copy(
                    success = false,
                    error = "The outcome was sent, but the completed CP could not be confirmed. Refresh the visit before retrying.",
                )
            }
        val detail = detailResponse.visit
        if (!detailResponse.success || detail == null) {
            return response.copy(
                success = false,
                error = detailResponse.error
                    ?: "The outcome was sent, but the completed CP was not returned. Refresh the visit before retrying.",
            )
        }
        detail.visitState().also {
            status = it.first
            outcome = it.second
        }
    }

    val error = cpOutcomeConfirmationError(request.outcome, status, outcome, jointCp = false)
    return if (error == null) {
        response.copy(
            status = response.status ?: status,
            outcome = response.outcome ?: outcome,
        )
    } else {
        response.copy(success = false, error = error)
    }
}

internal suspend fun GeoTrackApi.confirmCpConversion(
    token: String,
    cpVisitId: String,
    expectedOutcome: String,
    expectedLinkedId: String,
    siteVisit: Boolean,
): String? {
    val response = runCatching { getCpVisitDetail(token, cpVisitId) }
        .getOrElse {
            return "The conversion was created, but the completed CP could not be confirmed. Refresh the visit before retrying."
        }
    val visit = response.visit
    if (!response.success || visit == null) {
        return response.error
            ?: "The conversion was created, but the completed CP was not returned. Refresh the visit before retrying."
    }
    return cpConversionConfirmationError(
        expectedOutcome = expectedOutcome,
        status = visit.status,
        actualOutcome = visit.outcome,
        expectedLinkedId = expectedLinkedId,
        actualLinkedId = if (siteVisit) visit.convertedSiteVisitId else visit.convertedBookingId,
    )
}
