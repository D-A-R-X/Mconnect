package com.manjugroups.m_connect.geotrack.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PendingChatMessageDao {
    @Insert
    suspend fun insert(message: PendingChatMessageEntity): Long

    @Query("SELECT * FROM pending_chat_messages ORDER BY createdAt ASC")
    suspend fun listAll(): List<PendingChatMessageEntity>

    /** Pending messages for a single thread — used when re-opening a chat. */
    @Query("""
        SELECT * FROM pending_chat_messages
        WHERE (:conversationId IS NOT NULL AND conversationId = :conversationId)
           OR (:channelId      IS NOT NULL AND channelId      = :channelId)
        ORDER BY createdAt ASC
    """)
    suspend fun listForThread(conversationId: String?, channelId: String?): List<PendingChatMessageEntity>

    @Query("DELETE FROM pending_chat_messages WHERE localId = :localId")
    suspend fun deleteByLocalId(localId: String)

    @Query("UPDATE pending_chat_messages SET attemptCount = attemptCount + 1, lastError = :err WHERE localId = :localId")
    suspend fun bumpAttempt(localId: String, err: String?)

    @Query("SELECT COUNT(*) FROM pending_chat_messages")
    suspend fun count(): Int
}
