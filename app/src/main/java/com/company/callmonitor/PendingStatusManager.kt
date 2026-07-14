package com.company.callmonitor

import android.content.Context
import org.json.JSONArray

object PendingStatusManager {

    private const val PREFS_NAME = "PendingStatuses"
    private const val KEY_STATUSES = "statusesArray"

    @Synchronized
    fun savePendingStatus(context: Context, statusLog: ToggleLogEntry) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val statusesString = prefs.getString(KEY_STATUSES, "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(statusesString)
            jsonArray.put(statusLog.toJson())
            prefs.edit().putString(KEY_STATUSES, jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getPendingStatuses(context: Context): List<ToggleLogEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val statusesString = prefs.getString(KEY_STATUSES, "[]") ?: "[]"
        val pendingList = mutableListOf<ToggleLogEntry>()
        try {
            val jsonArray = JSONArray(statusesString)
            for (i in 0 until jsonArray.length()) {
                val jsonObj = jsonArray.getJSONObject(i)
                pendingList.add(ToggleLogEntry.fromJson(jsonObj))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return pendingList
    }

    @Synchronized
    fun clearSyncedStatuses(context: Context, syncedStatuses: List<ToggleLogEntry>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentStatuses = getPendingStatuses(context).toMutableList()
        
        val syncedTimestamps = syncedStatuses.map { it.timestamp }.toSet()
        currentStatuses.removeAll { it.timestamp in syncedTimestamps }

        val newArray = JSONArray()
        for (status in currentStatuses) {
            newArray.put(status.toJson())
        }
        
        prefs.edit().putString(KEY_STATUSES, newArray.toString()).apply()
    }
}
