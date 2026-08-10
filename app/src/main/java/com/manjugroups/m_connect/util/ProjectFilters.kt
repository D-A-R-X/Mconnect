package com.manjugroups.m_connect.util

import com.manjugroups.m_connect.network.MarketingProject

/**
 * Project pickers (CP visit, site visit, booking, daily log) should only offer
 * ONGOING projects — a completed / proposed project can't take a new visit or
 * log. Mirrors the web's "Ongoing" filter (status == "ongoing") and the
 * booking form, which already gates on the same value, so every mobile picker
 * shows the same set.
 */
fun List<MarketingProject>.ongoingOnly(): List<MarketingProject> =
    filter { (it.status ?: "").trim().equals("ongoing", ignoreCase = true) }

/**
 * CP-creation picker ordering: every ONGOING project first, then COMPLETED
 * projects at the bottom (a CP can still be raised against an old/completed
 * project — e.g. Old Client / Collection CP). Proposed / other statuses are
 * left out. Ordering within each group is preserved from the source list.
 */
fun List<MarketingProject>.ongoingThenCompleted(): List<MarketingProject> {
    fun statusOf(p: MarketingProject) = (p.status ?: "").trim().lowercase()
    val ongoing = filter { statusOf(it) == "ongoing" }
    val completed = filter { statusOf(it) == "completed" }
    return ongoing + completed
}
