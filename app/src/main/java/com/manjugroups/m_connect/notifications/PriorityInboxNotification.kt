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
import androidx.core.content.ContextCompat
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.NotificationData
import com.manjugroups.m_connect.ui.notifications.NotificationPriority

/**
 * Raises the phone's notification tray for ACTIONABLE inbox items — approvals
 * and tasks — so they are not missed while the app is closed.
 *
 * Deliberately NOT ongoing (unlike [TasksNotification]): this is news, not a
 * standing state, so the staff can dismiss it. Only genuinely new items post,
 * tracked by id — re-posting the same approval on every poll is how people
 * learn to swipe the tray without reading it.
 */
object PriorityInboxNotification {

    const val CHANNEL_ID = "priority_approvals"
    private const val NOTIF_ID = 776_002
    private const val PREFS = "priority_inbox_notifs"
    private const val KEY_SEEN_IDS = "seen_ids"

    /** Bounded so the marker can't grow forever on a busy account. */
    private const val MAX_REMEMBERED = 200

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Approvals & Tasks",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Approvals and tasks that need your action"
                enableVibration(true)
                setShowBadge(true)
            }
        )
    }

    /**
     * Post a tray notification for any unread approval/task not already
     * announced. Safe to call on every poll.
     */
    fun notifyNew(context: Context, items: List<NotificationData>) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(KEY_SEEN_IDS, emptySet()).orEmpty()

        val actionable = NotificationPriority.trayWorthy(items)
        val fresh = actionable.filter { it.id != null && !seen.contains(it.id) }

        // Remember everything currently actionable, not just what we posted —
        // otherwise an item read on another device would re-announce here.
        val nextSeen = actionable.mapNotNull { it.id }
            .plus(seen)
            .distinct()
            .take(MAX_REMEMBERED)
            .toSet()
        prefs.edit().putStringSet(KEY_SEEN_IDS, nextSeen).apply()

        if (fresh.isEmpty()) return
        if (!canPost(appCtx)) return
        ensureChannel(appCtx)

        val newest = fresh.first()
        val title = if (fresh.size == 1) {
            newest.title?.takeIf { it.isNotBlank() } ?: "Action needed"
        } else {
            "${fresh.size} approvals & tasks need you"
        }
        val body = newest.message?.takeIf { it.isNotBlank() }
            ?: "Open the app to review."

        val intent = Intent(appCtx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            appCtx,
            NOTIF_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching {
            NotificationManagerCompat.from(appCtx).notify(NOTIF_ID, notification)
        }
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
