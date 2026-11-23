package com.focusstudy.poc

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class PrayerMoment(val name: String, val atMillis: Long)

internal object PrayerPrefs {
    private const val FILE = "prayer_preferences"
    // Phase 4 adds full-precision string copies. The old Float keys stay for backward compatibility.
    private const val LATITUDE_TEXT = "latitude_text"
    private const val LONGITUDE_TEXT = "longitude_text"
    private const val CITY_NAME = "city_name"
    private const val SAVED_AT = "location_saved_at"
    private const val SAVED_DATE = "location_saved_date"
    private const val ASR_SCHOOL = "asr_school"
    private const val TUNE_PREFIX = "prayer_tune_"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun coordinate(context: Context, textKey: String, floatKey: String, range: ClosedFloatingPointRange<Double>): Double {
        val prefs = prefs(context)
        return prefs.getString(textKey, null)?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it in range }
            ?: prefs.getFloat(floatKey, Float.NaN).toDouble()
    }

    fun latitude(context: Context) = coordinate(context, LATITUDE_TEXT, "latitude", -90.0..90.0)
    fun longitude(context: Context) = coordinate(context, LONGITUDE_TEXT, "longitude", -180.0..180.0)
    fun method(context: Context) = prefs(context).getString("method", "EGYPTIAN") ?: "EGYPTIAN"
    fun cityName(context: Context) = prefs(context).getString(CITY_NAME, "").orEmpty()
    fun savedDate(context: Context) = prefs(context).getString(SAVED_DATE, null)
    fun savedAt(context: Context) = prefs(context).getLong(SAVED_AT, 0L)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)

    fun asrSchool(context: Context): AsrSchool = AsrSchool.from(prefs(context).getString(ASR_SCHOOL, "STANDARD"))
    fun fineTune(context: Context, key: String): Int =
        prefs(context).getInt(TUNE_PREFIX + key, 0).coerceIn(-30, 30)

    fun settings(context: Context) = PrayerSettings(
        method = method(context),
        asrSchool = asrSchool(context),
        fineTune = PrayerAccuracy.KEYS.associateWith { fineTune(context, it) }
    )

    fun saveLocation(context: Context, lat: Double, lon: Double, cityName: String = "") {
        prefs(context).edit()
            .putString(LATITUDE_TEXT, lat.toString())
            .putString(LONGITUDE_TEXT, lon.toString())
            .putFloat("latitude", lat.toFloat())
            .putFloat("longitude", lon.toFloat())
            .putString(CITY_NAME, cityName)
            .putLong(SAVED_AT, System.currentTimeMillis())
            .putString(SAVED_DATE, LocalDate.now(AppTimeZone.zone(context)).toString())
            .apply()
    }
    fun saveMethod(context: Context, method: String) { prefs(context).edit().putString("method", method).apply() }
    fun saveAsrSchool(context: Context, school: AsrSchool) { prefs(context).edit().putString(ASR_SCHOOL, school.key).apply() }
    fun saveFineTune(context: Context, key: String, minutes: Int) {
        require(key in PrayerAccuracy.KEYS)
        prefs(context).edit().putInt(TUNE_PREFIX + key, minutes.coerceIn(-30, 30)).apply()
    }
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("enabled", enabled).apply() }

    /** True when a new GPS fix is justified after a move of ~25 km or a new local day. */
    fun needsLocationRefresh(context: Context, lat: Double, lon: Double, now: Long = System.currentTimeMillis()): Boolean =
        PrayerCities.needsRefresh(
            latitude(context), longitude(context), lat, lon,
            savedDate(context),
            java.time.Instant.ofEpochMilli(now).atZone(AppTimeZone.zone(context)).toLocalDate().toString()
        )
}

internal object PrayerCalculator {
    fun today(context: Context, date: Date = Date()): List<PrayerMoment> {
        val lat = PrayerPrefs.latitude(context)
        val lon = PrayerPrefs.longitude(context)
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
        // Adhan treats DateComponents as a calendar date in UTC, so the date we hand it must be the
        // local date in the user's selected zone — not the device zone. ZoneId also applies DST.
        val zone = AppTimeZone.zone(context)
        val localDate = Instant.ofEpochMilli(date.time).atZone(zone).toLocalDate()
        return PrayerAccuracy.calculate(lat, lon, localDate, PrayerPrefs.settings(context))
    }

    fun next(context: Context, now: Long = System.currentTimeMillis()): PrayerMoment? {
        val upcomingToday = today(context).firstOrNull { it.atMillis > now }
        if (upcomingToday != null) return upcomingToday
        // plusDays on a ZonedDateTime keeps the local date correct across DST transitions.
        val tomorrow = Instant.ofEpochMilli(now).atZone(AppTimeZone.zone(context)).plusDays(1)
        return today(context, Date(tomorrow.toInstant().toEpochMilli())).firstOrNull()
    }

    fun format(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        SimpleDateFormat("h:mm a", Locale("ar")).apply {
            timeZone = TimeZone.getTimeZone(zone.id)
        }.format(Date(millis))

    fun format(context: Context, millis: Long): String = format(millis, AppTimeZone.zone(context))
}

/**
 * Compatibility facade. Part 7 replaced the single next-prayer alarm with a rolling seven-day
 * window of independent AT and BEFORE alarms, but every existing caller keeps using this name.
 */
internal object PrayerReminderScheduler {
    private const val FIVE_MINUTES = 5 * 60_000L

    fun schedule(context: Context) = PrayerNotificationScheduler.schedule(context)

    /** A one-off reminder from the full-screen alert; the normal daily schedule is untouched. */
    fun snooze(context: Context, prayerName: String) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        NotificationMaintenance.schedule(
            context,
            manager,
            System.currentTimeMillis() + FIVE_MINUTES,
            PrayerNotificationScheduler.snoozePendingIntent(context, prayerName),
            kind = "prayer_snooze"
        )
    }

    fun notifyTest(context: Context) = PrayerNotificationScheduler.notifyTest(context, PrayerAlertType.AT)

    fun notifyPrayer(context: Context, prayerName: String?, test: Boolean = false) =
        PrayerNotificationScheduler.notifyPrayer(context, prayerName, test)
}

class PrayerReminderReceiver : BroadcastReceiver() {
    companion object { const val EXTRA_NAME = "prayer_name" }

    override fun onReceive(context: Context, intent: Intent?) {
        PrayerNotificationScheduler.handle(context, intent)
    }
}

internal suspend fun currentApproximateLocation(context: Context): android.location.Location? = suspendCancellableCoroutine { continuation ->
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    // Never touch the provider without the grant: on Android 10+ a missing permission throws.
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!coarse && !fine) {
        if (continuation.isActive) continuation.resume(null)
        return@suspendCancellableCoroutine
    }
    if (Build.VERSION.SDK_INT < 30) {
        @Suppress("DEPRECATION")
        val listener = android.location.LocationListener { location -> if (continuation.isActive) continuation.resume(location) }
        try {
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, android.os.Looper.getMainLooper())
        } catch (_: Exception) { if (continuation.isActive) continuation.resume(null) }
        return@suspendCancellableCoroutine
    }
    val provider = runCatching { if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else LocationManager.GPS_PROVIDER }.getOrNull()
    if (provider == null) { continuation.resume(null); return@suspendCancellableCoroutine }
    val cancellation = CancellationSignal()
    continuation.invokeOnCancellation { cancellation.cancel() }
    try {
        manager.getCurrentLocation(provider, cancellation, context.mainExecutor) { location -> if (continuation.isActive) continuation.resume(location) }
    } catch (_: SecurityException) { if (continuation.isActive) continuation.resume(null) }
}
