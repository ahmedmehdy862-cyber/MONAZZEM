package com.focusstudy.poc

import android.content.Context
import android.content.SharedPreferences

/**
 * Durable auto-sync state in plain prefs. This file holds only flags and timestamps —
 * never the passphrase (that lives in [SyncCredentialStore], encrypted, or in
 * [SyncSession], in memory only).
 *
 * [sessionOnly] is the honest fallback flag: true when the user enabled auto-sync but
 * encrypted storage was unavailable, so the passphrase is kept in memory for this
 * process only and auto-sync stops working after a restart until the passphrase is
 * entered again.
 */
internal class AutoSyncState(private val prefs: SharedPreferences) {

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var sessionOnly: Boolean
        get() = prefs.getBoolean(KEY_SESSION_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_SESSION_ONLY, value).apply()

    var pendingPush: Boolean
        get() = prefs.getBoolean(KEY_PENDING_PUSH, false)
        set(value) = prefs.edit().putBoolean(KEY_PENDING_PUSH, value).apply()

    var lastAutoPullAt: Long
        get() = prefs.getLong(KEY_LAST_PULL, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PULL, value).apply()

    var lastAutoPushAt: Long
        get() = prefs.getLong(KEY_LAST_PUSH, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PUSH, value).apply()

    var lastLocalChangeAt: Long
        get() = prefs.getLong(KEY_LAST_CHANGE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHANGE, value).apply()

    /** Records a local change to a synced collection; the debounced push reads this. */
    fun recordLocalChange(now: Long) {
        prefs.edit()
            .putLong(KEY_LAST_CHANGE, now)
            .putBoolean(KEY_PENDING_PUSH, true)
            .apply()
    }

    companion object {
        private const val FILE_NAME = "focus_auto_sync"
        private const val KEY_ENABLED = "auto_sync_enabled"
        private const val KEY_SESSION_ONLY = "auto_sync_session_only"
        private const val KEY_PENDING_PUSH = "pending_push"
        private const val KEY_LAST_PULL = "last_auto_pull_at"
        private const val KEY_LAST_PUSH = "last_auto_push_at"
        private const val KEY_LAST_CHANGE = "last_local_change_at"

        fun get(context: Context): AutoSyncState = AutoSyncState(
            context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        )
    }
}
