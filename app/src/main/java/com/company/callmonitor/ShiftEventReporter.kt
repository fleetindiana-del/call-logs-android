package com.company.callmonitor

import android.content.Context
import com.company.callmonitor.location.LocationEnvironment
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's side of the shift audit trail.
 *
 * Every shift and tracking event is stamped with the moment it happened on the
 * phone, queued in a persistent [ShiftOutbox], and shipped to
 * `POST /api/shifts/device`. The server de-duplicates on the event id and returns
 * which shifts it now considers ended, so:
 *
 *  - an app that closes, a phone that loses signal, or a battery that dies loses
 *    nothing — the queue survives and drains on the next contact;
 *  - a driver who comes back online hours later still produces a log whose
 *    timestamps are when things happened, not when they were uploaded;
 *  - a shift the server closed (schedule end, an unresolved tracking failure)
 *    stops the phone too, so the two never disagree about whether someone is on
 *    shift.
 *
 * ## Tracking failures are reported as what they are
 *
 * [interruptTracking] takes a *cause* and never guesses one: permission revoked,
 * location switched off, tracking service stopped, a remote stop. Internet loss is
 * a separate event pair ([connectivityLost]/[connectivityRestored]) — losing signal
 * does not stop a phone recording locally, so it must never be filed as a tracking
 * failure. A device shutdown is not reported by the phone at all (it cannot);
 * instead [recordBoot] sends the last time the app was known to be running, and
 * the server decides what that evidence supports.
 *
 * Everything here is a no-op off a location-enabled (driver) device, and nothing
 * location-related is stored while the shift is off.
 */
object ShiftEventReporter {

    // Tracking-failure causes the phone may report. Mirrors the server's list;
    // NO_SIGNAL_FROM_DEVICE is deliberately absent — only the server can say that.
    const val CAUSE_GPS_PERMISSION_DENIED = "GPS_PERMISSION_DENIED"
    const val CAUSE_LOCATION_SERVICES_OFF = "LOCATION_SERVICES_OFF"
    const val CAUSE_TRACKING_SERVICE_STOPPED = "TRACKING_SERVICE_STOPPED"
    /** Location and permission are fine but no fix is arriving (tunnel, basement, weak signal). */
    const val CAUSE_NO_LOCATION_FIX = "NO_LOCATION_FIX"
    const val CAUSE_UNKNOWN = "UNKNOWN"

    private const val PREFS = "shift_events"
    private const val KEY_OUTBOX = "outbox"
    private const val KEY_CONFIG = "config"
    private const val KEY_LAST_ALIVE = "lastAliveAt"
    private const val KEY_LAST_LOC = "lastLocation"
    private const val KEY_LAST_FLUSH = "lastFlushAt"
    private const val KEY_CONN_LOST_SINCE = "connLostSince"
    private const val KEY_TRACK_CAUSE = "trackOpenCause"
    private const val KEY_TRACK_SINCE = "trackOpenSince"
    private const val KEY_TRACK_STARTED = "trackStartedRecorded"

    private const val BATCH = 200
    private const val MAX_ROUNDS = 5
    private const val PERSIST_THROTTLE_MS = 20_000L

    /** A silence longer than this since the app was last alive means the process was not running. */
    private const val PROCESS_GAP_MS = 3 * 60_000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shift-events").apply { isDaemon = true }
    }
    private val flushQueued = AtomicBoolean(false)
    private val flushLock = Any()
    private val stateLock = Any()

    @Volatile private var lastPersistAt = 0L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun now() = System.currentTimeMillis()

    // ── Schedule cache ───────────────────────────────────────────────────────

    /** The schedule the server last published, or the built-in default until then. */
    fun config(context: Context): ShiftSchedule.Config {
        val raw = prefs(context).getString(KEY_CONFIG, null)
        val parsed = raw?.let { try { ShiftSchedule.parse(JSONObject(it)) } catch (e: Exception) { null } }
        return parsed ?: ShiftSchedule.DEFAULT
    }

    // ── Recording ────────────────────────────────────────────────────────────

    /**
     * Queue one event stamped [at] (default: now) and start delivering it. Events
     * for a shift other than the current one must pass [shiftId] explicitly, which
     * is how the end of a shift is recorded after its state has been cleared.
     */
    fun record(
        context: Context,
        type: String,
        payload: JSONObject = JSONObject(),
        at: Long = now(),
        shiftId: String? = ShiftManager.shiftId(context)
    ) {
        if (shiftId.isNullOrBlank()) return
        val appCtx = context.applicationContext
        val event = ShiftOutbox.event("e-" + UUID.randomUUID(), shiftId, type, at, payload)
        synchronized(stateLock) {
            val p = prefs(appCtx)
            p.edit()
                .putString(KEY_OUTBOX, ShiftOutbox.append(p.getString(KEY_OUTBOX, ShiftOutbox.EMPTY) ?: ShiftOutbox.EMPTY, event))
                .apply()
        }
        Logger.log(appCtx, "🗒️ Shift event queued: $type")
        flushAsync(appCtx)
    }

    /** Payload carrying the last location recorded on this shift, when there is one. */
    fun withLocation(context: Context, payload: JSONObject = JSONObject()): JSONObject {
        lastLocation(context)?.let { payload.put("location", it) }
        return payload
    }

    fun pendingCount(context: Context): Int =
        ShiftOutbox.size(prefs(context).getString(KEY_OUTBOX, ShiftOutbox.EMPTY) ?: ShiftOutbox.EMPTY)

    // ── Shift lifecycle ──────────────────────────────────────────────────────

    /** A new shift starts from a clean slate: no inherited interruption or offline state. */
    fun beginShift(context: Context) {
        synchronized(stateLock) {
            prefs(context).edit()
                .remove(KEY_TRACK_CAUSE).remove(KEY_TRACK_SINCE)
                .putBoolean(KEY_TRACK_STARTED, false)
                .putLong(KEY_CONN_LOST_SINCE, 0L)
                .remove(KEY_LAST_LOC)
                .putLong(KEY_LAST_ALIVE, now())
                .apply()
        }
    }

    /**
     * Clear everything that only makes sense during a shift. The last known
     * location is dropped here: it is shift data, and nothing location-related is
     * kept once the shift is off.
     */
    fun endShift(context: Context) {
        synchronized(stateLock) {
            prefs(context).edit()
                .remove(KEY_TRACK_CAUSE).remove(KEY_TRACK_SINCE)
                .putBoolean(KEY_TRACK_STARTED, false)
                .putLong(KEY_CONN_LOST_SINCE, 0L)
                .remove(KEY_LAST_LOC)
                .apply()
        }
    }

    /** Outcome of asking the tracking service to start at shift on. */
    fun afterTrackingStart(context: Context, started: Boolean) {
        val appCtx = context.applicationContext
        if (started) {
            markTrackingStarted(appCtx)
            return
        }
        if (LocationEnvironment.isBlocked(appCtx)) {
            interruptTracking(
                appCtx, blockedCause(appCtx),
                reason = "Tracking could not start because " + blockedExplanation(appCtx)
            )
        } else {
            interruptTracking(
                appCtx, CAUSE_TRACKING_SERVICE_STOPPED,
                reason = "The tracking service could not be started when the shift began."
            )
        }
    }

    private fun markTrackingStarted(context: Context) {
        val appCtx = context.applicationContext
        val first = synchronized(stateLock) {
            val p = prefs(appCtx)
            val already = p.getBoolean(KEY_TRACK_STARTED, false)
            if (!already) p.edit().putBoolean(KEY_TRACK_STARTED, true).apply()
            !already
        }
        if (first) record(appCtx, "TRACKING_STARTED", withLocation(appCtx))
    }

    // ── Tracking interruptions ───────────────────────────────────────────────

    /** Why tracking is currently interrupted on this device, or null when it is not. */
    fun openTrackingCause(context: Context): String? = prefs(context).getString(KEY_TRACK_CAUSE, null)

    fun openTrackingSince(context: Context): Long = prefs(context).getLong(KEY_TRACK_SINCE, 0L)

    /**
     * Tracking stopped for a reason the driver did not choose. Idempotent: the same
     * outage is usually noticed by several code paths (the broadcast receiver, the
     * service watchdog, the periodic worker) and must produce one event, so a
     * second call while one is open is ignored. The one exception is a lost GPS fix:
     * if the real cause turns out to be harder (Location switched off, permission
     * revoked, service dead) that is recorded too, so the dashboard shows the truth.
     */
    fun interruptTracking(
        context: Context,
        cause: String,
        reason: String? = null,
        evidence: String? = null,
        at: Long = now()
    ) {
        val appCtx = context.applicationContext
        if (!ShiftManager.isOn(appCtx) || !ShiftManager.usesSchedule(appCtx)) return
        val shiftId = ShiftManager.shiftId(appCtx) ?: return

        val opened = synchronized(stateLock) {
            val p = prefs(appCtx)
            val open = p.getString(KEY_TRACK_CAUSE, null)
            val escalates = open == CAUSE_NO_LOCATION_FIX && cause != CAUSE_NO_LOCATION_FIX
            if (open != null && !escalates) false
            else {
                p.edit().putString(KEY_TRACK_CAUSE, cause).putLong(KEY_TRACK_SINCE, at).apply()
                true
            }
        }
        if (!opened) return

        val payload = withLocation(appCtx, JSONObject().put("cause", cause))
        if (!reason.isNullOrBlank()) payload.put("reason", reason)
        if (!evidence.isNullOrBlank()) payload.put("evidence", evidence)
        record(appCtx, "TRACKING_INTERRUPTED", payload, at, shiftId)
    }

    /**
     * Tracking is demonstrably working again. If tracking never successfully began
     * this shift, that is reported as its start rather than a resumption.
     */
    fun resumeTracking(context: Context, reason: String? = null) {
        val appCtx = context.applicationContext
        if (!ShiftManager.isOn(appCtx) || !ShiftManager.usesSchedule(appCtx)) return
        val closed = synchronized(stateLock) {
            val p = prefs(appCtx)
            if (p.getString(KEY_TRACK_CAUSE, null) == null) false
            else {
                p.edit().remove(KEY_TRACK_CAUSE).remove(KEY_TRACK_SINCE).apply()
                true
            }
        }
        if (!closed) return

        val everStarted = prefs(appCtx).getBoolean(KEY_TRACK_STARTED, false)
        val payload = withLocation(appCtx)
        if (!reason.isNullOrBlank()) payload.put("reason", reason)
        if (everStarted) {
            record(appCtx, "TRACKING_RESUMED", payload)
        } else {
            prefs(appCtx).edit().putBoolean(KEY_TRACK_STARTED, true).apply()
            record(appCtx, "TRACKING_STARTED", payload)
        }
    }

    fun blockedCause(context: Context): String =
        if (!LocationEnvironment.hasForegroundPermission(context)) CAUSE_GPS_PERMISSION_DENIED
        else CAUSE_LOCATION_SERVICES_OFF

    private fun blockedExplanation(context: Context): String =
        if (!LocationEnvironment.hasForegroundPermission(context)) {
            "location permission for this app is turned off."
        } else {
            "the phone's Location (GPS) is switched off."
        }

    fun causeLabel(cause: String?): String = when (cause) {
        CAUSE_GPS_PERMISSION_DENIED -> "Location permission is off"
        CAUSE_LOCATION_SERVICES_OFF -> "Phone location is switched off"
        CAUSE_TRACKING_SERVICE_STOPPED -> "Tracking service stopped"
        CAUSE_NO_LOCATION_FIX -> "No GPS fix (weak signal)"
        else -> "Tracking stopped"
    }

    // ── Connectivity (kept distinct from tracking failures) ──────────────────

    fun connectivityLostSince(context: Context): Long = prefs(context).getLong(KEY_CONN_LOST_SINCE, 0L)

    fun connectivityLost(context: Context, at: Long = now()) {
        val appCtx = context.applicationContext
        if (!ShiftManager.isOn(appCtx) || !ShiftManager.usesSchedule(appCtx)) return
        val opened = synchronized(stateLock) {
            val p = prefs(appCtx)
            if (p.getLong(KEY_CONN_LOST_SINCE, 0L) > 0L) false
            else { p.edit().putLong(KEY_CONN_LOST_SINCE, at).apply(); true }
        }
        if (opened) {
            record(
                appCtx, "CONNECTIVITY_LOST",
                JSONObject().put("reason", "The phone lost its internet connection. Recording continues on the phone."),
                at
            )
        }
    }

    fun connectivityRestored(context: Context, at: Long = now()) {
        val appCtx = context.applicationContext
        val wasLost = synchronized(stateLock) {
            val p = prefs(appCtx)
            if (p.getLong(KEY_CONN_LOST_SINCE, 0L) <= 0L) false
            else { p.edit().putLong(KEY_CONN_LOST_SINCE, 0L).apply(); true }
        }
        if (!wasLost) return
        if (ShiftManager.isOn(appCtx) && ShiftManager.usesSchedule(appCtx)) {
            record(appCtx, "CONNECTIVITY_RESTORED", withLocation(appCtx), at)
        }
        flushAsync(appCtx)
    }

    // ── Device restart ───────────────────────────────────────────────────────

    /**
     * The phone has restarted mid-shift. We cannot say it was switched off — only
     * that it restarted, and the last time the app was known to be running. The
     * server turns that into a shutdown interval only when the evidence supports it.
     */
    fun recordBoot(context: Context) {
        val appCtx = context.applicationContext
        if (!ShiftManager.isOn(appCtx) || !ShiftManager.usesSchedule(appCtx)) return
        // Application.onCreate runs before the boot receiver and may already have
        // refreshed the stored stamp; the value captured at process start is the
        // true last time the app was running before the restart.
        val aliveAt = if (aliveAtProcessStart >= 0L) aliveAtProcessStart else lastAliveAt(appCtx)
        val payload = JSONObject()
        if (aliveAt > 0L) {
            payload.put("lastAliveAt", aliveAt)
            payload.put("evidence", "Phone restarted. The app was last known to be running at that time.")
        } else {
            payload.put("evidence", "Phone restarted. No earlier activity time was available.")
        }
        // Offline state from before the restart no longer describes the phone.
        synchronized(stateLock) { prefs(appCtx).edit().putLong(KEY_CONN_LOST_SINCE, 0L).apply() }
        record(appCtx, "DEVICE_BOOTED", payload)
    }

    // ── Liveness and location ────────────────────────────────────────────────

    /** The stored liveness stamp as it was when this process began; -1 until read. */
    @Volatile private var aliveAtProcessStart = -1L

    /**
     * Call first thing when the process starts, before anything can refresh the
     * liveness stamp. If the process was killed mid-shift (an OEM battery manager,
     * the system reclaiming memory) tracking was not running between the last time
     * the app was known alive and now, and that gap is recorded with exactly that
     * evidence. A phone restart is not recorded here: [recordBoot] covers it, and
     * counting it twice would describe one outage as two.
     */
    fun onProcessStart(context: Context) {
        val appCtx = context.applicationContext
        if (aliveAtProcessStart >= 0L) return
        val alive = lastAliveAt(appCtx)
        aliveAtProcessStart = alive

        if (alive <= 0L || !ShiftManager.isOn(appCtx) || !ShiftManager.usesSchedule(appCtx)) return
        val t = now()
        val bootedAt = t - android.os.SystemClock.elapsedRealtime()
        if (bootedAt > alive) return
        if (t - alive < PROCESS_GAP_MS) return

        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        interruptTracking(
            appCtx,
            cause = CAUSE_TRACKING_SERVICE_STOPPED,
            reason = "The app was not running and was restarted.",
            evidence = "The app was last known to be running at ${fmt.format(java.util.Date(alive))} " +
                "and started again at ${fmt.format(java.util.Date(t))}.",
            at = alive
        )
    }

    fun lastAliveAt(context: Context): Long = prefs(context).getLong(KEY_LAST_ALIVE, 0L)

    /** The app is running now. Evidence for the restart/shutdown inference. */
    fun noteAlive(context: Context) {
        if (!ShiftManager.isOn(context)) return
        prefs(context).edit().putLong(KEY_LAST_ALIVE, now()).apply()
    }

    fun lastLocation(context: Context): JSONObject? =
        prefs(context).getString(KEY_LAST_LOC, null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }

    /**
     * Called for every fix the tracking service receives. A fix is also the
     * strongest possible proof that tracking works, so it closes an open
     * interruption. Persisted at most every [PERSIST_THROTTLE_MS]; nothing is
     * stored unless a shift is on.
     */
    fun noteFix(context: Context, lat: Double, lng: Double, accuracy: Float, recordedAt: Long) {
        val appCtx = context.applicationContext
        if (!ShiftManager.isOn(appCtx)) return

        val t = now()
        if (t - lastPersistAt >= PERSIST_THROTTLE_MS) {
            lastPersistAt = t
            val loc = JSONObject()
                .put("lat", lat).put("lng", lng)
                .put("accuracy", accuracy.toDouble())
                .put("recordedAt", recordedAt)
            prefs(appCtx).edit()
                .putString(KEY_LAST_LOC, loc.toString())
                .putLong(KEY_LAST_ALIVE, t)
                .apply()
        }
        if (openTrackingCause(appCtx) != null) {
            executor.execute { resumeTracking(appCtx, "A location fix was received again.") }
        }
    }

    // ── Periodic work ────────────────────────────────────────────────────────

    /**
     * Cheap, non-blocking housekeeping, safe to call from any loop: refresh the
     * liveness stamp, enforce the schedule, reconcile connectivity, and deliver
     * whatever is queued (plus a heartbeat) when due. Called by the tracking
     * service's watchdog, the 15-minute worker and app start.
     */
    fun tick(context: Context) {
        val appCtx = context.applicationContext
        if (!ShiftManager.usesSchedule(appCtx)) return
        executor.execute { tickNow(appCtx) }
    }

    /**
     * The blocking body of [tick], for callers that must finish their work before
     * returning (the periodic worker, which the system may stop once it returns).
     */
    fun tickNow(context: Context) {
        val appCtx = context.applicationContext
        if (!ShiftManager.usesSchedule(appCtx)) return
        try {
            ShiftManager.enforceSchedule(appCtx)
            val on = ShiftManager.isOn(appCtx)
            if (on) {
                noteAlive(appCtx)
                ConnectivityMonitor.sync(appCtx)
            }
            val rules = config(appCtx).rules
            val heartbeatDue = on && now() - prefs(appCtx).getLong(KEY_LAST_FLUSH, 0L) >= rules.heartbeatIntervalMs
            if (pendingCount(appCtx) > 0 || heartbeatDue) flush(appCtx)
        } catch (e: Exception) {
            Logger.log(appCtx, "⚠️ Shift housekeeping failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun flushAsync(context: Context) {
        val appCtx = context.applicationContext
        if (!flushQueued.compareAndSet(false, true)) return
        executor.execute {
            flushQueued.set(false)
            try {
                flush(appCtx)
            } catch (e: Exception) {
                Logger.log(appCtx, "⚠️ Shift upload failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // ── Delivery ─────────────────────────────────────────────────────────────

    /**
     * Deliver queued events (and a heartbeat while on shift). Blocking — call off
     * the main thread. An event leaves the queue only once the server has either
     * stored it or rejected it for good; a network failure leaves everything in
     * place for the next attempt.
     *
     * @return false only when the server could not be reached.
     */
    fun flush(context: Context): Boolean = synchronized(flushLock) {
        val appCtx = context.applicationContext
        if (!ShiftManager.usesSchedule(appCtx)) return true

        val serverUrl = EnrollmentPrefs.getServerUrl(appCtx)
        val apiKey = EnrollmentPrefs.getApiKey(appCtx)
        val deviceId = EnrollmentPrefs.getDeviceId(appCtx)
        if (serverUrl.isBlank() || deviceId.isBlank()) return false

        var rounds = 0
        while (rounds < MAX_ROUNDS) {
            rounds++
            val outbox = prefs(appCtx).getString(KEY_OUTBOX, ShiftOutbox.EMPTY) ?: ShiftOutbox.EMPTY
            val batch = ShiftOutbox.peek(outbox, BATCH)
            val onShift = ShiftManager.isOn(appCtx)
            val heartbeatDue = onShift &&
                now() - prefs(appCtx).getLong(KEY_LAST_FLUSH, 0L) >= config(appCtx).rules.heartbeatIntervalMs
            // Nothing queued and no heartbeat owed: nothing to say.
            if (batch.isEmpty() && !heartbeatDue) break

            val body = JSONObject().put("deviceId", deviceId)
            val arr = JSONArray()
            batch.forEach { arr.put(it) }
            body.put("events", arr)
            val sid = ShiftManager.shiftId(appCtx)
            if (onShift && sid != null) {
                val hb = JSONObject().put("shiftId", sid)
                lastLocation(appCtx)?.let { hb.put("location", it) }
                body.put("heartbeat", hb)
            }

            // Unreachable server: leave every event queued for the next attempt.
            val response = post(appCtx, serverUrl, apiKey, body) ?: return false
            prefs(appCtx).edit().putLong(KEY_LAST_FLUSH, now()).apply()
            handleResponse(appCtx, response)

            if (batch.size < BATCH) break
        }
        true
    }

    private fun handleResponse(context: Context, response: JSONObject) {
        val acknowledged = HashSet<String>()
        response.optJSONArray("accepted")?.let { a -> for (i in 0 until a.length()) acknowledged.add(a.optString(i)) }
        response.optJSONArray("duplicates")?.let { a -> for (i in 0 until a.length()) acknowledged.add(a.optString(i)) }
        response.optJSONArray("rejected")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                // Permanently invalid; resending can never help, and keeping it
                // would block the queue behind it forever.
                acknowledged.add(o.optString("key"))
                Logger.log(context, "⚠️ Server rejected shift event ${o.optString("key")}: ${o.optString("reason")}")
            }
        }
        if (acknowledged.isNotEmpty()) {
            synchronized(stateLock) {
                val p = prefs(context)
                val current = p.getString(KEY_OUTBOX, ShiftOutbox.EMPTY) ?: ShiftOutbox.EMPTY
                p.edit().putString(KEY_OUTBOX, ShiftOutbox.remove(current, acknowledged)).apply()
            }
        }

        // Follow the server's schedule, but only if it parses cleanly.
        val cfg = response.optJSONObject("config")
        if (cfg != null && ShiftSchedule.parse(cfg) != null) {
            prefs(context).edit().putString(KEY_CONFIG, cfg.toString()).apply()
        }

        // The server is authoritative about whether a shift is still open.
        val shifts = response.optJSONArray("shifts") ?: return
        val current = ShiftManager.shiftId(context) ?: return
        for (i in 0 until shifts.length()) {
            val s = shifts.optJSONObject(i) ?: continue
            if (s.optString("shiftId") == current && s.optBoolean("ended", false)) {
                ShiftManager.endedByServer(
                    context,
                    endReason = s.optString("endReason").ifBlank { null },
                    actualEnd = if (s.has("actualEnd")) s.optLong("actualEnd") else null
                )
            }
        }
    }

    private fun post(context: Context, serverUrl: String, apiKey: String, body: JSONObject): JSONObject? {
        var formatted = serverUrl.trimEnd('/')
        if (!formatted.startsWith("http")) formatted = "https://$formatted"
        return try {
            val conn = URL("$formatted/api/shifts/device").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-API-Key", apiKey)
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body.toString()) }

            val code = conn.responseCode
            if (code in 200..299) {
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(text)
            } else {
                Logger.log(context, "⏳ Shift upload will retry: HTTP $code")
                null
            }
        } catch (e: Exception) {
            // Offline is the normal case here, not an error worth shouting about.
            Logger.log(context, "⏳ Shift upload deferred (${e.javaClass.simpleName})")
            null
        }
    }
}
