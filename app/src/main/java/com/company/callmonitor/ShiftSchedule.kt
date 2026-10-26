package com.company.callmonitor

import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * The two predefined shifts and the rules around them, as the phone sees them.
 *
 * This mirrors `web/src/lib/shiftConfig.ts` — same windows, same arithmetic — so a
 * driver is offered exactly the shifts the server will accept, and the phone ends a
 * shift at the same instant the server would. It is pure (no Context, no network)
 * so the arithmetic can be unit-tested on the JVM.
 *
 * The server is the source of truth: every upload response carries the effective
 * schedule, which [ShiftEventReporter] caches and [parse]s. [DEFAULT] is only used
 * until the first contact, and matches the server's defaults.
 */
object ShiftSchedule {

    const val MORNING = "MORNING"
    const val EVENING = "EVENING"

    private const val MINUTE = 60_000L

    data class Def(val type: String, val label: String, val startMinute: Int, val endMinute: Int)

    data class Rules(
        /** A driver may switch a shift on this long before its scheduled start. */
        val earlyStartMs: Long,
        /** After the scheduled end the shift is closed; tracking must not continue indefinitely. */
        val endGraceMs: Long,
        val heartbeatIntervalMs: Long,
        val staleAfterMs: Long,
        val autoEndAfterInterruptionMs: Long
    )

    data class Config(val timezone: String, val shifts: List<Def>, val rules: Rules)

    /** One concrete occurrence of a shift on the calendar, in UTC milliseconds. */
    data class Window(val type: String, val label: String, val startMs: Long, val endMs: Long)

    val DEFAULT_RULES = Rules(
        earlyStartMs = 30 * MINUTE,
        endGraceMs = 15 * MINUTE,
        heartbeatIntervalMs = 5 * MINUTE,
        staleAfterMs = 10 * MINUTE,
        autoEndAfterInterruptionMs = 60 * MINUTE
    )

    val DEFAULT = Config(
        timezone = "Asia/Kolkata",
        shifts = listOf(
            Def(MORNING, "Morning shift", 6 * 60, 14 * 60),
            Def(EVENING, "Evening shift", 14 * 60, 22 * 60)
        ),
        rules = DEFAULT_RULES
    )

    // ── Server config ────────────────────────────────────────────────────────

    /**
     * Reads the `config` object the server publishes. Returns null for anything
     * malformed so a bad payload can never replace a good cached schedule — a
     * phone that mis-parsed its hours would offer the wrong shifts.
     */
    fun parse(json: JSONObject?): Config? {
        if (json == null) return null
        return try {
            val tz = json.getString("timezone")
            if (!isKnownZone(tz)) return null

            val arr = json.getJSONArray("shifts")
            val defs = ArrayList<Def>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val start = o.getInt("startMinute")
                val end = o.getInt("endMinute")
                if (start !in 0..1439 || end !in 0..1440 || start == end) return null
                defs.add(Def(o.getString("type"), o.optString("label", o.getString("type")), start, end))
            }
            val types = defs.map { it.type }.toSet()
            if (defs.size != 2 || types != setOf(MORNING, EVENING)) return null

            val r = json.optJSONObject("rules")
            val rules = if (r == null) DEFAULT_RULES else Rules(
                earlyStartMs = r.optLong("earlyStartMs", DEFAULT_RULES.earlyStartMs).coerceAtLeast(0),
                endGraceMs = r.optLong("endGraceMs", DEFAULT_RULES.endGraceMs).coerceAtLeast(0),
                heartbeatIntervalMs = r.optLong("heartbeatIntervalMs", DEFAULT_RULES.heartbeatIntervalMs).coerceAtLeast(MINUTE),
                staleAfterMs = r.optLong("staleAfterMs", DEFAULT_RULES.staleAfterMs),
                autoEndAfterInterruptionMs = r.optLong(
                    "autoEndAfterInterruptionMs", DEFAULT_RULES.autoEndAfterInterruptionMs
                )
            )
            Config(tz, defs.sortedBy { if (it.type == MORNING) 0 else 1 }, rules)
        } catch (e: Exception) {
            null
        }
    }

    private fun isKnownZone(id: String): Boolean =
        id == "GMT" || id == "UTC" || TimeZone.getTimeZone(id).id != "GMT"

    // ── Windows ──────────────────────────────────────────────────────────────

    /** The occurrence of [def] that begins on the local calendar day y-m-d. */
    fun windowOnDay(def: Def, y: Int, m: Int, d: Int, tz: TimeZone): Window {
        val start = localToUtc(y, m, d, def.startMinute, tz)
        val overnight = def.endMinute <= def.startMinute
        val end = localToUtc(y, m, if (overnight) d + 1 else d, def.endMinute, tz)
        return Window(def.type, def.label, start, end)
    }

    /** Yesterday's, today's and tomorrow's occurrence of every shift. */
    private fun candidates(at: Long, config: Config): List<Window> {
        val tz = TimeZone.getTimeZone(config.timezone)
        val cal = Calendar.getInstance(tz).apply { timeInMillis = at }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val out = ArrayList<Window>()
        for (offset in -1..1) for (def in config.shifts) out.add(windowOnDay(def, y, m, d + offset, tz))
        return out
    }

    /**
     * Shifts a driver can switch on at [at]: from the early-start allowance before
     * the scheduled start until the scheduled end. Around a handover both can be
     * eligible, which is why the UI lets the driver choose.
     */
    fun eligibleAt(config: Config, at: Long): List<Window> =
        candidates(at, config)
            .filter { at >= it.startMs - config.rules.earlyStartMs && at < it.endMs }
            .sortedBy { it.startMs }

    /** The occurrence of shift [type] closest to [at], eligible or not. */
    fun nearest(config: Config, type: String, at: Long): Window =
        candidates(at, config).filter { it.type == type }
            .minByOrNull { Math.abs(it.startMs - at) }!!

    /**
     * The window a shift of [type] started at [startedAt] belongs to. Prefers the
     * occurrence it was actually eligible for, otherwise the nearest one.
     */
    fun windowForStart(config: Config, type: String, startedAt: Long): Window {
        val ofType = candidates(startedAt, config).filter { it.type == type }
        return ofType.firstOrNull {
            startedAt >= it.startMs - config.rules.earlyStartMs && startedAt < it.endMs
        } ?: ofType.minByOrNull { Math.abs(it.startMs - startedAt) }!!
    }

    /** When the phone closes an unfinished shift: scheduled end plus grace. */
    fun hardEnd(window: Window, rules: Rules): Long = window.endMs + rules.endGraceMs

    // ── Display ──────────────────────────────────────────────────────────────

    /** "06:00 – 14:00" for a definition, in the schedule's own time zone. */
    fun hoursLabel(def: Def): String = "${clock(def.startMinute)} – ${clock(def.endMinute)}"

    fun clock(minuteOfDay: Int): String {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }

    // ── Time-zone arithmetic ─────────────────────────────────────────────────

    /**
     * UTC instant of [minutes] after local midnight on y-m-d. Day overflow is
     * allowed (1440 is the next midnight, day 32 rolls the month).
     *
     * The wall-clock fields are set rather than adding elapsed minutes to midnight:
     * on the day clocks change, "04:00" is not 240 minutes after midnight.
     */
    internal fun localToUtc(y: Int, m: Int, d: Int, minutes: Int, tz: TimeZone): Long {
        val cal = Calendar.getInstance(tz)
        cal.clear()
        cal.isLenient = true
        cal.set(y, m - 1, d, minutes / 60, minutes % 60, 0)
        return cal.timeInMillis
    }
}
