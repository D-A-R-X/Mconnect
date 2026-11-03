package com.manjugroups.m_connect.ui.hr

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Leave and permission can only be applied for today or later: a past day is
 * not selectable and is refused on submit (the server refuses it too).
 */
internal object HrApplicationDates {
    const val LEAVE_PAST_MESSAGE = "Leave can't be applied for a past date."
    const val PERMISSION_PAST_MESSAGE = "Permission can't be applied for a past date."

    /** Midnight at the start of today, in [zone]. */
    fun startOfToday(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    fun isPastDay(dayMillis: Long, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Boolean =
        dayMillis < startOfToday(now, zone)

    /** A `yyyy-MM-dd` date before today; unparseable values are not called past. */
    fun isPastIsoDate(iso: String, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Boolean {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone; isLenient = false }
        val day = runCatching { format.parse(iso)?.time }.getOrNull() ?: return false
        return isPastDay(day, now, zone)
    }

    fun todayIso(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(now)
}
