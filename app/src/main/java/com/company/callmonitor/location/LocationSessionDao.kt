package com.company.callmonitor.location

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocationSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(session: LocationSessionEntity)

    @Query("SELECT * FROM location_sessions WHERE status = 'ACTIVE' ORDER BY startTime DESC LIMIT 1")
    fun getActiveSession(): LocationSessionEntity?

    @Query("UPDATE location_sessions SET status = 'COMPLETED', endTime = :endTime WHERE sessionId = :sessionId")
    fun markCompleted(sessionId: String, endTime: Long)

    @Query("UPDATE location_sessions SET status = 'INTERRUPTED', endTime = :endTime WHERE sessionId = :sessionId")
    fun markInterrupted(sessionId: String, endTime: Long)

    @Query("UPDATE location_sessions SET uploadedAt = :uploadedAt WHERE sessionId = :sessionId")
    fun markUploaded(sessionId: String, uploadedAt: Long)

    @Query("SELECT * FROM location_sessions ORDER BY startTime DESC LIMIT 20")
    fun getRecentSessions(): List<LocationSessionEntity>
}
