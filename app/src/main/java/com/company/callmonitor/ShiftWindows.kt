package com.company.callmonitor

import org.json.JSONArray
import org.json.JSONObject

/**
 * The shift history, as pure arithmetic over a JSON string.
 *
 * Split out from [ShiftManager] because this is the part that decides whether a
 * given call is collected or discarded forever, and it is worth being able to
 * test that decision directly rather than through SharedPreferences and a phone.
 *
 * A window is `{"start": <ms>, "end": <ms>}`; a missing or non-positive `end`
 * means the shift is still running.
 */
object ShiftWindows {

    /**
     * How far back the history reaches. Comfortably longer than the two-day
     * sweep [PeriodicSyncManager] performs and the 24-hour default look-back in
     * [CallBackfillManager], so a phone that was off for a weekend can still
     * place its calls inside or outside a shift.
     */
    const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    const val EMPTY = "[]"

    /**
     * True when [timestampMs] falls inside a recorded shift.
     *
     * Unknown history answers false: a call that cannot be placed inside a shift
     * is not collected. Failing the other way would hand the server exactly the
     * off-shift calls the gate exists to keep out.
     */
    fun contains(json: String, timestampMs: Long): Boolean {
        for (window in parse(json)) {
            val start = window.optLong("start", 0L)
            if (start <= 0L || timestampMs < start) continue
            val end = window.optLong("end", 0L)
            if (end <= 0L || timestampMs <= end) return true
        }
        return false
    }

    /**
     * Start of the oldest retained window, so a collector can narrow its query
     * before filtering call by call. [Long.MAX_VALUE] when there is no history,
     * which reads as "no window to collect".
     */
    fun earliestStart(json: String): Long {
        var earliest = Long.MAX_VALUE
        for (window in parse(json)) {
            val start = window.optLong("start", 0L)
            if (start > 0L && start < earliest) earliest = start
        }
        return earliest
    }

    /**
     * Begin a window at [at].
     *
     * Any window still open is closed at its own start first. An open window is
     * only ever left behind by a crash or a wipe between turning the shift on
     * and off, and left alone it would silently cover every off-shift hour
     * since — the one failure mode of this design that leaks data.
     */
    fun open(json: String, at: Long): String {
        val kept = prune(parse(json), at)
        for (window in kept) {
            if (window.optLong("end", 0L) <= 0L) window.put("end", window.optLong("start", at))
        }
        kept.add(JSONObject().put("start", at))
        return write(kept)
    }

    /** Close the open window at [at]. A no-op when none is open. */
    fun close(json: String, at: Long): String {
        val kept = prune(parse(json), at)
        for (window in kept) {
            if (window.optLong("end", 0L) <= 0L) window.put("end", at)
        }
        return write(kept)
    }

    private fun parse(json: String): List<JSONObject> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun prune(windows: List<JSONObject>, now: Long): MutableList<JSONObject> {
        val cutoff = now - RETENTION_MS
        return windows.filter { window ->
            val end = window.optLong("end", 0L)
            // Keep an open window regardless of age: the shift is still running.
            end <= 0L || end >= cutoff
        }.toMutableList()
    }

    private fun write(windows: List<JSONObject>): String {
        val array = JSONArray()
        for (window in windows) array.put(window)
        return array.toString()
    }
}
