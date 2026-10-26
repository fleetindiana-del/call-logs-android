package com.company.callmonitor.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: a parked vehicle must not produce the "spider web" trail.
 */
class LocationQualityFilterTest {

    private val baseLat = 22.3072
    private val baseLng = 73.1812

    private fun fix(
        lat: Double = baseLat,
        lng: Double = baseLng,
        accuracy: Float = 8f,
        speed: Float = 0f,
        hasSpeed: Boolean = true,
        timeMs: Long = 1_000L,
        ageMs: Long = 0L,
        isMock: Boolean = false
    ) = LocationQualityFilter.Fix(
        latitude = lat,
        longitude = lng,
        accuracyMeters = accuracy,
        speedMetersPerSecond = speed,
        hasSpeed = hasSpeed,
        timeMs = timeMs,
        ageMs = ageMs,
        isMock = isMock
    )

    /** Offset a coordinate by roughly [meters] north. */
    private fun north(meters: Double) = baseLat + meters / 111_320.0

    private fun LocationQualityFilter.push(f: LocationQualityFilter.Fix): LocationQualityFilter.Decision {
        val d = evaluate(f)
        if (d.accept && d.updateAnchor) commit(f)
        return d
    }

    @Test
    fun firstGoodFixIsAccepted() {
        val filter = LocationQualityFilter()
        assertTrue(filter.evaluate(fix()).accept)
    }

    @Test
    fun lowAccuracyFixIsRejected() {
        val filter = LocationQualityFilter()
        // Wifi/cell derived fix — the source of the long spikes on the map.
        assertFalse(filter.evaluate(fix(accuracy = 1200f)).accept)
        assertFalse(filter.evaluate(fix(accuracy = 51f)).accept)
        // 30-50 m fixes are the parking-area multipath band — also rejected.
        assertFalse(filter.evaluate(fix(accuracy = 40f)).accept)
        assertTrue(filter.evaluate(fix(accuracy = 34f)).accept)
    }

    @Test
    fun missingAccuracyIsRejected() {
        val filter = LocationQualityFilter()
        assertFalse(filter.evaluate(fix(accuracy = -1f)).accept)
    }

    @Test
    fun staleFixIsRejected() {
        val filter = LocationQualityFilter()
        assertFalse(filter.evaluate(fix(ageMs = 90_000)).accept)
    }

    @Test
    fun nullIslandIsRejected() {
        val filter = LocationQualityFilter()
        assertFalse(filter.evaluate(fix(lat = 0.0, lng = 0.0)).accept)
    }

    @Test
    fun stationaryJitterIsCollapsed() {
        val filter = LocationQualityFilter()
        filter.push(fix(timeMs = 0))

        // Ten fixes wandering within the GPS error radius, zero reported speed.
        var rejected = 0
        for (i in 1..10) {
            val wobble = if (i % 2 == 0) 9.0 else -7.0
            val d = filter.push(fix(lat = north(wobble), timeMs = i * 20_000L))
            if (!d.accept) rejected++
        }
        assertEquals("all standstill jitter should be dropped", 10, rejected)
    }

    @Test
    fun aLongStopRecordsNothing() {
        val filter = LocationQualityFilter()
        filter.push(fix(timeMs = 0))
        // Six minutes later, 4 m away, on a tight fix: still parked. This used
        // to be accepted as a keepalive, which made the vehicle look like it had
        // set off again every five minutes. Liveness is a heartbeat's job now.
        val d = filter.push(fix(lat = north(4.0), accuracy = 10f, timeMs = 6 * 60_000L))
        assertFalse("a stop must not invent a position", d.accept)
    }

    @Test
    fun anHourParkedProducesNoPoints() {
        val filter = LocationQualityFilter()
        filter.push(fix(timeMs = 0))
        // One tight fix per 5 minutes, wandering inside the error radius —
        // exactly the pattern seen from a parked phone on the fleet map.
        var accepted = 0
        for (i in 1..12) {
            val wobble = if (i % 2 == 0) 5.0 else -4.0
            val d = filter.push(
                fix(lat = north(wobble), accuracy = 12f, timeMs = i * 5 * 60_000L)
            )
            if (d.accept) accepted++
        }
        assertEquals("a parked hour should draw no trail", 0, accepted)
    }

    @Test
    fun jumpFasterThanTheReportedSpeedIsRejected() {
        val filter = LocationQualityFilter()
        filter.push(fix(timeMs = 0, speed = 7.6f))
        // 85 m in 3.7 s is 23 m/s, but the device reports 7.6 m/s (27 km/h).
        val d = filter.push(fix(lat = north(85.0), accuracy = 30f, speed = 7.6f, timeMs = 3_700))
        assertFalse("position-implied speed contradicts Doppler speed", d.accept)
    }

    @Test
    fun pullingAwayAfterALongStopIsKept() {
        val filter = LocationQualityFilter()
        filter.push(fix(timeMs = 0))
        // The first fix after a stop is what ends the idle period in Route
        // History, so dropping standstill noise must not drop this too.
        val d = filter.push(
            fix(lat = north(40.0), accuracy = 10f, speed = 8f, timeMs = 6 * 60_000L)
        )
        assertTrue("movement after a stop must still register", d.accept)
    }

    @Test
    fun realMovementIsKept() {
        val filter = LocationQualityFilter()
        filter.push(fix(timeMs = 0))
        // 30 m in 2 s at 15 m/s — normal city driving.
        val d = filter.push(fix(lat = north(30.0), speed = 15f, timeMs = 2_000))
        assertTrue(d.accept)
    }

    @Test
    fun slowCrawlIsKeptWhenProviderReportsSpeed() {
        val filter = LocationQualityFilter()
        filter.push(fix(timeMs = 0))
        // Walking pace: only 4 m of displacement, but Doppler speed confirms motion.
        val d = filter.push(fix(lat = north(4.0), speed = 1.4f, timeMs = 3_000))
        assertTrue(d.accept)
    }

    @Test
    fun teleportOutlierIsRejected() {
        val filter = LocationQualityFilter()
        filter.push(fix(timeMs = 0))
        // 5 km in 2 s.
        val d = filter.push(fix(lat = north(5_000.0), speed = 0.2f, timeMs = 2_000))
        assertFalse(d.accept)
        assertTrue(d.reason.startsWith("teleport"))
    }

    @Test
    fun persistentOutliersReAnchorSoTrackingRecovers() {
        val filter = LocationQualityFilter()
        filter.push(fix(timeMs = 0))

        var accepted: LocationQualityFilter.Decision? = null
        for (i in 1..LocationQualityFilter.MAX_CONSECUTIVE_OUTLIERS) {
            val d = filter.push(fix(lat = north(5_000.0), speed = 30f, timeMs = i * 1_000L))
            if (d.accept) accepted = d
        }
        assertEquals("re-anchor", accepted?.reason)
    }

    @Test
    fun longGapIsNotTreatedAsTeleport() {
        val filter = LocationQualityFilter()
        filter.push(fix(timeMs = 0))
        // Doze / tunnel: 20 km later, 30 min gap — a genuine trip, not an outlier.
        val d = filter.push(fix(lat = north(20_000.0), speed = 12f, timeMs = 30 * 60_000L))
        assertTrue(d.accept)
    }

    @Test
    fun outOfOrderFixIsRejected() {
        val filter = LocationQualityFilter()
        filter.push(fix(timeMs = 10_000))
        assertFalse(filter.evaluate(fix(lat = north(200.0), timeMs = 5_000)).accept)
    }

    @Test
    fun anchorStuckInTheFutureRecoversInsteadOfRejectingForever() {
        val filter = LocationQualityFilter()
        // A fix stamped hours ahead — a clock correction landing mid-shift, or a
        // provider timestamp from the future — becomes the anchor.
        filter.push(fix(timeMs = 5 * 60 * 60_000L))

        // Every later fix now compares as out-of-order. A few rejections are
        // fine, but the filter must re-anchor rather than kill the rest of the
        // trip: this is what silently ended tracking mid-route.
        var accepted = false
        repeat(LocationQualityFilter.MAX_CONSECUTIVE_OUTLIERS) { i ->
            if (filter.push(fix(lat = north(50.0 * (i + 1)), timeMs = 1_000L * i)).accept) {
                accepted = true
            }
        }
        assertTrue("filter must recover from a future-dated anchor", accepted)

        // And it keeps working afterwards.
        assertTrue(filter.push(fix(lat = north(400.0), speed = 12f, timeMs = 60_000L)).accept)
    }

    @Test
    fun mockFixIsStoredButNeverBecomesTheAnchor() {
        val filter = LocationQualityFilter()
        filter.push(fix(timeMs = 0))
        val d = filter.push(fix(lat = north(5_000.0), timeMs = 1_000, isMock = true))
        assertTrue("mock fixes stay in the record as a tamper signal", d.accept)
        assertFalse(d.updateAnchor)

        // The anchor is still the original point, so the next real fix is judged
        // against it and the fake jump does not poison the trail.
        assertFalse(filter.push(fix(lat = north(2.0), timeMs = 2_000)).accept)
    }

    @Test
    fun seededAnchorSurvivesServiceRestart() {
        val filter = LocationQualityFilter()
        filter.seedAnchor(baseLat, baseLng, 0L)
        val d = filter.evaluate(fix(lat = north(3.0), timeMs = 20_000))
        assertFalse("restart must not re-accept the same parked position", d.accept)
    }
}
