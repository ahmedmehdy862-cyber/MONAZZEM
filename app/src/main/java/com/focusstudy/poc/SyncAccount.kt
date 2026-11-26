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

    internal enum class Result { SUCCESS, INVALID_PASSPHRASE, PROFILE_NOT_READY, NETWORK_ERROR, REJECTED }

    /**
     * HTTP failures are not all network failures: a 401/403 means the passphrase (or the
     * profile) was rejected — retrying the same secret will never succeed — while a timeout,
     * a 429 or a 503 is transient and worth retrying. `TeamMessages.request` used to throw a
     * plain message for every non-2xx, so a wrong passphrase surfaced as "no connection".
     */
    internal fun resultFor(error: Exception): Result {
        val status = (error as? HttpStatusException)?.status
        return if (status == 400 || status == 401 || status == 403) Result.REJECTED
        else Result.NETWORK_ERROR
    }

    suspend fun register(context: Context, passphrase: String): Result = withContext(Dispatchers.IO) {
        if (!SyncPassphrase.isValid(passphrase)) return@withContext Result.INVALID_PASSPHRASE
        val profileId = UserIdentity.profileId(context)
        if (profileId.isBlank()) return@withContext Result.PROFILE_NOT_READY
        if (!UserIdentity.isValidProfileId(profileId)) return@withContext Result.PROFILE_NOT_READY

        // Idempotent activation: the server requires `currentPassphrase` whenever the
        // account already exists, and this client never sends anything else — so a second
        // device (or a repeated tap) joins with the same secret instead of failing with 401
        // even when the passphrase is right. Changing the passphrase from Android is not
        // offered, so nothing is lost: a change still needs a client that sends a different
        // current value (see the server's register test).
        val payload = JSONObject()
            .put("action", "register")
            .put("profileId", profileId)
            .put("passphrase", passphrase)
            .put("currentPassphrase", passphrase)
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
     */
    suspend fun push(context: Context, passphrase: String): Result = withContext(Dispatchers.IO) {
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
            life = lifeSnapshot(db)
        )

        val payload = JSONObject()
            .put("action", "push")
            .put("profileId", profileId)
            .put("passphrase", passphrase)
            .put("data", payloadData(built.data))
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
    suspend fun pull(context: Context, passphrase: String): Result = withContext(Dispatchers.IO) {
        if (!SyncPassphrase.isValid(passphrase)) return@withContext Result.INVALID_PASSPHRASE
        val profileId = UserIdentity.profileId(context)
        if (profileId.isBlank()) return@withContext Result.PROFILE_NOT_READY
        if (!UserIdentity.isValidProfileId(profileId)) return@withContext Result.PROFILE_NOT_READY

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
            return@withContext resultFor(error)
        }
        // A successful pull returns the stored snapshot (with a revision), not an `ok` flag.
        val incoming = runCatching {
            val json = JSONObject(response)
            require(json.has("revision")) { "pull response has no revision" }
            jsonToData(json.optJSONObject("data"))
        }.getOrElse { return@withContext Result.REJECTED }
        try {
            val plan = SyncApply.plan(built.data, tracked, incoming)
            db.applySyncPlan(plan)
            // Plan stamps override the freshly built ones for applied/adopted records; every
            // other stamp stays exactly as the local build minted it.
            metadata.save(built.stamps + plan.stamps)
            Result.SUCCESS
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.REJECTED
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
