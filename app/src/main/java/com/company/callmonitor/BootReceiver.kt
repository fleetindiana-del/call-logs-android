package com.company.callmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = context.getSharedPreferences("CallMonitorConfig", Context.MODE_PRIVATE)
            if (prefs.getBoolean("monitoringEnabled", false)) {
                val serviceIntent = Intent(context, CallMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                CallSyncScheduler.schedule(context)
                ServiceRestartScheduler.enqueueDelayedRestart(context, 90, TimeUnit.SECONDS)
            }

            // Location tracking stays always-on when enrolled; website can stop via FCM.
            if (EnrollmentPrefs.hasLocationTracking(context)) {
                // Scheduled outside the thread so the keep-alive exists even if
                // the immediate start is refused right after BOOT_COMPLETED.
                com.company.callmonitor.location.LocationWatchdogWorker.schedule(context)
                Thread {
                    try {
                        // A restart mid-shift goes on the shift trail with the last
                        // time the app was known to be running, so the server can
                        // tell a shutdown from a signal problem. An app update
                        // (MY_PACKAGE_REPLACED) is not a restart and is not reported
                        // as one. Then close the shift if the phone was off past its
                        // scheduled end.
                        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                            ShiftEventReporter.recordBoot(context)
                        }
                        ShiftManager.enforceSchedule(context)

                        // A reboot is the one window where a driver could otherwise
                        // reach the toggle before our policy is back in force.
                        com.company.callmonitor.location.LocationPolicyManager.apply(context)
                        com.company.callmonitor.location.LocationGate.reconcile(context)
                        com.company.callmonitor.location.LocationDutyController.ensureAlwaysActive(
                            context,
                            "boot"
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("BootReceiver", "Failed to restart LocationTrackingService: ${e.message}")
                    }
                }.start()
            }

        }
    }
}
