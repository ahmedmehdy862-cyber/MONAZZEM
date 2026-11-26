package com.focusstudy.poc

import android.content.Context
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * جولة لقطات لقارئ المصحف على محاكي حقيقي (GitHub Actions):
 * الزوم الأدنى/الافتراضي/الأقصى على صفحات 2 و48 و597 (فاتح)، وعينات داكنة،
 * مع التحقق من أن مستوى الزوم يُحفظ ويبقى بعد إعادة التشغيل.
 *
 * يعيد استخدام بنية فرع emulator-qa (التشغيل 11 الناجح): فتح التطبيق،
 * تجاوز الإعداد الأولي عبر التفضيلات، زرع بيانات تجريبية، فتح «المصحف»
 * من ورقة «كل الأقسام»، واللقطات عبر UiDevice + تصديرها إلى التنزيلات
 * المشتركة (QaExport) لأن مجلد التطبيق يُحذف بعد الاختبار.
 *
 * لا يغيّر كود الإنتاج إطلاقًا؛ اللقطات تُرفع كـ artifact وتُقرأ يدويًا.
 */
@RunWith(AndroidJUnit4::class)
class QuranZoomScreenshotTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private lateinit var device: UiDevice
    private lateinit var shotDir: File
    private lateinit var logFile: File
    private var scenario: androidx.test.core.app.ActivityScenario<MainActivity>? = null
    // تُضبط في launch(): تُلحق بأسماء ملفات التشخيص حتى لا يمحو اختبار
    // الداكن دليل اختبار الفاتح (QaExport يحذف النسخة السابقة بنفس الاسم).
    private var currentTheme = "light"

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

    private fun log(line: String) {
        Log.i("QuranZoomQA", line)
        runCatching { logFile.appendText(line + "\n") }
    }

    private fun setPrefs(theme: String, zoomLevel: Int = QuranZoom.DEFAULT_LEVEL) {
        appContext().getSharedPreferences("focus_study_preferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_completed", true)
            .putString("student_name", "اختبار")
            .putString("theme_mode", theme)
            .putString("bottom_tabs", "today,tasks,calendar,subjects,more")
            .putInt("quran_zoom_level", zoomLevel)
            .apply()
    }

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
                val life = LifeRepository.get(ctx)
                val today = LocalDate.now().toString()
                val habitId = life.addHabit("قراءة ١٠ صفحات")
                life.setHabitDone(habitId, today, true)
                val wird = life.wirdSettings()
                life.logWird(today, wird.pagesPerDay, wird.surah)
                life.logMood(today, 4)
                log("seeded demo data")
            } else {
                log("seed skipped: tasks already present")
            }
        }
    }

    private fun launch(theme: String, zoomLevel: Int = QuranZoom.DEFAULT_LEVEL) {
        currentTheme = theme
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val ctx = appContext()
        shotDir = File(ctx.getExternalFilesDir(null), "qa-screenshots").apply { mkdirs() }
        logFile = File(shotDir, "qa-log.txt")
        setPrefs(theme, zoomLevel)
        seedDataOnce()
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        composeRule.waitForIdle()
        Thread.sleep(1500)
        log("launched MainActivity theme=$theme zoom=$zoomLevel")
        // لقطة + هرمية للشاشة الرئيسية قبل أي تنقّل: إن فشل فتح «الأقسام»
        // يبيّنان ما الذي كان على الشاشة فعلًا (حوار إذن؟ إعداد أولي؟).
        shot("home-after-launch-$currentTheme")
        dumpHierarchy("home-after-launch-$currentTheme")
    }

    private fun relaunchKeepPrefs() {
        runCatching { scenario?.close() }
        scenario = null
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        composeRule.waitForIdle()
        Thread.sleep(1500)
        log("relaunched without touching prefs")
    }

    private fun shot(name: String) {
        val file = File(shotDir, "$name.png")
        val ok = runCatching { device.takeScreenshot(file) }.getOrDefault(false)
        val size = if (file.exists()) file.length() else -1
        log("shot $name ok=$ok size=$size")
    }

    /**
     * يفرّغ هرمية النافذة الحالية إلى ملف يُصدَّر مع اللقطات: عند فشل تنقّل
     * ما (كأن لا يظهر زر «الأقسام») يبيّن الملف النصوص الموجودة فعلًا على
     * الشاشة، بدل التخمين من لقطة وحدها.
     */
    private fun dumpHierarchy(name: String) {
        val file = File(shotDir, "$name.xml")
        val ok = runCatching { device.dumpWindowHierarchy(file) }.getOrDefault(false)
        log("hierarchy $name ok=$ok size=${if (file.exists()) file.length() else -1}")
    }

    private fun clickText(label: String) {
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

    private fun goBack() {
        val hasBack = runCatching {
            device.wait(Until.hasObject(By.text("رجوع")), 3000)
        }.getOrDefault(false)
        if (hasBack) clickText("رجوع") else log("goBack: no in-app back button; staying")
        composeRule.waitForIdle()
        Thread.sleep(700)
    }

    /**
     * تمرير يدوي بطيء داخل الورقة حتى يظهر الصف: سحبة 300px بمئة خطوة
     * (بطيئة فلا قصور ذاتي) + ثانية استقرار قبل كل فحص. السحبات
     * السريعة كانت تُطلق قصورًا يتجاوز الصف أثناء الفحص (التشغيل
     * 45815159007، الفاتح: 20 سحبة انتهت أسفل الورقة دون أن يظهر
     * «المصحف» رغم مروره حتمًا في النطاق).
     */
    private fun scrollSheetTo(label: String, maxSwipes: Int = 25): Boolean {
        Thread.sleep(1000)
        composeRule.waitForIdle()
        repeat(maxSwipes) {
            val found = runCatching {
                device.wait(Until.hasObject(By.text(label)), 1500)
            }.getOrDefault(false)
            if (found) return true
            device.swipe(540, 1400, 540, 1100, 100)
            Thread.sleep(1000)
            composeRule.waitForIdle()
        }
        return runCatching {
            device.wait(Until.hasObject(By.text(label)), 1500)
        }.getOrDefault(false)
    }

    private fun openSection(title: String) {
        clickText("الأقسام")
        val sheetOpen = runCatching {
            device.wait(Until.hasObject(By.text("كل الأقسام")), 10000)
        }.getOrDefault(false)
        log("SHEET CHECK after [الأقسام]: open=$sheetOpen")
        // فشل سريع موثّق بدل متابعة التصوير على شاشة خاطئة: إن لم تفتح
        // الورقة فاللقطة والهرمية هنا هما الدليل التشخيصي.
        if (!sheetOpen) {
            shot("nav-debug-home-$currentTheme")
            dumpHierarchy("nav-debug-home-$currentTheme")
        }
        assertTrue("sections sheet [كل الأقسام] must open after tapping [الأقسام]", sheetOpen)
        val visible = scrollSheetTo(title)
        log("SHEET SCROLL [$title]: visible=$visible")
        clickText(title)
        val arrived = runCatching {
            device.wait(Until.hasObject(By.text("رجوع")), 10000)
        }.getOrDefault(false)
        log("NAV CHECK [$title]: arrived=$arrived")
        if (!arrived) {
            shot("nav-debug-after-sheet-$currentTheme")
            dumpHierarchy("nav-debug-after-sheet-$currentTheme")
        }
        assertTrue("reader screen must open after tapping [$title]", arrived)
    }

    /**
     * ينتظر اكتمال تحميل المصحف قبل أي تأكيد أو لقطة: انتظار واحد طويل
     * (90 ثانية) لظهور صف الزوم — وهو لا يظهر إلا بعد اكتمال التحميل.
     * تحليل ملف 1.4MB على محاكي بارد يتفاوت بشدة (التشغيل 45815159007،
     * الداكن: نص التحميل باقٍ بعد 30 ثانية؛ وفي تشغيل سابق اكتمل في
     * ثوانٍ). انتظار على مرحلتين (30+30) كان يهدر الوقت دون فائدة.
     */
    private fun waitForReaderReady() {
        val label = device.wait(Until.hasObject(By.textContains("حجم الخط")), 90000)
        log("READER READY: zoomRowPresent=$label")
        if (!label) {
            shot("reader-not-ready-$currentTheme")
            dumpHierarchy("reader-not-ready-$currentTheme")
        }
        assertTrue("reader must finish loading and show the zoom row", label)
    }

    /** النقر على زرّي الزوم «−» / «+» في صف الزوم أعلى نص المصحف. */
    private fun zoomTap(label: String, times: Int) {
        repeat(times) {
            clickText(label)
            Thread.sleep(400)
        }
        composeRule.waitForIdle()
    }

    /**
     * تأكيد تسمية الزوم: مطابقة جزئية عبر Compose على نص التسمية كاملًا
     * + مستوى الزوم المحفوظ كمصدر حقيقة رقمي. لا مطابقة تامة عبر
     * UiAutomator: فشلت مرة رغم ظهور التسمية في اللقطة نفسها (التشغيل
     * 45813074003: «حجم الخط 75٪» ظاهرة في quran-p2-zoom-min.png
     * وBy.text لم يجدها، بينما «100٪» وُجدت بنفس الصيغة — مطابِق
     * UiAutomator للنص العربي/اللاتيني المختلط غير موثوق).
     * النسبة نفسها تُتحقق بصريًا من اللقطة.
     */
    private fun assertZoomLabel(percent: String, level: Int) {
        val label = "حجم الخط $percent٪"
        composeRule.onNodeWithText(label, substring = true).assertIsDisplayed()
        log("ZOOM LABEL [$label]: displayed via compose")
        val saved = QuranZoom.loadZoomLevel(appContext())
        assertEquals("saved zoom level must match [$label]", level, saved)
    }

    /** النقر على تسمية الزوم نفسها (تعيد للافتراضي) عبر Compose. */
    private fun clickZoomLabel() {
        composeRule.onNodeWithText("حجم الخط", substring = true).performClick()
        composeRule.waitForIdle()
        Thread.sleep(800)
    }

    private fun jumpToPage(page: Int) {
        clickText("اذهب إلى صفحة")
        val fieldFound = device.wait(
            Until.hasObject(By.clazz("android.widget.EditText")), 5000
        )
        log("JUMP FIELD: found=$fieldFound")
        if (fieldFound) {
            val field = device.findObject(By.clazz("android.widget.EditText"))
            runCatching {
                field.click()
                Thread.sleep(300)
                field.text = page.toString()
            }
            Thread.sleep(500)
        }
        clickText("اذهب")
        val arrived = device.wait(
            Until.hasObject(By.textContains("صفحة $page من 604")), 8000
        )
        log("JUMP TO PAGE $page: arrived=$arrived")
        assertTrue("reader must show page $page after jump", arrived)
    }

    /** لقطات الزوم الثلاثة لصفحة واحدة: افتراضي، أدنى، أقصى — ثم عودة للافتراضي. */
    private fun shootPageZooms(page: Int, prefix: String) {
        // اللقطة الأولى بعد اكتمال التحميل لا قبله، وإلا صوّرنا شاشة التحميل.
        waitForReaderReady()
        shot("${prefix}-default")
        assertZoomLabel("100", QuranZoom.DEFAULT_LEVEL)
        zoomTap("−", 2)
        shot("${prefix}-min")
        assertZoomLabel("75", QuranZoom.MIN_LEVEL)
        zoomTap("+", 4)
        shot("${prefix}-max")
        assertZoomLabel("130", QuranZoom.MAX_LEVEL)
        zoomTap("−", 2)
        assertZoomLabel("100", QuranZoom.DEFAULT_LEVEL)
    }

    @Test
    fun quranZoomLight() {
        launch("light")
        openSection("المصحف")
        // القارئ يفتح على صفحة ورد اليوم كما يحسبها التطبيق (4 في هذا
        // التشغيل المزروع، لا 2 حرفيًا — البادئة «wird» لا «p2»).
        shootPageZooms(2, "quran-wird-zoom")
        jumpToPage(48)
        shootPageZooms(48, "quran-p48-zoom")
        jumpToPage(597)
        shootPageZooms(597, "quran-p597-zoom")

        // الحفظ: آخر مستوى مضبوط بالأزرار كان الأقصى قبل العودة للافتراضي
        // في shootPageZooms؛ نضبط الأقصى صراحةً ثم نتحقق من التفضيلة.
        zoomTap("+", 2)
        assertZoomLabel("130", QuranZoom.MAX_LEVEL)
        val saved = QuranZoom.loadZoomLevel(appContext())
        log("ZOOM PREF after +2: $saved")
        assertEquals(QuranZoom.MAX_LEVEL, saved)

        // البقاء بعد إعادة التشغيل: يُعاد الإقلاع بلا مساس بالتفضيلات،
        // فيفتح القارئ على الأقصى مباشرة (صفحة الورد).
        goBack()
        relaunchKeepPrefs()
        openSection("المصحف")
        assertZoomLabel("130", QuranZoom.MAX_LEVEL)
        shot("quran-wird-zoom-max-relaunch")
        // إعادة للافتراضي عبر الضغط على النسبة نفسها.
        clickZoomLabel()
        assertZoomLabel("100", QuranZoom.DEFAULT_LEVEL)
        assertEquals(QuranZoom.DEFAULT_LEVEL, QuranZoom.loadZoomLevel(appContext()))
    }

    @Test
    fun quranZoomDark() {
        launch("dark")
        openSection("المصحف")
        waitForReaderReady()
        shot("quran-wird-zoom-default-dark")
        assertZoomLabel("100", QuranZoom.DEFAULT_LEVEL)
        zoomTap("+", 2)
        shot("quran-wird-zoom-max-dark")
        assertZoomLabel("130", QuranZoom.MAX_LEVEL)
        zoomTap("−", 2)
        jumpToPage(597)
        shot("quran-p597-zoom-default-dark")
        assertZoomLabel("100", QuranZoom.DEFAULT_LEVEL)
    }
}
