package com.manjugroups.m_connect.geotrack

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.data.GeoTrackDatabase
import java.util.concurrent.TimeUnit

/**
 * One-shot, network-constrained flusher for GeoTrack data left behind after
 * the service died — the punch-out tail when the phone was offline, a backlog
 * from a dead zone that outlived the shift, queued heartbeats/tamper events.
 *
 * WorkManager survives process death and fires when connectivity returns, so
 * the buffered journey backfills the web timeline without waiting for the
 * next clock-in. Retries with backoff until the buffer is drained.
 */
class GeoTrackFlushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val session = SessionManager(applicationContext)
        if (!session.isLoggedIn) return Result.success()

        return try {
            val result = GeoTrackPointFlusher.flush(applicationContext, session = session)
            val eventsFlushed = runCatching {
                GeoTrackEventQueue.flush(applicationContext, session = session)
            }.getOrDefault(0)
            val eventsLeft = runCatching {
                GeoTrackDatabase.getInstance(applicationContext)
                    .pendingGeoTrackEventDao().getPendingCount()
            }.getOrDefault(0)

            Log.i(
                TAG,
                "Flush pass: ${result.flushedPoints} points, $eventsFlushed events " +
                    "(drained=${result.drained}, eventsLeft=$eventsLeft)",
            )
            when {
                result.drained && eventsLeft == 0 -> Result.success()
                runAttemptCount >= MAX_ATTEMPTS -> Result.success() // stop; buffer stays for the next trigger
                else -> Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Flush worker failed: ${e.message}")
            if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val TAG = "GeoTrackFlushWorker"
        private const val WORK_NAME = "geotrack_flush_leftovers"
        private const val MAX_ATTEMPTS = 8

        /**
         * Schedule a flush for any unsent points/events. Safe to call
         * blindly — replaces any queued instance and no-ops in doWork when
         * the buffer is already empty.
         */
        fun enqueue(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<GeoTrackFlushWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            } catch (e: Exception) {
                Log.w(TAG, "enqueue failed: ${e.message}")
            }
        }
    }
}
