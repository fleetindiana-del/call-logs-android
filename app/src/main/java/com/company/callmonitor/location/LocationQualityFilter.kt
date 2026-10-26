package com.company.callmonitor.location

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides which GPS fixes are worth recording.
 *
 * Without this every fix the fused provider emits is stored, so a parked vehicle
 * produces the "spider web" artifact seen on the fleet map: the phone falls back
 * to wifi/cell derived fixes with 100m–2km accuracy and the trail shoots out to
 * each of them and back. Four gates remove it — accuracy, stationary jitter,
 * teleport outliers, and disagreement with the device's own reported speed.
 *
 * Pure logic (no Android types) so it is unit testable.
 */
class LocationQualityFilter(
    private val maxAccuracyM: Float = MAX_ACCURACY_M,
    private val minMoveM: Float = MIN_MOVE_M,
    private val movingSpeedMps: Float = MOVING_SPEED_MPS,
    private val maxSpeedMps: Float = MAX_SPEED_MPS
) {

    /** A candidate fix, mapped from `android.location.Location`. */
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        /** Horizontal accuracy in metres; <= 0 when the provider did not report one. */
        val accuracyMeters: Float,
        val speedMetersPerSecond: Float,
        val hasSpeed: Boolean,
        /** Wall-clock time of the fix (ms). */
        val timeMs: Long,
        /** How long ago the fix was actually taken, from elapsed realtime (ms). */
        val ageMs: Long,
        val isMock: Boolean
    )

    data class Decision(
        val accept: Boolean,
        /** False for fixes that must not become the reference point (mock fixes). */
        val updateAnchor: Boolean,
        val reason: String
    )

    private data class Anchor(val latitude: Double, val longitude: Double, val timeMs: Long)

    private var anchor: Anchor? = null
    private var consecutiveOutliers = 0

    /** Seed the reference point from the last stored point after a service restart. */
    fun seedAnchor(latitude: Double, longitude: Double, timeMs: Long) {
        anchor = Anchor(latitude, longitude, timeMs)
        consecutiveOutliers = 0
    }

    fun reset() {
        anchor = null
        consecutiveOutliers = 0
    }

    fun evaluate(fix: Fix): Decision {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite()) {
            return Decision(false, false, "invalid-coords")
        }
        if (abs(fix.latitude) > 90.0 || abs(fix.longitude) > 180.0) {
            return Decision(false, false, "invalid-coords")
        }
        // Null Island: what some providers emit when they have nothing.
        if (abs(fix.latitude) < 1e-7 && abs(fix.longitude) < 1e-7) {
            return Decision(false, false, "null-island")
        }

        // Mock fixes are kept as a tamper signal but are never trusted as the
        // reference point, and are exempt from the motion gates below.
        if (fix.isMock) return Decision(true, false, "mock")

        if (fix.accuracyMeters <= 0f || fix.accuracyMeters > maxAccuracyM) {
            return Decision(false, false, "accuracy=${fix.accuracyMeters}")
        }
        if (fix.ageMs > MAX_FIX_AGE_MS) {
            return Decision(false, false, "stale=${fix.ageMs}ms")
        }

        val previous = anchor ?: return Decision(true, true, "first")

        val dtMs = fix.timeMs - previous.timeMs
        if (dtMs < 0) {
            // A single fix arriving out of order is noise. A *run* of them means
            // the anchor's clock is ahead of the phone's — which happens when the
            // network time correction lands mid-shift, or the provider stamped a
            // fix from the future — and rejecting on that comparison forever
            // silently killed tracking for the rest of the trip. Re-anchor
            // instead of waiting for a timestamp that will never come.
            consecutiveOutliers++
            if (consecutiveOutliers < MAX_CONSECUTIVE_OUTLIERS) {
                return Decision(false, false, "out-of-order=${dtMs}ms")
            }
            consecutiveOutliers = 0
            return Decision(true, true, "re-anchor-clock")
        }

        val distanceM = haversineMeters(
            previous.latitude, previous.longitude, fix.latitude, fix.longitude
        )

        // Teleport gate. Skipped after a long gap, where the device may simply
        // have been asleep or out of coverage while genuinely travelling.
        if (dtMs in 1 until GAP_RESET_MS) {
            val impliedSpeed = distanceM / (dtMs / 1000.0)
            val teleport = impliedSpeed > maxSpeedMps && distanceM > minMoveM

            // Doppler cross-check. The reported speed is measured, where the
            // implied speed is two smeared positions differenced, so the fix may
            // only reach as far as that speed allows plus its own error radius:
            // an 85 m step in 3.7 s from a device reporting 27 km/h is
            // multipath, not a manoeuvre. Stated as a distance rather than a
            // speed ratio so a rejected fix, which leaves the anchor further
            // back in time, does not quietly relax the test.
            val reachableM = fix.speedMetersPerSecond * (dtMs / 1000.0) * SPEED_TOLERANCE +
                max(minMoveM, fix.accuracyMeters)
            val inconsistent = fix.hasSpeed && distanceM > reachableM

            if (teleport || inconsistent) {
                consecutiveOutliers++
                if (consecutiveOutliers < MAX_CONSECUTIVE_OUTLIERS) {
                    val reason = if (teleport) "teleport" else "speed-mismatch"
                    return Decision(false, false, "$reason=${impliedSpeed.toInt()}mps")
                }
                // Repeatedly "impossible" means the anchor itself is wrong
                // (a bad fix we accepted, or a resumed trip) — re-anchor here.
                consecutiveOutliers = 0
                return Decision(true, true, "re-anchor")
            }
        }
        consecutiveOutliers = 0

        // Stationary gate. A fix inside its own error radius is indistinguishable
        // from standing still, so only Doppler speed or real displacement counts
        // as movement.
        val moving = fix.hasSpeed && fix.speedMetersPerSecond >= movingSpeedMps
        if (!moving && distanceM < max(minMoveM, fix.accuracyMeters)) {
            // A parked vehicle records nothing at all.
            //
            // This used to let one "keepalive" fix through every five minutes so
            // that a long stop still refreshed last-seen. It was the only signal
            // the server had that a phone was alive, and it cost a fabricated
            // position: the device went Live again every five minutes without
            // moving, and each keepalive landed a few metres off the last one —
            // measured at ~29 m of invented travel per parked hour on a real
            // device, with the anchor walking along behind it, since an accepted
            // heartbeat also committed itself as the new reference point.
            // LocationApiClient.heartbeat now reports
            // liveness directly, so the position no longer has to carry it.
            return Decision(false, false, "stationary=${distanceM.toInt()}m")
        }

        return Decision(true, true, "moving=${distanceM.toInt()}m")
    }

    /** Call after a fix has been persisted so the reference point advances. */
    fun commit(fix: Fix) {
        anchor = Anchor(fix.latitude, fix.longitude, fix.timeMs)
    }

    companion object {
        /**
         * Fixes worse than this are wifi/cell assisted, not GPS. Kept well under
         * the 50 m the provider is happy to hand out: 30–50 m fixes are where
         * multipath around a parking area draws phantom legs.
         */
        const val MAX_ACCURACY_M = 35f
        /** Below this displacement a fix is treated as noise, not travel. */
        const val MIN_MOVE_M = 15f
        /** Reported speed at or above this confirms real movement. */
        const val MOVING_SPEED_MPS = 1.0f
        /** ~200 km/h — above this the segment is an outlier, not a vehicle. */
        const val MAX_SPEED_MPS = 55f
        /** How much further than the reported speed allows a fix may reach. */
        const val SPEED_TOLERANCE = 1.25

        const val MAX_FIX_AGE_MS = 60_000L
        const val GAP_RESET_MS = 120_000L
        const val MAX_CONSECUTIVE_OUTLIERS = 5

        private const val EARTH_RADIUS_M = 6_371_000.0

        fun haversineMeters(
            aLat: Double,
            aLng: Double,
            bLat: Double,
            bLng: Double
        ): Double {
            val lat1 = Math.toRadians(aLat)
            val lat2 = Math.toRadians(bLat)
            val dLat = lat2 - lat1
            val dLng = Math.toRadians(bLng - aLng)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
            return EARTH_RADIUS_M * 2 * atan2(sqrt(h), sqrt(1 - h))
        }
    }
}
