package com.company.callmonitor.location

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.company.callmonitor.EnrollmentPrefs
import com.company.callmonitor.Logger
import com.company.callmonitor.ShiftEventReporter
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

        // Shift housekeeping first, and regardless of whether tracking is wanted:
        // schedule end, queued shift events, heartbeat. Blocking on purpose — the
        // system may stop this worker the moment it returns.
        ShiftEventReporter.tickNow(ctx)

        if (!LocationDutyController.isTrackingDesired(ctx)) return Result.success()

        val status = LocationEnvironment.trackingStatus(ctx)

        // Report first, unconditionally. This worker is the only thing still
        // running when the service is dead, so it is the one chance the fleet
        // map has to learn that a device stopped recording — previously it
        // returned success in silence and the dashboard kept showing the stale
        // "TRACKING" written when the session started days earlier.
        reportHeartbeat(ctx, status)

        // Permission or the master switch: on a managed handset we can simply put
        // it back, which turns this from a 15-minute-stale warning into a repair.
        if (LocationEnvironment.isBlocked(ctx)) {
            LocationPolicyManager.apply(ctx)
            if (!LocationEnvironment.isBlocked(ctx)) {
                Logger.log(ctx, "📍 watchdog: device policy restored location — resuming")
                LocationGate.onLocationOn(ctx)
                LocationHealthNotifier.clear(ctx)
                LocationDutyController.startTracking(ctx, "watchdog_policy_repair")
                return Result.success()
            }

            // Unmanaged phone: the outage stands. Open it here as well as in the
            // broadcast receiver — a process killed while Location was off never
            // gets that broadcast, and the outage must still be on the clock.
            LocationGate.onLocationOff(ctx)
            LocationHealthNotifier.update(ctx)
            Logger.log(
                ctx,
                "📍 watchdog: tracking blocked ($status) for " +
                    LocationGate.formatDuration(LocationGate.currentOutageMillis(ctx))
            )
            return Result.success()
        }
        LocationGate.onLocationOn(ctx)
        LocationHealthNotifier.clear(ctx)

        // Healthy service: nothing to do beyond draining anything still queued.
        if (LocationTrackingService.isHealthy()) {
            LocationUploadWorker.enqueue(ctx)
            return Result.success()
        }

        Logger.log(ctx, "📍 watchdog: tracking not healthy ($status) — restarting")
        if (!LocationTrackingService.isRunning) {
            // Nothing is recording and nothing told us why: the app was closed or
            // the system stopped the service. Say exactly that, no more.
            ShiftEventReporter.interruptTracking(
                ctx,
                cause = ShiftEventReporter.CAUSE_TRACKING_SERVICE_STOPPED,
                reason = "The tracking service was not running (the app may have been closed or stopped by the system).",
                evidence = "Found not running by the periodic check while the shift was on."
            )
        }
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
