package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NotificationPolicyTest {
    // Fixed UTC zone and instant so quiet-hour checks do not depend on the machine's locale.
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2023-11-14T23:30:00Z").toEpochMilli()
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun input(
        kind: ReminderKind = ReminderKind.TASK_DUE,
        critical: Boolean = false,
        enabled: Boolean = true,
        density: NotificationDensity = NotificationDensity.BALANCED,
        deliveredToday: Int = 0,
        lastNonCriticalAt: Long? = null,
        ignoredStreak: Int = 0,
        sessionEndsAt: Long? = null,
        prayerWindow: LongRange? = null,
        quietStart: Int? = null,
        quietEnd: Int? = null,
        respectQuietHours: Boolean = true,
        snoozedUntil: Long? = null,
        inCall: Boolean = false
    ) = PolicyInput(
        now = now,
        kind = kind,
        critical = critical,
        enabled = enabled,
        density = density,
        deliveredToday = deliveredToday,
        lastNonCriticalAt = lastNonCriticalAt,
        ignoredStreak = ignoredStreak,
        sessionEndsAt = sessionEndsAt,
        prayerWindow = prayerWindow,
        quietStart = quietStart,
        quietEnd = quietEnd,
        respectQuietHours = respectQuietHours,
        snoozedUntil = snoozedUntil,
        inCall = inCall
    )

    @Test
    fun `call defers companion reminder fifteen minutes`() {
        val decision = NotificationPolicy.decide(input(inCall = true))
        assertEquals(ReminderDecision.Defer(now + 15 * minute, DeferReason.IN_CALL), decision)
    }

    @Test
    fun `explicit snooze still wins over a call`() {
        val decision = NotificationPolicy.decide(input(inCall = true, snoozedUntil = now + 10 * minute))
        assertEquals(ReminderDecision.Defer(now + 10 * minute, DeferReason.SNOOZED), decision)
    }

    @Test
    fun `notifications disabled drops everything`() {
        val decision = NotificationPolicy.decide(input(enabled = false))
        assertEquals(ReminderDecision.Drop(DropReason.DISABLED), decision)
    }

    @Test
    fun `snoozed reminder waits for its instant`() {
        val decision = NotificationPolicy.decide(input(snoozedUntil = now + 10 * minute))
        assertEquals(ReminderDecision.Defer(now + 10 * minute, DeferReason.SNOOZED), decision)
    }

    @Test
    fun `expired snooze no longer blocks`() {
        assertEquals(ReminderDecision.Send, NotificationPolicy.decide(input(snoozedUntil = now - minute)))
    }

    @Test
    fun `active session defers even a critical task`() {
        val ends = now + 5 * minute
        val decision = NotificationPolicy.decide(input(critical = true, sessionEndsAt = ends))
        assertEquals(ReminderDecision.Defer(ends, DeferReason.ACTIVE_SESSION), decision)
    }

    @Test
    fun `finished session does not defer`() {
        assertEquals(ReminderDecision.Send, NotificationPolicy.decide(input(sessionEndsAt = now - minute)))
    }

    @Test
    fun `prayer window defers until it ends`() {
        val window = (now - 5 * minute)..(now + 15 * minute)
        val decision = NotificationPolicy.decide(input(critical = true, prayerWindow = window))
        assertEquals(ReminderDecision.Defer(window.last + 1, DeferReason.PRAYER), decision)
    }

    @Test
    fun `prayer window outside now does not defer`() {
        val window = (now + hour)..(now + hour + minute)
        assertEquals(ReminderDecision.Send, NotificationPolicy.decide(input(prayerWindow = window)))
    }

    @Test
    fun `quiet hours overnight defer to the morning`() {
        val decision = NotificationPolicy.decide(input(quietStart = 23 * 60, quietEnd = 7 * 60), zone)
        assertTrue(decision is ReminderDecision.Defer)
        val defer = decision as ReminderDecision.Defer
        assertEquals(DeferReason.QUIET_HOURS, defer.reason)
        assertEquals(7, PrayerWindows.minuteOfDay(defer.until, zone) / 60)
    }

    @Test
    fun `outside quiet hours notifications flow`() {
        val midday = now - (PrayerWindows.minuteOfDay(now, zone) - 12 * 60) * minute
        val decision = NotificationPolicy.decide(
            input(quietStart = 23 * 60, quietEnd = 7 * 60).copy(now = midday),
            zone
        )
        assertEquals(ReminderDecision.Send, decision)
    }

    @Test
    fun `respect quiet hours off sends critical reminders during the quiet window`() {
        val decision = NotificationPolicy.decide(
            input(critical = true, quietStart = 23 * 60, quietEnd = 7 * 60, respectQuietHours = false)
        )
        assertEquals(ReminderDecision.Send, decision)
    }

    @Test
    fun `respect quiet hours off sends every reminder kind at any hour`() {
        ReminderKind.entries.forEach { kind ->
            val decision = NotificationPolicy.decide(
                input(kind = kind, quietStart = 23 * 60, quietEnd = 7 * 60, respectQuietHours = false)
            )
            assertEquals("kind ${kind.key}", ReminderDecision.Send, decision)
        }
    }

    @Test
    fun `respect quiet hours off still keeps budget and spacing rules`() {
        val decision = NotificationPolicy.decide(
            input(
                quietStart = 23 * 60, quietEnd = 7 * 60, respectQuietHours = false,
                density = NotificationDensity.CALM, deliveredToday = 3
            )
        )
        assertEquals(ReminderDecision.Drop(DropReason.DAILY_BUDGET), decision)
    }

    @Test
    fun `critical task ignores the daily budget`() {
        val decision = NotificationPolicy.decide(
            input(critical = true, density = NotificationDensity.CALM, deliveredToday = 9)
        )
        assertEquals(ReminderDecision.Send, decision)
    }

    @Test
    fun `daily budget stops non critical reminders`() {
        val decision = NotificationPolicy.decide(
            input(density = NotificationDensity.CALM, deliveredToday = 3)
        )
        assertEquals(ReminderDecision.Drop(DropReason.DAILY_BUDGET), decision)
    }

    @Test
    fun `active density allows more before dropping`() {
        val decision = NotificationPolicy.decide(
            input(density = NotificationDensity.ACTIVE, deliveredToday = 6)
        )
        assertEquals(ReminderDecision.Send, decision)
    }

    @Test
    fun `three ignored notifications lower the grade`() {
        val decision = NotificationPolicy.decide(input(ignoredStreak = 3))
        assertEquals(ReminderDecision.Drop(DropReason.IGNORED_STREAK), decision)
    }

    @Test
    fun `blank delivery actions are not ignored streaks`() {
        assertEquals(0, NotificationPolicy.ignoredStreakFrom(listOf("", "", "")))
    }

    @Test
    fun `only consecutive swipes count as ignored`() {
        assertEquals(2, NotificationPolicy.ignoredStreakFrom(listOf(ACTION_IGNORED, ACTION_IGNORED, "")))
        assertEquals(1, NotificationPolicy.ignoredStreakFrom(listOf(ACTION_IGNORED, "done", ACTION_IGNORED)))
    }

    @Test
    fun `two ignored notifications still send`() {
        assertEquals(ReminderDecision.Send, NotificationPolicy.decide(input(ignoredStreak = 2)))
    }

    @Test
    fun `critical reminder survives an ignored streak`() {
        assertEquals(ReminderDecision.Send, NotificationPolicy.decide(input(critical = true, ignoredStreak = 5)))
    }

    @Test
    fun `minimum gap defers the next non critical reminder`() {
        val last = now - 10 * minute
        val decision = NotificationPolicy.decide(input(lastNonCriticalAt = last))
        assertEquals(
            ReminderDecision.Defer(last + NotificationPolicy.MIN_GAP_MILLIS, DeferReason.MIN_GAP),
            decision
        )
    }

    @Test
    fun `gap exactly at the limit sends`() {
        val last = now - NotificationPolicy.MIN_GAP_MILLIS
        assertEquals(ReminderDecision.Send, NotificationPolicy.decide(input(lastNonCriticalAt = last)))
    }

    @Test
    fun `critical ignores the minimum gap`() {
        assertEquals(
            ReminderDecision.Send,
            NotificationPolicy.decide(input(critical = true, lastNonCriticalAt = now - minute))
        )
    }

    @Test
    fun `empty state sends`() {
        assertEquals(ReminderDecision.Send, NotificationPolicy.decide(input()))
    }

    @Test
    fun `critical detection around the due time`() {
        assertTrue(NotificationPolicy.isCritical(now + 10 * minute, now))
        assertTrue(NotificationPolicy.isCritical(now - 2 * hour, now))
        assertFalse(NotificationPolicy.isCritical(now + 2 * hour, now))
        assertFalse(NotificationPolicy.isCritical(now - 30 * hour, now))
        assertFalse(NotificationPolicy.isCritical(null, now))
    }

    @Test
    fun `exam is critical only in its last day`() {
        assertTrue(NotificationPolicy.isExamCritical(now + 5 * hour, now))
        assertFalse(NotificationPolicy.isExamCritical(now + 3 * 24 * hour, now))
        assertFalse(NotificationPolicy.isExamCritical(null, now))
    }

    @Test
    fun `unknown density falls back to balanced`() {
        assertEquals(NotificationDensity.BALANCED, NotificationDensity.from("nonsense"))
        assertEquals(5, NotificationDensity.BALANCED.dailyBudget)
    }

    @Test
    fun `prayer window detection includes its margins`() {
        val moment = PrayerMoment("العصر", now + 10 * minute)
        val margins = PrayerWindows.Margins(20, 20)
        assertTrue(PrayerWindows.windowContaining(listOf(moment), now, margins) != null)
        assertTrue(PrayerWindows.windowContaining(listOf(moment), now + 31 * minute, margins) == null)
    }

    @Test
    fun `quiet hours wrap midnight`() {
        assertTrue(PrayerWindows.isInsideQuietHours(2 * 60, 23 * 60, 7 * 60))
        assertTrue(PrayerWindows.isInsideQuietHours(23 * 60 + 30, 23 * 60, 7 * 60))
        assertFalse(PrayerWindows.isInsideQuietHours(12 * 60, 23 * 60, 7 * 60))
        assertFalse(PrayerWindows.isInsideQuietHours(12 * 60, 12 * 60, 12 * 60))
    }

    // Phase 4: the ±15-minute evening spacing rule around every adhan.
    @Test
    fun `spacing pushes a reminder inside the adhan window to its end`() {
        val isha = now + 10 * minute
        assertEquals(
            isha + 15 * minute,
            NotificationPolicy.adjustOutsidePrayerWindows(now, listOf(isha))
        )
    }

    @Test
    fun `spacing leaves a reminder outside the adhan window alone`() {
        val isha = now + 10 * minute
        assertEquals(now, NotificationPolicy.adjustOutsidePrayerWindows(now, listOf(isha + 2 * hour)))
        assertEquals(
            now + 26 * minute,
            NotificationPolicy.adjustOutsidePrayerWindows(now + 26 * minute, listOf(isha))
        )
    }

    @Test
    fun `spacing pushes forward only and walks through two windows`() {
        val first = now + 10 * minute
        val second = first + 20 * minute
        // Lands in the first window, is pushed to its end, and that instant sits inside the
        // second window, so it walks on to the second window's end instead of stopping.
        assertEquals(
            second + 15 * minute,
            NotificationPolicy.adjustOutsidePrayerWindows(now, listOf(first, second))
        )
    }

    @Test
    fun `spacing with no prayer times changes nothing`() {
        assertEquals(now, NotificationPolicy.adjustOutsidePrayerWindows(now, emptyList()))
    }
}
