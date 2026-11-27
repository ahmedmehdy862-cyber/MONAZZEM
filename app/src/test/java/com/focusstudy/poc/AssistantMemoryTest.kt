package com.focusstudy.poc

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * اختبارات ذاكرة المساعد وسلاسل المتابعة على قاعدة بيانات حقيقية
 * (Robolectric + SQLite) — دون جهاز.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssistantMemoryTest {

    private lateinit var context: Context
    private lateinit var db: StudyDb

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        runCatching { StudyDb.get(context).close() }
        context.deleteDatabase(DB_FILE)
        db = StudyDb.get(context)
        db.writableDatabase.close()
    }

    @After
    fun tearDown() {
        runCatching { StudyDb.get(context).close() }
        runCatching { context.deleteDatabase(DB_FILE) }
    }

    @Test
    fun memoryRoundTripAndTouch() {
        val id = db.saveAssistantMemory("بحب جلسات 45 دقيقة", "روتين", "chat", 0.8)
        assertTrue(id > 0)
        val memories = db.assistantMemories()
        assertEquals(1, memories.size)
        assertEquals("بحب جلسات 45 دقيقة", memories.first().text)
        assertEquals(0, memories.first().useCount)

        db.touchAssistantMemory(id, 1000L)
        val touched = db.assistantMemories().first()
        assertEquals(1, touched.useCount)
        assertTrue(touched.confidence > 0.8)

        db.deleteAssistantMemory(id)
        assertTrue(db.assistantMemories().isEmpty())
    }

    @Test
    fun memoryConfidenceDecaysInStore() {
        val id = db.saveAssistantMemory("ذكرى قديمة", "عام", "chat", 0.5)
        db.updateAssistantMemoryConfidence(id, 0.1)
        assertEquals(0.1, db.assistantMemories().first().confidence, 0.001)
    }

    @Test
    fun profileEntriesRoundTrip() {
        val entries = listOf(
            AssistantProfileEntry("best_focus_hour", "أفضل وقت تركيز: الساعة 10:00 (4 جلسات)", 10.0, 0.8, 4, 1000L),
            AssistantProfileEntry("wird_streak", "سلسلة الورد الحالية: 5 أيام متواصلة", 5.0, 0.9, 6, 1000L)
        )
        db.saveAssistantProfile(entries)
        val saved = db.assistantProfile()
        assertEquals(2, saved.size)
        assertEquals("best_focus_hour", saved.first().key)
        assertEquals(10.0, saved.first().realValue!!, 0.001)

        // إعادة الحفظ تحدث البند نفسه لا تكرره.
        db.saveAssistantProfile(
            listOf(AssistantProfileEntry("best_focus_hour", "أفضل وقت تركيز: الساعة 11:00 (5 جلسات)", 11.0, 0.85, 5, 2000L))
        )
        val updated = db.assistantProfile()
        assertEquals(2, updated.size)
        assertEquals("أفضل وقت تركيز: الساعة 11:00 (5 جلسات)", updated.first { it.key == "best_focus_hour" }.textValue)
    }

    @Test
    fun interactionStatsCountAccepted() {
        db.recordAssistantInteraction("late_task", "اقتراح 1", accepted = true)
        db.recordAssistantInteraction("late_task", "اقتراح 2", accepted = false)
        db.recordAssistantInteraction("late_task", "اقتراح 3", accepted = true)
        val stats = db.assistantInteractionStats()
        assertEquals(3, stats.size)
        assertEquals(2, stats.count { it.accepted })
        val acceptance = AssistantMemoryStore.suggestionAcceptance(context)
        assertEquals(2.0 / 3.0, acceptance["late_task"]!!, 0.001)
    }

    @Test
    fun followUpUpsertDoesNotResetAttempts() {
        val now = 1000L
        db.upsertAssistantFollowUp(
            kind = "wird_morning", slot = "morning", day = "2026-11-25",
            attempts = 1, maxAttempts = 5, nextAt = 0L, state = "active",
            snoozeUntil = null, detail = "صفحات 2–3.", now = now
        )
        val created = db.assistantFollowUp("wird_morning", "morning", "2026-11-25")
        assertTrue(created != null)
        assertEquals(1, created!!.attempts)

        db.updateAssistantFollowUpAttempts(created.id, 3, now + 1)
        // إعادة الإنشاء في اليوم نفسه لا تصفّر العدّاد (CONFLICT_IGNORE).
        db.upsertAssistantFollowUp(
            kind = "wird_morning", slot = "morning", day = "2026-11-25",
            attempts = 1, maxAttempts = 5, nextAt = 0L, state = "active",
            snoozeUntil = null, detail = "صفحات 2–3.", now = now + 2
        )
        assertEquals(3, db.assistantFollowUp("wird_morning", "morning", "2026-11-25")!!.attempts)

        db.updateAssistantFollowUpState(created.id, "done", now + 3)
        assertTrue(db.assistantFollowUpsActive().isEmpty())
        assertEquals("done", db.assistantFollowUp("wird_morning", "morning", "2026-11-25")!!.state)
    }

    @Test
    fun followUpIsDoneWhenWirdLogged() {
        val day = "2026-11-25"
        assertEquals(false, AssistantFollowUpScheduler.isDone(context, FollowUpKind.WIRD_MORNING, day))
        assertTrue(db.logWird(day, 2, "البقرة"))
        assertEquals(true, AssistantFollowUpScheduler.isDone(context, FollowUpKind.WIRD_MORNING, day))
    }

    @Test
    fun followUpIsDoneWhenExerciseLogged() {
        val day = "2026-11-25"
        assertEquals(false, AssistantFollowUpScheduler.isDone(context, FollowUpKind.EXERCISE, day))
        db.setExerciseDone("ex-1", day, true)
        assertEquals(true, AssistantFollowUpScheduler.isDone(context, FollowUpKind.EXERCISE, day))
    }

    @Test
    fun entriesFromLearnedProfile() {
        val profile = LearnedProfile(
            bestFocusHours = listOf(HourPattern(10, 4, 0.8)),
            bestWirdHour = HourPattern(22, 3, 0.6),
            preferredFocusMinutes = 25,
            mostDelayedCategory = TaskCategory.STUDY,
            categoryStats = listOf(
                CategoryLearning(TaskCategory.STUDY, 3, 4, 0.75, 2, 2.0 / 3.0, 1.5)
            ),
            wirdStreakDays = 5,
            longestWirdStreakDays = 7,
            wirdTotalDays = 20,
            samples = 30,
            computedAt = 1000L
        )
        val entries = AssistantMemoryStore.entriesFrom(profile, 1000L)
        assertTrue(entries.any { it.key == "best_focus_hour" && it.textValue.contains("10:00") })
        assertTrue(entries.any { it.key == "best_wird_hour" && it.textValue.contains("22:00") })
        assertTrue(entries.any { it.key == "preferred_focus_minutes" })
        assertTrue(entries.any { it.key == "most_delayed_category" && it.textValue.contains("دراسة") })
        assertTrue(entries.any { it.key == "wird_streak" && it.textValue.contains("5") })
    }

    private companion object {
        const val DB_FILE = "focus_study.db"
    }
}
