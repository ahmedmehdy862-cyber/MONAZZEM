package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5 acceptance: adding a task with nothing but a name must succeed. */
class TaskDraftTest {

    @Test fun nameOnlyCreatesAValidTaskWithTheSameDefaults() {
        val draft = TaskDraft.fromName("  مراجعة الفيزياء  ")
        assertNotNull(draft)
        assertEquals("مراجعة الفيزياء", draft!!.title)
        assertEquals(TaskDraft.DEFAULT_MINUTES, draft.minutes)
        assertEquals(30, draft.minutes)
        assertEquals(TaskCategory.STUDY, draft.category)
        assertEquals(TaskPriority.MEDIUM, draft.priority)
        assertEquals(Recurrence.NONE, draft.recurrence)
        assertNull(draft.dueAt)
        assertNull(draft.subjectId)
        assertNull(draft.typeId)
        assertTrue(draft.isValid())
    }

    @Test fun blankNameIsRejected() {
        assertNull(TaskDraft.fromName("   "))
        assertFalse(TaskDraft.canAdd("   ", "30"))
        assertFalse(TaskDraft.fromName("مهمة")!!.copy(title = " ").isValid())
    }

    @Test fun emptyMinutesUsesTheDefaultInsteadOfBlockingTheAdd() {
        assertTrue(TaskDraft.canAdd("مهمة", ""))
        assertEquals(30, TaskDraft.minutesFrom(""))
        assertEquals(30, TaskDraft.minutesFrom("   "))
    }

    @Test fun outOfRangeOrInvalidMinutesAreRejected() {
        assertFalse(TaskDraft.canAdd("مهمة", "0"))
        assertFalse(TaskDraft.canAdd("مهمة", "721"))
        assertFalse(TaskDraft.canAdd("مهمة", "abc"))
        assertFalse(TaskDraft.fromName("مهمة")!!.copy(minutes = 0).isValid())
    }

    @Test fun validOptionalMinutesAreKept() {
        assertTrue(TaskDraft.canAdd("مهمة", "45"))
        assertEquals(45, TaskDraft.minutesFrom("45"))
        assertEquals(1, TaskDraft.minutesFrom("1"))
        assertEquals(720, TaskDraft.minutesFrom("720"))
    }

    @Test fun advancedFieldsSurviveTheDraft() {
        val draft = TaskDraft(
            title = "تسليم تقرير",
            minutes = 90,
            category = TaskCategory.WORK,
            priority = TaskPriority.URGENT,
            dueAt = "2026-11-20",
            recurrence = Recurrence.WEEKLY,
            notes = "ملاحظة خاصة",
            subjectId = 7L,
            typeId = "type-2"
        )
        assertTrue(draft.isValid())
        assertEquals("تسليم تقرير", draft.title)
        assertEquals(90, draft.minutes)
        assertEquals(TaskCategory.WORK, draft.category)
        assertEquals(TaskPriority.URGENT, draft.priority)
        assertEquals("2026-11-20", draft.dueAt)
        assertEquals(Recurrence.WEEKLY, draft.recurrence)
        assertEquals("ملاحظة خاصة", draft.notes)
        assertEquals(7L, draft.subjectId)
        assertEquals("type-2", draft.typeId)
    }
}
