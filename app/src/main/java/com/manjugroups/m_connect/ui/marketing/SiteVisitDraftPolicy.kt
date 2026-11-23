package com.manjugroups.m_connect.ui.marketing

import com.google.gson.Gson
import com.manjugroups.m_connect.network.CreateSiteVisitRequest

internal data class SiteVisitLeadIdentity(val leadId: String?, val coreLeadId: String?)

private val CORE_LEAD_ID = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)

internal fun siteVisitLeadIdentity(id: String?, coreLeadId: String?): SiteVisitLeadIdentity {
    val raw = id?.trim()?.takeIf(String::isNotEmpty)
    val core = coreLeadId?.trim()?.takeIf(String::isNotEmpty)
        ?: raw?.takeIf(CORE_LEAD_ID::matches)
    return SiteVisitLeadIdentity(raw?.takeUnless(CORE_LEAD_ID::matches), core)
}

internal fun siteVisitLookupMatches(requestedPhone: String, currentPhone: String): Boolean =
    requestedPhone.length == 10 && requestedPhone == currentPhone

internal fun siteVisitPickupIsUsable(latitude: Double?, longitude: Double?): Boolean =
    latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        (kotlin.math.abs(latitude) >= 0.000001 || kotlin.math.abs(longitude) >= 0.000001)

internal fun siteVisitHasRequiredVisitor(routing: String?, names: List<String>): Boolean =
    routing !in setOf("out_of_station", "immediate_pickup") || names.any(String::isNotBlank)

internal fun siteVisitCreateFingerprint(request: CreateSiteVisitRequest): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(Gson().toJson(request.copy(requestId = "")).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
