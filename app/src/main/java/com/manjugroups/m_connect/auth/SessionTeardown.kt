package com.manjugroups.m_connect.auth

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.manjugroups.m_connect.network.OfflineHttpCache
import com.manjugroups.m_connect.notifications.TasksNotification
import com.manjugroups.m_connect.ui.common.LocalCache

/**
 * Everything that must stop when nobody is signed in.
 *
 * Signing out used to clear the session and the caches and nothing else, so a
 * logged-out phone kept showing the GeoTrack tracking notification and the
 * pending-task reminders — work belonging to a user who is no longer here.
 * The forced sign-out on an expired session did even less.
 *
 * One place, called by both paths. Every step is independently guarded: a
 * teardown that throws halfway would leave the app in a worse state than the
 * one it is trying to clean up.
 */
object SessionTeardown {

    /** Periodic/queued background work that has no meaning without a session. */
    private val WORK_NAMES = listOf(
        "tracking_check_periodic",
        "geotrack_flush_leftovers",
        "attendance-punch-sync",
    )

    /**
     * Stop services, clear notifications, cancel background work and drop
     * cached data. Safe to call more than once.
     *
     * Does NOT clear the session itself — the caller owns that, because the
     * two sign-out paths differ in what they do with the session first.
     */
    fun run(context: Context) {
        val appCtx = context.applicationContext

        // 1. The tracking foreground service. Its notification is ongoing and
        //    cancelAll() below cannot remove it — only stopping the service can.
        runCatching {
            com.manjugroups.m_connect.geotrack.service.GeoTrackService.stop(appCtx)
        }

        // 2. Every notification this app has posted. A catch-all rather than a
        //    list, so a channel added later can't be forgotten here — with
        //    nobody signed in, nothing of ours should be on the tray.
        runCatching { NotificationManagerCompat.from(appCtx).cancelAll() }
        // The ongoing task reminder also keeps its own state, so clear it
        // properly rather than only removing the visible notification.
        runCatching { TasksNotification.clear(appCtx) }

        // 3. Background work that would otherwise keep running — and, worse,
        //    keep re-posting notifications after the tray was cleared.
        runCatching {
            val wm = WorkManager.getInstance(appCtx)
            for (name in WORK_NAMES) wm.cancelUniqueWork(name)
        }

        // 4. Cached data, so the next person to sign in on this phone is never
        //    shown the previous user's screens.
        runCatching { LocalCache.clearAll(appCtx) }
        runCatching { OfflineHttpCache.clear() }
    }
}
