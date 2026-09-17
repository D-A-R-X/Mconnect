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

/**
 * Plain message for a site-visit outcome the server refused because counselling
 * has not started. The server says e.g.
 * `Invalid transition: cannot set outcome from status "on_site". Allowed: ...`,
 * which means the client's QR was never scanned. Any other error passes through.
 */
internal fun siteVisitOutcomeUserMessage(rawError: String): String {
    val lower = rawError.lowercase(Locale.US)
    val beforeCounselling = lower.contains("cannot set outcome from status") &&
        listOf("\"on_site\"", "\"scheduled\"", "\"client_started\"", "\"picked_up\"", "\"reached_cp\"", "\"assigned\"")
            .any { lower.contains(it) }
    return if (beforeCounselling) SITE_VISIT_SCAN_QR_FIRST_MESSAGE else rawError
}

internal const val SITE_VISIT_SCAN_QR_FIRST_MESSAGE =
    "Scan the client's QR code to start counselling before recording the outcome."
