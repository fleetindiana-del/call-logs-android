package com.company.callmonitor

import org.json.JSONObject

data class ToggleLogEntry(
    val deviceId: String,
    val employeeName: String,
    val status: String,
    val timestamp: Long,
    /**
     * Why the status changed, when the phone knows. Carried through the offline
     * queue: an event queued with no signal used to reach the server later with its
     * reason stripped, which is exactly when the reason matters most.
     */
    val reason: String? = null
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("deviceId", deviceId)
        json.put("employeeName", employeeName)
        json.put("status", status)
        json.put("timestamp", timestamp)
        if (reason != null) json.put("reason", reason)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): ToggleLogEntry {
            return ToggleLogEntry(
                deviceId = json.optString("deviceId"),
                employeeName = json.optString("employeeName"),
                status = json.optString("status"),
                timestamp = json.optLong("timestamp"),
                reason = if (json.has("reason")) json.optString("reason").ifBlank { null } else null
            )
        }
    }
}
