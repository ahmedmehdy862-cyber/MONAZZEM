package com.focusstudy.poc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

internal const val ACTION_EXERCISE_REMINDER = "com.focusstudy.poc.EXERCISE_REMINDER"

/**
 * تذكير التمارين اليومي — جديد في هذا الفرع.
 *
 * قبل هذا الفرع لم يكن للتمارين أي تذكير: خطط التمرين (4 خطط: جيم/بيتي ×
 * ذكر/أنثى، 3 أيام أسبوعيًا) موجودة في «حياتي» لكن لا شيء يذكّر بها. هذا
 * المجدول يذكّر في أيام الخطة فقط (السبت والاثنين والأربعاء في كل الخطط
 * الأربع)، في وقت قابل للضبط (افتراضي 18:00)، وينقلك النقر إلى «حياتي»
 * لتسجيل التمرين. إن سُجّل أي تمرين في اليوم فلا يُرسل التذكير أصلًا،
 * وإن تجاهله المستخدم تبدأ سلسلة المتابعة «الزنان» من المساعد.
 *
 * المحتوى لا يسمّي تركيز اليوم (صدر/رجلين…) عمدًا: مكان التمرين
 * (جيم/بيتي) اختيار لحظي في شاشة الصحة غير محفوظ، فتسمية تركيز مكان
 * واحد ستكون خاطئة لنصف المستخدمين. التذكير يسمّي اليوم والخطة فقط —
 * صحيح دائمًا — حتى يُحفظ المكان (تحسين موثّق في docs/assistant-learning.md).
 *
 * يمر عبر بوابة السياسة المشتركة ([NotificationDelivery]) مثل الورد،
 * ويُجدول عبر مسار الصيانة الموحد ([NotificationMaintenance.rescheduleAll]).
 */
internal data class ExerciseReminderSettings(
    val enabled: Boolean = true,
    val hour: Int = 18,
    val minute: Int = 0
)

internal object ExerciseReminderPrefs {
    private const val FILE = "exercise_reminder_preferences"
    private const val ENABLED = "enabled"
    private const val HOUR = "hour"
    private const val MINUTE = "minute"

    fun load(context: Context): ExerciseReminderSettings {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return ExerciseReminderSettings(
            enabled = prefs.getBoolean(ENABLED, true),
            hour = prefs.getInt(HOUR, 18).coerceIn(0, 23),
            minute = prefs.getInt(MINUTE, 0).coerceIn(0, 59)
        )
    }

    fun save(context: Context, settings: ExerciseReminderSettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, settings.enabled)
            .putInt(HOUR, settings.hour.coerceIn(0, 23))
            .putInt(MINUTE, settings.minute.coerceIn(0, 59))
            .apply()
    }
}

internal object ExerciseReminderScheduler {
    const val EXTRA_OPEN_LIFE = "com.focusstudy.poc.OPEN_LIFE"

    private const val REQUEST = 6711
    private const val DEFERRED_REQUEST = 6712
    private const val NOTIFICATION = 6713

    /** أيام خطة التمرين في كل الخطط الأربع: السبت والاثنين والأربعاء. */
    val PLAN_DAYS: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)

    fun isPlanDay(day: DayOfWeek): Boolean = day in PLAN_DAYS

    /** أقرب وقوع قادم لوقت (ساعة، دقيقة) في يوم خطة، من الآن. */
    fun nextPlanOccurrence(hour: Int, minute: Int, now: Long, zone: ZoneId): Long {
        var cursor = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            .withHour(hour.coerceIn(0, 23))
            .withMinute(minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)
        repeat(8) {
            if (cursor.dayOfWeek in PLAN_DAYS &&
                cursor.atZone(zone).toInstant().toEpochMilli() > now
            ) {
                return cursor.atZone(zone).toInstant().toEpochMilli()
            }
            cursor = cursor.plusDays(1)
        }
        return cursor.atZone(zone).toInstant().toEpochMilli()
    }

    fun schedule(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val settings = ExerciseReminderPrefs.load(app)
        val pending = PendingIntent.getBroadcast(
            app, REQUEST,
            Intent(app, ExerciseReminderReceiver::class.java).setAction(ACTION_EXERCISE_REMINDER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (!settings.enabled) {
            manager.cancel(pending)
            return
        }
        val now = System.currentTimeMillis()
        val at = NotificationPolicy.adjustOutsidePrayerWindows(
            nextPlanOccurrence(settings.hour, settings.minute, now, AppTimeZone.zone(app)),
            PrayerWindows.todayPrayerMillis(app)
        )
        NotificationMaintenance.schedule(app, manager, at, pending, kind = "exercise")
    }

    fun notifyTest(context: Context) {
        notify(context, test = true)
    }

    fun notify(context: Context, test: Boolean = false, today: LocalDate? = null) {
        val app = context.applicationContext
        if (!AppNotifications.canPost(app)) return
        val now = System.currentTimeMillis()
        val zone = AppTimeZone.zone(app)
        val today = today ?: LocalDate.now(zone)
        val todayKey = today.toString()
        val db = StudyDb.get(app)
        if (!test) {
            if (!isPlanDay(today.dayOfWeek)) {
                schedule(app)
                return
            }
            if (runCatching { db.exerciseLogsSince(todayKey) }.getOrDefault(emptyList()).isNotEmpty()) {
                // تمرين اليوم مسجَّل أصلًا: لا تذكير. السلسلة اليومية تستمر.
                runCatching { db.logNotification("exercise", null, now, null, "skipped_logged") }
                schedule(app)
                return
            }
        }
        if (test) {
            post(app, today, test = true)
            return
        }
        when (val decision = NotificationDelivery.decide(app, ExerciseReminderPrefs.load(app).enabled, now)) {
            ReminderDecision.Send -> {
                post(app, today, test = false)
                NotificationDelivery.logSent(app, "exercise", now, now)
                AssistantFollowUpScheduler.scheduleAfterPost(
                    app, FollowUpKind.EXERCISE, slot = "daily", day = todayKey,
                    detail = exerciseDetail(today)
                )
                schedule(app)
            }
            is ReminderDecision.Defer -> {
                NotificationDelivery.logNotSent(app, "exercise", now, decision, now)
                scheduleDeferred(app, decision.until)
            }
            is ReminderDecision.Drop -> {
                NotificationDelivery.logNotSent(app, "exercise", now, decision, now)
                schedule(app)
            }
        }
    }

    private fun scheduleDeferred(context: Context, atMillis: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        NotificationMaintenance.schedule(
            context, manager, atMillis,
            PendingIntent.getBroadcast(
                context, DEFERRED_REQUEST,
                Intent(context, ExerciseReminderReceiver::class.java).setAction(ACTION_EXERCISE_REMINDER),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ),
            kind = "exercise"
        )
    }

    /** نص التذكير: اليوم وترتيبه في الخطة الأسبوعية (3 أيام). */
    fun exerciseDetail(today: LocalDate): String {
        val dayLabel = when (today.dayOfWeek) {
            DayOfWeek.SATURDAY -> "السبت"
            DayOfWeek.MONDAY -> "الاثنين"
            DayOfWeek.WEDNESDAY -> "الأربعاء"
            else -> ""
        }
        return "النهاردة $dayLabel — يوم تمرينك (3 أيام في الأسبوع: السبت والاثنين والأربعاء). افتح «حياتي» وشوف تمرين النهاردة وسجّله."
    }

    private fun post(context: Context, today: LocalDate, test: Boolean) {
        val openLife = PendingIntent.getActivity(
            context, NOTIFICATION,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_LIFE, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (test) "هكذا يظهر تذكير التمارين" else "وقت التمرين 💪"
        val body = exerciseDetail(today)
        val notification = AppNotifications.builder(
            context = context,
            channel = AppNotifications.DAILY,
            title = title,
            message = if (test) "$body (تجربة)" else body,
            contentIntent = openLife
        ).build()
        AppNotifications.manager(context).notify(NOTIFICATION, notification)
    }
}

class ExerciseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_EXERCISE_REMINDER) return
        ExerciseReminderScheduler.notify(context)
    }
}
