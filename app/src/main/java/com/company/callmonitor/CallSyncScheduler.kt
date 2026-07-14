package com.company.callmonitor

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the 4-hour call-sync worker and a 15-minute service keep-alive check
 * when monitoring is on; cancels both when monitoring is off.
 */
object CallSyncScheduler {

    private const val SYNC_WORK_NAME = "CallSyncWorker"
    private const val ALIVE_WORK_NAME = "ServiceAliveWorker"

    fun schedule(context: Context) {
        val appCtx = context.applicationContext
        val wm = WorkManager.getInstance(appCtx)

        // ── 4-hour periodic sync (fallback for missed broadcasts) ─────────
        val syncConstraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val syncBuilder = PeriodicWorkRequestBuilder<CallSyncWorker>(4, TimeUnit.HOURS)
            .setConstraints(syncConstraints)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            syncBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        wm.enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncBuilder.build()
        )

        // ── 15-minute service keep-alive check ───────────────────────────
        val aliveBuilder = PeriodicWorkRequestBuilder<ServiceAliveWorker>(15, TimeUnit.MINUTES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            aliveBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        wm.enqueueUniquePeriodicWork(
            ALIVE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            aliveBuilder.build()
        )

        MonitoringAlarmScheduler.startAlarmChain(appCtx)
    }

    fun cancel(context: Context) {
        val appCtx = context.applicationContext
        MonitoringAlarmScheduler.cancelAlarm(appCtx)
        val wm = WorkManager.getInstance(appCtx)
        wm.cancelUniqueWork(SYNC_WORK_NAME)
        wm.cancelUniqueWork(ALIVE_WORK_NAME)
    }
}
