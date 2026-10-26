package com.company.callmonitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/**
 * Watches the phone's internet connection so a loss of signal is recorded as
 * exactly that — and not mistaken for a tracking failure or for the driver
 * ending the shift.
 *
 * A phone without internet keeps recording locally and uploads on reconnect, so
 * this feeds a separate pair of events ([ShiftEventReporter.connectivityLost] /
 * [ShiftEventReporter.connectivityRestored]) that the server shows as an offline
 * period, never as an interruption of the shift.
 *
 * Callbacks only exist while the process is alive, so [sync] reconciles the
 * recorded state against reality on every housekeeping tick as well.
 */
object ConnectivityMonitor {

    /**
     * Switching between Wi-Fi and mobile data reports the old network lost an
     * instant before the new one is available. Waiting this long before believing
     * a loss keeps that handover from being logged as an outage.
     */
    private const val DEBOUNCE_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            // If we cannot tell, do not invent an outage.
            true
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (callback != null) return
        val appCtx = context.applicationContext
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ShiftEventReporter.connectivityRestored(appCtx)
            }

            override fun onLost(network: Network) {
                val lostAt = System.currentTimeMillis()
                handler.postDelayed({
                    if (!isOnline(appCtx)) ShiftEventReporter.connectivityLost(appCtx, lostAt)
                }, DEBOUNCE_MS)
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (e: Exception) {
            Logger.log(appCtx, "⚠️ Connectivity monitor unavailable: ${e.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun stop(context: Context) {
        val cb = callback ?: return
        callback = null
        try {
            (context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // Already unregistered.
        }
    }

    /** Bring the recorded offline state in line with the phone's actual state. */
    fun sync(context: Context) {
        val appCtx = context.applicationContext
        val online = isOnline(appCtx)
        val recordedLost = ShiftEventReporter.connectivityLostSince(appCtx) > 0L
        if (!online && !recordedLost) ShiftEventReporter.connectivityLost(appCtx)
        if (online && recordedLost) ShiftEventReporter.connectivityRestored(appCtx)
    }
}
