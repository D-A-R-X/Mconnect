package com.manjugroups.m_connect.ui.home

import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.GeoTrackResponse
import com.manjugroups.m_connect.network.JointCpWorkflow
import com.manjugroups.m_connect.network.SetOutcomeRequest
import com.manjugroups.m_connect.network.SetSiteVisitOutcomeRequest
import kotlinx.coroutines.CancellationException
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

    // A Joint CP owner is only saving a revisioned draft here. The parent CP
    // intentionally stays open until joint-submit-review succeeds and the
    // reviewer later completes it. Requiring a terminal parent status at this
    // point blocks the owner before that handoff can happen.
    if (jointCp) return null

    val normalizedStatus = status.contractValue()
    if (normalizedStatus !in TERMINAL_OUTCOME_STATUSES) {
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

private fun CpVisitDetail.siteVisitState(): Pair<String?, String?> {
    val siteVisit = proposedSiteVisit
    return if (siteVisit != null) {
        siteVisit.status to siteVisit.outcome
    } else {
        status to outcome
    }
}

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
    var requestFailure: Throwable? = null
    val response = try {
        setCpVisitOutcome(
            token,
            request.copy(actingStaffId = request.actingStaffId ?: actingStaffId),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        requestFailure = error
        null
    }

    if (jointCp) {
        val workflowResponse = runCatching { getJointCpWorkflow(token, request.id) }
            .getOrElse {
                requestFailure?.let { failure -> throw failure }
                return response?.copy(
                    success = false,
                    error = "The outcome was sent, but Joint CP confirmation could not be loaded. Refresh the visit before retrying.",
                ) ?: GeoTrackResponse(
                    success = false,
                    error = "The outcome was sent, but Joint CP confirmation could not be loaded. Refresh the visit before retrying.",
                )
            }
        val workflow = workflowResponse.workflow
        val error = if (!workflowResponse.success || workflow == null) {
            workflowResponse.error
                ?: "The outcome was sent, but Joint CP confirmation was not returned. Refresh the visit before retrying."
        } else {
            val (state, workflowOutcome) = workflow.workflowState()
            val responseOutcome = response?.responseVisitState()?.second
            cpOutcomeConfirmationError(
                request.outcome,
                state,
                workflowOutcome ?: responseOutcome,
                jointCp = true,
            )
        }
        return if (error == null) {
            (response ?: GeoTrackResponse(success = true)).copy(
                success = true,
                status = response?.status ?: workflow?.state,
                outcome = response?.outcome ?: workflow?.outcome,
            )
        } else {
            requestFailure?.let { failure -> throw failure }
            response?.copy(success = false, error = response.error ?: error)
                ?: GeoTrackResponse(success = false, error = error)
        }
    }

    var (status, outcome) = response?.responseVisitState() ?: (null to null)
    if (cpOutcomeConfirmationError(request.outcome, status, outcome, jointCp = false) != null) {
        val detailResponse = runCatching { getCpVisitDetail(token, request.id) }
            .getOrElse {
                requestFailure?.let { failure -> throw failure }
                return response?.copy(
                    success = false,
                    error = "The outcome was sent, but the completed CP could not be confirmed. Refresh the visit before retrying.",
                ) ?: GeoTrackResponse(
                    success = false,
                    error = "The outcome was sent, but the completed CP could not be confirmed. Refresh the visit before retrying.",
                )
            }
        val detail = detailResponse.visit
        if (!detailResponse.success || detail == null) {
            requestFailure?.let { failure -> throw failure }
            return (response ?: GeoTrackResponse(success = false)).copy(
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
        (response ?: GeoTrackResponse(success = true)).copy(
            success = true,
            status = response?.status ?: status,
            outcome = response?.outcome ?: outcome,
        )
    } else {
        requestFailure?.let { failure -> throw failure }
        response?.copy(success = false, error = response.error ?: error)
            ?: GeoTrackResponse(success = false, error = error)
    }
}

internal fun siteVisitOutcomeConfirmationError(
    expectedOutcome: String,
    status: String?,
    actualOutcome: String?,
): String? {
    val expected = if (expectedOutcome.contractValue() == "postponed") {
        "follow_up"
    } else {
        expectedOutcome.contractValue()
    }
    if (actualOutcome.contractValue() != expected) {
        return "The server did not confirm the saved site visit outcome. Refresh the visit and try again."
    }
    if (status.contractValue() !in setOf("completed", "complete", "done", "closed")) {
        return "The outcome was received, but the site visit was not finalized. Refresh the visit and try again."
    }
    return null
}

/**
 * Makes SV outcome submission idempotent from the app's perspective. A timeout
 * may happen after setOutcome committed and changed the row to completed; the
 * authoritative read prevents a retry from surfacing an invalid-transition
 * error or creating duplicate follow-up work.
 */
internal suspend fun GeoTrackApi.setSiteVisitOutcomeConfirmed(
    token: String,
    request: SetSiteVisitOutcomeRequest,
): GeoTrackResponse {
    var requestFailure: Throwable? = null
    val response = try {
        setSiteVisitOutcome(token, request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        requestFailure = error
        null
    }

    var (status, outcome) = response?.visit?.siteVisitState()
        ?: (response?.status to response?.outcome)
    var confirmationError = siteVisitOutcomeConfirmationError(request.outcome, status, outcome)
    if (confirmationError != null) {
        val detailResponse = runCatching { getCpVisitDetail(token, request.id) }.getOrNull()
        val detail = detailResponse?.visit
        if (detailResponse?.success == true && detail != null) {
            detail.siteVisitState().also {
                status = it.first
                outcome = it.second
            }
            confirmationError = siteVisitOutcomeConfirmationError(request.outcome, status, outcome)
        }
    }

    if (confirmationError == null) {
        return (response ?: GeoTrackResponse(success = true)).copy(
            success = true,
            error = null,
            status = response?.status ?: status,
            outcome = response?.outcome ?: outcome,
        )
    }
    requestFailure?.let { throw it }
    if (response?.success == false) return response
    return (response ?: GeoTrackResponse(success = false)).copy(
        success = false,
        error = confirmationError,
    )
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
