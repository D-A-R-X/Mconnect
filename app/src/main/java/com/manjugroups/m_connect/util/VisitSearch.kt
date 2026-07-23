package com.manjugroups.m_connect.util

import com.manjugroups.m_connect.network.TodayVisit
import java.util.Locale

/**
 * Shared search predicate for the SV / CP / Trip list search bars so all
 * three behave identically. A visit matches when the query hits any of its
 * text fields (place, client/lead name, address) OR — when the query
 * contains digits — the client/lead phone number.
 *
 * Phone matching is digit-only on both sides, so "9090 909090", "+91 90909"
 * and "90909" all match a stored "9090909090" regardless of spaces / +91 / -.
 */
object VisitSearch {

    fun matches(visit: TodayVisit, rawQuery: String): Boolean {
        val q = rawQuery.trim().lowercase(Locale.US)
        if (q.isBlank()) return true

        val textHit = listOf(visit.placeName, visit.leadName, visit.placeAddress)
            .any { it?.lowercase(Locale.US)?.contains(q) == true }
        if (textHit) return true

        val queryDigits = rawQuery.filter { it.isDigit() }
        if (queryDigits.isNotEmpty()) {
            val phoneDigits = visit.leadPhone?.filter { it.isDigit() }.orEmpty()
            if (phoneDigits.isNotEmpty() && phoneDigits.contains(queryDigits)) return true
        }
        return false
    }
}
