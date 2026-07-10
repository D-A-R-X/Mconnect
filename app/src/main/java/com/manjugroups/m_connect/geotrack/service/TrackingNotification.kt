package com.manjugroups.m_connect.geotrack.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager

/**
 * Builds — and refreshes in place — the GeoTrack foreground notification.
 *
 * It adapts to the staff's current [SessionManager.fieldActivity]:
 *   • plain shift  → the neutral "tracking during your shift" line
 *   • On Duty      → "On Duty · <category>" + a LIVE elapsed timer
 *   • CP / SV visit→ "<Client/Site> visit" + elapsed timer
 *   • Fleet trip   → "Fleet trip" + elapsed timer
 *
 * Two deliberate choices keep this battery-cheap (the old per-minute rewrite
 * was removed for exactly this reason):
 *   1. Elapsed time uses the OS chronometer (setUsesChronometer + setWhen), so
 *      the ticking costs us zero wakeups — the system renders it.
 *   2. [refresh] is EVENT-DRIVEN: a flow calls it only when the context
 *      actually changes, and it no-ops unless the service already owns the
 *      foreground notification, so it never spawns an orphan.
 *
 * `colorized` + `ongoing` give the "live activity"/island look on the OEMs and
 * Android versions that promote such notifications; elsewhere it degrades to a
 * normal ongoing notification. IDs/channel MUST match [GeoTrackService].
 */
object TrackingNotification {

    const val CHANNEL_ID = "geotrack_channel"
    const val NOTIFICATION_ID = 9001
    /** Tap-routing extra: the running field-activity kind (onduty/cp/sv/fleet). */
    const val EXTRA_OPEN_ACTIVITY_KIND = "open_field_activity_kind"
    private const val REQUEST_CODE = 41

    // A field activity older than this is treated as stale (process killed
    // mid-activity without a clean stop) → fall back to the neutral line so a
    // next-shift notification can never show a multi-day elapsed timer.
    private const val STALE_MS = 16L * 60 * 60 * 1000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        // IMPORTANCE_LOW → no sound/heads-up on every refresh, but still visible
        // and swipe-locked while ongoing. Matches the service's original channel.
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Location Tracking", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "M-Connect field tracking"
                    setShowBadge(false)
                }
            )
        }
    }

    fun build(context: Context): Notification {
        val session = SessionManager(context.applicationContext)
        val activity = session.fieldActivity()?.takeIf {
            it.startMs <= 0L || System.currentTimeMillis() - it.startMs < STALE_MS
        }

        // Tap lands where the staff can END the running activity: On Duty →
        // the HR dashboard (Complete On Duty), CP/SV/Fleet → Home's trips.
        // Distinct requestCode: with requestCode 0 this PendingIntent would
        // be the SAME PendingIntent as the tasks/permission notifications
        // (extras are ignored by Intent.filterEquals), and each posting would
        // clobber the others' routing extras.
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            activity?.let { putExtra(EXTRA_OPEN_ACTIVITY_KIND, it.kind) }
        }
        val pi = PendingIntent.getActivity(
            context, REQUEST_CODE, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_home)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (activity == null) {
            return builder
                .setContentTitle("M-Connect Tracking")
                .setContentText("Location tracking is active during your shift.")
                .build()
        }

        builder
            .setContentTitle(activity.title)
            .setContentText(activity.sub ?: defaultSub(activity.kind))
            .setColor(colorFor(activity.kind))
            .setColorized(true)

        // Live, OS-rendered elapsed timer since the activity began.
        if (activity.startMs > 0L) {
            builder.setWhen(activity.startMs)
            builder.setShowWhen(true)
            builder.setUsesChronometer(true)
        }
        return builder.build()
    }

    /**
     * Re-post the notification in place after the field-activity context
     * changed. No-ops unless the service already holds the foreground
     * notification, so it never creates a stray notification when off-shift.
     */
    fun refresh(context: Context) {
        if (!GeoTrackService.isRunning) return
        val appCtx = context.applicationContext
        ensureChannel(appCtx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appCtx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(appCtx).notify(NOTIFICATION_ID, build(appCtx))
        } catch (_: Exception) {}
    }

    private fun defaultSub(kind: String): String = when (kind) {
        "onduty" -> "On duty — tap to view"
        "cp" -> "Client visit in progress"
        "sv" -> "Site visit in progress"
        "fleet" -> "Fleet trip in progress"
        else -> "Location tracking is active during your shift."
    }

    private fun colorFor(kind: String): Int = when (kind) {
        "onduty" -> 0xFF0B61CA.toInt() // app-theme blue
        "cp" -> 0xFF7C3AED.toInt()     // violet
        "sv" -> 0xFF0891B2.toInt()     // teal
        "fleet" -> 0xFF2563EB.toInt()  // blue
        else -> 0xFF0B61CA.toInt()
    }
}
