package com.focusstudy.poc

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class PrayerCity(val name: String, val latitude: Double, val longitude: Double)

/** A small offline city list for manual fallback; coordinates match the three verified cities. */
internal object PrayerCities {
    const val SIGNIFICANT_MOVE_KM = 25.0

    val EGYPT = listOf(
        PrayerCity("القاهرة", 30.0444, 31.2357),
        PrayerCity("الجيزة", 30.0131, 31.2089),
        PrayerCity("الإسكندرية", 31.2001, 29.9187),
        PrayerCity("أسيوط", 27.1809, 31.1837),
        PrayerCity("المنيا", 28.1099, 30.7503),
        PrayerCity("سوهاج", 26.5569, 31.7617),
        PrayerCity("قنا", 26.1551, 32.7160),
        PrayerCity("الأقصر", 25.6872, 32.6396),
        PrayerCity("أسوان", 24.0889, 32.8998),
        PrayerCity("بورسعيد", 31.2653, 32.3019),
        PrayerCity("الإسماعيلية", 30.5965, 32.2715),
        PrayerCity("السويس", 29.9668, 32.5498),
        PrayerCity("طنطا", 30.7833, 30.9970),
        PrayerCity("المنصورة", 31.0366, 31.3807),
        PrayerCity("الزقازيق", 30.5876, 31.5020)
    )

    /** Haversine distance in kilometres; pure so the movement threshold can be tested. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadius * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Coordinates are cached from one GPS fix. Ask again only after a real move or a new local day;
     * a tiny GPS jitter must not trigger a refresh, and a new date must.
     */
    fun needsRefresh(
        savedLat: Double,
        savedLon: Double,
        currentLat: Double,
        currentLon: Double,
        savedDate: String?,
        today: String
    ): Boolean {
        if (!savedLat.isFinite() || !savedLon.isFinite()) return true
        if (savedDate != today) return true
        return distanceKm(savedLat, savedLon, currentLat, currentLon) > SIGNIFICANT_MOVE_KM
    }
}
