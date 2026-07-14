package com.company.callmonitor

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

/**
 * Vivo, Oppo, Realme, Xiaomi, etc. use extra layers (autostart, "background freeze")
 * on top of Android. Standard "ignore battery optimizations" is often not enough.
 * These intents are best-effort; OEMs change them between OS versions.
 */
object OemPowerSettingsHelper {

    fun isAggressivePowerOem(): Boolean {
        val m = Build.MANUFACTURER.lowercase(Locale.US)
        return m.contains("vivo") ||
            m.contains("oppo") ||
            m.contains("realme") ||
            m.contains("oneplus") ||
            m.contains("xiaomi") ||
            m.contains("redmi") ||
            m.contains("poco") ||
            m.contains("huawei") ||
            m.contains("honor") ||
            m.contains("infinix") ||
            m.contains("tecno") ||
            m.contains("itel")
    }

    /**
     * Tries to open the OEM-specific AutoStart / Background Start screen.
     * @return true if a vendor screen was opened, false otherwise.
     */
    fun openAutoStartSettings(activity: Activity): Boolean {
        val man = Build.MANUFACTURER.lowercase(Locale.US)
        val candidates = mutableListOf<Intent>()

        when {
            man.contains("xiaomi") || man.contains("redmi") || man.contains("poco") -> {
                candidates.add(
                    Intent().setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                )
            }
            man.contains("vivo") -> {
                candidates.add(
                    Intent().setClassName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                    )
                )
                candidates.add(
                    Intent().setClassName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                    )
                )
                candidates.add(
                    Intent().setClassName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.PurviewTabActivity"
                    )
                )
            }
            man.contains("oppo") || man.contains("realme") || man.contains("oneplus") -> {
                candidates.add(
                    Intent().setClassName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                )
                candidates.add(
                    Intent().setClassName(
                        "com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity"
                    )
                )
                candidates.add(
                    Intent().setClassName(
                        "com.oplus.safecenter",
                        "com.oplus.safecenter.permission.startup.StartupAppListActivity"
                    )
                )
            }
            man.contains("huawei") || man.contains("honor") -> {
                candidates.add(
                    Intent().setClassName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                )
                candidates.add(
                    Intent().setClassName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.optimize.process.ProtectActivity"
                    )
                )
            }
        }

        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                activity.startActivity(intent)
                return true
            } catch (_: Exception) { }
        }
        return false
    }

    /**
     * Tries manufacturer startup / background screens, then falls back to app details.
     * @return true if a vendor screen was probably opened, false if only fallback.
     */
    fun openKeepAliveSettings(activity: Activity): Boolean {
        // Try autostart settings first
        if (openAutoStartSettings(activity)) return true

        val pkg = activity.packageName
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", pkg, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) { }
        return false
    }
}
