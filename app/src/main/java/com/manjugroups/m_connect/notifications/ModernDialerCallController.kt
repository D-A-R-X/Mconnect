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
import androidx.core.app.Person
import com.manjugroups.m_connect.R
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object ModernDialerCallController {
    const val TYPE_INCOMING = "dialer-call-incoming"
    const val TYPE_ENDED = "dialer-call-ended"
    const val ACTION_PICKUP = "com.manjugroups.m_connect.dialer.PICKUP"
    const val ACTION_REJECT = "com.manjugroups.m_connect.dialer.REJECT"
    const val ACTION_HANGUP = "com.manjugroups.m_connect.dialer.HANGUP"
    const val ACTION_SHOW = "com.manjugroups.m_connect.dialer.SHOW"
    const val ACTION_KEEP_ACTIVE = "com.manjugroups.m_connect.dialer.KEEP_ACTIVE"
    const val ACTION_SET_AUDIO_ROUTE = "com.manjugroups.m_connect.dialer.SET_AUDIO_ROUTE"

    const val EXTRA_CALL_ID = "dialer.callId"
    const val EXTRA_FROM_NUMBER = "dialer.fromNumber"
    const val EXTRA_DISPLAY_NAME = "dialer.displayName"
    const val EXTRA_EXTENSION = "dialer.extension"
    const val EXTRA_EXPIRES_AT = "dialer.expiresAt"
    const val EXTRA_EVENT_ID = "dialer.eventId"
    const val EXTRA_AUDIO_ROUTE = "dialer.audioRoute"

    const val INCOMING_NOTIFICATION_ID = 730_101
    const val ACTIVE_NOTIFICATION_ID = 730_102

    fun isIncomingCall(data: Map<String, String>): Boolean =
        normalizedType(data) in INCOMING_TYPES && callId(data) != null

    fun isEndedCall(data: Map<String, String>): Boolean =
        normalizedType(data) in ENDED_TYPES && callId(data) != null

    fun intentFor(context: Context, data: Map<String, String>, action: String = ACTION_SHOW): Intent =
        Intent(context, IncomingCallActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CALL_ID, callId(data))
            putExtra(EXTRA_FROM_NUMBER, data["fromNumber"] ?: data["from"] ?: data["callerNumber"])
            putExtra(EXTRA_DISPLAY_NAME, callerName(context, data))
            putExtra(EXTRA_EXTENSION, data["extension"])
            putExtra(EXTRA_EXPIRES_AT, data["expiresAt"])
            putExtra(EXTRA_EVENT_ID, data["eventId"] ?: data["event_id"])
        }

    fun serviceIntent(context: Context, source: Intent, action: String): Intent =
        Intent(context, ModernDialerCallService::class.java).apply {
            this.action = action
            putExtra(EXTRA_CALL_ID, source.getStringExtra(EXTRA_CALL_ID))
            putExtra(EXTRA_FROM_NUMBER, source.getStringExtra(EXTRA_FROM_NUMBER))
            putExtra(EXTRA_DISPLAY_NAME, source.getStringExtra(EXTRA_DISPLAY_NAME))
            putExtra(EXTRA_EXTENSION, source.getStringExtra(EXTRA_EXTENSION))
            putExtra(EXTRA_EXPIRES_AT, source.getStringExtra(EXTRA_EXPIRES_AT))
            putExtra(EXTRA_EVENT_ID, source.getStringExtra(EXTRA_EVENT_ID))
        }

    @SuppressLint("MissingPermission")
    fun showIncomingCall(context: Context, data: Map<String, String>) {
        if (!canPostNotifications(context)) return
        if (isExpired(data["expiresAt"])) {
            clearIncoming(context)
            return
        }
        val showIntent = intentFor(context, data)
        val pickupIntent = intentFor(context, data, ACTION_PICKUP)
        val rejectIntent = intentFor(context, data, ACTION_REJECT)
        val callId = callId(data).orEmpty()
        val title = callerName(context, data) ?: "Incoming call"
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
            .setVibrate(longArrayOf(0, 700, 350, 700, 350, 700))
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    Person.Builder().setName(title).setImportant(true).build(),
                    reject,
                    pickup,
                ),
            )
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

    @SuppressLint("MissingPermission")
    fun showActiveCall(context: Context, callId: String, displayName: String?, number: String?) {
        if (!canPostNotifications(context)) return
        NotificationManagerCompat.from(context).notify(
            ACTIVE_NOTIFICATION_ID,
            activeCallNotification(context, callId, displayName, number),
        )
    }

    fun clearIncoming(context: Context) {
        NotificationManagerCompat.from(context).cancel(INCOMING_NOTIFICATION_ID)
    }

    fun clearActive(context: Context) {
        NotificationManagerCompat.from(context).cancel(ACTIVE_NOTIFICATION_ID)
    }

    fun clearCallNotifications(context: Context) {
        NotificationManagerCompat.from(context).cancel(INCOMING_NOTIFICATION_ID)
        NotificationManagerCompat.from(context).cancel(ACTIVE_NOTIFICATION_ID)
    }

    private fun requestCode(callId: String, salt: Int): Int =
        ("$callId:$salt").hashCode()

    private fun normalizedType(data: Map<String, String>): String =
        (data["type"] ?: data["event"] ?: data["eventType"])
            .orEmpty()
            .trim()
            .lowercase(Locale.US)
            .replace('_', '-')

    private fun callId(data: Map<String, String>): String? =
        (data["callId"] ?: data["call_id"] ?: data["id"])?.takeIf { it.isNotBlank() }

    private fun callerName(context: Context, data: Map<String, String>): String? {
        return listOf(
            data["clientName"],
            data["callerName"],
            data["contactName"],
            data["fromName"],
        ).firstOrNull { !it.isNullOrBlank() }
    }

    internal fun isExpired(raw: String?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (raw.isNullOrBlank()) return false
        val epochMillis = raw.toLongOrNull()?.let { if (it > 10_000_000_000L) it else it * 1_000L }
            ?: parseIsoMillis(raw)
            ?: return false
        return epochMillis <= nowMillis
    }

    internal fun timeoutMillis(
        raw: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long {
        val expiresAt = raw?.toLongOrNull()?.let {
            if (it > 10_000_000_000L) it else it * 1_000L
        } ?: raw?.let(::parseIsoMillis)
        return (expiresAt?.minus(nowMillis) ?: MAX_RING_MILLIS)
            .coerceIn(MIN_RING_MILLIS, MAX_RING_MILLIS)
    }

    private fun parseIsoMillis(raw: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                    isLenient = false
                }.parse(raw)?.time
            }.getOrNull()
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private const val MIN_RING_MILLIS = 1_000L
    private const val MAX_RING_MILLIS = 60_000L
    private val INCOMING_TYPES = setOf(TYPE_INCOMING, "modern-dialer-incoming", "incoming-call")
    private val ENDED_TYPES = setOf(TYPE_ENDED, "modern-dialer-ended", "call-ended")
}
