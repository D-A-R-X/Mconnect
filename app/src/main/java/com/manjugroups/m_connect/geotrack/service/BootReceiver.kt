package com.manjugroups.m_connect.geotrack.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.GeoTrackBootstrapSync
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
                        if (GeoTrackBootstrapSync.sync(context, allowPromptConsent = false, api = api)) {
                            Log.i("BootReceiver", "Device rebooted — resuming tracking from server-backed session")
                        }
                    } catch (e: Exception) {
                        Log.w("BootReceiver", "Failed to resume tracking after reboot: ${e.message}")
                    }
                }
            }
        }
    }
}
