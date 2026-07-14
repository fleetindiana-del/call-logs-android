package com.company.callmonitor

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {
    private const val PREFS_NAME = "CallMonitorLogs"
    private const val KEY_LOGS = "app_logs"
    private const val MAX_LOGS_LENGTH = 10000 // Keep logs manageable

    @Synchronized
    fun log(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentLogs = prefs.getString(KEY_LOGS, "") ?: ""
        
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newLogEntry = "[$time] $message\n"
        
        var updatedLogs = newLogEntry + currentLogs
        if (updatedLogs.length > MAX_LOGS_LENGTH) {
            updatedLogs = updatedLogs.substring(0, MAX_LOGS_LENGTH)
        }
        
        prefs.edit().putString(KEY_LOGS, updatedLogs).apply()

        // Queue for server upload (same line as on screen, without trailing newline)
        DeviceLogSync.enqueue(context.applicationContext, newLogEntry.trimEnd())
    }

    fun getLogs(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LOGS, "No logs yet.") ?: "No logs yet."
    }

    fun clearLogs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LOGS, "").apply()
        // Do not clear upload queue — pending lines still go to the server
    }
}
