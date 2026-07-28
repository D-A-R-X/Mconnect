package com.manjugroups.m_connect.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object EditableTimeFormat {
    private val inputPatterns = listOf(
        "HH:mm:ss",
        "HH:mm",
        "H:mm",
        "hh:mm a",
        "h:mm a",
        "hh:mma",
        "h:mma",
    )

    fun toDisplay(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return ""
        val parsed = parse(value) ?: return value
        return SimpleDateFormat("hh:mm a", Locale.US).format(parsed)
    }

    fun toStorage(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return ""
        val parsed = parse(value) ?: return value
        return SimpleDateFormat("HH:mm", Locale.US).format(parsed)
    }

    fun fromPicker(hourOfDay: Int, minute: Int): Pair<String, String> {
        val storage = String.format(Locale.US, "%02d:%02d", hourOfDay, minute)
        return storage to toDisplay(storage)
    }

    private fun parse(value: String): Date? {
        val timeOnly = Regex("""T(\d{1,2}:\d{2}(?::\d{2})?)""")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?: value

        return inputPatterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                }.parse(timeOnly.uppercase(Locale.US))
            }.getOrNull()
        }
    }
}
