package com.company.callmonitor.location

import android.app.Activity
import android.content.IntentSender
import com.company.callmonitor.Logger
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/**
 * Asks the system to turn Location back on, in place, with a single tap.
 *
 * This is the strongest guarantee Android allows: an app cannot flip the master
 * switch itself, but Play services will show its own "Turn on location?" dialog
 * and do it for the driver without ever leaving the app — no settings hunt, no
 * chance of ending up on the wrong screen.
 */
object LocationEnableRequest {

    const val REQUEST_CODE = 2101

    /** No-op when location is already usable, so it is safe to call on resume. */
    fun promptIfNeeded(activity: Activity) {
        if (!LocationEnvironment.hasForegroundPermission(activity)) return
        if (LocationEnvironment.isLocationEnabled(activity)) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L).build()
        val settings = LocationSettingsRequest.Builder()
            .addLocationRequest(request)
            .setAlwaysShow(true)
            .build()

        LocationServices.getSettingsClient(activity)
            .checkLocationSettings(settings)
            .addOnFailureListener { e ->
                if (e !is ResolvableApiException) {
                    Logger.log(activity, "📍 location settings not resolvable: ${e.message}")
                    return@addOnFailureListener
                }
                try {
                    e.startResolutionForResult(activity, REQUEST_CODE)
                } catch (sendEx: IntentSender.SendIntentException) {
                    Logger.log(activity, "📍 could not show location dialog: ${sendEx.message}")
                }
            }
    }
}
