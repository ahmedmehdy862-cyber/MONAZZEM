package com.focusstudy.poc

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.util.Log
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
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
 * بوابة النشر النهائية 0.35.0 — جولة لقطات على محاكي حقيقي (GitHub Actions).
 *
 * يفتح التطبيق، يتجاوز الإعداد الأولي عبر التفضيلات، يزرع بيانات تجريبية،
 * ويلتقط كل شاشة رئيسية فاتح/داكن، ويتحقق من بنود البوابة السبعة:
 * 1) الإقلاع وكل الشاشات + عدم التعطل. 2) شاشات الرفيق 3D. 3) فاتح/داكن.
 * 4) عدم حجب أي عنصر. 5) المشاكل الست مُصلحة فعلًا. 6) سحب/إفلات الشريط
 * السفلي. 7) شرح التمارين.
 *
 * يعيد استخدام بنية فرع emulator-qa (التشغيل 11 الناجح) مع إصلاح قيد
 * التغطية: تمرير ورقة «كل الأقسام» قبل النقر على كل صف حتى تُفتح
 * الشاشات الثماني التي لم تُفتح في التشغيل 11، وفتح الدرج من تبويب جذري.
 *
 * لا يغيّر كود الإنتاج إطلاقًا؛ اللقطات تُرفع كـ artifact وتُقرأ يدويًا.
 * كل خطوة تنقّل مغلّفة بـ runCatching وتُسجَّل في qa-log.txt حتى لا
 * تُسقط خطوة واحدة بقية الجولة.
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
        runCatching {
            if (::shotDir.isInitialized) QaExport.exportDir(appContext(), shotDir)
        }
    }

    private fun appContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private fun prefs() = FocusPreferences.load(appContext())

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
        dismissSystemAnr()
        waitForHome()
        log("launched MainActivity theme=$theme")
    }

    /**
     * يغلق حوار «التطبيق لا يستجيب» (ANR) للنظام إن ظهر — في التشغيل
     * الثاني حجب هذا الحوار كل اللقطات الـ 40 على API 34 ومنع UiAutomator
     * من رؤية التطبيق أصلًا. أزرار الحوار إنجليزية («Wait» / «Close app»).
     */
    private fun dismissSystemAnr() {
        runCatching {
            val anr = device.findObject(By.textContains("isn't responding"))
                ?: device.findObject(By.textContains("لا يستجيب"))
            if (anr != null) {
                val waitBtn = device.findObject(By.text("Wait"))
                    ?: device.findObject(By.text("انتظار"))
                if (waitBtn != null) {
                    waitBtn.click()
                    Thread.sleep(1000)
                    log("ANR dialog dismissed via Wait")
                } else {
                    log("ANR dialog present but no Wait button found")
                }
            }
        }
    }

    /**
     * ينتظر ظهور الرئيسية («اليوم») بعد الإقلاع، مع إغلاق أي حوار ANR
     * وتخطّي الترحيب إن ما زال معروضًا. يفشل بصوت عالٍ إن لم تظهر —
     * فبدون الرئيسية لا يمكن اختبار أي شيء، والنجاح الصامت هنا أدلة فارغة.
     */
    private fun waitForHome(timeoutMs: Long = 30000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var home = false
        while (System.currentTimeMillis() < deadline) {
            dismissSystemAnr()
            home = runCatching {
                device.wait(Until.hasObject(By.text("اليوم")), 2000)
            }.getOrDefault(false)
            if (home) break
            val welcome = runCatching {
                device.findObject(By.text("نرتّب يومك معًا"))
            }.getOrNull()
            if (welcome != null) {
                runCatching { device.click(device.displayWidth / 2, device.displayHeight / 2) }
                Thread.sleep(800)
            } else {
                Thread.sleep(1000)
            }
        }
        log("HOME reached=$home")
        check(home) { "home screen did not appear within ${timeoutMs}ms" }
    }

    /** إعادة إقلاع بلا مساس بالتفضيلات (للتحقق من استمرار الترتيب بعد إعادة التشغيل). */
    private fun relaunchKeepPrefs() {
        runCatching { scenario?.close() }
        scenario = null
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        composeRule.waitForIdle()
        Thread.sleep(1500)
        dismissSystemAnr()
        waitForHome()
        log("relaunched without touching prefs")
    }

    private var lastShotSize = -1L

    private fun shot(name: String) {
        dismissSystemAnr()
        val file = File(shotDir, "$name.png")
        val ok = runCatching { device.takeScreenshot(file) }.getOrDefault(false)
        val size = if (file.exists()) file.length() else -1
        log("shot $name ok=$ok size=$size prevSize=$lastShotSize")
        lastShotSize = size
    }

    private fun clickText(label: String) {
        dismissSystemAnr()
        val clicked = runCatching {
            device.wait(Until.hasObject(By.text(label)), 10000)
            val target = device.findObjects(By.text(label)).lastOrNull()
            if (target != null) {
                target.click()
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (!clicked) {
            runCatching {
                composeRule.onAllNodesWithText(label).onLast().performClick()
            }
        }
        composeRule.waitForIdle()
        Thread.sleep(800)
    }

    private fun clickDesc(desc: String): Boolean {
        dismissSystemAnr()
        val opened = runCatching {
            device.wait(Until.hasObject(By.desc(desc)), 5000)
            device.findObject(By.desc(desc))?.let { it.click(); true } == true
        }.getOrDefault(false)
        if (!opened) {
            runCatching {
                composeRule.onNodeWithContentDescription(desc).performClick()
            }
        }
        composeRule.waitForIdle()
        Thread.sleep(800)
        return opened
    }

    /** يمرّر شاشة عادية حتى يظهر النص (بحد أقصى 10 تمريرات). */
    private fun scrollScreenTo(label: String, maxSwipes: Int = 10): Boolean {
        repeat(maxSwipes) {
            val found = runCatching {
                device.wait(Until.hasObject(By.text(label)), 1500)
            }.getOrDefault(false)
            if (found) return true
            device.swipe(540, 1700, 540, 700, 20)
            Thread.sleep(500)
            composeRule.waitForIdle()
        }
        return runCatching {
            device.wait(Until.hasObject(By.text(label)), 1500)
        }.getOrDefault(false)
    }

    /**
     * يمرّر ورقة «كل الأقسام» (LazyColumn — العقد أسفل الطي غير موجودة
     * في الشجرة أصلًا) حتى يظهر الصف المطلوب. هذا إصلاح قيد التشغيل 11:
     * بدونه فشل النقر على كل الصفوف أسفل الطي.
     */
    private fun scrollSheetTo(label: String, maxSwipes: Int = 12): Boolean {
        repeat(maxSwipes) {
            val found = runCatching {
                device.wait(Until.hasObject(By.text(label)), 1500)
            }.getOrDefault(false)
            if (found) return true
            device.swipe(540, 1500, 540, 800, 20)
            Thread.sleep(500)
            composeRule.waitForIdle()
        }
        return runCatching {
            device.wait(Until.hasObject(By.text(label)), 1500)
        }.getOrDefault(false)
    }

    /** تحقق صريح (تذكير أحمد): زر + بعيد عن زر «الأقسام» في الشريط السفلي. */
    private fun logFabSectionsCheck(tag: String) {
        val fab = runCatching { device.findObject(By.desc("إضافة سريعة")) }.getOrNull()
        val sections = runCatching { device.findObject(By.text("الأقسام")) }.getOrNull()
        if (fab != null && sections != null) {
            val f = fab.visibleBounds
            val s = sections.visibleBounds
            val overlap = Rect.intersects(f, s)
            // الفاصل الرأسي بين أسفل زر + وأعلى زر الأقسام (بالبكسل؛ المحاكي
            // pixel_6 بكثافة 420dpi فـ 16dp ≈ 42px). يجب أن يكون موجبًا وبلا تداخل.
            val verticalGapPx = s.top - f.bottom
            log("FAB_SECTIONS_CHECK [$tag] via=uiautomator: fab=$f sections=$s overlap=$overlap verticalGapPx=$verticalGapPx")
            return
        }
        // بديل Compose عند فشل UiAutomator (كما في التشغيل الثاني حين حجبه
        // حوار ANR فعاد fabFound=false رغم وجود الزرين). الحدود هنا بوحدات
        // بكسل الجذر نفسه؛ نأخذ آخر عقدة «الأقسام» (تبويب الشريط السفلي).
        // ملاحظة: fetchSemanticsNodes (جمع) غير موجود في إصدار Compose
        // هنا — نستخدم onFirst/onLast + fetchSemanticsNode (مفرد) الثابتين.
        runCatching {
            val fabNode = composeRule.onAllNodesWithContentDescription("إضافة سريعة")
                .onFirst().fetchSemanticsNode()
            val sectionsNode = composeRule.onAllNodesWithText("الأقسام")
                .onLast().fetchSemanticsNode()
            val f = fabNode.boundsInRoot
            val s = sectionsNode.boundsInRoot
            val overlap = f.overlaps(s)
            val verticalGapPx = (s.top - f.bottom).toInt()
            log("FAB_SECTIONS_CHECK [$tag] via=compose: fab=$f sections=$s overlap=$overlap verticalGapPx=$verticalGapPx")
        }.onFailure {
            log("FAB_SECTIONS_CHECK [$tag]: uiautomator fabFound=${fab != null} sectionsFound=${sections != null} composeFallbackFailed=${it.message}")
        }
    }

    private fun step(label: String, block: () -> Unit) {
        runCatching { block() }.onFailure { log("STEP FAILED [$label]: ${it.message}") }
    }

    /**
     * عودة آمنة من شاشة ثانوية: زر «رجوع» داخل التطبيق فقط. لا نضغط زر رجوع
     * النظام أبدًا من تبويب جذري — ففي التشغيل 45762930514 ضغطة رجوع واحدة
     * بعد تنقل فاشل أخرجت التطبيق كله إلى شاشة اللانشر، فسقطت بقية الجولة.
     */
    private fun goBack() {
        val hasBack = runCatching {
            device.wait(Until.hasObject(By.text("رجوع")), 3000)
        }.getOrDefault(false)
        if (hasBack) clickText("رجوع") else log("goBack: no in-app back button; staying")
        composeRule.waitForIdle()
        Thread.sleep(700)
    }

    /** يفتح ورقة «كل الأقسام» من الشريط السفلي ثم يختار قسمًا بعنوانه (مع التمرير). */
    private fun openSection(title: String) {
        dismissSystemAnr()
        clickText("الأقسام")
        val sheetOpen = runCatching {
            device.wait(Until.hasObject(By.text("كل الأقسام")), 10000)
        }.getOrDefault(false)
        log("SHEET CHECK after [الأقسام]: open=$sheetOpen")
        val visible = scrollSheetTo(title)
        log("SHEET SCROLL [$title]: visible=$visible")
        clickText(title)
        val arrived = runCatching {
            device.wait(Until.hasObject(By.text("رجوع")), 10000)
        }.getOrDefault(false)
        log("NAV CHECK [$title]: arrived=$arrived")
    }

    @Test
    fun lightScreenshots() {
        launch("light")
        step("fab-sections-light") { logFabSectionsCheck("light-home") }
        step("today-top") { shot("01-today-light") }
        step("today-scroll") {
            // بطاقة المساعد ثم الرفيق 3D أسفل الطي في «اليوم».
            repeat(3) {
                device.swipe(540, 1800, 540, 700, 20)
                Thread.sleep(500)
            }
            composeRule.waitForIdle()
            shot("02-today-scrolled-light")
            repeat(3) {
                device.swipe(540, 700, 540, 1800, 20)
                Thread.sleep(500)
            }
            composeRule.waitForIdle()
        }
        step("tasks") { clickText("مهامي"); shot("03-tasks-light") }
        step("planner") { openSection("الخطة"); shot("04-planner-light"); goBack() }
        step("quran") { openSection("المصحف"); shot("05-quran-light"); goBack() }
        step("adhkar") { openSection("الأذكار"); shot("06-adhkar-light"); goBack() }
        step("life") { openSection("حياتي"); shot("07-life-light"); goBack() }
        step("assistant") { openSection("مساعدي"); shot("08-assistant-light"); goBack() }
        step("notifications") { openSection("الإشعارات"); shot("09-notifications-light"); goBack() }
        step("settings") {
            openSection("التخصيص")
            shot("10-settings-light")
        }
        step("settings-photo-mode") {
            // المشكلة 6: «صورتي كما هي» — الإصلاح (0.34.0) يحفظ الوضع فور
            // الضغطة؛ نتحقق من التفضيلة فورًا ثم بعد «رجوع» بلا حفظ.
            scrollScreenTo("صورتي كما هي")
            clickText("صورتي كما هي")
            shot("10b-settings-photo-mode")
            log("AVATAR MODE after tap: ${prefs().avatarMode.key}")
            goBack()
            log("AVATAR MODE after back (no save pressed): ${prefs().avatarMode.key}")
            shot("10c-settings-mode-after-back")
            // إعادة الوضع إلى كرتوني حتى لا تتأثر لقطات الاختبارات اللاحقة.
            openSection("التخصيص")
            scrollScreenTo("كرتوني")
            clickText("كرتوني")
            log("AVATAR MODE restored: ${prefs().avatarMode.key}")
            goBack()
        }
        step("support") { openSection("الدعم"); shot("11-support-light"); goBack() }
        step("goals") { openSection("أهدافي"); shot("12-goals-light"); goBack() }
        step("prayer") { openSection("الصلاة"); shot("13-prayer-light"); goBack() }
        step("bottombar") { openSection("الشريط السفلي"); shot("14-bottombar-light"); goBack() }
        step("more") { clickText("المزيد"); shot("15-more-light") }
        step("drawer") {
            // فتح الدرج من تبويب جذري (اليوم) لا من «المزيد» — إصلاح قيد
            // التشغيل 11 (لقطة الدرج كانت مطابقة للقطة «المزيد»).
            clickText("اليوم")
            clickDesc("القايمة")
            shot("16-drawer-light")
            // المشكلة 4: طي مجموعة «يومي» ثم فتحها مجددًا.
            clickDesc("طي قسم يومي")
            shot("16b-drawer-collapsed")
            log("DRAWER: collapsed يومي")
            clickDesc("فتح قسم يومي")
            shot("16c-drawer-restored")
        }
        log("light tour done")
    }

    @Test
    fun darkScreenshots() {
        launch("dark")
        step("fab-sections-dark") { logFabSectionsCheck("dark-home") }
        step("today-dark") { shot("01-today-dark") }
        step("tasks-dark") { clickText("مهامي"); shot("02-tasks-dark") }
        step("quran-dark") { openSection("المصحف"); shot("03-quran-dark"); goBack() }
        step("life-dark") { openSection("حياتي"); shot("04-life-dark"); goBack() }
        step("settings-dark") { openSection("التخصيص"); shot("05-settings-dark") }
        log("dark tour done")
    }

    @Test
    fun themeToggleCycle() {
        launch("light")
        val observed = mutableListOf<String>()
        fun currentMode(): String = prefs().themeMode.key

        shot("00-theme-start")
        observed += currentMode()
        repeat(2) { i ->
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
        // الإصلاح (0.35.0): الزر يدور وضعين فقط فاتح ← داكن ← فاتح.
        val expected = listOf("light", "dark", "light")
        if (observed != expected) {
            log("THEME CYCLE DIFFERS from light->dark->light: $cycle")
        }
    }

    @Test
    fun companionSettings() {
        launch("light")
        log("COMPANION prefs at launch: enabled=${prefs().companion3dEnabled} animations=${prefs().companion3dAnimationsEnabled}")
        step("companion-on") { shot("20-companion-home") }
        step("companion-off") {
            openSection("التخصيص")
            scrollScreenTo("رفيقك منظم (ثلاثي الأبعاد)")
            clickText("إظهار الرفيق")
            clickText("حفظ التخصيص")
            log("COMPANION after disable+save: enabled=${prefs().companion3dEnabled}")
            shot("21-companion-off")
        }
        step("companion-back-on") {
            openSection("التخصيص")
            scrollScreenTo("رفيقك منظم (ثلاثي الأبعاد)")
            clickText("إظهار الرفيق")
            clickText("حفظ التخصيص")
            log("COMPANION after re-enable+save: enabled=${prefs().companion3dEnabled}")
            shot("22-companion-on")
        }
        step("companion-static") {
            openSection("التخصيص")
            scrollScreenTo("رفيقك منظم (ثلاثي الأبعاد)")
            clickText("حركات الرفيق")
            clickText("حفظ التخصيص")
            log("COMPANION after animations-off+save: animations=${prefs().companion3dAnimationsEnabled}")
            shot("23-companion-static")
        }
        step("companion-restore") {
            openSection("التخصيص")
            scrollScreenTo("رفيقك منظم (ثلاثي الأبعاد)")
            clickText("حركات الرفيق")
            clickText("حفظ التخصيص")
            log("COMPANION restored: enabled=${prefs().companion3dEnabled} animations=${prefs().companion3dAnimationsEnabled}")
        }
        log("companion settings done")
    }

    @Test
    fun bottomBarReorder() {
        launch("light")
        val before = prefs().bottomTabs
        log("BOTTOMBAR BEFORE: ${before.joinToString(",")}")
        step("bottombar-open") {
            openSection("الشريط السفلي")
            shot("30-bottombar-before")
        }
        step("bottombar-arrow-down") {
            // السهم ↓ الأول يخص الصف الأول (اليوم): ينقله مرتبة واحدة للأسفل.
            runCatching {
                composeRule.onAllNodesWithText("↓")[0].performClick()
            }
            composeRule.waitForIdle()
            Thread.sleep(500)
            clickText("حفظ الشريط")
            log("BOTTOMBAR AFTER SAVE: ${prefs().bottomTabs.joinToString(",")}")
            shot("31-bottombar-after-save")
            goBack()
        }
        step("bottombar-restart") {
            relaunchKeepPrefs()
            log("BOTTOMBAR AFTER RESTART: ${prefs().bottomTabs.joinToString(",")}")
            shot("32-bottombar-after-restart")
            // تحقق صريح بعد إعادة الترتيب وإعادة التشغيل: زر + ما زال
            // بعيدًا عن زر «الأقسام» (بلا تعارض وبلا تغطية).
            logFabSectionsCheck("after-reorder-restart")
        }
        step("bottombar-restore") {
            openSection("الشريط السفلي")
            // السهم ↑ الثاني يخص الصف الثاني (اليوم بعد نقله): يعيده للأعلى.
            runCatching {
                composeRule.onAllNodesWithText("↑")[1].performClick()
            }
            composeRule.waitForIdle()
            Thread.sleep(500)
            clickText("حفظ الشريط")
            log("BOTTOMBAR RESTORED: ${prefs().bottomTabs.joinToString(",")}")
            shot("33-bottombar-restored")
            goBack()
        }
        step("sheet-drag-attempt") {
            // محاولة أفضل جهد لسحب القسم المثبّت في الورقة (ضغط مطوّل +
            // سحب)؛ الأسهم أعلاه هي التحقق الحاسم، وهذا إضافي.
            clickText("الأقسام")
            val sheetOpen = runCatching {
                device.wait(Until.hasObject(By.text("كل الأقسام")), 10000)
            }.getOrDefault(false)
            log("SHEET DRAG: sheet open=$sheetOpen")
            if (sheetOpen) {
                val dragged = runCatching {
                    val rows = device.findObjects(By.text("اليوم"))
                    val target = rows.minByOrNull { it.visibleBounds.top } ?: return@runCatching false
                    val b = target.visibleBounds
                    target.drag(Point(b.centerX(), b.centerY() + 260))
                    composeRule.waitForIdle()
                    Thread.sleep(800)
                    true
                }.getOrDefault(false)
                log("SHEET DRAG attempted=$dragged; BOTTOMBAR NOW: ${prefs().bottomTabs.joinToString(",")}")
                shot("34-sheet-drag")
                // إن غيّر السحب الترتيب، نعيده بالأسهم حتى لا يبقى متغيرًا.
                if (prefs().bottomTabs != before) {
                    openSection("الشريط السفلي")
                    runCatching {
                        composeRule.onAllNodesWithText("↑")[1].performClick()
                    }
                    composeRule.waitForIdle()
                    Thread.sleep(500)
                    clickText("حفظ الشريط")
                    log("BOTTOMBAR AFTER DRAG RESTORE: ${prefs().bottomTabs.joinToString(",")}")
                    goBack()
                }
            }
        }
        log("bottom bar reorder done")
    }

    @Test
    fun exerciseDetail() {
        launch("light")
        step("life-fitness") {
            openSection("حياتي")
            val found = scrollScreenTo("خطة التمارين الأسبوعية")
            log("FITNESS PLAN visible=$found")
            shot("39-life-fitness")
        }
        step("exercise-open") {
            // أول تمرين في الخطة الافتراضية (جيم/ذكر — السبت): بنش مستوٍ بالبار.
            clickText("بنش مستوٍ بالبار")
            val arrived = runCatching {
                device.wait(Until.hasObject(By.text("خطوات التنفيذ")), 10000)
            }.getOrDefault(false)
            log("EXERCISE DETAIL arrived=$arrived")
            shot("40-exercise-detail")
        }
        step("exercise-complete") {
            clickText("سجّلت إتمام التمرين")
            val done = runCatching {
                device.wait(Until.hasObject(By.text("✓ سجّلت إتمام التمرين اليوم — اضغط للتراجع")), 8000)
            }.getOrDefault(false)
            log("EXERCISE DONE state visible=$done")
            shot("41-exercise-done")
            // التراجع لإعادة الحالة كما كانت.
            if (done) {
                clickText("✓ سجّلت إتمام التمرين اليوم — اضغط للتراجع")
                Thread.sleep(500)
                log("EXERCISE undone; button back=${runCatching { device.wait(Until.hasObject(By.text("سجّلت إتمام التمرين")), 5000) }.getOrDefault(false)}")
            }
            goBack()
        }
        log("exercise detail done")
    }

    /**
     * شاشة الترحيب (0.35.0): تظهر بعد الإعداد الأولي مباشرة، ثم تنتقل
     * تلقائيًا أو بالنقر إلى الرئيسية دون إيقاف الدخول. لا يستخدم launch()
     * هنا عمدًا — فهي تنام 1500ms بعد الإقلاع، والترحيب ينتهي تلقائيًا
     * بعد 1200ms فيفوتنا؛ نلتقطه فور الإقلاع مباشرة (ضمن نافذة 1.2s)،
     * ثم نتحقق من تقدمه التلقائي بلا نقر، ثم نعيد الإقلاع لاختبار التخطي
     * بالنقر. إغلاق حوار ANR أولًا (التشغيل الثاني: الحوار حجب الترحيب
     * فظهر `WELCOME visible=false` رغم وجوده).
     */
    @Test
    fun welcomeScreen() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val ctx = appContext()
        shotDir = File(ctx.getExternalFilesDir(null), "qa-screenshots").apply { mkdirs() }
        logFile = File(shotDir, "qa-log.txt")
        setPrefs("light")
        seedDataOnce()
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        dismissSystemAnr()
        val welcomeVisible = runCatching {
            device.wait(Until.hasObject(By.text("نرتّب يومك معًا")), 8000)
        }.getOrDefault(false)
        log("WELCOME visible=$welcomeVisible")
        step("welcome-shot") { shot("00-welcome-light") }
        // 1) التقدم التلقائي بلا أي نقر: الترحيب ينتهي وحده بعد 1,200ms.
        val autoHome = runCatching {
            device.wait(Until.hasObject(By.text("اليوم")), 10000)
        }.getOrDefault(false)
        log("WELCOME auto-advanced to home=$autoHome")
        step("welcome-auto-home") { shot("00b-welcome-auto-home") }
        // 2) التخطي بالنقر: إعادة إقلاع جديدة (الترحيب يظهر مجددًا في
        // نسخة نشاط جديدة) ثم نقر في المنتصف.
        runCatching { scenario?.close() }
        scenario = null
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        dismissSystemAnr()
        val welcomeAgain = runCatching {
            device.wait(Until.hasObject(By.text("نرتّب يومك معًا")), 8000)
        }.getOrDefault(false)
        log("WELCOME visible on relaunch=$welcomeAgain")
        if (welcomeAgain) {
            runCatching { device.click(device.displayWidth / 2, device.displayHeight / 2) }
            Thread.sleep(800)
        }
        val homeVisible = runCatching {
            device.wait(Until.hasObject(By.text("اليوم")), 10000)
        }.getOrDefault(false)
        log("WELCOME skipped by tap, home visible=$homeVisible")
        composeRule.waitForIdle()
        Thread.sleep(800)
        step("welcome-skipped-home") { shot("00c-welcome-skipped-home") }
        log("welcome screen done")
    }
}
