package com.company.callmonitor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The phone must offer exactly the shifts the server will accept, and end a shift
 * at the instant the server would. These pin the arithmetic against known IST
 * instants (UTC+05:30, no DST) and a DST zone, and mirror the cases in
 * web/src/lib/shiftConfig.test.ts.
 */
class ShiftScheduleTest {

    private val cfg = ShiftSchedule.DEFAULT
    private val minute = 60_000L

    /** 2026-10-15 at hh:mm Asia/Kolkata, as UTC ms. */
    private fun ist(h: Int, m: Int, day: Int = 15): Long =
        java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
            clear()
            set(2026, java.util.Calendar.OCTOBER, day, h, m, 0)
            timeInMillis - (5 * 60 + 30) * minute
        }

    private fun types(at: Long) = ShiftSchedule.eligibleAt(cfg, at).map { it.type }

    @Test
    fun `mid morning only the morning shift is open`() {
        assertEquals(listOf("MORNING"), types(ist(8, 0)))
    }

    @Test
    fun `mid evening only the evening shift is open`() {
        assertEquals(listOf("EVENING"), types(ist(18, 0)))
    }

    @Test
    fun `a shift opens thirty minutes before its start`() {
        assertEquals(emptyList<String>(), types(ist(5, 29)))
        assertEquals(listOf("MORNING"), types(ist(5, 30)))
    }

    @Test
    fun `around the handover both shifts are open and ordered by start`() {
        assertEquals(listOf("MORNING", "EVENING"), types(ist(13, 45)))
    }

    @Test
    fun `a shift is no longer offered at its scheduled end`() {
        assertEquals(listOf("EVENING"), types(ist(21, 59)))
        assertEquals(emptyList<String>(), types(ist(22, 0)))
    }

    @Test
    fun `the middle of the night offers nothing`() {
        assertEquals(emptyList<String>(), types(ist(2, 0)))
    }

    @Test
    fun `window instants match the wall clock in the schedule zone`() {
        val w = ShiftSchedule.eligibleAt(cfg, ist(8, 0)).single()
        assertEquals(ist(6, 0), w.startMs)
        assertEquals(ist(14, 0), w.endMs)
    }

    @Test
    fun `the phone closes a shift at scheduled end plus grace`() {
        val w = ShiftSchedule.eligibleAt(cfg, ist(18, 0)).single()
        assertEquals(ist(22, 15), ShiftSchedule.hardEnd(w, cfg.rules))
    }

    @Test
    fun `an overnight shift belongs to the day it started`() {
        val night = ShiftSchedule.Config(
            "Asia/Kolkata",
            listOf(
                ShiftSchedule.Def("MORNING", "Morning", 6 * 60, 22 * 60),
                ShiftSchedule.Def("EVENING", "Night", 22 * 60, 6 * 60)
            ),
            ShiftSchedule.DEFAULT_RULES
        )
        // 02:00 on the 15th is inside the night shift that began at 22:00 on the 14th.
        val w = ShiftSchedule.eligibleAt(night, ist(2, 0)).single()
        assertEquals("EVENING", w.type)
        assertEquals(ist(22, 0, day = 14), w.startMs)
        assertEquals(ist(6, 0), w.endMs)
    }

    @Test
    fun `a start outside both windows is assigned to the nearest occurrence`() {
        val w = ShiftSchedule.windowForStart(cfg, "MORNING", ist(3, 0))
        assertEquals(ist(6, 0), w.startMs)
    }

    @Test
    fun `a shift spanning a clock change keeps its wall-clock hours`() {
        // Windows are built from wall-clock fields in the schedule zone, not by adding
        // elapsed minutes to midnight, so they stay right on the day clocks change.
        val ny = ShiftSchedule.Config(
            "America/New_York",
            listOf(
                ShiftSchedule.Def("MORNING", "Morning", 1 * 60, 4 * 60),
                ShiftSchedule.Def("EVENING", "Evening", 12 * 60, 20 * 60)
            ),
            ShiftSchedule.DEFAULT_RULES
        )
        val tz = TimeZone.getTimeZone("America/New_York")
        // 2026-03-08: clocks jump 02:00 -> 03:00, so 01:00-04:00 local is two hours long.
        val w = ShiftSchedule.windowOnDay(ny.shifts[0], 2026, 3, 8, tz)
        assertEquals(2 * 60 * minute, w.endMs - w.startMs)
    }

    @Test
    fun `server config is parsed`() {
        val parsed = ShiftSchedule.parse(
            JSONObject(
                """{"timezone":"Asia/Kolkata","shifts":[
                    {"type":"EVENING","label":"Evening shift","startMinute":900,"endMinute":1380},
                    {"type":"MORNING","label":"Morning shift","startMinute":420,"endMinute":900}],
                   "rules":{"earlyStartMs":600000,"endGraceMs":300000,"heartbeatIntervalMs":300000,
                            "staleAfterMs":600000,"autoEndAfterInterruptionMs":3600000}}"""
            )
        )
        assertNotNull(parsed)
        assertEquals(listOf("MORNING", "EVENING"), parsed!!.shifts.map { it.type })
        assertEquals(420, parsed.shifts[0].startMinute)
        assertEquals(600_000L, parsed.rules.earlyStartMs)
    }

    @Test
    fun `a malformed server config never replaces the schedule`() {
        fun parse(s: String) = ShiftSchedule.parse(JSONObject(s))
        // missing a shift
        assertNull(parse("""{"timezone":"Asia/Kolkata","shifts":[{"type":"MORNING","startMinute":360,"endMinute":840}]}"""))
        // unknown time zone (the JDK silently maps these to GMT)
        assertNull(
            parse(
                """{"timezone":"Mars/Olympus","shifts":[
                {"type":"MORNING","startMinute":360,"endMinute":840},
                {"type":"EVENING","startMinute":840,"endMinute":1320}]}"""
            )
        )
        // zero-length shift
        assertNull(
            parse(
                """{"timezone":"Asia/Kolkata","shifts":[
                {"type":"MORNING","startMinute":360,"endMinute":360},
                {"type":"EVENING","startMinute":840,"endMinute":1320}]}"""
            )
        )
        // minute out of range
        assertNull(
            parse(
                """{"timezone":"Asia/Kolkata","shifts":[
                {"type":"MORNING","startMinute":-5,"endMinute":840},
                {"type":"EVENING","startMinute":840,"endMinute":1320}]}"""
            )
        )
        assertNull(ShiftSchedule.parse(null))
    }

    @Test
    fun `hours are labelled in the schedule's own clock`() {
        assertEquals("06:00 – 14:00", ShiftSchedule.hoursLabel(cfg.shifts[0]))
        assertEquals("14:00 – 22:00", ShiftSchedule.hoursLabel(cfg.shifts[1]))
        assertTrue(ShiftSchedule.clock(1440) == "00:00")
    }
}
