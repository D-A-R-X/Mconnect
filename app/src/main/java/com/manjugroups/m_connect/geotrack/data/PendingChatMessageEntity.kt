package com.manjugroups.m_connect.geotrack.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per outbound chat message that hasn't yet been confirmed by
 * the server. Survives process death, network outages, and app
 * restarts so the staff can type while offline (WhatsApp-style) and
 * the message flushes automatically when connectivity returns.
 *
 * Lives in the GeoTrackDatabase alongside the GPS / events buffers
 * because it's the same offline-first storage pattern — keeps Room
 * configuration to a single DB.
 */
@Entity(tableName = "pending_chat_messages")
data class PendingChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Client-side UUID; also reused as the optimistic message id in the UI. */
    val localId: String,
    val conversationId: String?,
    val channelId: String?,
    val body: String,
    val parentMessageId: String?,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)
