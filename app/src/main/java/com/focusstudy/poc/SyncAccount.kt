package com.focusstudy.poc

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Opt-in account pairing over the existing Vercel Blob endpoint.
 *
 * The key is the server-issued [UserIdentity.profileId]; a display name, install id, device id or
 * push token is never used or sent as an account key. The passphrase is sent only in the request
 * body and is not persisted on the device. Pairing, push and pull are all opt-in.
 */
internal object SyncAccount {

    internal enum class Result {
        SUCCESS, INVALID_PASSPHRASE, PROFILE_NOT_READY, NETWORK_ERROR, REJECTED,
        /** A `baseRevision` push lost to a concurrent writer (409 `stale_revision`). */
        STALE_REVISION,
        /** The server is throttling this account/caller (429). */
        RATE_LIMITED,
        /**
         * The passphrase itself changed on the server, but the re-seal push could not
         * complete. The stored secret is already the new one; the push is left pending
         * and completes on the next sync.
         */
        CHANGED_NEEDS_RESYNC,
    }

    /** The outcome of an activation, plus whether the account was created by this call. */
    internal data class RegisterOutcome(val result: Result, val created: Boolean)

    /** Re-seal attempts after a passphrase change before giving up (design §2.2, Option A). */
    private const val MAX_RESEAL_ATTEMPTS = 3

    /**
     * HTTP failures are not all network failures: a 401/403 means the passphrase (or the
     * profile) was rejected — retrying the same secret will never succeed — while a timeout,
     * a 429 or a 503 is transient and worth retrying. `TeamMessages.request` used to throw a
     * plain message for every non-2xx, so a wrong passphrase surfaced as "no connection".
     */
    internal fun resultFor(error: Exception): Result {
        val status = (error as? HttpStatusException)?.status
        return when (status) {
            400, 401, 403 -> Result.REJECTED
            409 -> Result.STALE_REVISION
            429 -> Result.RATE_LIMITED
            else -> Result.NETWORK_ERROR
        }
    }

    suspend fun register(context: Context, passphrase: String): RegisterOutcome = withContext(Dispatchers.IO) {
        if (!SyncPassphrase.isValid(passphrase)) return@withContext RegisterOutcome(Result.INVALID_PASSPHRASE, false)
        val profileId = UserIdentity.profileId(context)
        if (profileId.isBlank()) return@withContext RegisterOutcome(Result.PROFILE_NOT_READY, false)
        if (!UserIdentity.isValidProfileId(profileId)) return@withContext RegisterOutcome(Result.PROFILE_NOT_READY, false)

        // Idempotent activation: the server requires `currentPassphrase` whenever the
        // account already exists, and this client never sends anything else — so a second
        // device (or a repeated tap) joins with the same secret instead of failing with 401
        // even when the passphrase is right. Changing the passphrase is a separate flow
        // ([changePassphrase]) that sends a different current value.
        val payload = JSONObject()
            .put("action", "register")
            .put("profileId", profileId)
            .put("passphrase", passphrase)
            .put("currentPassphrase", passphrase)
        val response = try {
            TeamMessages.request("${TeamMessages.BASE}/api/sync", "POST", payload.toString())
        } catch (error: Exception) {
            return@withContext RegisterOutcome(resultFor(error), false)
        }
        val json = runCatching { JSONObject(response) }.getOrNull()
        val ok = json?.optBoolean("ok") ?: false
        // `created` tells the screen whether this was the first activation, so it shows
        // the one-time recovery code only then.
        RegisterOutcome(if (ok) Result.SUCCESS else Result.REJECTED, json?.optBoolean("created") ?: false)
    }

    /**
     * Passphrase change, design §2.2 Option A: pull with the current secret (validates it
     * and reads the base revision), change the auth hash on the server, and only then
     * update the stored secret — never before, or the device would hold a secret the
     * server does not know. Finally the local snapshot is re-sealed: re-pushed with every
     * live record re-stamped +1ms and the pulled revision as `baseRevision`, so a
     * concurrent writer wins with 409 instead of being overwritten; up to three attempts.
     * On v1 the records are plaintext, so the re-seal only re-asserts them; on v2 the
     * same call carries the re-encrypted envelopes. When the re-seal cannot complete,
     * the password HAS changed and the push is left pending ([Result.CHANGED_NEEDS_RESYNC]).
     */
    suspend fun changePassphrase(context: Context, currentPassphrase: String, newPassphrase: String): Result =
        withContext(Dispatchers.IO) {
            if (!SyncPassphrase.isValid(currentPassphrase) || !SyncPassphrase.isValid(newPassphrase)) {
                return@withContext Result.INVALID_PASSPHRASE
            }
            if (currentPassphrase == newPassphrase) return@withContext Result.INVALID_PASSPHRASE
            val profileId = UserIdentity.profileId(context)
            if (profileId.isBlank()) return@withContext Result.PROFILE_NOT_READY
            if (!UserIdentity.isValidProfileId(profileId)) return@withContext Result.PROFILE_NOT_READY

            val (pulled, revision) = pullWithRevision(context, currentPassphrase)
            if (pulled != Result.SUCCESS) return@withContext pulled
            val payload = JSONObject()
                .put("action", "changePassphrase")
                .put("profileId", profileId)
                .put("passphrase", newPassphrase)
                .put("currentPassphrase", currentPassphrase)
            val changed = try {
                val response = TeamMessages.request("${TeamMessages.BASE}/api/sync", "POST", payload.toString())
                runCatching { JSONObject(response).optBoolean("ok") }.getOrDefault(false)
            } catch (error: Exception) {
                return@withContext resultFor(error)
            }
            if (!changed) return@withContext Result.REJECTED

            // The server knows the new secret now — and only now — so the device follows.
            SyncSession.set(newPassphrase)
            if (AutoSync.isEnabled(context)) {
                val store = SyncCredentialStore.default(context)
                if (!store.save(newPassphrase)) {
                    // Encrypted storage failed: drop the stale secret instead of leaving
                    // auto-sync retrying the old passphrase, and fall back to session-only.
                    store.clear()
                    AutoSyncState.get(context).sessionOnly = true
                }
            }
            AutoSyncState.get(context).pendingPush = true
            var base = revision
            repeat(MAX_RESEAL_ATTEMPTS) {
                when (val resealed = push(context, newPassphrase, baseRevision = base, forceReseal = true)) {
                    Result.SUCCESS -> {
                        AutoSyncState.get(context).pendingPush = false
                        return@withContext Result.SUCCESS
                    }
                    Result.STALE_REVISION -> {
                        val (repulled, fresh) = pullWithRevision(context, newPassphrase)
                        if (repulled != Result.SUCCESS) return@withContext Result.CHANGED_NEEDS_RESYNC
                        base = fresh
                    }
                    else -> return@withContext Result.CHANGED_NEEDS_RESYNC
                }
            }
            Result.CHANGED_NEEDS_RESYNC
        }

    /**
     * Stores the scrypt hash of a recovery code on the server (design §3.3). The code
     * itself is sent once over HTTPS — like the passphrase — and never stored anywhere.
     */
    suspend fun setRecoveryCode(context: Context, passphrase: String, code: String): Result =
        withContext(Dispatchers.IO) {
            if (!SyncPassphrase.isValid(passphrase)) return@withContext Result.INVALID_PASSPHRASE
            val normalized = SyncRecoveryCode.normalize(code)
            if (normalized.isEmpty()) return@withContext Result.INVALID_PASSPHRASE
            val profileId = UserIdentity.profileId(context)
            if (profileId.isBlank()) return@withContext Result.PROFILE_NOT_READY
            if (!UserIdentity.isValidProfileId(profileId)) return@withContext Result.PROFILE_NOT_READY

            val payload = JSONObject()
                .put("action", "setRecoveryCode")
                .put("profileId", profileId)
                .put("passphrase", passphrase)
                .put("recoveryCode", normalized)
            val response = try {
                TeamMessages.request("${TeamMessages.BASE}/api/sync", "POST", payload.toString())
            } catch (error: Exception) {
                return@withContext resultFor(error)
            }
            val ok = runCatching { JSONObject(response).optBoolean("ok") }.getOrDefault(false)
            if (ok) Result.SUCCESS else Result.REJECTED
        }

    /**
     * Forgot-password reset with a single-use recovery code (design §3.2). On success the
     * server deleted the account and its snapshot; the caller must re-register with a new
     * passphrase and upload this device's local data. The code is consumed either way —
     * a failed attempt never reveals whether the account exists.
     */
    suspend fun resetWithRecoveryCode(context: Context, code: String): Result =
        withContext(Dispatchers.IO) {
            val normalized = SyncRecoveryCode.normalize(code)
            if (normalized.isEmpty()) return@withContext Result.INVALID_PASSPHRASE
            val profileId = UserIdentity.profileId(context)
            if (profileId.isBlank()) return@withContext Result.PROFILE_NOT_READY
            if (!UserIdentity.isValidProfileId(profileId)) return@withContext Result.PROFILE_NOT_READY

            val payload = JSONObject()
                .put("action", "reset")
                .put("profileId", profileId)
                .put("recoveryCode", normalized)
            val response = try {
                TeamMessages.request("${TeamMessages.BASE}/api/sync", "POST", payload.toString())
            } catch (error: Exception) {
                return@withContext resultFor(error)
            }
            val ok = runCatching { JSONObject(response).optBoolean("ok") }.getOrDefault(false)
            if (ok) Result.SUCCESS else Result.REJECTED
        }

    /**
     * Push the whitelisted local collections once the account is active. Task notes, money
     * transaction notes, the private notebook and all device/push identifiers stay on the
     * device: [SyncPayload] copies only the fields the backend accepts. Fingerprints are saved
     * only after the server accepted the push, so a failed request never makes a local change
     * look already synchronized.
     *
     * @param baseRevision when set, the server refuses (409) unless its revision still
     * matches — the re-seal guard of a passphrase change. @param forceReseal re-stamps
     * every live record +1ms even when unchanged (see [SyncPayload.build]).
     */
    suspend fun push(
        context: Context,
        passphrase: String,
        baseRevision: Long? = null,
        forceReseal: Boolean = false
    ): Result = withContext(Dispatchers.IO) {
        if (!SyncPassphrase.isValid(passphrase)) return@withContext Result.INVALID_PASSPHRASE
        val profileId = UserIdentity.profileId(context)
        if (profileId.isBlank()) return@withContext Result.PROFILE_NOT_READY
        if (!UserIdentity.isValidProfileId(profileId)) return@withContext Result.PROFILE_NOT_READY

        val metadata = SyncMetadata.get(context)
        val tracked = metadata.tracked()
        // The backend keeps the stored row on an equal timestamp, so stay strictly monotonic.
        val now = maxOf(System.currentTimeMillis(), (tracked.values.maxOfOrNull { it.updatedAt } ?: 0L) + 1L)
        val db = StudyDb.get(context)
        val built = SyncPayload.build(
            tasks = db.tasks(),
            subjects = db.subjects(),
            types = db.taskTypes(includeArchived = true),
            tracked = tracked,
            now = now,
            life = lifeSnapshot(db),
            forceRefresh = forceReseal
        )

        val payload = JSONObject()
            .put("action", "push")
            .put("profileId", profileId)
            .put("passphrase", passphrase)
            .put("data", payloadData(built.data))
        if (baseRevision != null) payload.put("baseRevision", baseRevision)
        val response = try {
            TeamMessages.request("${TeamMessages.BASE}/api/sync", "POST", payload.toString())
        } catch (error: Exception) {
            return@withContext resultFor(error)
        }
        // A successful push returns the merged snapshot (with a revision), not an `ok` flag.
        val accepted = runCatching { JSONObject(response).has("revision") }.getOrDefault(false)
        if (accepted) {
            metadata.save(built.stamps)
            Result.SUCCESS
        } else {
            Result.REJECTED
        }
    }

    /**
     * Pull the stored snapshot and apply it locally, per record last-write-wins
     * ([SyncApply.plan]). The local snapshot is built first so unsynced local data is never
     * overwritten: a pulled record only wins when it is newer than our last synced stamp, or
     * when there is no local row at all. Stamps are saved only after [StudyDb.applySyncPlan]
     * succeeds, so a failed apply leaves the old stamps and the next pull retries the same
     * snapshot instead of losing the delete. Notes are never in the snapshot (see SyncPayload),
     * so a pull can neither leak nor erase them.
     */
    suspend fun pull(context: Context, passphrase: String): Result =
        pullWithRevision(context, passphrase).first

    /** Same as [pull] but also returns the server snapshot revision for `baseRevision` pushes. */
    suspend fun pullWithRevision(context: Context, passphrase: String): Pair<Result, Long> =
        withContext(Dispatchers.IO) {
            if (!SyncPassphrase.isValid(passphrase)) return@withContext Result.INVALID_PASSPHRASE to 0L
            val profileId = UserIdentity.profileId(context)
            if (profileId.isBlank()) return@withContext Result.PROFILE_NOT_READY to 0L
            if (!UserIdentity.isValidProfileId(profileId)) return@withContext Result.PROFILE_NOT_READY to 0L

            val metadata = SyncMetadata.get(context)
            val tracked = metadata.tracked()
            // Same monotonic clock as push: a stamp minted while planning must not collide with one
            // the server already holds for this account.
            val now = maxOf(System.currentTimeMillis(), (tracked.values.maxOfOrNull { it.updatedAt } ?: 0L) + 1L)
            val db = StudyDb.get(context)
            val built = SyncPayload.build(
                tasks = db.tasks(),
                subjects = db.subjects(),
                types = db.taskTypes(includeArchived = true),
                tracked = tracked,
                now = now,
                life = lifeSnapshot(db)
            )

            val payload = JSONObject()
                .put("action", "pull")
                .put("profileId", profileId)
                .put("passphrase", passphrase)
            val response = try {
                TeamMessages.request("${TeamMessages.BASE}/api/sync", "POST", payload.toString())
            } catch (error: Exception) {
                return@withContext resultFor(error) to 0L
            }
            // A successful pull returns the stored snapshot (with a revision), not an `ok` flag.
            val (revision, incoming) = runCatching {
                val json = JSONObject(response)
                require(json.has("revision")) { "pull response has no revision" }
                json.optLong("revision") to jsonToData(json.optJSONObject("data"))
            }.getOrElse { return@withContext Result.REJECTED to 0L }
            try {
                val plan = SyncApply.plan(built.data, tracked, incoming)
                db.applySyncPlan(plan)
                // Plan stamps override the freshly built ones for applied/adopted records; every
                // other stamp stays exactly as the local build minted it.
                metadata.save(built.stamps + plan.stamps)
                Result.SUCCESS to revision
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.REJECTED to 0L
            }
        }

    private fun lifeSnapshot(db: StudyDb): LifeSyncSnapshot = LifeSyncSnapshot(
        habits = db.allHabits(),
        habitLogs = db.allHabitLogs(),
        wirdLogs = db.allWirdLogs(),
        healthLogs = db.allHealthLogs(),
        transactions = db.allTransactions(),
        moodLogs = db.allMoodLogs(),
        calorieLogs = db.allCalorieLogs()
    )

    private fun payloadData(data: Map<String, List<Map<String, Any>>>): JSONObject {
        val out = JSONObject()
        for ((kind, records) in data) {
            val array = org.json.JSONArray()
            for (record in records) {
                val item = JSONObject()
                for ((key, value) in record) item.put(key, value)
                array.put(item)
            }
            out.put(kind, array)
        }
        return out
    }

    /** Converts a pulled `data` object into record maps; integral JSON numbers become Long. */
    private fun jsonToData(data: JSONObject?): Map<String, List<Map<String, Any>>> {
        val out = LinkedHashMap<String, List<Map<String, Any>>>()
        if (data == null) return out
        for (kind in SyncPayload.collections) {
            val array = data.optJSONArray(kind) ?: continue
            val records = ArrayList<Map<String, Any>>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val record = LinkedHashMap<String, Any>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = obj.opt(key) ?: continue
                    if (value == JSONObject.NULL) continue
                    record[key] = when (value) {
                        is Int -> value.toLong()
                        is Long, is Double, is Boolean, is String -> value
                        is Float -> value.toDouble()
                        else -> value.toString()
                    }
                }
                records.add(record)
            }
            out[kind] = records
        }
        return out
    }
}
