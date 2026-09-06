package com.company.callmonitor

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Reads the phone's address book and pushes it to /api/contacts (the Contact Bank).
 *
 * Contact sync used to live only in MainActivity's monitoring toggle listener, so it
 * fired exactly once — on the OFF→ON transition of that switch. Enrolled employees
 * reach monitoring through EnrollmentActivity and the daily ON status sent by
 * CallMonitorService, never through that transition, so their contacts never synced.
 * This manager owns the sync so every path (enrollment, the service's periodic cycle,
 * the manual toggle) can trigger it.
 */
object ContactSyncManager {

    private const val LAST_SYNC_KEY = "lastContactSyncAt"
    private const val SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val BATCH_SIZE = 200

    /** Runs a sync only if the last successful one is older than 24h. Safe to call often. */
    fun syncIfDue(context: Context) {
        val prefs = prefs(context)
        if (!prefs.getBoolean("monitoringEnabled", false)) return
        val last = prefs.getLong(LAST_SYNC_KEY, 0L)
        if (last != 0L && System.currentTimeMillis() - last < SYNC_INTERVAL_MS) return
        syncNow(context)
    }

    /**
     * Reads and uploads all contacts using the credentials stored in CallMonitorConfig
     * (enrollment mirrors them there). Blocking — call from a background thread.
     */
    fun syncNow(context: Context): Boolean {
        val prefs = prefs(context)
        return syncNow(
            context,
            serverUrl = prefs.getString("serverUrl", "") ?: "",
            apiKey = prefs.getString("apiKey", "") ?: "",
            deviceId = prefs.getString("deviceId", "") ?: "",
            employeeName = prefs.getString("employeeName", "") ?: ""
        )
    }

    fun syncNow(
        context: Context,
        serverUrl: String,
        apiKey: String,
        deviceId: String,
        employeeName: String
    ): Boolean {
        if (serverUrl.isBlank() || deviceId.isBlank()) {
            Logger.log(context, "⚠️ Contact sync skipped — server URL or device ID not configured")
            return false
        }
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Logger.log(context, "❌ Contact sync skipped — READ_CONTACTS permission not granted")
            return false
        }

        val contactsList = try {
            readDeviceContacts(context, deviceId, employeeName)
        } catch (e: Exception) {
            Logger.log(context, "❌ Error reading contacts: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }

        if (contactsList.isEmpty()) {
            Logger.log(context, "ℹ️ No contacts found to sync")
            return false
        }

        val batches = contactsList.chunked(BATCH_SIZE)
        Logger.log(context, "🔄 Syncing ${contactsList.size} contacts in ${batches.size} batch(es)...")

        var totalSynced = 0
        val failedContacts = mutableListOf<ContactEntry>()
        for ((index, batch) in batches.withIndex()) {
            if (ApiClient.syncContacts(serverUrl, apiKey, batch, context)) {
                totalSynced += batch.size
            } else {
                failedContacts.addAll(batch)
                Logger.log(context, "❌ Batch ${index + 1}/${batches.size} failed — saving offline to retry later")
            }
        }

        if (failedContacts.isNotEmpty()) {
            PendingContactsManager.savePendingContacts(context, failedContacts)
        }

        val allSuccess = failedContacts.isEmpty()
        if (allSuccess) {
            Logger.log(context, "✅ All $totalSynced contacts synced successfully")
            // Only a fully clean run resets the clock; a partial run retries on the next cycle.
            prefs(context).edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply()
        } else {
            Logger.log(context, "⚠️ Synced $totalSynced of ${contactsList.size} contacts (some batches failed)")
        }
        return allSuccess
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)

    private fun readDeviceContacts(
        context: Context,
        deviceId: String,
        employeeName: String
    ): List<ContactEntry> {
        val contactsList = mutableListOf<ContactEntry>()
        val now = System.currentTimeMillis()

        context.contentResolver.query(
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use {
            val nameIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val name = if (nameIndex >= 0) it.getString(nameIndex) else "Unknown"
                var number = if (numberIndex >= 0) it.getString(numberIndex) else ""

                // Clean up the phone number for better representation/matching
                number = number?.replace(Regex("[^0-9+]"), "") ?: ""

                if (number.isNotBlank()) {
                    contactsList.add(
                        ContactEntry(deviceId, employeeName, name ?: "Unknown", number, now)
                    )
                }
            }
        }

        return contactsList
    }
}
