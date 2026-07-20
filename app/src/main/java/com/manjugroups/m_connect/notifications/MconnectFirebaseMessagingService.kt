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

    private companion object {
        // The backend used to double-push chat messages (in-app notification
        // hook + explicit chat push) with divergent payloads, which hashed to
        // two different notification ids — the user saw the same "Hi" twice.
        // Collapse look-alike pushes that arrive close together onto one
        // notification id so a duplicate replaces rather than stacks.
        const val DEDUPE_WINDOW_MS = 20_000L
        const val DEDUPE_MAX_ENTRIES = 32
        // Stable request code for the chat group-summary PendingIntent.
        const val CHAT_SUMMARY_REQUEST = 0x5044D2
        val recentPushes = object : LinkedHashMap<String, Pair<Int, Long>>(
            DEDUPE_MAX_ENTRIES, 0.75f, true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Pair<Int, Long>>?
            ): Boolean = size > DEDUPE_MAX_ENTRIES
        }

        fun dedupedNotifId(key: String, freshId: Int): Int = synchronized(recentPushes) {
            val now = System.currentTimeMillis()
            val prior = recentPushes[key]
            if (prior != null && now - prior.second <= DEDUPE_WINDOW_MS) {
                recentPushes[key] = prior.first to now
                prior.first
            } else {
                recentPushes[key] = freshId to now
                freshId
            }
        }
    }

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
            ?: message.data["chatBody"]
            ?: message.data["msg"]
            ?: message.data["messageBody"]
            ?: message.data["messageText"]
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
        // Same title+body+type arriving within the dedupe window reuses the
        // prior notification id — a server double-send replaces instead of
        // showing the identical notification twice.
        val notifId = dedupedNotifId(
            key = "$title|$body|${message.data["type"].orEmpty()}",
            freshId = notifKey.hashCode(),
        )

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

        // Chat pushes carry a conversation identity — channelId for a group,
        // conversationId for a DM. Collapse those per-conversation (MessagingStyle
        // + a group summary) so a user or group that sends many messages shows as
        // ONE expandable entry instead of one row per message.
        val chatChannelId = message.data["channelId"]?.takeIf { it.isNotBlank() }
        val chatConversationId = message.data["conversationId"]?.takeIf { it.isNotBlank() }
        val convKey = chatChannelId ?: chatConversationId
        if (convKey != null) {
            if (!canPostNotifications()) return
            val summaryIntent = PendingIntent.getActivity(
                this,
                CHAT_SUMMARY_REQUEST,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(
                        WorkflowNotificationRoute.EXTRA_TARGET_TAB,
                        WorkflowNotificationRoute.TAB_CHAT,
                    )
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            ChatNotifications.postChatMessage(
                ctx = this,
                notifChannelId = channelId,
                convKey = convKey,
                isGroupChat = chatChannelId != null,
                // The conversation's own name (channel/DM), not the display
                // title (which for a mention is "X mentioned you").
                conversationTitle = message.data["chatTitle"]?.takeIf { it.isNotBlank() }
                    ?: title,
                // Per-message sender — the backend now sends `senderName`; older
                // payloads fall back to the title (which is the sender for a DM).
                senderName = message.data["senderName"]?.takeIf { it.isNotBlank() }
                    ?: title,
                body = body,
                sentTime = message.sentTime,
                messageId = message.data["messageId"],
                contentIntent = pendingIntent,
                summaryIntent = summaryIntent,
            )
            return
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            // Heads-up for chat / approvals / alerts; the channel
            // importance already governs sound + heads-up on API 26+.
            // DEFAULT_ALL adds the default sound + vibration + lights so
            // the legacy general channel (API < 26 fallback) still feels
            // alive without per-channel config.
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        showNotification(notifId, notification)
    }

    /** Shared gate for BOTH the chat-grouped path and the single-notification
     *  path: honour the in-app notifications toggle and the runtime permission. */
    private fun canPostNotifications(): Boolean {
        val session = com.manjugroups.m_connect.auth.SessionManager(this)
        if (!session.isNotificationEnabled) {
            Log.w("MconnectFCM", "canPostNotifications: notifications toggled off in settings")
            return false
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("MconnectFCM", "canPostNotifications: POST_NOTIFICATIONS not granted")
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(id: Int, notification: android.app.Notification) {
        if (!canPostNotifications()) return
        Log.d("MconnectFCM", "showNotification: calling notify for notification id=$id")
        NotificationManagerCompat.from(this).notify(id, notification)
    }
}
