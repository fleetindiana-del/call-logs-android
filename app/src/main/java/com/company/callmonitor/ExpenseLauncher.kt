package com.company.callmonitor

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent

object ExpenseLauncher {

    fun open(context: Context) {
        val webBaseUrl = EnrollmentPrefs.getWebBaseUrl(context)
        if (webBaseUrl.isBlank()) {
            Toast.makeText(context, "Expense portal not configured. Please contact your administrator.", Toast.LENGTH_LONG).show()
            return
        }

        val url = "${webBaseUrl.trimEnd('/')}/driver/add-expense"
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open expense portal: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
