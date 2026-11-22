package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Phase 4: a saved IANA id must govern wall-clock work, including DST transitions. */
class AppTimeZoneTest {

    private val cairo = ZoneId.of("Africa/Cairo")
    private val utc = ZoneId.of("UTC")

    @Test fun validIdsAreTrimmedAndCanonicalized() {
        assertEquals("Africa/Cairo", AppTimeZone.sanitize("  Africa/Cairo  "))
        assertEquals("UTC", AppTimeZone.sanitize("UTC"))
        assertTrue(AppTimeZone.isValid("Europe/London"))
    }

    @Test fun invalidAndEmptyIdsFallBackToDeviceZone() {
        assertEquals("", AppTimeZone.sanitize("Not/AZone"))
        assertEquals("", AppTimeZone.sanitize("   "))
        assertFalse(AppTimeZone.isValid("Not/AZone"))
        assertEquals(utc, AppTimeZone.resolve("Not/AZone", utc))
        assertEquals(utc, AppTimeZone.resolve(null, utc))
        assertEquals(utc, AppTimeZone.resolve("  ", utc))
        assertEquals(cairo, AppTimeZone.resolve("Africa/Cairo", utc))
    }

    @Test fun cairoDstChangesOffsetWithoutChangingZoneId() {
        val before = LocalDateTime.of(2024, 4, 25, 12, 0).atZone(cairo).offset
        val after = LocalDateTime.of(2024, 5, 1, 12, 0).atZone(cairo).offset
        assertEquals(ZoneOffset.ofHours(2), before)
        assertEquals(ZoneOffset.ofHours(3), after)
        assertNotEquals(before, after)
        assertEquals("Africa/Cairo", AppTimeZone.resolve("Africa/Cairo", utc).id)
    }

    @Test fun fixedDhikrTimesFollowSelectedZoneAcrossDst() {
        // 07:00 local in Cairo is 05:00 UTC before the 2024 DST change and 04:00 UTC after it.
        val beforeDst = Instant.parse("2024-04-25T12:00:00Z").toEpochMilli()
        val afterDst = Instant.parse("2024-05-01T12:00:00Z").toEpochMilli()
        assertEquals(
            "2024-04-25T05:00:00Z",
            Instant.ofEpochMilli(DhikrReminderScheduler.fixed(beforeDst, 7 to 0, cairo)).toString()
        )
        assertEquals(
            "2024-05-01T04:00:00Z",
            Instant.ofEpochMilli(DhikrReminderScheduler.fixed(afterDst, 7 to 0, cairo)).toString()
        )
        assertEquals(
            "2024-05-01T07:00:00Z",
            Instant.ofEpochMilli(DhikrReminderScheduler.fixed(afterDst, 7 to 0, utc)).toString()
        )
    }

    @Test fun nextDayKeepsWallClockTimeAcrossDst() {
        val at = ZonedDateTime.of(LocalDate.of(2024, 4, 25), java.time.LocalTime.of(21, 30), cairo)
        val next = DhikrReminderScheduler.nextDay(at.toInstant().toEpochMilli(), cairo)
        val localNext = Instant.ofEpochMilli(next).atZone(cairo)
        assertEquals(21, localNext.hour)
        assertEquals(30, localNext.minute)
        assertEquals(LocalDate.of(2024, 4, 26), localNext.toLocalDate())
        // The real instant moves by 23 hours because Cairo gains an hour at midnight.
        assertEquals(at.toInstant().plusSeconds(23 * 3600), Instant.ofEpochMilli(next))
    }

    @Test fun prayerFormattingUsesSelectedZone() {
        val at = Instant.parse("2024-05-01T12:00:00Z").toEpochMilli()
        assertNotEquals(PrayerCalculator.format(at, cairo), PrayerCalculator.format(at, utc))
        assertEquals(PrayerCalculator.format(at, utc), PrayerCalculator.format(at, ZoneId.of("Etc/UTC")))
    }

    @Test fun quietHoursDeferUsesSelectedZoneAndDoesNotRepeat() {
        val zone = cairo
        val now = ZonedDateTime.of(LocalDate.of(2024, 5, 1), java.time.LocalTime.of(2, 30), zone)
            .toInstant().toEpochMilli()
        val input = PolicyInput(
            now = now,
            kind = ReminderKind.TASK_DUE,
            quietStart = 23 * 60,
            quietEnd = 7 * 60
        )
        val first = NotificationPolicy.decide(input, zone)
        assertTrue(first is ReminderDecision.Defer)
        val until = (first as ReminderDecision.Defer).until
        assertTrue(until > now)
        assertEquals(7, PrayerWindows.minuteOfDay(until, zone) / 60)

        // Re-evaluating at the deferred instant sends it once instead of deferring again or losing it.
        val second = NotificationPolicy.decide(input.copy(now = until), zone)
        assertEquals(ReminderDecision.Send, second)
    }
}
