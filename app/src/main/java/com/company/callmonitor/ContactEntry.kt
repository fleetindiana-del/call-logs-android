package com.company.callmonitor

import org.json.JSONObject

data class ContactEntry(
    val deviceId: String,
    val employeeName: String,
    val contactName: String,
    val phoneNumber: String,
    val timestamp: Long
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("deviceId", deviceId)
        json.put("employeeName", employeeName)
        json.put("contactName", contactName)
        json.put("phoneNumber", phoneNumber)
        json.put("timestamp", timestamp)
        return json
    }
}
