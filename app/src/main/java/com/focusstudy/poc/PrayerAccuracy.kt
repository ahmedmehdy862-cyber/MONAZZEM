package com.focusstudy.poc

import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.CalculationParameters
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.Madhab
import com.batoulapps.adhan.PrayerTimes as AdhanPrayerTimes
import com.batoulapps.adhan.data.DateComponents
import java.time.LocalDate

/** The school used for Asr. Egypt's official timetable uses the standard (Shafi'i) shadow length. */
internal enum class AsrSchool(val key: String, val label: String) {
    STANDARD("STANDARD", "الشافعي والمالكي والحنبلي"),
    HANAFI("HANAFI", "الحنفي");

    companion object {
        fun from(key: String?): AsrSchool = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

internal data class PrayerSettings(
    val method: String = "EGYPTIAN",
    val asrSchool: AsrSchool = AsrSchool.STANDARD,
    /** Signed minutes added to each of the five prayers by the user. */
    val fineTune: Map<String, Int> = emptyMap()
)

/**
 * Offline prayer-time calculation with explicit method/school selection and per-prayer tuning.
 * Adhan is the trusted astronomy engine; this file only wires the user's choices into it and keeps
 * the adjustments in one testable place.
 */
internal object PrayerAccuracy {
    val METHODS: List<Pair<String, String>> = listOf(
        "EGYPTIAN" to "المصرية (الهيئة العامة للمساحة)",
        "MUSLIM_WORLD_LEAGUE" to "رابطة العالم الإسلامي",
        "UMM_AL_QURA" to "أم القرى",
        "KARACHI" to "كراتشي"
    )
    val ASR_SCHOOLS: List<AsrSchool> = AsrSchool.entries

    /** Stable keys used both in preferences and in Adhan's adjustment list. */
    val KEYS = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")
    val NAMES = listOf("الفجر", "الظهر", "العصر", "المغرب", "العشاء")

    fun keyFor(prayerName: String): String =
        NAMES.indexOf(prayerName).takeIf { it >= 0 }?.let { KEYS[it] } ?: "fajr"

    /** Fresh parameters: Adhan's method singletons must never be mutated in place. */
    fun parameters(method: String, school: AsrSchool): CalculationParameters {
        val base = when (method) {
            "UMM_AL_QURA" -> CalculationMethod.UMM_AL_QURA.parameters
            "MUSLIM_WORLD_LEAGUE" -> CalculationMethod.MUSLIM_WORLD_LEAGUE.parameters
            "KARACHI" -> CalculationMethod.KARACHI.parameters
            else -> CalculationMethod.EGYPTIAN.parameters
        }
        val copy = if (base.ishaInterval > 0) {
            CalculationParameters(base.fajrAngle, base.ishaInterval, base.method)
        } else {
            CalculationParameters(base.fajrAngle, base.ishaAngle, base.method)
        }
        copy.highLatitudeRule = base.highLatitudeRule
        copy.madhab = if (school == AsrSchool.HANAFI) Madhab.HANAFI else Madhab.SHAFI
        return copy
    }

    /**
     * Calculates the five prayers for a local calendar date. The zone is used by the caller when
     * showing the instants, so DST transitions are handled by the device's IANA rules rather than
     * by a fixed UTC offset.
     */
    fun calculate(
        latitude: Double,
        longitude: Double,
        date: LocalDate,
        settings: PrayerSettings = PrayerSettings()
    ): List<PrayerMoment> {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        val components = DateComponents(date.year, date.monthValue, date.dayOfMonth)
        val prayers = AdhanPrayerTimes(
            Coordinates(latitude, longitude),
            components,
            parameters(settings.method, settings.asrSchool)
        )
        val instants = listOf(prayers.fajr, prayers.dhuhr, prayers.asr, prayers.maghrib, prayers.isha)
        return instants.mapIndexed { index, instant ->
            val minutes = settings.fineTune[KEYS[index]] ?: 0
            // Adhan keeps the current milliseconds on every computed instant, so two calls for the
            // same date can differ by a few ms. Prayer times are minute-precision: normalise to the
            // whole minute so rescheduling is deterministic and past/future comparisons are exact.
            val wholeMinute = Math.floorDiv(instant.time, 60_000L) * 60_000L
            PrayerMoment(NAMES[index], wholeMinute + minutes * 60_000L)
        }
    }

    /** Adds or removes minutes from one prayer without changing the other four. */
    fun withFineTune(settings: PrayerSettings, key: String, minutes: Int): PrayerSettings {
        require(key in KEYS)
        return settings.copy(fineTune = settings.fineTune + (key to minutes.coerceIn(-30, 30)))
    }
}
