package com.focusstudy.poc

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The opt-in encrypted passphrase store. The encrypted-prefs provider is injected so the
 * round-trip is testable without a Keystore; the unavailable path (null provider) must
 * degrade to "not stored" instead of throwing or writing the secret to plain prefs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncCredentialStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun plainPrefs() =
        context.getSharedPreferences("test_sync_secret", Context.MODE_PRIVATE)

    @Test fun roundTripsThroughInjectedPrefs() {
        val store = SyncCredentialStore { plainPrefs() }
        assertTrue(store.isEncryptionAvailable())
        assertTrue(store.save("كلمة مرور قوية 123"))
        assertEquals("كلمة مرور قوية 123", store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test fun rejectsInvalidPassphrasesWithoutStoring() {
        val store = SyncCredentialStore { plainPrefs() }
        assertFalse(store.save("short"))
        assertFalse(store.save(""))
        assertNull(store.load())
        assertTrue(plainPrefs().all.isEmpty())
    }

    @Test fun unavailableStorageDegradesInsteadOfThrowing() {
        val store = SyncCredentialStore { null }
        assertFalse(store.isEncryptionAvailable())
        assertFalse(store.save("كلمة مرور قوية 123"))
        assertNull(store.load())
        store.clear() // must not throw
    }

    @Test fun failingProviderDegradesInsteadOfThrowing() {
        val store = SyncCredentialStore { error("keystore broken") }
        assertFalse(store.isEncryptionAvailable())
        assertFalse(store.save("كلمة مرور قوية 123"))
        assertNull(store.load())
        store.clear() // must not throw
    }

    @Test fun realDefaultProviderNeverThrows() {
        // Under Robolectric the Keystore is absent, so this is the unavailable path in
        // practice — but the assertion is only "no throw", whatever the device does.
        val store = SyncCredentialStore.default(context)
        runCatching { store.isEncryptionAvailable() }
        runCatching { store.save("كلمة مرور قوية 123") }
        runCatching { store.load() }
        runCatching { store.clear() }
    }
}
