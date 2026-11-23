package com.focusstudy.poc

/**
 * Pure formatting for the "next prayer in ..." label. Kept out of the Composable so the Arabic
 * output and the hour/minute boundaries can be unit tested without Android.
 */
internal object PrayerCountdownFormat {

    fun label(minutesUntilPrayer: Long): String {
        val minutes = minutesUntilPrayer.coerceAtLeast(0)
        return when {
            minutes < 60 -> "بعد $minutes د"
            minutes % 60 == 0L -> "بعد ${minutes / 60} س"
            else -> "بعد ${minutes / 60} س و${minutes % 60} د"
        }
    }
}
