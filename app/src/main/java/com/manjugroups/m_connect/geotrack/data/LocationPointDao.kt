package com.manjugroups.m_connect.geotrack.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LocationPointDao {
    @Insert
    suspend fun insert(point: LocationPointEntity)

    @Query("SELECT * FROM pending_points WHERE sent = 0 ORDER BY recordedAt ASC LIMIT :limit")
    suspend fun getUnsent(limit: Int = 200): List<LocationPointEntity>

    @Query("DELETE FROM pending_points WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM pending_points WHERE sent = 0")
    suspend fun getUnsentCount(): Int

    @Query("DELETE FROM pending_points WHERE sent = 1")
    suspend fun deleteSent()

    @Query("DELETE FROM pending_points WHERE recordedAt < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long)
}
