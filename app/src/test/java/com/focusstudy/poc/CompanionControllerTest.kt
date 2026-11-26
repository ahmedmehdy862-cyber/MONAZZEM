package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات آلة حالات الرفيق ثلاثي الأبعاد (0.34.0، المواصفة §17).
 * المتحكم خالص بلا اعتماد على أندرويد؛ الساعة وهمية هنا حتى يبقى
 * الاختبار JVM عاديًا بلا Robolectric.
 */
class CompanionControllerTest {

    private var fakeNow = 1_000L
    private fun controller() = CompanionController(nowMillis = { fakeNow })

    @Test fun defaultStateIsIdleAndEnabled() {
        val c = controller()
        assertEquals(CompanionState.IDLE, c.current)
        assertTrue(c.isEnabled)
    }

    @Test fun greetMovesToGreetingAndReturnsToIdle() {
        val c = controller()
        c.greet()
        assertEquals(CompanionState.GREETING, c.current)
        c.returnToIdle(CompanionState.GREETING)
        assertEquals(CompanionState.IDLE, c.current)
    }

    @Test fun returnToIdleIgnoresAStaleExpectedState() {
        val c = controller()
        c.greet()
        c.celebrateAchievement()
        // عودة متأخرة باسم الحركة القديمة لا تلغي الحركة الأحدث.
        c.returnToIdle(CompanionState.GREETING)
        assertEquals(CompanionState.ACHIEVEMENT, c.current)
        c.returnToIdle(CompanionState.ACHIEVEMENT)
        assertEquals(CompanionState.IDLE, c.current)
    }

    @Test fun newEventReplacesPendingOneShotWithoutQueueing() {
        val c = controller()
        c.greet()
        c.encourage()
        assertEquals(CompanionState.ENCOURAGEMENT, c.current)
        c.celebrateAchievement()
        assertEquals(CompanionState.ACHIEVEMENT, c.current)
    }

    @Test fun thinkingLoopsUntilPlayIdle() {
        val c = controller()
        c.think()
        assertEquals(CompanionState.THINKING, c.current)
        // الـ loop لا تعود وحدها بانتهاء مدتها.
        c.returnToIdle(CompanionState.THINKING)
        assertEquals(CompanionState.THINKING, c.current)
        c.playIdle()
        assertEquals(CompanionState.IDLE, c.current)
    }

    @Test fun requestingTheSameLoopStateDoesNotRestartIt() {
        val c = controller()
        c.think()
        fakeNow += 500L
        val stamped = c.lastRequestUptimeMillis
        c.think()
        assertEquals(stamped, c.lastRequestUptimeMillis)
        assertEquals(CompanionState.THINKING, c.current)
    }

    @Test fun disabledBlocksEveryRequest() {
        val c = controller()
        c.setEnabled(false)
        assertEquals(CompanionState.DISABLED, c.current)
        assertFalse(c.isEnabled)
        c.greet()
        c.encourage()
        c.celebrateAchievement()
        c.think()
        assertEquals(CompanionState.DISABLED, c.current)
    }

    @Test fun reEnablingReturnsToIdle() {
        val c = controller()
        c.greet()
        c.setEnabled(false)
        c.setEnabled(true)
        assertEquals(CompanionState.IDLE, c.current)
        assertTrue(c.isEnabled)
        c.greet()
        assertEquals(CompanionState.GREETING, c.current)
    }

    @Test fun releaseDisables() {
        val c = controller()
        c.celebrateAchievement()
        c.release()
        assertEquals(CompanionState.DISABLED, c.current)
    }

    @Test fun requestsAreStampedWithTheInjectedClock() {
        val c = controller()
        assertEquals(0L, c.lastRequestUptimeMillis)
        fakeNow = 42_000L
        c.greet()
        assertEquals(42_000L, c.lastRequestUptimeMillis)
    }

    @Test fun animationNamesMatchTheGlbExactly() {
        assertEquals("IDLE", CompanionState.IDLE.gltfName)
        assertEquals("GREETING", CompanionState.GREETING.gltfName)
        assertEquals("ENCOURAGEMENT", CompanionState.ENCOURAGEMENT.gltfName)
        assertEquals("ACHIEVEMENT", CompanionState.ACHIEVEMENT.gltfName)
        assertEquals("THINKING", CompanionState.THINKING.gltfName)
    }

    @Test fun oneShotDurationsMatchTheAnimationReference() {
        assertEquals(2_500L, CompanionState.GREETING.durationMillis)
        assertEquals(2_500L, CompanionState.ENCOURAGEMENT.durationMillis)
        assertEquals(3_000L, CompanionState.ACHIEVEMENT.durationMillis)
        assertFalse(CompanionState.GREETING.loop)
        assertFalse(CompanionState.ACHIEVEMENT.loop)
        assertTrue(CompanionState.IDLE.loop)
        assertTrue(CompanionState.THINKING.loop)
    }
}
