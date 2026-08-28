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
