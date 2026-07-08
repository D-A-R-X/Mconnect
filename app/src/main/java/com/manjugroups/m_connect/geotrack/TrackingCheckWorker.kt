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

            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "TrackingCheckWorker failed: ${e.message}")
            Result.retry()
        }
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
