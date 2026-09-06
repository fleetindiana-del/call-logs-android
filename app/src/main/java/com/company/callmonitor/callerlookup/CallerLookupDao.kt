package com.company.callmonitor.callerlookup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CallerLookupDao {
    @Query("SELECT * FROM caller_lookup_jobs WHERE jobId = :jobId LIMIT 1")
    fun getJob(jobId: String): CallerLookupJobEntity?

    @Query("SELECT * FROM caller_lookup_jobs ORDER BY updatedAt DESC LIMIT 1")
    fun getLatestJob(): CallerLookupJobEntity?

    @Upsert
    fun upsertJob(job: CallerLookupJobEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertResults(results: List<CallerLookupResultEntity>)

    @Query(
        "SELECT * FROM caller_lookup_results " +
            "WHERE jobId = :jobId ORDER BY lookedUpAt ASC, localId ASC"
    )
    fun getResultsForJob(jobId: String): List<CallerLookupResultEntity>

    @Query(
        "SELECT * FROM caller_lookup_results " +
            "WHERE jobId = :jobId AND lookupStatus = 'found' " +
            "ORDER BY lookedUpAt DESC, localId DESC LIMIT :limit"
    )
    fun getRecentFoundResults(jobId: String, limit: Int = 30): List<CallerLookupResultEntity>

    @Query(
        "SELECT * FROM caller_lookup_results " +
            "WHERE jobId = :jobId " +
            "ORDER BY lookedUpAt DESC, localId DESC LIMIT :limit"
    )
    fun getRecentResults(jobId: String, limit: Int = 40): List<CallerLookupResultEntity>

    @Query("SELECT COUNT(*) FROM caller_lookup_results WHERE jobId = :jobId AND lookupStatus = 'found'")
    fun countFound(jobId: String): Int

    @Query(
        "SELECT * FROM caller_lookup_results " +
            "WHERE jobId = :jobId AND uploaded = 0 ORDER BY localId LIMIT :limit"
    )
    fun getPendingResults(jobId: String, limit: Int = 200): List<CallerLookupResultEntity>

    @Query("UPDATE caller_lookup_results SET uploaded = 1 WHERE localId IN (:ids)")
    fun markResultsUploaded(ids: List<Long>)

    @Insert
    fun insertLogs(logs: List<CallerLookupLogEntity>)

    @Query(
        "SELECT * FROM caller_lookup_logs " +
            "WHERE jobId = :jobId AND uploaded = 0 ORDER BY localId LIMIT :limit"
    )
    fun getPendingLogs(jobId: String, limit: Int = 300): List<CallerLookupLogEntity>

    @Query("UPDATE caller_lookup_logs SET uploaded = 1 WHERE localId IN (:ids)")
    fun markLogsUploaded(ids: List<Long>)
}

