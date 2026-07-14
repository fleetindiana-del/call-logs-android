package com.company.callmonitor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.judemanutd.autostarter.AutoStartPermissionHelper
import java.util.Locale

/**
 * Mandatory onboarding screen shown on first launch (or whenever the OEM setup
 * hasn't been completed). Guides the user through:
 *   1. Enabling AutoStart / Background Start for this app
 *   2. Disabling battery optimization (unrestricted battery)
 *   3. Locking the app in the recents/multitask screen
 *
 * This activity is NOT skippable — the user must confirm they have completed
 * all steps before proceeding to the main UI.
 */
class OemOnboardingActivity : AppCompatActivity() {

    companion object {
        private const val PREF_ONBOARDING_COMPLETED = "oemOnboardingCompleted"

        /** Check whether onboarding was already finished. */
        fun isCompleted(context: Context): Boolean {
            return context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
                .getBoolean(PREF_ONBOARDING_COMPLETED, false)
        }

        /** Mark onboarding as complete. */
        fun markCompleted(context: Context) {
            context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_ONBOARDING_COMPLETED, true).apply()
        }
    }

    private var autoStartOpened = false
    private var batteryOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_oem_onboarding)

        val manufacturer = Build.MANUFACTURER.lowercase(Locale.US)

        // Customize subtitle based on OEM
        val subtitle = findViewById<TextView>(R.id.tvOnboardingSubtitle)
        val brand = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> "Xiaomi/Redmi"
            manufacturer.contains("vivo") -> "Vivo"
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> "OPPO/Realme/OnePlus"
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> "Huawei/Honor"
            else -> null
        }
        if (brand != null) {
            subtitle.text = "Your $brand phone aggressively kills background apps. " +
                "Complete these steps so call monitoring stays active 24/7."
        }

        // ── Step 1: AutoStart ─────────────────────────────────────────────────
        findViewById<Button>(R.id.btnOpenAutoStart).setOnClickListener {
            autoStartOpened = true

            // Try AutoStarter library first (broad OEM coverage)
            val autoStartAvailable = try {
                AutoStartPermissionHelper.getInstance().getAutoStartPermission(this)
            } catch (_: Exception) {
                false
            }

            if (!autoStartAvailable) {
                // Fall back to our own OEM intents
                val opened = OemPowerSettingsHelper.openAutoStartSettings(this)
                if (!opened) {
                    Toast.makeText(this,
                        "Could not find AutoStart settings. Please enable it manually in your phone's Security or Battery settings.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }

        // ── Step 2: Battery Optimization ──────────────────────────────────────
        findViewById<Button>(R.id.btnOpenBattery).setOnClickListener {
            batteryOpened = true
            openBatteryOptimizationSettings()
        }

        // ── Step 3 has no button — it's a manual instruction ──────────────────

        // ── Done button ───────────────────────────────────────────────────────
        findViewById<Button>(R.id.btnOnboardingDone).setOnClickListener {
            if (!autoStartOpened) {
                Toast.makeText(this, "Please open AutoStart settings first (Step 1)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!batteryOpened) {
                Toast.makeText(this, "Please open Battery settings first (Step 2)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            markCompleted(this)
            Logger.log(this, "✅ OEM onboarding completed — manufacturer: ${Build.MANUFACTURER}")
            finish()
        }
    }

    /** Disables back-press so the user cannot skip onboarding. */
    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        Toast.makeText(this, "Please complete all steps before continuing", Toast.LENGTH_SHORT).show()
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                    return
                } catch (_: Exception) { }
            }
        }
        // Fallback: open the general battery optimization list
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            } catch (_: Exception) {
                Toast.makeText(this, "Could not open battery settings. Please do it manually.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
