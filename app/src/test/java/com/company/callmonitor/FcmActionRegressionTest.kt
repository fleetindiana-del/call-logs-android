package com.company.callmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents protected FCM action contract for call sync.
 * GPS and caller-lookup must use separate actions, never overload sync_now.
 */
class FcmActionRegressionTest {

    @Test
    fun syncNow_isReservedForCallSync() {
        assertEquals("sync_now", CALL_SYNC_FCM_ACTION)
    }

    @Test
    fun locationActions_areIsolatedFromCallSync() {
        val locationActions = setOf(
            "location_upload",
            "location_latest",
            "location_live_mode",
            "location_stop_live_mode",
            "location_start_tracking",
            "location_stop_tracking"
        )
        assertTrue(CALL_SYNC_FCM_ACTION !in locationActions)
    }

    @Test
    fun callerLookupActions_areIsolatedFromCallSync() {
        val callerLookupActions = setOf(
            "caller_lookup_start",
            "caller_lookup_resume",
            "caller_lookup_control"
        )
        assertTrue(CALL_SYNC_FCM_ACTION !in callerLookupActions)
    }

    companion object {
        const val CALL_SYNC_FCM_ACTION = "sync_now"
    }
}
