package com.company.callmonitor

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class CallLogEntry(
    val phoneNumber: String,
    val callType: String,
    val duration: Long,
    val timestamp: Long,
    val deviceId: String,
    val employeeName: String,
    val contactName: String
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("phoneNumber", phoneNumber)
        json.put("callType", callType)
        json.put("duration", duration)
        json.put("timestamp", timestamp)
        json.put("deviceId", deviceId)
        json.put("employeeName", employeeName)
        json.put("contactName", contactName)
        return json
    }
    
    companion object {
        fun fromJson(json: JSONObject): CallLogEntry {
            return CallLogEntry(
                phoneNumber = json.optString("phoneNumber"),
                callType = json.optString("callType"),
                duration = json.optLong("duration"),
                timestamp = json.optLong("timestamp"),
                deviceId = json.optString("deviceId"),
                employeeName = json.optString("employeeName"),
                contactName = json.optString("contactName", "Unknown")
            )
        }
    }
}

/** Outcome of a single call-sync attempt. */
enum class SyncResult {
    /** Server accepted the call (2xx). */
    SUCCESS,
    /** 4xx response — bad payload, duplicate, auth issue. Retrying won't help. */
    PERMANENT_FAILURE,
    /** 5xx or network error — transient, worth retrying later. */
    TRANSIENT_FAILURE
}

object ApiClient {

    fun syncCall(
        serverUrl: String,
        apiKey: String,
        callLog: CallLogEntry,
        context: android.content.Context? = null
    ): SyncResult {
        if (serverUrl.isBlank()) return SyncResult.PERMANENT_FAILURE

        return try {
            var formattedUrl = serverUrl.trimEnd('/')
            if (!formattedUrl.startsWith("http")) {
                formattedUrl = "https://$formattedUrl"
            }
            val url = URL("$formattedUrl/api/calls")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-API-Key", apiKey)
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.doOutput = true

            val out = OutputStreamWriter(connection.outputStream, "UTF-8")
            out.write(callLog.toJson().toString())
            out.flush()
            out.close()

            val responseCode = connection.responseCode

            if (responseCode in 200..299) {
                SyncResult.SUCCESS
            } else {
                val body = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "(no body)"
                } catch (e: Exception) { "(unreadable)" }

                val msg = "❌ syncCall HTTP $responseCode — body: $body"
                context?.let { Logger.log(it, msg) }
                android.util.Log.e("ApiClient", "syncCall HTTP $responseCode for ${callLog.phoneNumber} @ ${callLog.timestamp} — $body")

                // 4xx = permanent (bad data / duplicate / auth). 5xx = transient.
                if (responseCode in 400..499) SyncResult.PERMANENT_FAILURE
                else SyncResult.TRANSIENT_FAILURE
            }
        } catch (e: Exception) {
            val msg = "syncCall network error for ${callLog.phoneNumber}: ${e.javaClass.simpleName}: ${e.message}"
            context?.let { Logger.log(it, "❌ $msg") }
            android.util.Log.e("ApiClient", msg)
            SyncResult.TRANSIENT_FAILURE
        }
    }


    fun testConnection(serverUrl: String, apiKey: String): Boolean {
        if (serverUrl.isBlank()) return false

        return try {
            var formattedUrl = serverUrl.trimEnd('/')
            if (!formattedUrl.startsWith("http")) {
                formattedUrl = "https://$formattedUrl"
            }
            val url = URL("$formattedUrl/api/health")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("X-API-Key", apiKey)
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            val responseCode = connection.responseCode
            responseCode in 200..299
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun syncContacts(serverUrl: String, apiKey: String, contacts: List<ContactEntry>, context: android.content.Context? = null): Boolean {
        if (serverUrl.isBlank()) {
            context?.let { Logger.log(it, "⚠️ syncContacts: serverUrl is blank") }
            return false
        }
        if (contacts.isEmpty()) {
            context?.let { Logger.log(it, "⚠️ syncContacts: no contacts to sync") }
            return false
        }

        return try {
            var formattedUrl = serverUrl.trimEnd('/')
            if (!formattedUrl.startsWith("http")) {
                formattedUrl = "https://$formattedUrl"
            }
            val url = URL("$formattedUrl/api/contacts")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-API-Key", apiKey)
            connection.connectTimeout = 60000
            connection.readTimeout = 60000
            connection.doOutput = true

            val jsonArray = org.json.JSONArray()
            for (c in contacts) {
                jsonArray.put(c.toJson())
            }

            val payload = JSONObject()
            payload.put("contacts", jsonArray)

            val out = OutputStreamWriter(connection.outputStream, "UTF-8")
            out.write(payload.toString())
            out.flush()
            out.close()

            val responseCode = connection.responseCode
            val success = responseCode in 200..299

            if (!success) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "no body"
                } catch (e: Exception) { "unreadable" }
                context?.let {
                    Logger.log(it, "❌ syncContacts failed: HTTP $responseCode — $errorBody")
                }
                android.util.Log.e("ApiClient", "syncContacts HTTP $responseCode at $formattedUrl/api/contacts — $errorBody")
            }
            success
        } catch (e: Exception) {
            context?.let {
                Logger.log(it, "❌ syncContacts error: ${e.javaClass.simpleName}: ${e.message}")
            }
            android.util.Log.e("ApiClient", "syncContacts exception: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Sends a message to the bot-logs API so it appears in the dashboard.
     * Used when call sync keeps failing so admins see the issue.
     */
    fun sendBotLog(
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        employeeName: String,
        level: String,
        step: String,
        message: String,
        data: Map<String, Any>? = null,
        context: android.content.Context? = null
    ): Boolean {
        if (serverUrl.isBlank()) {
            context?.let { Logger.log(it, "⚠️ sendBotLog: serverUrl is blank") }
            return false
        }
        return try {
            var formattedUrl = serverUrl.trimEnd('/')
            if (!formattedUrl.startsWith("http")) {
                formattedUrl = "https://$formattedUrl"
            }
            val url = URL("$formattedUrl/api/bot-logs")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-API-Key", apiKey)
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true

            val json = JSONObject()
            json.put("deviceId", deviceId)
            json.put("employeeName", employeeName)
            json.put("level", level)
            json.put("step", step)
            json.put("message", message)
            if (data != null) {
                val dataObj = JSONObject()
                for ((k, v) in data) {
                    when (v) {
                        is Number -> dataObj.put(k, v)
                        is String -> dataObj.put(k, v)
                        is Boolean -> dataObj.put(k, v)
                        else -> dataObj.put(k, v.toString())
                    }
                }
                json.put("data", dataObj)
            }

            val out = OutputStreamWriter(connection.outputStream, "UTF-8")
            out.write(json.toString())
            out.flush()
            out.close()

            val responseCode = connection.responseCode
            val success = responseCode in 200..299
            if (!success) {
                context?.let { Logger.log(it, "❌ sendBotLog failed: HTTP $responseCode") }
                android.util.Log.e("ApiClient", "sendBotLog HTTP $responseCode at $formattedUrl/api/bot-logs")
            }
            success
        } catch (e: Exception) {
            context?.let { Logger.log(it, "❌ sendBotLog error: ${e.javaClass.simpleName}: ${e.message}") }
            android.util.Log.e("ApiClient", "sendBotLog exception: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Batch-upload in-app log lines to the dashboard (POST /api/device-app-logs).
     * @param entries JSONArray of objects: { "message": string, "recordedAt": long }
     */
    fun uploadDeviceLogs(
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        employeeName: String,
        entries: org.json.JSONArray,
        context: android.content.Context? = null
    ): Boolean {
        if (serverUrl.isBlank() || entries.length() == 0) return false
        return try {
            var formattedUrl = serverUrl.trimEnd('/')
            if (!formattedUrl.startsWith("http")) {
                formattedUrl = "https://$formattedUrl"
            }
            val url = URL("$formattedUrl/api/device-app-logs")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-API-Key", apiKey)
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.doOutput = true

            val payload = JSONObject()
            payload.put("deviceId", deviceId)
            payload.put("employeeName", employeeName)
            payload.put("entries", entries)

            val out = OutputStreamWriter(connection.outputStream, "UTF-8")
            out.write(payload.toString())
            out.flush()
            out.close()

            val responseCode = connection.responseCode
            val success = responseCode in 200..299
            if (!success) {
                context?.let {
                    Logger.log(it, "❌ uploadDeviceLogs failed: HTTP $responseCode")
                }
                android.util.Log.e(
                    "ApiClient",
                    "uploadDeviceLogs HTTP $responseCode at $formattedUrl/api/device-app-logs"
                )
            }
            success
        } catch (e: Exception) {
            context?.let {
                Logger.log(it, "❌ uploadDeviceLogs error: ${e.javaClass.simpleName}: ${e.message}")
            }
            android.util.Log.e("ApiClient", "uploadDeviceLogs: ${e.message}")
            false
        }
    }

    fun sendStatus(serverUrl: String, apiKey: String, deviceId: String, employeeName: String, status: String, timestamp: Long? = null, context: android.content.Context? = null, reason: String? = null): Boolean {
        if (serverUrl.isBlank()) {
            context?.let { Logger.log(it, "⚠️ sendStatus: serverUrl is blank") }
            return false
        }
        
        return try {
            var formattedUrl = serverUrl.trimEnd('/')
            if (!formattedUrl.startsWith("http")) {
                formattedUrl = "https://$formattedUrl"
            }
            val url = URL("$formattedUrl/api/status")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-API-Key", apiKey)
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true

            val json = JSONObject()
            json.put("deviceId", deviceId)
            json.put("employeeName", employeeName)
            json.put("status", status)
            json.put("timestamp", timestamp ?: System.currentTimeMillis())
            if (reason != null) json.put("reason", reason)

            val out = OutputStreamWriter(connection.outputStream, "UTF-8")
            out.write(json.toString())
            out.flush()
            out.close()

            val responseCode = connection.responseCode
            val success = responseCode in 200..299
            if (!success) {
                context?.let { Logger.log(it, "❌ sendStatus failed: server returned HTTP $responseCode (URL: $formattedUrl/api/status)") }
                android.util.Log.e("ApiClient", "sendStatus HTTP $responseCode at $formattedUrl/api/status")
            }
            success
        } catch (e: Exception) {
            context?.let { Logger.log(it, "❌ sendStatus error: ${e.javaClass.simpleName}: ${e.message}") }
            android.util.Log.e("ApiClient", "sendStatus exception: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Registers the device's FCM token with the backend so it can send
     * high-priority push notifications to wake the app when sync is stale.
     */
    fun registerFcmToken(
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        fcmToken: String,
        context: android.content.Context? = null
    ): Boolean {
        if (serverUrl.isBlank()) return false
        return try {
            var formattedUrl = serverUrl.trimEnd('/')
            if (!formattedUrl.startsWith("http")) {
                formattedUrl = "https://$formattedUrl"
            }
            val url = URL("$formattedUrl/api/fcm-token")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-API-Key", apiKey)
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true

            val json = JSONObject()
            json.put("deviceId", deviceId)
            json.put("token", fcmToken)

            val out = OutputStreamWriter(connection.outputStream, "UTF-8")
            out.write(json.toString())
            out.flush()
            out.close()

            val responseCode = connection.responseCode
            val success = responseCode in 200..299
            if (!success) {
                context?.let { Logger.log(it, "❌ registerFcmToken failed: HTTP $responseCode") }
            }
            success
        } catch (e: Exception) {
            context?.let { Logger.log(it, "❌ registerFcmToken error: ${e.javaClass.simpleName}: ${e.message}") }
            false
        }
    }
}
