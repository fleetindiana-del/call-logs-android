package com.company.callmonitor

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps a shift honest while it is on, independently of location tracking.
 *
 * The location watchdog is cancelled whenever tracking is stopped, which is
 * precisely when a shift most needs supervising: tracking stopped but the shift is
 * still open. This worker runs for the life of the shift and does the things that
 * must not depend on the tracking service being alive — end the shift at its
 * scheduled end, deliver queued shift events, send a heartbeat, and learn from the
 * server whether it has closed the shift.
 *
 * It exists only while a shift is on: scheduled by [ShiftManager.turnOn] and
 * cancelled when the shift ends, so nothing runs, and nothing is sent, off shift.
 */
class ShiftHousekeepingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!ShiftManager.isOn(ctx)) {
            cancel(ctx)
            return Result.success()
        }
        ShiftEventReporter.tickNow(ctx)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "ShiftHousekeepingWorker"

        /** 15 minutes is WorkManager's floor for periodic work. KEEP so repeat calls do not reset it. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ShiftHousekeepingWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
