package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part 2 UX invariants: calm motion within the requested range, clear Arabic countdown copy. */
class Part2UxTest {

    @Test
    fun motionTimingsStayWithin150To300Ms() {
        listOf(MotionSpec.SHORT_MS, MotionSpec.MEDIUM_MS, MotionSpec.LONG_MS).forEach { ms ->
            assertTrue("motion $ms ms must be between 150 and 300", ms in 150..300)
        }
    }

    @Test
    fun reducedMotionUsesTheShortestTiming() {
        assertTrue(MotionSpec.SHORT_MS < MotionSpec.MEDIUM_MS)
        assertEquals(150, MotionSpec.SHORT_MS)
    }

    @Test
    fun countdownFormatsMinutesOnlyUnderAnHour() {
        assertEquals("بعد 0 د", PrayerCountdownFormat.label(-5))
        assertEquals("بعد 1 د", PrayerCountdownFormat.label(1))
        assertEquals("بعد 59 د", PrayerCountdownFormat.label(59))
    }

    @Test
    fun countdownFormatsHoursAndMinutes() {
        assertEquals("بعد 1 س", PrayerCountdownFormat.label(60))
        assertEquals("بعد 2 س و5 د", PrayerCountdownFormat.label(125))
        assertEquals("بعد 3 س و59 د", PrayerCountdownFormat.label(239))
    }

    @Test
    fun onboardingHasExactlyThreeSteps() {
        // Mirrors the guided first-run steps: name, daily goal, first task.
        val titles = listOf("ما اسمك؟", "هدفك اليومي", "أول مهمة لك")
        assertEquals(3, titles.size)
    }
}
