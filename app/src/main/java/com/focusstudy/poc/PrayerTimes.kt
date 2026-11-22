package com.focusstudy.poc

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import java.text.SimpleDateFormat
import java.time.Instant
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
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun saveLocation(context: Context, lat: Double, lon: Double) {
        prefs(context).edit()
            .putString(LATITUDE_TEXT, lat.toString())
            .putString(LONGITUDE_TEXT, lon.toString())
            .putFloat("latitude", lat.toFloat())
            .putFloat("longitude", lon.toFloat())
            .apply()
    }
    fun saveMethod(context: Context, method: String) { prefs(context).edit().putString("method", method).apply() }
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("enabled", enabled).apply() }
}

internal object PrayerCalculator {
    private fun parameters(method: String) = when (method) {
        "UMM_AL_QURA" -> CalculationMethod.UMM_AL_QURA.parameters
        "MUSLIM_WORLD_LEAGUE" -> CalculationMethod.MUSLIM_WORLD_LEAGUE.parameters
        "KARACHI" -> CalculationMethod.KARACHI.parameters
        else -> CalculationMethod.EGYPTIAN.parameters
    }

    fun today(context: Context, date: Date = Date()): List<PrayerMoment> {
        val lat = PrayerPrefs.latitude(context)
        val lon = PrayerPrefs.longitude(context)
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
        // Adhan treats DateComponents as a calendar date in UTC, so the date we hand it must be the
        // local date in the user's selected zone — not the device zone.
        val localDate = Instant.ofEpochMilli(date.time).atZone(AppTimeZone.zone(context)).toLocalDate()
        val components = DateComponents(localDate.year, localDate.monthValue, localDate.dayOfMonth)
        val prayers = PrayerTimes(Coordinates(lat, lon), components, parameters(PrayerPrefs.method(context)))
        return listOf(
            PrayerMoment("الفجر", prayers.fajr.time), PrayerMoment("الظهر", prayers.dhuhr.time),
            PrayerMoment("العصر", prayers.asr.time), PrayerMoment("المغرب", prayers.maghrib.time),
            PrayerMoment("العشاء", prayers.isha.time)
        )
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

internal object PrayerReminderScheduler {
    private const val ACTION = "com.focusstudy.poc.PRAYER_REMINDER"
    private const val REQUEST = 6201
    private const val SNOOZE_REQUEST = 6203
    private const val NOTIFICATION = 6202
    private const val EXTRA_PRAYER = "prayer_name"
    private const val FIVE_MINUTES = 5 * 60_000L

    fun schedule(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val baseIntent = Intent(context, PrayerReminderReceiver::class.java).setAction(ACTION)
        val pending = PendingIntent.getBroadcast(context, REQUEST, baseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (!PrayerPrefs.enabled(context)) { manager.cancel(pending); return }
        val next = runCatching { PrayerCalculator.next(context) }.getOrNull() ?: run { manager.cancel(pending); return }
        baseIntent.putExtra(EXTRA_PRAYER, next.name)
        val updatedPending = PendingIntent.getBroadcast(context, REQUEST, baseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setAlarm(context, manager, next.atMillis, updatedPending)
    }

    /** A one-off reminder from the full-screen alert; the normal daily schedule is untouched. */
    fun snooze(context: Context, prayerName: String) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, PrayerReminderReceiver::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_PRAYER, prayerName)
        val pending = PendingIntent.getBroadcast(
            context, SNOOZE_REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setAlarm(context, manager, System.currentTimeMillis() + FIVE_MINUTES, pending)
    }

    fun notifyTest(context: Context) {
        val nextName = runCatching { PrayerCalculator.next(context)?.name }.getOrNull()
        notifyPrayer(context, nextName ?: "الفجر", test = true)
    }

    fun notifyPrayer(context: Context, prayerName: String?, test: Boolean = false) {
        val name = prayerName?.takeIf { it.isNotBlank() } ?: "الصلاة"
        AppNotifications.ensure(context, AppNotifications.PRAYER)
        val now = System.currentTimeMillis()
        val displayAt = if (test) runCatching { PrayerCalculator.next(context)?.atMillis }.getOrNull() ?: now else now
        if (AppNotifications.canPost(context)) {
            val content = alertPendingIntent(context, name, displayAt, test, NOTIFICATION + 1)
            val fullScreen = alertPendingIntent(context, name, displayAt, test, NOTIFICATION + 2)
            val title = if (test) "هكذا يصل أذان مُنظِّم" else "حان وقت صلاة $name"
            val body = if (test) "اضغط لعرض شاشة التنبيه كما تظهر عند وقت الصلاة."
                else "افتح شاشة الأذان، أو اضغط «ذكّرني بعد ٥ دقائق»."
            val notification = AppNotifications.builder(
                context = context,
                channel = AppNotifications.PRAYER,
                title = title,
                message = body,
                contentIntent = content,
                fullScreenIntent = fullScreen
            ).build()
            AppNotifications.manager(context).notify(NOTIFICATION, notification)
        }
        schedule(context)
    }

    private fun alertPendingIntent(
        context: Context,
        prayerName: String,
        atMillis: Long,
        test: Boolean,
        request: Int
    ): PendingIntent = PendingIntent.getActivity(
        context,
        request,
        Intent(context, PrayerAlertActivity::class.java)
            .putExtra(PrayerAlertActivity.EXTRA_PRAYER_NAME, prayerName)
            .putExtra(PrayerAlertActivity.EXTRA_AT_MILLIS, atMillis)
            .putExtra(PrayerAlertActivity.EXTRA_TEST, test)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun setAlarm(context: Context, manager: AlarmManager, atMillis: Long, pending: PendingIntent) {
        NotificationMaintenance.schedule(context, manager, atMillis, pending, kind = "prayer")
    }
}

class PrayerReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "com.focusstudy.poc.PRAYER_REMINDER") PrayerReminderScheduler.notifyPrayer(context, intent.getStringExtra("prayer_name"))
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
