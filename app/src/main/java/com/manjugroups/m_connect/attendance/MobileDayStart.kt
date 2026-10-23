package com.manjugroups.m_connect.attendance

import android.content.Context
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.SessionData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The day started at the biometric gate (or by HR), and whether the staff has
 * started it in the app yet (iOS parity).
 *
 * After a gate punch-in the app used to offer only Clock Out, so staff who
 * pressed it to start their day in the app ended the day instead. While the
 * day was started elsewhere and not yet in the app, the button is Clock In:
 * with the gate session open it starts the app day without a second punch
 * (the server refuses one), with it closed it is the normal Clock In.
 */
object MobileDayStart {

    private const val PREFS = "mobile_day_start"

    /**
     * True when today has punches, none of them a mobile punch-in, and the day
     * was not ended by a Clock Out in the app.
     */
    fun startedElsewhere(sessions: List<SessionData>): Boolean {
        val started = sessions.filter { !it.punchInTime.isNullOrBlank() }
        if (started.isEmpty()) return false
        fun isMobile(value: String?) = value?.trim().equals("mobile", ignoreCase = true)
        return started.none { isMobile(it.source) || isMobile(it.punchOutSource) }
    }

    /** Whether this staff member already started today in the app. */
    fun isStarted(context: Context): Boolean {
        val key = key(context) ?: return false
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key, false)
    }

    fun markStarted(context: Context) {
        val key = key(context) ?: return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear() // only today's flag is ever needed
            .putBoolean(key, true)
            .apply()
    }

    private fun key(context: Context): String? {
        val staffId = SessionManager(context.applicationContext).staffId
            ?.takeIf { it.isNotBlank() } ?: return null
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Kolkata") }
            .format(Date())
        return "$staffId:$day"
    }
}
