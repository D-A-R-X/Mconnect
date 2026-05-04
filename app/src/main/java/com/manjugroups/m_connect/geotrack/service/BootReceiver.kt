package com.manjugroups.m_connect.geotrack.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.AttendanceTrackingGate
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.TamperReportRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val session = SessionManager(context)
            if (session.isLoggedIn) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val api = GeoTrackApi.create()
                        api.reportTamper(
                            session.bearerToken,
                            TamperReportRequest(
                                "DEVICE_REBOOT",
                                mapOf("ts" to System.currentTimeMillis())
                            )
                        )
                        val bootstrap = api.getTrackingBootstrap(
                            session.bearerToken,
                            session.trackingDeviceId
                        ).data
                        val attendanceActive = AttendanceTrackingGate.isClockedInForToday(
                            session.bearerToken,
                            ApiService.create(),
                        )
                        session.activeTrackingSessionId = bootstrap?.activeSession?.id
                        session.shouldTrackNow = attendanceActive && bootstrap?.shouldTrack == true
                        if (attendanceActive &&
                            bootstrap?.shouldTrack == true &&
                            !bootstrap.activeSession?.id.isNullOrBlank() &&
                            GeoTrackService.hasRequiredLocationPermissions(context)
                        ) {
                            Log.i("BootReceiver", "Device rebooted — resuming tracking from server-backed session")
                            GeoTrackService.start(context)
                        }
                    } catch (e: Exception) {
                        Log.w("BootReceiver", "Failed to resume tracking after reboot: ${e.message}")
                    }
                }
            }
        }
    }
}
