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

        if (message.data["type"] == "geotrack_sync") {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    GeoTrackBootstrapSync.sync(applicationContext, allowPromptConsent = false)
                }
            }
            if (message.data["silent"] == "true") return
        }

        val title = message.data["title"] ?: message.notification?.title ?: getString(R.string.app_name)
        val body = message.data["body"] ?: message.notification?.body ?: return
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

        // Notification ID strategy — pick the most distinctive field
        // available so two messages don't collide and clobber each other:
        //   1. messageId / conversationId — per-chat unique, so each
        //      DM lands as a separate heads-up.
        //   2. eventId — for HR/workflow notifications.
        //   3. body + sentTime hash — fallback that still varies per
        //      arrival, so two identical-bodied messages a minute apart
        //      get two notifications instead of one.
        val notifKey = message.data["messageId"]
            ?: message.data["eventId"]
            ?: ((body) + ":" + message.sentTime)
        val notifId = notifKey.hashCode()

        val pendingIntent = PendingIntent.getActivity(
            this,
            notifId,
            routeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Route to the right per-type channel so the user can mute /
        // customise each category in system settings. Unknown types fall
        // back to the general channel so a new server-side notification
        // type still surfaces instead of silently dropping.
        val type = message.data["type"]
        val channelId = PushTokenManager.channelForType(type)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            // Heads-up for chat / approvals / alerts; the channel
            // importance already governs sound + heads-up on API 26+.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        showNotification(notifId, notification)
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(id: Int, notification: android.app.Notification) {
        // Respect the in-app "Manage Notification" toggle. The backend
        // also stops targeting this device once it unregisters the push
        // token, but a push can still arrive in the gap between the user
        // toggling off and the unregister landing — suppress it here so
        // the toggle is honoured immediately.
        if (!com.manjugroups.m_connect.auth.SessionManager(this).isNotificationEnabled) {
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        NotificationManagerCompat.from(this).notify(id, notification)
    }
}
