package com.focusstudy.poc

import android.content.Context
import android.media.MediaPlayer

/**
 * The adhan voices offered on the device.
 *
 * Every clip is packaged in the APK: nothing is streamed, and the choice stays private in
 * `prayer_alert_preferences`. `PrayerAdhanVoice` itself is plain data so the key mapping can be
 * unit tested without an Android runtime; only [PrayerAdhanSounds] touches resources and audio.
 *
 * The recordings are standalone adhan clips: the mosque field recordings end in the silence
 * before any Quran recitation or iqama, and the reciter recordings contain no prayer at all.
 * Every clip is normalised to the same peak so the voice, not the file, is what changes.
 */
internal enum class PrayerAdhanVoice(
    val key: String,
    val label: String,
    val hint: String
) {
    DEFAULT("default", "الافتراضي", "الصوت الذي أضفته بنفسك — محفوظ على جهازك كما هو"),
    MAKKAH("makkah", "الحرم المكي", "أذان الحرم المكي — الشيخ الغامدي"),
    MADINAH("madinah", "المسجد النبوي", "أذان المسجد النبوي — الشيخ الحذيفي"),
    MISR("misr", "مصر", "أذان من مصر — عبد الناصر حراك"),
    IMADI("imadi", "العمادي", "أذان الشيخ أحمد العمادي — تسجيل مستقل بلا تلاوة"),
    HAMATHANI("hamathani", "الحماثاني", "أذان الشيخ ماجد الحماثاني — تسجيل مستقل بلا تلاوة"),
    AFASY("afasy", "العفاسي", "أذان الشيخ مشاري العفاسي — تسجيل مستقل بلا تلاوة"),
    SLIMANE("slimane", "سليمان", "أذان الشيخ مختار حاج سليمان — تسجيل مستقل بلا تلاوة"),
    QATAMI("qatami", "القطامي", "أذان الشيخ ناصر القطامي — تسجيل مستقل بلا تلاوة");

    companion object {
        fun from(key: String?): PrayerAdhanVoice = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

internal object PrayerAdhanSounds {
    /** Called on the main thread from the settings row. One preview at a time keeps audio calm. */
    private var active: MediaPlayer? = null

    fun resource(voice: PrayerAdhanVoice): Int = when (voice) {
        PrayerAdhanVoice.DEFAULT -> R.raw.adhan_local
        PrayerAdhanVoice.MAKKAH -> R.raw.adhan_makkah
        PrayerAdhanVoice.MADINAH -> R.raw.adhan_madinah
        PrayerAdhanVoice.MISR -> R.raw.adhan_misr
        PrayerAdhanVoice.IMADI -> R.raw.adhan_imadi
        PrayerAdhanVoice.HAMATHANI -> R.raw.adhan_hamathani
        PrayerAdhanVoice.AFASY -> R.raw.adhan_afasy
        PrayerAdhanVoice.SLIMANE -> R.raw.adhan_slimane
        PrayerAdhanVoice.QATAMI -> R.raw.adhan_qatami
    }

    fun preview(context: Context, voice: PrayerAdhanVoice) {
        stopPreview()
        active = runCatching {
            MediaPlayer.create(context, resource(voice))?.apply {
                setOnCompletionListener { player ->
                    player.release()
                    if (active === player) active = null
                }
                setOnErrorListener { player, _, _ ->
                    player.release()
                    if (active === player) active = null
                    true
                }
                start()
            }
        }.getOrNull()
    }

    /**
     * A preview must never overlap the real adhan: both use the same packaged recording and two
     * players at once sound like a distorted, doubled call. The playback service calls this before
     * it starts, and the settings screen calls it when it leaves composition.
     */
    fun stopPreview() {
        active?.runCatching {
            setOnCompletionListener(null)
            setOnErrorListener(null)
            stop()
            release()
        }
        active = null
    }
}
