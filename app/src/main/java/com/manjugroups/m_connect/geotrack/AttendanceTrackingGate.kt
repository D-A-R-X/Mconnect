package com.manjugroups.m_connect.geotrack

import com.manjugroups.m_connect.network.ApiService

object AttendanceTrackingGate {
    fun isClockedInForToday(
        firstPunchIn: String?,
        hasOpenSession: Boolean,
    ): Boolean {
        return hasOpenSession || !firstPunchIn.isNullOrBlank()
    }

    suspend fun isClockedInForToday(
        token: String,
        api: ApiService = ApiService.create(),
    ): Boolean {
        val todayResp = api.getMyAttendanceToday(token)
        val attendance = if (todayResp.success) todayResp.attendance else null
        val dayResp = runCatching { api.getDaySessions(token) }.getOrNull()
        val firstPunchIn = dayResp?.firstPunchIn ?: attendance?.firstPunchIn
        val hasOpenSession = attendance?.hasOpenSession == true || dayResp?.hasOpenSession == true
        return isClockedInForToday(firstPunchIn, hasOpenSession)
    }
}
