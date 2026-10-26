package com.company.callmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shift gate decides which calls reach the server and which are discarded
 * for good, so both directions are worth pinning down: an on-shift call must
 * survive a dead phone and a late sweep, and an off-shift call must never be
 * collected by any of them.
 */
class ShiftWindowsTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    private val start = 1_700_000_000_000L
    private val end = start + 8 * hour

    private fun oneClosedShift(): String =
        ShiftWindows.close(ShiftWindows.open(ShiftWindows.EMPTY, start), end)

    @Test
    fun `no history collects nothing`() {
        assertFalse(ShiftWindows.contains(ShiftWindows.EMPTY, start))
        assertEquals(Long.MAX_VALUE, ShiftWindows.earliestStart(ShiftWindows.EMPTY))
    }

    @Test
    fun `calls inside a closed shift are collected`() {
        val json = oneClosedShift()
        assertTrue(ShiftWindows.contains(json, start))
        assertTrue(ShiftWindows.contains(json, start + 4 * hour))
        assertTrue(ShiftWindows.contains(json, end))
    }

    @Test
    fun `calls outside a closed shift are not collected`() {
        val json = oneClosedShift()
        assertFalse(ShiftWindows.contains(json, start - 1))
        assertFalse(ShiftWindows.contains(json, end + 1))
    }

    @Test
    fun `an open shift collects everything after its start and nothing before`() {
        val json = ShiftWindows.open(ShiftWindows.EMPTY, start)
        assertFalse(ShiftWindows.contains(json, start - 1))
        assertTrue(ShiftWindows.contains(json, start))
        assertTrue(ShiftWindows.contains(json, start + 100 * day))
    }

    @Test
    fun `the gap between two shifts is never collected`() {
        var json = oneClosedShift()
        val secondStart = end + 10 * hour
        json = ShiftWindows.open(json, secondStart)
        json = ShiftWindows.close(json, secondStart + 4 * hour)

        assertTrue(ShiftWindows.contains(json, start + hour))
        assertFalse(ShiftWindows.contains(json, end + 5 * hour))
        assertTrue(ShiftWindows.contains(json, secondStart + hour))
        assertEquals(start, ShiftWindows.earliestStart(json))
    }

    @Test
    fun `a shift left open by a crash does not swallow the off-shift hours after it`() {
        // turnOn without the matching turnOff — a process killed mid-shift.
        val abandoned = ShiftWindows.open(ShiftWindows.EMPTY, start)
        val nextStart = start + 3 * day

        // Opening the next shift closes the abandoned one at its own start, so
        // the three days in between stay off-shift.
        val json = ShiftWindows.open(abandoned, nextStart)
        assertFalse(ShiftWindows.contains(json, start + hour))
        assertFalse(ShiftWindows.contains(json, nextStart - hour))
        assertTrue(ShiftWindows.contains(json, nextStart + hour))
    }

    @Test
    fun `windows older than the retention period are dropped`() {
        val json = oneClosedShift()
        val muchLater = end + ShiftWindows.RETENTION_MS + day

        val pruned = ShiftWindows.open(json, muchLater)
        assertFalse(ShiftWindows.contains(pruned, start + hour))
        assertEquals(muchLater, ShiftWindows.earliestStart(pruned))
    }

    @Test
    fun `a window still inside the retention period survives a new shift`() {
        val json = oneClosedShift()
        val later = end + day

        val kept = ShiftWindows.open(json, later)
        assertTrue(ShiftWindows.contains(kept, start + hour))
        assertEquals(start, ShiftWindows.earliestStart(kept))
    }

    @Test
    fun `corrupt history collects nothing rather than everything`() {
        assertFalse(ShiftWindows.contains("not json", start))
        assertEquals(Long.MAX_VALUE, ShiftWindows.earliestStart("not json"))
    }

    @Test
    fun `closing with no shift open changes nothing`() {
        val json = oneClosedShift()
        val closedAgain = ShiftWindows.close(json, end + day)
        assertTrue(ShiftWindows.contains(closedAgain, start + hour))
        assertFalse(ShiftWindows.contains(closedAgain, end + hour))
    }
}
