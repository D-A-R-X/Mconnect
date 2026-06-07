package com.manjugroups.m_connect.geotrack.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_events")
data class PendingGeoTrackEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val metadataJson: String,
    val occurredAt: Long,
)
