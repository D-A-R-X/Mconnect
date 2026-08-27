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
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.data.GeoTrackDatabase
import com.manjugroups.m_connect.geotrack.data.PendingPunchEntity
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.PunchRequest
import com.manjugroups.m_connect.network.StorageUploader
import java.io.File
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
        val session = SessionManager(applicationContext)
        val token = session.bearerToken
        val currentStaffId = session.staffId
        val dao = GeoTrackDatabase.getInstance(applicationContext).pendingPunchDao()
        val allPending = dao.listAll()
        if (allPending.isEmpty()) return Result.success()
        // No session (logged out) — leave the rows; a later enqueue after login
        // will flush them. Don't spin a retry loop with no way to succeed.
        if (token.isBlank()) return Result.success()

        // Only replay punches belonging to the signed-in staff. A queued punch
        // outlives a logout, so flushing indiscriminately would record one
        // person's clock-in against whoever signed in next on that device.
        // Rows queued before staffId existed carry null and are still flushed —
        // dropping them would lose real attendance, and the pre-upgrade app
        // could not have had a second account on the device anyway.
        val pending = allPending.filter { p ->
            p.staffId.isNullOrBlank() || p.staffId == currentStaffId
        }
        if (pending.isEmpty()) return Result.success()

        val api = ApiService.create()
        var networkFailure = false

        for (p in pending) {
            try {
                val storageId = p.photoPath?.let { path ->
                    val f = File(path)
                    if (f.exists()) {
                        runCatching { StorageUploader.upload(api, token, f).storageId }.getOrNull()
                    } else null
                }
                val request = PunchRequest(
                    latitude = p.latitude,
                    longitude = p.longitude,
                    address = p.address,
                    photo = storageId,
                    deviceId = p.deviceId,
                    source = p.source,
                    clientPunchTime = p.clientPunchTime,
                )
                val resp = if (p.isPunchIn) {
                    api.punchIn(token, request)
                } else {
                    api.punchOut(token, request)
                }
                // Success OR a business rejection (already-punched / blocked
                // location, etc.) both clear the row — retrying a rejection would
                // loop forever, and a duplicate-rejection means it already landed.
                dao.deleteById(p.id)
                if (resp.success) deletePhoto(p.photoPath)
            } catch (e: Exception) {
                // Network / server error — keep the row and let WorkManager retry.
                networkFailure = true
                dao.bumpAttempt(p.id, e.message)
            }
        }
        return if (networkFailure) Result.retry() else Result.success()
    }

    private fun deletePhoto(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
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
