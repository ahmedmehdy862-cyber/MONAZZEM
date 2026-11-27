package com.focusstudy.poc

import android.content.Context
import java.time.LocalDate

/**
 * The shared policy gate for every non-critical, companion-style delivery: dhikr, the daily
 * morning/evening messages, the wird reminder and the skills/goals reminder.
 *
 * Before this file each of those senders decided on its own — dhikr checked nothing at all, so a
 * Fajr dhikr landed inside the default quiet hours (23:00–07:00) and the evening message could
 * stack on top of the Isha adhan. Now they all ask [NotificationPolicy] the same question with
 * the same inputs (density budget, 45-minute gap, quiet hours, active session, prayer window,
 * ignored streak), and they all log the outcome in `notification_log` so the budget and the gap
 * count every delivery, whichever kind sent it.
 *
 * Prayer alerts never come through here: the adhan is exempt from quiet hours, budget and
 * spacing by design.
 */
internal object NotificationDelivery {
    /**
     * Decides whether a companion-style notification may be posted right now.
     *
     * The kind is always [ReminderKind.COMPANION] for these senders: they are never critical, and
     * the per-kind log rows keep the diagnostic readable ("dhikr", "daily", "wird", "skill").
     */
    fun decide(
        context: Context,
        enabled: Boolean,
        now: Long = System.currentTimeMillis()
    ): ReminderDecision {
        val appContext = context.applicationContext
        val db = StudyDb.get(appContext)
        val prefs = FocusPreferences.load(appContext)
        val zone = AppTimeZone.zone(appContext)
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val session = db.activeSession()?.takeIf { it.endsAt > now }
        return NotificationPolicy.decide(
            PolicyInput(
                now = now,
                kind = ReminderKind.COMPANION,
                critical = false,
                enabled = enabled,
                density = prefs.notificationDensity,
                deliveredToday = db.deliveredCountSince(startOfDay),
                lastNonCriticalAt = db.lastNonCriticalDelivery(),
                ignoredStreak = db.ignoredStreak(),
                sessionEndsAt = session?.endsAt,
                prayerWindow = if (PrayerWindows.respectInReminders(appContext)) {
                    PrayerWindows.activeWindow(appContext, now)
                } else {
                    null
                },
                quietStart = prefs.quietStartHour * 60,
                quietEnd = prefs.quietEndHour * 60,
                respectQuietHours = prefs.quietHoursEnabled,
                // مكالمة جارية (0.35.0): التنبيهات غير الحرجة تُؤجَّل 15 دقيقة
                // ولا تُصدر صوتًا/اهتزازًا أثناء المكالمة.
                inCall = CallAware.isInCall(appContext)
            ),
            zone
        )
    }

    /** A posted notification, so the daily budget and the 45-minute gap count it. */
    fun logSent(context: Context, kind: String, scheduledAt: Long, now: Long = System.currentTimeMillis()) {
        runCatching {
            StudyDb.get(context.applicationContext).logNotification(kind, null, scheduledAt, now, "")
        }
    }

    /** A deferred or dropped delivery, kept in the diagnostic log with its reason code. */
    fun logNotSent(
        context: Context,
        kind: String,
        scheduledAt: Long,
        decision: ReminderDecision,
        now: Long = System.currentTimeMillis()
    ) {
        val action = when (decision) {
            is ReminderDecision.Defer -> "deferred:${decision.reason.key()}"
            is ReminderDecision.Drop -> "dropped:${decision.reason.key()}"
            ReminderDecision.Send -> return
        }
        runCatching {
            StudyDb.get(context.applicationContext).logNotification(kind, null, scheduledAt, null, action)
        }
    }
}
