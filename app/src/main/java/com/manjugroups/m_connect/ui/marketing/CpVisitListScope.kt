package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.CpVisitDetail

internal enum class CpVisitListScope(val apiValue: String) {
    MY("mine"),
    TEAM("direct"),
    ALL("all"),
}

/** Defense in depth: never trust a broad CP payload to define Team locally. */
internal object CpVisitListScopePolicy {
    fun belongsToAny(visit: CpVisitDetail, staffIds: Set<String>): Boolean {
        if (staffIds.isEmpty()) return false
        return visit.assignedStaffId in staffIds ||
            visit.joint?.leadStaffId in staffIds ||
            visit.joint?.participants.orEmpty().any { it.staffId in staffIds }
    }

    fun acceptsResponse(
        requestedScope: CpVisitListScope,
        returnedScope: String?,
    ): Boolean = when (requestedScope) {
        CpVisitListScope.MY -> returnedScope == null || returnedScope == "mine"
        CpVisitListScope.TEAM -> returnedScope == "direct"
        // ALL is exposed only after the caller has independently established
        // an authenticated admin session. Deployed servers may omit or
        // normalize this response echo even though they honor scope=all.
        CpVisitListScope.ALL -> true
    }

    fun initialScope(isAdmin: Boolean): CpVisitListScope =
        if (isAdmin) CpVisitListScope.ALL else CpVisitListScope.MY
}
