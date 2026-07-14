package com.company.callmonitor

import org.json.JSONObject

data class ToggleLogEntry(
    val deviceId: String,
    val employeeName: String,
    val status: String,
    val timestamp: Long
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("deviceId", deviceId)
        json.put("employeeName", employeeName)
        json.put("status", status)
        json.put("timestamp", timestamp)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): ToggleLogEntry {
            return ToggleLogEntry(
                deviceId = json.optString("deviceId"),
                employeeName = json.optString("employeeName"),
                status = json.optString("status"),
                timestamp = json.optLong("timestamp")
            )
        }
    }
}
