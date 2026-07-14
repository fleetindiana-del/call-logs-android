package com.company.callmonitor

import android.content.Context
import org.json.JSONArray

object PendingCallsManager {

    private const val PREFS_NAME = "PendingCalls"
    private const val KEY_CALLS = "callsArray"

    @Synchronized
    fun savePendingCall(context: Context, callLog: CallLogEntry) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val callsString = prefs.getString(KEY_CALLS, "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(callsString)
            jsonArray.put(callLog.toJson())
            prefs.edit().putString(KEY_CALLS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getPendingCalls(context: Context): List<CallLogEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val callsString = prefs.getString(KEY_CALLS, "[]") ?: "[]"
        val pendingList = mutableListOf<CallLogEntry>()
        try {
            val jsonArray = JSONArray(callsString)
            for (i in 0 until jsonArray.length()) {
                val jsonObj = jsonArray.getJSONObject(i)
                pendingList.add(CallLogEntry.fromJson(jsonObj))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return pendingList
    }

    @Synchronized
    fun clearSyncedCalls(context: Context, syncedCalls: List<CallLogEntry>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentCalls = getPendingCalls(context).toMutableList()
        
        val syncedTimestamps = syncedCalls.map { it.timestamp }.toSet()
        currentCalls.removeAll { it.timestamp in syncedTimestamps }

        val newArray = JSONArray()
        for (call in currentCalls) {
            newArray.put(call.toJson())
        }
        
        prefs.edit().putString(KEY_CALLS, newArray.toString()).apply()
    }
}
