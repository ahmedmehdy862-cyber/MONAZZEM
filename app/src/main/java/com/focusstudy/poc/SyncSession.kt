package com.focusstudy.poc

/**
 * In-memory passphrase for the current process only.
 *
 * Auto-sync needs the passphrase without asking on every launch, but a typed secret must
 * never be written to disk, saved instance state or a log. This holder keeps it in a plain
 * field: it dies with the process, it is never persisted, and it is cleared the moment
 * auto-sync is switched off. It is the fallback when encrypted on-device storage is
 * unavailable (see [SyncCredentialStore]): in that case auto-sync works for the session
 * only, and the user is told so on the sync screen instead of being promised persistence.
 */
internal object SyncSession {

    @Volatile
    private var passphrase: String? = null

    fun set(value: String) {
        if (SyncPassphrase.isValid(value)) passphrase = value
    }

    fun get(): String? = passphrase?.takeIf { SyncPassphrase.isValid(it) }

    fun clear() {
        passphrase = null
    }
}
