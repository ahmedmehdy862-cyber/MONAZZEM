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
 * already has the old, quiet channels. Low-priority channels (focus session, session summary)
 * deliberately stay outside this helper and remain silent.
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

    val PRAYER = AlertChannel(
        id = "prayer_adhan_v3",
        name = "الأذان ومواقيت الصلاة",
        description = "صوت أذان كامل وظهور عاجل على الشاشة عند دخول وقت الصلاة.",
        soundRes = R.raw.adhan_local,
        soundKey = NotificationSounds.PRAYER,
        category = NotificationCompat.CATEGORY_ALARM,
        alarmSound = true
    )
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
    val TEAM = AlertChannel(
        id = "team_alerts_v3",
        name = "رسائل الفريق العاجلة",
        description = "تنبيه فوري بصوت مميز عند وصول رسالة جديدة من الفريق.",
        soundRes = R.raw.notification_chime,
        soundKey = NotificationSounds.TEAM
    )
    val UPDATE = AlertChannel(
        id = "update_alerts_v3",
        name = "تحديثات مُنظِّم",
        description = "إشعار واضح عند توفر إصدار جديد.",
        soundRes = R.raw.notification_chime,
        soundKey = NotificationSounds.UPDATE
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

    /** All channels whose sound the dashboard may change. */
    val ALL: List<AlertChannel> = listOf(PRAYER, PRAYER_AT, PRAYER_BEFORE, TASK, EXAM, TEAM, UPDATE, DAILY, DHIKR)

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

    private fun resolvedSound(context: Context, channel: AlertChannel): Uri? =
        when (NotificationSounds.choice(context, channel.soundKey)) {
            NotificationSounds.SILENT -> null
            NotificationSounds.CHIME -> soundUri(context, R.raw.notification_chime)
            NotificationSounds.ADHAN -> soundUri(context, R.raw.adhan_local)
            NotificationSounds.SYSTEM -> systemSoundUri(channel)
            else -> soundUri(context, channel.soundRes)
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
        soundIsAlarm: Boolean = false
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
        if (soundRes != null) {
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
