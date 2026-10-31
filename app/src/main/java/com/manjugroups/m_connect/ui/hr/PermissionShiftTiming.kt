package com.manjugroups.m_connect.ui.hr

import com.manjugroups.m_connect.network.TodayShiftResponse
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

internal data class PermissionShiftTiming(
    val startTime: String,
    val endTime: String,
    val isWorkDay: Boolean = true,
) {
    fun error(fromTime: String?, toTime: String?): String? {
        if (!isWorkDay) return "Permissions cannot be requested on a week-off."
        val start = minutes(startTime)
        val end = minutes(endTime)
        if (start == null || end == null || end <= start) return "Permission timing for this shift needs HR review."
        if (fromTime == null || toTime == null) return null
        val from = minutes(fromTime)
        val to = minutes(toTime)
        if (from == null || to == null || to <= from) return "To time must be after from time."
        return if (from >= start && to <= end && (from == start || to == end)) null
        else "Permission must start at $startTime or end at $endTime, within your shift."
    }

    fun defaultEndTime(): String? {
        val start = minutes(startTime) ?: return null
        val end = minutes(endTime) ?: return null
        if (end <= start) return null
        val to = minOf(start + 60, end)
        return String.format(Locale.US, "%02d:%02d", to / 60, to % 60)
    }

    companion object {
        fun resolve(response: TodayShiftResponse, date: String): PermissionShiftTiming {
            require(response.success == true && (response.date == null || response.date == date)) {
                "Could not load the selected day's shift."
            }
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(date)
                ?: error("Invalid permission date.")
            val weekday = Calendar.getInstance().apply { time = parsed }.get(Calendar.DAY_OF_WEEK)
            val schedule = response.shift?.schedule
            val day = when (weekday) {
                Calendar.MONDAY -> schedule?.monday
                Calendar.TUESDAY -> schedule?.tuesday
                Calendar.WEDNESDAY -> schedule?.wednesday
                Calendar.THURSDAY -> schedule?.thursday
                Calendar.FRIDAY -> schedule?.friday
                Calendar.SATURDAY -> schedule?.saturday
                else -> schedule?.sunday
            }
            // Match the existing API's fallback only after a successful shift lookup.
            return PermissionShiftTiming(
                day?.startTime ?: "09:30", day?.endTime ?: "18:30",
                day?.isWorkDay ?: (weekday != Calendar.SUNDAY),
            )
        }

        private fun minutes(value: String): Int? {
            val parts = value.trim().split(":")
            if (parts.size != 2) return null
            val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
            val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
            return hour * 60 + minute
        }
    }
}
