package com.focusstudy.poc

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات سياسة المتابعة «الزنان» (رياضيات نقية) + أيام خطة التمارين.
 */
class AssistantFollowUpTest {

    @Test
    fun persistentLevelIntervalsEscalate() {
        val intervals = AssistantFollowUpPolicy.intervalsMinutes(AssistantNagLevel.PERSISTENT)
        assertEquals(listOf(30, 60, 120, 180), intervals)
        assertEquals(5, AssistantNagLevel.PERSISTENT.maxAttempts)
    }

    @Test
    fun balancedLevelHasThreeAttempts() {
        assertEquals(listOf(60, 120), AssistantFollowUpPolicy.intervalsMinutes(AssistantNagLevel.BALANCED))
        assertEquals(3, AssistantNagLevel.BALANCED.maxAttempts)
    }

    @Test
    fun quietLevelIsOneShot() {
        assertTrue(AssistantFollowUpPolicy.intervalsMinutes(AssistantNagLevel.QUIET).isEmpty())
        assertEquals(1, AssistantNagLevel.QUIET.maxAttempts)
    }

    @Test
    fun nextDelayFollowsAttempts() {
        val level = AssistantNagLevel.PERSISTENT
        assertEquals(30 * 60_000L, AssistantFollowUpPolicy.nextDelayMillis(level, 1))
        assertEquals(60 * 60_000L, AssistantFollowUpPolicy.nextDelayMillis(level, 2))
        assertEquals(120 * 60_000L, AssistantFollowUpPolicy.nextDelayMillis(level, 3))
        assertEquals(180 * 60_000L, AssistantFollowUpPolicy.nextDelayMillis(level, 4))
        assertNull(AssistantFollowUpPolicy.nextDelayMillis(level, 5))
    }

    @Test
    fun shouldContinueStopsAtMaxAttempts() {
        val level = AssistantNagLevel.BALANCED
        assertTrue(AssistantFollowUpPolicy.shouldContinue(level, 1))
        assertTrue(AssistantFollowUpPolicy.shouldContinue(level, 2))
        assertFalse(AssistantFollowUpPolicy.shouldContinue(level, 3))
    }

    @Test
    fun escalationCopyChangesWithAttempt() {
        val first = AssistantFollowUpPolicy.titleFor(FollowUpKind.WIRD_MORNING, 1)
        val second = AssistantFollowUpPolicy.titleFor(FollowUpKind.WIRD_MORNING, 2)
        val fourth = AssistantFollowUpPolicy.titleFor(FollowUpKind.WIRD_MORNING, 4)
        assertTrue(first != second)
        assertTrue(second != fourth)
        assertTrue(fourth.contains("مستنيك"))
        val body = AssistantFollowUpPolicy.bodyFor(FollowUpKind.WIRD_MORNING, 4, "صفحات 2–3.")
        assertTrue(body.contains("مش النهاردة"))
    }

    @Test
    fun nagLevelDefaultsToPersistent() {
        assertEquals(AssistantNagLevel.PERSISTENT, AssistantNagLevel.from(null))
        assertEquals(AssistantNagLevel.PERSISTENT, AssistantNagLevel.from("unknown"))
        assertEquals(AssistantNagLevel.QUIET, AssistantNagLevel.from("quiet"))
    }

    @Test
    fun exercisePlanDaysAreSatMonWed() {
        assertTrue(ExerciseReminderScheduler.isPlanDay(DayOfWeek.SATURDAY))
        assertTrue(ExerciseReminderScheduler.isPlanDay(DayOfWeek.MONDAY))
        assertTrue(ExerciseReminderScheduler.isPlanDay(DayOfWeek.WEDNESDAY))
        assertFalse(ExerciseReminderScheduler.isPlanDay(DayOfWeek.SUNDAY))
        assertFalse(ExerciseReminderScheduler.isPlanDay(DayOfWeek.FRIDAY))
    }

    @Test
    fun nextPlanOccurrenceSkipsNonPlanDays() {
        val zone = ZoneId.of("Africa/Cairo")
        // الجمعة 2026-11-27 الساعة 19:00 → السبت 2026-11-28 الساعة 18:00.
        val fridayEvening = LocalDateTime.of(2026, 11, 27, 19, 0).atZone(zone).toInstant().toEpochMilli()
        val next = ExerciseReminderScheduler.nextPlanOccurrence(18, 0, fridayEvening, zone)
        val expected = LocalDateTime.of(2026, 11, 28, 18, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, next)
        // السبت 17:00 → السبت نفسه 18:00.
        val saturdayBefore = LocalDateTime.of(2026, 11, 28, 17, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, ExerciseReminderScheduler.nextPlanOccurrence(18, 0, saturdayBefore, zone))
        // السبت 19:00 → الاثنين 18:00.
        val saturdayAfter = LocalDateTime.of(2026, 11, 28, 19, 0).atZone(zone).toInstant().toEpochMilli()
        val monday = LocalDateTime.of(2026, 11, 30, 18, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(monday, ExerciseReminderScheduler.nextPlanOccurrence(18, 0, saturdayAfter, zone))
    }

    @Test
    fun exerciseDetailNamesDayAndPlan() {
        val saturday = LocalDate.of(2026, 11, 28)
        val detail = ExerciseReminderScheduler.exerciseDetail(saturday)
        assertTrue(detail.contains("السبت"))
        assertTrue(detail.contains("3 أيام"))
    }
}
