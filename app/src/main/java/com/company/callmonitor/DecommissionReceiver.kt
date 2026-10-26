package com.company.callmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.company.callmonitor.location.LocationPolicyManager

/**
 * Releases Device Owner without needing the app's UI.
 *
 * The settings-screen action is the intended route, but it is only reachable
 * from [DriverHomeActivity] / [EmployeeHomeActivity], and both require the
 * handset to be enrolled. That leaves the phones most likely to need releasing —
 * mis-provisioned, never enrolled, or with a broken screen — with no way to
 * reach it, and `adb` cannot remove a Device Owner. Without this receiver those
 * handsets can only be recovered by a factory reset.
 *
 * Triggered over adb during provisioning and recovery:
 *
 * ```sh
 * adb shell am broadcast -n com.company.callmonitor/.DecommissionReceiver \
 *   -a com.company.callmonitor.DECOMMISSION --es password '<admin password>'
 * ```
 *
 * Exported, because a broadcast from `adb shell` arrives as uid 2000 and a
 * non-exported receiver would never see it. That means any app on the device can
 * send this intent, so the password is checked before anything happens — the same
 * secret that already gates the settings screen, so this opens no door that was
 * not already open. It is nonetheless the weakest part of the design; see
 * [AdminAuth].
 */
class DecommissionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext

        if (!AdminAuth.matches(intent.getStringExtra("password"))) {
            Logger.log(appCtx, "🚫 Decommission refused — wrong or missing password")
            return
        }

        if (!LocationPolicyManager.isDeviceOwner(appCtx)) {
            Logger.log(appCtx, "ℹ️ Decommission ignored — this handset is not managed")
            return
        }

        // clearDeviceOwnerApp() is far too slow for a receiver's main-thread
        // budget; goAsync keeps the process alive until it returns.
        val pending = goAsync()
        Thread {
            try {
                LocationPolicyManager.decommission(appCtx)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
