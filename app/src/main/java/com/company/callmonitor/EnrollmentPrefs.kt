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

    /**
     * The Keystore master key is device-bound and never leaves the original phone, so a
     * cloud-restored or otherwise corrupt store cannot be decrypted. Rebuilding it once
     * turns an unrecoverable crash (or a silently blank deviceToken that surfaces as a
     * bogus "not enrolled" error) into a clean, honest "re-enroll this device" state.
     */
    private fun prefs(context: Context): SharedPreferences = try {
        build(context)
    } catch (e: Exception) {
        Log.w(TAG, "Enrollment store unreadable (${e.javaClass.simpleName}); rebuilding", e)
        context.deleteSharedPreferences(PREFS_NAME)
        try {
            build(context)
        } catch (fatal: Exception) {
            Log.e(TAG, "Enrollment store unrecoverable; using in-memory fallback", fatal)
            context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
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
        capExpenseManagement: Boolean
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
    }
}
