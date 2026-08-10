package com.manjugroups.m_connect.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.manjugroups.m_connect.R

object ModernDialerCallController {
    const val TYPE_INCOMING = "dialer-call-incoming"
    const val ACTION_PICKUP = "com.manjugroups.m_connect.dialer.PICKUP"
    const val ACTION_REJECT = "com.manjugroups.m_connect.dialer.REJECT"
    const val ACTION_HANGUP = "com.manjugroups.m_connect.dialer.HANGUP"
    const val ACTION_SHOW = "com.manjugroups.m_connect.dialer.SHOW"

    const val EXTRA_CALL_ID = "dialer.callId"
    const val EXTRA_FROM_NUMBER = "dialer.fromNumber"
    const val EXTRA_DISPLAY_NAME = "dialer.displayName"
    const val EXTRA_EXTENSION = "dialer.extension"
    const val EXTRA_EXPIRES_AT = "dialer.expiresAt"

    const val INCOMING_NOTIFICATION_ID = 730_101
    const val ACTIVE_NOTIFICATION_ID = 730_102

    fun isIncomingCall(data: Map<String, String>): Boolean =
        data["type"] == TYPE_INCOMING && !data["callId"].isNullOrBlank()

    fun intentFor(context: Context, data: Map<String, String>, action: String = ACTION_SHOW): Intent =
        Intent(context, IncomingCallActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL_ID, data["callId"])
            putExtra(EXTRA_FROM_NUMBER, data["fromNumber"] ?: data["from"] ?: data["callerNumber"])
            putExtra(EXTRA_DISPLAY_NAME, data["displayName"] ?: data["callerName"])
            putExtra(EXTRA_EXTENSION, data["extension"])
            putExtra(EXTRA_EXPIRES_AT, data["expiresAt"])
        }

    fun serviceIntent(context: Context, source: Intent, action: String): Intent =
        Intent(context, ModernDialerCallService::class.java).apply {
            this.action = action
            putExtra(EXTRA_CALL_ID, source.getStringExtra(EXTRA_CALL_ID))
            putExtra(EXTRA_FROM_NUMBER, source.getStringExtra(EXTRA_FROM_NUMBER))
            putExtra(EXTRA_DISPLAY_NAME, source.getStringExtra(EXTRA_DISPLAY_NAME))
            putExtra(EXTRA_EXTENSION, source.getStringExtra(EXTRA_EXTENSION))
            putExtra(EXTRA_EXPIRES_AT, source.getStringExtra(EXTRA_EXPIRES_AT))
        }

    @SuppressLint("MissingPermission")
    fun showIncomingCall(context: Context, data: Map<String, String>) {
        if (!canPostNotifications(context)) return
        val showIntent = intentFor(context, data)
        val pickupIntent = intentFor(context, data, ACTION_PICKUP)
        val rejectIntent = intentFor(context, data, ACTION_REJECT)
        val callId = data["callId"].orEmpty()
        val title = data["displayName"]?.takeIf { it.isNotBlank() } ?: "Incoming call"
        val number = data["fromNumber"] ?: data["from"] ?: data["callerNumber"] ?: "Unknown number"

        val fullScreen = PendingIntent.getActivity(
            context,
            requestCode(callId, 1),
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pickup = PendingIntent.getActivity(
            context,
            requestCode(callId, 2),
            pickupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reject = PendingIntent.getActivity(
            context,
            requestCode(callId, 3),
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, PushTokenManager.CHANNEL_CALLS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(number)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .addAction(0, "Reject", reject)
            .addAction(0, "Accept", pickup)
            .build()

        NotificationManagerCompat.from(context).notify(INCOMING_NOTIFICATION_ID, notification)
    }

    fun activeCallNotification(context: Context, callId: String, displayName: String?, number: String?): Notification {
        val hangupIntent = Intent(context, ModernDialerCallService::class.java).apply {
            action = ACTION_HANGUP
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_DISPLAY_NAME, displayName)
            putExtra(EXTRA_FROM_NUMBER, number)
        }
        val hangup = PendingIntent.getService(
            context,
            requestCode(callId, 4),
            hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, PushTokenManager.CHANNEL_CALLS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(displayName?.takeIf { it.isNotBlank() } ?: "Dialer call")
            .setContentText(number ?: "Call active")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(0, "Hang up", hangup)
            .build()
    }

    fun clearIncoming(context: Context) {
        NotificationManagerCompat.from(context).cancel(INCOMING_NOTIFICATION_ID)
    }

    private fun requestCode(callId: String, salt: Int): Int =
        ("$callId:$salt").hashCode()

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
