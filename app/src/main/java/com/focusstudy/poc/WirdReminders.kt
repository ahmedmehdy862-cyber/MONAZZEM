package com.focusstudy.poc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

internal const val ACTION_WIRD_REMINDER = "com.focusstudy.poc.WIRD_REMINDER"
internal const val ACTION_SKILL_REMINDER = "com.focusstudy.poc.SKILL_REMINDER"

/** Next wall-clock occurrence of an (hour, minute) pair in [zone], today or tomorrow. */
private fun nextDailyOccurrence(hour: Int, minute: Int, now: Long, zone: ZoneId): Long {
    val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
        .withHour(hour.coerceIn(0, 23))
        .withMinute(minute.coerceIn(0, 59))
        .withSecond(0)
        .withNano(0)
    val next = if (local.atZone(zone).toInstant().toEpochMilli() > now) local else local.plusDays(1)
    return next.atZone(zone).toInstant().toEpochMilli()
}

/**
 * تذكير الورد اليومي.
 *
 * الورد نفسه موجود من قبل (صفحتان يوميًا من سورة البقرة بداية من صفحة 2، ويُحسب من مجموع
 * الصفحات المسجلة)، لكن لم يكن له أي تذكير: بحث شامل عن ورد+إشعار/منبّه/جدولة أعاد صفر نتائج.
 * هذا التذكير جديد كليًا: وقتان قابلان للضبط (افتراضي 10:00 و22:00)، والنقر عليه يفتح المصحف
 * على صفحة ورد اليوم مباشرة. إن سُجّل ورد اليوم من قبل فلا يُرسل التذكير أصلًا.
 *
 * مثل الأذكار والرسائل اليومية، يمر عبر بوابة السياسة المشتركة ([NotificationDelivery]):
 * ساعات الهدوء والميزانية اليومية والفاصل 45 دقيقة وجلسة التركيز ونافذة الصلاة، ويُجدول عبر
 * مسار الصيانة الموحد ([NotificationMaintenance.rescheduleAll]) فيعيد الإقلاع وتغيّر الوقت
 * جدولته تلقائيًا. أوقاته تُدفع خارج نافذة ±15 دقيقة حول كل أذان مثل غيره من التنبيهات.
 */
internal data class WirdReminderSettings(
    val enabled: Boolean = true,
    val morningHour: Int = 10,
    val morningMinute: Int = 0,
    val eveningHour: Int = 22,
    val eveningMinute: Int = 0
)

internal object WirdPrefs {
    private const val FILE = "wird_reminder_preferences"
    private const val ENABLED = "enabled"
    private const val MORNING_HOUR = "morning_hour"
    private const val MORNING_MINUTE = "morning_minute"
    private const val EVENING_HOUR = "evening_hour"
    private const val EVENING_MINUTE = "evening_minute"

    fun load(context: Context): WirdReminderSettings {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return WirdReminderSettings(
            enabled = prefs.getBoolean(ENABLED, true),
            morningHour = prefs.getInt(MORNING_HOUR, 10).coerceIn(0, 23),
            morningMinute = prefs.getInt(MORNING_MINUTE, 0).coerceIn(0, 59),
            eveningHour = prefs.getInt(EVENING_HOUR, 22).coerceIn(0, 23),
            eveningMinute = prefs.getInt(EVENING_MINUTE, 0).coerceIn(0, 59)
        )
    }

    fun save(context: Context, settings: WirdReminderSettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, settings.enabled)
            .putInt(MORNING_HOUR, settings.morningHour.coerceIn(0, 23))
            .putInt(MORNING_MINUTE, settings.morningMinute.coerceIn(0, 59))
            .putInt(EVENING_HOUR, settings.eveningHour.coerceIn(0, 23))
            .putInt(EVENING_MINUTE, settings.eveningMinute.coerceIn(0, 59))
            .apply()
    }
}

internal object WirdReminderScheduler {
    const val EXTRA_OPEN_QURAN = "com.focusstudy.poc.OPEN_QURAN"
    const val EXTRA_SLOT = "wird_slot"
    const val SLOT_MORNING = "morning"
    const val SLOT_EVENING = "evening"

    private const val MORNING_REQUEST = 6601
    private const val EVENING_REQUEST = 6602
    private const val MORNING_DEFERRED_REQUEST = 6603
    private const val EVENING_DEFERRED_REQUEST = 6604
    private const val MORNING_NOTIFICATION = 6605
    private const val EVENING_NOTIFICATION = 6606

    fun schedule(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val settings = WirdPrefs.load(app)
        cancel(app, manager, MORNING_REQUEST)
        cancel(app, manager, EVENING_REQUEST)
        if (!settings.enabled) return
        scheduleSlot(app, manager, SLOT_MORNING, MORNING_REQUEST, settings.morningHour, settings.morningMinute)
        scheduleSlot(app, manager, SLOT_EVENING, EVENING_REQUEST, settings.eveningHour, settings.eveningMinute)
    }

    private fun cancel(context: Context, manager: AlarmManager, request: Int) {
        manager.cancel(
            PendingIntent.getBroadcast(
                context, request,
                Intent(context, WirdReminderReceiver::class.java).setAction(ACTION_WIRD_REMINDER),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    private fun scheduleSlot(
        context: Context,
        manager: AlarmManager,
        slot: String,
        request: Int,
        hour: Int,
        minute: Int
    ) {
        val now = System.currentTimeMillis()
        val zone = AppTimeZone.zone(context)
        val at = NotificationPolicy.adjustOutsidePrayerWindows(
            nextDailyOccurrence(hour, minute, now, zone),
            PrayerWindows.todayPrayerMillis(context)
        )
        val intent = Intent(context, WirdReminderReceiver::class.java)
            .setAction(ACTION_WIRD_REMINDER)
            .putExtra(EXTRA_SLOT, slot)
        NotificationMaintenance.schedule(
            context, manager, at,
            PendingIntent.getBroadcast(
                context, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ),
            kind = "wird"
        )
    }

    fun notifyTest(context: Context) {
        notify(context, SLOT_MORNING, test = true)
    }

    fun notify(context: Context, slot: String, test: Boolean = false) {
        val app = context.applicationContext
        if (!AppNotifications.canPost(app)) return
        val now = System.currentTimeMillis()
        val zone = AppTimeZone.zone(app)
        val todayKey = LocalDate.now(zone).toString()
        val db = StudyDb.get(app)
        if (!test && runCatching { db.wirdLoggedToday(todayKey) }.getOrDefault(false)) {
            // The wird is already done today: nothing to remind about. Keep the chain alive.
            runCatching {
                db.logNotification("wird", null, now, null, "skipped_logged")
            }
            schedule(app)
            return
        }
        val settings = runCatching { db.wirdSettings() }.getOrDefault(WirdSettings())
        val totalPages = runCatching { db.wirdTotalPages() }.getOrDefault(0)
        val firstPage = settings.startPage + totalPages
        val lastPage = firstPage + settings.pagesPerDay - 1
        if (test) {
            post(app, slot, settings, firstPage, lastPage, test = true)
            return
        }
        when (val decision = NotificationDelivery.decide(app, WirdPrefs.load(app).enabled, now)) {
            ReminderDecision.Send -> {
                post(app, slot, settings, firstPage, lastPage, test = false)
                NotificationDelivery.logSent(app, "wird", now, now)
                schedule(app)
            }
            is ReminderDecision.Defer -> {
                NotificationDelivery.logNotSent(app, "wird", now, decision, now)
                scheduleDeferred(app, slot, decision.until)
            }
            is ReminderDecision.Drop -> {
                NotificationDelivery.logNotSent(app, "wird", now, decision, now)
                schedule(app)
            }
        }
    }

    /** A deferred wird reminder keeps its own slot so the daily chain never cancels it. */
    private fun scheduleDeferred(context: Context, slot: String, atMillis: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val request = if (slot == SLOT_EVENING) EVENING_DEFERRED_REQUEST else MORNING_DEFERRED_REQUEST
        val intent = Intent(context, WirdReminderReceiver::class.java)
            .setAction(ACTION_WIRD_REMINDER)
            .putExtra(EXTRA_SLOT, slot)
        NotificationMaintenance.schedule(
            context, manager, atMillis,
            PendingIntent.getBroadcast(
                context, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ),
            kind = "wird"
        )
    }

    private fun post(
        context: Context,
        slot: String,
        settings: WirdSettings,
        firstPage: Int,
        lastPage: Int,
        test: Boolean
    ) {
        val openQuran = PendingIntent.getActivity(
            context,
            if (slot == SLOT_EVENING) EVENING_NOTIFICATION else MORNING_NOTIFICATION,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_QURAN, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (test) "هكذا يظهر تذكير الورد" else "ورد اليوم 📖"
        val body = "صفحات $firstPage–$lastPage من سورة ${settings.surah} (${settings.pagesPerDay} صفحات). اضغط لفتح المصحف على صفحة ورد اليوم."
        val notification = AppNotifications.builder(
            context = context,
            channel = AppNotifications.DAILY,
            title = title,
            message = if (test) "$body (تجربة)" else body,
            contentIntent = openQuran
        ).build()
        AppNotifications.manager(context).notify(
            if (slot == SLOT_EVENING) EVENING_NOTIFICATION else MORNING_NOTIFICATION,
            notification
        )
    }
}

class WirdReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WIRD_REMINDER) return
        WirdReminderScheduler.notify(
            context,
            intent.getStringExtra(WirdReminderScheduler.EXTRA_SLOT) ?: WirdReminderScheduler.SLOT_MORNING
        )
    }
}

/**
 * تذكير يومي واحد للمهارات والأهداف — بلا تعقيد: مفتاح واحد ووقت واحد، متوقف افتراضيًا.
 *
 * يُفعَّل من شاشة الإشعارات. الرسالة تذكر أول مهارة مسجلة بالاسم إن وُجدت، وإلا تبقى عامة،
 * والنقر يفتح شاشة المهارات. يمر بنفس بوابة السياسة والفاصل حول الأذان مثل تذكير الورد.
 */
internal data class SkillReminderSettings(
    val enabled: Boolean = false,
    val hour: Int = 20,
    val minute: Int = 0
)

internal object SkillReminderPrefs {
    private const val FILE = "skill_reminder_preferences"
    private const val ENABLED = "enabled"
    private const val HOUR = "hour"
    private const val MINUTE = "minute"

    fun load(context: Context): SkillReminderSettings {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return SkillReminderSettings(
            enabled = prefs.getBoolean(ENABLED, false),
            hour = prefs.getInt(HOUR, 20).coerceIn(0, 23),
            minute = prefs.getInt(MINUTE, 0).coerceIn(0, 59)
        )
    }

    fun save(context: Context, settings: SkillReminderSettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, settings.enabled)
            .putInt(HOUR, settings.hour.coerceIn(0, 23))
            .putInt(MINUTE, settings.minute.coerceIn(0, 59))
            .apply()
    }
}

internal object SkillReminderScheduler {
    const val EXTRA_OPEN_SKILLS = "com.focusstudy.poc.OPEN_SKILLS"

    private const val REQUEST = 6611
    private const val DEFERRED_REQUEST = 6612
    private const val NOTIFICATION = 6613

    fun schedule(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(
            app, REQUEST,
            Intent(app, SkillReminderReceiver::class.java).setAction(ACTION_SKILL_REMINDER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val settings = SkillReminderPrefs.load(app)
        if (!settings.enabled) {
            manager.cancel(pending)
            return
        }
        val now = System.currentTimeMillis()
        val at = NotificationPolicy.adjustOutsidePrayerWindows(
            nextDailyOccurrence(settings.hour, settings.minute, now, AppTimeZone.zone(app)),
            PrayerWindows.todayPrayerMillis(app)
        )
        NotificationMaintenance.schedule(context = app, manager = manager, atMillis = at, pending = pending, kind = "skill")
    }

    fun notifyTest(context: Context) {
        notify(context, test = true)
    }

    fun notify(context: Context, test: Boolean = false) {
        val app = context.applicationContext
        if (!AppNotifications.canPost(app)) return
        if (test) {
            post(app, test = true)
            return
        }
        val now = System.currentTimeMillis()
        when (val decision = NotificationDelivery.decide(app, SkillReminderPrefs.load(app).enabled, now)) {
            ReminderDecision.Send -> {
                post(app, test = false)
                NotificationDelivery.logSent(app, "skill", now, now)
                schedule(app)
            }
            is ReminderDecision.Defer -> {
                NotificationDelivery.logNotSent(app, "skill", now, decision, now)
                val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                NotificationMaintenance.schedule(
                    context = app,
                    manager = manager,
                    atMillis = decision.until,
                    pending = PendingIntent.getBroadcast(
                        app, DEFERRED_REQUEST,
                        Intent(app, SkillReminderReceiver::class.java).setAction(ACTION_SKILL_REMINDER),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    ),
                    kind = "skill"
                )
            }
            is ReminderDecision.Drop -> {
                NotificationDelivery.logNotSent(app, "skill", now, decision, now)
                schedule(app)
            }
        }
    }

    private fun post(context: Context, test: Boolean) {
        val openSkills = PendingIntent.getActivity(
            context,
            NOTIFICATION,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_SKILLS, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val skillName = runCatching {
            StudyDb.get(context.applicationContext).skills().firstOrNull()?.name.orEmpty()
        }.getOrDefault("")
        val title = if (test) "هكذا يظهر تذكير المهارات" else "وقت مهاراتك وأهدافك 🎯"
        val body = if (skillName.isNotBlank()) {
            "خطوة صغيرة اليوم في «$skillName» تقرّبك من هدفك. اضغط لفتح المهارات."
        } else {
            "راجع هدفًا أو تدرّب على مهارة دقائق اليوم — خطوة صغيرة كل يوم. اضغط لفتح المهارات."
        }
        val notification = AppNotifications.builder(
            context = context,
            channel = AppNotifications.DAILY,
            title = title,
            message = if (test) "$body (تجربة)" else body,
            contentIntent = openSkills
        ).build()
        AppNotifications.manager(context).notify(NOTIFICATION, notification)
    }
}

class SkillReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SKILL_REMINDER) return
        SkillReminderScheduler.notify(context)
    }
}
