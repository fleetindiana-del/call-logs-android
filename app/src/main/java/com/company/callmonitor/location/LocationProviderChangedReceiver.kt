package com.company.callmonitor.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.company.callmonitor.EnrollmentPrefs
import com.company.callmonitor.Logger

/**
 * Reacts to the phone's master Location switch.
 *
 * The gap this closes: when a driver turned Location off, tracking stopped and
 * nothing on the device noticed. Turning it back on did not resume recording
 * either — the service was still nominally "running", so every restart path
 * treated it as healthy and skipped it. Recording only resumed when the process
 * happened to be killed and revived by a push from the dashboard, which is why
 * routes appeared to start from the moment someone intervened rather than from
 * the start of the shift.
 */
class LocationProviderChangedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocationManager.PROVIDERS_CHANGED_ACTION) return

        val appCtx = context.applicationContext
        if (!EnrollmentPrefs.hasLocationTracking(appCtx)) return
        if (!LocationDutyController.isTrackingDesired(appCtx)) return

        val enabled = LocationEnvironment.isLocationEnabled(appCtx)

        // This broadcast fires several times per toggle (once per provider), so
        // act on the transition rather than on every delivery.
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_LAST_ENABLED) && prefs.getBoolean(KEY_LAST_ENABLED, true) == enabled) {
            return
        }
        prefs.edit().putBoolean(KEY_LAST_ENABLED, enabled).apply()

        val pending = goAsync()
        Thread {
            try {
                if (enabled) {
                    Logger.log(appCtx, "📍 location switched on — resuming tracking")
                    LocationHealthNotifier.clear(appCtx)
                    LocationDutyController.startTracking(appCtx, "location_enabled")
                } else {
                    Logger.log(appCtx, "📍 location switched off — route recording paused")
                    LocationHealthNotifier.update(appCtx)
                    LocationDutyController.reportState(appCtx, LocationEnvironment.STATUS_GPS_OFF)
                }
            } catch (e: Exception) {
                Logger.log(appCtx, "📍 providers-changed handling failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val PREFS = "location_prefs"
        const val KEY_LAST_ENABLED = "lastProvidersEnabled"
    }
}
