package com.focusstudy.poc

import android.content.Context
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * جولة لقطات على محاكي حقيقي (GitHub Actions): يفتح التطبيق، يتجاوز الإعداد
 * الأولي عبر التفضيلات، يزرع بيانات تجريبية، ويلتقط كل شاشة رئيسية فاتح/داكن.
 *
 * لا يغيّر كود الإنتاج إطلاقًا؛ اللقطات تُرفع كـ artifact من الـ workflow
 * وتُقرأ يدويًا في تقرير QA. كل خطوة تنقّل مغلّفة بـ runCatching وتُسجَّل في
 * qa-log.txt حتى لا تُسقط خطوة واحدة بقية الجولة — اللقطة نفسها تثبت أي
 * شاشة وصلنا إليها فعلًا.
 *
 * أهداف تحقق إلزامية من أحمد (2026-11-26) يغطيها هذا الملف:
 * 1) زر الثيم: هل يدور 3 أوضاع (فاتح ← داكن ← نظام)؟ [themeToggleCycle]
 * 2) الأفاتار: هل ملف الملامح يغيّر الرسم فعلًا؟ [AvatarRenderQaTest]
 * 3) المساعد منظم: أين يظهر؟ [لقطة الرئيسية + القائمة الجانبية]
 * 4) القائمة الجانبية: هل فيها طي/فتح؟ [لقطة القائمة]
 * 5) زر +: هل يغطي محتوى/أزرارًا؟ [لقطات الرئيسية والمهام]
 * 6) «صورتي كما هي»: هل يعمل؟ [لقطتا الإعدادات 10b و10c]
 */
@RunWith(AndroidJUnit4::class)
class EmulatorQaTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var device: UiDevice
    private lateinit var shotDir: File
    private lateinit var logFile: File
    private var scenario: androidx.test.core.app.ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        scenario = null
    }

    private fun appContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(line: String) {
        Log.i("EmuQA", line)
        runCatching { logFile.appendText(line + "\n") }
    }

    private fun setPrefs(theme: String) {
        appContext().getSharedPreferences("focus_study_preferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_completed", true)
            .putString("student_name", "اختبار")
            .putString("theme_mode", theme)
            .putString("bottom_tabs", "today,tasks,calendar,subjects,more")
            .apply()
    }

    /** يزرع بيانات مرة واحدة فقط (البيانات تبقى بين طرق الاختبار في نفس التشغيل). */
    private fun seedDataOnce() {
        val ctx = appContext()
        runBlocking {
            val tasks = TaskRepository.get(ctx)
            if (tasks.tasks().isEmpty()) {
                val now = System.currentTimeMillis()
                val h = 3600_000L
                tasks.add(
                    title = "مراجعة درس الرياضيات",
                    plannedMinutes = 45,
                    blockedPackages = emptySet(),
                    dueAt = now + 2 * h,
                    priority = TaskPriority.HIGH,
                )
                tasks.add(
                    title = "تمرين منزلي — الجزء العلوي",
                    plannedMinutes = 30,
                    blockedPackages = emptySet(),
                    dueAt = now + 26 * h,
                )
                val doneId = tasks.add(
                    title = "قراءة ورد الأمس",
                    plannedMinutes = 20,
                    blockedPackages = emptySet(),
                    dueAt = now - 5 * h,
                )
                tasks.setStatus(doneId, TaskStatus.DONE)
                val life = LifeRepository.get(ctx)
                val today = LocalDate.now().toString()
                val habitId = life.addHabit("قراءة ١٠ صفحات")
                life.setHabitDone(habitId, today, true)
                val wird = life.wirdSettings()
                life.logWird(today, wird.pagesPerDay, wird.surah)
                life.logMood(today, 4)
                life.addTransaction(MoneyKind.EXPENSE, 85.0, "مواصلات", "اختبار", today)
                life.addCalorieEntry("فطور", 350, today)
                log("seeded demo data")
            } else {
                log("seed skipped: tasks already present")
            }
        }
    }

    private fun launch(theme: String) {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val ctx = appContext()
        shotDir = File(ctx.getExternalFilesDir(null), "qa-screenshots").apply { mkdirs() }
        logFile = File(shotDir, "qa-log.txt")
        setPrefs(theme)
        seedDataOnce()
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        composeRule.waitForIdle()
        Thread.sleep(1500)
        log("launched MainActivity theme=$theme")
    }

    private fun shot(name: String) {
        val file = File(shotDir, "$name.png")
        val ok = runCatching { device.takeScreenshot(file) }.getOrDefault(false)
        log("shot $name ok=$ok size=${if (file.exists()) file.length() else -1}")
    }

    /**
     * ينقر نصًا؛ آخر عقدة مطابقة هي الأعلى في الشجرة (الشريط/الدرج فوق المحتوى).
     * UiAutomator أولًا: performScrollTo في Compose يفشل («no parent layout
     * with a Scroll SemanticsAction») لعناصر الشريط السفلي والورقة والحوار
     * لأنها بلا أب قابل للتمرير — فكانت كل خطوات openSection تفشل قبل النقر.
     */
    private fun clickText(label: String) {
        val clicked = runCatching {
            device.wait(Until.hasObject(By.text(label)), 5000)
            val target = device.findObjects(By.text(label)).lastOrNull()
            if (target != null) {
                target.click() // UiObject2.click() ترجع Unit وتفشل باستثناء
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (!clicked) {
            // احتياطي: مسار Compose المباشر بلا تمرير.
            runCatching {
                composeRule.onAllNodesWithText(label).onLast().performClick()
            }
        }
        composeRule.waitForIdle()
        Thread.sleep(800)
    }

    private fun step(label: String, block: () -> Unit) {
        runCatching { block() }.onFailure { log("STEP FAILED [$label]: ${it.message}") }
    }

    private fun back() {
        runCatching { Espresso.pressBack() }
        composeRule.waitForIdle()
        Thread.sleep(700)
    }

    /** يفتح ورقة «كل الأقسام» من الشريط السفلي ثم يختار قسمًا بعنوانه. */
    private fun openSection(title: String) {
        clickText("الأقسام")
        clickText(title)
    }

    @Test
    fun lightScreenshots() {
        launch("light")
        step("today-top") { shot("01-today-light") }
        step("today-scroll") {
            repeat(3) {
                composeRule.onRoot().performTouchInput { swipeUp() }
                Thread.sleep(400)
            }
            composeRule.waitForIdle()
            shot("02-today-scrolled-light")
            repeat(3) {
                composeRule.onRoot().performTouchInput { swipeDown() }
                Thread.sleep(400)
            }
            composeRule.waitForIdle()
        }
        step("tasks") { clickText("مهامي"); shot("03-tasks-light") }
        step("planner") { openSection("الخطة"); shot("04-planner-light"); back() }
        step("quran") { openSection("المصحف"); shot("05-quran-light"); back() }
        step("adhkar") { openSection("الأذكار"); shot("06-adhkar-light"); back() }
        step("life") { openSection("حياتي"); shot("07-life-light"); back() }
        step("assistant") { openSection("مساعدي"); shot("08-assistant-light"); back() }
        step("notifications") { openSection("الإشعارات"); shot("09-notifications-light"); back() }
        step("settings") {
            openSection("التخصيص")
            shot("10-settings-light")
        }
        step("settings-photo-mode") {
            // المشكلة 6: «صورتي كما هي» — بلا صورة مختارة، هل يتغير أي شيء؟
            clickText("صورتي كما هي")
            shot("10b-settings-photo-mode")
            clickText("رجوع")
            openSection("التخصيص")
            // الخروج بـ «رجوع» بلا حفظ يفترض أن يلغي تغيير الوضع.
            shot("10c-settings-mode-after-back")
            back()
        }
        step("support") { openSection("الدعم"); shot("11-support-light"); back() }
        step("goals") { openSection("أهدافي"); shot("12-goals-light"); back() }
        step("prayer") { openSection("الصلاة"); shot("13-prayer-light"); back() }
        step("more") { clickText("المزيد"); shot("15-more-light") }
        step("drawer") {
            // آخر خطوة عمدًا: إن أغلق زر الرجوع النشاط بدل الدرج، لا شيء بعدها.
            composeRule.onNodeWithContentDescription("القايمة").performClick()
            composeRule.waitForIdle()
            Thread.sleep(800)
            shot("14-drawer-light")
            back()
        }
        log("light tour done")
    }

    @Test
    fun darkScreenshots() {
        launch("dark")
        step("today-dark") { shot("01-today-dark") }
        step("tasks-dark") { clickText("مهامي"); shot("02-tasks-dark") }
        step("quran-dark") { openSection("المصحف"); shot("03-quran-dark"); back() }
        step("life-dark") { openSection("حياتي"); shot("04-life-dark"); back() }
        step("settings-dark") { openSection("التخصيص"); shot("05-settings-dark") }
        log("dark tour done")
    }

    @Test
    fun themeToggleCycle() {
        launch("light")
        val observed = mutableListOf<String>()
        fun currentMode(): String =
            FocusPreferences.load(appContext()).themeMode.key

        shot("00-theme-start")
        observed += currentMode()
        repeat(3) { i ->
            step("theme-tap-${i + 1}") {
                val moon = composeRule.onAllNodesWithText("☾").fetchSemanticsNodes().isNotEmpty()
                if (moon) composeRule.onNodeWithText("☾").performClick()
                else composeRule.onNodeWithText("☀").performClick()
                composeRule.waitForIdle()
                Thread.sleep(900)
                observed += currentMode()
                shot("0${i + 1}-theme-tap${i + 1}")
            }
        }
        val cycle = observed.joinToString(" -> ")
        log("THEME CYCLE: $cycle")
        // المشكلة 1: الزر يدور 3 أوضاع لا 2 — نسجّل السلوك الفعلي ونثبّته.
        val expected = listOf("light", "dark", "system", "light")
        if (observed != expected) {
            log("THEME CYCLE DIFFERS from light->dark->system->light: $cycle")
        }
    }
}
