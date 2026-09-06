package com.company.callmonitor.location

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.company.callmonitor.EnrollmentPrefs
import com.company.callmonitor.Logger
import java.util.concurrent.TimeUnit

/**
 * Periodic keep-alive for GPS duty — the location counterpart of
 * `ServiceAliveWorker`, which only ever revived the call-monitoring service.
 *
 * Without it the only things that could restart tracking were a reboot and the
 * driver opening the app, so on a phone whose OEM battery manager kills the
 * process mid-shift (common on Vivo/Oppo/Xiaomi/Realme) the route simply ended
 * where the app was killed and never resumed.
 */
class LocationWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        if (!EnrollmentPrefs.hasLocationTracking(ctx)) return Result.success()
        if (!LocationDutyController.isTrackingDesired(ctx)) return Result.success()
        if (!LocationDutyController.hasLocationPermission(ctx)) return Result.success()

        // Healthy service: nothing to do beyond draining anything still queued.
        if (LocationTrackingService.isHealthy()) {
            LocationUploadWorker.enqueue(ctx)
            return Result.success()
        }

        Logger.log(ctx, "📍 watchdog: tracking not healthy — restarting")
        LocationDutyController.startTracking(ctx, "watchdog")
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "LocationWatchdogWorker"

        /**
         * 15 minutes is WorkManager's floor for periodic work; the in-service
         * watchdog covers the shorter outages, this one covers a killed process.
         * KEEP so repeated calls from boot / cold start / start-tracking do not
         * reset the schedule.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LocationWatchdogWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
