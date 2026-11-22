package com.focusstudy.poc

import android.content.Context
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * The single source of wall-clock time for MONAZZEM.
 *
 * An empty saved value means "follow the device" (the behaviour before Phase 4). A saved IANA id
 * such as `Africa/Cairo` survives travel and DST rule changes: prayer times, dhikr times and the
 * day boundaries used by reminders are all computed in that zone instead of the device zone.
 * The id is stored in the existing private preferences file under a new, additive key.
 * No network call and no location permission is involved.
 */
internal object AppTimeZone {
    internal const val PREF_KEY = "timezone_iana"

    /** Shown first when the picker has no query; the full IANA database is still searchable. */
    internal val SUGGESTED_IDS = listOf(
        "Africa/Cairo",
        "Asia/Riyadh",
        "Asia/Dubai",
        "Asia/Karachi",
        "Asia/Colombo",
        "Asia/Kuala_Lumpur",
        "Asia/Jerusalem",
        "Europe/London",
        "Europe/Paris",
        "America/New_York",
        "America/Los_Angeles",
        "Australia/Sydney"
    )

    fun deviceId(): String = ZoneId.systemDefault().id

    /** Returns the canonical id, or an empty string for a missing/invalid value. */
    fun sanitize(id: String?): String {
        val value = id?.trim().orEmpty()
        if (value.isEmpty()) return ""
        return runCatching { ZoneId.of(value).id }.getOrDefault("")
    }

    fun isValid(id: String?): Boolean = sanitize(id).isNotEmpty()

    /** Never throws on bad saved data: an invalid or empty id falls back to the device zone. */
    fun resolve(id: String?, fallback: ZoneId = ZoneId.systemDefault()): ZoneId {
        val valid = sanitize(id)
        return if (valid.isEmpty()) fallback else ZoneId.of(valid)
    }

    fun zone(context: Context): ZoneId {
        val raw = context.getSharedPreferences(FocusPreferences.FILE, Context.MODE_PRIVATE)
            .getString(PREF_KEY, null)
        return resolve(raw)
    }

    fun javaTimeZone(context: Context): TimeZone = TimeZone.getTimeZone(zone(context).id)

    fun availableIds(): List<String> = ZoneId.getAvailableZoneIds()
        .sortedWith(compareBy<String> { it.lowercase(Locale.ROOT) })

    fun displayLabel(id: String): String {
        val city = id.substringAfterLast('/').replace('_', ' ')
        return if (city == id) id else "$city · $id"
    }
}
