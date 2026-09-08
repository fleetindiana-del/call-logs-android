package com.company.callmonitor.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.company.callmonitor.EnrollmentPrefs

/**
 * The single source of truth for "can this phone actually produce a fix right
 * now, and is it doing so".
 *
 * Everything the device told the server about itself used to be a literal:
 * `registerDevice(trackingStatus = "TRACKING", gpsEnabled = true)`, written once
 * when a session started and never revised. So a phone whose location switch was
 * off, or whose provider had gone silent, still showed on the fleet map as a
 * healthy tracking device — the dashboard could not distinguish "parked" from
 * "recording nothing for the last 26 hours". Every status the device reports now
 * comes from here instead.
 */
object LocationEnvironment {

    /** Recording, and fixes are arriving. */
    const val STATUS_TRACKING = "TRACKING"
    /** Service is up but the provider has gone quiet — the interesting failure. */
    const val STATUS_NO_FIX = "NO_FIX"
    /** The phone's master location switch is off. */
    const val STATUS_GPS_OFF = "GPS_OFF"
    /** Location permission was never granted or has been revoked. */
    const val STATUS_PERMISSION_DENIED = "PERMISSION_DENIED"
    /** Background ("Allow all the time") missing — tracking dies off-screen. */
    const val STATUS_BACKGROUND_DENIED = "BACKGROUND_DENIED"
    /** Route ended from the website; not a fault. */
    const val STATUS_IDLE = "IDLE"

    /**
     * A tracking service that has produced nothing for this long is not
     * "in a tunnel", it is broken. Matches the service's own staleness bound.
     */
    private const val NO_FIX_AFTER_MS = 15 * 60_000L

    fun isLocationEnabled(context: Context): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (e: Exception) {
            // A phone that will not answer the question cannot be assumed healthy.
            false
        }
    }

    fun hasForegroundPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasBackgroundPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /** "BACKGROUND" / "FOREGROUND" / "DENIED" — what the dashboard shows. */
    fun permissionStatus(context: Context): String = when {
        !hasForegroundPermission(context) -> "DENIED"
        !hasBackgroundPermission(context) -> "FOREGROUND"
        else -> "BACKGROUND"
    }

    /**
     * Why tracking is (or is not) producing points, most blocking cause first.
     * Deliberately reports the fault even while the service is nominally alive:
     * a live service with a dead subscription is exactly the state that used to
     * be invisible on both ends.
     */
    fun trackingStatus(context: Context): String {
        if (!hasForegroundPermission(context)) return STATUS_PERMISSION_DENIED
        if (!isLocationEnabled(context)) return STATUS_GPS_OFF
        if (!LocationDutyController.isTrackingDesired(context)) return STATUS_IDLE
        if (!hasBackgroundPermission(context)) return STATUS_BACKGROUND_DENIED
        if (!LocationTrackingService.isRunning) return STATUS_NO_FIX

        val silence = LocationTrackingService.millisSinceLastFix() ?: return STATUS_NO_FIX
        return if (silence > NO_FIX_AFTER_MS) STATUS_NO_FIX else STATUS_TRACKING
    }

    /** True when duty is on but the phone is stopping us from recording. */
    fun isBlocked(context: Context): Boolean {
        if (!EnrollmentPrefs.hasLocationTracking(context)) return false
        if (!LocationDutyController.isTrackingDesired(context)) return false
        return !hasForegroundPermission(context) || !isLocationEnabled(context)
    }

    /** Short, driver-readable reason for the blocked notification. */
    fun blockedReason(context: Context): String? = when {
        !EnrollmentPrefs.hasLocationTracking(context) -> null
        !LocationDutyController.isTrackingDesired(context) -> null
        !hasForegroundPermission(context) ->
            "Location permission is off for this app. Tap to set it to \"Allow all the time\"."
        !isLocationEnabled(context) ->
            "Your phone's Location is switched off, so your route is not being recorded. Tap to turn it back on."
        else -> null
    }
}
