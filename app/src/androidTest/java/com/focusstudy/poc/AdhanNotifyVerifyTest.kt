package com.focusstudy.poc

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * تحقق حي (فرع adhan-notify-verify، أساس fix-035-feedback 5b4aed68):
 *
 * 1) إشعارات الأذان لا تتراكم: معرّف ثابت لكل نوع (AT=6202، BEFORE=6204)
 *    فيُستبدل لا يتكدّس؛ إعادة الجدولة تلغي المنبّهات القديمة أولًا؛ نشاط
 *    التنبيه singleTop + حارس 60 ثانية يمنع نسختين فوق بعضهما.
 * 2) الأوقات مضبوطة: حساب EGYPTIAN على إحداثيات القاهرة يُقارن بمرجع مستقل
 *    (جداول الهيئة المصرية العامة للمساحة: 27 نوفمبر 2026 شتاءً و15 يونيو
 *    2026 صيفًا بتوقيت صيفي)، ومنبّه «قبل» يضرب قبل الوقت بالإزاحة الصحيحة.
 *
 * اختبارات فقط، بلا أي تغيير في كود الإنتاج. ترتيب الطرق ثابت بالاسم.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AdhanNotifyVerifyTest {

    private lateinit var device: UiDevice
    private lateinit var context: Context
    private lateinit var shotDir: File
    private lateinit var logFile: File

    @Before
    fun setUp() {
        val instr = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instr)
        context = instr.targetContext
        runCatching {
            instr.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            )
        }
        // مستخدم فعّل الأذان وحفظ موقع القاهرة + منطقة القاهرة الزمنية.
        context.getSharedPreferences("prayer_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true)
            .putString("latitude_text", "30.0444")
            .putString("longitude_text", "31.2357")
            .apply()
        context.getSharedPreferences("focus_study_preferences", Context.MODE_PRIVATE).edit()
            .putString("timezone_iana", "Africa/Cairo")
            .apply()
        AppNotifications.ensure(context, AppNotifications.PRAYER_AT)
        AppNotifications.ensure(context, AppNotifications.PRAYER_BEFORE)
        AppNotifications.ensurePlaybackChannel(context)
        shotDir = File(context.getExternalFilesDir(null), "qa-screenshots").apply { mkdirs() }
        logFile = File(shotDir, "qa-log.txt")
        log("canScheduleExact=${NotificationMaintenance.canScheduleExact(context)}")
        log("sdk=${Build.VERSION.SDK_INT}")
    }

    @After
    fun tearDown() {
        runCatching { AdhanPlaybackService.stop(context) }
        runCatching { device.pressBack() }
        runCatching {
            instr.uiAutomation.executeShellCommand(
                "appops set --uid ${context.packageName} SCHEDULE_EXACT_ALARM default"
            )
        }
        runCatching { QaExport.exportDir(context, shotDir) }
    }

    private val instr get() = InstrumentationRegistry.getInstrumentation()

    private fun log(line: String) {
        Log.i("AdhanNotifyQA", line)
        runCatching { logFile.appendText(line + "\n") }
    }

    private fun shot(name: String) {
        val file = File(shotDir, name)
        val ok = runCatching { device.takeScreenshot(file) }.getOrDefault(false)
        log("screenshot $name ok=$ok")
    }

    private fun shellOutput(command: String): String {
        val pfd = instr.uiAutomation.executeShellCommand(command)
        return pfd.use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
    }

    private fun countOnChannel(channelId: String): Int {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.activeNotifications.count { it.notification.channelId == channelId }
    }

    private fun fireReceiver(type: PrayerAlertType, key: String, name: String, prayerAt: Long) {
        val action = if (type == PrayerAlertType.AT) "com.focusstudy.poc.PRAYER_AT"
        else "com.focusstudy.poc.PRAYER_BEFORE"
        val intent = Intent(context, PrayerReminderReceiver::class.java)
            .setAction(action)
            .putExtra("prayer_key", key)
            .putExtra(PrayerReminderReceiver.EXTRA_NAME, name)
            .putExtra("prayer_alert_type", type.key)
            .putExtra("prayer_time_millis", prayerAt)
        context.sendBroadcast(intent)
    }

    private fun waitForChannel(channelId: String, seconds: Int = 15): Boolean {
        repeat(seconds) {
            if (countOnChannel(channelId) > 0) return true
            Thread.sleep(1000)
        }
        return countOnChannel(channelId) > 0
    }

    /**
     * المعرّف الثابت يستبدل لا يتكدّس: إطلاق AT لثلاث صلوات متتالية يبقي
     * إشعارًا واحدًا على قناة الأذان، وإطلاق BEFORE لصلاتين يبقي واحدًا.
     */
    @Test
    fun test01_fixedIdsReplaceNeverStack() {
        val at = AppNotifications.PRAYER_AT.id
        val before = AppNotifications.PRAYER_BEFORE.id
        val now = System.currentTimeMillis()

        fireReceiver(PrayerAlertType.BEFORE, "fajr", "الفجر", now + 10 * 60_000L)
        assertTrue("BEFORE fajr never posted", waitForChannel(before))
        assertEquals("BEFORE must be exactly one notification", 1, countOnChannel(before))

        fireReceiver(PrayerAlertType.BEFORE, "dhuhr", "الظهر", now + 60 * 60_000L)
        Thread.sleep(3000)
        assertEquals("BEFORE dhuhr must replace BEFORE fajr, not stack", 1, countOnChannel(before))
        log("BEFORE replaces across prayers: count=1")

        fireReceiver(PrayerAlertType.AT, "fajr", "الفجر", now)
        assertTrue("AT fajr never posted", waitForChannel(at))
        assertEquals("AT must be exactly one notification", 1, countOnChannel(at))

        fireReceiver(PrayerAlertType.AT, "dhuhr", "الظهر", now)
        Thread.sleep(3000)
        assertEquals("AT dhuhr must replace AT fajr, not stack", 1, countOnChannel(at))

        fireReceiver(PrayerAlertType.AT, "asr", "العصر", now)
        Thread.sleep(3000)
        assertEquals("AT asr must replace AT dhuhr, not stack", 1, countOnChannel(at))
        log("AT replaces across prayers: count=1 after fajr/dhuhr/asr")
    }

    /**
     * إشعار «قبل» لنفس الصلاة يجب أن يختفي عند وصول «عند» — وإلا بقي إشعاران
     * لصلاة واحدة في الستارة (تراكم). هذا الاختبار يثبت السلوك الفعلي.
     */
    @Test
    fun test02_beforeGoneWhenAtFires() {
        val at = AppNotifications.PRAYER_AT.id
        val before = AppNotifications.PRAYER_BEFORE.id
        val now = System.currentTimeMillis()

        fireReceiver(PrayerAlertType.BEFORE, "fajr", "الفجر", now + 10 * 60_000L)
        assertTrue("BEFORE fajr never posted", waitForChannel(before))
        assertEquals("BEFORE must be exactly one before AT", 1, countOnChannel(before))

        fireReceiver(PrayerAlertType.AT, "fajr", "الفجر", now)
        assertTrue("AT fajr never posted", waitForChannel(at))
        Thread.sleep(3000)
        val atCount = countOnChannel(at)
        val beforeCount = countOnChannel(before)
        log("after AT fajr: AT=$atCount BEFORE=$beforeCount (total=${atCount + beforeCount})")
        device.openNotification()
        Thread.sleep(1500)
        shot("02_shade_after_at.png")
        device.pressBack()
        assertEquals("AT must be exactly one notification", 1, atCount)
        assertEquals(
            "stale BEFORE notification for the same prayer remains after AT fired",
            0, beforeCount
        )
    }

    /**
     * إعادة الجدولة تلغي القديم أولًا: جدول مرة ثم ثلاث مرات، وعدد أسطر
     * PRAYER_AT/PRAYER_BEFORE في dumpsys alarm يجب ألا يتغير. كذلك خطة
     * الأيام السبعة نفسها بلا requestCode مكرر.
     */
    @Test
    fun test03_rescheduleCreatesNoDuplicateAlarms() {
        val plan = PrayerNotificationPlan.build(
            latitude = 30.0444,
            longitude = 31.2357,
            prayer = PrayerSettings(),
            alerts = PrayerAlertSettings(),
            from = LocalDate.now(ZoneId.of("Africa/Cairo")),
            fromMillis = System.currentTimeMillis()
        )
        val codes = plan.map { it.requestCode }
        assertEquals("plan has duplicate request codes", codes.size, codes.distinct().size)
        val expectedAt = plan.count { it.type == PrayerAlertType.AT }
        val expectedBefore = plan.count { it.type == PrayerAlertType.BEFORE }
        log("plan: ${plan.size} alarms (AT=$expectedAt BEFORE=$expectedBefore) distinct codes OK")

        fun alarmCounts(): Pair<Int, Int> {
            val dumpsys = shellOutput("dumpsys alarm")
            val at = dumpsys.lines().count { it.contains("PRAYER_AT") }
            val before = dumpsys.lines().count { it.contains("PRAYER_BEFORE") }
            val screens = dumpsys.lines().count { it.contains("PrayerAlertActivity") }
            log("dumpsys alarm lines: PRAYER_AT=$at PRAYER_BEFORE=$before PrayerAlertActivity=$screens")
            return at to before
        }

        PrayerReminderScheduler.schedule(context)
        Thread.sleep(2000)
        val (at1, before1) = alarmCounts()
        assertTrue("no PRAYER_AT alarm after schedule()", at1 > 0)
        assertTrue("no PRAYER_BEFORE alarm after schedule()", before1 > 0)

        PrayerReminderScheduler.schedule(context)
        PrayerReminderScheduler.schedule(context)
        Thread.sleep(2000)
        val (at2, before2) = alarmCounts()
        assertEquals("rescheduling duplicated PRAYER_AT alarms", at1, at2)
        assertEquals("rescheduling duplicated PRAYER_BEFORE alarms", before1, before2)
        log("reschedule x3: counts unchanged (AT=$at1 BEFORE=$before1) — no duplicates")
    }

    /**
     * الأوقات مضبوطة: حساب EGYPTIAN على إحداثيات القاهرة مقابل جداول الهيئة
     * المصرية العامة للمساحة (مصادر مستقلة متعددة متفقة): 27 نوفمبر 2026
     * (شتاء، EET) و15 يونيو 2026 (صيف، EEST بتوقيت صيفي). التسامح ±دقيقتان
     * لتقريب الجداول المنشورة. وكذلك إزاحة «قبل» = 10 دقائق بالظبط.
     */
    @Test
    fun test04_timesMatchReferenceAndBeforeOffset() {
        val zone = ZoneId.of("Africa/Cairo")
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        fun check(date: LocalDate, ref: Map<String, String>) {
            val moments = PrayerAccuracy.calculate(30.0444, 31.2357, date, PrayerSettings())
            moments.forEach { moment ->
                val key = PrayerAccuracy.keyFor(moment.name)
                val computed = java.time.Instant.ofEpochMilli(moment.atMillis).atZone(zone).format(fmt)
                val expected = ref.getValue(key)
                val diff = minutesBetween(expected, computed)
                log("$date $key: computed=$computed reference=$expected diff=${diff}min")
                assertTrue(
                    "$date $key off by ${diff}min (computed=$computed reference=$expected, tolerance 2min)",
                    kotlin.math.abs(diff) <= 2
                )
            }
        }
        // 27 نوفمبر 2026 — إجماع المصادر (prayertime.online، qurani.io،
        // prayertime.info، azkar.me، ummahapi، almuslimplus): الفجر 04:59،
        // الظهر 11:44 (11:43 في مصدرين — تقريب)، العصر 14:35، المغرب 16:55،
        // العشاء 18:17. الطريقة: الهيئة المصرية 19.5°/17.5°، عصر شافعي.
        check(
            LocalDate.of(2026, 11, 27),
            mapOf(
                "fajr" to "04:59", "dhuhr" to "11:44", "asr" to "14:35",
                "maghrib" to "16:55", "isha" to "18:17"
            )
        )
        // 15 يونيو 2026 — توقيت صيفي (EEST UTC+3). al-habib.info (الهيئة
        // المصرية): الفجر 04:09، الظهر 12:59، العصر 16:32، المغرب 19:59،
        // العشاء 21:32. فرق ساعة كاملة هنا يعني تجاهل التوقيت الصيفي.
        check(
            LocalDate.of(2026, 6, 15),
            mapOf(
                "fajr" to "04:09", "dhuhr" to "12:59", "asr" to "16:32",
                "maghrib" to "19:59", "isha" to "21:32"
            )
        )

        // إزاحة «قبل»: كل منبّه BEFORE يضرب قبل وقت صلاته بعشر دقائق بالظبط.
        val day = LocalDate.of(2026, 11, 27)
        val startOfDay = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val plan = PrayerNotificationPlan.build(
            latitude = 30.0444, longitude = 31.2357,
            prayer = PrayerSettings(), alerts = PrayerAlertSettings(),
            from = day, fromMillis = startOfDay, days = 1
        )
        val byKey = plan.groupBy { it.key }
        assertEquals("plan must cover the five prayers", 5, byKey.size)
        byKey.forEach { (key, alarms) ->
            val at = alarms.first { it.type == PrayerAlertType.AT }
            val before = alarms.first { it.type == PrayerAlertType.BEFORE }
            val offsetMin = (at.prayerAt - before.fireAt) / 60_000L
            log("offset $key: AT=${at.prayerAt} BEFORE fires ${offsetMin}min earlier")
            assertEquals("BEFORE offset must be 10 minutes for $key", 10L, offsetMin)
        }
        log("times match reference (both dates, tolerance 2min) + BEFORE offset 10min — OK")
    }

    private fun minutesBetween(expected: String, computed: String): Int {
        fun toMin(t: String): Int {
            val (h, m) = t.split(":").map { it.toInt() }
            return h * 60 + m
        }
        return toMin(computed) - toMin(expected)
    }

    /**
     * عند رفض إذن المنبّهات الدقيقة يسقط التطبيق لبديل غير دقيق — المنبّه
     * يبقى مسجَّلًا في النظام (لا يضيع الأذان)، لكن دقته غير مضمونة. على
     * API 30 وما قبلها الإذن الدقيق متاح دائمًا فيُسجَّل ذلك ويُتخطى.
     */
    @Test
    fun test05_exactAlarmDeniedStillSchedules() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            log("API ${Build.VERSION.SDK_INT}: exact alarms always available pre-S — skipped")
            return
        }
        val denied = shellOutput(
            "appops set --uid ${context.packageName} SCHEDULE_EXACT_ALARM deny"
        )
        log("appops deny output: '${denied.trim()}'")
        Thread.sleep(1000)
        if (NotificationMaintenance.canScheduleExact(context)) {
            log("could NOT deny exact alarms on this emulator — fallback path unprovable here, skipped")
            return
        }
        log("exact alarms denied (canScheduleExact=false)")
        PrayerReminderScheduler.schedule(context)
        Thread.sleep(2000)
        val dumpsys = shellOutput("dumpsys alarm")
        val atLines = dumpsys.lines().count { it.contains("PRAYER_AT") }
        log("dumpsys PRAYER_AT lines under denial: $atLines")
        assertTrue("no PRAYER_AT alarm registered under exact-alarm denial", atLines > 0)
        shellOutput("appops set --uid ${context.packageName} SCHEDULE_EXACT_ALARM default")
        Thread.sleep(1000)
        log("restored exact-alarm op; canScheduleExact=${NotificationMaintenance.canScheduleExact(context)}")
    }

    /**
     * الإطلاق المزدوج (منبّه النشاط المباشر + نية ملء الشاشة) يفتح نسخة
     * واحدة فقط من شاشة التنبيه: singleTop + حارس الـ 60 ثانية.
     */
    @Test
    fun test06_doubleLaunchShowsOneActivity() {
        val atMillis = System.currentTimeMillis() + 60_000L
        fun launchIntent() = Intent(context, PrayerAlertActivity::class.java)
            .putExtra(PrayerAlertActivity.EXTRA_PRAYER_NAME, "الفجر")
            .putExtra(PrayerAlertActivity.EXTRA_PRAYER_KEY, "fajr")
            .putExtra(PrayerAlertActivity.EXTRA_AT_MILLIS, atMillis)
            .putExtra(PrayerAlertActivity.EXTRA_TYPE, "at")
            .putExtra(PrayerAlertActivity.EXTRA_VOICE, "default")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(launchIntent())
        Thread.sleep(500)
        context.startActivity(launchIntent())
        Thread.sleep(3000)
        val found = device.wait(Until.hasObject(By.text("حان وقت الأذان")), 10_000)
        log("alert text visible: $found")
        shot("06_double_launch.png")
        val activities = shellOutput("dumpsys activity activities")
        val records = activities.lines().count {
            it.contains("ActivityRecord") && it.contains("PrayerAlertActivity")
        }
        log("PrayerAlertActivity ActivityRecord lines: $records")
        assertEquals("double launch stacked two alert activities", 1, records)
        runCatching { device.pressBack() }
        Thread.sleep(1000)
    }
}
