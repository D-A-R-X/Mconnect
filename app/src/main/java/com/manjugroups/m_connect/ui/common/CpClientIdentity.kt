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
).firstNotNullOfOrNull { value -> value?.trim()?.takeIf(String::isNotEmpty) }
