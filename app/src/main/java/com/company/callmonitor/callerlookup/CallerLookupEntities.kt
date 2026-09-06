package com.company.callmonitor.callerlookup

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "caller_lookup_jobs")
data class CallerLookupJobEntity(
    @PrimaryKey val jobId: String,
    val status: String,
    val deviceId: String,
    val mobileProvider: String,
    val seriesId: String,
    val seriesPrefix: String,
    val seriesLabel: String,
    val startNumber: String,
    val endNumber: String,
    val totalPlanned: Int,
    val delayMs: Long,
    val workers: Int,
    val lookupProviderId: String,
    val maxRetries: Int,
    val cursorIndex: Int = 0,
    val processed: Int = 0,
    val successful: Int = 0,
    val failed: Int = 0,
    val totalLookupDurationMs: Long = 0,
    val currentNumber: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "caller_lookup_results",
    indices = [
        Index(value = ["jobId", "phoneNumber"], unique = true),
        Index(value = ["jobId", "uploaded"])
    ]
)
data class CallerLookupResultEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val jobId: String,
    val phoneNumber: String,
    val callerName: String?,
    val lookupStatus: String,
    val provider: String,
    val lookupProviderId: String,
    val metadataJson: String?,
    val kycJson: String?,
    val rawResponseJson: String?,
    val durationMs: Long,
    val retryCount: Int,
    val error: String?,
    val lookedUpAt: Long,
    val uploaded: Boolean = false
)

@Entity(
    tableName = "caller_lookup_logs",
    indices = [Index(value = ["jobId", "uploaded"])]
)
data class CallerLookupLogEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val jobId: String,
    val level: String,
    val message: String,
    val phoneNumber: String? = null,
    val durationMs: Long? = null,
    val detailsJson: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    val uploaded: Boolean = false
)

