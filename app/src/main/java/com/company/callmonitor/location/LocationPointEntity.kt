package com.company.callmonitor.location

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "location_points",
    indices = [
        Index(value = ["pointId"], unique = true),
        Index(value = ["sessionId", "sequenceNumber"]),
        Index(value = ["uploadState"])
    ]
)
data class LocationPointEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val pointId: String,
    val sessionId: String,
    val deviceId: String,
    val sequenceNumber: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float,
    val bearingDegrees: Float,
    val altitudeMeters: Double,
    val recordedAt: Long,
    val savedAt: Long,
    val batteryPercent: Int?,
    val provider: String?,
    val isMockLocation: Boolean,
    /** PENDING, UPLOADING, SYNCED */
    val uploadState: String,
    val uploadedAt: Long? = null
)
