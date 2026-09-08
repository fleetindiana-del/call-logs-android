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

        val status = LocationEnvironment.trackingStatus(ctx)

        // Report first, unconditionally. This worker is the only thing still
        // running when the service is dead, so it is the one chance the fleet
        // map has to learn that a device stopped recording — previously it
        // returned success in silence and the dashboard kept showing the stale
        // "TRACKING" written when the session started days earlier.
        reportHeartbeat(ctx, status)

        // Permission or the master switch: nothing to restart until the driver
        // acts, so make sure they are being asked.
        if (LocationEnvironment.isBlocked(ctx)) {
            LocationHealthNotifier.update(ctx)
            Logger.log(ctx, "📍 watchdog: tracking blocked ($status)")
            return Result.success()
        }
        LocationHealthNotifier.clear(ctx)

        // Healthy service: nothing to do beyond draining anything still queued.
        if (LocationTrackingService.isHealthy()) {
            LocationUploadWorker.enqueue(ctx)
            return Result.success()
        }

        Logger.log(ctx, "📍 watchdog: tracking not healthy ($status) — restarting")
        if (LocationTrackingService.isRunning) {
            // Alive but not producing: hand it a clean service rather than
            // another ACTION_START the broken instance would absorb.
            LocationDutyController.requestRestart(ctx, "watchdog_restart")
        } else {
            LocationDutyController.startTracking(ctx, "watchdog")
        }
        return Result.success()
    }

    private fun reportHeartbeat(ctx: Context, status: String) {
        try {
            val pending = FleetLocationDatabase.getInstance(ctx)
                .locationPointDao().countByState("PENDING")
            LocationApiClient.heartbeat(
                ctx,
                trackingStatus = status,
                gpsEnabled = LocationEnvironment.isLocationEnabled(ctx),
                pendingPoints = pending,
                permissionStatus = LocationEnvironment.permissionStatus(ctx)
            )
        } catch (e: Exception) {
            Logger.log(ctx, "📍 watchdog heartbeat failed: ${e.message}")
        }
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
