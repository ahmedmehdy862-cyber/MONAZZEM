package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part 4: one merged category selector, minimal default view, sticky Add button. */
class Part4TaskComposerTest {

    @Test fun categorySelectorContainsExactlyTheEightRequestedChoices() {
        val expected = listOf("دراسة", "جامعة", "عمل", "شخصي", "ديني", "حياة", "عادات", "تطوع")
        assertEquals(expected, TaskComposer.CATEGORIES.map { it.label })
    }

    @Test fun categorySelectorHasNoDuplicates() {
        val labels = TaskComposer.CATEGORIES.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
        val keys = TaskComposer.CATEGORIES.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun quickDurationChipsAre15_30_60_90() {
        assertEquals(listOf(15, 30, 60, 90), TaskComposer.QUICK_MINUTES)
        assertTrue(TaskComposer.isQuick(30))
        assertTrue(!TaskComposer.isQuick(45))
    }

    @Test fun customDurationFallsBackToTheDefaultWhenEmpty() {
        assertEquals(30, TaskComposer.quickOrDefault(0))
        assertEquals(30, TaskComposer.quickOrDefault(45))
        assertEquals(90, TaskComposer.quickOrDefault(90))
    }

    @Test fun legacyOtherCategoryStillResolvesForOldData() {
        assertEquals(TaskCategory.OTHER, TaskCategory.from("other"))
        assertEquals(TaskCategory.STUDY, TaskCategory.from(null))
    }
}
