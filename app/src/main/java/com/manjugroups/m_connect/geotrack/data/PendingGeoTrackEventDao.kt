package com.manjugroups.m_connect.geotrack.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PendingGeoTrackEventDao {
    @Insert
    suspend fun insert(event: PendingGeoTrackEventEntity)

    @Query("SELECT * FROM pending_events ORDER BY occurredAt ASC LIMIT :limit")
    suspend fun getPending(limit: Int = 100): List<PendingGeoTrackEventEntity>

    @Query("DELETE FROM pending_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM pending_events")
    suspend fun getPendingCount(): Int

    @Query("DELETE FROM pending_events WHERE occurredAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
