package com.company.callmonitor.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.company.callmonitor.DriverHomeActivity
import com.company.callmonitor.EnrollmentPrefs
import com.company.callmonitor.Logger
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

class LocationTrackingService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private val executor = Executors.newSingleThreadExecutor()
    private var sessionId: String? = null
    private val sequence = AtomicLong(0)

    private val qualityFilter = LocationQualityFilter()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locations = result.locations.sortedBy { it.time }

            // Proof the subscription is alive, recorded before any filtering so a
            // run of rejected fixes never looks like a dead provider to the
            // watchdog. [lastAcceptedAt] is what tracks useful output.
            if (locations.isNotEmpty()) {
                lastFixAt = SystemClock.elapsedRealtime()
                silentCycles = 0
            }

            val sid = sessionId ?: return
            val deviceId = EnrollmentPrefs.getDeviceId(applicationContext)
            val battery = currentBatteryPercent()

            // Only good fixes get a vote on the sampling rate — a 500m wifi fix
            // must not push us into "driving" mode while the vehicle is parked.
            locations.lastOrNull { it.accuracy > 0f && it.accuracy <= LocationQualityFilter.MAX_ACCURACY_M }
                ?.let {
                    lastSpeedMps = if (it.hasSpeed()) it.speed else 0f
                    adjustInterval(lastSpeedMps)
                }

            locations.forEach { location ->
                val fix = location.toFix()

                // Filtering and persistence share the single-threaded executor so
                // the filter's reference point stays consistent with what is stored.
                // A fix can land after onDestroy shut the executor down; rejecting
                // that silently beats crashing the process on the main thread.
                submit {
                    val decision = qualityFilter.evaluate(fix)
                    if (!decision.accept) {
                        android.util.Log.d(TAG, "fix rejected: ${decision.reason}")
                        return@submit
                    }

                    val db = FleetLocationDatabase.getInstance(applicationContext)
                    val nextSeq = db.locationPointDao().maxSequence(sid) + 1
                    sequence.set(nextSeq)

                    db.locationPointDao().insert(
                        LocationPointEntity(
                            pointId = UUID.randomUUID().toString(),
                            sessionId = sid,
                            deviceId = deviceId,
                            sequenceNumber = nextSeq,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracyMeters = location.accuracy,
                            speedMetersPerSecond = location.speed,
                            bearingDegrees = location.bearing,
                            altitudeMeters = location.altitude,
                            recordedAt = fix.timeMs,
                            savedAt = System.currentTimeMillis(),
                            batteryPercent = battery,
                            provider = location.provider,
                            isMockLocation = fix.isMock,
                            uploadState = "PENDING"
                        )
                    )
                    if (decision.updateAnchor) qualityFilter.commit(fix)
                    lastAcceptedAt = SystemClock.elapsedRealtime()

                    // Continuous near-real-time upload: push the fresh point (and any
                    // backlog) to the server so the fleet map trail advances live.
                    // ExistingWorkPolicy.KEEP coalesces rapid enqueues into one run;
                    // the CONNECTED constraint holds points locally while offline.
                    LocationUploadWorker.enqueue(applicationContext)
                }
                // Local-first: recording never blocks on the network. Uploads are
                // enqueued per point above and also on server command / worker wake.
            }
        }
    }

    private fun Location.toFix(): LocationQualityFilter.Fix {
        val mocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isMock
        } else {
            @Suppress("DEPRECATION")
            isFromMockProvider
        }
        val ageMs = if (elapsedRealtimeNanos > 0) {
            (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
        } else {
            0L
        }
        return LocationQualityFilter.Fix(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else -1f,
            speedMetersPerSecond = if (hasSpeed()) speed else 0f,
            hasSpeed = hasSpeed(),
            timeMs = if (time > 0) time else System.currentTimeMillis(),
            ageMs = ageMs.coerceAtLeast(0L),
            isMock = mocked
        )
    }

    /** Hands work to [executor] unless it is already shutting down. */
    private fun submit(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (e: RejectedExecutionException) {
            android.util.Log.w(TAG, "executor closed, dropping fix")
        }
    }

    private var currentIntervalMs = 3_000L
    private var lastIntervalChangeAt = 0L

    /** Speed from the most recent trustworthy fix; drives the sampling rate. */
    @Volatile
    private var lastSpeedMps = 0f

    /** Elapsed-realtime of the last delivery from the provider (any quality). */
    @Volatile
    private var lastFixAt = 0L

    /** Elapsed-realtime of the last fix that actually made it into the database. */
    @Volatile
    private var lastAcceptedAt = 0L

    /** Elapsed-realtime of the last successful [requestLocationUpdates]. */
    @Volatile
    private var subscribedAt = 0L

    /**
     * Elapsed-realtime this service last *began* recording (start or resume).
     *
     * Health is measured from here rather than from [subscribedAt], which the
     * watchdog below refreshes on every re-subscribe: a service whose provider
     * had gone permanently silent kept bumping [subscribedAt] every cycle and so
     * reported itself healthy forever. Every recovery path — the 8-minute alarm,
     * the 15-minute worker, and [LocationDutyController.startTracking] itself —
     * is gated on that health check, so all three quietly did nothing while the
     * phone recorded no route at all. This is the anchor that cannot be moved by
     * merely trying again.
     */
    @Volatile
    private var recordingSince = 0L

    /** Consecutive watchdog cycles that re-subscribed and still saw no fix. */
    private var silentCycles = 0

    /** Elapsed-realtime of the last heartbeat posted to the server. */
    private var lastHeartbeatAt = 0L

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            checkSubscriptionAlive()
            watchdogHandler.postDelayed(this, WATCHDOG_PERIOD_MS)
        }
    }

    /**
     * The failure this exists for: the service stays alive with its notification
     * showing, but the fused provider stops delivering — Play services updated
     * underneath us, the OEM froze the app and thawed it without the
     * subscription, or the user toggled GPS off and back on. Nothing throws, so
     * the silence has to be measured. Re-subscribing costs one call and is tried
     * first; when that keeps failing the service is rebuilt, and the whole time
     * the phone's real state is reported so the outage is visible from the
     * dashboard rather than only from a missing route afterwards.
     */
    private fun checkSubscriptionAlive() {
        if (sessionId == null) return
        val now = SystemClock.elapsedRealtime()

        reportHeartbeat(now)

        // The phone's own location switch going off is not something another
        // re-subscribe can fix. Say so — on the notification shade and to the
        // server — instead of spinning silently for hours.
        if (LocationEnvironment.isBlocked(applicationContext)) {
            LocationHealthNotifier.update(applicationContext)
            return
        }
        LocationHealthNotifier.clear(applicationContext)

        val reference = maxOf(lastFixAt, subscribedAt)
        if (reference == 0L) return

        // Allow several missed cycles before acting: indoors or in a tunnel a
        // healthy subscription legitimately goes quiet for a while.
        val allowance = maxOf(currentIntervalMs * 5, MIN_SILENCE_MS)
        if (now - reference < allowance) return

        silentCycles++
        Logger.log(
            applicationContext,
            "📍 no fixes for ${(now - reference) / 1000}s — re-subscribing (attempt $silentCycles)"
        )
        startLocationUpdates()

        // Re-subscribing is the cheap fix and usually works. When it has not
        // worked several times over, the fused client itself is the problem —
        // Play services updated under us, or the OEM thawed the process without
        // its subscription. Tear the service down so the duty controller builds
        // a fresh one; staying alive here is what made the outage permanent.
        if (silentCycles >= MAX_SILENT_CYCLES) {
            Logger.log(
                applicationContext,
                "📍 provider dead after $silentCycles re-subscribes — restarting service"
            )
            silentCycles = 0
            LocationDutyController.requestRestart(applicationContext, "provider_dead")
        }
    }

    /**
     * Tell the server what this phone is actually doing, roughly every five
     * minutes. Until now [LocationApiClient.heartbeat] existed but was never
     * called from anywhere, so `DeviceLocationState` only ever changed when a
     * point was uploaded — meaning a device that had stopped recording looked
     * identical to one that was simply parked.
     */
    private fun reportHeartbeat(now: Long) {
        if (lastHeartbeatAt != 0L && now - lastHeartbeatAt < HEARTBEAT_PERIOD_MS) return
        lastHeartbeatAt = now

        val appCtx = applicationContext
        submit {
            val pending = try {
                FleetLocationDatabase.getInstance(appCtx).locationPointDao().countByState("PENDING")
            } catch (e: Exception) {
                0
            }
            try {
                LocationApiClient.heartbeat(
                    appCtx,
                    trackingStatus = LocationEnvironment.trackingStatus(appCtx),
                    gpsEnabled = LocationEnvironment.isLocationEnabled(appCtx),
                    permissionStatus = LocationEnvironment.permissionStatus(appCtx),
                    pendingPoints = pending,
                    batteryPercent = currentBatteryPercent()
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "heartbeat failed: ${e.message}")
            }
        }
    }

    private fun isLiveMode() = applicationContext
        .getSharedPreferences("location_prefs", Context.MODE_PRIVATE)
        .getBoolean("liveMode", false)

    /**
     * Near-Strava density while moving so the raw trace hugs the road closely
     * without inventing a path; back off when parked to protect battery on
     * all-day shifts. (Full 1 Hz is Strava's cost; ~2s is the balance.)
     * Live mode pins the fast rate regardless of speed.
     */
    private fun targetIntervalFor(speedMps: Float): Long {
        if (isLiveMode()) return LIVE_INTERVAL_MS
        return when {
            speedMps >= 5f -> 2_000L      // driving — dense enough to trace curves
            speedMps >= 1f -> 3_000L      // walking / slow crawl
            else -> 20_000L               // stationary — save battery
        }
    }

    private fun adjustInterval(speedMps: Float) {
        applyInterval(targetIntervalFor(speedMps), force = false)
    }

    private fun applyInterval(target: Long, force: Boolean) {
        if (kotlin.math.abs(target - currentIntervalMs) < 1_000L) return

        // Hysteresis: speed hovering around a threshold used to re-subscribe on
        // every fix, and each re-subscribe triggers a fresh burst of fixes.
        // A live-mode switch is explicit and must not wait it out — while parked
        // the next fix is 20s away, and the gate could then swallow it entirely.
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastIntervalChangeAt < MIN_INTERVAL_CHANGE_MS) return

        lastIntervalChangeAt = now
        currentIntervalMs = target
        startLocationUpdates()
    }

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        current = this
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // START_STICKY re-delivers a null intent after the system kills us, and a
        // null action arrives from any plain startService. Both used to fall
        // through this whole method doing nothing: no startForeground, no
        // subscription — a live-looking service (isRunning == true) that records
        // nothing and blocks LocationDutyController from starting a real one.
        // That is the silent stop drivers were seeing mid-shift, so recover the
        // session from prefs and start properly instead.
        val prefs = getSharedPreferences("location_prefs", Context.MODE_PRIVATE)
        val action = intent?.action ?: ACTION_RESUME
        val requestedSession = intent?.getStringExtra("sessionId")

        when (action) {
            ACTION_APPLY_LIVE_MODE -> {
                if (sessionId == null) {
                    // Woken by a live-mode ping without ever having started.
                    if (!resume(prefs)) return START_NOT_STICKY
                }
                // Re-subscribe now so a live-mode toggle from the dashboard takes
                // effect immediately instead of at the next scheduled fix.
                applyInterval(targetIntervalFor(lastSpeedMps), force = true)
            }

            ACTION_START -> {
                val sid = requestedSession ?: prefs.getString("activeSessionId", null)
                if (sid.isNullOrBlank()) {
                    android.util.Log.w(TAG, "START without a session id — stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // A repeat START for the session already running is the watchdog
                // healing us: keep the anchor, just make sure we are subscribed.
                val sameSession = sid == sessionId
                sessionId = sid
                prefs.edit().putString("activeSessionId", sid).apply()
                if (!sameSession) {
                    sequence.set(0)
                    seedFilterAnchor(sid)
                }
                if (!startForegroundNotification()) return START_NOT_STICKY
                beginRecording()
                startLocationUpdates()
            }

            else -> {
                if (!resume(prefs)) return START_NOT_STICKY
            }
        }
        startWatchdog()
        return START_STICKY
    }

    /**
     * Bring a restarted process back to recording the session that was active.
     * Returns false when there is nothing to resume, in which case the service
     * has stopped itself.
     */
    private fun resume(prefs: android.content.SharedPreferences): Boolean {
        val sid = sessionId ?: prefs.getString("activeSessionId", null)
        if (sid.isNullOrBlank() || !LocationDutyController.isTrackingDesired(this)) {
            android.util.Log.i(TAG, "nothing to resume — stopping")
            stopSelf()
            return false
        }
        if (sessionId != sid) {
            sessionId = sid
            seedFilterAnchor(sid)
        }
        if (!startForegroundNotification()) return false
        beginRecording()
        startLocationUpdates()
        Logger.log(applicationContext, "📍 tracking resumed after restart session=$sid")
        return true
    }

    /**
     * Mark the start of a recording stretch. [recordingSince] is the only
     * timestamp the staleness check trusts when no fix has ever arrived, so a
     * service that starts and then produces nothing goes stale on schedule
     * instead of looking permanently healthy.
     */
    private fun beginRecording() {
        // Once per service instance, deliberately. A repeat ACTION_START — which
        // the 8-minute alarm sends whenever it finds us unhealthy — must not
        // push the staleness deadline out, or "keep asking the same broken
        // service to try again" becomes the permanent state all over again.
        // A genuinely fresh instance starts from zero, which is what we want.
        if (recordingSince == 0L) {
            recordingSince = SystemClock.elapsedRealtime()
            silentCycles = 0
        }
    }

    private fun startWatchdog() {
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.postDelayed(watchdog, WATCHDOG_PERIOD_MS)
    }

    /**
     * Resume filtering from the last stored point so a service restart mid-shift
     * does not accept a bad fix as the new reference point.
     */
    private fun seedFilterAnchor(sid: String?) {
        if (sid == null) {
            submit { qualityFilter.reset() }
            return
        }
        submit {
            val last = try {
                FleetLocationDatabase.getInstance(applicationContext)
                    .locationPointDao().getLastPoint(sid)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "seed anchor failed: ${e.message}")
                null
            }
            if (last != null && !last.isMockLocation) {
                qualityFilter.seedAnchor(last.latitude, last.longitude, last.recordedAt)
            } else {
                qualityFilter.reset()
            }
        }
    }

    private fun startLocationUpdates() {
        // Capture on the time interval with no distance gate so curves are traced
        // exactly. Standstill duplicates are dropped by [qualityFilter] instead —
        // a provider-side distance gate cannot tell a 10m jitter hop from a 10m
        // crawl, which is what produced the standstill spikes on the fleet map.
        val stationary = currentIntervalMs >= 20_000L
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, currentIntervalMs)
            .setMinUpdateIntervalMillis((currentIntervalMs * 0.7).toLong())
            .setMinUpdateDistanceMeters(0f)
            // Ask explicitly for GPS-grade coordinates. Without this the platform
            // may hand back permission-derived coarse fixes, which the quality
            // filter then throws away — leaving gaps in the trace.
            .setGranularity(Granularity.GRANULARITY_FINE)
            // Skip the fast-but-rough first fix and wait for the accurate one.
            .setWaitForAccurateLocation(true)
            // Deliver promptly when moving; only allow batching while parked.
            .setMaxUpdateDelayMillis(if (stationary) currentIntervalMs * 3 else currentIntervalMs)
            .build()

        try {
            fusedClient.removeLocationUpdates(locationCallback)
            fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
            subscribedAt = SystemClock.elapsedRealtime()
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "Location permission denied: ${e.message}")
            Logger.log(applicationContext, "📍 location permission denied — tracking stopped")
            stopSelf()
        } catch (e: Exception) {
            // Play services can be updating or missing. Stay alive on the old
            // subscription; the watchdog retries rather than killing the session.
            android.util.Log.e(TAG, "requestLocationUpdates failed: ${e.message}")
        }
    }

    private fun currentBatteryPercent(): Int? {
        return try {
            val bm = getSystemService(BatteryManager::class.java)
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (current === this) current = null
        watchdogHandler.removeCallbacks(watchdog)
        try {
            fusedClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "removeLocationUpdates: ${e.message}")
        }
        executor.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Enter the foreground. Returns false — after stopping the service — when the
     * platform refuses, which it does on Android 14 if the location permission
     * was revoked and on Android 12+ for a background start outside an
     * exemption. Letting that exception escape crashed the whole app.
     */
    private fun startForegroundNotification(): Boolean {
        return try {
            showNotification()
            true
        } catch (e: Exception) {
            Logger.log(
                applicationContext,
                "📍 startForeground refused (${e.javaClass.simpleName}: ${e.message}) — will retry from watchdog"
            )
            stopSelf()
            false
        }
    }

    private fun showNotification() {
        createChannel()

        val openIntent = Intent(this, DriverHomeActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Route tracking active")
            .setContentText("Your location is being recorded for fleet tracking")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        // Declare the location type explicitly — required from Android 10 on, and
        // enforced from 14, where an untyped start throws.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while recording your route"
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.company.callmonitor.location.START"
        const val ACTION_STOP = "com.company.callmonitor.location.STOP"
        const val ACTION_APPLY_LIVE_MODE = "com.company.callmonitor.location.APPLY_LIVE_MODE"
        /** Internal: a start with no action, i.e. a system restart. */
        private const val ACTION_RESUME = "com.company.callmonitor.location.RESUME"
        private const val CHANNEL_ID = "LocationTrackingChannel"
        private const val NOTIFICATION_ID = 8001
        private const val TAG = "LocationTrackingService"
        /** Minimum time between sampling-rate changes (avoids re-subscribe churn). */
        private const val MIN_INTERVAL_CHANGE_MS = 15_000L
        /** Sampling rate while the dashboard is watching this device live. */
        private const val LIVE_INTERVAL_MS = 2_000L
        /** How often the in-service watchdog checks that fixes are still arriving. */
        private const val WATCHDOG_PERIOD_MS = 60_000L
        /** Never re-subscribe on a silence shorter than this. */
        private const val MIN_SILENCE_MS = 3 * 60_000L
        /**
         * Re-subscribes to try before concluding the fused client is the problem
         * and rebuilding the service. Each attempt resets the silence clock, so
         * this is [MIN_SILENCE_MS] apart — about a quarter-hour of patience.
         */
        private const val MAX_SILENT_CYCLES = 5
        /** How often the service reports its real state to the server. */
        private const val HEARTBEAT_PERIOD_MS = 5 * 60_000L
        /**
         * Beyond this a service claiming to run is treated as dead by
         * [LocationDutyController], which then restarts it. Deliberately far
         * longer than [MIN_SILENCE_MS] so the cheap in-process fix gets several
         * attempts before we tear the service down.
         */
        private const val STALE_AFTER_MS = 15 * 60_000L

        /**
         * Re-read the liveMode flag and re-subscribe at the matching rate.
         * No-op when nothing is recording — starting the service from here would
         * create one that never calls startForeground.
         */
        fun applyLiveMode(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, LocationTrackingService::class.java)
                .setAction(ACTION_APPLY_LIVE_MODE)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "applyLiveMode failed: ${e.message}")
            }
        }

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var current: LocationTrackingService? = null

        /**
         * Whether the service is genuinely recording, as opposed to merely
         * existing. [isRunning] alone was the trap: a service the system had
         * restarted, or one whose provider subscription had gone quiet, still
         * reported true and made every restart path a no-op.
         */
        fun isHealthy(): Boolean {
            val svc = current ?: return false
            if (!isRunning || svc.sessionId == null) return false

            // Only evidence the service cannot manufacture counts: a delivered
            // fix, or — before the first one lands — the moment recording began.
            // Using the last re-subscribe here (as this did) let the in-service
            // watchdog vouch for itself forever, so a permanently silent
            // provider never became "unhealthy" and nothing ever restarted it.
            val reference = if (svc.lastFixAt != 0L) svc.lastFixAt else svc.recordingSince
            if (reference == 0L) return false
            return SystemClock.elapsedRealtime() - reference < STALE_AFTER_MS
        }

        /** Elapsed-realtime ms since the provider last delivered anything. */
        fun millisSinceLastFix(): Long? {
            val svc = current ?: return null
            val reference = if (svc.lastFixAt != 0L) svc.lastFixAt else svc.recordingSince
            if (reference == 0L) return null
            return SystemClock.elapsedRealtime() - reference
        }

        /** Elapsed-realtime ms since the last stored point, or null if unknown. */
        fun millisSinceLastAcceptedFix(): Long? {
            val svc = current ?: return null
            val last = svc.lastAcceptedAt
            if (last == 0L) return null
            return SystemClock.elapsedRealtime() - last
        }
    }
}
