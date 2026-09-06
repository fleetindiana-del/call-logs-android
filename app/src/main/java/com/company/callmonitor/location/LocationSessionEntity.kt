package com.company.callmonitor.location

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "location_sessions")
data class LocationSessionEntity(
    @PrimaryKey val sessionId: String,
    val employeeId: String,
    val deviceId: String,
    val vehicleId: String?,
    val startTime: Long,
    val endTime: Long?,
    /** ACTIVE, COMPLETED, INTERRUPTED */
    val status: String,
    val uploadedAt: Long?
)
