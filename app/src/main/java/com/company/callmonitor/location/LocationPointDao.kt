package com.company.callmonitor.location

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocationPointDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(point: LocationPointEntity)

    @Query("SELECT * FROM location_points WHERE uploadState = :state ORDER BY sequenceNumber ASC LIMIT :limit")
    fun getByState(state: String, limit: Int): List<LocationPointEntity>

    @Query("SELECT COUNT(*) FROM location_points WHERE uploadState = :state")
    fun countByState(state: String): Int

    @Query("UPDATE location_points SET uploadState = :newState WHERE localId IN (:ids)")
    fun updateState(ids: List<Long>, newState: String)

    @Query("UPDATE location_points SET uploadState = :newState, uploadedAt = :uploadedAt WHERE pointId IN (:pointIds)")
    fun updateStateByPointIds(pointIds: List<String>, newState: String, uploadedAt: Long?)

    @Query("SELECT * FROM location_points WHERE sessionId = :sessionId ORDER BY sequenceNumber DESC LIMIT 1")
    fun getLastPoint(sessionId: String): LocationPointEntity?

    @Query("SELECT COALESCE(MAX(sequenceNumber), 0) FROM location_points WHERE sessionId = :sessionId")
    fun maxSequence(sessionId: String): Long

    @Query("DELETE FROM location_points WHERE uploadState = 'SYNCED' AND recordedAt < :beforeTimestamp")
    fun pruneSynced(beforeTimestamp: Long)

    @Query("SELECT * FROM location_points ORDER BY recordedAt DESC LIMIT 1")
    fun getLatestPoint(): LocationPointEntity?

    // Full route for a session, in travel order — drives the in-app track map.
    @Query("SELECT * FROM location_points WHERE sessionId = :sessionId ORDER BY sequenceNumber ASC")
    fun getPointsForSession(sessionId: String): List<LocationPointEntity>

    // sessionId of the most recently recorded point (used when no duty is active).
    @Query("SELECT sessionId FROM location_points ORDER BY recordedAt DESC LIMIT 1")
    fun latestSessionId(): String?

    @Query("SELECT COUNT(*) FROM location_points")
    fun countAll(): Int
}
