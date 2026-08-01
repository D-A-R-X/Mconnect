package com.manjugroups.m_connect.geotrack.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.GeoTrackEventQueue
import com.manjugroups.m_connect.geotrack.GeoTrackBootstrapSync
import com.manjugroups.m_connect.network.GeoTrackApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED: device restarted. MY_PACKAGE_REPLACED: our APK was
        // updated — Android kills the running foreground service on update and
        // never restarts it on its own, so tracking silently dies mid-shift
        // (the 31-Jul GPS-loss bug). Both cases resume the SAME way: replay the
        // server-backed session through GeoTrackBootstrapSync, which re-checks
        // location/background permissions and only (re)starts the service when
        // the user is inside a clock-in tracking window.
        val eventType = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "DEVICE_REBOOT"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "APP_UPDATED"
            else -> return
        }
        val session = SessionManager(context)
        if (!session.isLoggedIn) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val api = GeoTrackApi.create()
                GeoTrackEventQueue.enqueue(context, eventType)
                runCatching { GeoTrackEventQueue.flush(context, api, session) }
                if (GeoTrackBootstrapSync.sync(context, allowPromptConsent = false, api = api)) {
                    Log.i("BootReceiver", "$eventType — resuming tracking from server-backed session")
                }
            } catch (e: Exception) {
                Log.w("BootReceiver", "Failed to resume tracking after $eventType: ${e.message}")
            }
        }
    }
}
