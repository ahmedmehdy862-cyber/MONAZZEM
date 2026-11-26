package com.focusstudy.poc

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adhan alert QA on a real emulator: the full-screen alert shows the big one-tap
 * «إيقاف الأذان» button, the alarm stream is raised to its maximum while the
 * adhan plays, and tapping the button stops the sound for real (the playback
 * service stops, its volume is restored, and the alert closes).
 *
 * Runs in light and dark (the alert follows the person's theme choice). The two
 * tests use different prayers so the alert's duplicate-launch guard (same alert
 * within 60s) cannot swallow the second launch.
 *
 * What this test deliberately does NOT assert: the service's ongoing notification
 * (6301). On API 34 that notification depends on the foreground-service allowance
 * of the launch path, and the test's launch paths cannot provide it:
 *  - ActivityScenario.launch shows the alert fine, but the service started from
 *    the activity's onCreate is refused foreground status on API 34 (run
 *    45796616349: the service started, requested alarm focus and decoded the
 *    clip, yet notification 6301 never appeared);
 *  - PendingIntent.send() from the background test process never launches the
 *    activity at all on API 34 (run 45799063258: no lifecycle lines whatsoever),
 *    because a self-sent PendingIntent from the background is BAL-blocked.
 * The production flows do not have this problem: the exact alarm grants the
 * allowance to the receiver that starts the service, and the alarm's screen
 * PendingIntent is sent by the system. So the test asserts the service itself
 * (running/stopped) and the alarm volume, which are the user-visible facts.
 *
 * Honest limit of this test: it proves the stop path, the visible button and the
 * volume boost, not audible loudness or routing to a real headset — the emulator
 * runs with -noaudio and has no headset. Those need a real device (see the report).
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

    private fun audioManager(): AudioManager =
        appContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun serviceNotificationPresent(): Boolean {
        val nm = appContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.activeNotifications.any { it.id == SERVICE_NOTIFICATION_ID }
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(): Boolean {
        val am = appContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == AdhanPlaybackService::class.java.name }
    }

    private fun waitFor(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching { check() }.getOrDefault(false)) return true
            Thread.sleep(250)
        }
        return runCatching { check() }.getOrDefault(false)
    }

    private fun launchAndStop(theme: String, name: String, key: String, shotPrefix: String) {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val ctx = appContext()
        shotDir = File(ctx.getExternalFilesDir(null), "qa-screenshots").apply { mkdirs() }
        logFile = File(shotDir, "qa-log.txt")
        setTheme(theme)
        val am = audioManager()
        val volumeBefore = am.getStreamVolume(AudioManager.STREAM_ALARM)
        val volumeMax = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        log("[$theme] alarm volume before: $volumeBefore / max $volumeMax")
        // The alert plays the adhan on launch (key != null + default rule AT sound = ADHAN).
        scenario = ActivityScenario.launch<PrayerAlertActivity>(alertIntent(name, key))
        assertTrue(
            "[$theme] stop button not visible",
            device.wait(Until.hasObject(By.text("إيقاف الأذان")), 15000)
        )
        val runningBefore = waitFor(timeoutMs = 10000) { serviceRunning() }
        log("[$theme] service running before tap: $runningBefore")
        assertTrue("[$theme] adhan service did not start", runningBefore)
        // Loud: the alarm stream is raised to its maximum while the adhan plays.
        val loudBefore = waitFor(timeoutMs = 10000) {
            am.getStreamVolume(AudioManager.STREAM_ALARM) == volumeMax
        }
        log(
            "[$theme] alarm volume at max before tap: $loudBefore " +
                "(now ${am.getStreamVolume(AudioManager.STREAM_ALARM)})"
        )
        assertTrue("[$theme] alarm volume was not raised to maximum", loudBefore)
        log("[$theme] service notification present (diagnostic only): ${serviceNotificationPresent()}")
        assertTrue(
            "[$theme] close caption missing",
            device.hasObject(By.text("إغلاق الشاشة لا يوقف الأذان"))
        )
        val shot = File(shotDir, "$shotPrefix-alert.png")
        val shotOk = runCatching { device.takeScreenshot(shot) }.getOrDefault(false)
        log("[$theme] shot $shotPrefix-alert ok=$shotOk size=${if (shot.exists()) shot.length() else -1}")

        // One tap — no confirmation step any more.
        device.findObject(By.text("إيقاف الأذان")).click()
        val stopped = waitFor(timeoutMs = 8000) { !serviceRunning() }
        val closed = device.wait(Until.gone(By.text("إيقاف الأذان")), 8000)
        // The volume is restored when playback ends.
        val restored = waitFor(timeoutMs = 8000) {
            am.getStreamVolume(AudioManager.STREAM_ALARM) == volumeBefore
        }
        log("[$theme] service stopped after tap: $stopped")
        log("[$theme] alert closed after tap: $closed")
        log(
            "[$theme] alarm volume restored after tap: $restored " +
                "(now ${am.getStreamVolume(AudioManager.STREAM_ALARM)}, before $volumeBefore)"
        )
        assertTrue("[$theme] adhan did not stop after tapping «إيقاف الأذان»", stopped)
        assertTrue("[$theme] alert did not close after tapping «إيقاف الأذان»", closed)
        assertFalse(
            "[$theme] service notification still present after stop",
            serviceNotificationPresent()
        )
        assertEquals(
            "[$theme] alarm volume was not restored after stop",
            volumeBefore,
            am.getStreamVolume(AudioManager.STREAM_ALARM)
        )
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
