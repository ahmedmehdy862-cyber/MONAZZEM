package com.focusstudy.poc

import android.content.Context

/**
 * المساعد «الزنان»: متابعة وتذكيرات مستمرة لا تختفي بإشعار واحد.
 *
 * المبدأ: التذكير الواحد سلسلة محاولات، لا إشعارًا وحيدًا. بعد الإشعار الأول،
 * إن لم يُنجَز العنصر (ورد/تمرين) ولم يؤجّل المستخدم أو يلغِ صراحةً، يعيد
 * المساعد التذكير بفواصل متصاعدة وبصياغة تتصاعد بلطف — حتى الإنجاز أو
 * التأجيل أو الإلغاء الصريح أو بلوغ حد المحاولات.
 *
 * ضوابط ضد الإزعاج (كلها إلزامية، لا مخرج منها):
 * - كل محاولة تمر عبر بوابة السياسة المشتركة [NotificationDelivery]: ساعات
 *   الهدوء، وجلسة التركيز الجارية، ونافذة الصلاة، والميزانية اليومية،
 *   والفاصل 45 دقيقة — فلا تصل محاولة في وقت ممنوع أبدًا.
 * - سحب 3 إشعارات متتالية (تجاهل صريح) يوقف السلسلة بقاعدة [NotificationPolicy]
 *   نفسها — مخرج المستخدم دائمًا بيده.
 * - مستوى الإلحاح يحدد حد المحاولات؛ الافتراضي «مثابر» كما طلب أحمد،
 *   و«هادئ» يعيد سلوك الإشعار الواحد القديم.
 * - السلسلة تُحفظ في جدول `assistant_followups` فتعيش عبر إعادة التشغيل،
 *   وتُعاد جدولتها بعد الإقلاع من [NotificationMaintenance.rescheduleAll].
 */

/** مستوى إلحاح المتابعة؛ يختاره المستخدم من مركز الإشعارات. */
enum class AssistantNagLevel(val key: String, val label: String, val maxAttempts: Int) {
    /** 5 محاولات: فواصل 30/60/120/180 دقيقة بعد الإشعار الأول. */
    PERSISTENT("persistent", "مثابر", 5),
    /** 3 محاولات: فواصل 60/120 دقيقة. */
    BALANCED("balanced", "متوسط", 3),
    /** محاولة واحدة فقط: سلوك الإشعار الواحد القديم. */
    QUIET("quiet", "هادئ", 1);

    companion object {
        fun from(key: String?): AssistantNagLevel =
            entries.firstOrNull { it.key == key } ?: PERSISTENT
    }
}

/** إعدادات المتابعة المحفوظة محليًا. */
data class AssistantNagSettings(val level: AssistantNagLevel = AssistantNagLevel.PERSISTENT)

internal object AssistantNagPrefs {
    private const val FILE = "assistant_followup_preferences"
    private const val LEVEL = "nag_level"

    fun load(context: Context): AssistantNagSettings {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return AssistantNagSettings(AssistantNagLevel.from(prefs.getString(LEVEL, null)))
    }

    fun save(context: Context, settings: AssistantNagSettings) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(LEVEL, settings.level.key)
            .apply()
    }
}

/** أنواع سلاسل المتابعة. */
enum class FollowUpKind(val key: String) {
    WIRD_MORNING("wird_morning"),
    WIRD_EVENING("wird_evening"),
    EXERCISE("exercise");

    companion object {
        fun from(key: String?): FollowUpKind? = entries.firstOrNull { it.key == key }
    }
}

/** صف سلسلة متابعة محفوظ في `assistant_followups`. */
data class AssistantFollowUpRow(
    val id: Long,
    val kind: String,
    val slot: String,
    val day: String,
    val attempts: Int,
    val maxAttempts: Int,
    val nextAt: Long,
    val state: String, // active | done | dismissed | expired
    val snoozeUntil: Long?,
    val detail: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** رياضيات السلسلة النقية: فواصل المحاولات وصياغة التصعيد. */
internal object AssistantFollowUpPolicy {

    /** فواصل المحاولات بالدقائق بعد الإشعار الأول، حسب المستوى. */
    fun intervalsMinutes(level: AssistantNagLevel): List<Int> = when (level) {
        AssistantNagLevel.PERSISTENT -> listOf(30, 60, 120, 180)
        AssistantNagLevel.BALANCED -> listOf(60, 120)
        AssistantNagLevel.QUIET -> emptyList()
    }

    /** هل تُجدول محاولة أخرى بعد هذه المحاولة؟ */
    fun shouldContinue(level: AssistantNagLevel, attempts: Int): Boolean =
        attempts < level.maxAttempts && attempts - 1 < intervalsMinutes(level).size

    /** الفاصل بالمللي ثانية قبل المحاولة التالية (attempts = المحاولات المرسلة حتى الآن). */
    fun nextDelayMillis(level: AssistantNagLevel, attempts: Int): Long? {
        val intervals = intervalsMinutes(level)
        val index = attempts - 1 // الفاصل بعد المحاولة رقم attempts
        if (index < 0 || index >= intervals.size) return null
        return intervals[index] * 60_000L
    }

    /** عنوان متصاعد بلطف حسب رقم المحاولة (1 = الإشعار الأول). */
    fun titleFor(kind: FollowUpKind, attempt: Int): String = when (kind) {
        FollowUpKind.WIRD_MORNING, FollowUpKind.WIRD_EVENING -> when (attempt) {
            1 -> "ورد اليوم 📖"
            2 -> "لسه مستنيك — ورد اليوم 📖"
            3 -> "تذكير تالت: ورد اليوم لسه ما اتقراش 📖"
            else -> "ورد اليوم مستنيك من بدري 📖"
        }
        FollowUpKind.EXERCISE -> when (attempt) {
            1 -> "وقت التمرين 💪"
            2 -> "لسه ما اتمرنتش النهاردة 💪"
            3 -> "تذكير تالت: تمرين النهاردة لسه ما اتعملش 💪"
            else -> "تمرين النهاردة مستنيك من بدري 💪"
        }
    }

    /** نص متصاعد بلطف حسب رقم المحاولة؛ [detail] تفاصيل العنصر (صفحات/يوم التمرين). */
    fun bodyFor(kind: FollowUpKind, attempt: Int, detail: String): String = when (kind) {
        FollowUpKind.WIRD_MORNING, FollowUpKind.WIRD_EVENING -> when (attempt) {
            1 -> detail
            2 -> "فكّرتك من شوية ولسه ما اتسجّلش. $detail"
            3 -> "ده التذكير التالت — الورد صفحتين بس، خلّصهم وارتاح. $detail"
            else -> "أنا زنان عشان الورد مهم، بس صفحتين ويخلص. $detail — أو اضغط «مش النهاردة» وأنا أسكت."
        }
        FollowUpKind.EXERCISE -> when (attempt) {
            1 -> detail
            2 -> "فكّرتك من شوية ولسه ما اتمرنتش. $detail"
            3 -> "ده التذكير التالت — تمرين قصير النهاردة أحسن من مفيش. $detail"
            else -> "أنا زنان عشان صحتك، بس جرّب ولو 10 دقايق. $detail — أو اضغط «مش النهاردة» وأنا أسكت."
        }
    }
}
