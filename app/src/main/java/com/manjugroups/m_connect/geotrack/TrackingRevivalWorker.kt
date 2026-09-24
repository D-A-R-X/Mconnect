package com.manjugroups.m_connect.geotrack

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.service.GeoTrackService
import com.manjugroups.m_connect.geotrack.service.TrackingNotification
import kotlinx.coroutines.delay

/**
 * Restarts [GeoTrackService] on phones where the 15-minute watchdog is not
 * allowed to.
 *
 * Android 12+ refuses `startForegroundService()` from the background unless the
 * app is exempt from battery optimisation. [TrackingCheckWorker] runs in the
 * background, so on a non-exempt phone its restart attempt throws every time:
 * the session sync succeeds, the server records the staff member as tracking,
 * and the phone captures nothing. Measured on production 2026-09-24, that was
 * 152 of 220 phones with `tracking_active`.
 *
 * The way out is the one exemption the app can reach on its own: an app that
 * already owns a foreground service may start another. WorkManager can put a
 * worker into the foreground via [setForeground], and expedited one-time work
 * is given the elevated process state that makes that call legal from the
 * background. So this worker takes a short-lived foreground service of its own,
 * starts the real tracking service underneath it, waits for it to come up, and
 * then lets go.
 *
 * Deliberately a separate one-shot worker rather than a change to
 * [TrackingCheckWorker]: `setExpedited` does not exist on periodic work.
 *
 * It cannot help a phone that is missing location permission — nothing can, and
 * that case is already covered by the ongoing permission alert. This is only
 * for the phone that *could* track and is being blocked by the restriction.
 */
class TrackingRevivalWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        TrackingNotification.ensureChannel(applicationContext)
        val notification = TrackingNotification.build(applicationContext)
        // A DISTINCT id from the service's own notification. Sharing
        // TrackingNotification.NOTIFICATION_ID would mean WorkManager tearing
        // this worker down takes the service's notification with it, which on
        // Android is the same thing as demoting the service we just started.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                REVIVAL_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            ForegroundInfo(REVIVAL_NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        if (GeoTrackService.isRunning) return Result.success()

        // Same three gates the rest of GeoTrack uses, re-read here because this
        // worker can run minutes after it was enqueued: never track outside a
        // shift, and never pretend a phone without permission can be revived.
        val session = SessionManager(applicationContext)
        if (!session.isLoggedIn || !session.geoTrackingEnabled) return Result.success()
        if (!session.shouldTrackNow) return Result.success()
        if (!GeoTrackService.hasRequiredLocationPermissions(applicationContext)) {
            return Result.success()
        }

        val promoted = runCatching { setForeground(foregroundInfo()) }.isSuccess
        if (!promoted) {
            // Quota exhausted, or the OEM refused even this. Nothing further to
            // try from code; the ongoing permission alert already tells the
            // staff member to lift battery optimisation, which is the real fix.
            Log.w(TAG, "Could not take a foreground slot; leaving it to the permission alert")
            return Result.success()
        }

        GeoTrackService.start(applicationContext)

        // Hold the foreground slot until the service has its own, or the start
        // is clearly not happening. Releasing immediately would drop the app
        // back to a background state mid-handover.
        repeat(HANDOVER_TICKS) {
            if (GeoTrackService.isRunning) {
                Log.i(TAG, "GeoTrack service revived from the background")
                return Result.success()
            }
            delay(HANDOVER_TICK_MS)
        }
        Log.w(TAG, "Service did not come up within the handover window")
        return Result.success()
    }

    companion object {
        private const val TAG = "TrackingRevival"
        private const val WORK_NAME = "tracking_revival"

        /** Distinct from TrackingNotification.NOTIFICATION_ID (9001) on purpose. */
        private const val REVIVAL_NOTIFICATION_ID = 9003
        private const val HANDOVER_TICKS = 20
        private const val HANDOVER_TICK_MS = 250L

        /**
         * Ask for a revival attempt. Unique work with [ExistingWorkPolicy.KEEP],
         * so repeated watchdog ticks queue one attempt, not a pile of them.
         *
         * [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST] means a phone
         * that has spent its expedited quota still gets the attempt, just
         * later and without the elevated state — strictly better than dropping
         * it.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<TrackingRevivalWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
