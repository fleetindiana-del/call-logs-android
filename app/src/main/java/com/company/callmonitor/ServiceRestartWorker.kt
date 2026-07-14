package com.company.callmonitor

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * One-time WorkManager job: starts [CallMonitorService] when monitoring is enabled
 * but the service is not running. Used when immediate FGS restart from [Service.onDestroy]
 * fails (Android 12+ background restrictions).
 */
class ServiceRestartWorker(
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
            CallSyncScheduler.schedule(ctx)
            return Result.success()
        }
        return try {
            val intent = Intent(ctx, CallMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            Logger.log(ctx, "ServiceRestartWorker: started CallMonitorService")
            CallSyncScheduler.schedule(ctx)
            Result.success()
        } catch (e: Exception) {
            Logger.log(ctx, "ServiceRestartWorker: start failed — ${e.javaClass.simpleName}: ${e.message}")
            Result.retry()
        }
    }
}

object ServiceRestartScheduler {

    fun enqueueRestart(context: Context) {
        val appCtx = context.applicationContext
        val builder = OneTimeWorkRequestBuilder<ServiceRestartWorker>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(appCtx).enqueue(builder.build())
    }

    /**
     * Delayed restart after boot so FGS restrictions right after BOOT_COMPLETED are less likely to apply.
     */
    fun enqueueDelayedRestart(context: Context, delay: Long, unit: TimeUnit) {
        val appCtx = context.applicationContext
        val request = OneTimeWorkRequestBuilder<ServiceRestartWorker>()
            .setInitialDelay(delay, unit)
            .build()
        WorkManager.getInstance(appCtx).enqueue(request)
    }
}
