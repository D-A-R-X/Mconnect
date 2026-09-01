package com.manjugroups.m_connect.ui.marketing

import com.manjugroups.m_connect.network.TodayVisit

internal object SiteVisitListRules {
    fun belongsInSiteVisits(visit: TodayVisit): Boolean =
        !visit.tripType.equals("client_place", ignoreCase = true)
}
