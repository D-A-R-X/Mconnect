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

    /**
     * Purge only SENT points older than [cutoffMs]. Preserves unsent
     * points so a multi-day offline period doesn't lose the buffered
     * journey — they still get to flush when the network returns.
     */
    @Query("DELETE FROM pending_points WHERE sent = 1 AND recordedAt < :cutoffMs")
    suspend fun deleteSentOlderThan(cutoffMs: Long)

    /**
     * Safety-cap purge for genuinely abandoned unsent points (e.g. the
     * staff was offline for 30+ days). Without this, the local DB
     * could grow unbounded if the device never recovers.
     */
    @Query("DELETE FROM pending_points WHERE sent = 0 AND recordedAt < :cutoffMs")
    suspend fun deleteUnsentOlderThan(cutoffMs: Long)
}
