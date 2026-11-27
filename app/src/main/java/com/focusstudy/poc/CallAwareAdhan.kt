package com.focusstudy.poc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * مراعاة المكالمة الجارية (0.35.0 — بأمر أحمد): صوت الأذان والإشعارات لا
 * تقطع مكالمة ولا تعلو عليها، ولا يصدر أي إشعار صوتًا/اهتزازًا أثناء مكالمة.
 *
 * الكشف بلا أي إذن جديد: `AudioManager.mode` — ‏`MODE_IN_CALL` للمكالمات
 * الهاتفية و`MODE_IN_COMMUNICATION` لمكالمات VoIP — فلا `TelephonyManager`
 * ولا `READ_PHONE_STATE`.
 *
 * القاعدة في ثلاثة مواضع:
 * - [AdhanPlaybackService]: لا يبدأ الأذان أثناء مكالمة؛ يؤجَّل 15 دقيقة
 *   (حتى 3 مرات) ثم يُعلن فواته بصمت. ومكالمة تبدأ أثناء الأذان توقفه
 *   عبر فقدان تركيز الصوت (انظر المستمع هناك).
 * - [PrayerNotifications]: تنبيه الصلاة أثناء مكالمة يُنشر صامتًا تمامًا
 *   (قناة تشغيل الأذان الصامتة — بلا صوت ولا اهتزاز) وبلا نية ملء الشاشة،
 *   مع جدولة إعادة الأذان.
 * - [NotificationDelivery]: تنبيهات الرفيق غير الحرجة تُؤجَّل 15 دقيقة
 *   أثناء مكالمة (سبب `IN_CALL` في [NotificationPolicy]).
 *
 * ما يحتاج جهازًا حقيقيًا (لا يثبته المحاكي): مكالمة فعلية — وضع
 * `AudioManager` لا يتغير بمكالمة محاكاة، وسماع أن الأذان توقف فعلًا عند
 * بدء مكالمة. المنطق الخالص ([CallAwareAdhanPolicy]) مغطى باختبارات وحدة.
 */
internal object CallAware {
    fun isInCall(audioManager: AudioManager): Boolean =
        audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION

    fun isInCall(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return false
        return isInCall(audioManager)
    }
}

/** قرار الأذان أثناء مكالمة — منطق خالص بلا أندرويد، يُختبر بـ JUnit عادي. */
internal enum class AdhanCallDecision { PLAY, DEFER, MISSED }

internal object CallAwareAdhanPolicy {
    /** الفاصل بين محاولات إعادة الأذان بعد مكالمة. */
    const val DEFER_INTERVAL_MILLIS = 15 * 60 * 1000L

    /** أقصى عدد تأجيلات قبل إعلان فوات الأذان بصمت. */
    const val MAX_DEFERS = 3

    fun decide(inCall: Boolean, deferCount: Int, maxDefers: Int = MAX_DEFERS): AdhanCallDecision =
        when {
            !inCall -> AdhanCallDecision.PLAY
            deferCount < maxDefers -> AdhanCallDecision.DEFER
            else -> AdhanCallDecision.MISSED
        }

    fun nextRetryAt(now: Long): Long = now + DEFER_INTERVAL_MILLIS
}

/**
 * تأجيل الأذان وإعادة جدولته بعد مكالمة. إعادة الجدولة بمنبه دقيق يُعيد
 * استدعاء [AdhanPlaybackService] بالاسم والصوت نفسه وعدّاد تأجيل أعلى؛
 * والخدمة نفسها تعيد الفحص عند الإقلاع (حماية من سباق: مكالمة بدأت بين
 * الجدولة والتنفيذ).
 */
internal object AdhanCallDeferral {
    internal const val EXTRA_DEFER_COUNT = "adhan_defer_count"
    private const val DEFER_REQUEST = 6313
    internal const val MISSED_NOTIFICATION_ID = 6302
    private const val TAG = "CallAwareAdhan"

    fun deferCountOf(intent: Intent?): Int = intent?.getIntExtra(EXTRA_DEFER_COUNT, 0) ?: 0

    fun scheduleRetry(context: Context, name: String, voice: PrayerAdhanVoice, deferCount: Int) {
        val intent = Intent(context, AdhanPlaybackService::class.java)
            .setAction(AdhanPlaybackService.ACTION_PLAY)
            .putExtra(AdhanPlaybackService.EXTRA_NAME, name)
            .putExtra(AdhanPlaybackService.EXTRA_VOICE, voice.key)
            .putExtra(EXTRA_DEFER_COUNT, deferCount + 1)
        val pending = PendingIntent.getService(
            context,
            DEFER_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = CallAwareAdhanPolicy.nextRetryAt(System.currentTimeMillis())
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                @Suppress("DEPRECATION")
                alarm.setExact(AlarmManager.RTC_WAKEUP, at, pending)
            }
            Log.i(TAG, "أذان صلاة $name مؤجل أثناء مكالمة — إعادة بعد 15 دقيقة (محاولة ${deferCount + 1})")
        }.onFailure {
            Log.w(TAG, "تعذرت جدولة إعادة أذان صلاة $name بعد المكالمة", it)
        }
    }
}

/**
 * إشعار صامت تمامًا (بلا صوت ولا اهتزاز) على قناة تشغيل الأذان — القناة
 * نفسها التي يبقي بها [AdhanPlaybackService] نفسه حيًا، واهتزازها معطّل
 * أصلًا (`ensurePlaybackChannel`). لا يُستخدم `AppNotifications.builder`
 * هنا عمدًا: فهو يستدعي `ensure()` التي تفعّل الاهتزاز على أي قناة.
 */
internal fun postSilentAdhanNotice(context: Context, id: Int, title: String, message: String) {
    AppNotifications.ensurePlaybackChannel(context)
    val notification = NotificationCompat.Builder(context, AppNotifications.ADHAN_PLAYBACK.id)
        .setSmallIcon(R.drawable.ic_stat_focus)
        .setContentTitle(title)
        .setContentText(message)
        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setSound(null)
        .setVibrate(null)
        .setAutoCancel(true)
        .setShowWhen(true)
        .build()
    AppNotifications.manager(context).notify(id, notification)
}
