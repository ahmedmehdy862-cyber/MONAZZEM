package com.focusstudy.poc

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Opt-in encrypted storage for the sync passphrase, behind one explicit user toggle.
 *
 * The contract (§1) says the passphrase is «not stored on the device» — that was the
 * manual-only rule. Automatic sync cannot ask for it on every launch, so storing it is
 * allowed only after the user explicitly enables «مزامنة تلقائية» on the sync screen, and
 * only in [EncryptedSharedPreferences] (AES256-SIV keys, AES256-GCM values, key in the
 * Android Keystore, never in app storage). Switching the toggle off deletes it.
 *
 * The store never throws: on devices where the Keystore/encrypted prefs are unavailable
 * (some emulators, broken keystore) every call degrades to «unavailable» and the caller
 * falls back to the session-only passphrase ([SyncSession]) instead of crashing or
 * silently writing the secret to plain prefs. A plain-SharedPreferences file is never
 * used for the secret.
 *
 * [encryptedPrefs] is injectable so tests can pass plain prefs (round-trip) or a provider
 * that returns null (unavailable path) without touching the real Keystore.
 */
internal class SyncCredentialStore(
    private val encryptedPrefs: () -> SharedPreferences?
) {

    /** True when encrypted storage can be opened on this device. Never throws. */
    fun isEncryptionAvailable(): Boolean =
        runCatching { encryptedPrefs() != null }.getOrDefault(false)

    /**
     * Stores the passphrase encrypted. Returns false (and stores nothing) when encrypted
     * storage is unavailable or the value is invalid.
     */
    fun save(passphrase: String): Boolean {
        if (!SyncPassphrase.isValid(passphrase)) return false
        return runCatching {
            val prefs = encryptedPrefs() ?: return false
            prefs.edit().putString(KEY_PASSPHRASE, passphrase).apply()
            true
        }.getOrDefault(false)
    }

    /** The stored passphrase, or null when none is stored / storage is unavailable. */
    fun load(): String? = runCatching {
        encryptedPrefs()?.getString(KEY_PASSPHRASE, null)
            ?.takeIf { SyncPassphrase.isValid(it) }
    }.getOrNull()

    /** Deletes the stored passphrase, if any. Never throws. */
    fun clear() {
        runCatching { encryptedPrefs()?.edit()?.remove(KEY_PASSPHRASE)?.apply() }
    }

    companion object {
        private const val FILE_NAME = "focus_sync_secret"
        private const val KEY_PASSPHRASE = "sync_passphrase"

        fun default(context: Context): SyncCredentialStore = SyncCredentialStore {
            defaultEncryptedPrefs(context.applicationContext)
        }

        /** Null when the Keystore/encrypted prefs cannot be opened on this device. */
        fun defaultEncryptedPrefs(context: Context): SharedPreferences? = runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()
    }
}
