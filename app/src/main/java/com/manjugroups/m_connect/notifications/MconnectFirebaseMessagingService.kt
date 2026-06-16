package com.manjugroups.m_connect.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.GeoTrackBootstrapSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.util.Log


class MconnectFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val session = SessionManager(applicationContext)
        if (!session.isLoggedIn) return

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                PushTokenManager.registerRefreshedToken(applicationContext, session, token)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d("MconnectFCM", "onMessageReceived: from=${message.from}, data=${message.data}, notificationTitle=${message.notification?.title}, notificationBody=${message.notification?.body}")

        if (message.data["type"] == "geotrack_sync") {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    GeoTrackBootstrapSync.sync(applicationContext, allowPromptConsent = false)
                }
            }
            if (message.data["silent"] == "true") return
        }

        val title = message.data["title"]
            ?: message.data["subject"]
            ?: message.data["chatTitle"]
            ?: message.data["targetTitle"]
            ?: message.notification?.title
            ?: getString(R.string.app_name)

        val body = message.data["body"]
            ?: message.data["message"]
            ?: message.data["text"]
            ?: message.data["content"]
            ?: message.data["desc"]
            ?: message.data["description"]
            ?: message.notification?.body

        if (body.isNullOrBlank()) {
            Log.w("MconnectFCM", "onMessageReceived: empty/null body, returning early")
            return
        }
        val inferredTab = when {
            message.data["targetTab"] != null -> message.data["targetTab"]
            message.data["channelId"] != null || message.data["conversationId"] != null -> WorkflowNotificationRoute.TAB_CHAT
            else -> WorkflowNotificationRoute.TAB_HR
        }
        val inferredScreen = when {
            message.data["targetScreen"] != null -> message.data["targetScreen"]
            message.data["channelId"] != null -> WorkflowNotificationRoute.SCREEN_CHAT_CHANNEL
            message.data["conversationId"] != null -> WorkflowNotificationRoute.SCREEN_CHAT_CONVERSATION
            else -> null
        }
        val inferredMode = when (inferredTab) {
            WorkflowNotificationRoute.TAB_CHAT -> WorkflowNotificationRoute.MODE_CHAT
            else -> message.data["targetMode"] ?: WorkflowNotificationRoute.MODE_HISTORY
        }

        val routeIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(
                WorkflowNotificationRoute.EXTRA_TARGET_TAB,
                inferredTab
            )
            putExtra(
                WorkflowNotificationRoute.EXTRA_TARGET_SCREEN,
                inferredScreen
            )
            putExtra(
                WorkflowNotificationRoute.EXTRA_TARGET_MODE,
                inferredMode
            )
            putExtra(
                WorkflowNotificationRoute.EXTRA_ENTITY_ID,
                message.data["entityId"]
            )
            putExtra(
                WorkflowNotificationRoute.EXTRA_ACTION_URL,
                message.data["actionUrl"]
            )
            putExtra(
                WorkflowNotificationRoute.EXTRA_CHANNEL_ID,
                message.data["channelId"]
            )
            putExtra(
                WorkflowNotificationRoute.EXTRA_CONVERSATION_ID,
                message.data["conversationId"]
            )
            putExtra(
                WorkflowNotificationRoute.EXTRA_MESSAGE_ID,
                message.data["messageId"]
            )
            putExtra(
                WorkflowNotificationRoute.EXTRA_TARGET_TITLE,
                message.data["chatTitle"] ?: message.data["targetTitle"] ?: title
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            (message.data["eventId"] ?: body).hashCode(),
            routeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, PushTokenManager.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        showNotification((message.data["eventId"] ?: body).hashCode(), notification)
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(id: Int, notification: android.app.Notification) {
        val session = com.manjugroups.m_connect.auth.SessionManager(this)
        if (!session.isNotificationEnabled) {
            Log.w("MconnectFCM", "showNotification: skipped because notifications are toggled off in settings")
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("MconnectFCM", "showNotification: skipped because POST_NOTIFICATIONS permission is not granted")
            return
        }

        Log.d("MconnectFCM", "showNotification: calling notify for notification id=$id")
        NotificationManagerCompat.from(this).notify(id, notification)
    }
}
