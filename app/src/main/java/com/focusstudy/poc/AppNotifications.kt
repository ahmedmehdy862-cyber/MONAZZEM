package com.focusstudy.poc

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * One place for every alert that is meant to reach the screen, not just the shade.
 *
 * Android will not raise a heads-up banner for a channel created at IMPORTANCE_DEFAULT, and the
 * importance of an existing channel cannot be lowered or raised later. That is why the alert
 * channels below have new ids: upgrading the app creates them correctly even on a device that
 * already has the old, quiet channels. The low-priority focus-session channel deliberately
 * stays outside this helper and remains silent. (The old `session_summary` channel was removed
 * in phase 4: it was created but nothing ever posted on it.)
 *
 * The channel *sound* can be changed after creation, unlike importance. `soundKey` is the id the
 * admin dashboard uses to choose a sound per notification type, stored privately on the server and
 * cached on the device by [NotificationSounds].
 */
internal data class AlertChannel(
    val id: String,
    val name: String,
    val description: String,
    val soundRes: Int,
    val soundKey: String,
    val highPriority: Boolean = true,
    val category: String = NotificationCompat.CATEGORY_REMINDER,
    val alarmSound: Boolean = false
)

internal object AppNotifications {

    private const val MIGRATION_PREFS = "notification_channel_migrations"
    private const val TEAM_UPDATE_DEFAULTED = "team_update_default_v1"

    /**
     * Part 7 splits prayer alerts in two channels. Their volume/importance and visibility are
     * permanently high; only the sound can change, and the per-prayer adhan/tone choice is applied
     * per notification on top of the channel default. New ids avoid inheriting an old quiet channel.
     */
    val PRAYER_AT = AlertChannel(
        id = "prayer_at_v4",
        name = "الأذان عند وقت الصلاة",
        description = "ظهور عاجل وصوت أذان عند دخول وقت كل صلاة.",
        soundRes = R.raw.adhan_local,
        soundKey = NotificationSounds.PRAYER,
        category = NotificationCompat.CATEGORY_ALARM,
        alarmSound = true
    )
    val PRAYER_BEFORE = AlertChannel(
        id = "prayer_before_v4",
        name = "تنبيه قبل الصلاة",
        description = "تنبيه عاجل قبل كل صلاة بالمدة والصوت الذي تختاره.",
        soundRes = R.raw.notification_chime,
        soundKey = NotificationSounds.PRAYER,
        category = NotificationCompat.CATEGORY_ALARM,
        alarmSound = true
    )
    val TASK = AlertChannel(
        id = "task_alerts_v3",
        name = "تنبيهات المهام العاجلة",
        description = "ظهور فوري على الشاشة مع صوت مميز عند استحقاق مهمة أو تأخرها.",
        soundRes = R.raw.notification_chime,
        soundKey = NotificationSounds.TASK
    )
    val EXAM = AlertChannel(
        id = "exam_alerts_v3",
        name = "تنبيهات الامتحانات",
        description = "عدّ تنازلي واضح قبل الامتحانات بالصوت والظهور على الشاشة.",
        soundRes = R.raw.notification_chime,
        soundKey = NotificationSounds.EXAM
    )
    /**
     * Team and update messages are ordinary, not urgent: they arrive at DEFAULT importance (no
     * heads-up banner) since phase 4. Their ids stay unchanged — [downgradeTeamAndUpdateChannels]
     * deletes and recreates them once on existing devices, because re-creating a channel with
     * the same id otherwise keeps its old importance.
     */
    val TEAM = AlertChannel(
        id = "team_alerts_v3",
        name = "رسائل الفريق",
        description = "رسالة من الفريق عند وصول إعلان أو تحديث.",
        soundRes = R.raw.notification_chime,
        soundKey = NotificationSounds.TEAM,
        highPriority = false
    )
    val UPDATE = AlertChannel(
        id = "update_alerts_v3",
        name = "تحديثات مُنظِّم",
        description = "إشعار واضح عند توفر إصدار جديد.",
        soundRes = R.raw.notification_chime,
        soundKey = NotificationSounds.UPDATE,
        highPriority = false
    )
    val DAILY = AlertChannel(
        id = "daily_encouragement_v3",
        name = "رسائل التشجيع",
        description = "رسائل صباحية ومسائية اختيارية بالصوت نفسه دون إزعاج الشاشة.",
        soundRes = R.raw.notification_chime,
        soundKey = NotificationSounds.DAILY,
        highPriority = false,
        category = NotificationCompat.CATEGORY_MESSAGE
    )
    val DHIKR = AlertChannel(
        id = "dhikr_reminders_v3",
        name = "تذكيرات الأذكار",
        description = "أذكار الصباح والمساء وبعد الظهر وقبل النوم في أوقاتها المناسبة.",
        soundRes = R.raw.notification_chime,
        soundKey = NotificationSounds.DHIKR,
        highPriority = false
    )
    /** Silent, ongoing channel for the adhan playback service — never chooses an audible sound. */
    val ADHAN_PLAYBACK = AlertChannel(
        id = "adhan_playback_v1",
        name = "تشغيل الأذان",
        description = "إشعار هادئ يبقي صوت الأذان يعمل حتى لو أُغلقت شاشة التنبيه.",
        soundRes = R.raw.notification_chime,
        soundKey = "",
        highPriority = false
    )

    /**
     * All channels whose sound the dashboard may change. The old `prayer_adhan_v3` channel is
     * gone from this list since phase 4: nothing ever posted on it (the v4 channels carry the
     * adhan), so it is no longer created. It may still appear in system settings on devices
     * that had it, idle, until Android removes it.
     */
    val ALL: List<AlertChannel> = listOf(PRAYER_AT, PRAYER_BEFORE, TASK, EXAM, TEAM, UPDATE, DAILY, DHIKR)

    /**
     * One-time downgrade of the team and update channels from HIGH to DEFAULT (phase 4).
     *
     * Android cannot change an existing channel's importance in place: creating a channel with
     * an id that already exists silently keeps the old importance. Each channel is therefore
     * deleted and created again with the same id, so the person keeps one channel per kind and
     * the new importance applies. A channel the person blocked in system settings
     * (IMPORTANCE_NONE) is left untouched — deleting and recreating it would silently unblock
     * messages they asked not to receive. A one-time flag keeps this from running on every
     * reschedule; deleting also drops any per-channel customisation (sound, vibration) on those
     * two channels only, which the in-app sound choices re-apply through [ensure].
     */
    fun downgradeTeamAndUpdateChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val prefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(TEAM_UPDATE_DEFAULTED, false)) return
        val manager = manager(context)
        listOf(TEAM, UPDATE).forEach { channel ->
            val existing = runCatching { manager.getNotificationChannel(channel.id) }.getOrNull()
            if (existing != null && existing.importance != NotificationManager.IMPORTANCE_NONE) {
                runCatching { manager.deleteNotificationChannel(channel.id) }
                ensure(context, channel)
            }
        }
        prefs.edit().putBoolean(TEAM_UPDATE_DEFAULTED, true).apply()
    }
    /** Playback channel is created separately: it must stay silent whatever the dashboard selects. */
    fun ensurePlaybackChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            ADHAN_PLAYBACK.id,
            ADHAN_PLAYBACK.name,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = ADHAN_PLAYBACK.description
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        manager(context).createNotificationChannel(channel)
    }

    private val vibration = longArrayOf(0, 180, 120, 260)

    fun soundUri(context: Context, resId: Int): Uri =
        Uri.parse("android.resource://${context.packageName}/$resId")

    /** Android's own notification/alarm sound, chosen by the person in system settings. */
    private fun systemSoundUri(channel: AlertChannel): Uri =
        Uri.parse(if (channel.alarmSound) "content://settings/system/alarm_alert" else "content://settings/system/notification_sound")

    fun canPost(context: Context): Boolean {
        if (!manager(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Re-applies cached admin choices to channel sounds after the public feed is fetched. */
    fun refreshChannels(context: Context) = ALL.forEach { ensure(context, it) }

    /** The two adhan channels follow the voice chosen on the device, even for the default choice. */
    private fun defaultSoundRes(context: Context, channel: AlertChannel): Int =
        if (channel.id == PRAYER_AT.id) {
            PrayerAdhanSounds.resource(PrayerAlertPrefs.load(context).voice)
        } else channel.soundRes

    private fun resolvedSound(context: Context, channel: AlertChannel): Uri? =
        when (NotificationSounds.choice(context, channel.soundKey)) {
            NotificationSounds.SILENT -> null
            NotificationSounds.CHIME -> soundUri(context, R.raw.notification_chime)
            NotificationSounds.ADHAN -> soundUri(
                context,
                PrayerAdhanSounds.resource(PrayerAlertPrefs.load(context).voice)
            )
            NotificationSounds.SYSTEM -> systemSoundUri(channel)
            else -> soundUri(context, defaultSoundRes(context, channel))
        }

    private fun audioAttributes(context: Context, channel: AlertChannel): AudioAttributes {
        // A full adhan needs alarm usage to play at its intended volume; the rest stay notification sounds.
        val useAlarm = channel.alarmSound || NotificationSounds.choice(context, channel.soundKey) == NotificationSounds.ADHAN
        return AudioAttributes.Builder()
            .setUsage(if (useAlarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    fun ensure(context: Context, channel: AlertChannel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val attributes = audioAttributes(context, channel)
        val nativeChannel = NotificationChannel(
            channel.id,
            channel.name,
            if (channel.highPriority) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = channel.description
            setSound(resolvedSound(context, channel), attributes)
            enableVibration(true)
            vibrationPattern = vibration
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
            if (channel.highPriority) {
                enableLights(true)
                lightColor = 0x5FE0CE
            }
        }
        manager(context).createNotificationChannel(nativeChannel)
    }

    fun builder(
        context: Context,
        channel: AlertChannel,
        title: String,
        message: String,
        contentIntent: PendingIntent,
        fullScreenIntent: PendingIntent? = null,
        largeIcon: Bitmap? = null,
        deleteIntent: PendingIntent? = null,
        soundRes: Int? = null,
        soundIsAlarm: Boolean = false,
        silent: Boolean = false
    ): NotificationCompat.Builder {
        // Reading the cached choice here as well as in ensure() means a receiver firing while the
        // app is closed still uses the latest sound the admin published before the device went offline.
        ensure(context, channel)
        val sound = resolvedSound(context, channel)
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(channel.category)
            .setPriority(
                if (channel.highPriority) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setVibrate(vibration)
            .setAutoCancel(true)
            .setShowWhen(true)
        // AudioAttributes are applied on the channel in ensure(); the per-notification Uri only
        // selects which of those configured sounds this alert uses. A per-prayer override also
        // sets its own attributes so an adhan played on the short-tone channel still uses alarm
        // volume, and a short tone on the adhan channel stays gentle.
        if (silent) notification.setSound(null)
        else if (soundRes != null) {
            notification.setSound(
                soundUri(context, soundRes),
                if (soundIsAlarm) AudioManager.STREAM_ALARM else AudioManager.STREAM_NOTIFICATION
            )
        } else if (sound == null) notification.setSound(null)
        else notification.setSound(sound)
        return notification.apply {
            if (fullScreenIntent != null) setFullScreenIntent(fullScreenIntent, true)
            if (largeIcon != null) setLargeIcon(largeIcon)
            // A swipe is the only real "ignored" signal. Without this, blank actions were mistaken
            // for ignores and the adaptive policy silenced non-critical reminders after three sends.
            if (deleteIntent != null) setDeleteIntent(deleteIntent)
        }
    }
}
