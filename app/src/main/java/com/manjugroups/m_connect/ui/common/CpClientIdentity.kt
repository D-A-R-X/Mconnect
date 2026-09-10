package com.manjugroups.m_connect.ui.common

import com.manjugroups.m_connect.network.CpVisitDetail

/** Uses the identity captured for this CP visit before older reconciled records. */
fun CpVisitDetail.preferredCpClientName(includePlaceFallback: Boolean = true): String? {
    val candidates = mutableListOf(
        lead?.contactName,
        lead?.manualProfile?.clientName,
        client?.clientName,
    )
    if (includePlaceFallback) {
        candidates += clientPlace?.name
    }
    return candidates.firstNotNullOfOrNull { value -> value?.trim()?.takeIf(String::isNotEmpty) }
}

fun CpVisitDetail.preferredCpClientPhone(): String? = listOf(
    lead?.mobileNumber,
    client?.mobileNumber,
    clientPlace?.contactPhone,
    mobileNumberNormalized,
).firstNotNullOfOrNull { value -> value?.trim()?.takeIf(String::isNotEmpty) }

/** Booking fields accept the local 10-digit mobile even when APIs return +91/91. */
fun bookingMobileNumber(value: String?): String? = value
    ?.filter(Char::isDigit)
    ?.takeLast(10)
    ?.takeIf { it.length == 10 }
