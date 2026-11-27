package com.focusstudy.poc

import android.content.Context
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
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

    private fun openSection(title: String) {
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

    /** النقر على زرّي الزوم «−» / «+» في صف الزوم أعلى نص المصحف. */
    private fun zoomTap(label: String, times: Int) {
        repeat(times) {
            clickText(label)
            Thread.sleep(400)
        }
        composeRule.waitForIdle()
    }

    // التطبيق يعرض النسبة بأرقام غربية («حجم الخط 100٪») اتساقًا مع ترويسة
    // القارئ («صفحة 4 من 604») — لا بأرقام مشرقية؛ توقّع الأرقام الغربية.
    private fun assertZoomLabel(percentDigits: String) {
        val label = "حجم الخط $percentDigits٪"
        val found = device.wait(Until.hasObject(By.text(label)), 5000)
        log("ZOOM LABEL [$label]: found=$found")
        assertTrue("zoom label [$label] must be visible", found)
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
        shot("${prefix}-default")
        assertZoomLabel("100")
        zoomTap("−", 2)
        shot("${prefix}-min")
        assertZoomLabel("75")
        zoomTap("+", 4)
        shot("${prefix}-max")
        assertZoomLabel("130")
        zoomTap("−", 2)
        assertZoomLabel("100")
    }

    @Test
    fun quranZoomLight() {
        launch("light")
        openSection("المصحف")
        // القارئ يفتح على صفحة ورد اليوم (2 افتراضيًا).
        shootPageZooms(2, "quran-p2-zoom")
        jumpToPage(48)
        shootPageZooms(48, "quran-p48-zoom")
        jumpToPage(597)
        shootPageZooms(597, "quran-p597-zoom")

        // الحفظ: آخر مستوى مضبوط بالأزرار كان الأقصى قبل العودة للافتراضي
        // في shootPageZooms؛ نضبط الأقصى صراحةً ثم نتحقق من التفضيلة.
        zoomTap("+", 2)
        assertZoomLabel("130")
        val saved = QuranZoom.loadZoomLevel(appContext())
        log("ZOOM PREF after +2: $saved")
        assertEquals(QuranZoom.MAX_LEVEL, saved)

        // البقاء بعد إعادة التشغيل: يُعاد الإقلاع بلا مساس بالتفضيلات،
        // فيفتح القارئ على الأقصى مباشرة (صفحة الورد 2).
        goBack()
        relaunchKeepPrefs()
        openSection("المصحف")
        assertZoomLabel("130")
        shot("quran-p2-zoom-max-relaunch")
        // إعادة للافتراضي عبر الضغط على النسبة نفسها.
        clickText("حجم الخط 130٪")
        assertZoomLabel("100")
        assertEquals(QuranZoom.DEFAULT_LEVEL, QuranZoom.loadZoomLevel(appContext()))
    }

    @Test
    fun quranZoomDark() {
        launch("dark")
        openSection("المصحف")
        shot("quran-p2-zoom-default-dark")
        assertZoomLabel("100")
        zoomTap("+", 2)
        shot("quran-p2-zoom-max-dark")
        assertZoomLabel("130")
        zoomTap("−", 2)
        jumpToPage(597)
        shot("quran-p597-zoom-default-dark")
        assertZoomLabel("100")
    }
}
