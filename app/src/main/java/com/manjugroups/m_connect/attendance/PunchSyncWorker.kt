package com.manjugroups.m_connect.attendance

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.manjugroups.m_connect.geotrack.data.PendingPunchEntity
import java.util.concurrent.TimeUnit

/**
 * Flushes queued offline attendance punches once connectivity returns.
 *
 * Each pending punch already carries the staff's REAL tap time
 * ([PendingPunchEntity.clientPunchTime]); the backend records that time, so a
 * punch taken offline at 9:00 and synced at 9:20 still lands at 9:00.
 *
 * Runs under a CONNECTED network constraint so WorkManager fires it
 * automatically when the network comes back — even if the app is closed.
 */
class PunchSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // PunchReplayer sends oldest-first, stops at the first punch that
        // can't go yet, and notifies the staff of any the server refuses.
        val outcomes = PunchReplayer.flush(applicationContext)
        return if (outcomes.values.any { it == PunchReplayer.Outcome.Deferred }) Result.retry()
        else Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "attendance-punch-sync"

        /** Schedule a flush; runs as soon as the network is available. Safe to
         *  call repeatedly (KEEP keeps a pending run rather than piling up). */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<PunchSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request)
        }
    }
}
