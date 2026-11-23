package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Part 3 regression tests for the seven issues seen in the screenshots. */
class Part3BugFixTest {

    private val zone = ZoneId.of("Africa/Cairo")
    private val today = LocalDate.of(2026, 11, 23)

    private fun task(
        id: Long = 1,
        minutes: Int = 30,
        doneMinutes: Int = 0,
        status: TaskStatus = TaskStatus.TODO,
        isDone: Boolean = status == TaskStatus.DONE,
        dueDate: LocalDate? = null,
        originalDueAt: Long? = null
    ) = StudyTask(
        id = id,
        title = "Task $id",
        plannedMinutes = minutes,
        doneMinutes = doneMinutes,
        isDone = isDone,
        blockedPackages = emptySet(),
        createdAt = id,
        status = status,
        dueAt = dueDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
        originalDueAt = originalDueAt
    )

    // Bug 1 — date header formatting
    @Test fun arabicDateCarriesThePassedDayAndSuffix() {
        val formatted = ArabicDate.format(LocalDate.of(2026, 3, 4))
        assertTrue(formatted.contains("4") || formatted.contains("٤"))
        assertTrue(ArabicDate.withSuffix(today, "رتّب يومك").endsWith("رتّب يومك"))
    }

    // Bug 2 — one status source of truth
    @Test fun completedTaskIsRecognisedFromStatusOrLegacyFlag() {
        assertTrue(TaskState.completed(task(status = TaskStatus.DONE, doneMinutes = 0)))
        assertTrue(TaskState.completed(task(status = TaskStatus.TODO, isDone = true)))
        assertFalse(TaskState.completed(task(status = TaskStatus.CANCELLED, isDone = true)))
    }

    @Test fun completedWithoutLoggedMinutesIsNotShownAsZeroOfThirty() {
        val label = TaskState.progressText(task(status = TaskStatus.DONE, doneMinutes = 0))
        assertFalse(label.contains("0 من"))
        assertTrue(label.contains("أُنجزت يدويًا"))
    }

    @Test fun overdueBadgeCountsDaysFromTheCurrentDate() {
        val late = task(dueDate = today.minusDays(3))
        assertEquals("متأخرة بـ 3 أيام", TaskState.badge(late, today, zone))
        assertEquals(3, TaskState.overdueDays(late, today, zone))
        assertEquals(DisplayTaskState.OVERDUE, TaskState.of(late, today, zone))
    }

    @Test fun onlyOneDueDateIsDisplayed() {
        val rescheduled = task(dueDate = today, originalDueAt = today.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli())
        val text = TaskState.dueText(rescheduled, zone)
        assertTrue(text.contains("الموعد"))
        assertFalse(text.contains("كان"))
    }

    // Bug 3 — subject name stays clean
    @Test fun examDateIsNeverAppendedToSubjectName() {
        val examDate = LocalDate.of(2026, 11, 16)
        val name = SubjectInput.nameForSave("  Chem  ")
        assertEquals("Chem", name)
        assertFalse(name.contains("2026"))
        assertEquals("2026-11-16T00:00", SubjectInput.examMillis(examDate, zone)?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime().toString().take(16)
        })
    }

    @Test fun daysLeftCountdownCoversTodayAndPastDates() {
        assertEquals("الامتحان اليوم", SubjectInput.daysLeftText(0))
        assertEquals("باقي يوم واحد", SubjectInput.daysLeftText(1))
        assertEquals("باقي 5 أيام", SubjectInput.daysLeftText(5))
        assertEquals("انقضى الموعد", SubjectInput.daysLeftText(-2))
        val exam = today.plusDays(4).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(4, SubjectInput.daysLeft(exam, today, zone))
    }

    // Bug 4 — FAB and dock clearance
    @Test fun contentPaddingClearsFabAndNavigationDock() {
        assertTrue(LayoutSpec.CONTENT_BOTTOM_DP >= LayoutSpec.FAB_BOTTOM_DP + LayoutSpec.FAB_SIZE_DP)
        assertTrue(LayoutSpec.FAB_BOTTOM_DP >= LayoutSpec.DOCK_HEIGHT_DP + LayoutSpec.DOCK_BOTTOM_DP)
    }

    // Bug 7 — stale Smart Plan blocks
    @Test fun completedAndPastDueBlocksAreReportedAsStale() {
        val done = PlannedStudyBlock(today, task(status = TaskStatus.DONE), 30)
        val pastDue = PlannedStudyBlock(today, task(dueDate = today.minusDays(2)), 30)
        val fresh = PlannedStudyBlock(today, task(dueDate = today.plusDays(2)), 30)
        assertEquals("المهمة مكتملة", StudyPlanner.staleReason(done, today, zone))
        assertEquals("انتهى موعدها", StudyPlanner.staleReason(pastDue, today, zone))
        assertNull(StudyPlanner.staleReason(fresh, today, zone))
    }
}
