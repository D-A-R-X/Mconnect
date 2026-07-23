package com.manjugroups.m_connect.util

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Formats a visit's deadline — the scheduled day (plus time when known) that
 * the visit is expected to be completed by — for display on the trip screens.
 * Shared so the CP / SV / Home callers all render it identically.
 */
object VisitDeadline {

    fun format(scheduledDate: String?, time: String?): String? {
        val date = scheduledDate?.takeIf { it.isNotBlank() } ?: return null
        val datePart = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)
        }.getOrNull()?.let {
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(it)
        } ?: date
        val t = time?.takeIf { it.isNotBlank() }
        return if (t != null) "$datePart · $t" else datePart
    }
}
