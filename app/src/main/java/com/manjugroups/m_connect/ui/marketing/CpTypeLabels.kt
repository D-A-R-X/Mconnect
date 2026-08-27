package com.manjugroups.m_connect.ui.marketing

/**
 * Display label for a CP visit's intent. When the row carries a
 * specific `cpType` (sv_cum_cp / follow_up / booking_cp / collection_cp /
 * old_client / gift_distribution), we surface that — it's the actual
 * intent the field staff picked at creation. Otherwise we fall back
 * to the generic visitCategory bucket. Used by HomeFragment's Today
 * row, CpVisitsFragment's list row, and TripNavigationFragment's
 * trip header so all three reads consistent.
 */
fun formatCpVisitTypeLabel(
    visitCategory: String?,
    cpType: String?,
    isPlaceOnly: Boolean = false,
    hasCpRow: Boolean = false,
): String {
    cpType?.lowercase()?.let { ct ->
        when (ct) {
            "sv_cum_cp" -> return "SV cum CP"
            "follow_up" -> return "Follow-up"
            "booking_cp" -> return "Booking CP"
            "collection_cp" -> return "Collection CP"
            "old_client" -> return "Old Client"
            "gift_distribution" -> return "Gift Distribution"
            "new_client_cp" -> return "New Client CP"
            "other_cp" -> return "Other CP"
            "joint_cp" -> return "Joint CP"
        }
    }
    return when (visitCategory) {
        "sv_cum_cp" -> "SV confirmation CP"
        "direct_cp" -> "Direct CP"
        "site_visit" -> "Site Visit"
        else -> when {
            isPlaceOnly -> "Assigned place"
            hasCpRow -> "CP visit"
            else -> "Visit"
        }
    }
}


/**
 * Display label for a CP visit's OUTCOME.
 *
 * Split out of the site-visit sheet's private copy because a Joint CP shows
 * each participant's outcome side by side, and two screens disagreeing on the
 * wording of the same value would read as two different results.
 */
fun formatCpOutcomeLabel(outcome: String?): String = when (outcome?.lowercase()) {
    "interested" -> "Interested"
    "not_interested" -> "Not Interested"
    "postponed", "follow_up" -> "Follow up"
    "converted_to_site_visit" -> "Converted to SV"
    "converted_to_booking" -> "Converted as Booking"
    "rejected" -> "Rejected"
    "gift_distributed" -> "Gift Distributed"
    "old_client_visited" -> "Visited"
    "collection_done" -> "Collected"
    "not_collected" -> "Not Collected"
    "referral" -> "Referral"
    "other" -> "Other"
    null, "" -> ""
    // An unknown value is shown rather than swallowed: a new server-side
    // outcome should be visible, not silently blank.
    else -> outcome.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
