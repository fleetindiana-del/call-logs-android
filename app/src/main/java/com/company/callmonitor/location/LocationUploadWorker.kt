package com.company.callmonitor.location

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.company.callmonitor.Logger

class LocationUploadWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val db = FleetLocationDatabase.getInstance(applicationContext)
        val dao = db.locationPointDao()

        // Also poll missed commands when network is available
        try {
            pollCommands()
        } catch (e: Exception) {
            Logger.log(applicationContext, "⚠️ command poll failed: ${e.message}")
        }

        var uploadedAny = false
        var hasMore = true
        var loops = 0

        while (hasMore && loops < 20) {
            loops++
            val pending = dao.getByState("PENDING", BATCH_LIMIT)
            if (pending.isEmpty()) {
                hasMore = false
                break
            }

            val ids = pending.map { it.localId }
            dao.updateState(ids, "UPLOADING")

            val ack = LocationApiClient.uploadBatch(applicationContext, pending)
            if (ack == null) {
                dao.updateState(ids, "PENDING")
                return Result.retry()
            }

            val synced = (ack.acceptedPointIds + ack.duplicatePointIds).distinct()
            if (synced.isNotEmpty()) {
                dao.updateStateByPointIds(synced, "SYNCED", System.currentTimeMillis())
                uploadedAny = true
            }

            val syncedSet = synced.toSet()
            val leftover = pending.filter { it.pointId !in syncedSet }.map { it.localId }
            if (leftover.isNotEmpty()) {
                dao.updateState(leftover, "PENDING")
            }

            hasMore = ack.hasMore || pending.size >= BATCH_LIMIT
        }

        if (uploadedAny) {
            applicationContext.getSharedPreferences("location_prefs", Context.MODE_PRIVATE)
                .edit().putLong("lastUploadAt", System.currentTimeMillis()).apply()
            val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            dao.pruneSynced(thirtyDaysAgo)
        }

        return Result.success()
    }

    private fun pollCommands() {
        val commands = LocationApiClient.getCommands(applicationContext)
        for (cmd in commands) {
            val type = cmd.optString("type", cmd.optString("action"))
            val id = cmd.optString("_id", cmd.optString("id"))
            when (type) {
                "location_upload", "location_latest" -> {
                    if (id.isNotBlank()) {
                        LocationApiClient.updateCommandStatus(applicationContext, id, "UPLOADING")
                    }
                    // continue upload loop in doWork
                }
                "location_live_mode" -> {
                    applicationContext.getSharedPreferences("location_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("liveMode", true).apply()
                    LocationTrackingService.applyLiveMode(applicationContext)
                    if (id.isNotBlank()) {
                        LocationApiClient.updateCommandStatus(applicationContext, id, "COMPLETED")
                    }
                }
                "location_stop_live_mode" -> {
                    applicationContext.getSharedPreferences("location_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("liveMode", false).apply()
                    LocationTrackingService.applyLiveMode(applicationContext)
                    if (id.isNotBlank()) {
                        LocationApiClient.updateCommandStatus(applicationContext, id, "COMPLETED")
                    }
                }
                "location_start_tracking" -> {
                    LocationDutyController.startTracking(applicationContext, "poll_start")
                    if (id.isNotBlank()) {
                        LocationApiClient.updateCommandStatus(applicationContext, id, "COMPLETED")
                    }
                }
                "location_stop_tracking" -> {
                    LocationDutyController.stopTracking(applicationContext, "poll_stop")
                    if (id.isNotBlank()) {
                        LocationApiClient.updateCommandStatus(applicationContext, id, "COMPLETED")
                    }
                }
            }
        }
    }

    companion object {
        private const val BATCH_LIMIT = 400
        private const val WORK_NAME = "LocationUploadWorker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<LocationUploadWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
