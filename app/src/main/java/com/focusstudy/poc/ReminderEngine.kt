package com.focusstudy.poc

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal const val ACTION_IGNORED = "ignored"

data class StoredReminder(
    val id: Long,
    val taskId: Long,
    val kind: String,
    val fireAt: Long
)

data class NotificationRecord(
    val kind: String,
    val scheduledAt: Long,
    val deliveredAt: Long?,
    val action: String
)

/**
 * Schedules and delivers task reminders through [NotificationPolicy].
 *
 * Everything it decides is explainable: a reminder is either sent, deferred to a concrete instant
 * with a reason, or dropped with a reason. The legacy every-two-hours nudge stays available behind
 * the "التنبيهات الكلاسيكية" switch.
 */
internal object ReminderEngine {
    const val ACTION_FIRE = "com.focusstudy.poc.REMINDER_FIRE"
    const val ACTION_DONE = "com.focusstudy.poc.REMINDER_DONE"
    const val ACTION_SNOOZE_HOUR = "com.focusstudy.poc.REMINDER_SNOOZE_HOUR"
    const val ACTION_SNOOZE_PRAYER = "com.focusstudy.poc.REMINDER_SNOOZE_PRAYER"
    const val ACTION_OPEN_START = "com.focusstudy.poc.REMINDER_OPEN_START"
    const val ACTION_DISMISSED = "com.focusstudy.poc.REMINDER_DISMISSED"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_KIND = "reminder_kind"

    private const val CHANNEL_SUMMARY = "session_summary"

    private const val MAX_ALARMS = 30
    private const val NOTIFICATION_BASE_ID = 7300
    private const val DEFER_PREFIX = "deferred|"
    private const val PERMISSION_RETRY_MS = 30 * 60 * 1000L

    fun channels(context: Context) {
        // Task and exam alerts use the new high-priority channels so they appear on screen.
        AppNotifications.ensure(context, AppNotifications.TASK)
        AppNotifications.ensure(context, AppNotifications.EXAM)
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SUMMARY, "ملخص الجلسة", NotificationManager.IMPORTANCE_LOW).apply {
                description = "ما وصلك أثناء جلسة التركيز، بعد انتهائها"
            }
        )
    }

    /** Rebuilds the whole schedule from the database; safe to call after any task change. */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        val db = StudyDb.get(appContext)
        val prefs = FocusPreferences.load(appContext)
        val now = System.currentTimeMillis()
        val lead = prefs.leadMinutes * 60_000L
        val active = db.tasks().filter { !it.isDone && it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED }

        for (task in active) {
            val schedule = buildList {
                task.dueAt?.let { due ->
                    if (due > now) add(ReminderKind.TASK_DUE.key to (due - lead).coerceAtLeast(now + 60_000))
                    add(ReminderKind.TASK_DUE.key to due)
                    add(ReminderKind.TASK_OVERDUE.key to (due + 24 * 60 * 60 * 1000L))
                }
                if (task.originalDueAt != null && task.dueAt != null && task.originalDueAt != task.dueAt) {
                    add(ReminderKind.TASK_OVERDUE.key to (now + 60_000))
                }
            }
            db.replaceReminders(task.id, schedule.filter { it.second > now }.sortedBy { it.second })
        }
        scheduleAlarms(appContext, db)
        registerExamReminders(appContext, now)
    }

    private fun registerExamReminders(context: Context, now: Long) {
        val db = StudyDb.get(context)
        db.subjects().forEach { subject ->
            val examAt = subject.nextExamAt ?: return@forEach
            val days = ((examAt - now) / DAY).toInt()
            val fireAt = when {
                days == 7 || days == 3 || days == 1 -> now + 60_000
                days > 7 -> examAt - 7 * DAY
                days > 3 -> examAt - 3 * DAY
                days > 1 -> examAt - DAY
                else -> null
            }
            if (fireAt != null && fireAt > now) {
                db.replaceReminders(-subject.id, listOf(ReminderKind.EXAM.key to fireAt))
            }
        }
    }

    private fun scheduleAlarms(context: Context, db: StudyDb) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        val upcoming = db.pendingReminders(MAX_ALARMS * 4).filter { it.fireAt > now }.take(MAX_ALARMS)
        for (reminder in upcoming) {
            val decoded = decodeKind(reminder.kind)
            scheduleFire(
                context,
                manager,
                StoredReminder(reminder.id, reminder.taskId, reminder.kind, reminder.fireAt),
                alarmRequestCode(reminder.id),
                decoded.sourceKind
            )
        }
    }

    private fun scheduleOne(context: Context, reminder: StoredReminder, request: Int) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        scheduleFire(context, manager, reminder, request, decodeKind(reminder.kind).sourceKind)
    }

    private fun scheduleFire(
        context: Context,
        manager: AlarmManager,
        reminder: StoredReminder,
        request: Int,
        sourceKind: String
    ) {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_REMINDER_ID, reminder.id)
            .putExtra(EXTRA_TASK_ID, reminder.taskId)
            .putExtra(EXTRA_KIND, reminder.kind)
        val pending = PendingIntent.getBroadcast(
            context,
            request,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationMaintenance.schedule(
            context = context,
            manager = manager,
            atMillis = reminder.fireAt,
            pending = pending,
            kind = sourceKind,
            taskId = reminder.taskId.takeIf { it >= 0 }
        )
    }

    internal fun encodeDeferred(sourceKind: String, reason: String): String =
        "$DEFER_PREFIX$reason|$sourceKind"

    internal data class DecodedReminder(val sourceKind: String, val deferred: Boolean, val deferReason: String = "")

    internal fun decodeKind(encoded: String): DecodedReminder {
        if (!encoded.startsWith(DEFER_PREFIX)) return DecodedReminder(encoded, false)
        val parts = encoded.split("|", limit = 3)
        return DecodedReminder(
            sourceKind = parts.getOrElse(2) { parts.getOrNull(1).orEmpty() },
            deferred = true,
            deferReason = parts.getOrElse(1) { "" }
        )
    }

    fun dispatch(context: Context, reminderId: Long, taskId: Long, kind: String) {
        val appContext = context.applicationContext
        val db = StudyDb.get(appContext)
        val prefs = FocusPreferences.load(appContext)
        channels(appContext)

        // Alarms left behind by an older schedule must not fire a second time.
        if (reminderId > 0 && !db.reminderIsPending(reminderId)) return

        val decoded = decodeKind(kind)
        val effectiveKind = decoded.sourceKind

        if (taskId >= 0) {
            val task = db.task(taskId)
            if (task == null || task.isDone || task.status == TaskStatus.DONE || task.status == TaskStatus.CANCELLED) {
                db.markReminderDone(reminderId, "skipped")
                return
            }
        }

        val now = System.currentTimeMillis()
        val zone = AppTimeZone.zone(appContext)
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val session = db.activeSession()?.takeIf { it.endsAt > now }
        val subject = if (taskId >= 0) db.task(taskId)?.subjectId?.let { id -> db.subjects().firstOrNull { it.id == id } } else null
        val examAt = if (taskId < 0) db.subjects().firstOrNull { it.id == -taskId }?.nextExamAt else subject?.nextExamAt
        val dueAt = if (taskId >= 0) db.task(taskId)?.dueAt else null

        val input = PolicyInput(
            now = now,
            kind = ReminderKind.entries.firstOrNull { it.key == effectiveKind } ?: ReminderKind.TASK_DUE,
            critical = NotificationPolicy.isCritical(dueAt, now) ||
                NotificationPolicy.isExamCritical(examAt, now) ||
                effectiveKind == ReminderKind.EXAM.key,
            // Task/exam reminders have their own switch; the general-messages switch only controls
            // the companion's morning/evening messages.
            enabled = prefs.smartRemindersEnabled,
            density = prefs.notificationDensity,
            deliveredToday = db.deliveredCountSince(startOfDay),
            lastNonCriticalAt = db.lastNonCriticalDelivery(),
            ignoredStreak = db.ignoredStreak(),
            sessionEndsAt = session?.endsAt,
            prayerWindow = if (PrayerWindows.respectInReminders(appContext)) PrayerWindows.activeWindow(appContext, now) else null,
            quietStart = if (prefs.quietHoursEnabled) prefs.quietStartHour * 60 else null,
            quietEnd = if (prefs.quietHoursEnabled) prefs.quietEndHour * 60 else null
        )

        val decision = NotificationPolicy.decide(input, zone)

        when (decision) {
            ReminderDecision.Send -> {
                // If posting is blocked (runtime permission or system-disabled), keep a persisted retry
                // instead of consuming the reminder and losing it.
                if (!AppNotifications.canPost(appContext)) {
                    db.markReminderDone(reminderId, "permission_blocked")
                    db.logNotification(effectiveKind, taskId.takeIf { it != 0L }, now, null, NotificationMaintenance.PERMISSION_DENIED)
                    scheduleDeferred(appContext, db, taskId, effectiveKind, now + PERMISSION_RETRY_MS, "permission_retry")
                    return
                }
                val delivered = deliver(appContext, db, prefs, taskId, effectiveKind, now)
                db.markReminderDone(reminderId, if (delivered) "fired" else "failed_delivery")
                if (!delivered) {
                    // A transient failure (e.g. notifier exception) must not consume the reminder.
                    scheduleDeferred(appContext, db, taskId, effectiveKind, now + PERMISSION_RETRY_MS, "retry")
                }
            }
            is ReminderDecision.Defer -> {
                db.markReminderDone(reminderId, "deferred")
                scheduleDeferred(appContext, db, taskId, effectiveKind, decision.until, decision.reason.key())
                db.logNotification(effectiveKind, taskId.takeIf { it != 0L }, now, null, "deferred:${decision.reason.key()}")
            }
            is ReminderDecision.Drop -> {
                db.markReminderDone(reminderId, "dropped")
                db.logNotification(effectiveKind, taskId.takeIf { it != 0L }, now, null, "dropped:${decision.reason.key()}")
            }
        }
    }

    private fun scheduleDeferred(
        context: Context,
        db: StudyDb,
        taskId: Long,
        sourceKind: String,
        until: Long,
        reason: String
    ) {
        db.clearDeferrals(taskId, sourceKind)
        val encoded = encodeDeferred(sourceKind, reason)
        val id = db.insertReminder(taskId, encoded, until)
        if (id > 0) {
            scheduleOne(context, StoredReminder(id, taskId, encoded, until), alarmRequestCode(id))
        }
    }

    private fun deliver(
        context: Context,
        db: StudyDb,
        prefs: FocusPreferences,
        taskId: Long,
        kind: String,
        now: Long
    ): Boolean {
        if (!AppNotifications.canPost(context)) return false
        val task = if (taskId >= 0) db.task(taskId) else null
        val examSubject = if (taskId < 0) db.subjects().firstOrNull { it.id == -taskId } else null
        val persona = Persona.from(prefs.companionStyle)
        val tone = ToneResolver.resolve(ToneState(userTone = prefs.tonePreference))
        val body = when {
            examSubject != null -> {
                val days = (((examSubject.nextExamAt ?: now) - now) / DAY).toInt().coerceAtLeast(0)
                Voice.say(
                    VoiceScene.EXAM_SOON, persona, tone,
                    mapOf("subject" to examSubject.name, "days" to daysText(days))
                )
            }
            task == null -> return false
            task.dueAt != null && task.dueAt < now - DAY -> Voice.say(
                VoiceScene.TASK_OVERDUE, persona, tone, mapOf("task" to task.title)
            )
            task.dueAt != null && task.dueAt < now -> Voice.say(
                VoiceScene.DEFERRED, persona, tone, mapOf("task" to task.title, "count" to deferCount(task).toString())
            )
            else -> Voice.say(
                VoiceScene.TASK_DUE, persona, tone,
                mapOf("task" to task.title, "minutes" to task.remainingMinutes.coerceAtLeast(5).toString())
            )
        }
        val title = examSubject?.let { "امتحان ${it.name}" }
            ?: task?.let { "مهمة: ${it.title}" }
            ?: "مُنظِّم"
        val alert = if (examSubject != null) AppNotifications.EXAM else AppNotifications.TASK
        val notificationId = NOTIFICATION_BASE_ID + (if (taskId >= 0) taskId else -taskId).toInt()
        val builder = AppNotifications.builder(
            context = context,
            channel = alert,
            title = title,
            message = body,
            contentIntent = openIntent(context, taskId),
            deleteIntent = dismissIntent(context, taskId, kind)
        )

        if (taskId >= 0) {
            builder.addAction(0, "تمّ", actionIntent(context, ACTION_DONE, taskId, 1))
            builder.addAction(0, "أجّل ساعة", actionIntent(context, ACTION_SNOOZE_HOUR, taskId, 2))
            builder.addAction(0, "ابدأ تركيز", actionIntent(context, ACTION_OPEN_START, taskId, 3))
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return runCatching {
            manager.notify(notificationId, builder.build())
            db.logNotification(kind, taskId.takeIf { it != 0L }, now, now, "")
        }.isSuccess
    }

    private fun deferCount(task: StudyTask): Int = when {
        task.originalDueAt == null || task.dueAt == null -> 0
        task.originalDueAt == task.dueAt -> 0
        else -> 1
    }

    fun handleAction(context: Context, action: String?, taskId: Long) {
        val appContext = context.applicationContext
        val db = StudyDb.get(appContext)
        val now = System.currentTimeMillis()
        when (action) {
            ACTION_DONE -> {
                db.setTaskStatus(taskId, TaskStatus.DONE)
                db.logNotification("action", taskId, now, now, "done")
            }
            ACTION_SNOOZE_HOUR -> {
                val task = db.task(taskId) ?: return
                val base = maxOf(task.dueAt ?: now, now)
                db.rescheduleTask(taskId, base + 60 * 60 * 1000L)
                db.logNotification("action", taskId, now, now, "snoozed")
            }
            ACTION_SNOOZE_PRAYER -> {
                val task = db.task(taskId) ?: return
                val base = maxOf(task.dueAt ?: now, now)
                db.rescheduleTask(taskId, PrayerWindows.nextFreeStart(appContext, base))
                db.logNotification("action", taskId, now, now, "snoozed_prayer")
            }
        }
        SessionEngine.notifyTasksChanged()
        refresh(appContext)
    }

    private fun openIntent(context: Context, taskId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getActivity(
            context,
            requestCode(taskId + 900),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionIntent(context: Context, action: String, taskId: Long, slot: Int): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_TASK_ID, taskId)
        return PendingIntent.getBroadcast(
            context,
            requestCode(taskId * 10 + slot),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** A swipe is an explicit ignore, not a blank delivery row. */
    private fun dismissIntent(context: Context, taskId: Long, kind: String): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(ACTION_DISMISSED)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_KIND, kind)
        return PendingIntent.getBroadcast(
            context,
            requestCode(taskId + 4_000),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Unique per stored reminder, not per task/kind. A task can have a lead reminder, a due reminder
     * and an overdue reminder at the same time; sharing a request code used to silently replace the
     * earlier alarms.
     */
    internal fun alarmRequestCode(reminderId: Long): Int =
        200_000 + Math.floorMod(reminderId, 500_000L).toInt()

    internal fun deferredAlarmRequestCode(taskId: Long, fireAt: Long): Int =
        700_000 + Math.floorMod(taskId * 131 + fireAt, 200_000L).toInt()

    private fun requestCode(id: Long): Int = Math.floorMod(id, 100_000L).toInt() + 21_000

    private fun daysText(days: Int): String = when (days) {
        0 -> "اليوم"
        1 -> "يوم واحد"
        2 -> "يومين"
        in 3..10 -> "$days أيام"
        else -> "$days يومًا"
    }

    private fun DeferReason.key(): String = name.lowercase()
    private fun DropReason.key(): String = name.lowercase()

    private const val DAY = 24 * 60 * 60 * 1000L
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ReminderEngine.ACTION_FIRE) return
        ReminderEngine.dispatch(
            context,
            reminderId = intent.getLongExtra(ReminderEngine.EXTRA_REMINDER_ID, -1),
            taskId = intent.getLongExtra(ReminderEngine.EXTRA_TASK_ID, -1),
            kind = intent.getStringExtra(ReminderEngine.EXTRA_KIND).orEmpty()
        )
    }
}

class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val taskId = intent?.getLongExtra(ReminderEngine.EXTRA_TASK_ID, -1) ?: return
        val pending = goAsync()
        val action = intent.action
        try {
            if (action == ReminderEngine.ACTION_DISMISSED) {
                val kind = intent.getStringExtra(ReminderEngine.EXTRA_KIND).orEmpty()
                StudyDb.get(context.applicationContext).markIgnored(
                    kind = kind,
                    taskId = taskId.takeIf { it != 0L }
                )
                return
            }
            if (taskId < 0) return
            if (action == ReminderEngine.ACTION_OPEN_START) {
                val open = Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ReminderEngine.EXTRA_TASK_ID, taskId)
                context.startActivity(open)
                return
            }
            ReminderEngine.handleAction(context, action, taskId)
        } finally {
            pending.finish()
        }
    }
}

/** Mirrors the session end instant so a finished session always releases delayed reminders. */
internal fun sessionEndsAt(context: Context): Long? =
    StudyDb.get(context).activeSession()?.endsAt

internal fun examCountdownDays(
    examAt: Long?,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault()
): Int? = examAt?.let {
    Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay()
        .minus(LocalDate.now(zone).toEpochDay()).toInt()
}
