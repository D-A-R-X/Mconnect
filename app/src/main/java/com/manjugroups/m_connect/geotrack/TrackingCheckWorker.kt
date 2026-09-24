package com.manjugroups.m_connect.geotrack

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.service.GeoTrackService
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that detects biometric (or any external) punch-ins and starts
 * GeoTrackService when the backend confirms tracking should be active.
 *
 * Runs every 15 minutes. Harmless no-op when the user isn't clocked in or
 * tracking is already running.
 */
class TrackingCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val session = SessionManager(applicationContext)

        if (!session.isLoggedIn) {
            com.manjugroups.m_connect.notifications.TasksNotification.clear(applicationContext)
            return Result.success()
        }

        // Keep the pending-tasks notification fresh in the background — runs
        // even while GeoTrack is active (before the early-return below), so the
        // system-pane reminder updates/clears without the app being opened.
        runCatching {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val open = com.manjugroups.m_connect.network.ApiService.create()
                .getTaskManagerTasks(session.bearerToken, today)
                .tasks.filter { it.status == "pending" || it.status == "in-progress" }
            val dueSoon = open.count { t ->
                val d = t.deadline?.trim().orEmpty()
                d.isNotEmpty() && d <= today
            }
            val top = open.maxByOrNull { it.creationTime ?: 0.0 }
            com.manjugroups.m_connect.notifications.TasksNotification.update(
                applicationContext,
                open.size,
                dueSoon,
                top?.let { it.title ?: it.taskName },
            )
        }

        if (GeoTrackService.isRunning) return Result.success()

        return try {
            val started = GeoTrackBootstrapSync.sync(applicationContext, allowPromptConsent = false)
            if (started) {
                Log.i(TAG, "GeoTrack session active — synced via periodic check")
            }

            // Safety net: with no service running, drain any buffered
            // points/events left behind (offline clock-out tail, dead-zone
            // backlog) so the web timeline backfills within 15 minutes even
            // if the teardown flush worker never got to run.
            runCatching {
                val flushed = GeoTrackPointFlusher.flush(applicationContext, session = session)
                val events = GeoTrackEventQueue.flush(applicationContext, session = session)
                if (flushed.flushedPoints > 0 || events > 0) {
                    Log.i(TAG, "Leftover flush: ${flushed.flushedPoints} points, $events events")
                }
            }

            // Ask the SERVICE whether it is running. Do not trust `started`.
            //
            // sync() returns session.shouldTrackNow, and applyDirectSession
            // sets that purely from "the server has a session for me" — it
            // starts the service only if permissions allow, but returns true
            // either way. So a phone with a live session and no usable location
            // permission reports started = true while capturing nothing, and
            // the alert this worker exists to raise never fires on exactly the
            // phones that need it.
            //
            // That is the majority case, not an edge case. Measured on prod
            // 2026-09-24: of 220 phones with tracking_active, 152 had their
            // live_status row refreshed inside 15 minutes (so the app is awake
            // and talking to the server) while last_seen was a median 3.2 hours
            // stale (so no heartbeat or fix was arriving).
            //
            // Safe to run unconditionally: update() CLEARS the alert when
            // nothing is missing, so a healthy phone that is simply mid-start
            // just gets a no-op.
            if (!GeoTrackService.isRunning) {
                runCatching { reconcilePermissionAlert(session) }
            }

            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "TrackingCheckWorker failed: ${e.message}")
            Result.retry()
        }
    }

    /**
     * Raise (or clear) the ongoing red permission alert for a staff member who
     * is clocked in but whose phone is not tracking.
     *
     * This lives in the worker rather than in the sync or the UI because every
     * other trigger is behind state a reinstall destroys. `sync()` returns
     * early when the stored geoTrackingEnabled flag is false, and
     * MainActivity's gate is behind the same flag — so a fresh install whose
     * login response left the flag false warns nobody, ever, however long the
     * person stays clocked in. That is a silent failure: the staffer sees a
     * normal clocked-in screen while the live board shows them offline all day.
     *
     * Server truth decides, so a stale local false cannot suppress the alert,
     * and genuinely untracked office staff are still never nagged.
     */
    private suspend fun reconcilePermissionAlert(session: SessionManager) {
        runCatching { GeoTrackingFlagRefresher.refresh(applicationContext, force = true) }
        if (!session.geoTrackingEnabled) return
        // Only inside the shift. `null` means the attendance call failed —
        // not proof of anything, so stay quiet rather than guess.
        if (AttendanceTrackingGate.hasOpenSessionNow(session.bearerToken) != true) return

        val missing = BackgroundPermissionsGateDialog.missingPermissionKeys(applicationContext)
        // update() clears the alert when the list is empty, so this also takes
        // the notification down once the staffer has granted everything.
        com.manjugroups.m_connect.notifications.PermissionAlertNotification
            .update(applicationContext, missing)
        if (missing.isNotEmpty()) Log.i(TAG, "Clocked in but not tracking; missing $missing")
    }

    companion object {
        private const val TAG = "TrackingCheckWorker"
        private const val WORK_NAME = "tracking_check_periodic"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrackingCheckWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
