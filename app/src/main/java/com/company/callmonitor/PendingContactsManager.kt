package com.company.callmonitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PendingContactsManager {

    private const val PREFS_NAME = "PendingContacts"
    private const val KEY_CONTACTS = "contactsArray"

    @Synchronized
    fun savePendingContacts(context: Context, contacts: List<ContactEntry>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val contactsString = prefs.getString(KEY_CONTACTS, "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(contactsString)
            
            // To prevent storing massive duplicates if it fails multiple times,
            // we could deduplicate, but for simplicity, we append and rely on backend upsert
            for (contact in contacts) {
                jsonArray.put(contact.toJson())
            }
            
            prefs.edit().putString(KEY_CONTACTS, jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun getPendingContacts(context: Context): List<ContactEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val contactsString = prefs.getString(KEY_CONTACTS, "[]") ?: "[]"
        val pendingList = mutableListOf<ContactEntry>()
        try {
            val jsonArray = JSONArray(contactsString)
            for (i in 0 until jsonArray.length()) {
                val jsonObj = jsonArray.getJSONObject(i)
                pendingList.add(
                    ContactEntry(
                        deviceId = jsonObj.optString("deviceId"),
                        employeeName = jsonObj.optString("employeeName"),
                        contactName = jsonObj.optString("contactName", "Unknown"),
                        phoneNumber = jsonObj.optString("phoneNumber"),
                        timestamp = jsonObj.optLong("timestamp")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return pendingList
    }

    @Synchronized
    fun clearSyncedContacts(context: Context, syncedContacts: List<ContactEntry>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentContacts = getPendingContacts(context).toMutableList()
        
        // Remove based on phone number and timestamp combination
        val syncedKeys = syncedContacts.map { "${it.phoneNumber}_${it.timestamp}" }.toSet()
        currentContacts.removeAll { "${it.phoneNumber}_${it.timestamp}" in syncedKeys }

        val newArray = JSONArray()
        for (contact in currentContacts) {
            newArray.put(contact.toJson())
        }
        
        prefs.edit().putString(KEY_CONTACTS, newArray.toString()).apply()
    }
}
