package com.focusstudy.poc

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات الدفعة A من التدقيق النهائي (2026-11-28):
 * A1 تطبيع عربي + صيغ الهوية/التحية، A2 النبرة والملف والتعلّم والتأكيد
 * والمدة، A8 أولوية المنبهات، A9 بصمة SHA-256.
 */
class AssistantFinalAuditTest {

    private val today = LocalDate.of(2026, 11, 25)
    private val zone = ZoneId.of("Africa/Cairo")
    private val settings = AssistantSettings(name = "منظم", tone = AssistantTone.PLAYFUL)

    private fun snapshot() = AssistantSnapshot(
        userName = "أحمد",
        wirdDoneToday = false,
        openTasks = listOf(
            AssistantTaskRef(1, "مراجعة فيزياء"),
            AssistantTaskRef(2, "حل واجب رياضيات")
        ),
        nextTaskTitle = "مراجعة فيزياء"
    )

    private fun reply(
        text: String,
        snap: AssistantSnapshot = snapshot(),
        tone: AssistantTone = AssistantTone.PLAYFUL,
        profileText: String = ""
    ) = AssistantEngine.reply(
        text, snap, settings.copy(tone = tone), emptyList(), profileText,
        today = today, zone = zone
    )

    // ---- A1: التطبيع ----

    @Test
    fun normalizeArabicMapsAlefTehAndAlefMaksura() {
        assertEquals("انت مين", AssistantEngine.normalizeArabic("أنتا مين"))
        assertEquals("انت مين", AssistantEngine.normalizeArabic("انتا مين"))
        assertEquals("مراجعه فيزياء", AssistantEngine.normalizeArabic("مراجعة فيزياء"))
        assertEquals("عامل ايه", AssistantEngine.normalizeArabic("عامل أيه"))
        assertEquals("صلاه الفجر", AssistantEngine.normalizeArabic("صلاة الفجر"))
    }

    @Test
    fun normalizeArabicStripsDiacriticsAndTatweel() {
        assertEquals("انت مين", AssistantEngine.normalizeArabic("أَنْتَا مِين"))
        assertEquals("سلام", AssistantEngine.normalizeArabic("سـلام"))
        assertEquals("لخص يومي", AssistantEngine.normalizeArabic("لخّص يومي"))
    }

    @Test
    fun identityVariantsAnswerWithIdentityNotFallback() {
        listOf("انتا مين", "مين انتا", "عرف نفسك", "انت مين", "مين انت", "من انت", "مين اللي بيكلمني").forEach { variant ->
            val answer = reply(variant).text
            assertTrue("«$variant» يجب أن يجيب بالهوية، جاء: $answer", answer.contains("أنا منظم"))
            assertFalse("«$variant» يجب ألا يسقط في الاحتياطي", answer.contains("لسه مش من الحاجات"))
        }
    }

    @Test
    fun greetingVariantsAnswerWithGreetingNotFallback() {
        listOf("عامل اي", "عامل أيه", "عامل ايه", "ازيك عامل اي", "ازيك", "صباح الخير").forEach { variant ->
            val answer = reply(variant).text
            assertTrue("«$variant» يجب أن يجيب بتحية، جاء: $answer", answer.contains("أهل"))
            assertFalse("«$variant» يجب ألا يسقط في الاحتياطي", answer.contains("فهمت نص الكلام"))
        }
    }

    // ---- A2: النبرة ----

    @Test
    fun toneWrapChangesContentAnswersNotJustGreeting() {
        val playful = reply("لخّص يومي", tone = AssistantTone.PLAYFUL).text
        val formal = reply("لخّص يومي", tone = AssistantTone.FORMAL).text
        val calm = reply("لخّص يومي", tone = AssistantTone.CALM).text
        assertTrue(formal.contains("في الخدمة"))
        assertTrue(calm.contains("خطوة بخطوة"))
        assertFalse(playful.contains("في الخدمة"))
        assertFalse(playful.contains("خطوة بخطوة"))
        assertTrue(formal.contains("ملخص يومك"))
    }

    // ---- A2: تحليل الملف التعريفي ----

    @Test
    fun parseProfileFactsExtractsLabeledLinesOnly() {
        val text = """
            الاسم: أحمد مهدي
            هدفي: إنهاء الثانوية بتفوق
            روتيني: أنام 11 وأصحى 6
            كلام بلا وسم لا يُفسَّر
            وزني: 70 كجم
        """.trimIndent()
        val facts = AssistantEngine.parseProfileFacts(text)
        assertEquals(4, facts.size)
        assertTrue(facts.any { it.category == "اسم" && it.text == "أحمد مهدي" })
        assertTrue(facts.any { it.category == "هدف" && it.text.contains("الثانوية") })
        assertTrue(facts.any { it.category == "روتين" })
        assertTrue(facts.any { it.category == "صحة" })
        assertFalse(facts.any { it.text.contains("بلا وسم") })
    }

    @Test
    fun parseProfileFactsKeepsUnlabeledTextVerbatim() {
        val text = "أنا شخص يحب القراءة والهدوء."
        assertTrue(AssistantEngine.parseProfileFacts(text).isEmpty())
        // بلا وسوم: «تعرف عني إيه؟» تقتبس النص حرفيًا بدل اختلاق معنى.
        val answer = reply("تعرف عني إيه؟", profileText = text).text
        assertTrue(answer.contains("أنا شخص يحب القراءة"))
    }

    @Test
    fun parsedProfileGoalAppearsInGreetingAndPlan() {
        val profile = "هدفي: حفظ جزء عم"
        val snap = snapshot()
        val greeting = AssistantEngine.greeting(
            settings, snap, AssistantEngine.parseProfileFacts(profile)
        )
        assertTrue(greeting.contains("حفظ جزء عم"))
        // بلا مهام مستعجلة ولا ورد معلّق: الخطة تقترح خطوة من هدف الملف التعريفي.
        val emptySnap = snap.copy(nextTaskTitle = null, openTasks = emptyList(), wirdDoneToday = true)
        val plan = reply("أعمل إيه دلوقتي؟", snap = emptySnap, profileText = profile).text
        assertTrue(plan.contains("حفظ جزء عم"))
    }

    // ---- A2: تأكيد جلسة التركيز + المدة ----

    @Test
    fun focusInviteQuestionAsksConfirmationInsteadOfStarting() {
        val result = reply("أبدأ جلسة تركيز؟")
        assertNull(result.action)
        assertTrue(result.chips.any { it.contains("أيوه ابدأ جلسة تركيز") })
        assertTrue(result.text.contains("أبدأ لك جلسة"))
    }

    @Test
    fun focusInviteForNamedTaskConfirmationCarriesTheTask() {
        val result = reply("أبدأ جلسة تركيز لمهمة مراجعة فيزياء؟")
        assertNull(result.action)
        assertTrue(result.chips.any { it.contains("مراجعة فيزياء") })
    }

    @Test
    fun directFocusCommandStillStartsImmediately() {
        val result = reply("ابدأ جلسة تركيز لمهمة مراجعة فيزياء")
        assertTrue(result.action is AssistantAction.StartFocus)
        val action = result.action as AssistantAction.StartFocus
        assertEquals(1L, action.taskId)
    }

    @Test
    fun addTaskTakesDurationFromUserWords() {
        val result = reply("ضيف مهمة مراجعة فيزياء لمدة ساعتين")
        val action = result.action as? AssistantAction.AddTask
        assertNotNull(action)
        assertEquals("مراجعة فيزياء", action!!.title)
        assertEquals(120, action.plannedMinutes)
    }

    @Test
    fun addTaskWithoutDurationLeavesItNull() {
        val result = reply("ضيف مهمة مراجعة فيزياء")
        val action = result.action as? AssistantAction.AddTask
        assertNotNull(action)
        assertNull(action!!.plannedMinutes)
    }

    // ---- A2: حلقة الاقتراحات ----

    @Test
    fun suggestionNotSuppressedAfterSingleUntappedShow() {
        val learned = LearnedProfile()
        val context = AssistantContext(
            overdueTasks = listOf(AssistantTaskRef(1, "مراجعة فيزياء")),
            overdueCount = 1,
            learned = learned,
            suggestionAcceptance = mapOf("late_task" to 0.0),
            suggestionShows = mapOf("late_task" to 1)
        )
        val snap = snapshot().copy(context = context)
        val suggestions = AssistantSuggestions.suggest(snap, LocalDateTime.of(2026, 11, 25, 14, 0))
        assertTrue(suggestions.any { it.kind == "late_task" })
    }

    @Test
    fun suggestionSuppressedOnlyAfterFiveUntappedShows() {
        val learned = LearnedProfile()
        val context = AssistantContext(
            overdueTasks = listOf(AssistantTaskRef(1, "مراجعة فيزياء")),
            overdueCount = 1,
            learned = learned,
            suggestionAcceptance = mapOf("late_task" to 0.0),
            suggestionShows = mapOf("late_task" to 5)
        )
        val snap = snapshot().copy(context = context)
        val suggestions = AssistantSuggestions.suggest(snap, LocalDateTime.of(2026, 11, 25, 14, 0))
        assertFalse(suggestions.any { it.kind == "late_task" })
    }

    // ---- A8: أولوية المنبهات ----

    @Test
    fun prioritizeUpcomingTakesNearestFirstAndCountsDropped() {
        val now = 1_000_000L
        val reminders = (1..35).map { i ->
            StoredReminder(id = i.toLong(), taskId = i.toLong(), kind = "task_due", fireAt = now + (36 - i) * 60_000L)
        }.shuffled()
        val (scheduled, dropped) = ReminderEngine.prioritizeUpcoming(reminders, now)
        assertEquals(30, scheduled.size)
        assertEquals(5, dropped)
        assertEquals(now + 60_000L, scheduled.first().fireAt)
        assertTrue(scheduled.zipWithNext().all { (a, b) -> a.fireAt <= b.fireAt })
    }

    // ---- A9: SHA-256 ----

    @Test
    fun sha256OfKnownContentMatches() {
        val file = File.createTempFile("sha-test", ".bin")
        try {
            file.writeText("abc")
            // SHA-256("abc") المعروفة.
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                DirectUpdateDownloader.sha256Of(file)
            )
            assertTrue(DirectUpdateDownloader.matchesSha256(file, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
            assertFalse(DirectUpdateDownloader.matchesSha256(file, "00".repeat(32)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun matchesSha256ToleratesFeedsWithoutHash() {
        val file = File.createTempFile("sha-test", ".bin")
        try {
            file.writeText("abc")
            assertTrue(DirectUpdateDownloader.matchesSha256(file, null))
            assertTrue(DirectUpdateDownloader.matchesSha256(file, ""))
            assertTrue(DirectUpdateDownloader.matchesSha256(file, "  "))
        } finally {
            file.delete()
        }
    }
}

// ملاحظة: إعادة تشغيل CI بعد فشلين سريعين بلا تقارير (46123674217، 46124831269).
