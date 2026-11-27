package com.focusstudy.poc

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات محرك التعلّم المحلي — نقية (JVM) بمدخلات ثابتة.
 */
class AssistantLearnerTest {

    private val zone = ZoneId.of("Africa/Cairo")
    private val today = LocalDate.of(2026, 11, 25) // الثلاثاء

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun session(day: Int, startHour: Int, minutes: Int) =
        SessionLearningRow(at(day, startHour), at(day, startHour) + minutes * 60_000L, null)

    @Test
    fun focusHoursNeedsThreeSessionsInSameHour() {
        val signals = LearningSignals(
            sessions = listOf(
                session(20, 10, 25), session(21, 10, 30), session(22, 10, 25),
                session(23, 10, 40), session(24, 15, 25)
            ),
            zone = zone
        )
        val profile = AssistantLearner.learn(signals, today, 0L)
        assertEquals(1, profile.bestFocusHours.size)
        assertEquals(10, profile.bestFocusHours.first().hour)
        assertEquals(4, profile.bestFocusHours.first().count)
        assertEquals(0.8, profile.bestFocusHours.first().share, 0.001)
    }

    @Test
    fun focusHoursIgnoredWithTooFewSamples() {
        val signals = LearningSignals(
            sessions = listOf(session(24, 10, 25), session(24, 15, 25)),
            zone = zone
        )
        assertTrue(AssistantLearner.learn(signals, today, 0L).bestFocusHours.isEmpty())
    }

    @Test
    fun preferredFocusMinutesIsMedian() {
        val signals = LearningSignals(
            sessions = listOf(session(20, 9, 20), session(21, 9, 25), session(22, 9, 30)),
            zone = zone
        )
        assertEquals(25, AssistantLearner.learn(signals, today, 0L).preferredFocusMinutes)
    }

    @Test
    fun preferredFocusMinutesNullWithTooFewSessions() {
        val signals = LearningSignals(
            sessions = listOf(session(24, 9, 25), session(24, 10, 25)),
            zone = zone
        )
        assertNull(AssistantLearner.learn(signals, today, 0L).preferredFocusMinutes)
    }

    @Test
    fun categoryStatsCompletionAndLateness() {
        val tasks = listOf(
            TaskLearningRow(TaskCategory.STUDY, at(20, 8), at(20, 18), at(20, 12), TaskStatus.DONE),
            TaskLearningRow(TaskCategory.STUDY, at(21, 8), at(21, 10), at(21, 12), TaskStatus.DONE),
            TaskLearningRow(TaskCategory.STUDY, at(22, 8), at(24, 10), at(22, 12), TaskStatus.DONE),
            TaskLearningRow(TaskCategory.STUDY, at(23, 8), null, at(23, 12), TaskStatus.TODO)
        )
        val stats = AssistantLearner.categoryStats(tasks)
        assertEquals(1, stats.size)
        val study = stats.first()
        assertEquals(3, study.completed)
        assertEquals(4, study.total)
        assertEquals(0.75, study.completionRate, 0.001)
        assertEquals(2, study.late)
        assertEquals(2.0 / 3.0, study.lateRate, 0.001)
        assertTrue(study.avgDelayDays > 0)
    }

    @Test
    fun categoryStatsIgnoredWithTooFewTasks() {
        val tasks = listOf(
            TaskLearningRow(TaskCategory.WORK, at(24, 8), at(24, 9), at(24, 12), TaskStatus.DONE)
        )
        assertTrue(AssistantLearner.categoryStats(tasks).isEmpty())
    }

    @Test
    fun wirdStreakCountsConsecutiveDaysEndingToday() {
        val logs = (20..25).map { WirdLearningRow("2026-11-$it", at(it, 10)) }
        val (streak, longest, total) = AssistantLearner.wirdStreaks(logs, today)
        assertEquals(6, streak)
        assertEquals(6, longest)
        assertEquals(6, total)
    }

    @Test
    fun wirdStreakEndsYesterdayWhenTodayNotLoggedYet() {
        val logs = (20..24).map { WirdLearningRow("2026-11-$it", at(it, 10)) }
        val (streak, longest, _) = AssistantLearner.wirdStreaks(logs, today)
        assertEquals(5, streak)
        assertEquals(5, longest)
    }

    @Test
    fun wirdStreakBreaksOnGap() {
        val logs = listOf(20, 21, 23, 24, 25).map { WirdLearningRow("2026-11-$it", at(it, 10)) }
        val (streak, longest, _) = AssistantLearner.wirdStreaks(logs, today)
        assertEquals(3, streak)
        assertEquals(3, longest)
    }

    @Test
    fun moodOnFocusDaysComparedWithNoFocusDays() {
        val moods = (19..25).map { MoodLearningRow("2026-11-$it", if (it % 2 == 0) 5 else 3) }
        val signals = LearningSignals(
            sessions = listOf(session(20, 10, 25), session(22, 10, 25), session(24, 10, 25)),
            moods = moods,
            zone = zone
        )
        val (_, focusAvg, noFocusAvg) = AssistantLearner.moodAnalysis(signals, today, zone)
        // أيام التركيز (20، 22، 24) مزاجها 5؛ وأيام بلا تركيز (19، 21، 23، 25) مزاجها 3.
        assertEquals(5.0, focusAvg!!, 0.001)
        assertEquals(3.0, noFocusAvg!!, 0.001)
    }

    @Test
    fun moneyAnalysisTopCategoryAndDailyAverage() {
        val transactions = listOf(
            MoneyLearningRow(MoneyKind.EXPENSE, 100.0, "أكل", "2026-11-24"),
            MoneyLearningRow(MoneyKind.EXPENSE, 50.0, "أكل", "2026-11-25"),
            MoneyLearningRow(MoneyKind.EXPENSE, 40.0, "مواصلات", "2026-11-25"),
            MoneyLearningRow(MoneyKind.INCOME, 1000.0, "راتب", "2026-11-25")
        )
        val (top, avg) = AssistantLearner.moneyAnalysis(transactions, today)
        assertEquals("أكل", top)
        assertEquals(190.0 / 30.0, avg, 0.01)
    }

    @Test
    fun emptySignalsMeansNotEnoughData() {
        val profile = AssistantLearner.learn(LearningSignals(zone = zone), today, 0L)
        assertFalse(profile.hasEnoughData)
        assertTrue(profile.bestFocusHours.isEmpty())
        assertNull(profile.bestWirdHour)
        assertNull(profile.preferredFocusMinutes)
        assertEquals(0, profile.wirdStreakDays)
    }
}
