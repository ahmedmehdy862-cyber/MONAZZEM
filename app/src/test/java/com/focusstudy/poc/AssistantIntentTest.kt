package com.focusstudy.poc

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات فهم النية من كلام حر — بما فيها المحادثات التي كان المساعد
 * القديم يفشل فيها (انظر docs/assistant-learning.md §3).
 */
class AssistantIntentTest {

    private val today = LocalDate.of(2026, 11, 25)
    private val zone = ZoneId.of("Africa/Cairo")

    private fun snapshot(wirdDone: Boolean = false) = AssistantSnapshot(
        userName = "أحمد",
        wirdDoneToday = wirdDone,
        openTasks = listOf(
            AssistantTaskRef(1, "مراجعة فيزياء"),
            AssistantTaskRef(2, "حل واجب رياضيات")
        ),
        nextTaskTitle = "مراجعة فيزياء"
    )

    private fun classify(text: String, snap: AssistantSnapshot = snapshot()) =
        AssistantIntentClassifier.classify(text, snap, today, zone)

    @Test
    fun askPlanAdviceNotMistakenForAddTask() {
        // الخطأ القديم: «أعمل» فعل إضافة فكان ينشئ مهمة بعنوان «إيه دلوقتي».
        assertTrue(classify("أعمل إيه دلوقتي؟") is ClassifiedIntent.AskPlanAdvice)
        assertTrue(classify("اعمل ايه دلوقتي") is ClassifiedIntent.AskPlanAdvice)
    }

    @Test
    fun futureEventProposesTask() {
        val intent = classify("عندي امتحان فيزياء بكرة")
        assertTrue(intent is ClassifiedIntent.ProposeTask)
        val proposed = intent as ClassifiedIntent.ProposeTask
        assertTrue(proposed.title.contains("امتحان فيزياء"))
        assertTrue(proposed.dueAt != null)
    }

    @Test
    fun futureEventWithoutDateStillProposes() {
        val intent = classify("لازم أخلص المشروع")
        assertTrue(intent is ClassifiedIntent.ProposeTask)
    }

    @Test
    fun bareDoneAsksSmartClarification() {
        val intent = classify("خلصت")
        assertTrue(intent is ClassifiedIntent.Clarify)
        val clarify = intent as ClassifiedIntent.Clarify
        assertTrue(clarify.options.any { it.contains("الورد") })
        assertTrue(clarify.options.any { it.contains("مراجعة فيزياء") })
    }

    @Test
    fun bareDoneWithoutWirdOrTasksHasNoClarification() {
        val snap = snapshot(wirdDone = true).copy(openTasks = emptyList())
        assertTrue(classify("خلصت", snap) is ClassifiedIntent.PassThrough)
    }

    @Test
    fun bareStartAsksWhatToStart() {
        val intent = classify("يلا")
        assertTrue(intent is ClassifiedIntent.Clarify)
        val clarify = intent as ClassifiedIntent.Clarify
        assertTrue(clarify.options.any { it.contains("جلسة تركيز") })
    }

    @Test
    fun daySummaryIntents() {
        assertTrue(classify("لخّص يومي") is ClassifiedIntent.AskDaySummary)
        assertTrue(classify("يومي عامل إيه؟") is ClassifiedIntent.AskDaySummary)
        assertTrue(classify("ملخص يومي") is ClassifiedIntent.AskDaySummary)
    }

    @Test
    fun moneyIntents() {
        assertTrue(classify("صرفت كام النهاردة؟") is ClassifiedIntent.AskMoney)
        assertTrue(classify("مصاريفي الشهر ده عاملة إيه؟") is ClassifiedIntent.AskMoney)
    }

    @Test
    fun memoryIntents() {
        val intent = classify("فاكر إني قلتلك بحب جلسات طويلة؟")
        assertTrue(intent is ClassifiedIntent.AskMemory)
    }

    @Test
    fun explicitAddTaskPassesThroughToEngine() {
        assertTrue(classify("ضيف مهمة مراجعة فيزياء الساعة 6") is ClassifiedIntent.PassThrough)
    }

    @Test
    fun ordinaryGreetingPassesThrough() {
        assertTrue(classify("صباح الخير") is ClassifiedIntent.PassThrough)
    }

    @Test
    fun proposeTaskSkippedWhenExplicitAddVerb() {
        // «ضيف مهمة امتحان فيزياء بكرة» أمر صريح لا اقتراح.
        assertTrue(classify("ضيف مهمة امتحان فيزياء بكرة") is ClassifiedIntent.PassThrough)
    }

    @Test
    fun clarifyOptionsAreRealDataNotTemplates() {
        val intent = classify("خلصت") as ClassifiedIntent.Clarify
        assertEquals(3, intent.options.size) // الورد + مهمتان مفتوحتان
    }
}
