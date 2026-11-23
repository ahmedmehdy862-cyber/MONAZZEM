package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Part 7: the two-alarm-per-prayer policy and the seven-day rolling window, without a device. */
class Part7PrayerNotificationsTest {

    private val date = LocalDate.of(2025, 1, 15)
    private val cairo = 30.0444 to 31.2357
    private val egyptian = PrayerSettings()

    private fun plan(
        alerts: PrayerAlertSettings = PrayerAlertSettings(),
        fromMillis: Long = 0L,
        days: Int = PrayerNotificationPlan.DAYS_AHEAD
    ) = PrayerNotificationPlan.build(
        latitude = cairo.first,
        longitude = cairo.second,
        prayer = egyptian,
        alerts = alerts,
        from = date,
        fromMillis = fromMillis,
        days = days
    )

    @Test fun everyPrayerStartsWithTwoIndependentAlerts() {
        val settings = PrayerAlertSettings()
        assertEquals(5, PrayerAccuracy.KEYS.size)
        PrayerAccuracy.KEYS.forEach { key ->
            val rule = settings.rule(key)
            assertTrue(rule.atEnabled)
            assertTrue(rule.hasBefore)
            assertEquals(10, rule.beforeMinutes)
            assertEquals(PrayerAlertSound.ADHAN, rule.atSound)
            assertEquals(PrayerAlertSound.TONE, rule.beforeSound)
        }
    }

    @Test fun sevenDayWindowHoldsTwoAlertsForEveryPrayerOfEveryDay() {
        val alarms = plan()
        assertEquals(7 * 5 * 2, alarms.size)
        assertEquals(7 * 5, alarms.count { it.type == PrayerAlertType.AT })
        assertEquals(7 * 5, alarms.count { it.type == PrayerAlertType.BEFORE })
        assertEquals(PrayerNotificationPlan.DAYS_AHEAD, alarms.maxOf { it.dayIndex } + 1)
    }

    @Test fun beforeAlarmFiresExactlyTheConfiguredMinutesBeforeThePrayer() {
        val alarms = plan()
        val before = alarms.first { it.key == "fajr" && it.type == PrayerAlertType.BEFORE }
        assertEquals(10 * 60_000L, before.prayerAt - before.fireAt)
        val at = alarms.first { it.key == "fajr" && it.type == PrayerAlertType.AT }
        assertEquals(before.prayerAt, at.prayerAt)
        assertEquals(at.prayerAt, at.fireAt)
    }

    @Test fun turningBeforeOffRemovesOnlyBeforeAlerts() {
        val off = PrayerAlertSettings().withRule("asr", PrayerAlertRule().copy(beforeMinutes = 0))
        val alarms = plan(off).filter { it.key == "asr" }
        assertEquals(7, alarms.size)
        assertTrue(alarms.all { it.type == PrayerAlertType.AT })
    }

    @Test fun turningAtOffRemovesOnlyAtAlerts() {
        val off = PrayerAlertSettings().withRule("maghrib", PrayerAlertRule().copy(atEnabled = false))
        val alarms = plan(off).filter { it.key == "maghrib" }
        assertEquals(7, alarms.size)
        assertTrue(alarms.all { it.type == PrayerAlertType.BEFORE })
    }

    @Test fun fiveAndFifteenMinuteOptionsAreHonouredIndependently() {
        var settings = PrayerAlertSettings()
        settings = settings.withRule("fajr", settings.rule("fajr").copy(beforeMinutes = 5))
        settings = settings.withRule("isha", settings.rule("isha").copy(beforeMinutes = 15))
        val alarms = plan(settings)
        val fajr = alarms.first { it.key == "fajr" && it.type == PrayerAlertType.BEFORE }
        val isha = alarms.first { it.key == "isha" && it.type == PrayerAlertType.BEFORE }
        assertEquals(5 * 60_000L, fajr.prayerAt - fajr.fireAt)
        assertEquals(15 * 60_000L, isha.prayerAt - isha.fireAt)
    }

    @Test fun soundsCanDifferBetweenTheTwoAlertsOfOnePrayer() {
        val settings = PrayerAlertSettings().withRule(
            "dhuhr",
            PrayerAlertRule().copy(atSound = PrayerAlertSound.TONE, beforeSound = PrayerAlertSound.ADHAN)
        )
        val rule = settings.rule("dhuhr")
        assertEquals(PrayerAlertSound.TONE, rule.atSound)
        assertEquals(PrayerAlertSound.ADHAN, rule.beforeSound)
        assertNotEquals(rule.atSound, rule.beforeSound)
        assertEquals(PrayerAlertSound.ADHAN, settings.rule("asr").atSound)
    }

    @Test fun pastAlarmsAreNotScheduled() {
        val firstPrayer = PrayerAccuracy.calculate(cairo.first, cairo.second, date, egyptian).first().atMillis
        val alarms = plan(fromMillis = firstPrayer)
        assertTrue(alarms.isNotEmpty())
        assertTrue(alarms.all { it.fireAt > firstPrayer })
        assertFalse(alarms.any { it.key == "fajr" && it.dayIndex == 0 })
    }

    @Test fun everyRequestCodeIsUniqueAndInsideTheCancelledRange() {
        val alarms = plan()
        val codes = alarms.map { it.requestCode }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it in PrayerNotificationPlan.CANCEL_RANGE })
        assertEquals(
            PrayerNotificationPlan.requestCode(0, 0, PrayerAlertType.AT),
            PrayerNotificationPlan.requestCode(0, 0, PrayerAlertType.AT)
        )
        assertNotEquals(
            PrayerNotificationPlan.requestCode(0, 0, PrayerAlertType.AT),
            PrayerNotificationPlan.requestCode(0, 0, PrayerAlertType.BEFORE)
        )
    }

    @Test fun masterSwitchOffCancelsTheWholeWindow() {
        assertTrue(plan(PrayerAlertSettings(enabled = false)).isEmpty())
    }

    @Test fun invalidBeforeValuesAreNormalisedToTheOfferedOptions() {
        assertEquals(0, PrayerAlertRule.before(0))
        assertEquals(5, PrayerAlertRule.before(5))
        assertEquals(10, PrayerAlertRule.before(10))
        assertEquals(15, PrayerAlertRule.before(15))
        assertEquals(5, PrayerAlertRule.before(3))
        assertEquals(15, PrayerAlertRule.before(12))
        assertEquals(10, PrayerAlertRule.before(99))
        assertEquals(PrayerAlertRule.BEFORE_OPTIONS, listOf(0, 5, 10, 15))
    }

    @Test fun alertsAreChronologicallyOrderedAcrossTheWindow() {
        val alarms = plan()
        assertEquals(alarms.sortedBy { it.fireAt }, alarms)
    }

    @Test fun automaticFullScreenHappensOnlyAtTheAdhanItself() {
        assertTrue(PrayerAlertPolicy.automaticFullScreen(PrayerAlertType.AT))
        assertFalse(PrayerAlertPolicy.automaticFullScreen(PrayerAlertType.BEFORE))
        assertTrue(PrayerAlertPolicy.launchesActivity(PrayerAlertType.AT))
        assertFalse(PrayerAlertPolicy.launchesActivity(PrayerAlertType.BEFORE))
    }

    @Test fun onlyTheAtAdhanPlaysFromTheUninterruptibleService() {
        assertTrue(AdhanPlaybackService.shouldPlay(PrayerAlertType.AT, PrayerAlertSound.ADHAN))
        assertFalse(AdhanPlaybackService.shouldPlay(PrayerAlertType.AT, PrayerAlertSound.TONE))
        assertFalse(AdhanPlaybackService.shouldPlay(PrayerAlertType.BEFORE, PrayerAlertSound.ADHAN))
        assertFalse(AdhanPlaybackService.shouldPlay(PrayerAlertType.BEFORE, PrayerAlertSound.TONE))
    }

    @Test fun screenLaunchAlarmsNeverCollideWithBroadcastSlots() {
        val alarms = plan()
        val broadcastCodes = alarms.map { it.requestCode }.toSet()
        val screenCodes = alarms.filter { it.type == PrayerAlertType.AT }
            .map { PrayerNotificationPlan.screenRequestCode(it.requestCode) }
        assertEquals(7 * 5, screenCodes.size)
        assertEquals(screenCodes.size, screenCodes.toSet().size)
        assertTrue(screenCodes.none { it in broadcastCodes })
        assertTrue(screenCodes.all { it in PrayerNotificationPlan.CANCEL_RANGE })
    }
}
