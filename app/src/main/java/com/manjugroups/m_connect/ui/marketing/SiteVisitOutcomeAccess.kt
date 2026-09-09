package com.manjugroups.m_connect.ui.marketing

import java.util.Locale

private val SITE_VISIT_OUTCOME_RECORDABLE_STATUSES = setOf(
    "on_counselling",
    "picked_from_site",
    "dropped",
    // A committed status with no outcome is a recoverable partial transition.
    // The backend accepts this state so the staff can finish the interrupted form.
    "completed",
)

internal fun siteVisitHasRecordedOutcome(outcome: String?): Boolean =
    !outcome.isNullOrBlank()

internal fun siteVisitOutcomeCanBeRecorded(status: String?, outcome: String?): Boolean {
    if (siteVisitHasRecordedOutcome(outcome)) return false
    val normalizedStatus = status
        ?.trim()
        ?.lowercase(Locale.US)
        ?.replace('-', '_')
        .orEmpty()
    return normalizedStatus in SITE_VISIT_OUTCOME_RECORDABLE_STATUSES
}
