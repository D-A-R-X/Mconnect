package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.CpVisitDetail

internal enum class CpVisitListScope(val apiValue: String) {
    MY("mine"),
    TEAM("direct"),
}

/** Defense in depth: never trust a broad CP payload to define Team locally. */
internal object CpVisitListScopePolicy {
    fun belongsToAny(visit: CpVisitDetail, staffIds: Set<String>): Boolean {
        if (staffIds.isEmpty()) return false
        return visit.assignedStaffId in staffIds ||
            visit.joint?.participants.orEmpty().any { it.staffId in staffIds }
    }

    fun acceptsResponse(
        requestedScope: CpVisitListScope,
        returnedScope: String?,
    ): Boolean = requestedScope == CpVisitListScope.MY || returnedScope == "direct"
}
