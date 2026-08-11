package com.manjugroups.m_connect.geotrack.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PendingPunchDao {
    @Insert
    suspend fun insert(punch: PendingPunchEntity): Long

    /** Oldest first — punches flush in the order they were taken. */
    @Query("SELECT * FROM pending_punches ORDER BY createdAt ASC")
    suspend fun listAll(): List<PendingPunchEntity>

    @Query("DELETE FROM pending_punches WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE pending_punches SET attemptCount = attemptCount + 1, lastError = :err WHERE id = :id")
    suspend fun bumpAttempt(id: Long, err: String?)

    @Query("SELECT COUNT(*) FROM pending_punches")
    suspend fun count(): Int
}
