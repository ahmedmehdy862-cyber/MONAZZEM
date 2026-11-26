package com.focusstudy.poc

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * اختبارات تذكير التمارين: الإعدادات الافتراضية، والنشر التجريبي،
 * وتخطّي التذكير إن سُجّل تمرين اليوم.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExerciseReminderTest {

    private lateinit var context: Context
    private lateinit var db: StudyDb

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        runCatching { StudyDb.get(context).close() }
        context.deleteDatabase(DB_FILE)
        db = StudyDb.get(context)
        db.writableDatabase.close()
        // بيئة الاختبار: إذن الإشعارات ممنوح والإشعارات مفعّلة.
        shadowOf(context.applicationContext as Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(AppNotifications.manager(context)).setNotificationsEnabled(true)
    }

    @After
    fun tearDown() {
        runCatching { StudyDb.get(context).close() }
        runCatching { context.deleteDatabase(DB_FILE) }
    }

    @Test
    fun defaultsAreEnabledAtSixPm() {
        val settings = ExerciseReminderPrefs.load(context)
        assertTrue(settings.enabled)
        assertEquals(18, settings.hour)
        assertEquals(0, settings.minute)
    }

    @Test
    fun testNotificationIsPostedOnDailyChannel() {
        ExerciseReminderScheduler.notifyTest(context)
        val posted = shadowOf(AppNotifications.manager(context)).allNotifications
        assertTrue(posted.isNotEmpty())
        val notification = posted.first()
        assertEquals(AppNotifications.DAILY.id, notification.channelId)
        assertTrue(notification.extras.getString("android.text").orEmpty().contains("تمرين"))
    }

    @Test
    fun noReminderOnNonPlanDay() {
        // اليوم (الخميس) ليس يوم خطة: لا تذكير أصلًا — بغض النظر عن التسجيل.
        ExerciseReminderScheduler.notify(context, test = false)
        val posted = shadowOf(AppNotifications.manager(context)).allNotifications
        assertTrue(posted.isEmpty())
    }

    @Test
    fun followUpChainStopsWhenExerciseLogged() {
        val todayKey = LocalDate.now(AppTimeZone.zone(context)).toString()
        db.upsertAssistantFollowUp(
            kind = "exercise", slot = "daily", day = todayKey,
            attempts = 1, maxAttempts = 5, nextAt = 0L, state = "active",
            snoozeUntil = null, detail = "تمرين النهاردة.", now = 1000L
        )
        // قبل التسجيل: السلسلة نشطة.
        assertEquals(1, db.assistantFollowUpsActive().size)
        db.setExerciseDone("ex-1", todayKey, true)
        // fire() بعد التسجيل ينهي السلسلة بصمت بلا نشر.
        AssistantFollowUpScheduler.fire(context, FollowUpKind.EXERCISE, "daily", todayKey)
        assertTrue(db.assistantFollowUpsActive().isEmpty())
        assertEquals("done", db.assistantFollowUp("exercise", "daily", todayKey)!!.state)
    }

    private companion object {
        const val DB_FILE = "focus_study.db"
    }
}
