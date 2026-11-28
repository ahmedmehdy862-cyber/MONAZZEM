package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android push payload is whitelisted and stamped without touching the local database. */
class SyncPayloadTest {

    private fun task() = StudyTask(
        id = 1L,
        title = "مراجعة الوحدة الأولى",
        plannedMinutes = 30,
        doneMinutes = 0,
        isDone = false,
        blockedPackages = emptySet(),
        createdAt = 100L,
        notes = "ملاحظة خاصة لا تخرج من الجهاز"
    )

    private fun subject() = StudySubject(
        id = 7L,
        semesterId = null,
        name = "الرياضيات",
        color = 0,
        icon = "book",
        instructor = "",
        goalMinutes = 60,
        createdAt = 100L,
        nextExamAt = null
    )

    @Test fun notesNeverEnterThePayload() {
        val built = SyncPayload.build(listOf(task()), listOf(subject()), emptyList(), emptyMap(), 1_000L)
        val serialized = built.data.toString()
        assertFalse(serialized.contains("ملاحظة خاصة لا تخرج من الجهاز"))
        assertFalse(serialized.contains("notes"))
        assertEquals(setOf("id", "title", "minutes", "category", "priority", "status", "recurrence", "updatedAt"),
            built.data.getValue("tasks").single().keys)
        assertEquals(setOf("id", "name", "updatedAt"), built.data.getValue("subjects").single().keys)
    }

    @Test fun unchangedRecordsKeepTheirStamp() {
        val source = task()
        val first = SyncPayload.build(listOf(source), emptyList(), emptyList(), emptyMap(), 1_000L)
        assertEquals(1_000L, first.data.getValue("tasks").single()["updatedAt"])

        val second = SyncPayload.build(listOf(source), emptyList(), emptyList(), first.stamps, 5_000L)
        assertEquals(1_000L, second.data.getValue("tasks").single()["updatedAt"])
    }

    @Test fun editedRecordsGetAFreshStamp() {
        val first = SyncPayload.build(listOf(task()), emptyList(), emptyList(), emptyMap(), 1_000L)
        val edited = task().copy(title = "مراجعة الوحدة الثانية")
        val next = SyncPayload.build(listOf(edited), emptyList(), emptyList(), first.stamps, 5_000L)
        assertEquals(5_000L, next.data.getValue("tasks").single()["updatedAt"])
    }

    @Test fun forceRefreshRestampsUnchangedRecordsForReseal() {
        val first = SyncPayload.build(listOf(task()), emptyList(), emptyList(), emptyMap(), 1_000L)
        assertEquals(1_000L, first.data.getValue("tasks").single()["updatedAt"])
        // Without the flag an unchanged record keeps its stamp…
        val kept = SyncPayload.build(listOf(task()), emptyList(), emptyList(), first.stamps, 5_000L)
        assertEquals(1_000L, kept.data.getValue("tasks").single()["updatedAt"])
        // …with it, the same content is re-stamped at `now` (design §2.2, Option A),
        // and the new stamp is the one the next build keeps.
        val resealed = SyncPayload.build(
            listOf(task()), emptyList(), emptyList(), first.stamps, 5_000L, forceRefresh = true
        )
        assertEquals(5_000L, resealed.data.getValue("tasks").single()["updatedAt"])
        val after = SyncPayload.build(listOf(task()), emptyList(), emptyList(), resealed.stamps, 9_000L)
        assertEquals(5_000L, after.data.getValue("tasks").single()["updatedAt"])
    }

    @Test fun removedRecordsBecomePersistentTombstones() {
        val first = SyncPayload.build(listOf(task()), emptyList(), emptyList(), emptyMap(), 1_000L)
        val removed = SyncPayload.build(emptyList(), emptyList(), emptyList(), first.stamps, 7_000L)
        val tombstone = removed.data.getValue("tasks").single()
        assertEquals("1", tombstone["id"])
        assertEquals(true, tombstone["deleted"])
        assertEquals(7_000L, tombstone["updatedAt"])

        val repeated = SyncPayload.build(emptyList(), emptyList(), emptyList(), removed.stamps, 9_000L)
        assertEquals(1, repeated.data.getValue("tasks").size)
        assertEquals(7_000L, repeated.data.getValue("tasks").single()["updatedAt"])
    }

    @Test fun lifeCollectionsCarryTheirIdsAndFields() {
        val life = LifeSyncSnapshot(
            habits = listOf(Habit(id = 3L, name = "قراءة", createdAt = 500L)),
            habitLogs = listOf(HabitLogRow(habitId = 3L, day = "2026-11-26")),
            wirdLogs = listOf(WirdLog(day = "2026-11-26", pages = 2, surah = "البقرة")),
            healthLogs = listOf(HealthLog(day = "2026-11-26", weight = 70.5, sleepHours = 7.0, waterCups = 6, exerciseMinutes = 30)),
            transactions = listOf(
                MoneyTransaction(id = 8L, kind = MoneyKind.EXPENSE, amount = 12.5, category = "طعام", note = "ملاحظة مالية سرية", day = "2026-11-26", createdAt = 400L)
            ),
            moodLogs = listOf(MoodLog(day = "2026-11-26", mood = 4)),
            calorieLogs = listOf(CalorieEntry(id = 5L, day = "2026-11-26", name = "أرز", calories = 300, createdAt = 400L))
        )
        val built = SyncPayload.build(emptyList(), emptyList(), emptyList(), emptyMap(), 1_000L, life)
        assertEquals(setOf("tasks", "subjects", "taskTypes", "habits", "habitLogs", "wirdLogs", "healthLogs", "transactions", "moodLogs", "calorieLogs"), built.data.keys)
        assertEquals("3", built.data.getValue("habits").single()["id"])
        assertEquals("3:2026-11-26", built.data.getValue("habitLogs").single()["id"])
        assertEquals("2026-11-26", built.data.getValue("wirdLogs").single()["id"])
        assertEquals("2026-11-26", built.data.getValue("healthLogs").single()["id"])
        assertEquals("2026-11-26", built.data.getValue("moodLogs").single()["id"])
        // A money note never leaves the device, like task notes.
        val serialized = built.data.toString()
        assertFalse(serialized.contains("ملاحظة مالية سرية"))
        assertFalse(serialized.contains("note"))
        assertEquals(setOf("id", "kind", "amount", "category", "day", "createdAt", "updatedAt"),
            built.data.getValue("transactions").single().keys)
    }

    @Test fun removedLifeRecordsBecomePersistentTombstones() {
        val life = LifeSyncSnapshot(
            habits = listOf(Habit(id = 3L, name = "قراءة", createdAt = 500L)),
            wirdLogs = listOf(WirdLog(day = "2026-11-26", pages = 2, surah = "البقرة"))
        )
        val first = SyncPayload.build(emptyList(), emptyList(), emptyList(), emptyMap(), 1_000L, life)
        val removed = SyncPayload.build(emptyList(), emptyList(), emptyList(), first.stamps, 7_000L, LifeSyncSnapshot())
        assertEquals(true, removed.data.getValue("habits").single()["deleted"])
        assertEquals("3", removed.data.getValue("habits").single()["id"])
        assertEquals(true, removed.data.getValue("wirdLogs").single()["deleted"])
        assertEquals("2026-11-26", removed.data.getValue("wirdLogs").single()["id"])
        val repeated = SyncPayload.build(emptyList(), emptyList(), emptyList(), removed.stamps, 9_000L, LifeSyncSnapshot())
        assertEquals(7_000L, repeated.data.getValue("habits").single()["updatedAt"])
        assertEquals(7_000L, repeated.data.getValue("wirdLogs").single()["updatedAt"])
    }

    @Test fun unchangedLifeRecordsKeepTheirStamp() {
        val life = LifeSyncSnapshot(
            wirdLogs = listOf(WirdLog(day = "2026-11-26", pages = 2, surah = "البقرة")),
            moodLogs = listOf(MoodLog(day = "2026-11-26", mood = 4))
        )
        val first = SyncPayload.build(emptyList(), emptyList(), emptyList(), emptyMap(), 1_000L, life)
        val second = SyncPayload.build(emptyList(), emptyList(), emptyList(), first.stamps, 5_000L, life)
        assertEquals(1_000L, second.data.getValue("wirdLogs").single()["updatedAt"])
        assertEquals(1_000L, second.data.getValue("moodLogs").single()["updatedAt"])
    }

    @Test fun contentFingerprintIgnoresNumberTypes() {
        assertEquals(
            SyncPayload.contentFingerprint(mapOf("pages" to 2, "surah" to "البقرة")),
            SyncPayload.contentFingerprint(mapOf("surah" to "البقرة", "pages" to 2L))
        )
        assertEquals(
            SyncPayload.contentFingerprint(mapOf("amount" to 12.5)),
            SyncPayload.contentFingerprint(mapOf("amount" to 12.5))
        )
    }

    @Test fun archivedTypesAreIncludedWithTheirFlag() {
        val type = TaskType(id = "work", name = "عمل", archived = true)
        val built = SyncPayload.build(emptyList(), emptyList(), listOf(type), emptyMap(), 1_000L)
        val record = built.data.getValue("taskTypes").single()
        assertEquals("work", record["id"])
        assertTrue(record["archived"] as Boolean)
    }
}
