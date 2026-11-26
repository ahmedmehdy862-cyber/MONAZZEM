package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pull planner is pure: per-record last-write-wins, tombstones delete, unsynced local data stays. */
class SyncApplyTest {

    private fun wird(day: String, pages: Int, updatedAt: Long, deleted: Boolean = false): Map<String, Any> {
        val record = LinkedHashMap<String, Any>()
        record["id"] = day
        record["day"] = day
        record["pages"] = pages
        record["surah"] = "البقرة"
        if (deleted) record["deleted"] = true
        record["updatedAt"] = updatedAt
        return record
    }

    private fun localWird(day: String, pages: Int, updatedAt: Long) = wird(day, pages, updatedAt)

    @Test fun newerIncomingWins() {
        val local = mapOf("wirdLogs" to listOf(localWird("2026-11-25", 2, 100L)))
        val tracked = mapOf("wirdLogs:2026-11-25" to SyncStamp("old", 100L))
        val plan = SyncApply.plan(local, tracked, mapOf("wirdLogs" to listOf(wird("2026-11-25", 4, 200L))))
        assertEquals(4, plan.upserts.getValue("wirdLogs").single()["pages"])
        assertEquals(200L, plan.stamps.getValue("wirdLogs:2026-11-25").updatedAt)
        assertFalse(plan.stamps.getValue("wirdLogs:2026-11-25").deleted)
    }

    @Test fun tieKeepsLocal() {
        val local = mapOf("wirdLogs" to listOf(localWird("2026-11-25", 2, 100L)))
        val tracked = mapOf("wirdLogs:2026-11-25" to SyncStamp("old", 100L))
        val plan = SyncApply.plan(local, tracked, mapOf("wirdLogs" to listOf(wird("2026-11-25", 4, 100L))))
        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.deletes.isEmpty())
        assertEquals(100L, plan.stamps.getValue("wirdLogs:2026-11-25").updatedAt)
    }

    @Test fun olderIncomingIsIgnored() {
        val local = mapOf("wirdLogs" to listOf(localWird("2026-11-25", 2, 300L)))
        val tracked = mapOf("wirdLogs:2026-11-25" to SyncStamp("old", 300L))
        val plan = SyncApply.plan(local, tracked, mapOf("wirdLogs" to listOf(wird("2026-11-25", 4, 200L))))
        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.deletes.isEmpty())
    }

    @Test fun newerTombstoneDeletesLocally() {
        val local = mapOf("wirdLogs" to listOf(localWird("2026-11-25", 2, 100L)))
        val tracked = mapOf("wirdLogs:2026-11-25" to SyncStamp("old", 100L))
        val plan = SyncApply.plan(local, tracked, mapOf("wirdLogs" to listOf(wird("2026-11-25", 2, 300L, deleted = true))))
        assertEquals(listOf("2026-11-25"), plan.deletes["wirdLogs"])
        assertTrue(plan.stamps.getValue("wirdLogs:2026-11-25").deleted)
        assertEquals(300L, plan.stamps.getValue("wirdLogs:2026-11-25").updatedAt)
    }

    @Test fun unsyncedLocalRowIsNeverOverwritten() {
        // No stamp: the local row was never synced, so the remote write loses even when newer.
        val local = mapOf("wirdLogs" to listOf(localWird("2026-11-25", 2, 100L)))
        val plan = SyncApply.plan(local, emptyMap(), mapOf("wirdLogs" to listOf(wird("2026-11-25", 4, 200L))))
        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.deletes.isEmpty())
        assertTrue(plan.stamps.isEmpty())
    }

    @Test fun identicalContentAdoptsStampWithoutAWrite() {
        val local = mapOf("wirdLogs" to listOf(localWird("2026-11-25", 2, 100L)))
        val plan = SyncApply.plan(local, emptyMap(), mapOf("wirdLogs" to listOf(wird("2026-11-25", 2, 500L))))
        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.deletes.isEmpty())
        assertEquals(500L, plan.stamps.getValue("wirdLogs:2026-11-25").updatedAt)
        assertFalse(plan.stamps.getValue("wirdLogs:2026-11-25").deleted)
    }

    @Test fun newerLiveRecordResurrectsAfterOldTombstone() {
        val tracked = mapOf("wirdLogs:2026-11-25" to SyncStamp("old", 100L, deleted = true))
        val plan = SyncApply.plan(emptyMap(), tracked, mapOf("wirdLogs" to listOf(wird("2026-11-25", 4, 200L))))
        assertEquals(4, plan.upserts.getValue("wirdLogs").single()["pages"])
        assertFalse(plan.stamps.getValue("wirdLogs:2026-11-25").deleted)
    }

    @Test fun unknownKindsAndMissingIdsAreIgnored() {
        val plan = SyncApply.plan(
            emptyMap(), emptyMap(),
            mapOf(
                "notes" to listOf(mapOf<String, Any>("id" to "1", "text" to "سر", "updatedAt" to 1L)),
                "wirdLogs" to listOf(mapOf<String, Any>("day" to "2026-11-25", "pages" to 2, "updatedAt" to 1L))
            )
        )
        assertTrue(plan.upserts.isEmpty())
        assertTrue(plan.deletes.isEmpty())
        assertTrue(plan.stamps.isEmpty())
    }
}
