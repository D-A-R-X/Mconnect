package com.manjugroups.m_connect.ui.marketing

import java.util.Locale

internal object SiteVisitStaffRules {
    fun isActive(status: String?): Boolean = status.isNullOrBlank() || status.trim().equals("active", true)

    fun salesMarketing(status: String?, department: String?): Boolean {
        val normalized = normalize(department)
        return isActive(status) && normalized.contains("sales") && normalized.contains("marketing")
    }

    // Sales & Marketing accepts every designation, matching the web and API.
    // Preserve the create API's existing Telesales / Channel Partner rules.
    fun lmoOrBdo(status: String?, department: String?, designation: String?): Boolean {
        if (!isActive(status)) return false
        val normalized = normalize(department)
        val title = normalize(designation).trim()
        val telesalesLmo = normalized.contains("telesales") && (title == "lmo" ||
            title.contains("lead management executive") || title.contains("telecaller"))
        return salesMarketing(status, department) || telesalesLmo || normalized.contains("channel partner")
    }

    private fun normalize(value: String?): String = value.orEmpty().trim().lowercase(Locale.ROOT)
        .replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").replace(Regex("\\s+"), " ")
}
