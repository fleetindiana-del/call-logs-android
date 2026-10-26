package com.company.callmonitor

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Logger.log(context, "✅ Device Admin enabled — Force Stop is now blocked")

        val prefs = context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("adminWasActive", true).apply()

        // If this device was provisioned as Device Owner (not merely Device Admin)
        // this is the earliest point the Location lockdown can be applied.
        Thread {
            com.company.callmonitor.location.LocationPolicyManager.apply(context.applicationContext)
        }.start()

        val serverUrl    = prefs.getString("serverUrl", "") ?: ""
        val apiKey       = prefs.getString("apiKey", "") ?: ""
        val deviceId     = prefs.getString("deviceId", "") ?: ""
        val employeeName = prefs.getString("employeeName", "") ?: ""

        Thread {
            ApiClient.sendStatus(
                serverUrl, apiKey, deviceId, employeeName,
                "ADMIN_ENABLED", System.currentTimeMillis(), context.applicationContext,
                "User enabled device admin on this device."
            )
        }.start()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Shown to the employee BEFORE they can confirm deactivation
        return "⚠️ WARNING: Disabling this will immediately stop work call " +
               "monitoring on this device. This action will be logged and " +
               "reported to your manager immediately. Are you sure?"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Logger.log(context, "🚨 Device Admin DISABLED by employee — alerting server!")

        val prefs = context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("adminWasActive", false).apply()

        val serverUrl    = prefs.getString("serverUrl", "") ?: ""
        val apiKey       = prefs.getString("apiKey", "") ?: ""
        val deviceId     = prefs.getString("deviceId", "") ?: ""
        val employeeName = prefs.getString("employeeName", "") ?: ""

        // Fire-and-forget thread — receiver has very little execution time
        Thread {
            ApiClient.sendStatus(
                serverUrl, apiKey, deviceId, employeeName,
                "ADMIN_DISABLED", System.currentTimeMillis(), context.applicationContext,
                "User disabled device admin in Android Settings — work protection is off."
            )
        }.start()

        // Also persist to pending queue in case the server is unreachable
        val entry = ToggleLogEntry(deviceId, employeeName, "ADMIN_DISABLED",
            System.currentTimeMillis())
        PendingStatusManager.savePendingStatus(context, entry)
    }

    companion object {
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context, MyDeviceAdminReceiver::class.java)
    }
}
