package com.focusstudy.poc

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات الاقتراحات المتكيفة: تُبنى من بيانات فعلية، وتتنوع، وتُخفَّض
 * الأنواع التي يتجاهلها المستخدم.
 */
class AssistantSuggestionsTest {

    private fun snapshotWith(
        overdue: List<AssistantTaskRef> = emptyList(),
        focusHours: List<HourPattern> = emptyList(),
        focusSessionsToday: Int = 0,
        wirdDone: Boolean = false,
        wirdStreak: Int = 0,
        habitRates: Map<Long, Double> = emptyMap(),
        habitNames: Map<Long, String> = emptyMap(),
        spentToday: Double = 0.0,
        avgSpend: Double = 0.0,
        acceptance: Map<String, Double> = emptyMap()
    ): AssistantSnapshot {
        val learned = LearnedProfile(
            bestFocusHours = focusHours,
            preferredFocusMinutes = 25,
            avgDailySpend30d = avgSpend
        )
        val context = AssistantContext(
            overdueTasks = overdue,
            overdueCount = overdue.size,
            focusSessionsToday = focusSessionsToday,
            wirdStreakDays = wirdStreak,
            longestWirdStreakDays = wirdStreak,
            habitRates = habitRates,
            habitNames = habitNames,
            spentToday = spentToday,
            learned = learned,
            suggestionAcceptance = acceptance
        )
        return AssistantSnapshot(
            userName = "أحمد",
            wirdDoneToday = wirdDone,
            openTasksToday = overdue.size,
            nextTaskTitle = "مراجعة فيزياء",
            context = context
        )
    }

    @Test
    fun overdueTaskSuggestionNamesTheRealTask() {
        val snap = snapshotWith(overdue = listOf(AssistantTaskRef(1, "مراجعة فيزياء")))
        val suggestions = AssistantSuggestions.suggest(snap, LocalDateTime.of(2026, 11, 25, 14, 0))
        val late = suggestions.firstOrNull { it.kind == "late_task" }
        assertTrue(late != null)
        assertTrue(late!!.text.contains("مراجعة فيزياء"))
    }

    @Test
    fun focusInviteOnlyInBestHourAndBeforeAnySession() {
        val snap = snapshotWith(
            focusHours = listOf(HourPattern(10, 4, 0.8)),
            focusSessionsToday = 0
        )
        val atBestHour = AssistantSuggestions.suggest(snap, LocalDateTime.of(2026, 11, 25, 10, 0))
        assertTrue(atBestHour.any { it.kind == "focus_invite" })
        val afterSession = AssistantSuggestions.suggest(
            snap.copy(context = snap.context.copy(focusSessionsToday = 1)),
            LocalDateTime.of(2026, 11, 25, 10, 0)
        )
        assertFalse(afterSession.any { it.kind == "focus_invite" })
        val wrongHour = AssistantSuggestions.suggest(snap, LocalDateTime.of(2026, 11, 25, 14, 0))
        assertFalse(wrongHour.any { it.kind == "focus_invite" })
    }

    @Test
    fun celebrationNamesTheRealStreak() {
        val snap = snapshotWith(wirdDone = true, wirdStreak = 5)
        val suggestions = AssistantSuggestions.suggest(snap, LocalDateTime.of(2026, 11, 25, 14, 0))
        val celebration = suggestions.firstOrNull { it.kind == "celebration" }
        assertTrue(celebration != null)
        assertTrue(celebration!!.text.contains("5"))
    }

    @Test
    fun weakHabitNudgeNamesTheHabitAndRate() {
        val snap = snapshotWith(
            habitRates = mapOf(7L to 0.2),
            habitNames = mapOf(7L to "قراءة")
        )
        val suggestions = AssistantSuggestions.suggest(snap, LocalDateTime.of(2026, 11, 25, 14, 0))
        val nudge = suggestions.firstOrNull { it.kind == "habit_nudge" }
        assertTrue(nudge != null)
        assertTrue(nudge!!.text.contains("قراءة"))
        assertTrue(nudge.text.contains("20%"))
    }

    @Test
    fun ignoredKindIsDemoted() {
        val snap = snapshotWith(
            overdue = listOf(AssistantTaskRef(1, "مراجعة فيزياء")),
            acceptance = mapOf("late_task" to 0.1)
        )
        val suggestions = AssistantSuggestions.suggest(snap, LocalDateTime.of(2026, 11, 25, 14, 0))
        assertFalse(suggestions.any { it.kind == "late_task" })
    }

    @Test
    fun acceptedKindStays() {
        val snap = snapshotWith(
            overdue = listOf(AssistantTaskRef(1, "مراجعة فيزياء")),
            acceptance = mapOf("late_task" to 0.9)
        )
        val suggestions = AssistantSuggestions.suggest(snap, LocalDateTime.of(2026, 11, 25, 14, 0))
        assertTrue(suggestions.any { it.kind == "late_task" })
    }

    @Test
    fun noDataMeansNoSuggestionsInDaytime() {
        val snap = AssistantSnapshot(userName = "أحمد")
        val suggestions = AssistantSuggestions.suggest(snap, LocalDateTime.of(2026, 11, 25, 14, 0))
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun atMostFourSuggestions() {
        val snap = snapshotWith(
            overdue = listOf(AssistantTaskRef(1, "مراجعة فيزياء")),
            focusHours = listOf(HourPattern(10, 4, 0.8)),
            wirdDone = true,
            wirdStreak = 5,
            habitRates = mapOf(7L to 0.1),
            habitNames = mapOf(7L to "قراءة"),
            spentToday = 500.0,
            avgSpend = 100.0
        )
        val suggestions = AssistantSuggestions.suggest(snap, LocalDateTime.of(2026, 11, 25, 10, 0))
        assertTrue(suggestions.size <= 4)
        assertEquals(suggestions.size, suggestions.map { it.kind }.toSet().size)
    }
}
