package com.manjugroups.m_connect.geotrack.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per attendance punch (in/out) that couldn't reach the server because
 * the device was offline / the network failed. The staff's REAL tap time is
 * captured in [clientPunchTime] so, when the queue flushes after connectivity
 * returns, the punch is recorded at the time it actually happened — not the
 * (later) sync time. This is what stops "attendance times exceeding" due to
 * network delay.
 *
 * The proof photo is kept as a local file ([photoPath]) and uploaded at flush
 * time; storing a _storage id here would be impossible while offline.
 *
 * Lives in GeoTrackDatabase alongside the other offline-first buffers.
 */
@Entity(tableName = "pending_punches")
data class PendingPunchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val isPunchIn: Boolean,
    /** ISO-8601 device time captured at the moment of the tap. */
    val clientPunchTime: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String? = null,
    /** Absolute path to the proof photo file (uploaded on flush), if any. */
    val photoPath: String? = null,
    /**
     * WHOSE punch this is. A queued punch outlives a logout, so without this a
     * punch taken by one staff could be replayed under whoever logs in next and
     * be recorded against them. The flush skips rows that do not belong to the
     * signed-in staff.
     */
    val staffId: String? = null,
    val deviceId: String? = null,
    val source: String = "mobile",
    val createdAt: Long,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)
