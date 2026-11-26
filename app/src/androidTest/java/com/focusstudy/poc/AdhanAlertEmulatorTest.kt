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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * تحقق حي من إشعارات الأذان بالتصميم الجديد على كود 0.33.1 المنشور (main a3adcf60).
 *
 * لا يغيّر كود الإنتاج إطلاقًا؛ يطلق مسار الإنتاج نفسه (منبّه دقيق ← مستقبل
 * البث ← إشعار + نية ملء الشاشة + منبّه نشاط مباشر) ويثبت باللقطات وسجل
 * النظام أن التنبيه يظهر فعلًا. ترتيب الطرق ثابت بالاسم حتى لا يتداخل
 * تنبيه «قبل الصلاة» مع تنبيه «عند الصلاة».
 *
 * ملاحظة: التثبيت الجديد يبدأ وإشعارات الصلاة معطّلة (PrayerPrefs.enabled
 * الافتراضي false) وبلا موقع، لذلك يزرع الاختبار مستخدمًا فعّل الأذان
 * وحفظ موقع القاهرة — تمامًا كما يفعل الإعداد الأولي — قبل أي خطوة.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AdhanAlertEmulatorTest {

    private lateinit var device: UiDevice
    private lateinit var context: Context
    private lateinit var shotDir: File
    private lateinit var logFile: File

    @Before
    fun setUp() {
        val instr = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instr)
        context = instr.targetContext
        // API 33+: إذن الإشعارات وقت التشغيل. على API 30 لا يوجد أصلًا.
        runCatching {
            instr.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            )
        }
        // مستخدم فعّل الأذان وحفظ موقع القاهرة (الإعداد الأولي يفعل هذا).
        context.getSharedPreferences("prayer_preferences", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true)
            .putString("latitude_text", "30.0444")
            .putString("longitude_text", "31.2357")
            .apply()
        AppNotifications.ensure(context, AppNotifications.PRAYER_AT)
        AppNotifications.ensure(context, AppNotifications.PRAYER_BEFORE)
        AppNotifications.ensurePlaybackChannel(context)
        shotDir = File(context.getExternalFilesDir(null), "qa-screenshots").apply { mkdirs() }
        logFile = File(shotDir, "qa-log.txt")
        log("canScheduleExact=${NotificationMaintenance.canScheduleExact(context)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            log("canUseFullScreenIntent=${nm.canUseFullScreenIntent()}")
        }
    }

    @After
    fun tearDown() {
        runCatching { QaExport.exportDir(context, shotDir) }
    }

    private fun log(line: String) {
        Log.i("AdhanQA", line)
        runCatching { logFile.appendText(line + "\n") }
    }

    private fun shot(name: String) {
        val file = File(shotDir, name)
        val ok = runCatching { device.takeScreenshot(file) }.getOrDefault(false)
        log("screenshot $name ok=$ok")
    }

    private fun activeOnChannel(channelId: String): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.activeNotifications.any { it.notification.channelId == channelId }
    }

    private fun shellOutput(command: String): String {
        val instr = InstrumentationRegistry.getInstrumentation()
        val pfd = instr.uiAutomation.executeShellCommand(command)
        return pfd.use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
    }

    /**
     * الرؤية الفعلية على شاشة القفل — نفس منطق CTS Verifier: قيمة القناة إن
     * حدّدت شيئًا، وإلا الإعداد العام للجهاز. التشغيل الأول أثبت أن النظام
     * يعيد -1000 لقناة أُنشئت مع PUBLIC على API 30 و34 معًا؛ القيمة -1000
     * ليست عيبًا بل «لا تجاوز — اتبع الإعداد العام»، والعام الافتراضي PUBLIC
     * (إشعارات القفل مفعّلة والمحتوى الخاص مسموح).
     * ملاحظة: الثابت NotificationManager.VISIBILITY_NO_OVERRIDE مخفي (@hide)
     * ولا يُترجم من كود التطبيق — لذلك القيمة الحرفية -1000 هنا.
     */
    private fun effectiveVisibility(channel: android.app.NotificationChannel): Int {
        val v = channel.lockscreenVisibility
        if (v != -1000) return v
        val cr = context.contentResolver
        val show = android.provider.Settings.Secure.getInt(cr, "lock_screen_show_notifications", 1) == 1
        val allowPrivate = android.provider.Settings.Secure.getInt(cr, "lock_screen_allow_private_notifications", 1) == 1
        return when {
            !show -> android.app.Notification.VISIBILITY_SECRET
            !allowPrivate -> android.app.Notification.VISIBILITY_PRIVATE
            else -> android.app.Notification.VISIBILITY_PUBLIC
        }
    }

    @Test
    fun test01_channelsAndRollingSchedule() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val at = nm.getNotificationChannel(AppNotifications.PRAYER_AT.id)
        val before = nm.getNotificationChannel(AppNotifications.PRAYER_BEFORE.id)
        assertTrue("PRAYER_AT channel missing", at != null)
        assertTrue("PRAYER_BEFORE channel missing", before != null)
        assertEquals(
            "PRAYER_AT must be HIGH (heads-up + full-screen)",
            NotificationManager.IMPORTANCE_HIGH, at!!.importance
        )
        log("PRAYER_AT raw lockscreenVisibility=${at.lockscreenVisibility} (1=PUBLIC, -1000=NO_OVERRIDE)")
        assertEquals(
            "PRAYER_AT must show fully on the lock screen (effective visibility)",
            android.app.Notification.VISIBILITY_PUBLIC, effectiveVisibility(at)
        )
        assertEquals(
            "PRAYER_BEFORE must be HIGH",
            NotificationManager.IMPORTANCE_HIGH, before!!.importance
        )
        log("PRAYER_BEFORE raw lockscreenVisibility=${before.lockscreenVisibility}")
        assertEquals(
            "PRAYER_BEFORE must show fully on the lock screen (effective visibility)",
            android.app.Notification.VISIBILITY_PUBLIC, effectiveVisibility(before)
        )
        log("channels OK: prayer_at_v4 HIGH/effective-PUBLIC, prayer_before_v4 HIGH/effective-PUBLIC")

        // الجدولة الحقيقية لنافذة الأيام السبعة، ثم إثبات وجود المنبهات في النظام.
        PrayerReminderScheduler.schedule(context)
        val dumpsys = shellOutput("dumpsys alarm")
        val prayerAlarms = dumpsys.lineSequence()
            .filter { it.contains("PRAYER_AT") || it.contains("PRAYER_BEFORE") }
            .take(10).toList()
        log("dumpsys prayer alarm lines: ${prayerAlarms.size}")
        prayerAlarms.forEach { log("  ALARM: $it") }
        assertTrue(
            "No PRAYER_AT/PRAYER_BEFORE alarm found in dumpsys after schedule()",
            prayerAlarms.isNotEmpty()
        )
    }

    @Test
    fun test02_beforeReminderPostsWithoutFullscreen() {
        // «قبل الصلاة» بث مباشر عبر المسار الحقيقي للمستقبل.
        val intent = Intent(context, PrayerReminderReceiver::class.java)
            .setAction("com.focusstudy.poc.PRAYER_BEFORE")
            .putExtra("prayer_key", "fajr")
            .putExtra(PrayerReminderReceiver.EXTRA_NAME, "الفجر")
            .putExtra("prayer_alert_type", "before")
            .putExtra("prayer_time_millis", System.currentTimeMillis() + 10 * 60_000L)
        context.sendBroadcast(intent)
        var posted = false
        for (i in 0 until 15) {
            if (activeOnChannel(AppNotifications.PRAYER_BEFORE.id)) { posted = true; break }
            Thread.sleep(1000)
        }
        assertTrue("BEFORE notification never posted on prayer_before_v4", posted)
        log("BEFORE notification posted on prayer_before_v4")
        // سياسة التصميم: «قبل» لا تفتح الشاشة أبدًا.
        Thread.sleep(3000)
        assertTrue(
            "BEFORE alert wrongly opened the full-screen activity",
            !device.hasObject(By.text("حان وقت الأذان"))
        )
        device.openNotification()
        val inShade = device.wait(Until.hasObject(By.textContains("قبل صلاة الفجر")), 10_000)
        shot("02_before_shade.png")
        device.pressBack()
        assertTrue("BEFORE notification not visible in the shade", inShade)
        log("BEFORE visible in shade, no full-screen — policy OK")
    }

    @Test
    fun test03_atAlertOnLockScreen() {
        val fireAt = System.currentTimeMillis() + 20_000L
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // منبّه البث: نفس ما يبنيه setAlarm في الإنتاج (نفس الإجراء والإضافات).
        val broadcast = PendingIntent.getBroadcast(
            context, 7000,
            Intent(context, PrayerReminderReceiver::class.java)
                .setAction("com.focusstudy.poc.PRAYER_AT")
                .putExtra("prayer_key", "fajr")
                .putExtra(PrayerReminderReceiver.EXTRA_NAME, "الفجر")
                .putExtra("prayer_alert_type", "at")
                .putExtra("prayer_time_millis", fireAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // منبّه الشاشة المباشر: نفس screenPendingIntent في الإنتاج (الشق +2).
        val screen = PendingIntent.getActivity(
            context, 7002,
            Intent(context, PrayerAlertActivity::class.java)
                .putExtra(PrayerAlertActivity.EXTRA_PRAYER_NAME, "الفجر")
                .putExtra(PrayerAlertActivity.EXTRA_PRAYER_KEY, "fajr")
                .putExtra(PrayerAlertActivity.EXTRA_AT_MILLIS, fireAt)
                .putExtra(PrayerAlertActivity.EXTRA_TYPE, "at")
                .putExtra(PrayerAlertActivity.EXTRA_VOICE, "default")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, broadcast)
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, screen)
        log("AT alarm scheduled for +20s (broadcast 7000 + activity 7002)")

        // قفل الجهاز كما يكون وقت الفجر عادةً، ثم انتظار التنبيه.
        device.sleep()
        Thread.sleep(1000)
        val textFound = device.wait(Until.hasObject(By.text("حان وقت الأذان")), 90_000)
        log("screenOn=${device.isScreenOn} textFound=$textFound")
        shot("01_adhan_fullscreen_lock.png")
        // دليل مستقل عن UiAutomator: التشغيل الأول أثبت أن النشاط يُعرض
        // فوق القفل على API 34 (Displayed + setOccluded(true)) بينما لا يرى
        // UiAutomator نصوص نافذة showWhenLocked والقفل نشط — فالحكم هنا على
        // حالة النشاط الفعلية من النظام، والنص مجرد ملاحظة مسجلة.
        val activities = shellOutput("dumpsys activity activities")
        val actLines = activities.lines()
        val idx = actLines.indexOfFirst { it.contains("PrayerAlertActivity") }
        val window = if (idx >= 0) actLines.subList(idx, minOf(idx + 40, actLines.size)) else emptyList()
        window.take(8).forEach { log("  ACT: $it") }
        val resumed = window.any {
            it.contains("RESUMED", ignoreCase = true) || it.contains("mResumed=true")
        }
        val windows = shellOutput("dumpsys window windows")
        val focus = windows.lineSequence()
            .filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }.take(4).toList()
        focus.forEach { log("  WIN: $it") }
        val alertFocused = focus.any { it.contains("PrayerAlertActivity") }
        val foregroundPackage = device.currentPackageName
        log("foregroundPackage=$foregroundPackage textFound=$textFound resumed=$resumed alertFocused=$alertFocused")
        assertTrue(
            "PrayerAlertActivity is not resumed/focused on the locked device",
            idx >= 0 && (resumed || alertFocused)
        )
        assertEquals(
            "Alert activity is not the foreground package",
            context.packageName, foregroundPackage
        )

        // الإشعار نفسه منشور على قناة الأذان؟
        assertTrue(
            "AT notification missing on prayer_at_v4",
            activeOnChannel(AppNotifications.PRAYER_AT.id)
        )
        // ملاحظة فقط (لا إلزام): هل بدأ صوت الأذان كخدمة أمامية؟
        val serviceOn = activeOnChannel(AppNotifications.ADHAN_PLAYBACK.id)
        log("adhan playback service notification present=$serviceOn")

        // إغلاق التنبيه ثم التحقق من ظهور الإشعار في الستارة.
        runCatching { device.findObject(By.text("إغلاق"))?.click() }
        Thread.sleep(1500)
        device.openNotification()
        val inShade = device.wait(Until.hasObject(By.textContains("حان وقت صلاة الفجر")), 15_000)
        shot("03_adhan_shade.png")
        device.pressBack()
        device.pressHome()
        assertTrue("AT notification not visible in the shade", inShade)
        log("AT alert on lock screen + notification in shade — PASS")
    }
}
