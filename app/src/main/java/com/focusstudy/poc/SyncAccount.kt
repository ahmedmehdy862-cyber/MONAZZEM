package com.focusstudy.poc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Opt-in account pairing over the existing Vercel Blob endpoint.
 *
 * The key is the server-issued [UserIdentity.profileId]; a display name, install id, device id or
 * push token is never used or sent as an account key. The passphrase is sent only in the request
 * body and is not persisted on the device. Pairing and a one-way data push are both opt-in.
 */
internal object SyncAccount {

    internal enum class Result { SUCCESS, INVALID_PASSPHRASE, PROFILE_NOT_READY, NETWORK_ERROR, REJECTED }

    suspend fun register(context: Context, passphrase: String): Result = withContext(Dispatchers.IO) {
        if (!SyncPassphrase.isValid(passphrase)) return@withContext Result.INVALID_PASSPHRASE
        val profileId = UserIdentity.profileId(context)
        if (profileId.isBlank()) return@withContext Result.PROFILE_NOT_READY
        if (!UserIdentity.isValidProfileId(profileId)) return@withContext Result.PROFILE_NOT_READY

        val payload = JSONObject()
            .put("action", "register")
            .put("profileId", profileId)
            .put("passphrase", passphrase)
        val response = try {
            TeamMessages.request("${TeamMessages.BASE}/api/sync", "POST", payload.toString())
        } catch (error: Exception) {
            return@withContext Result.NETWORK_ERROR
        }
        val ok = runCatching { JSONObject(response).optBoolean("ok") }.getOrDefault(false)
        if (ok) Result.SUCCESS else Result.REJECTED
    }

    /**
     * Push the whitelisted local collections once the account is active. Task notes, the private
     * notebook and all device/push identifiers stay on the device: [SyncPayload] copies only the
     * fields the backend accepts. Fingerprints are saved only after the server accepted the push,
     * so a failed request never makes a local change look already synchronized.
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
            now = now
        )

        val data = JSONObject()
        for ((kind, records) in built.data) {
            val array = org.json.JSONArray()
            for (record in records) {
                val item = JSONObject()
                for ((key, value) in record) item.put(key, value)
                array.put(item)
            }
            data.put(kind, array)
        }
        val payload = JSONObject()
            .put("action", "push")
            .put("profileId", profileId)
            .put("passphrase", passphrase)
            .put("data", data)
        val response = try {
            TeamMessages.request("${TeamMessages.BASE}/api/sync", "POST", payload.toString())
        } catch (error: Exception) {
            return@withContext Result.NETWORK_ERROR
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
}
