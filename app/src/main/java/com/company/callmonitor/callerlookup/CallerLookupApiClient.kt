package com.company.callmonitor.callerlookup

import android.content.Context
import android.util.Log
import com.company.callmonitor.EnrollmentPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class RemoteCallerLookupJob(
    val id: String,
    val status: String,
    val requestedAction: String?,
    val deviceId: String,
    val mobileProvider: String,
    val seriesId: String,
    val seriesPrefix: String,
    val seriesLabel: String,
    val startNumber: String,
    val endNumber: String,
    val totalPlanned: Int,
    val delayMs: Long,
    val workers: Int,
    val lookupProviderId: String,
    val maxRetries: Int,
    val cursorIndex: Int,
    val processed: Int,
    val successful: Int,
    val failed: Int,
    val totalLookupDurationMs: Long,
    val currentNumber: String?
)

data class ApiResult<T>(
    val value: T? = null,
    val error: String? = null
) {
    val isOk get() = value != null && error == null
}

object CallerLookupApiClient {
    private const val TAG = "CallerLookupApi"

    fun createJob(
        context: Context,
        series: NumberSeries,
        batchSize: Int,
        startNumber: String? = null,
        endNumber: String? = null,
        delayMs: Long = 200,
        workers: Int = 1,
        maxRetries: Int = 2,
        lookupProviderId: String = "android-call-log-cache"
    ): ApiResult<RemoteCallerLookupJob> {
        val body = JSONObject().apply {
            put("mobileProvider", series.provider)
            put("seriesId", series.id)
            put("seriesPrefix", series.prefix)
            put("seriesLabel", series.label)
            put("batchSize", batchSize)
            put("delayMs", delayMs)
            put("workers", workers)
            put("maxRetries", maxRetries)
            put("lookupProviderId", lookupProviderId)
            if (!startNumber.isNullOrBlank()) put("startNumber", startNumber)
            if (!endNumber.isNullOrBlank()) put("endNumber", endNumber)
        }
        val response = request(context, "POST", "/api/caller-lookup/device/jobs", body.toString())
        if (!response.isOk) {
            return ApiResult(error = response.error ?: "Job creation failed")
        }
        return try {
            ApiResult(value = parseJob(JSONObject(response.value!!).getJSONObject("job")))
        } catch (e: Exception) {
            Log.e(TAG, "Invalid create job response: ${e.message}")
            ApiResult(error = "Invalid server response when creating job")
        }
    }

    fun controlJob(context: Context, jobId: String, action: String): Boolean {
        val body = JSONObject().put("action", action)
        return request(
            context,
            "POST",
            "/api/caller-lookup/device/jobs/$jobId/control",
            body.toString()
        ).isOk
    }

    fun getLatestJob(context: Context): RemoteCallerLookupJob? {
        val response = request(context, "GET", "/api/caller-lookup/device/jobs/latest")
        if (!response.isOk) return null
        return try {
            val json = JSONObject(response.value!!)
            if (json.isNull("job")) null else parseJob(json.getJSONObject("job"))
        } catch (e: Exception) {
            Log.e(TAG, "Invalid latest job response: ${e.message}")
            null
        }
    }

    fun getPendingJobs(context: Context): List<RemoteCallerLookupJob>? {
        val response = request(context, "GET", "/api/caller-lookup/device/jobs")
        if (!response.isOk) return null
        return try {
            val array = JSONObject(response.value!!).optJSONArray("jobs") ?: JSONArray()
            (0 until array.length()).map { parseJob(array.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.e(TAG, "Invalid pending jobs response: ${e.message}")
            null
        }
    }

    fun getJob(context: Context, jobId: String): RemoteCallerLookupJob? {
        val response = request(context, "GET", "/api/caller-lookup/device/jobs/$jobId")
        if (!response.isOk) return null
        return try {
            parseJob(JSONObject(response.value!!).getJSONObject("job"))
        } catch (e: Exception) {
            Log.e(TAG, "Invalid job response: ${e.message}")
            null
        }
    }

    fun updateStatus(
        context: Context,
        jobId: String,
        status: String,
        errorMessage: String? = null
    ): Boolean {
        val body = JSONObject().put("status", status)
        if (!errorMessage.isNullOrBlank()) body.put("errorMessage", errorMessage)
        return request(
            context,
            "POST",
            "/api/caller-lookup/device/jobs/$jobId/status",
            body.toString()
        ).isOk
    }

    fun uploadProgress(context: Context, job: CallerLookupJobEntity): Boolean {
        val body = JSONObject().apply {
            put("cursorIndex", job.cursorIndex)
            put("processed", job.processed)
            put("successful", job.successful)
            put("failed", job.failed)
            put("totalLookupDurationMs", job.totalLookupDurationMs)
            put("totalPlanned", job.totalPlanned)
            put("currentNumber", job.currentNumber ?: JSONObject.NULL)
        }
        return request(
            context,
            "POST",
            "/api/caller-lookup/device/jobs/${job.jobId}/progress",
            body.toString()
        ).isOk
    }

    fun uploadResults(
        context: Context,
        job: CallerLookupJobEntity,
        results: List<CallerLookupResultEntity>
    ): Boolean {
        if (results.isEmpty()) return true
        val array = JSONArray()
        results.forEach { result ->
            array.put(JSONObject().apply {
                put("phoneNumber", result.phoneNumber)
                put("callerName", result.callerName ?: JSONObject.NULL)
                put("lookupStatus", result.lookupStatus)
                put("provider", result.provider)
                put("lookupProviderId", result.lookupProviderId)
                putJsonOrNull("metadata", result.metadataJson)
                putJsonOrNull("kyc", result.kycJson)
                putJsonOrNull("rawResponse", result.rawResponseJson)
                put("durationMs", result.durationMs)
                put("retryCount", result.retryCount)
                put("error", result.error ?: JSONObject.NULL)
                put("lookedUpAt", result.lookedUpAt)
            })
        }
        return request(
            context,
            "POST",
            "/api/caller-lookup/device/jobs/${job.jobId}/results",
            JSONObject().put("results", array).toString()
        ).isOk
    }

    fun uploadLogs(
        context: Context,
        jobId: String,
        logs: List<CallerLookupLogEntity>
    ): Boolean {
        if (logs.isEmpty()) return true
        val array = JSONArray()
        logs.forEach { log ->
            array.put(JSONObject().apply {
                put("level", log.level)
                put("message", log.message)
                put("phoneNumber", log.phoneNumber ?: JSONObject.NULL)
                put("durationMs", log.durationMs ?: JSONObject.NULL)
                putJsonOrNull("details", log.detailsJson)
                put("occurredAt", log.occurredAt)
            })
        }
        return request(
            context,
            "POST",
            "/api/caller-lookup/device/jobs/$jobId/logs",
            JSONObject().put("logs", array).toString()
        ).isOk
    }

    private fun JSONObject.putJsonOrNull(key: String, raw: String?) {
        if (raw.isNullOrBlank()) put(key, JSONObject.NULL)
        else {
            val value: Any = try {
                JSONObject(raw)
            } catch (_: Exception) {
                try { JSONArray(raw) } catch (_: Exception) { raw }
            }
            put(key, value)
        }
    }

    private fun parseJob(json: JSONObject) = RemoteCallerLookupJob(
        id = json.optString("_id", json.optString("id")),
        status = json.optString("status", "requested"),
        requestedAction = json.optString("requestedAction").takeIf { it.isNotBlank() && it != "null" },
        deviceId = json.optString("deviceId"),
        mobileProvider = json.optString("mobileProvider", "jio"),
        seriesId = json.optString("seriesId"),
        seriesPrefix = json.optString("seriesPrefix"),
        seriesLabel = json.optString("seriesLabel"),
        startNumber = json.optString("startNumber"),
        endNumber = json.optString("endNumber"),
        totalPlanned = json.optInt("totalPlanned"),
        delayMs = json.optLong("delayMs", 200),
        workers = json.optInt("workers", 1).coerceIn(1, 20),
        lookupProviderId = json.optString("lookupProviderId", "android-call-log-cache"),
        maxRetries = json.optInt("maxRetries", 2).coerceIn(0, 5),
        cursorIndex = json.optInt("cursorIndex"),
        processed = json.optInt("processed"),
        successful = json.optInt("successful"),
        failed = json.optInt("failed"),
        totalLookupDurationMs = json.optLong("totalLookupDurationMs"),
        currentNumber = json.optString("currentNumber").takeIf { it.isNotBlank() && it != "null" }
    )

    private fun request(
        context: Context,
        method: String,
        path: String,
        body: String? = null
    ): ApiResult<String> {
        val serverUrl = EnrollmentPrefs.getServerUrl(context)
        val token = EnrollmentPrefs.getDeviceToken(context)
        if (serverUrl.isBlank()) {
            return ApiResult(error = "Enrollment error: server URL is missing. Re-enroll this device.")
        }
        if (token.isBlank()) {
            return ApiResult(error = "Enrollment error: device token is missing. Re-enroll this device.")
        }
        return try {
            val conn = URL("${normalizeBaseUrl(serverUrl)}$path").openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("X-Device-Token", token)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            conn.disconnect()
            if (code in 200..299) {
                ApiResult(value = text.ifBlank { "{}" })
            } else {
                Log.e(TAG, "$method $path failed ($code): $text")
                val serverMsg = try {
                    JSONObject(text).optString("error").takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                val message = when (code) {
                    401, 403 -> serverMsg ?: "Enrollment error: device token rejected ($code). Re-enroll."
                    404 -> serverMsg ?: "Server endpoint not found ($code). Is the backend deployed?"
                    in 500..599 -> serverMsg ?: "Server error ($code)"
                    else -> serverMsg ?: "Request failed ($code)"
                }
                ApiResult(error = message)
            }
        } catch (e: java.net.MalformedURLException) {
            Log.e(TAG, "$method $path bad server URL '$serverUrl': ${e.message}")
            ApiResult(error = "Enrollment error: server URL '$serverUrl' is not valid. Re-enroll this device.")
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "$method $path host unreachable: ${e.message}")
            ApiResult(error = "No internet connection (cannot reach ${e.message ?: "server"}).")
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "$method $path timed out: ${e.message}")
            ApiResult(error = "Server timed out. Check the connection and retry.")
        } catch (e: Exception) {
            Log.e(TAG, "$method $path failed: ${e.message}")
            ApiResult(error = "Network error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * Enrollment codes are sometimes configured as a bare host ("fleet.example.com"), which
     * makes URL() throw MalformedURLException and surface as a misleading "Network error".
     * Default to https and drop any trailing slash so path concatenation stays correct.
     */
    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }
}
