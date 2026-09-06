package com.company.callmonitor.location

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.company.callmonitor.EnrollmentPrefs
import com.company.callmonitor.Logger

/**
 * Starts/stops GPS duty from FCM, command poll, boot, or home — not from a driver button.
 * Website sends [location_start_tracking] / [location_stop_tracking].
 *
 * [trackingDesired] defaults true (always active). Website End clears it so boot/home
 * do not immediately restart until website Start (or driver opens after Start FCM).
 */
object LocationDutyController {

    const val ACTION_START_TRACKING = "location_start_tracking"
    const val ACTION_STOP_TRACKING = "location_stop_tracking"

    private const val PREFS = "location_prefs"
    private const val KEY_DESIRED = "trackingDesired"

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isTrackingDesired(context: Context): Boolean {
        // Default true = always-on until website End Route
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DESIRED, true)
    }

    fun setTrackingDesired(context: Context, desired: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DESIRED, desired).apply()
    }

    /**
     * Ensure an ACTIVE session + foreground [LocationTrackingService].
     * No-op if already running with an active session id.
     */
    @JvmStatic
    fun startTracking(context: Context, reason: String = "manual"): Boolean {
        if (!EnrollmentPrefs.hasLocationTracking(context)) {
            Logger.log(context, "📍 skip start ($reason): location capability off")
            return false
        }
        if (!hasLocationPermission(context)) {
            Logger.log(context, "📍 skip start ($reason): location permission missing")
            return false
        }

        setTrackingDesired(context, true)

        val appCtx = context.applicationContext
        // Armed on every attempt, including the "already active" one below, so a
        // device that is currently fine still has a keep-alive for when it is not.
        LocationWatchdogWorker.schedule(appCtx)
        com.company.callmonitor.MonitoringAlarmScheduler.ensureAlarmChain(appCtx)
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var sessionId = prefs.getString("activeSessionId", null)

        // Only skip the restart when the service is actually producing fixes.
        // Checking isRunning alone meant a service the system had restarted with
        // a null intent — alive, subscribed to nothing — permanently swallowed
        // every attempt to revive tracking. Re-sending ACTION_START to a healthy
        // service is harmless (it keeps its session and anchor), so when in
        // doubt, start.
        if (LocationTrackingService.isHealthy() && !sessionId.isNullOrBlank()) {
            Logger.log(appCtx, "📍 tracking already active ($reason) session=$sessionId")
            return true
        }
        if (LocationTrackingService.isRunning && !sessionId.isNullOrBlank()) {
            Logger.log(appCtx, "📍 tracking service stalled — restarting ($reason) session=$sessionId")
        }

        if (sessionId.isNullOrBlank()) {
            sessionId = "session_${System.currentTimeMillis()}"
            val deviceId = EnrollmentPrefs.getDeviceId(appCtx)
            val employeeId = EnrollmentPrefs.getEmployeeId(appCtx)
            val vehicleId = EnrollmentPrefs.getVehicleId(appCtx)
            val startedAt = System.currentTimeMillis()

            val db = FleetLocationDatabase.getInstance(appCtx)
            db.locationSessionDao().insert(
                LocationSessionEntity(
                    sessionId = sessionId,
                    employeeId = employeeId,
                    deviceId = deviceId,
                    vehicleId = vehicleId.ifBlank { null },
                    startTime = startedAt,
                    endTime = null,
                    status = "ACTIVE",
                    uploadedAt = null
                )
            )
            prefs.edit().putString("activeSessionId", sessionId).apply()

            try {
                LocationApiClient.registerDevice(appCtx, trackingStatus = "TRACKING", gpsEnabled = true)
                LocationApiClient.upsertSession(
                    appCtx,
                    sessionId = sessionId,
                    status = "ACTIVE",
                    startedAtMs = startedAt
                )
            } catch (e: Exception) {
                Logger.log(appCtx, "📍 register/upsert on start ($reason): ${e.message}")
            }
        }

        val intent = Intent(appCtx, LocationTrackingService::class.java).apply {
            action = LocationTrackingService.ACTION_START
            putExtra("sessionId", sessionId)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
            Logger.log(appCtx, "📍 tracking started ($reason) session=$sessionId")
            LocationUploadWorker.enqueue(appCtx)
            return true
        } catch (e: Exception) {
            Logger.log(appCtx, "📍 failed to start tracking ($reason): ${e.message}")
            return false
        }
    }

    /**
     * Stop the foreground service and mark the active session COMPLETED.
     * Clears [trackingDesired] so boot/home will not auto-restart until Start.
     */
    @JvmStatic
    fun stopTracking(context: Context, reason: String = "manual"): Boolean {
        val appCtx = context.applicationContext
        setTrackingDesired(appCtx, false)

        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sessionId = prefs.getString("activeSessionId", null)

        try {
            val intent = Intent(appCtx, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_STOP
            }
            appCtx.startService(intent)
        } catch (e: Exception) {
            Logger.log(appCtx, "📍 stop service ($reason): ${e.message}")
        }

        if (sessionId != null) {
            val endedAt = System.currentTimeMillis()
            try {
                val db = FleetLocationDatabase.getInstance(appCtx)
                db.locationSessionDao().markCompleted(sessionId, endedAt)
            } catch (e: Exception) {
                Logger.log(appCtx, "📍 markCompleted ($reason): ${e.message}")
            }
            prefs.edit().remove("activeSessionId").apply()
            try {
                LocationApiClient.upsertSession(
                    appCtx,
                    sessionId = sessionId,
                    status = "COMPLETED",
                    stoppedAtMs = endedAt
                )
                LocationApiClient.registerDevice(appCtx, trackingStatus = "IDLE", gpsEnabled = true)
            } catch (e: Exception) {
                Logger.log(appCtx, "📍 upsert/register on stop ($reason): ${e.message}")
            }
            LocationUploadWorker.enqueue(appCtx)
        }

        prefs.edit().putBoolean("liveMode", false).apply()
        LocationWatchdogWorker.cancel(appCtx)
        Logger.log(appCtx, "📍 tracking stopped ($reason) session=${sessionId ?: "none"}")
        return true
    }

    /** Resume after reboot / home open only when website has not ended the route. */
    @JvmStatic
    fun ensureAlwaysActive(context: Context, reason: String = "ensure"): Boolean {
        if (!EnrollmentPrefs.hasLocationTracking(context)) return false
        if (!isTrackingDesired(context)) {
            Logger.log(context, "📍 skip ensure ($reason): website ended route")
            return false
        }
        return startTracking(context, reason)
    }
}
