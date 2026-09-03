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
}
