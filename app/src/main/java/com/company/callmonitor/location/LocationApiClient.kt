package com.company.callmonitor.location

import android.content.Context
import android.util.Log
import com.company.callmonitor.EnrollmentPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class BatchAck(
    val acceptedPointIds: List<String>,
    val duplicatePointIds: List<String>,
    val rejected: List<String>,
    val hasMore: Boolean
)

object LocationApiClient {

    private const val TAG = "LocationApiClient"

    /** POST /api/location/batch — returns ack or null on hard failure. */
    fun uploadBatch(context: Context, points: List<LocationPointEntity>): BatchAck? {
        val serverUrl = EnrollmentPrefs.getServerUrl(context)
        val deviceToken = EnrollmentPrefs.getDeviceToken(context)
        val deviceId = EnrollmentPrefs.getDeviceId(context)
        if (serverUrl.isBlank() || deviceToken.isBlank() || points.isEmpty()) return null

        return try {
            val sessionId = points.first().sessionId
            val array = JSONArray()
            for (p in points) {
                array.put(JSONObject().apply {
                    put("pointId", p.pointId)
                    put("sessionId", p.sessionId)
                    put("sequenceNumber", p.sequenceNumber)
                    put("latitude", p.latitude)
                    put("longitude", p.longitude)
                    put("accuracyMeters", p.accuracyMeters.toDouble())
                    put("speedMetersPerSecond", p.speedMetersPerSecond.toDouble())
                    put("bearingDegrees", p.bearingDegrees.toDouble())
                    put("altitudeMeters", p.altitudeMeters)
                    put("recordedAt", p.recordedAt)
                    put("batteryPercent", p.batteryPercent)
                    put("provider", p.provider)
                    put("isMockLocation", p.isMockLocation)
                })
            }
            val body = JSONObject().apply {
                put("requestId", UUID.randomUUID().toString())
                put("deviceId", deviceId)
                put("sessionId", sessionId)
                put("points", array)
            }.toString()

            val response = postForBody(serverUrl, "/api/location/batch", deviceToken, body) ?: return null
            val json = JSONObject(response)
            BatchAck(
                acceptedPointIds = jsonArrayToStrings(json.optJSONArray("acceptedPointIds")),
                duplicatePointIds = jsonArrayToStrings(json.optJSONArray("duplicatePointIds")),
                rejected = jsonArrayToStrings(json.optJSONArray("rejected")),
                hasMore = json.optBoolean("hasMore", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "uploadBatch failed: ${e.message}")
            null
        }
    }

    fun getCommands(context: Context): List<JSONObject> {
        val serverUrl = EnrollmentPrefs.getServerUrl(context)
        val deviceToken = EnrollmentPrefs.getDeviceToken(context)
        val deviceId = EnrollmentPrefs.getDeviceId(context)
        if (serverUrl.isBlank() || deviceToken.isBlank()) return emptyList()

        return try {
            val url = URL("${serverUrl.trimEnd('/')}/api/location/commands?deviceId=$deviceId")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $deviceToken")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            val code = conn.responseCode
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                val arr = json.optJSONArray("commands") ?: return emptyList()
                (0 until arr.length()).map { arr.getJSONObject(it) }
            } else {
                conn.disconnect()
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getCommands failed: ${e.message}")
            emptyList()
        }
    }

    fun updateCommandStatus(context: Context, commandId: String, status: String): Boolean {
        val serverUrl = EnrollmentPrefs.getServerUrl(context)
        val deviceToken = EnrollmentPrefs.getDeviceToken(context)
        if (serverUrl.isBlank() || deviceToken.isBlank()) return false
        val body = JSONObject().put("status", status).toString()
        return postForBody(serverUrl, "/api/location/commands/$commandId/status", deviceToken, body) != null
    }

    fun heartbeat(context: Context, trackingStatus: String, gpsEnabled: Boolean, pendingPoints: Int): Boolean {
        val serverUrl = EnrollmentPrefs.getServerUrl(context)
        val deviceToken = EnrollmentPrefs.getDeviceToken(context)
        if (serverUrl.isBlank() || deviceToken.isBlank()) return false
        val body = JSONObject().apply {
            put("trackingStatus", trackingStatus)
            put("gpsEnabled", gpsEnabled)
            put("pendingPoints", pendingPoints)
            put("appVersion", "1.0")
        }.toString()
        return postForBody(serverUrl, "/api/location/heartbeat", deviceToken, body) != null
    }

    /** POST /api/location/device/register — creates/updates fleet-map device row ASAP. */
    fun registerDevice(
        context: Context,
        trackingStatus: String = "TRACKING",
        gpsEnabled: Boolean = true,
        batteryPercent: Int? = null
    ): Boolean {
        val serverUrl = EnrollmentPrefs.getServerUrl(context)
        val deviceToken = EnrollmentPrefs.getDeviceToken(context)
        if (serverUrl.isBlank() || deviceToken.isBlank()) return false
        val body = JSONObject().apply {
            put("trackingStatus", trackingStatus)
            put("gpsEnabled", gpsEnabled)
            put("appVersion", "1.0")
            if (batteryPercent != null) put("batteryPercent", batteryPercent)
        }.toString()
        return postForBody(serverUrl, "/api/location/device/register", deviceToken, body) != null
    }

    /** POST /api/location/sessions/upsert — makes the device selectable in Route History. */
    fun upsertSession(
        context: Context,
        sessionId: String,
        status: String,
        startedAtMs: Long? = null,
        stoppedAtMs: Long? = null
    ): Boolean {
        val serverUrl = EnrollmentPrefs.getServerUrl(context)
        val deviceToken = EnrollmentPrefs.getDeviceToken(context)
        val deviceId = EnrollmentPrefs.getDeviceId(context)
        if (serverUrl.isBlank() || deviceToken.isBlank() || sessionId.isBlank()) return false
        val body = JSONObject().apply {
            put("sessionId", sessionId)
            put("deviceId", deviceId)
            put("employeeId", EnrollmentPrefs.getEmployeeId(context))
            put("vehicleId", EnrollmentPrefs.getVehicleId(context))
            put("status", status)
            if (startedAtMs != null) put("startedAt", startedAtMs)
            if (stoppedAtMs != null) put("stoppedAt", stoppedAtMs)
        }.toString()
        return postForBody(serverUrl, "/api/location/sessions/upsert", deviceToken, body) != null
    }

    private fun jsonArrayToStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            when (v) {
                is String -> out.add(v)
                is JSONObject -> out.add(v.optString("pointId", v.toString()))
                else -> out.add(v.toString())
            }
        }
        return out
    }

    private fun postForBody(serverUrl: String, path: String, token: String, body: String): String? {
        return try {
            val url = URL("${serverUrl.trimEnd('/')}$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            if (code in 200..299) text.ifBlank { "{}" } else null
        } catch (e: Exception) {
            Log.e(TAG, "POST $path failed: ${e.message}")
            null
        }
    }
}
