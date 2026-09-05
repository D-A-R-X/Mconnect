package com.manjugroups.m_connect.notifications

import android.annotation.SuppressLint
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
 * Persistent "you have incomplete tasks" notification in the system pane —
 * the companion to the in-app nav banner (both surfaces show the same count).
 *
 * Ongoing (setOngoing → can't be swiped away), high-priority, updates in place
 * as the count changes, and is CANCELLED the moment everything is done. Driven
 * by the same count as the banner: MainActivity.refreshTasksBanner()
 * (foreground) + the 15-min TrackingCheckWorker (background).
 */
object TasksNotification {

    const val CHANNEL_ID = "pending_tasks_ongoing"
    const val EXTRA_OPEN_TASKS = "open_pending_tasks"
    private const val NOTIF_ID = 776_001

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Pending Tasks",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "A standing reminder while you have incomplete tasks"
                enableVibration(true)
                setShowBadge(true)
            }
        )
    }

    /** Post/refresh the reminder when [pending] > 0, or clear it when 0. */
    @SuppressLint("MissingPermission")
    fun update(context: Context, pending: Int, dueSoon: Int, topTitle: String?) {
        val appCtx = context.applicationContext
        if (pending <= 0) {
            clear(appCtx)
            return
        }
        ensureChannel(appCtx)

        val title = if (pending == 1) "1 pending task" else "$pending pending tasks"
        // Body must not read like a second, conflicting total. Phrase the
        // due-today figure as an explicit SUBSET of the header count so
        // "280 pending" + "257 of them due today" can't be misread.
        val summary = if (dueSoon > 0) {
            "$dueSoon of them due today — tap to complete"
        } else {
            "Tap to complete your pending tasks"
        }
        val big = if (!topTitle.isNullOrBlank()) "$summary\nLatest: $topTitle" else summary

        val intent = Intent(appCtx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TASKS, true)
        }
        val pi = PendingIntent.getActivity(
            appCtx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_custom_clipboard_check)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setColor(0xFFDC2626.toInt())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)        // un-removable — can't be swiped away
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)  // heads-up once, silent on later count updates
            .setContentIntent(pi)
            .build()

        if (!canPost(appCtx)) return
        try {
            NotificationManagerCompat.from(appCtx).notify(NOTIF_ID, notif)
        } catch (_: Exception) {}
    }

    /** Remove the reminder — all tasks done, or the user logged out. */
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
}
