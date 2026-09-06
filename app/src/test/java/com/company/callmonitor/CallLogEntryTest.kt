package com.company.callmonitor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: call payload JSON shape must stay stable for /api/calls.
 */
class CallLogEntryTest {

    @Test
    fun toJson_containsRequiredFields() {
        val entry = CallLogEntry(
            phoneNumber = "+919999999999",
            callType = "OUTGOING",
            duration = 65,
            timestamp = 1_700_000_000_000L,
            deviceId = "device-1",
            employeeName = "Ramesh",
            contactName = "Office"
        )
        val json = entry.toJson()
        assertEquals("+919999999999", json.getString("phoneNumber"))
        assertEquals("OUTGOING", json.getString("callType"))
        assertEquals(65L, json.getLong("duration"))
        assertEquals(1_700_000_000_000L, json.getLong("timestamp"))
        assertEquals("device-1", json.getString("deviceId"))
        assertEquals("Ramesh", json.getString("employeeName"))
        assertEquals("Office", json.getString("contactName"))
    }

    @Test
    fun fromJson_roundTrip() {
        val original = CallLogEntry(
            phoneNumber = "100",
            callType = "MISSED",
            duration = 0,
            timestamp = 42L,
            deviceId = "d2",
            employeeName = "Emp",
            contactName = "Unknown"
        )
        val restored = CallLogEntry.fromJson(original.toJson())
        assertEquals(original.phoneNumber, restored.phoneNumber)
        assertEquals(original.callType, restored.callType)
        assertEquals(original.duration, restored.duration)
        assertEquals(original.timestamp, restored.timestamp)
        assertEquals(original.deviceId, restored.deviceId)
        assertEquals(original.employeeName, restored.employeeName)
        assertEquals(original.contactName, restored.contactName)
    }

    @Test
    fun fromJson_defaultsMissingContactName() {
        val json = JSONObject()
            .put("phoneNumber", "1")
            .put("callType", "INCOMING")
            .put("duration", 1)
            .put("timestamp", 2)
            .put("deviceId", "d")
            .put("employeeName", "e")
        val entry = CallLogEntry.fromJson(json)
        assertEquals("Unknown", entry.contactName)
    }
}
