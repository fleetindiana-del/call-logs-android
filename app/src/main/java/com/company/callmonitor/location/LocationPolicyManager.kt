package com.company.callmonitor.location

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import com.company.callmonitor.Logger
import com.company.callmonitor.MyDeviceAdminReceiver

/**
 * The only mechanism Android offers for actually preventing a driver from
 * switching Location off — as opposed to noticing afterwards that they did.
 *
 * [LocationHealthNotifier] and [LocationProviderChangedReceiver] handle the
 * detect-and-recover half, which is all an ordinary app is permitted. This class
 * handles the prevent half, which requires the app to be **Device Owner**:
 *
 *  - [UserManager.DISALLOW_CONFIG_LOCATION] greys the Location toggle out of both
 *    Settings and the Quick Settings tile. The driver's tap does nothing, because
 *    the OS itself refuses it — there is no dialog to defeat and so no password to
 *    ask for. Android exposes no other hook on that toggle: no permission, no
 *    overlay, no accessibility trick.
 *  - [DevicePolicyManager.setLocationEnabled] forces the switch back on, so a
 *    phone enrolled while Location happened to be off still starts tracking.
 *  - [DevicePolicyManager.setPermissionGrantState] grants our location permissions
 *    outright and makes them un-revokable, closing the other route to the same
 *    outcome: leaving Location on but denying it to this app.
 *
 * Device Owner cannot be granted by the app or the user. It is provisioned once,
 * on a phone with no accounts added — see `docs/DEVICE-OWNER-PROVISIONING.md`.
 * Every method here is therefore a **safe no-op on a phone that is not
 * provisioned**, which is what makes this class safe to call unconditionally in a
 * mixed fleet: company handsets get the hard lock, personal handsets fall through
 * to [LocationGate]'s workflow gate instead.
 */
object LocationPolicyManager {

    private const val TAG = "LocationPolicyManager"

    /**
     * `DISALLOW_CONFIG_LOCATION` only exists from API 28. Below that the toggle
     * cannot be locked at all, even as Device Owner, so those phones are handled
     * entirely by [LocationGate].
     */
    private val CAN_LOCK_TOGGLE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    private val LOCATION_PERMISSIONS = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    )

    /** True only on a phone where this app was provisioned as Device Owner. */
    fun isDeviceOwner(context: Context): Boolean = try {
        dpm(context)?.isDeviceOwnerApp(context.packageName) == true
    } catch (e: Exception) {
        // A DevicePolicyManager that will not answer is treated as "not owner":
        // the fallback gate is always safe, a wrongly-assumed lock is not.
        false
    }

    /**
     * True when the Location toggle is genuinely locked on this phone right now.
     * Drives the driver-facing copy — telling a BYOD driver their toggle is locked
     * when it plainly is not would destroy trust in every other warning we show.
     */
    fun isLocationToggleLocked(context: Context): Boolean =
        CAN_LOCK_TOGGLE && isDeviceOwner(context)

    /**
     * Apply the full lockdown. Idempotent and cheap, so it is called on every
     * process start, on boot, and whenever tracking starts — a policy silently
     * dropped by a system update or a `dpm` reset then repairs itself rather than
     * waiting for someone to notice the phone had stopped reporting.
     *
     * @return true if the lock is in force after this call.
     */
    fun apply(context: Context): Boolean {
        val appCtx = context.applicationContext
        if (!isDeviceOwner(appCtx)) return false

        val dpm = dpm(appCtx) ?: return false
        val admin = MyDeviceAdminReceiver.getComponentName(appCtx)

        if (CAN_LOCK_TOGGLE) {
            try {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_LOCATION)
            } catch (e: Exception) {
                Logger.log(appCtx, "📍 could not lock Location toggle: ${e.message}")
            }
        } else {
            Logger.log(
                appCtx,
                "📍 Android ${Build.VERSION.SDK_INT} cannot lock the Location toggle — " +
                    "relying on the workflow gate"
            )
        }

        // Order matters: force the switch on *after* restricting it, otherwise a
        // driver mid-tap could win the race and we would lock it in the off state.
        forceLocationOn(appCtx)
        grantLocationPermissions(appCtx, dpm, admin)

        try {
            dpm.setUninstallBlocked(admin, appCtx.packageName, true)
        } catch (e: Exception) {
            Logger.log(appCtx, "📍 could not block uninstall: ${e.message}")
        }

        return CAN_LOCK_TOGGLE
    }

    /**
     * Switch Location back on immediately. Only a Device Owner may do this — for
     * everyone else the master switch belongs to the user, so this returns false
     * and the caller falls back to [LocationEnableRequest]'s one-tap prompt.
     *
     * Called from [LocationProviderChangedReceiver] as a second line of defence:
     * even with the toggle restricted, anything that manages to turn Location off
     * (an OEM battery-saver mode, a `dpm` window during provisioning) is undone
     * within a broadcast of happening.
     */
    fun forceLocationOn(context: Context): Boolean {
        val appCtx = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (!isDeviceOwner(appCtx)) return false
        if (LocationEnvironment.isLocationEnabled(appCtx)) return true

        val dpm = dpm(appCtx) ?: return false
        return try {
            dpm.setLocationEnabled(MyDeviceAdminReceiver.getComponentName(appCtx), true)
            Logger.log(appCtx, "📍 Location forced back ON by device policy")
            true
        } catch (e: Exception) {
            Logger.log(appCtx, "📍 setLocationEnabled failed: ${e.message}")
            false
        }
    }

    /**
     * Grant our own location permissions and take them out of the driver's hands.
     * Without this the toggle lock is only half a lock: Location stays on for the
     * phone while this app is denied it, which produces exactly the same silent
     * gap in the route.
     */
    private fun grantLocationPermissions(
        context: Context,
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName
    ) {
        for (permission in LOCATION_PERMISSIONS) {
            // ACCESS_BACKGROUND_LOCATION does not exist before Q; asking about it
            // there throws rather than returning false.
            if (permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
            ) continue

            try {
                dpm.setPermissionGrantState(
                    admin,
                    context.packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            } catch (e: Exception) {
                Logger.log(context, "📍 could not pin $permission: ${e.message}")
            }
        }
    }

    /**
     * Lift the lock — for decommissioning a handset or handing it to a new driver.
     * Deliberately not reachable from the driver UI; it exists so that releasing a
     * phone does not require a factory reset.
     */
    fun release(context: Context) {
        val appCtx = context.applicationContext
        if (!isDeviceOwner(appCtx)) return
        val dpm = dpm(appCtx) ?: return
        val admin = MyDeviceAdminReceiver.getComponentName(appCtx)

        if (CAN_LOCK_TOGGLE) {
            try {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_LOCATION)
            } catch (e: Exception) {
                Logger.log(appCtx, "📍 could not clear location restriction: ${e.message}")
            }
        }
        try {
            dpm.setUninstallBlocked(admin, appCtx.packageName, false)
        } catch (e: Exception) {
            Logger.log(appCtx, "📍 could not unblock uninstall: ${e.message}")
        }
        Logger.log(appCtx, "📍 location policy released on this device")
    }

    /**
     * Hand the handset back: clear every policy and then give up Device Owner
     * itself, leaving an ordinary phone behind.
     *
     * This exists because **adb cannot undo provisioning**. `dpm
     * remove-active-admin` refuses a real Device Owner with `SecurityException:
     * Attempt to remove non-test admin`, so the only code permitted to release
     * ownership is the owning app's own call to [clearDeviceOwnerApp]. Without
     * this method the sole exit from a provisioned handset — to reassign it, to
     * retire it, or to correct a mis-provisioning — is a factory reset.
     *
     * [release] must run first and its ordering is not cosmetic: once ownership
     * is gone the restriction can no longer be cleared, and the handset would be
     * left with a permanently greyed-out Location toggle and no way to reach it —
     * the exact wipe this method exists to avoid.
     *
     * Reachable only from the password-gated admin screen; a driver who could
     * call this could unlock their own phone.
     *
     * @return true if ownership was actually released.
     */
    fun decommission(context: Context): Boolean {
        val appCtx = context.applicationContext
        if (!isDeviceOwner(appCtx)) return false
        val dpm = dpm(appCtx) ?: return false

        // Clears DISALLOW_CONFIG_LOCATION and unblocks uninstall while we still
        // hold the authority to do either.
        release(appCtx)

        return try {
            dpm.clearDeviceOwnerApp(appCtx.packageName)
            Logger.log(appCtx, "📍 Device Owner released — handset is no longer managed")
            true
        } catch (e: Exception) {
            // Leaves the phone unlocked but still owned. Recoverable: apply()
            // re-establishes the lock, and decommission can be retried.
            Logger.log(appCtx, "📍 clearDeviceOwnerApp failed: ${e.message}")
            false
        }
    }

    /** Admin-screen diagnostics: why this phone is or is not locked down. */
    fun statusLabel(context: Context): String = when {
        !isDeviceOwner(context) ->
            "Not a managed device — Location toggle cannot be locked (workflow gate active)"
        !CAN_LOCK_TOGGLE ->
            "Managed, but Android ${Build.VERSION.SDK_INT} is too old to lock the toggle"
        else ->
            "Managed device — Location toggle is locked for the driver"
    }

    private fun dpm(context: Context): DevicePolicyManager? =
        context.applicationContext
            .getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
}
