package com.focusstudy.poc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

internal object StudyNotificationScheduler {
    const val EXTRA_OPEN_TASKS = "com.focusstudy.poc.OPEN_TASKS"
    const val ACTION_TASK_NUDGE = "com.focusstudy.poc.TASK_NUDGE"
    const val ACTION_MORNING = "com.focusstudy.poc.MORNING_MESSAGE"
    const val ACTION_EVENING = "com.focusstudy.poc.EVENING_MESSAGE"

    private const val TASK_NOTIFICATION_ID = 5101
    private const val MORNING_NOTIFICATION_ID = 5102
    private const val EVENING_NOTIFICATION_ID = 5103
    private const val TASK_REQUEST = 5111
    private const val MORNING_REQUEST = 5112
    private const val EVENING_REQUEST = 5113
    private val taskHours = listOf(9, 11, 13, 15, 17, 19, 21)

    fun update(context: Context, preferences: FocusPreferences) {
        cancelLegacyReminder(context)
        // One task-reminder mode at a time: smart on means the engine owns task notifications and
        // the classic two-hour nudge stays cancelled; smart off means the classic cadence runs.
        // The old `legacyReminders` flag is no longer consulted here — the notification screen now
        // offers a single mode choice, so the two flags can never disagree again.
        if (preferences.smartRemindersEnabled) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(
                pendingIntent(context, ACTION_TASK_NUDGE, TASK_REQUEST)
            )
        } else {
            scheduleNextTaskNudge(context)
        }
        // Morning/evening messages have their own switches now: turning team messages off used
        // to silence them through the shared `generalNotificationsEnabled` flag without saying so.
        scheduleDaily(
            context,
            ACTION_MORNING,
            MORNING_REQUEST,
            preferences.morningMessageHour,
            preferences.morningMessageEnabled
        )
        scheduleDaily(
            context,
            ACTION_EVENING,
            EVENING_REQUEST,
            preferences.eveningMessageHour,
            preferences.eveningMessageEnabled
        )
    }

    private fun cancelLegacyReminder(context: Context) {
        val legacy = PendingIntent.getBroadcast(
            context,
            402,
            Intent(context, DailyReminderReceiver::class.java).setAction("com.focusstudy.poc.DAILY_REMINDER"),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(legacy)
        legacy.cancel()
    }

    private fun scheduleNextTaskNudge(context: Context) {
        val now = ZonedDateTime.now()
        val next = taskHours.map { hour -> now.withHour(hour).withMinute(0).withSecond(0).withNano(0) }
            .firstOrNull { it.isAfter(now) }
            ?: now.plusDays(1).withHour(taskHours.first()).withMinute(0).withSecond(0).withNano(0)
        schedule(context, ACTION_TASK_NUDGE, TASK_REQUEST, next.toInstant().toEpochMilli())
    }

    private fun scheduleDaily(context: Context, action: String, requestCode: Int, hour: Int, enabled: Boolean) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context, action, requestCode)
        if (!enabled) {
            alarmManager.cancel(pendingIntent)
            alarmManager.cancel(pendingIntent(context, action, deferredRequestCode(requestCode)))
            return
        }
        val now = ZonedDateTime.now()
        var next = now.withHour(hour.coerceIn(0, 23)).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        // The evening message at 20:00 can sit within ±15 minutes of the Isha adhan; push it to
        // the end of the spacing window instead of stacking it on the adhan (same for Fajr vs.
        // the morning message when the user picks an early hour).
        val atMillis = NotificationPolicy.adjustOutsidePrayerWindows(
            next.toInstant().toEpochMilli(),
            PrayerWindows.todayPrayerMillis(context.applicationContext)
        )
        // م5: جدولة موحّدة — منبّه دقيق عند السماح، وغير دقيق مع تسجيل تشخيصي
        // عند الرفض أو الفشل، بدل الاستدعاء المباشر الصامت.
        NotificationMaintenance.schedule(context, alarmManager, atMillis, pendingIntent, kind = "daily")
    }

    /** A deferred retry keeps its own request code so the daily reschedule never cancels it. */
    private fun deferredRequestCode(requestCode: Int): Int = requestCode + 100

    private fun scheduleDeferred(context: Context, action: String, requestCode: Int, atMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        NotificationMaintenance.schedule(
            context, alarmManager, atMillis,
            pendingIntent(context, action, deferredRequestCode(requestCode)),
            kind = "daily"
        )
    }

    private fun schedule(context: Context, action: String, requestCode: Int, atMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // م5: نفس الجدولة الموحّدة (انظر scheduleDaily) — kind = "task" للتشخيص.
        NotificationMaintenance.schedule(
            context, alarmManager, atMillis,
            pendingIntent(context, action, requestCode),
            kind = "task"
        )
    }

    private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, DailyReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** A real notification to look for in the shade: the notification screen's proof button. */
    fun notifyTest(context: Context) {
        if (!notificationsAllowed(context)) return
        post(
            context = context,
            channel = AppNotifications.DAILY,
            notificationId = 5999,
            title = "الإشعارات تعمل ✓",
            message = "هكذا سيصلك صوت مُنظِّم المميز. اضغط لفتح مهامي.",
            openTasks = true
        )
    }

    suspend fun dispatch(context: Context, action: String?) {
        val appContext = context.applicationContext
        when (action) {
            ACTION_TASK_NUDGE -> showTaskNudge(appContext)
            ACTION_MORNING -> showEncouragement(appContext, morning = true)
            ACTION_EVENING -> showEncouragement(appContext, morning = false)
        }
        update(appContext, FocusPreferences.load(appContext))
    }

    private fun showTaskNudge(context: Context) {
        if (!notificationsAllowed(context)) return
        val database = StudyDb.get(context)
        val activeSession = database.activeSession()?.takeIf { it.endsAt > System.currentTimeMillis() }
        if (activeSession != null) return

        val pending = database.tasks().filter {
            !it.isDone && it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED
        }
        if (pending.isEmpty()) {
            notificationManager(context).cancel(TASK_NOTIFICATION_ID)
            return
        }
        val nextTask = pending.sortedWith(
            compareBy<StudyTask> {
                when {
                    it.dueAt?.let { due ->
                        java.time.Instant.ofEpochMilli(due).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                            .isBefore(java.time.LocalDate.now())
                    } == true -> 0
                    it.priority == TaskPriority.URGENT -> 1
                    else -> 2
                }
            }.thenByDescending { it.priority.level }.thenBy { it.dueAt ?: Long.MAX_VALUE }
        ).first()
        val name = FocusPreferences.load(context).studentName.trim()
        val overdue = pending.count { task ->
            task.dueAt?.let {
                java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    .isBefore(java.time.LocalDate.now())
            } == true
        }
        val message = ReminderCopy.taskNudge(
            name = name,
            total = pending.size,
            overdue = overdue,
            nextTitle = nextTask.title
        )
        post(
            context,
            channel = AppNotifications.TASK,
            notificationId = TASK_NOTIFICATION_ID,
            title = "خطوة واحدة تكفي الآن",
            message = message,
            openTasks = true
        )
    }

    private fun showEncouragement(context: Context, morning: Boolean) {
        val prefs = FocusPreferences.load(context)
        // Each message has its own switch; the old shared `generalNotificationsEnabled` gate is
        // gone, so silencing team messages no longer silences the morning/evening messages.
        val enabled = if (morning) prefs.morningMessageEnabled else prefs.eveningMessageEnabled
        if (!enabled || !notificationsAllowed(context)) return
        val now = System.currentTimeMillis()
        // The shared policy gate: quiet hours, the daily budget, the 45-minute gap, an active
        // focus session and the prayer window. A deferred message is re-alarmed at the instant
        // the policy names instead of being lost.
        when (val decision = NotificationDelivery.decide(context, enabled, now)) {
            ReminderDecision.Send -> {
                val today = java.time.LocalDate.now()
                val open = StudyDb.get(context).tasks().filter {
                    !it.isDone && it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED
                }
                val dueToday = open.filter { it.dueAt?.let { end -> java.time.Instant.ofEpochMilli(end).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == today } == true }
                val message = ReminderCopy.encouragement(
                    name = prefs.studentName.trim(),
                    morning = morning,
                    dueToday = dueToday.size,
                    open = open.size,
                    dayOfYear = today.dayOfYear
                )
                post(
                    context,
                    channel = AppNotifications.DAILY,
                    notificationId = if (morning) MORNING_NOTIFICATION_ID else EVENING_NOTIFICATION_ID,
                    title = if (morning) "صباح الخير ☀️" else "مساء التقدم 🌙",
                    message = message,
                    avatarPath = prefs.avatarPath,
                    openTasks = true
                )
                NotificationDelivery.logSent(context, "daily", now, now)
            }
            is ReminderDecision.Defer -> {
                NotificationDelivery.logNotSent(context, "daily", now, decision, now)
                scheduleDeferred(
                    context,
                    if (morning) ACTION_MORNING else ACTION_EVENING,
                    if (morning) MORNING_REQUEST else EVENING_REQUEST,
                    decision.until
                )
            }
            is ReminderDecision.Drop -> {
                NotificationDelivery.logNotSent(context, "daily", now, decision, now)
            }
        }
    }

    private fun notificationsAllowed(context: Context): Boolean = AppNotifications.canPost(context)

    private fun notificationManager(context: Context) = AppNotifications.manager(context)

    private fun post(
        context: Context,
        channel: AlertChannel,
        notificationId: Int,
        title: String,
        message: String,
        avatarPath: String = "",
        openTasks: Boolean = false
    ) {
        val openApp = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_TASKS, openTasks)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = AppNotifications.builder(
            context = context,
            channel = channel,
            title = title,
            message = message,
            contentIntent = openApp,
            largeIcon = NotificationAvatar.decode(avatarPath)
        )
        notificationManager(context).notify(notificationId, builder.build())
    }
}

class DailyReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                StudyNotificationScheduler.dispatch(context.applicationContext, intent?.action)
            } finally {
                result.finish()
            }
        }
    }
}

class FocusBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED
            )
        ) return

        // One failing step must not cancel the rest: a single crash here used to leave the
        // phone with no scheduled reminders until the user opened the app again.
        val app = context.applicationContext
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                NotificationMaintenance.rescheduleAll(app)
                runCatching { ReminderRescheduleJob.schedule(app) }
                    .onFailure { Log.w(TAG, "reschedule job failed", it) }
                Log.i(TAG, "rescheduled after $action")
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        const val TAG = "FocusBoot"
    }
}
