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
 *
 * H1 - end-to-end encryption (audit 11.1): since 0.35.0 every pushed record travels as a
 * versioned v2 envelope ({id, updatedAt, v:2, enc}) encrypted with AES-256-GCM under a key
 * derived from the sync passphrase ([SyncCrypto]); the server stores ciphertext only and
 * merges on the plaintext id/updatedAt. Old plaintext (v1) records are still read, so
 * published 0.34.0 clients keep working, and losing the passphrase means the synced copy
 * can never be recovered - there is no server recovery.
 */
internal object SyncAccount {

    internal enum class Result { SUCCESS, INVALID_PASSPHRASE, PROFILE_NOT_READY, NETWORK_ERROR, REJECTED }

    suspend fun register(context: Context, passphrase: String): Result = withContext(Dispatchers.IO) {
        if (!SyncPassphrase.isValid(passphrase)) return@withContext Result.INVALID_PASSPHRASE
        val profileId = UserIdentity.profileId(context)
        if (profileId.isBlank()) return@withContext Result.PROFILE_NOT_READY
        if (!UserIdentity.isValidProfileId(profileId)) return@withContext Result.PROFILE_NOT_READY

        // M1 (H2): first registration must prove ownership of the profile id — the
        // server checks that this install id owns it in the user registry, so
        // knowing the profile id alone can no longer register the account.
        val uid = UserIdentity.id(context)
        fun attempt(currentPassphrase: String?): String {
            val payload = JSONObject()
                .put("action", "register")
                .put("profileId", profileId)
                .put("passphrase", passphrase)
                .put("uid", uid)
            if (currentPassphrase != null) payload.put("currentPassphrase", currentPassphrase)
            return TeamMessages.request("${TeamMessages.BASE}/api/sync", "POST", payload.toString())
        }
        val response = runCatching { attempt(null) }.getOrElse { error ->
            // Linking to an already-created account needs the current passphrase;
            // retry once with it so a single entry suffices.
            if (error.message?.contains("http 401") != true) {
                return@withContext if (error.message?.contains("http 403") == true) {
                    Result.REJECTED
                } else {
                    Result.NETWORK_ERROR
                }
            }
            runCatching { attempt(passphrase) }.getOrElse { retryError ->
                return@withContext if (retryError.message?.contains("http 401") == true ||
                    retryError.message?.contains("http 403") == true
                ) {
                    Result.REJECTED
                } else {
                    Result.NETWORK_ERROR
                }
            }
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

        val key = SyncCrypto.deriveKey(passphrase, profileId)
        val payload = JSONObject()
            .put("action", "push")
            .put("profileId", profileId)
            .put("passphrase", passphrase)
            .put("syncVersion", SyncCrypto.SYNC_VERSION)
            .put("data", payloadData(encryptSnapshot(key, built.data)))
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

    /**
     * P-02: يحذف نسخة المزامنة وحساب المزامنة من الخادم نهائيًا (مصادقة بكلمة
     * المرور). بعد نجاح الحذف تُمسح الأختام المحلية فيبدأ أي ربط لاحق من جديد.
     */
    suspend fun deleteAccount(context: Context, passphrase: String): Result = withContext(Dispatchers.IO) {
        if (!SyncPassphrase.isValid(passphrase)) return@withContext Result.INVALID_PASSPHRASE
        val profileId = UserIdentity.profileId(context)
        if (profileId.isBlank()) return@withContext Result.PROFILE_NOT_READY
        if (!UserIdentity.isValidProfileId(profileId)) return@withContext Result.PROFILE_NOT_READY
        val payload = JSONObject()
            .put("action", "delete")
            .put("profileId", profileId)
            .put("passphrase", passphrase)
        val response = try {
            TeamMessages.request("${TeamMessages.BASE}/api/sync", "POST", payload.toString())
        } catch (error: Exception) {
            return@withContext if (error.message?.contains("http 401") == true) {
                Result.REJECTED
            } else {
                Result.NETWORK_ERROR
            }
        }
        val ok = runCatching { JSONObject(response).optBoolean("ok") }.getOrDefault(false)
        if (!ok) return@withContext Result.REJECTED
        SyncMetadata.get(context).clear()
        Result.SUCCESS
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

        val key = SyncCrypto.deriveKey(passphrase, profileId)
        val payload = JSONObject()
            .put("action", "pull")
            .put("profileId", profileId)
            .put("passphrase", passphrase)
            .put("syncVersion", SyncCrypto.SYNC_VERSION)
        val response = try {
            TeamMessages.request("${TeamMessages.BASE}/api/sync", "POST", payload.toString())
        } catch (error: Exception) {
            return@withContext Result.NETWORK_ERROR
        }
        // A successful pull returns the stored snapshot (with a revision), not an `ok` flag.
        val incoming = runCatching {
            val json = JSONObject(response)
            require(json.has("revision")) { "pull response has no revision" }
            jsonToData(json.optJSONObject("data"), key)
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

    /**
     * H1: يشفّر كل سجل غير محذوف في مغلف v2 ({id, updatedAt, v:2, enc})؛
     * المغلفات المحذوفة (tombstones) بلا محتوى فتُرسل كما هي. عميل v1
     * يقرأ فقط السجلات غير المشفرة (الخادم يجرّد المغلفات عنه).
     */
    private fun encryptSnapshot(
        key: ByteArray,
        data: Map<String, List<Map<String, Any>>>
    ): Map<String, List<Map<String, Any>>> {
        val out = LinkedHashMap<String, List<Map<String, Any>>>()
        for ((kind, records) in data) {
            val encrypted = ArrayList<Map<String, Any>>()
            for (record in records) {
                val id = record["id"]?.toString() ?: continue
                val updatedAt = record["updatedAt"] ?: continue
                if (record["deleted"] == true) {
                    encrypted.add(record)
                    continue
                }
                val fields = record.filterKeys { it != "id" && it != "updatedAt" && it != "deleted" }
                val envelope = LinkedHashMap<String, Any>()
                envelope["id"] = id
                envelope["updatedAt"] = updatedAt
                envelope["v"] = SyncCrypto.VERSION
                envelope["enc"] = SyncCrypto.encryptRecord(key, kind, id, fields)
                encrypted.add(envelope)
            }
            out[kind] = encrypted
        }
        return out
    }

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

    /**
     * Converts a pulled `data` object into record maps; integral JSON numbers become Long.
     * H1: سجلات v2 تُفكّ هنا (المفتاح مشتق من كلمة المرور)؛ سجل يتعذر فكه
     * يُتخطى بهدوء ويُسجَّل ولا يكسر السحب كله.
     */
    private fun jsonToData(
        data: JSONObject?,
        key: ByteArray
    ): Map<String, List<Map<String, Any>>> {
        val out = LinkedHashMap<String, List<Map<String, Any>>>()
        if (data == null) return out
        for (kind in SyncPayload.collections) {
            val array = data.optJSONArray(kind) ?: continue
            val records = ArrayList<Map<String, Any>>()
            var skipped = 0
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optInt("v", 1) == SyncCrypto.VERSION) {
                    val id = obj.optString("id")
                    val enc = obj.optString("enc")
                    if (id.isBlank() || enc.isBlank()) {
                        skipped++
                        continue
                    }
                    val fields = SyncCrypto.decryptRecord(key, kind, id, enc)
                    if (fields == null) {
                        skipped++
                        continue
                    }
                    val record = LinkedHashMap<String, Any>(fields)
                    record["id"] = id
                    record["updatedAt"] = obj.optLong("updatedAt")
                    if (obj.optBoolean("deleted", false)) record["deleted"] = true
                    records.add(record)
                    continue
                }
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
            if (skipped > 0) {
                android.util.Log.w(
                    "SyncAccount",
                    "skipped $skipped undecryptable v2 records in $kind"
                )
            }
            out[kind] = records
        }
        return out
    }
}
