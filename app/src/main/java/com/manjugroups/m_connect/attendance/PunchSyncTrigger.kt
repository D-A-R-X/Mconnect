package com.manjugroups.m_connect.attendance

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sends saved punches the moment the network comes back.
 *
 * WorkManager alone was slow to do it: a run already waiting out its retry
 * delay (which grows by 30 s per failed try) was kept rather than replaced, and
 * the system can take a while to report the network. This listens for the
 * network directly and flushes at once, with a few quick retries while the new
 * connection settles; [PunchSyncWorker] stays as the fallback when the app is
 * not running.
 */
object PunchSyncTrigger {

    /** Waits before each try: at once, then while the connection settles. */
    private val SETTLE_DELAYS_MS = listOf(0L, 3_000L, 10_000L, 30_000L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    private var registered = false

    /** Call once at app start. The callback also fires at once when online. */
    fun register(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (registered) return
            registered = true
        }
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    syncNow(appContext)
                }
            })
        }
    }

    /** Flush now, retrying briefly; hands over to the worker if still not sent. */
    fun syncNow(context: Context) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (running?.isActive == true) return
            running = scope.launch {
                for (wait in SETTLE_DELAYS_MS) {
                    if (wait > 0) delay(wait)
                    val outcomes = runCatching { PunchReplayer.flush(appContext) }.getOrNull() ?: continue
                    if (outcomes.values.none { it == PunchReplayer.Outcome.Deferred }) return@launch
                }
                PunchSyncWorker.enqueue(appContext)
            }
        }
    }
}
