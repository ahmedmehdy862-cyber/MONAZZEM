package com.focusstudy.poc

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Lock-screen verification for the assistant's notifications (wird, exercise,
 * follow-up chain): the DAILY channel must be IMPORTANCE_DEFAULT (a LOW channel
 * never shows on the lock screen at all) with VISIBILITY_PUBLIC, and every
 * notification built for it must carry VISIBILITY_PUBLIC with full content.
 *
 * The visual check on Ahmed's real Xiaomi/MIUI device remains a manual gate —
 * this test pins the code-side guarantees so they cannot regress silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssistantLockScreenTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        runCatching { StudyDb.get(context).close() }
        runCatching { context.deleteDatabase("focus_study.db") }
    }

    @Test
    fun dailyChannelIsDefaultImportanceAndPublicOnLockScreen() {
        AppNotifications.ensure(context, AppNotifications.DAILY)
        val channel = AppNotifications.manager(context)
            .getNotificationChannel(AppNotifications.DAILY.id)
        assertTrue(channel != null)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel!!.importance)
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
    }

    @Test
    fun everyAlertChannelIsDefaultOrHighAndPublic() {
        AppNotifications.ALL.forEach { alert ->
            AppNotifications.ensure(context, alert)
            val channel = AppNotifications.manager(context).getNotificationChannel(alert.id)
            assertTrue("channel ${alert.id} missing", channel != null)
            assertTrue(
                "channel ${alert.id} importance ${channel!!.importance} hides lock-screen content",
                channel.importance == NotificationManager.IMPORTANCE_DEFAULT ||
                    channel.importance == NotificationManager.IMPORTANCE_HIGH
            )
            assertEquals(
                "channel ${alert.id} must show full content on lock screen",
                NotificationCompat.VISIBILITY_PUBLIC,
                channel.lockscreenVisibility
            )
        }
    }

    @Test
    fun followUpNotificationIsPublicAndOnDailyChannel() {
        val contentIntent = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // The follow-up post builds exactly this way (DAILY channel, full text).
        val notification = AppNotifications.builder(
            context = context,
            channel = AppNotifications.DAILY,
            title = AssistantFollowUpPolicy.titleFor(FollowUpKind.WIRD_MORNING, 2),
            message = AssistantFollowUpPolicy.bodyFor(FollowUpKind.WIRD_MORNING, 2, "صفحات 2–3."),
            contentIntent = contentIntent
        ).build()
        assertEquals(AppNotifications.DAILY.id, notification.channelId)
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, notification.visibility)
    }

    @Test
    fun lockScreenStateReportsNoBlockedChannels() {
        AppNotifications.ALL.forEach { AppNotifications.ensure(context, it) }
        val state = AppNotifications.lockScreenState(context)
        assertTrue(state.systemShowsOnLock)
        assertTrue(state.blockedChannels.isEmpty())
    }
}
