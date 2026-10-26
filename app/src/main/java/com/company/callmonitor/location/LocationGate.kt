package com.company.callmonitor.location

import android.content.Context
import com.company.callmonitor.ApiClient
import com.company.callmonitor.EnrollmentPrefs
import com.company.callmonitor.Logger
import com.company.callmonitor.PendingStatusManager
import com.company.callmonitor.ShiftEventReporter
import com.company.callmonitor.ToggleLogEntry

/**
 * What we do about a driver switching Location off on a phone we cannot lock.
 *
 * [LocationPolicyManager] prevents it outright on company-owned handsets. On a
 * driver's personal phone Android permits no such lock at any API level, so the
 * lever we have left is not prevention but consequence: make the outage recorded,
 * attributable, timed, and inconvenient. Specifically —
 *
 *  - Every off/on transition is reported to the server as `LOCATION_DISABLED` /
 *    `LOCATION_RESTORED`, reusing the same status endpoint and offline queue as
 *    the existing ADMIN_DISABLED alert, so an outage that happens with no signal
 *    still arrives once the phone reconnects. A driver cannot make the report
 *    vanish by also turning data off.
 *  - The **duration** of each outage is accumulated, not just the current state.
 *    "Location is off right now" was never the useful question; "this phone
 *    recorded nothing for 40 minutes of a shift" is.
 *  - While tracking is owed and Location is off, the driver's own reasons for
 *    opening the app — expenses and route view — are withheld. This
 *    is the strongest honest lever available without Device Owner: it makes the
 *    outage cost the driver something immediately rather than at payroll.
 */
object LocationGate {

    private const val PREFS = "location_prefs"
    private const val KEY_OFF_SINCE = "gateOffSince"
    private const val KEY_TOTAL_OFF = "gateTotalOffMs"
    private const val KEY_OUTAGE_COUNT = "gateOutageCount"

    /**
     * True when tracking is owed but the phone is refusing to produce it — the
     * state in which driver features are withheld.
     *
     * Anchored on [LocationEnvironment.isBlocked] so it agrees with the sticky
     * notification and the dashboard status rather than being a fourth opinion.
     * Deliberately does not fire for [LocationEnvironment.STATUS_NO_FIX]: a tunnel
     * or an underground car park is not misconduct, and punishing it would teach
     * drivers the warnings are noise.
     */
    fun isBlocking(context: Context): Boolean = LocationEnvironment.isBlocked(context)

    /** Driver-facing reason, or null when nothing is being withheld. */
    fun blockReason(context: Context): String? {
        if (!isBlocking(context)) return null
        return if (!LocationEnvironment.hasForegroundPermission(context)) {
            "Location permission is off for this app. Turn it back on to use expenses and route history."
        } else {
            "Your phone's Location is switched off. Turn it back on to use expenses and route history."
        }
    }

    /** Wall-clock length of the outage in progress, or 0 when none is. */
    fun currentOutageMillis(context: Context): Long {
        val since = prefs(context).getLong(KEY_OFF_SINCE, 0L)
        if (since <= 0L) return 0L
        return (System.currentTimeMillis() - since).coerceAtLeast(0L)
    }

    /** Total time this device has spent with Location off while on duty. */
    fun totalOffMillis(context: Context): Long =
        prefs(context).getLong(KEY_TOTAL_OFF, 0L) + currentOutageMillis(context)

    fun outageCount(context: Context): Int = prefs(context).getInt(KEY_OUTAGE_COUNT, 0)

    /**
     * Record and escalate the moment Location goes off. Safe to call more than
     * once for the same outage — the second call is ignored, so the several
     * PROVIDERS_CHANGED broadcasts one toggle produces cannot inflate the count.
     *
     * Does network I/O; call off the main thread.
     */
    fun onLocationOff(context: Context) {
        val appCtx = context.applicationContext
        val p = prefs(appCtx)
        if (p.getLong(KEY_OFF_SINCE, 0L) > 0L) return

        val now = System.currentTimeMillis()
        p.edit()
            .putLong(KEY_OFF_SINCE, now)
            .putInt(KEY_OUTAGE_COUNT, p.getInt(KEY_OUTAGE_COUNT, 0) + 1)
            .apply()

        // The two causes need different words: a revoked permission and a flipped
        // master switch look identical on the dashboard otherwise, and they call
        // for different conversations with the driver.
        // Worded as what was observed, not as anyone's decision: from the phone we
        // can see that permission or the Location switch is off, not who turned it
        // off or why (a driver, a policy change, a system update).
        val cause = if (!LocationEnvironment.hasForegroundPermission(appCtx)) {
            "Location permission for this app was turned off while route tracking was active."
        } else {
            "The phone's Location (GPS) was switched off while route tracking was active."
        }

        Logger.log(appCtx, "🚨 Location unavailable — reporting to server ($cause)")
        report(appCtx, status = "LOCATION_DISABLED", timestamp = now, reason = cause)

        // The same outage, on the shift's own audit trail. The cause is the one we
        // can actually observe (permission vs. the phone's Location switch) — it is
        // never worded as the driver's choice, because from here we cannot tell a
        // deliberate tap from a policy or system change.
        ShiftEventReporter.interruptTracking(
            appCtx,
            cause = ShiftEventReporter.blockedCause(appCtx),
            reason = cause,
            at = now
        )
    }

    /**
     * Close the outage, banking its duration so the shift total survives the
     * process being killed between outages.
     *
     * Does network I/O; call off the main thread.
     */
    fun onLocationOn(context: Context) {
        val appCtx = context.applicationContext
        val p = prefs(appCtx)
        val since = p.getLong(KEY_OFF_SINCE, 0L)
        if (since <= 0L) return

        val now = System.currentTimeMillis()
        val outage = (now - since).coerceAtLeast(0L)
        p.edit()
            .putLong(KEY_OFF_SINCE, 0L)
            .putLong(KEY_TOTAL_OFF, p.getLong(KEY_TOTAL_OFF, 0L) + outage)
            .apply()

        val minutes = outage / 60_000
        Logger.log(appCtx, "📍 Location restored after ${formatDuration(outage)}")
        report(
            appCtx,
            status = "LOCATION_RESTORED",
            timestamp = now,
            reason = "Location switched back on after ${formatDuration(outage)} " +
                "($minutes min of route not recorded)."
        )
        ShiftEventReporter.resumeTracking(
            appCtx,
            reason = "Location was available again after ${formatDuration(outage)}."
        )
    }

    /**
     * A new shift starts with a clean outage record. Without this, an outage left
     * open from before the shift (Location switched off while off duty) would make
     * [onLocationOff] return early and the new shift would never record the failure
     * it actually starts with.
     */
    fun beginShift(context: Context) {
        prefs(context).edit()
            .putLong(KEY_OFF_SINCE, 0L)
            .putLong(KEY_TOTAL_OFF, 0L)
            .putInt(KEY_OUTAGE_COUNT, 0)
            .apply()
    }

    /**
     * Reconcile at startup. A phone killed mid-outage and revived with Location
     * already back on would otherwise carry [KEY_OFF_SINCE] forever, reporting a
     * days-long outage the next time one genuinely ended.
     *
     * Does network I/O; call off the main thread.
     */
    fun reconcile(context: Context) {
        val appCtx = context.applicationContext
        if (!EnrollmentPrefs.hasLocationTracking(appCtx)) return

        val open = prefs(appCtx).getLong(KEY_OFF_SINCE, 0L) > 0L
        val enabled = LocationEnvironment.isLocationEnabled(appCtx)
        if (open && enabled) {
            onLocationOn(appCtx)
        } else if (!open && !enabled && LocationDutyController.isTrackingDesired(appCtx)) {
            onLocationOff(appCtx)
        }
    }

    /** Shift-total reset, for the start of a new duty session. */
    fun resetTotals(context: Context) {
        prefs(context).edit()
            .putLong(KEY_TOTAL_OFF, 0L)
            .putInt(KEY_OUTAGE_COUNT, 0)
            .apply()
    }

    fun formatDuration(millis: Long): String {
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            totalMinutes < 1 -> "under a minute"
            hours == 0L -> "$minutes min"
            else -> "${hours}h ${minutes}m"
        }
    }

    /**
     * Reuses the call-monitoring status endpoint and its offline queue rather than
     * introducing a second alerting path — ADMIN_DISABLED already proved this one
     * survives a device with no connectivity, which is the case that matters most
     * here.
     */
    private fun report(context: Context, status: String, timestamp: Long, reason: String) {
        val deviceId = EnrollmentPrefs.getDeviceId(context)
        val employeeName = EnrollmentPrefs.getEmployeeName(context)
        val serverUrl = EnrollmentPrefs.getServerUrl(context)
        val apiKey = EnrollmentPrefs.getApiKey(context)

        val delivered = try {
            ApiClient.sendStatus(
                serverUrl, apiKey, deviceId, employeeName,
                status, timestamp, context, reason
            )
        } catch (e: Exception) {
            Logger.log(context, "📍 $status report failed: ${e.message}")
            false
        }

        if (!delivered) {
            PendingStatusManager.savePendingStatus(
                context,
                ToggleLogEntry(deviceId, employeeName, status, timestamp, reason)
            )
        }
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
