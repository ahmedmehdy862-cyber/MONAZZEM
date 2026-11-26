package com.focusstudy.poc

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adhan alert QA on a real emulator: the full-screen alert shows the big one-tap
 * «إيقاف الأذان» button, and tapping it stops the sound for real (the playback
 * service's ongoing notification disappears and the alert closes).
 *
 * Runs in light and dark (the alert follows the person's theme choice). The two
 * tests use different prayers so the alert's duplicate-launch guard (same alert
 * within 60s) cannot swallow the second launch.
 *
 * Honest limit of this test: it proves the stop path and the visible button, not
 * audible loudness or routing to a real headset — the emulator runs with -noaudio
 * and has no headset. Those need a real device (see the report).
 */
@RunWith(AndroidJUnit4::class)
class AdhanAlertQaTest {

    private lateinit var device: UiDevice
    private lateinit var shotDir: File
    private lateinit var logFile: File
    private var scenario: ActivityScenario<PrayerAlertActivity>? = null

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
        Log.i("AdhanQA", line)
        runCatching { logFile.appendText(line + "\n") }
    }

    private fun setTheme(theme: String) {
        appContext().getSharedPreferences("focus_study_preferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarding_completed", true)
            .putString("theme_mode", theme)
            .apply()
    }

    private fun alertIntent(name: String, key: String): Intent =
        Intent(appContext(), PrayerAlertActivity::class.java)
            .putExtra(PrayerAlertActivity.EXTRA_PRAYER_NAME, name)
            .putExtra(PrayerAlertActivity.EXTRA_PRAYER_KEY, key)
            .putExtra(PrayerAlertActivity.EXTRA_AT_MILLIS, System.currentTimeMillis())
            .putExtra(PrayerAlertActivity.EXTRA_TYPE, PrayerAlertType.AT.key)
            .putExtra(PrayerAlertActivity.EXTRA_VOICE, PrayerAdhanVoice.DEFAULT.key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun serviceNotificationPresent(): Boolean {
        val nm = appContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.activeNotifications.any { it.id == SERVICE_NOTIFICATION_ID }
    }

    private fun launchAndStop(theme: String, name: String, key: String, shotPrefix: String) {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val ctx = appContext()
        shotDir = File(ctx.getExternalFilesDir(null), "qa-screenshots").apply { mkdirs() }
        logFile = File(shotDir, "qa-log.txt")
        setTheme(theme)
        // The alert plays the adhan on launch (key != null + default rule AT sound = ADHAN).
        scenario = ActivityScenario.launch<PrayerAlertActivity>(alertIntent(name, key))
        Thread.sleep(3000)
        val playingBefore = serviceNotificationPresent()
        log("[$theme] service notification present before tap: $playingBefore")
        assertTrue("[$theme] adhan service did not start", playingBefore)

        assertTrue(
            "[$theme] stop button not visible",
            device.wait(Until.hasObject(By.text("إيقاف الأذان")), 10000)
        )
        assertTrue(
            "[$theme] close caption missing",
            device.hasObject(By.text("إغلاق الشاشة لا يوقف الأذان"))
        )
        val shot = File(shotDir, "$shotPrefix-alert.png")
        val shotOk = runCatching { device.takeScreenshot(shot) }.getOrDefault(false)
        log("[$theme] shot $shotPrefix-alert ok=$shotOk size=${if (shot.exists()) shot.length() else -1}")

        // One tap — no confirmation step any more.
        device.findObject(By.text("إيقاف الأذان")).click()
        val stopped = waitFor(timeoutMs = 8000) { !serviceNotificationPresent() }
        val destroyed = waitFor(timeoutMs = 8000) {
            runCatching { scenario?.state == Lifecycle.State.DESTROYED }.getOrDefault(false)
        }
        log("[$theme] service notification gone after tap: $stopped")
        log("[$theme] alert activity destroyed after tap: $destroyed")
        assertTrue("[$theme] adhan did not stop after tapping «إيقاف الأذان»", stopped)
        assertTrue("[$theme] alert did not close after tapping «إيقاف الأذان»", destroyed)
        assertFalse(
            "[$theme] service notification still present after stop",
            serviceNotificationPresent()
        )
    }

    private fun waitFor(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching { check() }.getOrDefault(false)) return true
            Thread.sleep(250)
        }
        return runCatching { check() }.getOrDefault(false)
    }

    @Test
    fun stopButtonStopsAdhanLight() {
        launchAndStop("light", "الفجر", "fajr", "adhan-light")
    }

    @Test
    fun stopButtonStopsAdhanDark() {
        launchAndStop("dark", "الظهر", "dhuhr", "adhan-dark")
    }

    private companion object {
        /** Ongoing notification id of AdhanPlaybackService. */
        const val SERVICE_NOTIFICATION_ID = 6301
    }
}
