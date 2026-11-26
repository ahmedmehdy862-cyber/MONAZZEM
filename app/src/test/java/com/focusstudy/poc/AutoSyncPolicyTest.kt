package com.focusstudy.poc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure trigger decisions: no Android calls, no network, no clock beyond the argument. */
class AutoSyncPolicyTest {

    private val now = 1_000_000L

    @Test fun pullOnOpenNeedsEnabledOnlineAndInterval() {
        assertTrue(AutoSyncPolicy.shouldPullOnOpen(now, 0L, enabled = true, online = true))
        assertFalse(AutoSyncPolicy.shouldPullOnOpen(now, 0L, enabled = false, online = true))
        assertFalse(AutoSyncPolicy.shouldPullOnOpen(now, 0L, enabled = true, online = false))
        // Pulled a minute ago: not due yet.
        assertFalse(
            AutoSyncPolicy.shouldPullOnOpen(
                now, now - 60_000L, enabled = true, online = true
            )
        )
        // Pulled six minutes ago: due.
        assertTrue(
            AutoSyncPolicy.shouldPullOnOpen(
                now, now - 6 * 60_000L, enabled = true, online = true
            )
        )
    }

    @Test fun foregroundPullUsesTheLongerThrottle() {
        // Ten minutes since the last pull: due on open, not on foreground.
        assertTrue(AutoSyncPolicy.shouldPullOnOpen(now, now - 10 * 60_000L, true, true))
        assertFalse(AutoSyncPolicy.shouldPullOnForeground(now, now - 10 * 60_000L, true, true))
        assertTrue(AutoSyncPolicy.shouldPullOnForeground(now, now - 16 * 60_000L, true, true))
        assertFalse(AutoSyncPolicy.shouldPullOnForeground(now, 0L, enabled = false, online = true))
        assertFalse(AutoSyncPolicy.shouldPullOnForeground(now, 0L, enabled = true, online = false))
    }

    @Test fun pushWaitsForTheDebounceQuietPeriod() {
        // Changed a second ago: still inside the debounce.
        assertFalse(
            AutoSyncPolicy.shouldPushNow(now, now - 1_000L, pendingPush = true, enabled = true, online = true)
        )
        // Changed 31 seconds ago: due.
        assertTrue(
            AutoSyncPolicy.shouldPushNow(now, now - 31_000L, pendingPush = true, enabled = true, online = true)
        )
        assertFalse(
            AutoSyncPolicy.shouldPushNow(now, now - 31_000L, pendingPush = false, enabled = true, online = true)
        )
        assertFalse(
            AutoSyncPolicy.shouldPushNow(now, now - 31_000L, pendingPush = true, enabled = false, online = true)
        )
        assertFalse(
            AutoSyncPolicy.shouldPushNow(now, now - 31_000L, pendingPush = true, enabled = true, online = false)
        )
        // No change ever recorded.
        assertFalse(
            AutoSyncPolicy.shouldPushNow(now, 0L, pendingPush = true, enabled = true, online = true)
        )
    }
}
