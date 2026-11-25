package com.focusstudy.poc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

internal const val ACTION_DHIKR_REMINDER = "com.focusstudy.poc.DHIKR_REMINDER"

internal data class DhikrMoment(val key: String, val title: String, val body: String, val atMillis: Long)

/** A single on-device switch; no account, network call, or personal data is involved. */
internal object DhikrPrefs {
    private const val FILE = "dhikr_preferences"
    private const val ENABLED = "enabled"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, true)
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean(ENABLED, enabled).apply() }
}

/**
 * Four short remembrances spread across the day.
 *
 * With a saved location they follow the local prayer times (after Fajr, Dhuhr, Maghrib and Isha),
 * otherwise they fall back to gentle fixed times. One alarm is kept at a time and each delivery
 * schedules the next one, so reboot, date changes and an app launch all converge on the same plan.
 *
 * Delivery goes through the shared policy gate ([NotificationDelivery]): quiet hours, the daily
 * density budget, the 45-minute gap, an active focus session and the prayer window all apply —
 * the Fajr dhikr used to land inside the default quiet hours (23:00–07:00) because nothing
 * checked. A deferred dhikr is re-alarmed at the instant the policy names instead of being
 * dropped, and every prayer-based time is pushed out of the ±15-minute adhan spacing window
 * (the +10-minute midday/evening moments move to +15).
 */
internal object DhikrReminderScheduler {
    private const val REQUEST = 6401
    private const val NOTIFICATION = 6402
    /** A deferred dhikr keeps its own slot so the normal chain never cancels it by accident. */
    private const val DEFERRED_REQUEST = 6403
    internal const val EXTRA_KEY = "dhikr_key"
    private const val EXTRA_TITLE = "dhikr_title"
    private const val EXTRA_BODY = "dhikr_body"

    internal const val MORNING = "morning"
    internal const val MIDDAY = "midday"
    internal const val EVENING = "evening"
    internal const val NIGHT = "night"

    private val texts = mapOf(
        MORNING to ("أذكار الصباح" to "أصبحتَ في نعمة الله وعافيته. سبحان الله وبحمده، سبحان الله العظيم، ولا حول ولا قوة إلا بالله."),
        MIDDAY to ("ذكر الظهر" to "سبحان الله وبحمده، عدد خلقه ورضا نفسه وزنة عرشه ومداد كلماته."),
        EVENING to ("أذكار المساء" to "أمسيتَ في نعمة الله وعافيته. اللهم بك أمسينا وبك أصبحنا، وبك نحيا وبك نموت وإليك النشور."),
        NIGHT to ("ذكر قبل النوم" to "باسم الله الذي لا يضر مع اسمه شيء في الأرض ولا في السماء، وهو السميع العليم.")
    )

    fun moments(context: Context, now: Long = System.currentTimeMillis()): List<DhikrMoment> {
        val hasLocation = PrayerPrefs.latitude(context).isFinite() && PrayerPrefs.longitude(context).isFinite()
        val zone = AppTimeZone.zone(context)
        var prayerMillis = emptyList<Long>()
        val bases = if (hasLocation) {
            runCatching {
                val prayers = PrayerCalculator.today(context, Date(now))
                prayerMillis = prayers.map { it.atMillis }
                mapOf(
                    MORNING to after(prayers, "الفجر", 20),
                    MIDDAY to after(prayers, "الظهر", 10),
                    EVENING to after(prayers, "المغرب", 10),
                    NIGHT to after(prayers, "العشاء", 20)
                )
            }.getOrDefault(emptyMap())
        } else emptyMap()

        return texts.map { (key, copy) ->
            // Prayer-based times keep clear of the adhan itself: +10 minutes after Dhuhr/Maghrib
            // sits inside the ±15-minute spacing window, so it moves to +15; +20 stays as it is.
            // Fixed fallback times have no prayer times to measure against, so they stand.
            val fromPrayer = bases[key]
            val base = if (fromPrayer != null) {
                NotificationPolicy.adjustOutsidePrayerWindows(fromPrayer, prayerMillis)
            } else fixed(now, when (key) {
                MORNING -> 7 to 0
                MIDDAY -> 12 to 30
                EVENING -> 18 to 0
                else -> 21 to 30
            }, zone)
            val at = if (base > now) base else nextDay(base, zone)
            DhikrMoment(key, copy.first, copy.second, at)
        }.sortedBy { it.atMillis }
    }

    private fun after(prayers: List<PrayerMoment>, name: String, minutes: Int): Long? =
        prayers.firstOrNull { it.name == name }?.atMillis?.plus(minutes * 60_000L)

    /** Fixed fallback times are wall-clock times in the selected zone, including across DST. */
    internal fun fixed(now: Long, hourAndMinute: Pair<Int, Int>, zone: ZoneId): Long =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            .withHour(hourAndMinute.first)
            .withMinute(hourAndMinute.second)
            .withSecond(0)
            .withNano(0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    internal fun nextDay(atMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(atMillis).atZone(zone).plusDays(1).toInstant().toEpochMilli()

    fun schedule(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val base = Intent(context, DhikrReminderReceiver::class.java).setAction(ACTION_DHIKR_REMINDER)
        val cancelWith = PendingIntent.getBroadcast(
            context, REQUEST, base,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (!DhikrPrefs.enabled(context)) { manager.cancel(cancelWith); return }

        val next = moments(context).firstOrNull() ?: run { manager.cancel(cancelWith); return }
        base.putExtra(EXTRA_KEY, next.key)
            .putExtra(EXTRA_TITLE, next.title)
            .putExtra(EXTRA_BODY, next.body)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST, base,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationMaintenance.schedule(
            context = context,
            manager = manager,
            atMillis = next.atMillis,
            pending = pending,
            kind = "dhikr"
        )
    }

    fun notifyTest(context: Context) {
        val sample = moments(context).firstOrNull { it.key == MORNING }
            ?: moments(context).first()
        notify(context, sample, test = true)
    }

    fun notify(context: Context, moment: DhikrMoment, test: Boolean = false) {
        if (test) {
            // An explicit «جرّب» tap is the person asking to see it now; the policy must not
            // swallow a test, so it posts directly and the normal chain continues.
            post(context, moment, test = true)
            schedule(context)
            return
        }
        val now = System.currentTimeMillis()
        when (val decision = NotificationDelivery.decide(context, DhikrPrefs.enabled(context), now)) {
            ReminderDecision.Send -> {
                if (post(context, moment, test = false)) {
                    NotificationDelivery.logSent(context, "dhikr", moment.atMillis, now)
                }
                schedule(context)
            }
            is ReminderDecision.Defer -> {
                NotificationDelivery.logNotSent(context, "dhikr", moment.atMillis, decision, now)
                scheduleDeferred(context, moment, decision.until)
            }
            is ReminderDecision.Drop -> {
                NotificationDelivery.logNotSent(context, "dhikr", moment.atMillis, decision, now)
                schedule(context)
            }
        }
    }

    /** Re-alarms one deferred dhikr at the instant the policy named, without touching the chain. */
    private fun scheduleDeferred(context: Context, moment: DhikrMoment, atMillis: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DhikrReminderReceiver::class.java)
            .setAction(ACTION_DHIKR_REMINDER)
            .putExtra(EXTRA_KEY, moment.key)
            .putExtra(EXTRA_TITLE, moment.title)
            .putExtra(EXTRA_BODY, moment.body)
        val pending = PendingIntent.getBroadcast(
            context, DEFERRED_REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationMaintenance.schedule(context, manager, atMillis, pending, kind = "dhikr")
    }

    /** Posts the notification; false when the system permission blocks it. */
    private fun post(context: Context, moment: DhikrMoment, test: Boolean): Boolean {
        if (!AppNotifications.canPost(context)) return false
        val openApp = PendingIntent.getActivity(
            context,
            NOTIFICATION,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (test) "هكذا تظهر تذكيرات الأذكار" else moment.title
        val body = if (test) "${moment.body} (تجربة)" else moment.body
        val notification = AppNotifications.builder(
            context = context,
            channel = AppNotifications.DHIKR,
            title = title,
            message = body,
            contentIntent = openApp
        ).build()
        AppNotifications.manager(context).notify(NOTIFICATION + moment.key.hashCode(), notification)
        return true
    }
}

class DhikrReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DHIKR_REMINDER) return
        val key = intent.getStringExtra(DhikrReminderScheduler.EXTRA_KEY).orBlank()
        if (key.isBlank()) return
        val moment = DhikrMoment(
            key = key,
            title = intent.getStringExtra("dhikr_title").orBlank(),
            body = intent.getStringExtra("dhikr_body").orBlank(),
            atMillis = System.currentTimeMillis()
        )
        if (moment.title.isBlank() || moment.body.isBlank()) return
        DhikrReminderScheduler.notify(context, moment)
    }
}

private fun String?.orBlank(): String = this?.takeIf { it.isNotBlank() }.orEmpty()
