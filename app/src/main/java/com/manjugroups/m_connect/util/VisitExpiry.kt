package com.manjugroups.m_connect.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Shared "has the scheduled slot passed?" logic for anything with a fixed
 * date/time — driver trips, site visits, CP visits, tasks.
 *
 * A row is *expired* when it isn't already finished (completed/cancelled/etc.)
 * and its scheduled date — or date + time, when a time is given — is strictly
 * in the past on the India calendar, which is what every backend gate uses.
 * This is why some CP/SV rows kept offering "Start" long after their day was
 * lost: nothing was comparing the slot against the clock.
 */
object VisitExpiry {

    private val IST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    private fun dateFmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = IST
        isLenient = false
    }

    /** Today's IST calendar day as yyyy-MM-dd. */
    fun todayInIndia(): String = dateFmt().format(Date())

    /**
     * The scheduled slot, as an epoch-ms instant in IST, or null if the date
     * can't be parsed. When [scheduledTime] is blank the slot is taken as the
     * END of the day, so a date-only visit only expires the day AFTER.
     */
    private fun slotMillis(scheduledDate: String?, scheduledTime: String?): Long? {
        val date = scheduledDate?.trim().orEmpty()
        if (date.isEmpty()) return null
        val parsedDate = runCatching { dateFmt().parse(date) }.getOrNull() ?: return null

        val cal = Calendar.getInstance(IST).apply { time = parsedDate }
        val time = scheduledTime?.trim().orEmpty()
        val hm = parseHourMinute(time)
        if (hm != null) {
            cal.set(Calendar.HOUR_OF_DAY, hm.first)
            cal.set(Calendar.MINUTE, hm.second)
            cal.set(Calendar.SECOND, 0)
        } else {
            // No usable time — treat the whole day as still valid.
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
        }
        return cal.timeInMillis
    }

    /** Parse "09:30", "9:30 AM", "18:05" → (hour24, minute), else null. */
    private fun parseHourMinute(raw: String): Pair<Int, Int>? {
        if (raw.isBlank()) return null
        val patterns = listOf("HH:mm", "H:mm", "hh:mm a", "h:mm a", "hh:mma", "h:mma")
        for (p in patterns) {
            val fmt = SimpleDateFormat(p, Locale.US).apply {
                timeZone = IST
                isLenient = false
            }
            val d = runCatching { fmt.parse(raw.trim().uppercase(Locale.US)) }.getOrNull()
            if (d != null) {
                val c = Calendar.getInstance(IST).apply { time = d }
                return c.get(Calendar.HOUR_OF_DAY) to c.get(Calendar.MINUTE)
            }
        }
        return null
    }

    /**
     * True when a still-open row's slot is in the past.
     *
     * @param isDone already completed / cancelled / otherwise terminal — such
     *   rows are never "expired", they're just done.
     */
    /** 24 hours in millis — grace period before a slot is treated as expired. */
    private const val EXPIRY_GRACE_MS = 48L * 60 * 60 * 1000

    fun isExpired(
        scheduledDate: String?,
        scheduledTime: String?,
        isDone: Boolean,
        createdAtMillis: Long? = null,
    ): Boolean {
        if (isDone) return false
        if (createdAtMillis != null && createdAtMillis > 0L) {
            return createdAtMillis + EXPIRY_GRACE_MS <= System.currentTimeMillis()
        }
        val slot = slotMillis(scheduledDate, scheduledTime) ?: return false
        // A trip is only considered expired after 1 full day has passed since
        // its scheduled slot, so e.g. a 09:30 trip stays valid until 09:30
        // the following day rather than expiring the instant the time passes.
        return slot + EXPIRY_GRACE_MS <= System.currentTimeMillis()
    }
}
