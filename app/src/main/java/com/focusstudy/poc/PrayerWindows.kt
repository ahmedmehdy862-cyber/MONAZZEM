package com.focusstudy.poc

import android.content.Context
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Prayer-aware scheduling helpers on top of [PrayerCalculator].
 *
 * A prayer "window" is the interval around a prayer during which the planner and the reminder
 * engine must not schedule work or fire non-critical notifications. The margins come from the
 * user's preferences (default 20 minutes before, 20 after).
 */
internal object PrayerWindows {
    const val DEFAULT_MARGIN_MINUTES = 20

    data class Margins(val before: Int, val after: Int) {
        companion object {
            val DEFAULT = Margins(DEFAULT_MARGIN_MINUTES, DEFAULT_MARGIN_MINUTES)
        }
    }

    fun margins(context: Context): Margins {
        val prefs = context.getSharedPreferences(FocusPreferences.FILE, Context.MODE_PRIVATE)
        return Margins(
            before = prefs.getInt("prayer_margin_before", DEFAULT_MARGIN_MINUTES).coerceIn(0, 90),
            after = prefs.getInt("prayer_margin_after", DEFAULT_MARGIN_MINUTES).coerceIn(0, 90)
        )
    }

    fun saveMargins(context: Context, margins: Margins) {
        context.getSharedPreferences(FocusPreferences.FILE, Context.MODE_PRIVATE).edit()
            .putInt("prayer_margin_before", margins.before.coerceIn(0, 90))
            .putInt("prayer_margin_after", margins.after.coerceIn(0, 90))
            .apply()
    }

    /** Reminder and planning respect for prayer; both are on by default. */
    fun respectInReminders(context: Context): Boolean =
        context.getSharedPreferences(FocusPreferences.FILE, Context.MODE_PRIVATE)
            .getBoolean("prayer_respect_reminders", true)

    fun setRespectInReminders(context: Context, value: Boolean) {
        context.getSharedPreferences(FocusPreferences.FILE, Context.MODE_PRIVATE).edit()
            .putBoolean("prayer_respect_reminders", value).apply()
    }

    /** The window that contains [now], or null when [now] is outside every prayer window. */
    fun windowContaining(moments: List<PrayerMoment>, now: Long, margins: Margins): LongRange? =
        moments.firstOrNull { moment ->
            now >= moment.atMillis - margins.before * MINUTE && now <= moment.atMillis + margins.after * MINUTE
        }?.let { moment ->
            (moment.atMillis - margins.before * MINUTE)..(moment.atMillis + margins.after * MINUTE)
        }

    /** The next moment whose window starts after [now]; used for "start after prayer" offers. */
    fun nextWindowStart(moments: List<PrayerMoment>, now: Long, margins: Margins): Long? =
        moments.map { it.atMillis - margins.before * MINUTE }.filter { it > now }.minOrNull()

    fun nextMoment(context: Context, now: Long = System.currentTimeMillis()): PrayerMoment? =
        if (!PrayerPrefs.enabled(context)) null
        else runCatching { PrayerCalculator.next(context, now) }.getOrNull()

    fun todayMoments(context: Context, now: Long = System.currentTimeMillis()): List<PrayerMoment> =
        if (!PrayerPrefs.enabled(context)) emptyList()
        else runCatching { PrayerCalculator.today(context, java.util.Date(now)) }.getOrDefault(emptyList())

    /** The prayer window around [now] for the enabled user, otherwise null. */
    fun activeWindow(context: Context, now: Long = System.currentTimeMillis()): LongRange? {
        if (!PrayerPrefs.enabled(context)) return null
        return windowContaining(todayMoments(context, now), now, margins(context))
    }

    /** First instant at or after [from] that is outside every prayer window today. */
    fun nextFreeStart(context: Context, from: Long): Long {
        if (!PrayerPrefs.enabled(context)) return from
        val margins = margins(context)
        var candidate = from
        repeat(12) {
            val window = windowContaining(todayMoments(context, candidate), candidate, margins) ?: return candidate
            candidate = window.last + MINUTE
        }
        return candidate
    }

    /** "بعد المغرب" for the next prayer; used by the capture parser and the next-step reason. */
    fun describeNext(context: Context, now: Long = System.currentTimeMillis()): String? =
        nextMoment(context, now)?.let { "${it.name} ${PrayerCalculator.format(context, it.atMillis)}" }

    fun isInsideQuietHours(nowMinutesOfDay: Int, quietStart: Int, quietEnd: Int): Boolean =
        if (quietStart == quietEnd) false
        else if (quietStart < quietEnd) nowMinutesOfDay in quietStart until quietEnd
        else nowMinutesOfDay >= quietStart || nowMinutesOfDay < quietEnd

    fun minuteOfDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    /** Start of the next quiet-hours window after [now], used to defer a reminder to morning. */
    fun quietHoursEnd(now: Long, quietEnd: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
        val localNow = Instant.ofEpochMilli(now).atZone(zone)
        val target = LocalTime.of(quietEnd / 60, quietEnd % 60)
        var candidate = localNow.with(target)
        if (!candidate.toInstant().toEpochMilli().let { it > now }) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }

    private const val MINUTE = 60_000L
}
