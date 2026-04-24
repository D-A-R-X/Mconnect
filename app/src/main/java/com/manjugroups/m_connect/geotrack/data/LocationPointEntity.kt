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
    val sent: Boolean = false
)
