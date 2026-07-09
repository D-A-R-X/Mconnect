package com.manjugroups.m_connect.notifications

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

/**
 * Local, offline-capable alert telling the staff a tracking permission is
 * missing so they can fix it and normal signal transfer resumes. Posted from
 * GeoTrackService's permission-health check — it does NOT depend on the
 * network (the backend also notifies the reporting officer once the buffered
 * PERMISSION_MISSING event syncs, but this reaches the staff immediately).
 */
object PermissionAlertNotification {

    const val CHANNEL_ID = "geotrack_permission_alert"
    private const val NOTIF_ID = 776_002

    private val LABELS = mapOf(
        "fine_location" to "Precise location",
        "background_location" to "Allow location all the time",
        "activity_recognition" to "Physical activity",
        "battery_optimization" to "Turn off battery optimization",
    )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Tracking Permission Alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Alerts when a permission tracking needs is missing"
                    enableVibration(true)
                }
            )
        }
    }

    /** Post/refresh the alert for [missing] permission keys, or clear when empty. */
    fun update(context: Context, missing: List<String>) {
        val appCtx = context.applicationContext
        if (missing.isEmpty()) { clear(appCtx); return }
        if (!canPost(appCtx)) return
        ensureChannel(appCtx)

        val friendly = missing.map { LABELS[it] ?: it.replace('_', ' ') }
        val body = "Grant: ${friendly.joinToString(", ")}. Tracking can't work fully until you do — tap to fix."

        val intent = Intent(appCtx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_FIX_PERMISSIONS, true)
        }
        // Distinct requestCode — requestCode 0 would collapse into the same
        // PendingIntent as the tasks/tracking notifications (extras don't
        // participate in PendingIntent identity) and clobber their routing.
        val pi = PendingIntent.getActivity(
            appCtx, 42, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_home)
            .setContentTitle("Fix tracking permission")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(0xFFDC2626.toInt())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(appCtx).notify(NOTIF_ID, notif)
        } catch (_: Exception) {}
    }

    fun clear(context: Context) {
        try {
            NotificationManagerCompat.from(context.applicationContext).cancel(NOTIF_ID)
        } catch (_: Exception) {}
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    const val EXTRA_FIX_PERMISSIONS = "fix_tracking_permissions"
}
