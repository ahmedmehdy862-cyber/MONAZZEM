package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part 9: the offline voice list and the bounded notification icon, without a device. */
class Part9AdhanVoicesTest {

    @Test fun allRequestedVoicesAreOffered() {
        assertEquals(
            listOf("default", "makkah", "madinah", "misr", "imadi", "hamathani", "afasy", "slimane", "qatami"),
            PrayerAdhanVoice.entries.map { it.key }
        )
        assertEquals("الافتراضي", PrayerAdhanVoice.DEFAULT.label)
        assertEquals("الحرم المكي", PrayerAdhanVoice.MAKKAH.label)
        assertEquals("المسجد النبوي", PrayerAdhanVoice.MADINAH.label)
        assertEquals("مصر", PrayerAdhanVoice.MISR.label)
        assertEquals("العمادي", PrayerAdhanVoice.IMADI.label)
        assertEquals("الحماثاني", PrayerAdhanVoice.HAMATHANI.label)
        assertEquals("العفاسي", PrayerAdhanVoice.AFASY.label)
        assertEquals("سليمان", PrayerAdhanVoice.SLIMANE.label)
        assertEquals("القطامي", PrayerAdhanVoice.QATAMI.label)
    }

    @Test fun everyDownloadedVoiceHasAStandaloneHint() {
        PrayerAdhanVoice.entries.forEach { voice ->
            assertTrue(voice.key, voice.hint.isNotBlank())
            assertTrue(voice.key, voice.label.isNotBlank())
        }
        assertTrue(PrayerAdhanVoice.QATAMI.hint.contains("بلا تلاوة"))
    }

    @Test fun unknownOrMissingVoiceFallsBackToTheUsersOwnAdhan() {
        assertEquals(PrayerAdhanVoice.DEFAULT, PrayerAdhanVoice.from(null))
        assertEquals(PrayerAdhanVoice.DEFAULT, PrayerAdhanVoice.from(""))
        assertEquals(PrayerAdhanVoice.DEFAULT, PrayerAdhanVoice.from("unknown"))
        assertEquals(PrayerAdhanVoice.MAKKAH, PrayerAdhanVoice.from("makkah"))
    }

    @Test fun voiceIsIndependentOfPerPrayerSoundRules() {
        val settings = PrayerAlertSettings().copy(voice = PrayerAdhanVoice.MISR)
        assertEquals(PrayerAdhanVoice.MISR, settings.voice)
        assertEquals(PrayerAdhanVoice.DEFAULT, PrayerAlertSettings().voice)
        assertEquals(PrayerAlertSound.ADHAN, settings.rule("fajr").atSound)
        assertEquals(PrayerAlertSound.TONE, settings.rule("fajr").beforeSound)
    }

    @Test fun notificationIconsAreSampledBeforeDecoding() {
        assertEquals(1, NotificationAvatar.sampleSize(96, 96))
        assertEquals(2, NotificationAvatar.sampleSize(200, 100))
        assertEquals(4, NotificationAvatar.sampleSize(512, 512))
        assertEquals(8, NotificationAvatar.sampleSize(1024, 768))
        assertEquals(2, NotificationAvatar.sampleSize(96, 96, maxPx = 64))
    }
}
