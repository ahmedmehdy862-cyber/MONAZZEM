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

    @Test fun archivedTypesAreIncludedWithTheirFlag() {
        val type = TaskType(id = "work", name = "عمل", archived = true)
        val built = SyncPayload.build(emptyList(), emptyList(), listOf(type), emptyMap(), 1_000L)
        val record = built.data.getValue("taskTypes").single()
        assertEquals("work", record["id"])
        assertTrue(record["archived"] as Boolean)
    }
}
