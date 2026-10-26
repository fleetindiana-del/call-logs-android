package com.company.callmonitor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outbox is what makes the shift trail survive a closed app, a dead network and
 * a late reconnect. The properties worth pinning: events keep the time they
 * *happened*, resending is harmless, nothing leaves until acknowledged, and an
 * oversized queue sheds the least important events first.
 */
class ShiftOutboxTest {

    private fun ev(id: String, type: String = "TRACKING_INTERRUPTED", at: Long = 1_000L) =
        ShiftOutbox.event(id, "sh-12345678", type, at, JSONObject().put("cause", "LOCATION_SERVICES_OFF"))

    @Test
    fun `an empty outbox has nothing to send`() {
        assertEquals(0, ShiftOutbox.size(ShiftOutbox.EMPTY))
        assertTrue(ShiftOutbox.peek(ShiftOutbox.EMPTY, 10).isEmpty())
    }

    @Test
    fun `events are delivered in the order they happened, not the order they were queued`() {
        var box = ShiftOutbox.EMPTY
        box = ShiftOutbox.append(box, ev("e-00000003", at = 3_000))
        box = ShiftOutbox.append(box, ev("e-00000001", at = 1_000))
        box = ShiftOutbox.append(box, ev("e-00000002", at = 2_000))

        assertEquals(
            listOf("e-00000001", "e-00000002", "e-00000003"),
            ShiftOutbox.peek(box, 10).map { it.getString("id") }
        )
    }

    @Test
    fun `the occurrence time survives storage`() {
        val box = ShiftOutbox.append(ShiftOutbox.EMPTY, ev("e-00000001", at = 1_234_567L))
        assertEquals(1_234_567L, ShiftOutbox.peek(box, 1).single().getLong("occurredAt"))
    }

    @Test
    fun `queueing the same event twice keeps one copy`() {
        var box = ShiftOutbox.append(ShiftOutbox.EMPTY, ev("e-00000001"))
        box = ShiftOutbox.append(box, ev("e-00000001"))
        assertEquals(1, ShiftOutbox.size(box))
    }

    @Test
    fun `an event with no id is not queued`() {
        val bad = JSONObject().put("shiftId", "sh-12345678").put("type", "SHIFT_STARTED")
        assertEquals(0, ShiftOutbox.size(ShiftOutbox.append(ShiftOutbox.EMPTY, bad)))
    }

    @Test
    fun `only acknowledged events leave the queue`() {
        var box = ShiftOutbox.EMPTY
        for (i in 1..4) box = ShiftOutbox.append(box, ev("e-0000000$i", at = i * 1000L))

        box = ShiftOutbox.remove(box, setOf("e-00000001", "e-00000003"))

        assertEquals(listOf("e-00000002", "e-00000004"), ShiftOutbox.peek(box, 10).map { it.getString("id") })
    }

    @Test
    fun `a batch is limited and the rest waits`() {
        var box = ShiftOutbox.EMPTY
        for (i in 1..5) box = ShiftOutbox.append(box, ev("e-0000000$i", at = i * 1000L))
        assertEquals(2, ShiftOutbox.peek(box, 2).size)
        assertEquals(5, ShiftOutbox.size(box))
    }

    @Test
    fun `a corrupted store is treated as empty rather than crashing`() {
        assertEquals(0, ShiftOutbox.size("not json"))
        val box = ShiftOutbox.append("not json", ev("e-00000001"))
        assertEquals(1, ShiftOutbox.size(box))
    }

    @Test
    fun `an overfull queue sheds routine events before shift starts and ends`() {
        var box = ShiftOutbox.append(ShiftOutbox.EMPTY, ev("start-0001", "SHIFT_STARTED", at = 1))
        for (i in 1..ShiftOutbox.MAX_EVENTS) {
            box = ShiftOutbox.append(box, ev("noise-%05d".format(i), "CONNECTIVITY_LOST", at = 10L + i))
        }
        box = ShiftOutbox.append(box, ev("end-00001", "SHIFT_ENDED", at = 999_999))

        val ids = ShiftOutbox.peek(box, Int.MAX_VALUE).map { it.getString("id") }
        assertEquals(ShiftOutbox.MAX_EVENTS, ids.size)
        assertTrue("shift start must survive", "start-0001" in ids)
        assertTrue("shift end must survive", "end-00001" in ids)
    }
}
