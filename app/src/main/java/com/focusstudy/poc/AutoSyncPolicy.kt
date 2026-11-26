package com.focusstudy.poc

/**
 * Pure trigger decisions for automatic sync — no Android calls, so plain JUnit covers them.
 *
 * The rules (windows-sync contract §5: one serialized sync per account, pull never overwrites
 * unsynced local data — that guarantee lives in [SyncAccount]/[SyncApply], this file only
 * decides *when* to run them):
 *
 * - Pull on app open, but at most once per [OPEN_PULL_MIN_INTERVAL_MS] so a process restart
 *   loop cannot hammer the server.
 * - Pull on returning to the foreground, at most once per [FOREGROUND_PULL_MIN_INTERVAL_MS].
 * - Push only after [PUSH_DEBOUNCE_MS] of quiet following the last local change, so typing a
 *   task title or ticking several habits does not fire one request per keystroke/tap.
 * - Nothing runs when auto-sync is off or the device is offline; a failed attempt only
 *   reschedules through [NetworkRetryJob], it never retries in a tight loop.
 */
internal object AutoSyncPolicy {

    /** Pull at most this often on cold start. */
    const val OPEN_PULL_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /** Pull at most this often when the app comes back to the foreground. */
    const val FOREGROUND_PULL_MIN_INTERVAL_MS = 15 * 60 * 1000L

    /** Quiet period after the last local change before a debounced push fires. */
    const val PUSH_DEBOUNCE_MS = 30_000L

    fun shouldPullOnOpen(now: Long, lastPullAt: Long, enabled: Boolean, online: Boolean): Boolean =
        enabled && online && now - lastPullAt >= OPEN_PULL_MIN_INTERVAL_MS

    fun shouldPullOnForeground(now: Long, lastPullAt: Long, enabled: Boolean, online: Boolean): Boolean =
        enabled && online && now - lastPullAt >= FOREGROUND_PULL_MIN_INTERVAL_MS

    /** True once the debounce quiet period has passed since the last local change. */
    fun shouldPushNow(
        now: Long,
        lastChangeAt: Long,
        pendingPush: Boolean,
        enabled: Boolean,
        online: Boolean
    ): Boolean =
        enabled && online && pendingPush && lastChangeAt > 0L &&
            now - lastChangeAt >= PUSH_DEBOUNCE_MS
}
