package com.focusstudy.poc

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

/**
 * تنفيذ سلسلة المتابعة «الزنان»: من الإشعار الأول حتى الإنجاز أو الإلغاء.
 *
 * الدورة: مرسل التذكير (ورد/تمرين) ينشر الإشعار الأول ثم يستدعي
 * [scheduleAfterPost] فيُنشأ صف `assistant_followups` بمحاولة واحدة
 * ويُجدول المنبّه التالي. عند إطلاق المنبّه يفحص [fire] شرط التوقف أولًا
 * (الورد اتسجّل؟ التمرين اتعمل؟) فإن تحقق انتهت السلسلة بصمت، وإلا مرّت
 * المحاولة عبر بوابة السياسة [NotificationDelivery] (هدوء/جلسة/صلاة/
 * ميزانية/فاصل) ثم نُشرت بصياغة متصاعدة أو أُجّلت أو أُسقطت.
 *
 * إجراءات الإشعار (بلا فتح إجباري للتطبيق):
 * - «تم ✅» للورد: يسجّل الورد فورًا من الإشعار نفسه.
 * - «افتح التمارين»: يفتح «حياتي» على قسم الصحة لتسجيل التمرين.
 * - «أجّل ساعة»: يؤجّل المحاولة التالية ساعة.
 * - «مش النهاردة»: يلغي السلسلة لهذا اليوم نهائيًا.
 * - السحب: يُسجَّل تجاهلًا صريحًا فيُوقف السياسة السلسلة بعد 3 سحبات.
 *
 * القناة هي [AppNotifications.DAILY] نفسها (IMPORTANCE_DEFAULT +
 * VISIBILITY_PUBLIC) فتظهر كل محاولة على شاشة القفل بمحتواها الكامل.
 */
internal object AssistantFollowUpScheduler {

    const val ACTION_FOLLOWUP_FIRE = "com.focusstudy.poc.ASSISTANT_FOLLOWUP_FIRE"
    const val ACTION_FOLLOWUP_DONE = "com.focusstudy.poc.ASSISTANT_FOLLOWUP_DONE"
    const val ACTION_FOLLOWUP_OPEN = "com.focusstudy.poc.ASSISTANT_FOLLOWUP_OPEN"
    const val ACTION_FOLLOWUP_SNOOZE = "com.focusstudy.poc.ASSISTANT_FOLLOWUP_SNOOZE"
    const val ACTION_FOLLOWUP_DISMISS = "com.focusstudy.poc.ASSISTANT_FOLLOWUP_DISMISS"
    const val ACTION_FOLLOWUP_IGNORED = "com.focusstudy.poc.ASSISTANT_FOLLOWUP_IGNORED"
    const val EXTRA_KIND = "followup_kind"
    const val EXTRA_SLOT = "followup_slot"
    const val EXTRA_DAY = "followup_day"

    private const val REQUEST_WIRD_MORNING = 6701
    private const val REQUEST_WIRD_EVENING = 6702
    private const val REQUEST_EXERCISE = 6703
    private const val NOTIFICATION_WIRD_MORNING = 6705
    private const val NOTIFICATION_WIRD_EVENING = 6706
    private const val NOTIFICATION_EXERCISE = 6707

    private fun requestCode(kind: FollowUpKind): Int = when (kind) {
        FollowUpKind.WIRD_MORNING -> REQUEST_WIRD_MORNING
        FollowUpKind.WIRD_EVENING -> REQUEST_WIRD_EVENING
        FollowUpKind.EXERCISE -> REQUEST_EXERCISE
    }

    private fun notificationId(kind: FollowUpKind): Int = when (kind) {
        FollowUpKind.WIRD_MORNING -> NOTIFICATION_WIRD_MORNING
        FollowUpKind.WIRD_EVENING -> NOTIFICATION_WIRD_EVENING
        FollowUpKind.EXERCISE -> NOTIFICATION_EXERCISE
    }

    /** تُستدعى بعد نشر الإشعار الأول: تنشئ السلسلة وتجدول المحاولة التالية. */
    fun scheduleAfterPost(context: Context, kind: FollowUpKind, slot: String, day: String, detail: String) {
        val app = context.applicationContext
        val level = AssistantNagPrefs.load(app).level
        if (level == AssistantNagLevel.QUIET) return
        val now = System.currentTimeMillis()
        val db = StudyDb.get(app)
        runCatching {
            db.upsertAssistantFollowUp(
                kind = kind.key, slot = slot, day = day,
                attempts = 1, maxAttempts = level.maxAttempts,
                nextAt = 0L, state = "active", snoozeUntil = null,
                detail = detail, now = now
            )
        }
        scheduleNext(app, kind, slot, day, now)
    }

    /** تجدول المحاولة التالية حسب مستوى الإلحاح، أو تنهي السلسلة إن اكتملت. */
    fun scheduleNext(context: Context, kind: FollowUpKind, slot: String, day: String, now: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val db = StudyDb.get(app)
        val row = runCatching { db.assistantFollowUp(kind.key, slot, day) }.getOrNull() ?: return
        if (row.state != "active") return
        val level = AssistantNagPrefs.load(app).level
        val delay = AssistantFollowUpPolicy.nextDelayMillis(level, row.attempts)
        if (delay == null || !AssistantFollowUpPolicy.shouldContinue(level, row.attempts)) {
            runCatching { db.updateAssistantFollowUpState(row.id, "expired", now) }
            runCatching { db.logNotification("followup", null, now, null, "expired:${kind.key}") }
            return
        }
        val nextAt = now + delay
        runCatching { db.updateAssistantFollowUpNextAt(row.id, nextAt, now) }
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        NotificationMaintenance.schedule(
            app, manager, nextAt,
            PendingIntent.getBroadcast(
                app, requestCode(kind),
                Intent(app, AssistantFollowUpReceiver::class.java)
                    .setAction(ACTION_FOLLOWUP_FIRE)
                    .putExtra(EXTRA_KIND, kind.key)
                    .putExtra(EXTRA_SLOT, slot)
                    .putExtra(EXTRA_DAY, day),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ),
            kind = "followup"
        )
    }

    /** إطلاق محاولة: فحص التوقف، ثم بوابة السياسة، ثم النشر أو التأجيل أو الإسقاط. */
    fun fire(context: Context, kind: FollowUpKind, slot: String, day: String) {
        val app = context.applicationContext
        if (!AppNotifications.canPost(app)) return
        val now = System.currentTimeMillis()
        val db = StudyDb.get(app)
        val row = runCatching { db.assistantFollowUp(kind.key, slot, day) }.getOrNull()
        if (row == null || row.state != "active") return
        if (row.snoozeUntil != null && row.snoozeUntil > now) {
            // مؤجَّل بطلب المستخدم: أعد الجدولة لوقت التأجيل نفسه.
            val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            NotificationMaintenance.schedule(
                app, manager, row.snoozeUntil,
                PendingIntent.getBroadcast(
                    app, requestCode(kind),
                    Intent(app, AssistantFollowUpReceiver::class.java)
                        .setAction(ACTION_FOLLOWUP_FIRE)
                        .putExtra(EXTRA_KIND, kind.key)
                        .putExtra(EXTRA_SLOT, slot)
                        .putExtra(EXTRA_DAY, day),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ),
                kind = "followup"
            )
            return
        }
        if (isDone(app, kind, day)) {
            runCatching { db.updateAssistantFollowUpState(row.id, "done", now) }
            return
        }
        val attempt = row.attempts + 1
        when (val decision = NotificationDelivery.decide(app, true, now)) {
            ReminderDecision.Send -> {
                post(app, kind, slot, day, attempt, row.detail)
                NotificationDelivery.logSent(app, "followup", now, now)
                runCatching { db.updateAssistantFollowUpAttempts(row.id, attempt, now) }
                scheduleNext(app, kind, slot, day, now)
            }
            is ReminderDecision.Defer -> {
                NotificationDelivery.logNotSent(app, "followup", now, decision, now)
                val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                NotificationMaintenance.schedule(
                    app, manager, decision.until,
                    PendingIntent.getBroadcast(
                        app, requestCode(kind),
                        Intent(app, AssistantFollowUpReceiver::class.java)
                            .setAction(ACTION_FOLLOWUP_FIRE)
                            .putExtra(EXTRA_KIND, kind.key)
                            .putExtra(EXTRA_SLOT, slot)
                            .putExtra(EXTRA_DAY, day),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    ),
                    kind = "followup"
                )
            }
            is ReminderDecision.Drop -> {
                NotificationDelivery.logNotSent(app, "followup", now, decision, now)
                runCatching { db.updateAssistantFollowUpState(row.id, "expired", now) }
            }
        }
    }

    /** هل أُنجز العنصر المتابَع؟ ورد مسجَّل أو تمرين مسجَّل في اليوم نفسه. */
    fun isDone(context: Context, kind: FollowUpKind, day: String): Boolean {
        val db = StudyDb.get(context.applicationContext)
        return when (kind) {
            FollowUpKind.WIRD_MORNING, FollowUpKind.WIRD_EVENING ->
                runCatching { db.wirdLoggedToday(day) }.getOrDefault(false)
            FollowUpKind.EXERCISE ->
                runCatching { db.exerciseLogsSince(day) }.getOrDefault(emptyList()).isNotEmpty()
        }
    }

    private fun post(context: Context, kind: FollowUpKind, slot: String, day: String, attempt: Int, detail: String) {
        val app = context.applicationContext
        val title = AssistantFollowUpPolicy.titleFor(kind, attempt)
        val body = AssistantFollowUpPolicy.bodyFor(kind, attempt, detail)
        val contentIntent = PendingIntent.getActivity(
            app, notificationId(kind),
            Intent(app, MainActivity::class.java).apply {
                when (kind) {
                    FollowUpKind.WIRD_MORNING, FollowUpKind.WIRD_EVENING ->
                        putExtra(WirdReminderScheduler.EXTRA_OPEN_QURAN, true)
                    FollowUpKind.EXERCISE ->
                        putExtra(ExerciseReminderScheduler.EXTRA_OPEN_LIFE, true)
                }
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = AppNotifications.builder(
            context = app,
            channel = AppNotifications.DAILY,
            title = title,
            message = body,
            contentIntent = contentIntent,
            deleteIntent = actionIntent(app, ACTION_FOLLOWUP_IGNORED, kind, slot, day, 9)
        )
        when (kind) {
            FollowUpKind.WIRD_MORNING, FollowUpKind.WIRD_EVENING -> {
                builder.addAction(0, "تم ✅", actionIntent(app, ACTION_FOLLOWUP_DONE, kind, slot, day, 1))
                builder.addAction(0, "أجّل ساعة", actionIntent(app, ACTION_FOLLOWUP_SNOOZE, kind, slot, day, 2))
                builder.addAction(0, "مش النهاردة", actionIntent(app, ACTION_FOLLOWUP_DISMISS, kind, slot, day, 3))
            }
            FollowUpKind.EXERCISE -> {
                builder.addAction(0, "افتح التمارين", actionIntent(app, ACTION_FOLLOWUP_OPEN, kind, slot, day, 1))
                builder.addAction(0, "أجّل ساعة", actionIntent(app, ACTION_FOLLOWUP_SNOOZE, kind, slot, day, 2))
                builder.addAction(0, "مش النهاردة", actionIntent(app, ACTION_FOLLOWUP_DISMISS, kind, slot, day, 3))
            }
        }
        AppNotifications.manager(app).notify(notificationId(kind), builder.build())
    }

    private fun actionIntent(
        context: Context,
        action: String,
        kind: FollowUpKind,
        slot: String,
        day: String,
        code: Int
    ): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode(kind) * 10 + code,
        Intent(context, AssistantFollowUpReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_KIND, kind.key)
            .putExtra(EXTRA_SLOT, slot)
            .putExtra(EXTRA_DAY, day),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** إجراءات المستخدم على إشعار المتابعة. */
    fun handleAction(context: Context, action: String?, kind: FollowUpKind, slot: String, day: String) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val db = StudyDb.get(app)
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (action) {
            ACTION_FOLLOWUP_DONE -> {
                // للورد: تسجيل فوري من الإشعار نفسه. للتمرين لا يوجد «تم» مباشر
                // (التمرين عدة تمارين بمكان غير محفوظ) فيُفتح «حياتي» بدلًا منه.
                if (kind == FollowUpKind.WIRD_MORNING || kind == FollowUpKind.WIRD_EVENING) {
                    val settings = runCatching { db.wirdSettings() }.getOrDefault(WirdSettings())
                    runCatching { db.logWird(day, settings.pagesPerDay, settings.surah) }
                }
                runCatching {
                    db.assistantFollowUp(kind.key, slot, day)?.let {
                        db.updateAssistantFollowUpState(it.id, "done", now)
                    }
                }
                runCatching { db.logNotification("followup", null, now, now, "done:${kind.key}") }
                manager.cancel(notificationId(kind))
            }
            ACTION_FOLLOWUP_OPEN -> {
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .putExtra(ExerciseReminderScheduler.EXTRA_OPEN_LIFE, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                manager.cancel(notificationId(kind))
            }
            ACTION_FOLLOWUP_SNOOZE -> {
                runCatching {
                    db.assistantFollowUp(kind.key, slot, day)?.let { row ->
                        db.updateAssistantFollowUpSnooze(row.id, now + 60 * 60 * 1000L, now)
                    }
                }
                runCatching { db.logNotification("followup", null, now, null, "snoozed:${kind.key}") }
                manager.cancel(notificationId(kind))
                // منبّه واحد فقط لوقت التأجيل: لا نستدعي scheduleNext هنا حتى
                // لا يُجدول الفاصل المعتاد فوق التأجيل. عند إطلاقه يفحص fire()
                // أن التأجيل انتهى ثم يكمل السلسلة بفواصلها.
                val row = runCatching { db.assistantFollowUp(kind.key, slot, day) }.getOrNull()
                row?.snoozeUntil?.let { until ->
                    val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                    NotificationMaintenance.schedule(
                        app, alarmManager, until,
                        PendingIntent.getBroadcast(
                            app, requestCode(kind),
                            Intent(app, AssistantFollowUpReceiver::class.java)
                                .setAction(ACTION_FOLLOWUP_FIRE)
                                .putExtra(EXTRA_KIND, kind.key)
                                .putExtra(EXTRA_SLOT, slot)
                                .putExtra(EXTRA_DAY, day),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        ),
                        kind = "followup"
                    )
                }
            }
            ACTION_FOLLOWUP_DISMISS -> {
                runCatching {
                    db.assistantFollowUp(kind.key, slot, day)?.let {
                        db.updateAssistantFollowUpState(it.id, "dismissed", now)
                    }
                }
                runCatching { db.logNotification("followup", null, now, null, "dismissed:${kind.key}") }
                manager.cancel(notificationId(kind))
            }
            ACTION_FOLLOWUP_IGNORED -> {
                // سحب صريح: يُسجَّل تجاهلًا على آخر صف مسلَّم من نوع المتابعة،
                // فيحسبه ignoredStreak() وتوقف السياسة السلسلة بعد 3 سحبات.
                runCatching { db.markIgnored("followup", null) }
            }
        }
    }

    /** يعيد تسليح منبّهات السلاسل النشطة بعد الإقلاع أو تحديث التطبيق. */
    fun rescheduleActive(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val db = StudyDb.get(app)
        val active = runCatching { db.assistantFollowUpsActive() }.getOrDefault(emptyList())
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        active.forEach { row ->
            val kind = FollowUpKind.from(row.kind) ?: return@forEach
            if (row.state != "active") return@forEach
            if (isDone(app, kind, row.day)) {
                runCatching { db.updateAssistantFollowUpState(row.id, "done", now) }
                return@forEach
            }
            val at = maxOf(row.nextAt, now + 60_000L)
            NotificationMaintenance.schedule(
                app, manager, at,
                PendingIntent.getBroadcast(
                    app, requestCode(kind),
                    Intent(app, AssistantFollowUpReceiver::class.java)
                        .setAction(ACTION_FOLLOWUP_FIRE)
                        .putExtra(EXTRA_KIND, kind.key)
                        .putExtra(EXTRA_SLOT, row.slot)
                        .putExtra(EXTRA_DAY, row.day),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ),
                kind = "followup"
            )
        }
    }
}

class AssistantFollowUpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val kind = FollowUpKind.from(intent.getStringExtra(AssistantFollowUpScheduler.EXTRA_KIND)) ?: return
        val slot = intent.getStringExtra(AssistantFollowUpScheduler.EXTRA_SLOT).orEmpty()
        val day = intent.getStringExtra(AssistantFollowUpScheduler.EXTRA_DAY)
            ?: LocalDate.now(AppTimeZone.zone(context)).toString()
        when (action) {
            AssistantFollowUpScheduler.ACTION_FOLLOWUP_FIRE ->
                AssistantFollowUpScheduler.fire(context, kind, slot, day)
            else -> AssistantFollowUpScheduler.handleAction(context, action, kind, slot, day)
        }
    }
}
