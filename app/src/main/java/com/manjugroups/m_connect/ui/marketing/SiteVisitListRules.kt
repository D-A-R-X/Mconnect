package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.TodayVisit

internal object SiteVisitListRules {
    fun belongsInSiteVisits(visit: TodayVisit): Boolean =
        !visit.tripType.equals("client_place", ignoreCase = true)

    fun shouldAutoFill(
        visitCount: Int,
        pageSize: Int,
        hasMore: Boolean,
        loadedExtraPages: Int,
        maxExtraPages: Int,
    ): Boolean = visitCount < pageSize && hasMore && loadedExtraPages < maxExtraPages

    fun belongsToAny(visit: TodayVisit, staffIds: Set<String>): Boolean =
        staffIds.isNotEmpty() && (visit.bdoStaffId in staffIds || visit.lmoStaffId in staffIds)

    fun hasUsableNextPage(
        hasMore: Boolean?,
        currentCursor: String?,
        nextCursor: String?,
        total: Int?,
    ): Boolean {
        if (hasMore != true || nextCursor.isNullOrBlank() || nextCursor == currentCursor) return false
        val offset = nextCursor.toIntOrNull()
        return offset == null || total == null || offset < total
    }
}
