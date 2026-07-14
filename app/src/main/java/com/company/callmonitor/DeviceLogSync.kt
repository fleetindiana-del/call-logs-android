package com.company.callmonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Queues lines written by [Logger] and uploads them in batches to the server.
 * Avoid calling [Logger.log] from flush paths to prevent feedback loops.
 */
object DeviceLogSync {

    private const val PREFS_NAME = "DeviceLogUploadQueue"
    private const val KEY_ENTRIES = "entriesJson"
    private const val MAX_QUEUED = 800
    private const val BATCH_SIZE = 120

    @Synchronized
    fun enqueue(context: Context, line: String) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var arr = try {
            JSONArray(prefs.getString(KEY_ENTRIES, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }

        val trimmed = line.trim().take(3500)
        if (trimmed.isEmpty()) return

        arr.put(
            JSONObject()
                .put("m", trimmed)
                .put("t", System.currentTimeMillis())
        )

        while (arr.length() > MAX_QUEUED) {
            val pruned = JSONArray()
            for (i in 1 until arr.length()) {
                pruned.put(arr.getJSONObject(i))
            }
            arr = pruned
        }

        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    /**
     * Uploads up to [BATCH_SIZE] queued entries. Returns true if upload succeeded or nothing to send.
     */
    @Synchronized
    fun flush(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = try {
            JSONArray(prefs.getString(KEY_ENTRIES, "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        if (arr.length() == 0) return true

        val cfg = app.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
        if (!cfg.getBoolean("monitoringEnabled", false)) return true

        val serverUrl = cfg.getString("serverUrl", "") ?: ""
        val apiKey = cfg.getString("apiKey", "") ?: ""
        val deviceId = cfg.getString("deviceId", "") ?: ""
        val employeeName = cfg.getString("employeeName", "") ?: ""

        if (serverUrl.isBlank() || deviceId.isBlank()) return false

        val batch = minOf(BATCH_SIZE, arr.length())
        val entries = JSONArray()
        for (i in 0 until batch) {
            val o = arr.getJSONObject(i)
            val msg = o.optString("m", "")
            val t = o.optLong("t", System.currentTimeMillis())
            if (msg.isNotBlank()) {
                entries.put(
                    JSONObject()
                        .put("message", msg)
                        .put("recordedAt", t)
                )
            }
        }

        if (entries.length() == 0) {
            val remaining = JSONArray()
            for (i in batch until arr.length()) {
                remaining.put(arr.getJSONObject(i))
            }
            prefs.edit().putString(KEY_ENTRIES, remaining.toString()).apply()
            return true
        }

        val ok = ApiClient.uploadDeviceLogs(
            serverUrl, apiKey, deviceId, employeeName, entries,
            null
        )
        if (!ok) return false

        val remaining = JSONArray()
        for (i in batch until arr.length()) {
            remaining.put(arr.getJSONObject(i))
        }
        prefs.edit().putString(KEY_ENTRIES, remaining.toString()).apply()
        return true
    }

    /** Flush until queue empty or one failure (max [maxRounds] batches). */
    fun flushAll(context: Context, maxRounds: Int = 25) {
        var rounds = 0
        while (rounds < maxRounds) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val len = try {
                JSONArray(prefs.getString(KEY_ENTRIES, "[]") ?: "[]").length()
            } catch (_: Exception) {
                0
            }
            if (len == 0) break
            if (!flush(context)) break
            rounds++
        }
    }
}
