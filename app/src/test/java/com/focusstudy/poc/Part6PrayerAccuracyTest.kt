package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Part 6 verification. Reference timings for 15 Jan 2025 were read from the AlAdhan API
 * (method 5 = Egyptian General Authority of Survey, the same 19.5°/17.5° parameters) for
 * Cairo, Alexandria and Aswan. All are local Africa/Cairo clock times.
 */
class Part6PrayerAccuracyTest {

    private val zone = ZoneId.of("Africa/Cairo")
    private val date = LocalDate.of(2025, 1, 15)
    private val egyptian = PrayerSettings(method = "EGYPTIAN", asrSchool = AsrSchool.STANDARD)

    private fun clock(millis: Long): String = SimpleDateFormat("HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone(zone.id)
    }.format(Date(millis))

    private fun clocks(lat: Double, lon: Double, settings: PrayerSettings = egyptian): List<String> =
        PrayerAccuracy.calculate(lat, lon, date, settings).map { clock(it.atMillis) }

    /** Adhan 1.2.1 rounds Asr one minute later than the reference feed; one minute is acceptable. */
    private fun assertWithinOneMinute(expected: List<String>, actual: List<String>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (reference, calculated) ->
            val e = reference.substringBefore(':').toInt() * 60 + reference.substringAfter(':').toInt()
            val a = calculated.substringBefore(':').toInt() * 60 + calculated.substringAfter(':').toInt()
            assertTrue("$reference vs $calculated must differ by at most one minute", kotlin.math.abs(e - a) <= 1)
        }
    }

    @Test fun cairoMatchesTheKnownEgyptianTimetable() {
        assertWithinOneMinute(listOf("05:21", "12:05", "14:57", "17:18", "18:39"), clocks(30.0444, 31.2357))
    }

    @Test fun alexandriaMatchesTheKnownEgyptianTimetable() {
        assertWithinOneMinute(listOf("05:27", "12:10", "15:00", "17:20", "18:43"), clocks(31.2001, 29.9187))
    }

    @Test fun aswanMatchesTheKnownEgyptianTimetable() {
        assertWithinOneMinute(listOf("05:07", "11:58", "15:01", "17:22", "18:40"), clocks(24.0889, 32.8998))
    }

    @Test fun hanafiSchoolMovesAsrLaterAndLeavesTheOtherPrayersAlone() {
        val standard = clocks(30.0444, 31.2357)
        val hanafi = clocks(30.0444, 31.2357, egyptian.copy(asrSchool = AsrSchool.HANAFI))
        assertTrue(hanafi[2] > standard[2])
        assertEquals(standard.filterIndexed { i, _ -> i != 2 }, hanafi.filterIndexed { i, _ -> i != 2 })
    }

    @Test fun anotherMethodChangesFajrButKeepsFivePrayers() {
        val other = clocks(30.0444, 31.2357, egyptian.copy(method = "KARACHI"))
        assertEquals(5, other.size)
        assertFalse(other[0] == clocks(30.0444, 31.2357)[0])
    }

    @Test fun fineTuneShiftsOnlyTheSelectedPrayer() {
        val base = clocks(30.0444, 31.2357)
        val tuned = clocks(30.0444, 31.2357, PrayerAccuracy.withFineTune(egyptian, "maghrib", -3))
        assertEquals("17:15", tuned[3])
        assertEquals(base.filterIndexed { i, _ -> i != 3 }, tuned.filterIndexed { i, _ -> i != 3 })
    }

    @Test fun fineTuneIsClampedToThirtyMinutes() {
        val tuned = PrayerAccuracy.withFineTune(egyptian, "fajr", 500)
        assertEquals(30, tuned.fineTune["fajr"])
    }

    @Test fun gpsCacheIgnoresJitterButRefreshesOnMoveOrNewDay() {
        val today = "2025-01-15"
        assertFalse(PrayerCities.needsRefresh(30.0444, 31.2357, 30.0450, 31.2360, today, today))
        assertTrue(PrayerCities.needsRefresh(30.0444, 31.2357, 31.2001, 29.9187, today, today))
        assertTrue(PrayerCities.needsRefresh(30.0444, 31.2357, 30.0450, 31.2360, "2025-01-14", today))
        assertTrue(PrayerCities.needsRefresh(Double.NaN, 31.2357, 30.0444, 31.2357, today, today))
    }

    @Test fun manualEgyptianCitiesAreAvailableOffline() {
        val names = PrayerCities.EGYPT.map { it.name }
        assertTrue(names.containsAll(listOf("القاهرة", "الإسكندرية", "أسوان")))
        assertEquals(30.0444, PrayerCities.EGYPT.first { it.name == "القاهرة" }.latitude, 0.0)
    }

    @Test fun deviceZoneRulesCoverDstWithoutAFixedOffset() {
        val summer = ZoneId.of("Europe/Berlin")
        val winterOffset = LocalDate.of(2025, 1, 15).atStartOfDay(summer).offset.totalSeconds
        val summerOffset = LocalDate.of(2025, 7, 15).atStartOfDay(summer).offset.totalSeconds
        assertFalse("IANA zone must apply DST", winterOffset == summerOffset)
    }
}
