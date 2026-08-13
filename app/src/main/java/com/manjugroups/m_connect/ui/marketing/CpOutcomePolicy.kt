package com.manjugroups.m_connect.ui.marketing

import java.util.Locale

private val CP_TYPES_WITH_OTHER_OUTCOME = setOf(
    "booking_cp",
    "gift_distribution",
    "follow_up",
)

/** CP categories allowed to close through the free-text `other` outcome. */
fun cpTypeSupportsOtherOutcome(cpType: String?): Boolean =
    cpType
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.let(CP_TYPES_WITH_OTHER_OUTCOME::contains) == true
