package com.manjugroups.m_connect.notifications

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.manjugroups.m_connect.R

/**
 * Collapses chat pushes so the shade doesn't fill with one notification per
 * message. Each conversation (a DM sender or a channel/group) maps to ONE
 * stable notification built with [NotificationCompat.MessagingStyle]: a new
 * message from the same conversation appends to that notification (showing the
 * recent messages) instead of stacking a fresh one. All chat notifications also
 * share a group + summary so several conversations bundle into a single entry.
 *
 * Before this, every push used a per-message id, so N messages from one person
 * or one group showed as N separate rows.
 */
object ChatNotifications {

    /** Shared group key so conversations bundle under one summary in the shade. */
    const val GROUP_CHAT = "com.manjugroups.m_connect.CHAT"

    /** Stable id for the group summary (arbitrary, unlikely to collide). */
    private const val SUMMARY_ID = 0x6D_C0DE

    /** Most recent messages we shed a heads-up for, to swallow server
     *  double-sends of the same message (would otherwise add a duplicate line). */
    private const val DEDUPE_MS = 30_000L
    private val addedMessages = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > 128
    }

    /** Stable per-conversation notification id (same across a conversation's messages). */
    fun conversationNotifId(convKey: String): Int = "chatconv:$convKey".hashCode()

    /**
     * Post/append a chat message notification for the conversation [convKey].
     * Caller is responsible for the notifications-enabled / POST_NOTIFICATIONS
     * gate (see MconnectFirebaseMessagingService.canPostNotifications).
     */
    @SuppressLint("MissingPermission")
    fun postChatMessage(
        ctx: Context,
        notifChannelId: String,
        convKey: String,
        isGroupChat: Boolean,
        conversationTitle: String,
        senderName: String,
        body: String,
        sentTime: Long,
        messageId: String?,
        contentIntent: PendingIntent,
        summaryIntent: PendingIntent,
    ) {
        // Swallow a re-delivery of the same message (server double-send) so it
        // doesn't add a duplicate line to the conversation.
        val dedupeKey = messageId?.takeIf { it.isNotBlank() } ?: "$convKey|$body|$sentTime"
        synchronized(addedMessages) {
            val now = System.currentTimeMillis()
            val prior = addedMessages[dedupeKey]
            if (prior != null && now - prior <= DEDUPE_MS) return
            addedMessages[dedupeKey] = now
        }

        val notifId = conversationNotifId(convKey)
        val self = Person.Builder().setName("You").setKey("self:me").build()
        val style = extractExisting(ctx, notifId) ?: NotificationCompat.MessagingStyle(self)

        // Distinct sender per person in a group (so "John"/"Mary" read apart);
        // for a DM every message is the one partner, keyed by the conversation.
        val senderKey = if (isGroupChat) "s:$senderName" else "c:$convKey"
        val sender = Person.Builder().setName(senderName).setKey(senderKey).build()
        style.addMessage(NotificationCompat.MessagingStyle.Message(body, sentTime, sender))
        if (isGroupChat) {
            style.conversationTitle = conversationTitle
            style.isGroupConversation = true
        } else {
            // DM: no conversation title → Android shows the sender's name.
            style.isGroupConversation = false
        }

        val nm = NotificationManagerCompat.from(ctx)

        val child = NotificationCompat.Builder(ctx, notifChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(style)
            .setGroup(GROUP_CHAT)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        nm.notify(notifId, child)

        // Group summary: what the shade shows when 2+ conversations are active.
        val summary = NotificationCompat.Builder(ctx, notifChannelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setGroup(GROUP_CHAT)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(summaryIntent)
            .build()
        nm.notify(SUMMARY_ID, summary)
    }

    private fun extractExisting(ctx: Context, notifId: Int): NotificationCompat.MessagingStyle? =
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val active = nm.activeNotifications.firstOrNull { it.id == notifId } ?: return null
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(active.notification)
        }.getOrNull()
}
