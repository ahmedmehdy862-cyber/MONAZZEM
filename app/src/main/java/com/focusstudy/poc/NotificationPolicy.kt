package com.focusstudy.poc

import java.time.ZoneId

/**
 * Pure notification decision logic for the reminder engine.
 *
 * Every rule the user can observe lives here as a function of data, never of Android state, so it
 * is unit tested and can be explained ("لماذا؟") and overridden from the settings screen.
 */
internal enum class ReminderKind(val key: String) {
    TASK_DUE("task_due"),
    TASK_OVERDUE("task_overdue"),
    EXAM("exam"),
    COMPANION("companion"),
    PLEDGE("pledge")
}

enum class NotificationDensity(val key: String, val label: String, val dailyBudget: Int) {
    CALM("calm", "هادئ", 3),
    BALANCED("balanced", "متوازن", 5),
    ACTIVE("active", "نشيط", 8);

    companion object {
        fun from(key: String?): NotificationDensity = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

internal sealed interface ReminderDecision {
    data object Send : ReminderDecision
    data class Defer(val until: Long, val reason: DeferReason) : ReminderDecision
    data class Drop(val reason: DropReason) : ReminderDecision
}

internal enum class DeferReason(val label: String) {
    ACTIVE_SESSION("جلسة تركيز جارية"),
    PRAYER("نافذة صلاة"),
    QUIET_HOURS("ساعات الهدوء"),
    MIN_GAP("الحد الأدنى بين التنبيهات"),
    SNOOZED("مؤجّل بطلبك")
}

internal enum class DropReason(val label: String) {
    DISABLED("الإشعارات موقوفة"),
    DAILY_BUDGET("انتهت ميزانية اليوم"),
    IGNORED_STREAK("تجاهلت آخر التنبيهات فخفّضناها")
}

internal data class PolicyInput(
    val now: Long,
    val kind: ReminderKind,
    val critical: Boolean = false,
    val enabled: Boolean = true,
    val density: NotificationDensity = NotificationDensity.BALANCED,
    val deliveredToday: Int = 0,
    val lastNonCriticalAt: Long? = null,
    val ignoredStreak: Int = 0,
    val sessionEndsAt: Long? = null,
    val prayerWindow: LongRange? = null,
    val quietStart: Int? = null,
    val quietEnd: Int? = null,
    /** When false, the quiet window below is ignored: every kind may arrive at any hour. */
    val respectQuietHours: Boolean = true,
    val snoozedUntil: Long? = null
)

internal object NotificationPolicy {
    const val MIN_GAP_MILLIS = 45 * 60 * 1000L
    const val IGNORED_THRESHOLD = 3

    /**
     * قاعدة الفاصل حول الأذان (±15 دقيقة).
     *
     * المساء مزدحم بطبيعته: تنبيه «قبل العشاء» ثم أذان العشاء (شاشة كاملة) ثم أذكار ما بعد
     * العشاء ثم رسالة المساء، وكلها قد تقع في نحو نصف ساعة. أي تنبيه غير صلاة يُجدول داخل نافذة
     * [prayerMillis] ± [marginMinutes] يُدفع إلى نهايتها (وقت الصلاة + الهامش) بدل إلغائه،
     * فيصل متأخرًا قليلًا ولا يتكدس مع الأذان. تنبيهات «قبل الصلاة» نفسها معفاة لأنها جزء من
     * منظومة الصلاة، والأذان نفسه لا يُؤجَّل أبدًا. الدفع للأمام فقط، فلا يعود تنبيه إلى الماضي.
     */
    const val PRAYER_SPACING_MINUTES = 15

    fun adjustOutsidePrayerWindows(
        desiredMillis: Long,
        prayerMillis: List<Long>,
        marginMinutes: Int = PRAYER_SPACING_MINUTES
    ): Long {
        var adjusted = desiredMillis
        val margin = marginMinutes * 60_000L
        prayerMillis.sorted().forEach { prayerAt ->
            if (adjusted in (prayerAt - margin)..(prayerAt + margin)) {
                adjusted = prayerAt + margin
            }
        }
        return adjusted
    }

    /**
     * Only an explicit swipe/ignore counts. An empty action means the app showed the notification
     * but has no recorded answer; counting it used to drop reminders after three normal deliveries.
     */
    fun ignoredStreakFrom(actions: List<String>): Int {
        var streak = 0
        for (action in actions) {
            if (action == ACTION_IGNORED) streak++ else break
        }
        return streak
    }

    fun decide(input: PolicyInput, zone: ZoneId = ZoneId.systemDefault()): ReminderDecision {
        if (!input.enabled) return ReminderDecision.Drop(DropReason.DISABLED)

        input.snoozedUntil?.let { if (it > input.now) return ReminderDecision.Defer(it, DeferReason.SNOOZED) }

        // A running focus session always wins: nothing interrupts it, everything is summarised after.
        input.sessionEndsAt?.let { if (it > input.now) return ReminderDecision.Defer(it, DeferReason.ACTIVE_SESSION) }

        val window = input.prayerWindow
        if (window != null && input.now in window) {
            return ReminderDecision.Defer(window.last + 1, DeferReason.PRAYER)
        }

        val quietStart = input.quietStart
        val quietEnd = input.quietEnd
        if (input.respectQuietHours && quietStart != null && quietEnd != null &&
            PrayerWindows.isInsideQuietHours(PrayerWindows.minuteOfDay(input.now, zone), quietStart, quietEnd)
        ) {
            return ReminderDecision.Defer(PrayerWindows.quietHoursEnd(input.now, quietEnd, zone), DeferReason.QUIET_HOURS)
        }

        // Critical reminders (a task due now, an exam tomorrow) are exempt from budget and spacing.
        if (input.critical) return ReminderDecision.Send

        if (input.ignoredStreak >= IGNORED_THRESHOLD) {
            return ReminderDecision.Drop(DropReason.IGNORED_STREAK)
        }
        if (input.deliveredToday >= input.density.dailyBudget) {
            return ReminderDecision.Drop(DropReason.DAILY_BUDGET)
        }
        val last = input.lastNonCriticalAt
        if (last != null && input.now - last < MIN_GAP_MILLIS) {
            return ReminderDecision.Defer(last + MIN_GAP_MILLIS, DeferReason.MIN_GAP)
        }
        return ReminderDecision.Send
    }

    /** A task is critical when it is due within [windowMinutes] or already overdue by a day at most. */
    fun isCritical(dueAt: Long?, now: Long, windowMinutes: Int = 15): Boolean {
        if (dueAt == null) return false
        val delta = dueAt - now
        return delta <= windowMinutes * 60_000L && delta >= -24 * 60 * 60 * 1000L
    }

    /** Exams are critical in their last day, so the countdown always lands. */
    fun isExamCritical(examAt: Long?, now: Long): Boolean {
        if (examAt == null) return false
        val delta = examAt - now
        return delta in 0..(24 * 60 * 60 * 1000L)
    }
}

/** Short reason codes for the diagnostic log; shared so every sender spells them the same way. */
internal fun DeferReason.key(): String = name.lowercase()
internal fun DropReason.key(): String = name.lowercase()
