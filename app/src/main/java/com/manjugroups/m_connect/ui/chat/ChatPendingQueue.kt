package com.manjugroups.m_connect.ui.chat

import android.content.Context
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.data.GeoTrackDatabase
import com.manjugroups.m_connect.geotrack.data.PendingChatMessageEntity
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.SendMessageRequest

/**
 * Persistent outbox for chat messages, mirroring WhatsApp's behaviour:
 * the staff can type and "send" while offline — the message lands here
 * first, the UI marks it pending, and a background flush (on network
 * reconnect, app resume, or screen open) walks the queue oldest-first
 * and replays each row via the existing /api/chat/messages/send route.
 *
 * Successful sends are deleted from the queue; failures bump the
 * attempt counter so we can surface "this message couldn't send"
 * in the UI without losing the body.
 */
object ChatPendingQueue {

    /** Insert one message into the persistent queue. Returns the localId. */
    suspend fun enqueue(
        context: Context,
        localId: String,
        conversationId: String?,
        channelId: String?,
        body: String,
        parentMessageId: String?,
    ): String {
        val dao = GeoTrackDatabase.getInstance(context.applicationContext)
            .pendingChatMessageDao()
        dao.insert(
            PendingChatMessageEntity(
                localId = localId,
                conversationId = conversationId,
                channelId = channelId,
                body = body,
                parentMessageId = parentMessageId,
                createdAt = System.currentTimeMillis(),
            )
        )
        return localId
    }

    /** Drop a queued message (call once the server has confirmed it). */
    suspend fun confirmSent(context: Context, localId: String) {
        GeoTrackDatabase.getInstance(context.applicationContext)
            .pendingChatMessageDao().deleteByLocalId(localId)
    }

    /** Pending messages for one chat/conversation — used to hydrate the
     *  thread when re-opening a screen we just sent into. */
    suspend fun listForThread(
        context: Context,
        conversationId: String?,
        channelId: String?,
    ): List<PendingChatMessageEntity> =
        GeoTrackDatabase.getInstance(context.applicationContext)
            .pendingChatMessageDao()
            .listForThread(conversationId, channelId)

    /**
     * Walk every queued row oldest-first and re-attempt the send. Each
     * `onSent` callback fires when one message lands so the caller can
     * update its in-memory UI (replace the optimistic id with the real
     * server id). Returns the number of messages flushed.
     */
    suspend fun flush(
        context: Context,
        api: ApiService = ApiService.create(),
        session: SessionManager = SessionManager(context.applicationContext),
        onSent: (suspend (localId: String, serverMessageId: String?) -> Unit)? = null,
    ): Int {
        if (!session.isLoggedIn) return 0
        val dao = GeoTrackDatabase.getInstance(context.applicationContext)
            .pendingChatMessageDao()
        val pending = dao.listAll()
        if (pending.isEmpty()) return 0
        var sent = 0
        for (msg in pending) {
            val ok = runCatching {
                val resp = api.sendMessage(
                    token = session.bearerToken,
                    body = SendMessageRequest(
                        channelId = msg.channelId,
                        conversationId = msg.conversationId,
                        body = msg.body,
                        parentMessageId = msg.parentMessageId,
                    ),
                )
                if (resp.success) {
                    dao.deleteByLocalId(msg.localId)
                    onSent?.invoke(msg.localId, resp.messageId)
                    true
                } else {
                    dao.bumpAttempt(msg.localId, resp.toString())
                    false
                }
            }.getOrElse { e ->
                dao.bumpAttempt(msg.localId, e.message)
                false
            }
            if (!ok) break // network's still down — stop, try again later
            sent++
        }
        return sent
    }
}
