package com.company.callmonitor

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Access (call logs, location, expenses, shifts) is cached at sign-in. Without a
 * refresh, a change made in Device Enrollment would only reach the phone when
 * the driver signs in again — so the home screen re-reads it here, throttled.
 * A failed refresh keeps the cached access; the server copy is only believed
 * when it answers properly.
 */
object CapabilityRefresher {

    private const val MIN_INTERVAL_MS = 30_000L

    @Volatile
    private var lastAttemptAt = 0L

    /** [onRefreshed] runs on a background thread after new access has been stored. */
    fun refresh(context: Context, onRefreshed: (() -> Unit)? = null) {
        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < MIN_INTERVAL_MS) return
        lastAttemptAt = now
        Thread {
            if (fetchOnce(appCtx)) onRefreshed?.invoke()
        }.start()
    }

    private fun fetchOnce(appCtx: Context): Boolean {
        val serverUrl = EnrollmentPrefs.getServerUrl(appCtx)
        val token = EnrollmentPrefs.getDeviceToken(appCtx)
        if (serverUrl.isBlank() || token.isBlank()) return false

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$serverUrl/api/enrollment/capabilities").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Authorization", "Bearer $token")
            }
            if (conn.responseCode !in 200..299) {
                Logger.log(appCtx, "🔐 access refresh refused: HTTP ${conn.responseCode}")
                return false
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val caps = json.optJSONObject("capabilities") ?: return false
            val location = caps.optBoolean("locationTracking", false)
            // Older servers don't send `shifts`; there it rode on location tracking.
            val shifts = if (caps.has("shifts")) caps.optBoolean("shifts", false) else location
            EnrollmentPrefs.updateAccess(
                appCtx,
                capCallMonitoring = caps.optBoolean("callMonitoring", false),
                capLocationTracking = location,
                capExpenseManagement = caps.optBoolean("expenseManagement", false),
                capShifts = shifts,
                webBaseUrl = json.optString("webBaseUrl", "")
            )
            Logger.log(appCtx, "🔐 access refreshed from server")
            true
        } catch (e: Exception) {
            Logger.log(appCtx, "🔐 access refresh failed: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
