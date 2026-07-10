package com.manjugroups.m_connect.geotrack.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_points")
data class LocationPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val altitude: Double?,
    val activity: String,
    val activityConfidence: Int,
    val isMock: Boolean,
    val batteryPct: Int,
    val networkType: String,
    val gpsEnabled: Boolean,
    val airplaneMode: Boolean,
    val recordedAt: Long,
    val sent: Boolean = false,
    // Tracking session the point belongs to, stamped at CAPTURE time. Sync
    // groups by this instead of the live activeTrackingSessionId, so a
    // buffered backlog still uploads under its own session after clock-out,
    // a service restart, or the next day — never attributed to the wrong
    // session or stranded because the live id was cleared at teardown.
    val sessionId: String? = null,
)
