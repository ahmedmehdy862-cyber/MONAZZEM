package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * تغطية شروح التمارين (0.34.0): كل تمرين من الـ 72 في الخطط الأربع له
 * دليل كامل (≥3 خطوات، ماكينة أو أدوات، عضلات مستهدفة) ورسم توضيحي.
 */
class ExerciseGuideTest {

    @Test fun everyExerciseHasCompleteGuideAndPose() {
        val exercises = allExercises()
        assertEquals("عدد التمارين في الخطط الأربع", 72, exercises.size)
        assertEquals("لا تمرين مكرر", 72, exercises.map { it.id }.toSet().size)
        for (exercise in exercises) {
            val guide = exerciseGuide(exercise.id)
            assertNotNull("لا دليل للتمرين ${exercise.id}", guide)
            requireNotNull(guide)
            assertTrue("دليل ${exercise.id}: أقل من 3 خطوات", guide.steps.size >= 3)
            assertTrue(
                "دليل ${exercise.id}: بلا ماكينة ولا أدوات",
                guide.machine != null || guide.equipment.isNotEmpty()
            )
            assertTrue("دليل ${exercise.id}: بلا عضلات مستهدفة", guide.targetMuscles.isNotEmpty())
            assertEquals(
                "رسم ${exercise.id} ليس من نوع «مرسوم»",
                "drawn",
                guide.mediaKind
            )
            assertTrue("التمرين ${exercise.id}: بلا رسم توضيحي", hasExercisePose(exercise.id))
        }
    }

    @Test fun guideIdsMatchPlanIdsExactly() {
        val planIds = allExercises().map { it.id }.toSet()
        val guideIds = exerciseGuides.keys
        assertEquals("أدلة زائدة عن الخطط", emptySet<String>(), guideIds - planIds)
        assertEquals("تمارين بلا أدلة", emptySet<String>(), planIds - guideIds)
    }
}
