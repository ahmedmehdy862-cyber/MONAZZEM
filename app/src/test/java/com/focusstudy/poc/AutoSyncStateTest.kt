package com.focusstudy.poc

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Durable auto-sync flags/timestamps (plain prefs — never the passphrase) and the session holder. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoSyncStateTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("focus_auto_sync", Context.MODE_PRIVATE)
            .edit().clear().commit()
        SyncSession.clear()
    }

    @Test fun defaultsAreOffWithNoPendingPush() {
        val state = AutoSyncState.get(context)
        assertFalse(state.enabled)
        assertFalse(state.sessionOnly)
        assertFalse(state.pendingPush)
        assertEquals(0L, state.lastAutoPullAt)
        assertEquals(0L, state.lastAutoPushAt)
        assertEquals(0L, state.lastLocalChangeAt)
    }

    @Test fun recordLocalChangeMarksPendingWithTimestamp() {
        val state = AutoSyncState.get(context)
        state.recordLocalChange(123_456L)
        assertTrue(state.pendingPush)
        assertEquals(123_456L, state.lastLocalChangeAt)
        // A fresh read sees the same durable values.
        val reread = AutoSyncState.get(context)
        assertTrue(reread.pendingPush)
        assertEquals(123_456L, reread.lastLocalChangeAt)
    }

    @Test fun sessionPassphraseLivesInMemoryOnly() {
        assertNull(SyncSession.get())
        SyncSession.set("كلمة مرور قوية 123")
        assertEquals("كلمة مرور قوية 123", SyncSession.get())
        SyncSession.set("short")
        assertEquals("كلمة مرور قوية 123", SyncSession.get())
        SyncSession.clear()
        assertNull(SyncSession.get())
    }
}
