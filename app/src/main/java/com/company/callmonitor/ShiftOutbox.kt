package com.company.callmonitor

import org.json.JSONArray
import org.json.JSONObject

/**
 * The persistent queue of shift events waiting to reach the server.
 *
 * Pure functions over a JSON string, like [ShiftWindows], so the queue discipline
 * is unit-testable and the Android layer ([ShiftEventReporter]) only has to store
 * and ship the string.
 *
 * Why a dedicated outbox rather than the existing status queue: shift events are
 * an audit trail that must be complete and idempotent. Each carries its own id, so
 * resending after an unclear failure is harmless (the server de-duplicates), and an
 * event is only removed once the server has acknowledged it. Time of occurrence is
 * the phone's own clock at the moment it happened — never the moment of upload — so
 * an hour spent offline still lands in the right place in the log.
 */
object ShiftOutbox {

    const val EMPTY = "[]"

    /**
     * Upper bound so a phone offline for weeks cannot grow without limit. When it is
     * exceeded the chattiest, least consequential events go first; shift starts and
     * ends are never evicted while anything else can be.
     */
    const val MAX_EVENTS = 2000

    private val PROTECTED_TYPES = setOf("SHIFT_STARTED", "SHIFT_ENDED")

    fun event(
        id: String,
        shiftId: String,
        type: String,
        occurredAt: Long,
        payload: JSONObject = JSONObject()
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("shiftId", shiftId)
        .put("type", type)
        .put("occurredAt", occurredAt)
        .put("payload", payload)

    private fun parse(json: String): JSONArray =
        try { JSONArray(json) } catch (e: Exception) { JSONArray() }

    private fun objects(arr: JSONArray): List<JSONObject> =
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

    fun size(json: String): Int = parse(json).length()

    /** Appends [event]; an event whose id is already queued is ignored. */
    fun append(json: String, event: JSONObject): String {
        val list = objects(parse(json)).toMutableList()
        val id = event.optString("id")
        if (id.isEmpty() || list.any { it.optString("id") == id }) return toJson(list)
        list.add(event)

        var overflow = list.size - MAX_EVENTS
        if (overflow > 0) {
            val it = list.iterator()
            while (it.hasNext() && overflow > 0) {
                if (it.next().optString("type") !in PROTECTED_TYPES) {
                    it.remove()
                    overflow--
                }
            }
            while (overflow > 0 && list.isNotEmpty()) {
                list.removeAt(0)
                overflow--
            }
        }
        return toJson(list)
    }

    /** The oldest [limit] events, in the order they happened. */
    fun peek(json: String, limit: Int): List<JSONObject> =
        objects(parse(json)).sortedBy { it.optLong("occurredAt") }.take(limit)

    /** Drops every event whose id is in [ids] (acknowledged or permanently rejected). */
    fun remove(json: String, ids: Set<String>): String {
        if (ids.isEmpty()) return json
        return toJson(objects(parse(json)).filter { it.optString("id") !in ids })
    }

    private fun toJson(list: List<JSONObject>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }
}
