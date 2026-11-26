package com.focusstudy.poc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.time.LocalDate

/**
 * Part 7: two independent, exact alarms per prayer.
 *
 * Everything that can be decided without a phone is kept in pure data classes below so the
 * scheduling policy is unit-testable. The Android-facing object only reads preferences, asks
 * [NotificationMaintenance] for an exact alarm and posts the notification. No network, no new
 * dependency, and no change to the existing prayer-time calculation.
 */

internal enum class PrayerAlertType(val key: String, val label: String) {
    AT("at", "عند وقت الصلاة"),
    BEFORE("before", "قبل الصلاة");

    companion object {
        fun from(key: String?): PrayerAlertType = entries.firstOrNull { it.key == key } ?: AT
    }
}

internal enum class PrayerAlertSound(val key: String, val label: String) {
    ADHAN("adhan", "أذان"),
    TONE("tone", "نغمة قصيرة");

    companion object {
        fun from(key: String?): PrayerAlertSound = entries.firstOrNull { it.key == key } ?: ADHAN
    }
}

/** One prayer has its own AT switch/sound and its own BEFORE offset/sound. */
internal data class PrayerAlertRule(
    val atEnabled: Boolean = true,
    val beforeMinutes: Int = 10,
    val atSound: PrayerAlertSound = PrayerAlertSound.ADHAN,
    val beforeSound: PrayerAlertSound = PrayerAlertSound.TONE
) {
    val hasBefore: Boolean get() = beforeMinutes in BEFORE_OPTIONS && beforeMinutes > 0

    companion object {
        /** 0 means off; the prompt asks for 5 / 10 / 15 minutes and a default of 10. */
        val BEFORE_OPTIONS = listOf(0, 5, 10, 15)
        const val DEFAULT_BEFORE = 10

        fun before(value: Int): Int =
            if (value in BEFORE_OPTIONS) value else if (value > 0 && value < 5) 5 else if (value in 6..9) 10 else if (value in 11..14) 15 else DEFAULT_BEFORE
    }
}

internal data class PrayerAlertSettings(
    val enabled: Boolean = true,
    val rules: Map<String, PrayerAlertRule> = emptyMap(),
    val voice: PrayerAdhanVoice = PrayerAdhanVoice.DEFAULT
) {
    fun rule(key: String): PrayerAlertRule = rules[key] ?: PrayerAlertRule()

    fun withRule(key: String, rule: PrayerAlertRule): PrayerAlertSettings =
        copy(rules = rules + (key to rule))
}

/**
 * What is allowed to take over the screen.
 *
 * Only the AT alarm opens the centred alert: it is the adhan, not the iqama and not the optional
 * "before" nudge. Quiet hours never gate prayer alarms, so the adhan still arrives at its time when
 * the person has switched quiet-hours respect off (or on).
 */
internal object PrayerAlertPolicy {
    fun automaticFullScreen(type: PrayerAlertType): Boolean = type == PrayerAlertType.AT
    fun launchesActivity(type: PrayerAlertType): Boolean = type == PrayerAlertType.AT
}

/** A fully described alarm; [fireAt] is when the alarm fires, [prayerAt] is the prayer time. */
internal data class PrayerAlarm(
    val key: String,
    val name: String,
    val type: PrayerAlertType,
    val fireAt: Long,
    val prayerAt: Long,
    val dayIndex: Int,
    val requestCode: Int
)

internal object PrayerNotificationPlan {
    /** A rolling window: even if the app is never opened, the next week is already scheduled. */
    const val DAYS_AHEAD = 7
    const val FIRST_REQUEST = 7000
    const val REQUEST_SPAN = 100
    /** Slot 2 of each prayer is the direct screen launch for the AT alarm; 0/1 stay broadcasts. */
    const val ACTIVITY_SLOT = 2
    val CANCEL_RANGE = FIRST_REQUEST until FIRST_REQUEST + (DAYS_AHEAD + 2) * REQUEST_SPAN

    fun requestCode(dayIndex: Int, prayerIndex: Int, type: PrayerAlertType): Int =
        FIRST_REQUEST + dayIndex * REQUEST_SPAN + prayerIndex * 10 + if (type == PrayerAlertType.AT) 0 else 1

    /** The screen alarm shares the prayer's ten-slot block but never collides with its broadcast. */
    fun screenRequestCode(alarmRequestCode: Int): Int = alarmRequestCode + ACTIVITY_SLOT

    /**
     * Builds the whole rolling window from pure inputs. [fromMillis] suppresses alarms that are
     * already in the past, including a BEFORE reminder whose prayer is still ahead.
     */
    fun build(
        latitude: Double,
        longitude: Double,
        prayer: PrayerSettings,
        alerts: PrayerAlertSettings,
        from: LocalDate,
        fromMillis: Long,
        days: Int = DAYS_AHEAD
    ): List<PrayerAlarm> {
        if (!alerts.enabled) return emptyList()
        val result = mutableListOf<PrayerAlarm>()
        repeat(days.coerceIn(1, DAYS_AHEAD)) { dayIndex ->
            val date = from.plusDays(dayIndex.toLong())
            PrayerAccuracy.calculate(latitude, longitude, date, prayer).forEachIndexed { prayerIndex, moment ->
                val rule = alerts.rule(moment.keyForPrayer())
                if (rule.atEnabled && moment.atMillis > fromMillis) {
                    result += PrayerAlarm(
                        key = moment.keyForPrayer(),
                        name = moment.name,
                        type = PrayerAlertType.AT,
                        fireAt = moment.atMillis,
                        prayerAt = moment.atMillis,
                        dayIndex = dayIndex,
                        requestCode = requestCode(dayIndex, prayerIndex, PrayerAlertType.AT)
                    )
                }
                if (rule.hasBefore) {
                    val fireAt = moment.atMillis - rule.beforeMinutes * 60_000L
                    if (fireAt > fromMillis) {
                        result += PrayerAlarm(
                            key = moment.keyForPrayer(),
                            name = moment.name,
                            type = PrayerAlertType.BEFORE,
                            fireAt = fireAt,
                            prayerAt = moment.atMillis,
                            dayIndex = dayIndex,
                            requestCode = requestCode(dayIndex, prayerIndex, PrayerAlertType.BEFORE)
                        )
                    }
                }
            }
        }
        return result.sortedBy { it.fireAt }
    }
}

/** Maps a displayed prayer name back to its stable preference key. */
private fun PrayerMoment.keyForPrayer(): String = PrayerAccuracy.keyFor(name)

internal object PrayerAlertPrefs {
    private const val FILE = "prayer_alert_preferences"
    private const val AT = "at_"
    private const val BEFORE = "before_"
    private const val AT_SOUND = "at_sound_"
    private const val BEFORE_SOUND = "before_sound_"
    private const val VOICE = "adhan_voice"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context): PrayerAlertSettings {
        val values = prefs(context)
        val rules = PrayerAccuracy.KEYS.associateWith { key ->
            PrayerAlertRule(
                atEnabled = values.getBoolean(AT + key, true),
                beforeMinutes = PrayerAlertRule.before(values.getInt(BEFORE + key, PrayerAlertRule.DEFAULT_BEFORE)),
                atSound = PrayerAlertSound.from(values.getString(AT_SOUND + key, PrayerAlertSound.ADHAN.key)),
                beforeSound = PrayerAlertSound.from(values.getString(BEFORE_SOUND + key, PrayerAlertSound.TONE.key))
            )
        }
        // The long-standing master switch in prayer settings stays authoritative for focus windows
        // as well as alerts, so turning the feature off in either place cannot leave stray alarms.
        return PrayerAlertSettings(
            enabled = PrayerPrefs.enabled(context),
            rules = rules,
            voice = PrayerAdhanVoice.from(values.getString(VOICE, PrayerAdhanVoice.DEFAULT.key))
        )
    }

    fun setAtEnabled(context: Context, key: String, enabled: Boolean) {
        require(key in PrayerAccuracy.KEYS)
        prefs(context).edit().putBoolean(AT + key, enabled).apply()
    }

    fun setBeforeMinutes(context: Context, key: String, minutes: Int) {
        require(key in PrayerAccuracy.KEYS)
        prefs(context).edit().putInt(BEFORE + key, PrayerAlertRule.before(minutes)).apply()
    }

    fun setSound(context: Context, key: String, type: PrayerAlertType, sound: PrayerAlertSound) {
        require(key in PrayerAccuracy.KEYS)
        val field = if (type == PrayerAlertType.AT) AT_SOUND else BEFORE_SOUND
        prefs(context).edit().putString(field + key, sound.key).apply()
    }

    /** One voice for every adhan in the app; the short reminder tone is unaffected. */
    fun setVoice(context: Context, voice: PrayerAdhanVoice) {
        prefs(context).edit().putString(VOICE, voice.key).apply()
        AppNotifications.refreshChannels(context)
    }
}

internal object PrayerNotificationScheduler {
    private const val ACTION_AT = "com.focusstudy.poc.PRAYER_AT"
    private const val ACTION_BEFORE = "com.focusstudy.poc.PRAYER_BEFORE"
    private const val LEGACY_ACTION = "com.focusstudy.poc.PRAYER_REMINDER"
    const val EXTRA_KEY = "prayer_key"
    const val EXTRA_TYPE = "prayer_alert_type"
    const val EXTRA_PRAYER_AT = "prayer_time_millis"
    private const val LEGACY_REQUEST = 6201
    private const val SNOOZE_REQUEST = 6203
    private const val AT_NOTIFICATION = 6202
    private const val BEFORE_NOTIFICATION = 6204
    private const val TEST_SCREEN_REQUEST = 6311
    /** Request code for the «إيقاف الأذان» action on the AT notification itself. */
    private const val STOP_REQUEST = 6312

    /** Rebuilds the seven-day window. Safe to call from boot, time change, app update or settings. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelAll(app, manager)
        val lat = PrayerPrefs.latitude(app)
        val lon = PrayerPrefs.longitude(app)
        if (!lat.isFinite() || !lon.isFinite()) return
        val zone = AppTimeZone.zone(app)
        val now = System.currentTimeMillis()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val alarms = PrayerNotificationPlan.build(
            latitude = lat,
            longitude = lon,
            prayer = PrayerPrefs.settings(app),
            alerts = PrayerAlertPrefs.load(app),
            from = today,
            fromMillis = now
        )
        alarms.forEach { alarm -> setAlarm(app, manager, alarm) }
    }

    /** Cancels the old single-alarm shape and every slot in the rolling window before rewriting it. */
    private fun cancelAll(context: Context, manager: AlarmManager) {
        val legacy = PendingIntent.getBroadcast(
            context, LEGACY_REQUEST,
            Intent(context, PrayerReminderReceiver::class.java).setAction(LEGACY_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.cancel(legacy)
        // A broadcast PendingIntent is identified by its action as well as its request code, so
        // both kinds must be cancelled explicitly before the window is rewritten.
        PrayerNotificationPlan.CANCEL_RANGE.forEach { request ->
            listOf(ACTION_AT, ACTION_BEFORE).forEach { action ->
                manager.cancel(
                    PendingIntent.getBroadcast(
                        context, request,
                        Intent(context, PrayerReminderReceiver::class.java).setAction(action),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
            // The AT alarm also has a direct screen launch in the next slot.
            manager.cancel(
                PendingIntent.getActivity(
                    context,
                    PrayerNotificationPlan.screenRequestCode(request),
                    Intent(context, PrayerAlertActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
    }

    private fun setAlarm(context: Context, manager: AlarmManager, alarm: PrayerAlarm) {
        val action = if (alarm.type == PrayerAlertType.AT) ACTION_AT else ACTION_BEFORE
        val intent = Intent(context, PrayerReminderReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_KEY, alarm.key)
            .putExtra(PrayerReminderReceiver.EXTRA_NAME, alarm.name)
            .putExtra(EXTRA_TYPE, alarm.type.key)
            .putExtra(EXTRA_PRAYER_AT, alarm.prayerAt)
        val pending = PendingIntent.getBroadcast(
            context, alarm.requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        NotificationMaintenance.schedule(
            context, manager, alarm.fireAt, pending,
            kind = "prayer_${alarm.type.key}"
        )
        // Android lets the system start an activity from an alarm PendingIntent even while the app
        // is in the background. This is what makes the centred adhan screen appear by itself at the
        // exact prayer time; the notification's full-screen intent remains the fallback.
        if (PrayerAlertPolicy.launchesActivity(alarm.type)) {
            NotificationMaintenance.schedule(
                context, manager, alarm.fireAt,
                screenPendingIntent(context, alarm),
                kind = "prayer_screen"
            )
        }
    }

    private fun screenPendingIntent(context: Context, alarm: PrayerAlarm): PendingIntent =
        PendingIntent.getActivity(
            context,
            PrayerNotificationPlan.screenRequestCode(alarm.requestCode),
            Intent(context, PrayerAlertActivity::class.java)
                .putExtra(PrayerAlertActivity.EXTRA_PRAYER_NAME, alarm.name)
                .putExtra(PrayerAlertActivity.EXTRA_PRAYER_KEY, alarm.key)
                .putExtra(PrayerAlertActivity.EXTRA_AT_MILLIS, alarm.prayerAt)
                .putExtra(PrayerAlertActivity.EXTRA_TYPE, alarm.type.key)
                .putExtra(PrayerAlertActivity.EXTRA_VOICE, PrayerAlertPrefs.load(context).voice.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun handle(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            ACTION_AT, ACTION_BEFORE -> {
                val type = PrayerAlertType.from(intent.getStringExtra(EXTRA_TYPE))
                val key = intent.getStringExtra(EXTRA_KEY) ?: PrayerAccuracy.KEYS.first()
                val name = intent.getStringExtra(PrayerReminderReceiver.EXTRA_NAME).takeUnless { it.isNullOrBlank() }
                    ?: PrayerAccuracy.NAMES[PrayerAccuracy.KEYS.indexOf(key).coerceAtLeast(0)]
                val prayerAt = intent.getLongExtra(EXTRA_PRAYER_AT, System.currentTimeMillis())
                notify(context, name, key, type, prayerAt, test = false)
                // Keep a full window alive even if the app is never opened again.
                runCatching { schedule(context.applicationContext) }
            }
            LEGACY_ACTION -> notifyPrayer(context, intent.getStringExtra(PrayerReminderReceiver.EXTRA_NAME), test = false)
        }
    }

    /** Compatibility entry point used by the old facade and the snooze flow. */
    fun notifyPrayer(context: Context, prayerName: String?, test: Boolean) {
        val name = prayerName?.takeIf { it.isNotBlank() } ?: PrayerAccuracy.NAMES.first()
        val key = PrayerAccuracy.keyFor(name)
        notify(context, name, key, PrayerAlertType.AT, System.currentTimeMillis(), test)
    }

    fun notifyTest(context: Context, type: PrayerAlertType) {
        val next = runCatching { PrayerCalculator.next(context) }.getOrNull()
        val name = next?.name ?: PrayerAccuracy.NAMES.first()
        val key = PrayerAccuracy.keyFor(name)
        val atMillis = next?.atMillis ?: System.currentTimeMillis()
        notify(context, name, key, type, atMillis, test = true)
        // The real AT alert is opened by a system alarm holding an activity PendingIntent; some
        // OEMs ignore the notification's full-screen intent. Fire that same launch here so «جرّب
        // الآن» previews the centred screen a person actually sees at prayer time.
        if (type == PrayerAlertType.AT) {
            val launch = PendingIntent.getActivity(
                context,
                TEST_SCREEN_REQUEST,
                alertActivityIntent(context, name, key, atMillis, true, type, PrayerAlertPrefs.load(context).voice),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching { launch.send() }
        }
    }

    private fun notify(
        context: Context,
        name: String,
        key: String,
        type: PrayerAlertType,
        prayerAt: Long,
        test: Boolean
    ) {
        AppNotifications.ensure(context, channel(type))
        if (!AppNotifications.canPost(context)) return
        val settings = PrayerAlertPrefs.load(context)
        val rule = settings.rule(key)
        val sound = if (type == PrayerAlertType.AT) rule.atSound else rule.beforeSound
        val voice = settings.voice
        val channel = channel(type)
        // The full adhan plays from a foreground service with an exclusive alarm focus, so no
        // later notification can duck or cut it. The notification itself stays audible-free to
        // avoid playing the same recording twice.
        val playInService = AdhanPlaybackService.shouldPlay(type, sound)
        if (playInService) AdhanPlaybackService.play(context, name, voice)
        val title = if (type == PrayerAlertType.AT) "حان وقت صلاة $name" else "قبل صلاة $name"
        val body = if (type == PrayerAlertType.AT) {
            "الأذان الآن. الصوت: ${voice.label}. اضغط «ذكّرني بعد ٥ دقائق» إن أردت."
        } else {
            rule.beforeMinutes.let { "يبدأ وقت $name بعد $it دقائق." }
        }
        val notificationId = if (type == PrayerAlertType.AT) AT_NOTIFICATION else BEFORE_NOTIFICATION
        val content = alertPendingIntent(context, name, key, prayerAt, test, notificationId + 1, type, voice)
        val fullScreen = if (PrayerAlertPolicy.automaticFullScreen(type)) {
            alertPendingIntent(context, name, key, prayerAt, test, notificationId + 2, type, voice)
        } else null
        val notification = AppNotifications.builder(
            context = context,
            channel = channel,
            title = title,
            message = body,
            contentIntent = content,
            fullScreenIntent = fullScreen,
            largeIcon = NotificationAvatar.decode(FocusPreferences.load(context).avatarPath),
            soundRes = if (sound == PrayerAlertSound.ADHAN) PrayerAdhanSounds.resource(voice) else R.raw.notification_chime,
            soundIsAlarm = sound == PrayerAlertSound.ADHAN,
            silent = playInService
        ).apply {
            // One tap in the shade stops the adhan itself, like the big button on the
            // full-screen alert and the action on the service's ongoing notification:
            // the person should not have to open the app to silence the call.
            if (playInService) {
                addAction(
                    R.drawable.ic_stat_focus,
                    "إيقاف الأذان",
                    PendingIntent.getService(
                        context,
                        STOP_REQUEST,
                        Intent(context, AdhanPlaybackService::class.java)
                            .setAction(AdhanPlaybackService.ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        }.build()
        AppNotifications.manager(context).notify(notificationId, notification)
    }

    private fun channel(type: PrayerAlertType) = if (type == PrayerAlertType.AT) AppNotifications.PRAYER_AT else AppNotifications.PRAYER_BEFORE

    private fun alertPendingIntent(
        context: Context,
        name: String,
        key: String,
        atMillis: Long,
        test: Boolean,
        request: Int,
        type: PrayerAlertType,
        voice: PrayerAdhanVoice
    ): PendingIntent = PendingIntent.getActivity(
        context,
        request,
        alertActivityIntent(context, name, key, atMillis, test, type, voice),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** One launch intent shared by the real alarm, the full-screen fallback and the test button. */
    private fun alertActivityIntent(
        context: Context,
        name: String,
        key: String,
        atMillis: Long,
        test: Boolean,
        type: PrayerAlertType,
        voice: PrayerAdhanVoice
    ): Intent = Intent(context, PrayerAlertActivity::class.java)
        .putExtra(PrayerAlertActivity.EXTRA_PRAYER_NAME, name)
        .putExtra(PrayerAlertActivity.EXTRA_PRAYER_KEY, key)
        .putExtra(PrayerAlertActivity.EXTRA_AT_MILLIS, atMillis)
        .putExtra(PrayerAlertActivity.EXTRA_TEST, test)
        .putExtra(PrayerAlertActivity.EXTRA_TYPE, type.key)
        .putExtra(PrayerAlertActivity.EXTRA_VOICE, voice.key)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** Request codes reserved for the one-off snooze; never cancelled by the rolling window. */
    fun snoozePendingIntent(context: Context, name: String): PendingIntent {
        val intent = Intent(context, PrayerReminderReceiver::class.java)
            .setAction(LEGACY_ACTION)
            .putExtra(PrayerReminderReceiver.EXTRA_NAME, name)
        return PendingIntent.getBroadcast(
            context, SNOOZE_REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Battery guidance lives here so both the notification center and settings can reuse it. */
internal object PrayerBatteryGuidance {
    fun isIgnoringOptimizations(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            runCatching {
                (context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)
                    ?.isIgnoringBatteryOptimizations(context.packageName) == true
            }.getOrDefault(false)

    fun openBatterySettings(context: Context) {
        val packageUri = android.net.Uri.parse("package:${context.packageName}")
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(packageUri)
        runCatching { context.startActivity(request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(packageUri)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }

    fun openAppSettings(context: Context) {
        val packageUri = android.net.Uri.parse("package:${context.packageName}")
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(packageUri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
