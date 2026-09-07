package com.manjugroups.m_connect.geotrack

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Hard local boundary matching the backend's India attendance day. */
object AttendanceDayBoundary {
    private val indiaTimeZone: TimeZone
        get() = TimeZone.getTimeZone("Asia/Kolkata")

    fun dateKey(nowMillis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = indiaTimeZone
        }.format(Date(nowMillis))

    fun millisUntilNextDay(nowMillis: Long = System.currentTimeMillis()): Long {
        val nextDay = Calendar.getInstance(indiaTimeZone).apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return maxOf(1L, nextDay - nowMillis)
    }
}
