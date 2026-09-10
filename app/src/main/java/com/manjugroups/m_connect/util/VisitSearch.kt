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

    /** Canonical form sent to server-side search, especially for formatted phones. */
    fun serverQuery(rawQuery: String): String {
        val trimmed = rawQuery.trim()
        if (trimmed.isBlank()) return ""

        val digits = trimmed.filter(Char::isDigit)
        val compact = trimmed.filterNot {
            it.isWhitespace() || it == '+' || it == '-' || it == '(' || it == ')'
        }
        val phoneLike = digits.length >= 3 && compact.all(Char::isDigit)
        return if (phoneLike) digits.takeLast(10) else trimmed
    }

    /**
     * A completed server search is authoritative. Rechecking only the compact
     * display phone can hide rows matched through a denormalized phone snapshot.
     */
    fun matchesLoadedServerResult(
        visit: TodayVisit,
        rawQuery: String,
        loadedServerQuery: String?,
    ): Boolean {
        val query = serverQuery(rawQuery)
        if (query.isBlank()) return true
        if (loadedServerQuery != null && query.equals(loadedServerQuery, ignoreCase = true)) {
            return true
        }
        return matches(visit, rawQuery)
    }

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
