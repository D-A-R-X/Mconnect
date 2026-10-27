package com.manjugroups.m_connect.attendance

import com.manjugroups.m_connect.network.SessionData

/**
 * Whether today was started at the biometric gate (or by HR) rather than in
 * the app. A gate punch-in counts as clocked in, so the button is Clock Out;
 * its confirmation then says that clocking out ends the gate-started day.
 */
object MobileDayStart {

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
}
