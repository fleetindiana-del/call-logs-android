package com.company.callmonitor

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * The user's own on/off switch for their working shift, and the single gate every
 * collector asks before it records anything.
 *
 * Shift ON  → location history, live location and call logs are collected.
 * Shift OFF → nothing new is collected. Already-captured data still finishes
 *             uploading; that is delivery of data collected on shift, not new
 *             collection.
 *
 * Both transitions are recorded twice over: locally in the device log, and on the
 * server as a [ToggleLog] event carrying the exact wall-clock time of the tap.
 * The server write is queued through [PendingStatusManager] when the phone is
 * offline, so an audit entry is never lost to a dead network.
 *
 * ## Scheduled shifts (driver devices)
 *
 * On a device with location tracking a shift is one of the two predefined shifts
 * ([ShiftSchedule]). Switching on opens that shift under a fresh shift id and
 * reports it through [ShiftEventReporter] — the detailed, auditable trail the
 * admin Shifts page reads. The phone then ends the shift itself at the scheduled
 * end plus a grace period ([enforceSchedule]), and also stops if the server closes
 * it ([endedByServer]), so the driver cannot keep appearing on shift, or keep
 * being tracked, after the shift is over.
 *
 * Employee devices without location tracking keep the plain switch: no schedule,
 * no shift events.
 *
 * ## Why windows rather than a single flag
 *
 * A flag alone cannot answer the question the call collectors actually ask, which
 * is not "is the shift on now" but "was the shift on at 14:32 yesterday". Call
 * logs are swept from the phone's own log *after the fact*
 * ([CallBackfillManager], [PeriodicSyncManager]), so a device that was asleep or
 * dead through a shift still has calls worth collecting when it wakes — while the
 * calls the user made after clocking off must never be swept up with them. So we
 * keep the recent shift windows and filter candidate calls by them.
 */
object ShiftManager {

    /** Audit statuses. Mirrored in the ToggleLog enum on both servers. */
    const val STATUS_ON = "SHIFT_ON"
    const val STATUS_OFF = "SHIFT_OFF"

    private const val PREFS = "shift_prefs"
    private const val KEY_ON = "shiftOn"
    private const val KEY_STARTED_AT = "shiftStartedAt"
    private const val KEY_ENDED_AT = "shiftEndedAt"
    private const val KEY_WINDOWS = "shiftWindows"
    private const val KEY_MIGRATED = "shiftMigratedFromAlwaysOn"
    private const val KEY_SHIFT_ID = "shiftId"
    private const val KEY_SHIFT_TYPE = "shiftType"
    private const val KEY_SCHED_START = "shiftScheduledStart"
    private const val KEY_SCHED_END = "shiftScheduledEnd"
    private const val KEY_END_NOTE = "shiftEndNote"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── State ────────────────────────────────────────────────────────────────

    /**
     * Defaults to false. A device that has never seen this feature, or whose
     * store was wiped, must not be recording: the shift is a consent boundary,
     * and failing open would collect data nobody agreed to.
     */
    fun isOn(context: Context): Boolean = prefs(context).getBoolean(KEY_ON, false)

    /** Wall-clock start of the current shift, or 0 when off. */
    fun startedAt(context: Context): Long = prefs(context).getLong(KEY_STARTED_AT, 0L)

    /** Wall-clock end of the most recent shift, or 0 when there has been none. */
    fun endedAt(context: Context): Long = prefs(context).getLong(KEY_ENDED_AT, 0L)

    /** The live gate for anything recording right now. */
    fun mayCollectNow(context: Context): Boolean = isOn(context)

    /** Scheduled, audited shifts apply to employees given the Shifts capability. */
    fun usesSchedule(context: Context): Boolean = EnrollmentPrefs.hasShifts(context)

    /** Id of the shift in progress, or null when off or on a device without scheduled shifts. */
    fun shiftId(context: Context): String? =
        if (isOn(context)) prefs(context).getString(KEY_SHIFT_ID, null)?.takeIf { it.isNotBlank() } else null

    fun shiftType(context: Context): String? =
        if (isOn(context)) prefs(context).getString(KEY_SHIFT_TYPE, null) else null

    /** Scheduled start of the shift in progress, or 0. */
    fun scheduledStart(context: Context): Long =
        if (isOn(context)) prefs(context).getLong(KEY_SCHED_START, 0L) else 0L

    /** Scheduled end of the shift in progress, or 0. */
    fun scheduledEnd(context: Context): Long =
        if (isOn(context)) prefs(context).getLong(KEY_SCHED_END, 0L) else 0L

    /** "Evening shift" for the shift in progress, or null. */
    fun shiftLabel(context: Context): String? {
        val type = shiftType(context) ?: return null
        return ShiftEventReporter.config(context).shifts.firstOrNull { it.type == type }?.label
    }

    /**
     * Why the last shift ended when the driver did not end it, in words for the
     * driver; empty when the driver ended it themselves.
     */
    fun lastEndNote(context: Context): String = prefs(context).getString(KEY_END_NOTE, "") ?: ""

    // ── Eligibility ──────────────────────────────────────────────────────────

    /**
     * The shifts a driver may switch on right now: from the early-start allowance
     * before the scheduled start until the scheduled end. Empty outside both.
     */
    fun availableShifts(context: Context, at: Long = System.currentTimeMillis()): List<ShiftSchedule.Window> =
        ShiftSchedule.eligibleAt(ShiftEventReporter.config(context), at)

    /** The next shift that will open, for the "opens at …" message when none is. */
    fun nextShiftOpening(context: Context, at: Long = System.currentTimeMillis()): ShiftSchedule.Window? {
        val cfg = ShiftEventReporter.config(context)
        val windows = ArrayList<ShiftSchedule.Window>()
        val tz = java.util.TimeZone.getTimeZone(cfg.timezone)
        val cal = java.util.Calendar.getInstance(tz).apply { timeInMillis = at }
        val y = cal.get(java.util.Calendar.YEAR)
        val m = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        for (offset in 0..1) for (def in cfg.shifts) windows.add(ShiftSchedule.windowOnDay(def, y, m, d + offset, tz))
        return windows.filter { it.startMs - cfg.rules.earlyStartMs > at }.minByOrNull { it.startMs }
    }

    /**
     * One-time carry-over for phones upgrading from the always-on build.
     *
     * Those devices are mid-shift by definition — they have been recording
     * continuously — and defaulting them to OFF would silently stop collection
     * on the whole fleet the moment the APK rolls out, with nobody aware until
     * the data went missing. So the first run after the upgrade opens a shift
     * and audits it with an explicit reason, leaving the user free to end it.
     *
     * Only devices that were actually collecting are carried over; a device with
     * monitoring off and no location capability starts from OFF like a new one.
     */
    fun ensureInitialised(context: Context) {
        val appCtx = context.applicationContext
        val p = prefs(appCtx)
        if (p.getBoolean(KEY_MIGRATED, false)) return
        p.edit().putBoolean(KEY_MIGRATED, true).apply()

        if (!EnrollmentPrefs.isEnrolled(appCtx)) return

        val wasMonitoring = appCtx
            .getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
            .getBoolean("monitoringEnabled", false)
        val wasTracking = EnrollmentPrefs.hasLocationTracking(appCtx)
        if (!wasMonitoring && !wasTracking) return

        turnOn(appCtx, "upgrade_carry_over")
    }

    // ── Transitions ──────────────────────────────────────────────────────────

    /**
     * Begin a shift. Idempotent: a second call while already on refreshes the
     * notification and leaves the original start time — and its audit entry —
     * untouched, so a re-open of the home screen cannot rewrite when the shift
     * began.
     *
     * On a scheduled (driver) device [shiftType] picks which of the two shifts is
     * being worked; when omitted the one currently open is used. Switching on
     * outside both windows is still recorded — it happened — but flagged by the
     * server as outside the schedule; the UI prevents it for ordinary use.
     *
     * Safe to call from the UI thread; the server write happens on its own
     * thread.
     */
    @JvmStatic
    fun turnOn(context: Context, reason: String = "user_toggle", shiftType: String? = null): Boolean {
        val appCtx = context.applicationContext
        if (isOn(appCtx)) {
            ShiftNotifier.showOn(appCtx, startedAt(appCtx))
            return true
        }

        val at = System.currentTimeMillis()
        val scheduled = usesSchedule(appCtx)

        var newShiftId: String? = null
        var chosenType: String? = null
        var window: ShiftSchedule.Window? = null
        if (scheduled) {
            val cfg = ShiftEventReporter.config(appCtx)
            chosenType = shiftType
                ?: ShiftSchedule.eligibleAt(cfg, at).firstOrNull()?.type
                ?: cfg.shifts.minByOrNull { Math.abs(ShiftSchedule.nearest(cfg, it.type, at).startMs - at) }!!.type
            window = ShiftSchedule.windowForStart(cfg, chosenType, at)
            // Letters, digits and dashes only, and within the server's length bounds.
            newShiftId = "sh-" + UUID.randomUUID().toString()
        }

        prefs(appCtx).edit()
            .putBoolean(KEY_ON, true)
            .putLong(KEY_STARTED_AT, at)
            .putString(KEY_SHIFT_ID, newShiftId)
            .putString(KEY_SHIFT_TYPE, chosenType)
            .putLong(KEY_SCHED_START, window?.startMs ?: 0L)
            .putLong(KEY_SCHED_END, window?.endMs ?: 0L)
            .putString(KEY_END_NOTE, "")
            .apply()
        openWindow(appCtx, at)

        Logger.log(
            appCtx,
            "🟢 Shift ON ($reason) at ${formatTime(at)}" + (chosenType?.let { " — $it" } ?: "")
        )
        ShiftNotifier.showOn(appCtx, at)
        audit(appCtx, STATUS_ON, at, reason)

        if (scheduled && newShiftId != null && chosenType != null) {
            ShiftEventReporter.beginShift(appCtx)
            com.company.callmonitor.location.LocationGate.beginShift(appCtx)
            val payload = JSONObject().put("shiftType", chosenType)
            if (reason != "user_toggle") {
                payload.put("reason", reasonText(reason))
            }
            ShiftEventReporter.record(appCtx, "SHIFT_STARTED", payload, at, newShiftId)
            ShiftHousekeepingWorker.schedule(appCtx)
        }

        // Location is the only collector that needs waking; the call path is
        // event-driven and simply stops refusing once the gate opens.
        if (EnrollmentPrefs.hasLocationTracking(appCtx)) {
            Thread {
                try {
                    val started = com.company.callmonitor.location.LocationDutyController
                        .startTracking(appCtx, "shift_on")
                    if (scheduled) ShiftEventReporter.afterTrackingStart(appCtx, started)
                } catch (e: Exception) {
                    Logger.log(appCtx, "📍 shift start could not begin tracking: ${e.message}")
                    if (scheduled) {
                        ShiftEventReporter.afterTrackingStart(appCtx, false)
                    }
                }
            }.start()
        }
        return true
    }

    /**
     * End a shift on the user's say-so and stop every collector. Idempotent in the
     * same way as [turnOn].
     */
    @JvmStatic
    fun turnOff(context: Context, reason: String = "user_toggle"): Boolean =
        end(context, reason, System.currentTimeMillis())

    /**
     * Close the shift in progress at [at].
     *
     * Events are queued *before* the state is cleared, under the shift's own id —
     * once the shift is off there is no "current shift" to attribute them to.
     */
    private fun end(context: Context, reason: String, at: Long): Boolean {
        val appCtx = context.applicationContext
        if (!isOn(appCtx)) {
            ShiftNotifier.clear(appCtx)
            return true
        }

        val startedAt = startedAt(appCtx)
        val endedAt = maxOf(at, startedAt)
        val endingShiftId = shiftId(appCtx)
        val scheduled = usesSchedule(appCtx)
        val byScheduleEnd = reason == REASON_SCHEDULE_ELAPSED

        if (scheduled && endingShiftId != null) {
            val stopPayload = ShiftEventReporter.withLocation(
                appCtx,
                JSONObject().put("intentional", true).put(
                    "reason",
                    if (byScheduleEnd) "Tracking stopped by the app when the scheduled shift ended."
                    else "Driver switched the shift off."
                )
            )
            ShiftEventReporter.record(appCtx, "TRACKING_STOPPED", stopPayload, endedAt, endingShiftId)
            ShiftEventReporter.record(
                appCtx, "SHIFT_ENDED",
                ShiftEventReporter.withLocation(
                    appCtx,
                    JSONObject().put("endReason", if (byScheduleEnd) "SCHEDULE_ELAPSED" else "DRIVER_ENDED")
                ),
                endedAt, endingShiftId
            )
        }

        prefs(appCtx).edit()
            .putBoolean(KEY_ON, false)
            .putLong(KEY_ENDED_AT, endedAt)
            .putString(
                KEY_END_NOTE,
                if (byScheduleEnd) "Your shift ended automatically at its scheduled end time." else ""
            )
            .apply()
        closeWindow(appCtx, endedAt)

        Logger.log(
            appCtx,
            "🔴 Shift OFF ($reason) at ${formatTime(endedAt)} — worked ${formatDuration(endedAt - startedAt)}"
        )
        ShiftNotifier.showOff(appCtx, startedAt, endedAt)
        audit(appCtx, STATUS_OFF, endedAt, reason)

        if (scheduled) {
            ShiftEventReporter.endShift(appCtx)
            ShiftHousekeepingWorker.cancel(appCtx)
        }
        stopCollectors(appCtx, "shift_off")
        return true
    }

    /**
     * The server says this shift is over — the schedule ended or tracking was lost
     * for too long. Mirror that on the phone so the two never disagree about
     * whether the driver is on shift, and tracking does not carry on regardless.
     *
     * Deliberately emits no SHIFT_ENDED of its own: the server already recorded
     * the end, with the reason it actually had.
     *
     * @param actualEnd when the server counts the shift as having ended.
     */
    fun endedByServer(context: Context, endReason: String?, actualEnd: Long?): Boolean {
        val appCtx = context.applicationContext
        if (!isOn(appCtx)) return false

        val startedAt = startedAt(appCtx)
        val now = System.currentTimeMillis()
        val endedAt = (actualEnd ?: now).coerceIn(startedAt, maxOf(startedAt, now))

        prefs(appCtx).edit()
            .putBoolean(KEY_ON, false)
            .putLong(KEY_ENDED_AT, endedAt)
            .putString(KEY_END_NOTE, endNoteFor(endReason))
            .apply()
        closeWindow(appCtx, endedAt)

        Logger.log(
            appCtx,
            "🔴 Shift OFF (server closed: ${endReason ?: "unspecified"}) at ${formatTime(endedAt)}"
        )
        ShiftNotifier.showOff(appCtx, startedAt, endedAt)
        audit(appCtx, STATUS_OFF, endedAt, "server_closed:${endReason ?: "unspecified"}")

        ShiftEventReporter.endShift(appCtx)
        ShiftHousekeepingWorker.cancel(appCtx)
        stopCollectors(appCtx, "shift_closed_by_server")
        return true
    }

    /**
     * End the shift if its scheduled end plus the grace period has passed. Called
     * on every housekeeping tick, from the home screen and on boot, so a phone that
     * was asleep or dead through the end of the shift closes it the moment it wakes
     * — stamped with the time it *should* have ended, not the time it woke.
     */
    fun enforceSchedule(context: Context): Boolean {
        val appCtx = context.applicationContext
        if (!isOn(appCtx) || !usesSchedule(appCtx)) return false
        val scheduledEnd = scheduledEnd(appCtx)
        if (scheduledEnd <= 0L) return false

        val hardEnd = scheduledEnd + ShiftEventReporter.config(appCtx).rules.endGraceMs
        val now = System.currentTimeMillis()
        if (now < hardEnd) return false

        return end(appCtx, REASON_SCHEDULE_ELAPSED, maxOf(hardEnd, startedAt(appCtx)))
    }

    private fun stopCollectors(appCtx: Context, why: String) {
        // Stops the foreground service, completes the location session and
        // clears trackingDesired, so boot, watchdog and the home screen will not
        // quietly restart recording while the shift is off.
        Thread {
            try {
                com.company.callmonitor.location.LocationDutyController.stopTracking(appCtx, why)
            } catch (e: Exception) {
                Logger.log(appCtx, "📍 shift end could not stop tracking: ${e.message}")
            }
        }.start()
    }

    private fun reasonText(reason: String): String = when (reason) {
        "upgrade_carry_over" ->
            "Opened automatically when the app was updated, because the phone was already recording."
        else -> reason
    }

    private fun endNoteFor(endReason: String?): String = when (endReason) {
        "SCHEDULE_ELAPSED" -> "Your shift ended automatically at its scheduled end time."
        "TRACKING_LOST_TIMEOUT" ->
            "Your shift was ended automatically because location tracking was not working for over an hour."
        "SUPERSEDED_BY_NEW_SHIFT" -> "Your shift was closed because a new shift was started on this phone."
        else -> "Your shift was ended by the system."
    }

    // ── Shift windows ────────────────────────────────────────────────────────

    /**
     * True when [timestampMs] falls inside a recorded shift. The gate for
     * anything sweeping the phone's call log after the fact.
     */
    fun wasOnShift(context: Context, timestampMs: Long): Boolean =
        ShiftWindows.contains(read(context), timestampMs)

    /**
     * The earliest moment worth reading the call log from — the start of the
     * oldest retained shift window, or [Long.MAX_VALUE] when there is no shift
     * history at all.
     */
    fun earliestWindowStart(context: Context): Long =
        ShiftWindows.earliestStart(read(context))

    private fun read(context: Context): String =
        prefs(context).getString(KEY_WINDOWS, ShiftWindows.EMPTY) ?: ShiftWindows.EMPTY

    private fun openWindow(context: Context, at: Long) =
        write(context, ShiftWindows.open(read(context), at))

    private fun closeWindow(context: Context, at: Long) =
        write(context, ShiftWindows.close(read(context), at))

    private fun write(context: Context, json: String) {
        prefs(context).edit().putString(KEY_WINDOWS, json).apply()
    }

    // ── Audit ────────────────────────────────────────────────────────────────

    /**
     * Records the transition on the server, queueing it for retry when the send
     * fails. [CallMonitorService] flushes that queue every five minutes.
     */
    private fun audit(context: Context, status: String, at: Long, reason: String) {
        val appCtx = context.applicationContext
        val config = appCtx.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        val serverUrl = config.getString("serverUrl", "") ?: ""
        val apiKey = config.getString("apiKey", "") ?: ""
        val deviceId = config.getString("deviceId", "") ?: ""
        val employeeName = config.getString("employeeName", "") ?: ""

        if (serverUrl.isBlank() || deviceId.isBlank()) {
            Logger.log(appCtx, "⚠️ Shift $status not audited — device not configured yet")
            return
        }

        val entry = ToggleLogEntry(deviceId, employeeName, status, at, reason)
        Thread {
            try {
                val sent = ApiClient.sendStatus(
                    serverUrl, apiKey, deviceId, employeeName, status, at, appCtx, reason
                )
                if (sent) {
                    Logger.log(appCtx, "✅ Shift $status audit recorded on server")
                } else {
                    Logger.log(appCtx, "⏳ Shift $status audit queued — will retry automatically")
                    PendingStatusManager.savePendingStatus(appCtx, entry)
                }
            } catch (e: Exception) {
                Logger.log(appCtx, "⏳ Shift $status audit queued (${e.javaClass.simpleName})")
                PendingStatusManager.savePendingStatus(appCtx, entry)
            }
        }.start()
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    fun formatTime(millis: Long): String =
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(millis))

    fun formatDuration(millis: Long): String {
        if (millis <= 0L) return "0m"
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    /** One line for the home screens: what is happening and since when. */
    fun statusLine(context: Context): String {
        if (isOn(context)) {
            val since = startedAt(context)
            val suffix = if (since > 0L) {
                " since ${formatTime(since)} · ${formatDuration(System.currentTimeMillis() - since)}"
            } else ""
            return "Shift ON$suffix"
        }
        val ended = endedAt(context)
        return if (ended > 0L) "Shift OFF since ${formatTime(ended)}" else "Shift OFF"
    }

    private const val REASON_SCHEDULE_ELAPSED = "schedule_elapsed"
}
