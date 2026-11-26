package com.focusstudy.poc

/**
 * Pure pull planner: decides, record by record, what a pulled snapshot does to the local
 * database. It never touches SQLite, so it is unit-testable on the JVM; [StudyDb.applySyncPlan]
 * only executes the resulting [SyncPlan].
 *
 * Rules (windows-sync contract §5 — per-record last-write-wins on `updatedAt`):
 * - An incoming record newer than our last synced stamp for that key wins: upsert it, or
 *   delete the local row when it is a tombstone (`deleted: true`).
 * - On an `updatedAt` tie the local row wins; the next push re-asserts it.
 * - A record with no stamp and no local row is applied and its stamp adopted as-is.
 * - A record with no stamp whose local row has identical content only adopts the stamp
 *   (no database write); a differing local row is kept as newer — the next push stamps it
 *   with a fresh `updatedAt` and it wins on the server. Local data that was never synced is
 *   never overwritten by a pull.
 * - Stamps for applied records are taken from the incoming record ([SyncPayload.contentFingerprint]
 *   + incoming `updatedAt`), never re-stamped with "now": that is what keeps a row deleted by
 *   a tombstone from coming back on the next push.
 *
 * Known edge: a local delete that has not been pushed yet has no timestamp of its own — its
 * stamp is still the pre-delete one — so a remote write newer than that stamp resurrects the
 * row on pull. That is the documented LWW outcome; the delete only wins once it has been
 * pushed (when it carries a fresh `updatedAt`).
 */
internal data class SyncPlan(
    val upserts: Map<String, List<Map<String, Any>>>,
    val deletes: Map<String, List<String>>,
    /** Full stamp map: [tracked] plus one override per applied/adopted record. */
    val stamps: Map<String, SyncStamp>
)

internal object SyncApply {

    fun plan(
        localData: Map<String, List<Map<String, Any>>>,
        tracked: Map<String, SyncStamp>,
        incoming: Map<String, List<Map<String, Any>>>
    ): SyncPlan {
        val upserts = LinkedHashMap<String, MutableList<Map<String, Any>>>()
        val deletes = LinkedHashMap<String, MutableList<String>>()
        val stamps = LinkedHashMap<String, SyncStamp>(tracked)

        for (kind in SyncPayload.collections) {
            val records = incoming[kind] ?: continue
            for (record in records) {
                val id = record["id"]?.toString().orEmpty()
                if (id.isEmpty()) continue
                val key = "$kind:$id"
                val updatedAt = (record["updatedAt"] as? Number)?.toLong() ?: 0L
                val deleted = record["deleted"] == true
                val fingerprint = SyncPayload.contentFingerprint(record)
                val stamp = tracked[key]
                val local = localData[kind]?.firstOrNull { it["id"]?.toString() == id }

                val apply = when {
                    stamp != null -> updatedAt > stamp.updatedAt
                    local == null -> true
                    SyncPayload.contentFingerprint(local) == fingerprint -> {
                        // Same content, first time we see its stamp: adopt it, no write.
                        stamps[key] = SyncStamp(fingerprint, updatedAt)
                        false
                    }
                    else -> false // Unsynced local change wins; the next push stamps it.
                }
                if (!apply) continue
                if (deleted) {
                    deletes.getOrPut(kind) { ArrayList() }.add(id)
                    stamps[key] = SyncStamp(fingerprint, updatedAt, deleted = true)
                } else {
                    upserts.getOrPut(kind) { ArrayList() }.add(record)
                    stamps[key] = SyncStamp(fingerprint, updatedAt)
                }
            }
        }
        return SyncPlan(upserts = upserts, deletes = deletes, stamps = stamps)
    }
}
