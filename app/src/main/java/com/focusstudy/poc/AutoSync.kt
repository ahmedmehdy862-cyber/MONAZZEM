package com.focusstudy.poc

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Automatic sync triggers. Manual push/pull in [SyncAccountScreen] stay as the fallback;
 * everything below only runs when the user explicitly enabled «مزامنة تلقائية».
 *
 * Triggers (windows-sync contract §5: serialized per account, never a parallel push+pull):
 *
 * - App open ([onAppOpened], wired in `MainActivity.onCreate`): pull, then push if local
 *   changes are pending.
 * - Return to the foreground ([onForegrounded], wired in `MainActivity.onResume`): same,
 *   throttled to once per [AutoSyncPolicy.FOREGROUND_PULL_MIN_INTERVAL_MS].
 * - Local change ([notifyLocalChange], wired in the synced-collection writers of [StudyDb]):
 *   debounced push after [AutoSyncPolicy.PUSH_DEBOUNCE_MS] of quiet — never one request per
 *   keystroke or tap.
 * - Network return ([onNetworkRestored], wired in [NetworkRetryJob]): pull if due, then push
 *   anything pending. A failed attempt schedules [NetworkRetryJob] again; nothing retries
 *   in a tight loop.
 *
 * The passphrase comes from encrypted storage ([SyncCredentialStore]) when the toggle was
 * enabled with it, else from the in-memory session ([SyncSession]). No trigger ever asks
 * the user to type it, and no trigger runs without one.
 *
 * [runner], [clock], [debounceMs], [isOnline] and [credentialStoreProvider] are injectable
 * so unit tests can drive the triggers without a network or a Keystore.
 */
internal object AutoSync {

    /** What a sync actually does; the real one delegates to [SyncAccount]. */
    internal interface SyncRunner {
        suspend fun pull(context: Context, passphrase: String): SyncAccount.Result
        suspend fun push(context: Context, passphrase: String): SyncAccount.Result
    }

    private object RealSyncRunner : SyncRunner {
        override suspend fun pull(context: Context, passphrase: String): SyncAccount.Result =
            SyncAccount.pull(context, passphrase)

        override suspend fun push(context: Context, passphrase: String): SyncAccount.Result =
            SyncAccount.push(context, passphrase)
    }

    /** Outcome of enabling/disabling the toggle on the sync screen. */
    internal enum class EnableOutcome {
        ENABLED_ENCRYPTED,
        ENABLED_SESSION_ONLY,
        DISABLED,
        NEEDS_PASSPHRASE
    }

    internal var runner: SyncRunner = RealSyncRunner
    internal var clock: () -> Long = System::currentTimeMillis
    internal var debounceMs: Long = AutoSyncPolicy.PUSH_DEBOUNCE_MS
    internal var isOnline: (Context) -> Boolean = { OfflineFirst.isOnline(it) }
    internal var credentialStoreProvider: (Context) -> SyncCredentialStore =
        { SyncCredentialStore.default(it) }

    private val mutex = Mutex()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: Job? = null
    // One store per process: opening encrypted prefs touches the Keystore, so triggers
    // and the screen share the instance instead of creating one per call.
    private var credentialStoreCache: SyncCredentialStore? = null

    internal fun credentialStore(context: Context): SyncCredentialStore =
        credentialStoreCache ?: credentialStoreProvider(context.applicationContext)
            .also { credentialStoreCache = it }

    /** Test-only: drop any in-flight work and restore the production collaborators. */
    internal fun resetForTest() {
        debounceJob?.cancel()
        debounceJob = null
        credentialStoreCache = null
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runner = RealSyncRunner
        clock = System::currentTimeMillis
        debounceMs = AutoSyncPolicy.PUSH_DEBOUNCE_MS
        isOnline = { OfflineFirst.isOnline(it) }
        credentialStoreProvider = { SyncCredentialStore.default(it) }
        SyncSession.clear()
    }

    fun isEnabled(context: Context): Boolean = AutoSyncState.get(context).enabled

    fun isSessionOnly(context: Context): Boolean = AutoSyncState.get(context).sessionOnly

    /** Encrypted stored passphrase first, in-memory session passphrase second, else null. */
    fun resolvePassphrase(context: Context): String? =
        credentialStore(context).load() ?: SyncSession.get()

    /**
     * Enables or disables auto-sync. Enabling needs a passphrase: the one just typed, else
     * one already stored or in the session. The secret is stored encrypted only when the
     * device supports it; otherwise auto-sync still works for this session only and the
     * caller must tell the user (see [EnableOutcome.ENABLED_SESSION_ONLY]). Disabling
     * deletes the stored secret and the session copy.
     */
    fun setEnabled(context: Context, enable: Boolean, passphrase: String?): EnableOutcome {
        val app = context.applicationContext
        val state = AutoSyncState.get(app)
        if (!enable) {
            debounceJob?.cancel()
            debounceJob = null
            state.enabled = false
            state.sessionOnly = false
            state.pendingPush = false
            credentialStore(app).clear()
            SyncSession.clear()
            return EnableOutcome.DISABLED
        }
        val typed = passphrase?.takeIf { SyncPassphrase.isValid(it) }
        val effective = typed ?: resolvePassphrase(app)
        if (effective == null) return EnableOutcome.NEEDS_PASSPHRASE
        SyncSession.set(effective)
        val stored = if (typed != null) {
            credentialStore(app).save(typed)
        } else {
            credentialStore(app).load() != null
        }
        state.enabled = true
        state.sessionOnly = !stored
        return if (stored) EnableOutcome.ENABLED_ENCRYPTED else EnableOutcome.ENABLED_SESSION_ONLY
    }

    /**
     * A manual push/pull from the sync screen, run under the same lock as the automatic
     * triggers so this account never has a push and a pull in flight at once (contract §5).
     * Auto-sync timestamps are untouched, except a successful manual push clears
     * `pendingPush` (there is nothing left to send). The caller's result is returned as-is.
     */
    suspend fun manualSync(context: Context, isPush: Boolean, passphrase: String): SyncAccount.Result {
        mutex.withLock {
            val app = context.applicationContext
            val result = if (isPush) runner.push(app, passphrase) else runner.pull(app, passphrase)
            if (isPush && result == SyncAccount.Result.SUCCESS) {
                AutoSyncState.get(app).pendingPush = false
            }
            return result
        }
    }

    /** Cold start: pull when due, then push pending local changes. */
    fun onAppOpened(context: Context): Job = scope.launch {
        val app = context.applicationContext
        val state = AutoSyncState.get(app)
        if (!AutoSyncPolicy.shouldPullOnOpen(clock(), state.lastAutoPullAt, state.enabled, isOnline(app))) return@launch
        if (resolvePassphrase(app) == null) return@launch
        syncNowLocked(app, pull = true)
    }

    /** Back to the foreground: same as app open, with the longer throttle. */
    fun onForegrounded(context: Context): Job = scope.launch {
        val app = context.applicationContext
        val state = AutoSyncState.get(app)
        if (!AutoSyncPolicy.shouldPullOnForeground(clock(), state.lastAutoPullAt, state.enabled, isOnline(app))) return@launch
        if (resolvePassphrase(app) == null) return@launch
        syncNowLocked(app, pull = true)
    }

    /** Called when connectivity returns: pull if due, then push anything pending. */
    fun onNetworkRestored(context: Context): Job = scope.launch {
        val app = context.applicationContext
        val state = AutoSyncState.get(app)
        if (!state.enabled || !isOnline(app)) return@launch
        if (resolvePassphrase(app) == null) return@launch
        syncNowLocked(
            app,
            pull = AutoSyncPolicy.shouldPullOnOpen(clock(), state.lastAutoPullAt, true, true)
        )
    }

    /**
     * A synced collection changed locally. Records the change and (re)starts the debounce
     * timer; the push fires only after [debounceMs] with no further change. Cheap no-op
     * while auto-sync is off, so [StudyDb] can call it unconditionally.
     */
    fun notifyLocalChange(context: Context) {
        val app = context.applicationContext
        val state = AutoSyncState.get(app)
        if (!state.enabled) return
        state.recordLocalChange(clock())
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(debounceMs)
            val current = AutoSyncState.get(app)
            if (!AutoSyncPolicy.shouldPushNow(
                    clock(), current.lastLocalChangeAt, current.pendingPush,
                    current.enabled, isOnline(app)
                )
            ) return@launch
            syncNowLocked(app, pull = false)
        }
    }

    /**
     * Serialized sync: an optional pull first, then a push if local changes are pending.
     * Pull and push never run in parallel for this account (contract §5). A network
     * failure schedules [NetworkRetryJob] and leaves `pendingPush` set, so the change is
     * retried on reconnect instead of being lost. A rejected/invalid response leaves the
     * state untouched for the next trigger.
     */
    private suspend fun syncNowLocked(context: Context, pull: Boolean) {
        mutex.withLock {
            val passphrase = resolvePassphrase(context) ?: return
            val state = AutoSyncState.get(context)
            if (pull) {
                when (runner.pull(context, passphrase)) {
                    SyncAccount.Result.SUCCESS -> state.lastAutoPullAt = clock()
                    SyncAccount.Result.NETWORK_ERROR -> {
                        NetworkRetryJob.schedule(context)
                        return
                    }
                    else -> return
                }
            }
            if (state.pendingPush) {
                when (runner.push(context, passphrase)) {
                    SyncAccount.Result.SUCCESS -> {
                        state.pendingPush = false
                        state.lastAutoPushAt = clock()
                    }
                    SyncAccount.Result.NETWORK_ERROR -> NetworkRetryJob.schedule(context)
                    else -> Unit
                }
            }
        }
    }
}
