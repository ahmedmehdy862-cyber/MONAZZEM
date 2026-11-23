package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline-first invariants are pure decisions, so they are unit-testable without Android. */
class OfflineFirstTest {

    @Test
    fun backgroundChecksSkipWhenOffline() {
        assertFalse(OfflineFirst.shouldAttempt(online = false, manual = false))
    }

    @Test
    fun manualRefreshStillTriesOnceOfflineButFailsSilently() {
        assertTrue(OfflineFirst.shouldAttempt(online = false, manual = true))
    }

    @Test
    fun onlineChecksAlwaysRun() {
        assertTrue(OfflineFirst.shouldAttempt(online = true, manual = false))
        assertTrue(OfflineFirst.shouldAttempt(online = true, manual = true))
    }

    @Test
    fun offlineIsNotAnErrorResult() {
        val offline = UpdateCheckResult.Offline
        val failed = UpdateCheckResult.Failed("no network")
        assertTrue(offline is UpdateCheckResult.Offline)
        assertFalse(offline is UpdateCheckResult.Failed)
        assertEquals("no network", (failed as UpdateCheckResult.Failed).message)
    }
}
