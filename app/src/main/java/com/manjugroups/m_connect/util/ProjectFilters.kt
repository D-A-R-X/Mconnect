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
