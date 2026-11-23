package com.manjugroups.m_connect.ui.marketing

import java.util.Locale

private val CP_TYPES_WITH_OTHER_OUTCOME = setOf(
    "booking_cp",
    "gift_distribution",
    "old_client",
    "other_cp",
)

/** CP categories allowed to close through the free-text `other` outcome. */
fun cpTypeSupportsOtherOutcome(cpType: String?): Boolean =
    cpType
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.let(CP_TYPES_WITH_OTHER_OUTCOME::contains) == true

/**
 * Whether the outcome list should offer the free-text "Others" close.
 *
 * A PURE site visit keeps it. An SV-CONFIRMATION CP (`sv_cum_cp`) does not:
 * it already closes through Booking / Postpone / Not Interested / Cancel,
 * which cover every real ending, so "Others" only allowed a confirmation
 * visit to be closed without recording what actually happened.
 *
 * Every other CP type follows [cpTypeSupportsOtherOutcome].
 */
fun shouldOfferOtherOutcome(isPureSiteVisit: Boolean, cpType: String?): Boolean =
    isPureSiteVisit || cpTypeSupportsOtherOutcome(cpType)

/** New Client CP is the only category that asks the post-outcome referral question. */
fun isNewClientCpType(cpType: String?): Boolean =
    cpType?.trim()?.lowercase(Locale.ROOT) == "new_client_cp"

internal fun shouldRefreshCpOutcomePicker(outcomeChosen: Boolean, userInteracted: Boolean): Boolean =
    !outcomeChosen && !userInteracted

/** A setup linkage is not, by itself, a staff decision. */
fun confirmedCpOutcome(
    status: String?,
    outcome: String?,
    convertedSiteVisitId: String? = null,
    convertedBookingId: String? = null,
): String? {
    if (!isCpDecisionCommitted(status)) return null
    outcome?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
    // Old completed records may omit outcome; pending SV confirmations also
    // have links, so only a genuinely completed CP may use this fallback.
    if (normalizedCpDecisionStatus(status) !in COMPLETED_CP_STATUSES) return null
    return convertedSiteVisitId?.takeIf { it.isNotBlank() }?.let { "converted_to_site_visit" }
        ?: convertedBookingId?.takeIf { it.isNotBlank() }?.let { "converted_to_booking" }
}

private val COMPLETED_CP_STATUSES = setOf("completed", "complete", "done", "closed")

private fun normalizedCpDecisionStatus(status: String?): String? =
    status?.trim()?.lowercase(Locale.ROOT)?.replace('-', '_')

fun isCpDecisionCommitted(status: String?): Boolean =
    normalizedCpDecisionStatus(status) in COMPLETED_CP_STATUSES + setOf(
        "postponed", "pending_gm_approval", "cancelled", "canceled", "rejected",
    )

/** A closed travel leg cannot hide an undecided parent CP's outcome form. */
fun cpOutcomeCaptureStatus(parentStatus: String?, effectiveStatus: String): String =
    if (!isCpDecisionCommitted(parentStatus) &&
        normalizedCpDecisionStatus(effectiveStatus) in COMPLETED_CP_STATUSES
    ) "arrived" else effectiveStatus
