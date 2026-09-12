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
    // What the staff was doing when this point was recorded — a CP trip, an
    // on-duty trip, or the plain shift. Stamped at CAPTURE time for the same
    // reason as sessionId: a backlog flushed hours later must stay attributed
    // to the trip it happened on, not to whatever is running at upload time.
    // This is what lets the backend attribute distance to a trip instead of
    // falling back to a straight line.
    val contextType: String? = null,
    val contextId: String? = null,
)
