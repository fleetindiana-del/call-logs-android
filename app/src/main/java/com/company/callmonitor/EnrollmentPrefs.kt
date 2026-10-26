package com.company.callmonitor

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for enrollment data. Uses EncryptedSharedPreferences so that
 * enrollment tokens, apiKey, and identity fields are not readable in plain text on disk.
 */
object EnrollmentPrefs {

    private const val PREFS_NAME = "fleet_enrollment"
    private const val TAG = "EnrollmentPrefs"

    private fun build(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Volatile
    private var cached: SharedPreferences? = null

    /**
     * The Keystore master key is device-bound and never leaves the original phone, so a
     * cloud-restored or otherwise corrupt store genuinely cannot be decrypted, and wiping
     * it is then the only honest outcome.
     *
     * But [build] also fails *transiently* — AndroidKeyStore serialises access and throws
     * under contention, and this object is read concurrently from BootReceiver, three
     * WorkManager workers, two foreground services and the UI, often all at once during
     * the burst right after boot. Treating one such failure as corruption deleted a
     * perfectly good enrollment and sent the driver back to the sign-in screen for no
     * reason — the "enrolling again and again" complaint.
     *
     * So: memoise the instance (one Keystore unwrap per process, which also removes the
     * contention), and retry twice before concluding the store is really unreadable.
     * Deletion is now the last resort it was always meant to be.
     */
    @Synchronized
    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }

        val appCtx = context.applicationContext
        var lastError: Exception? = null

        repeat(3) { attempt ->
            try {
                return build(appCtx).also { cached = it }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Enrollment store open failed (attempt ${attempt + 1}/3): ${e.javaClass.simpleName}")
                // Brief, growing back-off: Keystore contention clears in milliseconds.
                try {
                    Thread.sleep(50L * (attempt + 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@repeat
                }
            }
        }

        Log.e(
            TAG,
            "Enrollment store unreadable after 3 attempts; rebuilding (enrollment will be lost)",
            lastError
        )
        appCtx.deleteSharedPreferences(PREFS_NAME)
        return try {
            build(appCtx).also { cached = it }
        } catch (fatal: Exception) {
            Log.e(TAG, "Enrollment store unrecoverable; using plaintext fallback", fatal)
            appCtx.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
                .also { cached = it }
        }
    }

    fun isEnrolled(context: Context): Boolean =
        prefs(context).getBoolean("enrolled", false)

    fun getEmployeeId(context: Context): String =
        prefs(context).getString("employeeId", "") ?: ""

    fun getEmployeeName(context: Context): String =
        prefs(context).getString("employeeName", "") ?: ""

    fun getRole(context: Context): String =
        prefs(context).getString("role", "") ?: ""

    fun getServerUrl(context: Context): String {
        val fromEnrollment = prefs(context).getString("serverUrl", "") ?: ""
        if (fromEnrollment.isNotBlank()) return fromEnrollment.trimEnd('/')
        // Repair older enrollments where backend returned blank serverUrl and wiped the typed URL.
        val fromConfig = context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
            .getString("serverUrl", "") ?: ""
        return fromConfig.trimEnd('/')
    }

    fun getApiKey(context: Context): String {
        val fromEnrollment = prefs(context).getString("apiKey", "") ?: ""
        if (fromEnrollment.isNotBlank()) return fromEnrollment
        return context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
            .getString("apiKey", "") ?: ""
    }

    fun getDeviceToken(context: Context): String =
        prefs(context).getString("deviceToken", "") ?: ""

    fun getDeviceId(context: Context): String {
        val fromEnrollment = prefs(context).getString("deviceId", "") ?: ""
        if (fromEnrollment.isNotBlank()) return fromEnrollment
        return context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
            .getString("deviceId", "") ?: ""
    }

    fun getWebBaseUrl(context: Context): String =
        prefs(context).getString("webBaseUrl", "") ?: ""

    /**
     * Applies access refreshed from the server (see CapabilityRefresher). A blank
     * webBaseUrl from the server never wipes a URL that already works.
     */
    fun updateAccess(
        context: Context,
        capCallMonitoring: Boolean,
        capLocationTracking: Boolean,
        capExpenseManagement: Boolean,
        capShifts: Boolean,
        webBaseUrl: String
    ) {
        val editor = prefs(context).edit()
            .putBoolean("capCallMonitoring", capCallMonitoring)
            .putBoolean("capLocationTracking", capLocationTracking)
            .putBoolean("capExpenseManagement", capExpenseManagement)
            .putBoolean("capShifts", capShifts)
        if (webBaseUrl.isNotBlank()) editor.putString("webBaseUrl", webBaseUrl.trimEnd('/'))
        editor.apply()
    }

    fun getVehicleId(context: Context): String =
        prefs(context).getString("vehicleId", "") ?: ""

    fun getVehicleRegistration(context: Context): String =
        prefs(context).getString("vehicleRegistration", "") ?: ""

    fun hasCallMonitoring(context: Context): Boolean =
        prefs(context).getBoolean("capCallMonitoring", false)

    fun hasLocationTracking(context: Context): Boolean =
        prefs(context).getBoolean("capLocationTracking", false)

    fun hasExpenseManagement(context: Context): Boolean =
        prefs(context).getBoolean("capExpenseManagement", false)

    /**
     * Works scheduled shifts. Enrollments saved before this capability existed
     * have no stored value; for them shifts rode on location tracking, so keep
     * that behaviour until the driver signs in again.
     */
    fun hasShifts(context: Context): Boolean =
        if (prefs(context).contains("capShifts")) prefs(context).getBoolean("capShifts", false)
        else hasLocationTracking(context)

    fun saveEnrollment(
        context: Context,
        employeeId: String,
        employeeName: String,
        role: String,
        deviceToken: String,
        deviceId: String,
        serverUrl: String,
        apiKey: String,
        webBaseUrl: String,
        vehicleId: String,
        vehicleRegistration: String,
        capCallMonitoring: Boolean,
        capLocationTracking: Boolean,
        capExpenseManagement: Boolean,
        capShifts: Boolean
    ) {
        prefs(context).edit()
            .putBoolean("enrolled", true)
            .putString("employeeId", employeeId)
            .putString("employeeName", employeeName)
            .putString("role", role)
            .putString("deviceToken", deviceToken)
            .putString("deviceId", deviceId)
            .putString("serverUrl", serverUrl)
            .putString("apiKey", apiKey)
            .putString("webBaseUrl", webBaseUrl)
            .putString("vehicleId", vehicleId)
            .putString("vehicleRegistration", vehicleRegistration)
            .putBoolean("capCallMonitoring", capCallMonitoring)
            .putBoolean("capLocationTracking", capLocationTracking)
            .putBoolean("capExpenseManagement", capExpenseManagement)
            .putBoolean("capShifts", capShifts)
            .apply()

        // Mirror call-monitoring credentials into CallMonitorConfig so the existing
        // call-log path continues to work without modification.
        context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
            .edit()
            .putString("serverUrl", serverUrl)
            .putString("apiKey", apiKey)
            .putString("deviceId", deviceId)
            .putString("employeeName", employeeName)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        cached = null
    }
}
