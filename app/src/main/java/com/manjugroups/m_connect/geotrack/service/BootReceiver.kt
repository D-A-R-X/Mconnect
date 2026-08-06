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

        // Restart the foreground service IMMEDIATELY, synchronously, if the last
        // persisted session said we were inside a clock-in tracking window. Two
        // reasons this must happen here and not after the network round-trip:
        //   1. An APK update / reboot cold-starts the process with nothing
        //      keeping it alive; deferring the (re)start to an async coroutine
        //      races process death, so onReceive returns and the app is killed
        //      before the service ever starts — tracking silently stayed dead
        //      after every update (the bug users hit: no location until they
        //      reopened the app).
        //   2. The FGS background-start exemption granted to BOOT_COMPLETED /
        //      MY_PACKAGE_REPLACED is only valid for a short window from the
        //      broadcast; a slow network call can miss it. Starting now uses it.
        // The bootstrap sync below then reconciles (stops the service if the
        // server says the shift/visit has since ended — tracking stays strictly
        // bounded to the clock-in → clock-out window).
        if (session.shouldTrackNow && !session.activeTrackingSessionId.isNullOrBlank()) {
            runCatching { GeoTrackService.start(context) }
        }

        // Keep the receiver (and its process) alive while the reconciling sync
        // runs — a bare coroutine would be torn down when onReceive returns.
        val pending = goAsync()
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
            } finally {
                pending.finish()
            }
        }
    }
}
