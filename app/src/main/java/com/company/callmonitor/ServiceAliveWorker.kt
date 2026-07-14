package com.company.callmonitor

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Lightweight periodic worker (every 15 min): if monitoring should be on but
 * [CallMonitorService] is not running, start it. Complements [CallSyncWorker].
 */
class ServiceAliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("monitoringEnabled", false)) {
            return Result.success()
        }
        val serverUrl = prefs.getString("serverUrl", "") ?: ""
        val deviceId = prefs.getString("deviceId", "") ?: ""
        if (serverUrl.isBlank() || deviceId.isBlank()) {
            return Result.success()
        }
        if (CallMonitorService.isRunning) {
            return Result.success()
        }
        return try {
            val intent = Intent(ctx, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            Logger.log(ctx, "ServiceAliveWorker: restarted CallMonitorService (was not running)")
            Result.success()
        } catch (e: Exception) {
            Logger.log(ctx, "ServiceAliveWorker: start failed — ${e.javaClass.simpleName}: ${e.message}")
            ServiceRestartScheduler.enqueueRestart(ctx)
            Result.success()
        }
    }
}
