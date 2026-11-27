package com.focusstudy.poc

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Plays the selected adhan as an alarm, not as an ordinary notification sound.
 *
 * Two things the notification channel alone cannot guarantee:
 *  - an exclusive audio focus, so a task/exam/team banner arriving mid-adhan cannot duck or cut it;
 *  - playback that survives the alert screen being dismissed.
 *
 * The ongoing notification is silent and low priority. Its only purpose is to keep the service
 * alive and to offer an explicit stop button; closing the centred screen never stops the adhan.
 *
 * Where the sound goes, and how loud it is, is decided at play time (see [AdhanAudioRouting]):
 *  - no headset connected: the alarm stream, boosted to its maximum for the duration of the
 *    adhan and restored afterwards, so the call is loud even when the alarm volume was left low;
 *  - a wired, USB or Bluetooth-A2DP headset connected: the media stream instead, because the
 *    system routes alarm audio to the speaker even with a headset on — the media stream is
 *    what actually reaches the headset. The media volume is the person's own listening
 *    volume and is never forced up (a forced maximum into earphones would be painful).
 */
internal class AdhanPlaybackService : Service() {

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusHeld = false
    /** Both the alarm receiver and the alert screen may ask to play; only one player may start. */
    private var starting = false
    /** Alarm-stream volume saved before the boost, so it is restored when playback ends. */
    private var savedAlarmVolume: Int? = null

    private val audioManager: AudioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A notification ducking request (CAN_DUCK) is normal and must not
        // interrupt the adhan. A permanent loss — and a transient loss, whose
        // main cause for an exclusive holder is a phone call starting — end
        // playback at once (0.35.0: the adhan must never play over a call).
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) stopPlayback()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPlayback()
                stopSelf()
            }
            ACTION_PLAY -> {
                if (player?.isPlaying != true && !starting) play(intent)
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun play(intent: Intent) {
        starting = true
        // A preview from the settings screen may still be running; never let it overlap the adhan.
        PrayerAdhanSounds.stopPreview()
        val voice = PrayerAdhanVoice.from(intent.getStringExtra(EXTRA_VOICE))
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { "الصلاة" }
        // حماية من سباق (0.35.0): مكالمة بدأت بعد جدولة الأذان وقبل تشغيله.
        // القرار العادي يُتخذ في PrayerNotifications قبل استدعاء الخدمة؛ هنا
        // إعادة فحص فقط. لا إشعار من الخدمة عند التأجيل (إشعار الصلاة الصامت
        // موجود أصلًا) إلا عند استنفاد التأجيلات في محاولة إعادة.
        val deferCount = AdhanCallDeferral.deferCountOf(intent)
        if (CallAware.isInCall(audioManager)) {
            when (CallAwareAdhanPolicy.decide(true, deferCount)) {
                AdhanCallDecision.DEFER -> AdhanCallDeferral.scheduleRetry(this, name, voice, deferCount)
                AdhanCallDecision.MISSED -> postSilentAdhanNotice(
                    this,
                    AdhanCallDeferral.MISSED_NOTIFICATION_ID,
                    "فاتك أذان صلاة $name",
                    "كان الأذان مؤجلًا أثناء مكالمة وانتهت محاولات إعادته. الصلاة التالية في وقتها إن شاء الله."
                )
                AdhanCallDecision.PLAY -> Unit // لا يحدث: decide(inCall=true) لا تعيد PLAY أبدًا.
            }
            starting = false
            stopSelf()
            return
        }
        // Promote to a foreground service before doing anything that could block: Android only
        // allows a few seconds between startForegroundService() and startForeground().
        runCatching { startForeground(NOTIFICATION_ID, ongoingNotification(name, voice)) }
        // The output is chosen now, at play time: a headset connected at this moment gets the
        // media stream (the only stream the system routes to it); otherwise the alarm stream.
        val headsetConnected = AdhanAudioRouting.hasConnectedHeadset(audioManager)
        val attributes = AdhanAudioRouting.attributesFor(headsetConnected)
        requestFocus(attributes)
        // Loud on the speaker only: the alarm stream is raised to its maximum for the adhan
        // and restored when playback ends. A headset keeps the person's own media volume.
        if (!headsetConnected) boostAlarmVolume()
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                setDataSource(
                    applicationContext,
                    Uri.parse("android.resource://${packageName}/${PrayerAdhanSounds.resource(voice)}")
                )
                setOnCompletionListener { stopPlayback(); stopSelf() }
                setOnErrorListener { _, _, _ -> stopPlayback(); stopSelf(); true }
                prepare()
                start()
            }
        }.onFailure { stopPlayback() }.getOrNull()
        starting = false
        if (player == null) stopSelf()
    }

    private fun requestFocus(attributes: AudioAttributes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusListener)
                .setAcceptsDelayedFocusGain(false)
                .build()
            focusRequest = request
            focusHeld = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            focusHeld = audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    /**
     * Raises the alarm stream to its maximum for the adhan. The previous level is saved
     * and restored by [restoreAlarmVolume] when playback ends; if it was already at the
     * maximum nothing is saved and nothing is changed. Needs MODIFY_AUDIO_SETTINGS
     * (a normal, install-time permission). Only the speaker path is boosted — never
     * the media volume of a connected headset.
     */
    private fun boostAlarmVolume() {
        runCatching {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            if (current < max) {
                savedAlarmVolume = current
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        }
    }

    private fun restoreAlarmVolume() {
        val saved = savedAlarmVolume ?: return
        savedAlarmVolume = null
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0) }
    }

    private fun ongoingNotification(name: String, voice: PrayerAdhanVoice): Notification {
        AppNotifications.ensurePlaybackChannel(this)
        val open = PendingIntent.getActivity(
            this,
            10,
            Intent(this, PrayerAlertActivity::class.java)
                .putExtra(PrayerAlertActivity.EXTRA_PRAYER_NAME, name)
                .putExtra(PrayerAlertActivity.EXTRA_TYPE, PrayerAlertType.AT.key)
                .putExtra(PrayerAlertActivity.EXTRA_VOICE, voice.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            11,
            Intent(this, AdhanPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, AppNotifications.ADHAN_PLAYBACK.id)
            .setSmallIcon(R.drawable.ic_stat_focus)
            .setContentTitle("الأذان يعمل الآن")
            .setContentText("صلاة $name · ${voice.label}")
            .setContentIntent(open)
            .setOngoing(true)
            .setSound(null)
            .setVibrate(null)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_stat_focus, "إيقاف الأذان", stop)
            .build()
    }

    private fun stopPlayback() {
        player?.runCatching {
            setOnCompletionListener(null)
            setOnErrorListener(null)
            stop()
            release()
        }
        player = null
        restoreAlarmVolume()
        if (focusHeld) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
            focusHeld = false
        }
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "com.focusstudy.poc.ADHAN_PLAY"
        const val ACTION_STOP = "com.focusstudy.poc.ADHAN_STOP"
        const val EXTRA_VOICE = "adhan_voice"
        const val EXTRA_NAME = "prayer_name"
        private const val NOTIFICATION_ID = 6301

        fun play(context: Context, name: String, voice: PrayerAdhanVoice) {
            val intent = Intent(context, AdhanPlaybackService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_VOICE, voice.key)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, AdhanPlaybackService::class.java).setAction(ACTION_STOP))
            }
        }

        /** Pure predicate so the AT-only policy remains unit-testable without Android. */
        fun shouldPlay(type: PrayerAlertType, sound: PrayerAlertSound): Boolean =
            type == PrayerAlertType.AT && sound == PrayerAlertSound.ADHAN
    }
}
