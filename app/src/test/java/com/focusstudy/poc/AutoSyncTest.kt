package com.focusstudy.poc

import android.content.Context
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The automatic triggers against a fake [AutoSync.SyncRunner]: no network, no Keystore.
 * The fake clock is advanced past the debounce where the production code waits for real time.
 *
 * Runs under Robolectric (no device needed) with the runtime pinned to SDK 35.
 * Run with `./gradlew testDebugUnitTest` on a machine with the Android SDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoSyncTest {

    private lateinit var context: Context
    private lateinit var fake: FakeRunner
    private var now = 1_000_000L

    private class FakeRunner : AutoSync.SyncRunner {
        var pullResult: SyncAccount.Result = SyncAccount.Result.SUCCESS
        var pushResult: SyncAccount.Result = SyncAccount.Result.SUCCESS
        var workMs: Long = 0L
        val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
        private val active = AtomicInteger(0)
        @Volatile var maxConcurrent = 0

        private suspend fun <T> tracked(name: String, block: suspend () -> T): T {
            val current = active.incrementAndGet()
            maxConcurrent = maxOf(maxConcurrent, current)
            try {
                if (workMs > 0) delay(workMs)
                calls.add(name)
                return block()
            } finally {
                active.decrementAndGet()
            }
        }

        override suspend fun pull(context: Context, passphrase: String): SyncAccount.Result =
            tracked("pull:$passphrase") { pullResult }

        override suspend fun push(context: Context, passphrase: String): SyncAccount.Result =
            tracked("push:$passphrase") { pushResult }
    }

    private fun state() = AutoSyncState.get(context)

    private fun enableWithStoredPassphrase() {
        val outcome = AutoSync.setEnabled(context, true, "كلمة مرور قوية 123")
        assertEquals(AutoSync.EnableOutcome.ENABLED_ENCRYPTED, outcome)
    }

    private suspend fun awaitCalls(expected: Int, timeoutMs: Long = 3_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (fake.calls.size < expected && System.currentTimeMillis() < deadline) delay(25)
        assertEquals(expected, fake.calls.size)
    }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("focus_auto_sync", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("test_sync_secret", Context.MODE_PRIVATE)
            .edit().clear().commit()
        AutoSync.resetForTest()
        fake = FakeRunner()
        AutoSync.runner = fake
        AutoSync.clock = { now }
        AutoSync.isOnline = { true }
        AutoSync.debounceMs = 50L
        AutoSync.credentialStoreProvider = {
            SyncCredentialStore {
                context.getSharedPreferences("test_sync_secret", Context.MODE_PRIVATE)
            }
        }
    }

    @After fun tearDown() {
        AutoSync.resetForTest()
    }

    @Test fun appOpenPullsWhenDueAndRecordsIt() = runBlocking {
        enableWithStoredPassphrase()
        AutoSync.onAppOpened(context).join()
        assertEquals(listOf("pull:كلمة مرور قوية 123"), fake.calls)
        assertEquals(now, state().lastAutoPullAt)
    }

    @Test fun appOpenSkipsWhenDisabledOrOfflineOrThrottled() = runBlocking {
        // Disabled: nothing runs.
        AutoSync.onAppOpened(context).join()
        assertTrue(fake.calls.isEmpty())

        enableWithStoredPassphrase()
        // Offline: nothing runs.
        AutoSync.isOnline = { false }
        AutoSync.onAppOpened(context).join()
        assertTrue(fake.calls.isEmpty())

        // Pulled a minute ago: inside the 5-minute open throttle.
        AutoSync.isOnline = { true }
        state().lastAutoPullAt = now - 60_000L
        AutoSync.onAppOpened(context).join()
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun appOpenDoesNothingWithoutAPassphrase() = runBlocking {
        state().enabled = true
        AutoSync.onAppOpened(context).join()
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun foregroundPullUsesTheLongerThrottle() = runBlocking {
        enableWithStoredPassphrase()
        state().lastAutoPullAt = now - 10 * 60_000L
        AutoSync.onForegrounded(context).join()
        assertTrue(fake.calls.isEmpty())
        state().lastAutoPullAt = now - 16 * 60_000L
        AutoSync.onForegrounded(context).join()
        assertEquals(1, fake.calls.size)
    }

    @Test fun networkReturnPullsWhenDueThenPushesPending() = runBlocking {
        enableWithStoredPassphrase()
        state().pendingPush = true
        state().lastLocalChangeAt = now - 60_000L
        AutoSync.onNetworkRestored(context).join()
        assertEquals(
            listOf("pull:كلمة مرور قوية 123", "push:كلمة مرور قوية 123"),
            fake.calls
        )
        assertFalse(state().pendingPush)
    }

    @Test fun localChangesDebounceIntoOnePush() = runBlocking {
        enableWithStoredPassphrase()
        AutoSync.notifyLocalChange(context)
        AutoSync.notifyLocalChange(context)
        AutoSync.notifyLocalChange(context)
        now += AutoSyncPolicy.PUSH_DEBOUNCE_MS + 1
        awaitCalls(1)
        assertEquals(listOf("push:كلمة مرور قوية 123"), fake.calls)
        assertFalse(state().pendingPush)
        // No second push: the debounce fired once for the burst.
        delay(150)
        assertEquals(1, fake.calls.size)
    }

    @Test fun appOpenPullsBeforePushingPendingChanges() = runBlocking {
        enableWithStoredPassphrase()
        state().pendingPush = true
        state().lastLocalChangeAt = now - 60_000L
        AutoSync.onAppOpened(context).join()
        assertEquals(
            listOf("pull:كلمة مرور قوية 123", "push:كلمة مرور قوية 123"),
            fake.calls
        )
    }

    @Test fun failedPullDoesNotPushAndKeepsPending() = runBlocking {
        enableWithStoredPassphrase()
        fake.pullResult = SyncAccount.Result.NETWORK_ERROR
        state().pendingPush = true
        AutoSync.onAppOpened(context).join()
        assertEquals(listOf("pull:كلمة مرور قوية 123"), fake.calls)
        assertTrue(state().pendingPush)
    }

    @Test fun failedPushKeepsPendingForTheNextRetry() = runBlocking {
        enableWithStoredPassphrase()
        fake.pushResult = SyncAccount.Result.NETWORK_ERROR
        AutoSync.notifyLocalChange(context)
        now += AutoSyncPolicy.PUSH_DEBOUNCE_MS + 1
        awaitCalls(1)
        assertTrue(state().pendingPush)
    }

    @Test fun enableWithoutStorageFallsBackToSessionOnly() {
        AutoSync.credentialStoreProvider = { SyncCredentialStore { null } }
        val outcome = AutoSync.setEnabled(context, true, "كلمة مرور قوية 123")
        assertEquals(AutoSync.EnableOutcome.ENABLED_SESSION_ONLY, outcome)
        assertTrue(state().enabled)
        assertTrue(state().sessionOnly)
        assertEquals("كلمة مرور قوية 123", SyncSession.get())
        assertEquals("كلمة مرور قوية 123", AutoSync.resolvePassphrase(context))
    }

    @Test fun enableWithoutAnyPassphraseNeedsOne() {
        assertEquals(
            AutoSync.EnableOutcome.NEEDS_PASSPHRASE,
            AutoSync.setEnabled(context, true, null)
        )
        assertFalse(state().enabled)
    }

    @Test fun disableDeletesTheStoredSecretAndTheSession() {
        enableWithStoredPassphrase()
        assertEquals("كلمة مرور قوية 123", AutoSync.resolvePassphrase(context))
        assertEquals(AutoSync.EnableOutcome.DISABLED, AutoSync.setEnabled(context, false, null))
        assertFalse(state().enabled)
        assertFalse(state().sessionOnly)
        assertEquals(null, AutoSync.resolvePassphrase(context))
        assertEquals(null, SyncSession.get())
    }

    @Test fun manualSyncSerializesAndClearsPendingOnPush() = runBlocking {
        enableWithStoredPassphrase()
        fake.workMs = 150L
        state().pendingPush = true
        val first = async { AutoSync.manualSync(context, true, "كلمة مرور قوية 123") }
        val second = async { AutoSync.manualSync(context, false, "كلمة مرور قوية 123") }
        assertEquals(SyncAccount.Result.SUCCESS, first.await())
        assertEquals(SyncAccount.Result.SUCCESS, second.await())
        assertEquals(1, fake.maxConcurrent)
        assertEquals(2, fake.calls.size)
        assertFalse(state().pendingPush)
    }
}
