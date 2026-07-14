package com.company.callmonitor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Runs every 30 minutes (when scheduled). Ensures any unsent calls are synced:
 * 1) Runs backfill (calls since last synced), 2) Runs periodic sync (yesterday+today),
 * 3) Retries all pending calls. If sync keeps failing, sends an alert to bot logs.
 * Also starts CallMonitorService so the foreground service is running again if it was killed.
 */
class CallSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("monitoringEnabled", false)) return Result.success()

        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val apiKey = prefs.getString("apiKey", "") ?: ""
        val deviceId = prefs.getString("deviceId", "") ?: ""
        val employeeName = prefs.getString("employeeName", "") ?: ""

        if (serverUrl.isBlank() || deviceId.isBlank()) return Result.success()

        // Bring the foreground service back up if it was killed
        try {
            val intent = android.content.Intent(applicationContext, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        } catch (e: Exception) {
            Logger.log(applicationContext, "CallSyncWorker: could not start service — ${e.message}")
        }

        // 1) Backfill missed calls since last synced
        CallBackfillManager.backfillMissedCalls(applicationContext)

        // 2) Periodic sync: yesterday + today, dedupe, send new
        try {
            PeriodicSyncManager.sync(applicationContext)
        } catch (e: Exception) {
            Logger.log(applicationContext, "CallSyncWorker: periodic sync error — ${e.message}")
        }

        // 3) Retry all pending calls
        retryPendingCalls(applicationContext, serverUrl, apiKey)

        // 4) Upload queued in-app log lines to dashboard
        DeviceLogSync.flushAll(applicationContext)

        val pendingAfter = PendingCallsManager.getPendingCalls(applicationContext)
        val configPrefs = applicationContext.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        val failureKey = "callSyncConsecutiveFailureCount"

        if (pendingAfter.isEmpty()) {
            configPrefs.edit().putInt(failureKey, 0).apply()
            Logger.log(applicationContext, "CallSyncWorker: all calls synced")
            ApiClient.sendBotLog(
                serverUrl, apiKey,
                deviceId, employeeName,
                "success", "CALL_SYNC_OK",
                "Call sync OK — app is working. Device: $deviceId, Employee: $employeeName.",
                mapOf("deviceId" to deviceId, "employeeName" to employeeName),
                applicationContext
            )
            return Result.success()
        }

        val count = configPrefs.getInt(failureKey, 0) + 1
        configPrefs.edit().putInt(failureKey, count).apply()

        val permissionGranted = ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        val issue = when {
            !permissionGranted -> "READ_CALL_LOG permission not granted"
            else -> "Network or server error — sync failed repeatedly (pending: ${pendingAfter.size})"
        }

        if (count >= 2) {
            val message = "No calls were saved. Issue: $issue. Pending call count: ${pendingAfter.size}. Device: $deviceId, Employee: $employeeName."
            val sent = ApiClient.sendBotLog(
                serverUrl, apiKey,
                deviceId, employeeName,
                "error", "CALLS_SYNC_FAILED",
                message,
                mapOf(
                    "pendingCount" to pendingAfter.size,
                    "consecutiveFailureCount" to count,
                    "reason" to issue
                ),
                applicationContext
            )
            if (sent) {
                Logger.log(applicationContext, "CallSyncWorker: bot-log alert sent — $message")
                configPrefs.edit().putInt(failureKey, 0).apply()
            }
        }

        return Result.success()
    }

    private fun retryPendingCalls(context: Context, serverUrl: String, apiKey: String) {
        val pendingCalls = PendingCallsManager.getPendingCalls(context)
        if (pendingCalls.isEmpty()) return

        Logger.log(context, "CallSyncWorker: retrying ${pendingCalls.size} pending call(s)...")
        val succeeded = mutableListOf<CallLogEntry>()
        val permanentlyFailed = mutableListOf<CallLogEntry>()

        for (call in pendingCalls) {
            when (ApiClient.syncCall(serverUrl, apiKey, call, context)) {
                SyncResult.SUCCESS -> {
                    succeeded.add(call)
                    CallBackfillManager.markLastSyncedTimestamp(context, call.timestamp)
                    PeriodicSyncManager.markCallAsSynced(context, call)
                }
                SyncResult.PERMANENT_FAILURE -> permanentlyFailed.add(call)
                SyncResult.TRANSIENT_FAILURE -> { /* keep in queue */ }
            }
        }

        val toRemove = succeeded + permanentlyFailed
        if (toRemove.isNotEmpty()) {
            PendingCallsManager.clearSyncedCalls(context, toRemove)
        }
        succeeded.maxOfOrNull { it.timestamp }?.let { CallBackfillManager.markLastSyncedTimestamp(context, it) }
    }
}
