package com.focusstudy.poc

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * المساعد الشخصي «منظم»: محرك قواعد محلي بالكامل.
 *
 * لا يوجد هنا أي اتصال شبكي ولا نموذج لغوي ولا حساب سحابي: الردود قواعد مبنية على
 * كلمات مفتاحية عربية وعلى معرفة التطبيق (انظر [AssistantKnowledge])، وكل البيانات
 * — الإعدادات والملف التعريفي والحقائق وسجل المحادثة — محفوظة في
 * تفضيلات مشفّرة ([SecurePrefs]) على الجهاز فقط، بصيغة JSON، عمدًا بدل جدول جديد في قاعدة
 * البيانات حتى لا يتعارض ترحيلها مع مراحل لاحقة.
 */

/** نبرة المساعد؛ يختارها المستخدم من رقاقات في شاشة المحادثة. */
enum class AssistantTone(val key: String, val label: String) {
    PLAYFUL("playful", "مرحة"),
    FORMAL("formal", "رسمية"),
    CALM("calm", "هادئة");

    companion object {
        fun from(key: String?): AssistantTone =
            entries.firstOrNull { it.key == key } ?: PLAYFUL
    }
}

/** إعدادات المساعد المحفوظة محليًا. الاسم الافتراضي «منظم». */
data class AssistantSettings(
    val name: String = "منظم",
    val tone: AssistantTone = AssistantTone.PLAYFUL,
    val promptShown: Boolean = false,
    val profileImported: Boolean = false,
    val speakReplies: Boolean = true
)

/** حقيقة تعلّمها المساعد من المحادثة؛ تظهر في قسم داخل شاشة المساعد ويمكن حذفها. */
data class LearnedFact(
    val id: String,
    val text: String,
    val category: String,
    val addedAt: Long
)

/** رسالة واحدة في سجل المحادثة. */
data class AssistantChatMessage(
    val role: String, // "user" أو "assistant"
    val text: String,
    val at: Long
)

/**
 * البرومبت الثابت الذي يعرضه المساعد في أول استخدام — حرفيًا كما هو، بلا أي تغيير
 * في نصه. المستخدم ينسخه ويلصقه في ChatGPT أو Gemini ثم يعيد الناتج إلى المساعد
 * كنص ملصوق أو كملف PDF.
 */
const val ASSISTANT_ONBOARDING_PROMPT = "أريدك أن تساعدني في إعداد ملف تعريفي شامل عني، سأعطيه لمساعدي الشخصي «منظم» داخل تطبيق مُنظِّم حتى يفهمني ويساعدني بشكل أفضل. اكتب — بناءً على ما تعرفه عني من محادثاتنا السابقة، واسألني عما لا تعرفه — ملفًا منظمًا يتضمن: 1) معلومات أساسية: الاسم الذي أحب أن أنادى به، عمري، دراستي أو عملي. 2) أهدافي: الدراسية والمهنية والشخصية والدينية. 3) روتيني اليومي: مواعيد النوم والاستيقاظ والدراسة أو العمل. 4) عاداتي: ورد القرآن، الرياضة، القراءة، وغيرها. 5) صحتي: الوزن والطول وأهدافي الصحية. 6) اهتماماتي وهواياتي. 7) التحديات التي أواجهها وما أريد تحسينه. 8) كيف أحب أن يكلمني المساعد: باللهجة المصرية، بأسلوب مرح وهادئ. اكتب الملف بصيغة نص منظم واضح، وإذا أمكن أخرجه كملف PDF."

/** اقتراحات تعليمية: برومبتات يعرضها المساعد ليدعو المستخدم أن يعلّمه عن نفسه. */
val ASSISTANT_TEACH_ME_PROMPTS = listOf(
    "عرفني على هدفك الجديد",
    "قولي عن روتينك اليومي",
    "مواعيد نومك إيه؟",
    "إيه التحدي اللي واقف قدامك؟",
    "إيه هواياتك؟",
    "عايز تحسّن إيه في صحتك؟"
)

/** أسئلة جاهزة مبنية على معرفة التطبيق الحالية. */
val ASSISTANT_KNOWLEDGE_PROMPTS = listOf(
    "وردي النهاردة إيه؟",
    "عندي مهام إيه النهاردة؟",
    "الصلاة الجاية إمتى؟",
    "سعراتي النهاردة عاملة إيه؟",
    "تعرف عني إيه؟"
)

/**
 * أوامر تنفيذية جاهزة: الضغط على أي منها ينفّذ الفعل فعلًا داخل التطبيق
 * (إضافة مهمة، تسجيل ورد، بدء جلسة، فتح المصحف أو خطة اليوم) — لا مجرد سؤال.
 */
val ASSISTANT_ACTION_PROMPTS = listOf(
    "ضيف مهمة مراجعة فيزياء الساعة 6",
    "سجّل ورد اليوم",
    "ابدأ جلسة تركيز",
    "افتح المصحف",
    "اعرض خطة اليوم"
)

/**
 * فعل يطلبه المستخدم من المساعد وينفَّذ داخل التطبيق محليًا بلا شبكة.
 *
 * المحرك نقي: يحدد الفعل ومعاملاته فقط. التنفيذ نفسه يتم في شاشة المساعد عبر
 * دوال رد نداء من MainActivity إلى المستودعات والتنقّل القائمين، فلا يملك
 * المحرك أي مرجع لمستودع أو نشاط، ولا ينشئ مسار تنفيذ موازيًا.
 */
sealed class AssistantAction {
    /** إضافة مهمة جديدة؛ [dueAt] بالمللي ثانية أو null بلا موعد محدد.
     * [plannedMinutes] مدة المهمة بالدقائق كما ذكرها المستخدم، أو null
     * فيستخدم التنفيذ الافتراضي (30 دقيقة) — لا مدة ثابتة صامتة. */
    data class AddTask(
        val title: String,
        val dueAt: Long?,
        val priorityLevel: Int?,
        val recurrence: Recurrence,
        val plannedMinutes: Int? = null
    ) : AssistantAction()

    /** تسجيل ورد اليوم بإعدادات الورد الحالية. */
    object LogWird : AssistantAction()

    /**
     * بدء جلسة تركيز؛ [taskId] null تعني جلسة عامة. [minutes] مضبوطة بين 5 و120
     * دقيقة في المحرك قبل أن تصل إلى التنفيذ.
     */
    data class StartFocus(
        val taskId: Long?,
        val taskTitle: String?,
        val minutes: Int
    ) : AssistantAction()

    /** تعليم مهمة موجودة كمنجزة. */
    data class CompleteTask(val taskId: Long, val title: String) : AssistantAction()

    /** فتح المصحف على صفحة ورد اليوم. */
    object OpenQuran : AssistantAction()

    /** عرض خطة اليوم (شاشة «اليوم»). */
    object OpenToday : AssistantAction()
}

/** نتيجة محاولة بدء جلسة تركيز من المساعد. */
enum class AssistantFocusOutcome {
    /** بدأت الجلسة فعلًا عبر مسار بدء الجلسة القائم. */
    STARTED,
    /** لا تطبيقات محظورة للمهمة/الجلسة: فُتحت شاشة إعداد الجلسة ليختارها المستخدم. */
    SETUP_OPENED,
    /** توجد جلسة تركيز شغالة أصلًا، فلم يبدأ شيء جديد. */
    ALREADY_RUNNING,
    /** فشل التنفيذ لسبب غير متوقع. */
    FAILED
}

/** تخزين المساعد المحلي: تفضيلات مشفّرة عبر [SecurePrefs] (A10 — كانت
 * SharedPreferences عادية بنص صريح). الترحيل الآمن (نسخ + تحقق بالقراءة +
 * حذف بعد النجاح) يتولاه [SecurePrefs]؛ وإن تعذّر التشفير على الجهاز يرجع
 * الملف العادي دون فقدان أي بيانات. */
internal object AssistantStore {
    private const val FILE = "assistant_preferences"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_FACTS = "facts"
    private const val KEY_PROFILE = "profile"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 200
    private const val MAX_FACTS = 100

    private fun prefs(context: Context) = SecurePrefs.prefs(context, FILE)

    fun loadSettings(context: Context): AssistantSettings {
        val raw = prefs(context).getString(KEY_SETTINGS, null) ?: return AssistantSettings()
        return runCatching {
            val json = JSONObject(raw)
            AssistantSettings(
                name = json.optString("name", "منظم").ifBlank { "منظم" },
                tone = AssistantTone.from(json.optString("tone", "playful")),
                promptShown = json.optBoolean("promptShown", false),
                profileImported = json.optBoolean("profileImported", false),
                speakReplies = json.optBoolean("speakReplies", true)
            )
        }.getOrDefault(AssistantSettings())
    }

    fun saveSettings(context: Context, settings: AssistantSettings) {
        val json = JSONObject().apply {
            put("name", settings.name)
            put("tone", settings.tone.key)
            put("promptShown", settings.promptShown)
            put("profileImported", settings.profileImported)
            put("speakReplies", settings.speakReplies)
        }
        prefs(context).edit().putString(KEY_SETTINGS, json.toString()).apply()
    }

    fun loadFacts(context: Context): List<LearnedFact> {
        val raw = prefs(context).getString(KEY_FACTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                LearnedFact(
                    id = item.optString("id", "fact-$i"),
                    text = item.optString("text", ""),
                    category = item.optString("category", "عام"),
                    addedAt = item.optLong("addedAt", 0L)
                )
            }.filter { it.text.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun saveFacts(context: Context, facts: List<LearnedFact>) {
        val array = JSONArray()
        facts.take(MAX_FACTS).forEach { fact ->
            array.put(JSONObject().apply {
                put("id", fact.id)
                put("text", fact.text)
                put("category", fact.category)
                put("addedAt", fact.addedAt)
            })
        }
        prefs(context).edit().putString(KEY_FACTS, array.toString()).apply()
    }

    fun loadProfile(context: Context): String =
        prefs(context).getString(KEY_PROFILE, "").orEmpty()

    fun saveProfile(context: Context, text: String) {
        prefs(context).edit().putString(KEY_PROFILE, text).apply()
    }

    fun loadHistory(context: Context): List<AssistantChatMessage> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                AssistantChatMessage(
                    role = item.optString("role", "assistant"),
                    text = item.optString("text", ""),
                    at = item.optLong("at", 0L)
                )
            }.filter { it.text.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun saveHistory(context: Context, messages: List<AssistantChatMessage>) {
        val array = JSONArray()
        messages.takeLast(MAX_HISTORY).forEach { message ->
            array.put(JSONObject().apply {
                put("role", message.role)
                put("text", message.text)
                put("at", message.at)
            })
        }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun clearHistory(context: Context) {
        prefs(context).edit().remove(KEY_HISTORY).apply()
    }
}

/** مهمة مقترحة من كلام عادي («عندي امتحان بكرة») — تعرضها الشاشة بزر تأكيد. */
data class ProposedTask(
    val title: String,
    val dueAt: Long?,
    val priorityLevel: Int?,
    val recurrence: Recurrence,
    /** مدة المهمة بالدقائق كما ذكرها المستخدم، أو null للافتراضي. */
    val plannedMinutes: Int? = null
)

/**
 * نتيجة رد المحرك: النص، مع حقيقة تعلّمها من الرسالة إن وُجدت، وفعل ينفَّذ إن
 * طُلب، ومهمة مقترحة بزر تأكيد إن وُجدت، ورقاقات توضيحية إن كان الرد سؤالًا،
 * ومعرّف ذكرى استُخدمت في الرد لتسجيل استخدامها.
 */
data class AssistantReply(
    val text: String,
    val learnedFact: LearnedFact? = null,
    val action: AssistantAction? = null,
    val proposedTask: ProposedTask? = null,
    val chips: List<String> = emptyList(),
    val usedMemoryId: Long? = null
)

/**
 * محرك الردود: قواعد محلية فقط. يطابق نية الرسالة بكلمات مفتاحية عربية، ويبني
 * الرد من [AssistantSnapshot] (معرفة التطبيق) ومن الحقائق المحفوظة والملف
 * التعريفي، ثم يغلّف الرد بصياغة النبرة المختارة.
 */
internal object AssistantEngine {

    /** مدة جلسة التركيز الافتراضية بالدقائق عند عدم ذكر مدة (مطابقة لإعداد التطبيق). */
    private const val DEFAULT_FOCUS_MINUTES = 25

    /**
     * تطبيع عربي واحد لكل مطابقة (A1): يُستدعى مرة واحدة في [reply] قبل
     * المصنّف و[decideAction] و[answerFor]، فلا يفشل «انتا مين» و«عامل اي»
     * بسبب همزة أو تاء مربوطة أو ألف مقصورة. أ/إ/آ/ٱ→ا، ة→ه، ى→ي، وإزالة
     * التشكيل والتطويل، و«انتا»→«انت». الإدخال الأصلي يبقى للعرض (عناوين
     * المهام تُبنى من النص الأصلي لا المطبَّع) — التطبيع للمطابقة فقط.
     */
    fun normalizeArabic(text: String): String {
        val withoutDiacritics = text.map { ch ->
            when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ة' -> 'ه'
                'ى' -> 'ي'
                'ـ' -> null // تطويل: يُحذف
                in '\u064B'..'\u0652', '\u0670' -> null // تشكيل: يُحذف
                else -> ch
            }
        }.filterNotNull().joinToString("")
        return withoutDiacritics.split(Regex("\\s+"))
            .joinToString(" ") { token -> if (token == "انتا") "انت" else token }
            .trim()
    }

    /**
     * غلاف النبرة (A2): يُطبَّق على كل إجابات المحتوى — لا على التحية والرد
     * الاحتياطي فقط (لهما صياغتهما الخاصة بالنبرة أصلًا). المرحة هي صوت
     * النص الحالي فيبقى كما هو؛ الهادئة تختم بهدوء، والرسمية تختم بصياغة
     * رسمية، فيتغير كل رد فعليًا بتبديل النبرة.
     */
    fun toneWrap(text: String, tone: AssistantTone): String = when (tone) {
        AssistantTone.PLAYFUL -> text
        AssistantTone.CALM -> "$text\nخذ وقتك — أنا معك خطوة بخطوة."
        AssistantTone.FORMAL -> "$text\nإن احتجت إلى أي مساعدة أخرى، فأنا في الخدمة."
    }

    /**
     * تحليل الملف التعريفي الملصوق (A2): سطور موسومة فقط — «الاسم: …»،
     * «الوظيفة/عملي: …»، «هدفي/أهدافي: …»، «روتيني/روتين: …»، «نومي: …»،
     * «صحتي/وزني: …»، «هواياتي: …» — تتحول إلى حقائق مصنّفة. سطر بلا وسم
     * لا يُفسَّر ولا يُخترع له معنى؛ يُبقي النص حرفيًا ويُعرض كمقتطف فقط
     * (انظر [aboutMeAnswer]). القيم تُقصّ إلى 220 حرفًا كالحقائق العادية.
     */
    fun parseProfileFacts(profileText: String): List<LearnedFact> {
        if (profileText.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        val labelToCategory = listOf(
            "الاسم" to "اسم", "اسمك" to "اسم", "اسمي" to "اسم",
            "الوظيفة" to "دراسة وعمل", "وظيفتي" to "دراسة وعمل",
            "عملي" to "دراسة وعمل", "دراستي" to "دراسة وعمل",
            "هدفي" to "هدف", "أهدافي" to "هدف", "اهدافي" to "هدف",
            "روتيني" to "روتين", "روتين" to "روتين",
            "نومي" to "روتين", "مواعيد نومي" to "روتين",
            "صحتي" to "صحة", "وزني" to "صحة", "طولي" to "صحة",
            "هواياتي" to "اهتمامات", "هوايتي" to "اهتمامات",
            "اهتماماتي" to "اهتمامات"
        )
        return profileText.lineSequence()
            .map { it.trim().trimStart('-', '•', '*').trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val colon = line.indexOf(':').takeIf { it > 0 } ?: return@mapNotNull null
                val label = normalizeArabic(line.substring(0, colon).trim())
                val value = line.substring(colon + 1).trim().trimStart('-').trim()
                if (value.isBlank()) return@mapNotNull null
                val category = labelToCategory.firstOrNull { (labelText, _) ->
                    label == normalizeArabic(labelText) || label.startsWith(normalizeArabic(labelText) + " ")
                }?.second ?: return@mapNotNull null
                LearnedFact(
                    id = "profile-${label.hashCode()}-${value.hashCode()}",
                    text = value.take(220),
                    category = category,
                    addedAt = now
                )
            }
            .distinctBy { it.text }
    }

    fun greeting(settings: AssistantSettings, userName: String): String {
        val name = userName.ifBlank { "صديقي" }
        return when (settings.tone) {
            AssistantTone.FORMAL -> "أهلًا بك يا $name، أنا ${settings.name}، مساعدك الشخصي في مُنظِّم. كيف أقدر أساعدك اليوم؟"
            AssistantTone.CALM -> "أهلًا يا $name، أنا ${settings.name}. موجود معاك بهدوء، اسألني عن يومك أو قولي عن نفسك."
            AssistantTone.PLAYFUL -> "أهلًا يا $name! أنا ${settings.name}، مساعدك الشخصي. جاهز نرتب اليوم مع بعض؟ اسألني عن وردك أو مهامك أو الصلاة الجاية."
        }
    }

    /**
     * تحية متكيفة من السياق الواسع: تذكر حال اليوم الفعلي (مهام، ورد، سلسلة،
     * مهمة متأخرة) بدل التحية الثابتة نفسها كل مرة. [profileFacts] حقائق
     * محلَّلة من الملف التعريفي الملصوق (A2): الاسم المفضل يُنادى به،
     * والهدف الأول يُذكَّر به — لا اختلاق إن لم يوجد ملف.
     */
    fun greeting(
        settings: AssistantSettings,
        snapshot: AssistantSnapshot,
        profileFacts: List<LearnedFact> = emptyList()
    ): String {
        val preferredName = profileFacts.firstOrNull { it.category == "اسم" }?.text
        val user = preferredName ?: snapshot.userName.ifBlank { "صديقي" }
        val context = snapshot.context
        val parts = ArrayList<String>()
        parts += when (settings.tone) {
            AssistantTone.FORMAL -> "أهلًا بك يا $user"
            AssistantTone.CALM -> "أهلًا يا $user"
            AssistantTone.PLAYFUL -> "أهلًا يا $user!"
        }
        val state = ArrayList<String>()
        if (snapshot.openTasksToday > 0) state += "عندك ${snapshot.openTasksToday} مهام النهاردة"
        if (!snapshot.wirdDoneToday) state += "وردك لسه ما اتسجّلش"
        if (context.overdueCount > 0) state += "وفيه ${context.overdueCount} مهام متأخرة"
        if (context.wirdStreakDays >= 3) state += "وسلسلة وردك ${context.wirdStreakDays} أيام"
        profileFacts.firstOrNull { it.category == "هدف" }?.let { goal ->
            state += "وهدفك «${goal.text}» لسه قدامك"
        }
        if (state.isEmpty()) {
            parts += when (settings.tone) {
                AssistantTone.FORMAL -> "يومك يبدو هادئًا حتى الآن. كيف أقدر أساعدك؟"
                AssistantTone.CALM -> "يوم هادي لحد دلوقتي. موجود معاك."
                AssistantTone.PLAYFUL -> "يومك فاضي لحد دلوقتي — نرتبه مع بعض؟"
            }
        } else {
            parts += state.joinToString("، ") + "."
        }
        return parts.joinToString("، ")
    }

    /** يختار صياغة من مجموعة متنوعة ببذرة اليوم واسم المستخدم — لا تكرار يومي. */
    private fun pick(pool: List<String>, seed: Long): String =
        pool[Math.floorMod(seed, pool.size.toLong()).toInt()]

    /** بذرة تنويع الصياغة: يوم مختلف = صياغة مختلفة لنفس الموقف. */
    private fun varietySeed(snapshot: AssistantSnapshot, today: LocalDate): Long =
        today.toEpochDay() * 31 + snapshot.userName.hashCode()

    /**
     * ملخص اليوم عبر كل الأقسام: مهام، ورد، تركيز، عادات، مزاج، فلوس،
     * تمارين — في سطور قصيرة، مع خطوة تالية واحدة مقترحة.
     */
    private fun daySummaryAnswer(snapshot: AssistantSnapshot, settings: AssistantSettings): String {
        val user = snapshot.userName.ifBlank { "صديقي" }
        val context = snapshot.context
        val lines = ArrayList<String>()
        lines += "ملخص يومك يا $user:"
        val tasksLine = when {
            snapshot.openTasksToday == 0 && snapshot.doneTasksToday == 0 -> "• مهام: مفيش مهام مستحقة النهاردة."
            else -> "• مهام: خلّصت ${snapshot.doneTasksToday}، ولسه ${snapshot.openTasksToday} مفتوحة."
        }
        lines += tasksLine
        if (context.overdueCount > 0) lines += "• متأخر: ${context.overdueCount} مهام فات موعدها."
        lines += if (snapshot.wirdDoneToday) {
            "• ورد: متسجّل — ${snapshot.wirdPagesPerDay} صفحات، وسلسلتك ${context.wirdStreakDays} أيام."
        } else {
            "• ورد: لسه ما اتسجّلش (${snapshot.wirdPagesPerDay} صفحات من سورة ${snapshot.wirdSurah})."
        }
        if (context.focusMinutesToday > 0) {
            lines += "• تركيز: ${context.focusMinutesToday} دقيقة في ${context.focusSessionsToday} جلسات."
        }
        if (snapshot.habitsTotal > 0) {
            lines += "• عادات: ${context.habitsDoneTodayCount} من ${snapshot.habitsTotal} اتعملت النهاردة."
        }
        if (context.spentToday > 0) lines += "• فلوس: صرفت النهاردة ${formatMoney(context.spentToday)}."
        context.moodToday?.let { lines += "• مزاجك النهاردة: ${moodLabel(it)}." }
        val nextStep = when {
            !snapshot.wirdDoneToday -> "الخطوة الجاية: وردك — صفحتين بس."
            context.overdueTasks.isNotEmpty() -> "الخطوة الجاية: «${context.overdueTasks.first().title}» — أقدم مهمة متأخرة."
            snapshot.openTasksToday > 0 && snapshot.nextTaskTitle != null -> "الخطوة الجاية: «${snapshot.nextTaskTitle}»."
            else -> "يومك شبه مكتمل — راحة مستحقة."
        }
        lines += nextStep
        return lines.joinToString("\n")
    }

    /**
     * اقتراح خطة متكيف: المتأخر أولًا، ثم المهمة التالية، مع موعد التركيز
     * المفضل ومدة الجلسة المفضلة من التعلّم، وساعة الورد المعتادة.
     */
    private fun planAdviceAnswer(
        snapshot: AssistantSnapshot,
        settings: AssistantSettings,
        profileFacts: List<LearnedFact> = emptyList()
    ): String {
        val user = snapshot.userName.ifBlank { "صديقي" }
        val context = snapshot.context
        val learned = context.learned
        val lines = ArrayList<String>()
        lines += "طيب يا $user، دي خطتي المقترحة لباقي يومك — من بياناتك مش من قالب:"
        var step = 1
        context.overdueTasks.firstOrNull()?.let { overdue ->
            val lateNote = if (context.mostDelayedCategory != null) {
                " (مهام «${context.mostDelayedCategory.label}» بتتأخر منك عادةً، فنخلصها الأول)"
            } else {
                ""
            }
            lines += "${step++}. «${overdue.title}» — متأخرة$lateNote."
        }
        snapshot.nextTaskTitle?.let { next ->
            val minutes = learned.preferredFocusMinutes ?: 25
            val hourNote = learned.bestFocusHours.firstOrNull()?.let { hour ->
                " — وأفضل وقت تركيز ليك الساعة ${hour.hour}:00"
            }.orEmpty()
            lines += "${step++}. جلسة تركيز $minutes دقيقة على «$next»$hourNote."
        }
        if (!snapshot.wirdDoneToday) {
            val hourNote = learned.bestWirdHour?.let { hour ->
                " (بتقراه عادةً الساعة ${hour.hour}:00)"
            }.orEmpty()
            lines += "${step++}. وردك: ${snapshot.wirdPagesPerDay} صفحات من سورة ${snapshot.wirdSurah}$hourNote."
        }
        if (step == 1) {
            // بلا مستعجل: هدف الملف التعريفي خطوة أولى مقترحة بدل فراغ.
            val goal = profileFacts.firstOrNull { it.category == "هدف" }
            if (goal != null) {
                lines += "مفيش حاجة مستعجلة — بس هدفك «${goal.text}» لسه قدامك. نبدأ بخطوة صغيرة منه النهاردة؟"
            } else {
                lines += "مفيش حاجة مستعجلة — يومك ماشي. لو حابب، نبدأ جلسة تركيز قصيرة؟"
            }
        }
        return lines.joinToString("\n")
    }

    /** إجابة الفلوس من بيانات «حياتي» الفعلية: اليوم والشهر وأكثر فئة. */
    private fun moneyAnswer(snapshot: AssistantSnapshot, settings: AssistantSettings): String {
        val user = snapshot.userName.ifBlank { "صديقي" }
        val context = snapshot.context
        val learned = context.learned
        val lines = ArrayList<String>()
        lines += "فلوسك يا $user:"
        lines += "• النهاردة: ${formatMoney(context.spentToday)}."
        lines += "• الشهر ده: ${formatMoney(context.spentMonth)}."
        if (learned.avgDailySpend30d > 0) {
            lines += "• متوسطك اليومي آخر 30 يوم: ${formatMoney(learned.avgDailySpend30d)}."
        }
        learned.topExpenseCategory?.let { lines += "• أكتر حاجة بتصرف عليها: «$it»." }
        if (context.spentToday > learned.avgDailySpend30d * 1.5 && learned.avgDailySpend30d > 0) {
            lines += "النهاردة صرفك أعلى من متوسطك بكتير — لو مش ضروري، كفاية كده النهاردة."
        }
        return lines.joinToString("\n")
    }

    /**
     * استرجاع ذاكرة: يطابق كلمات الرسالة مع الذكريات المحفوظة (بالفئة أو
     * النص)، ويذكر أعلى ثقة؛ وإن لم يجد شيئًا يعدد آخر ما تعلّمه بصراحة.
     */
    private fun memoryAnswer(
        query: String,
        snapshot: AssistantSnapshot,
        settings: AssistantSettings,
        facts: List<LearnedFact> = emptyList()
    ): Pair<String, Long?> {
        val user = snapshot.userName.ifBlank { "صديقي" }
        // A2: مصدر واحد للحقائق — الحقائق المتعلمة من المحادثة (prefs) تُبحث
        // هنا أيضًا، فلا تُحفظ مرتين (prefs + DB) لمجرد استرجاعها.
        val normalizedQuery = normalizeArabic(query)
        val queryWords = normalizedQuery.split(Regex("\\s+")).filter { it.length >= 3 }
        val factMatch = facts.firstOrNull { fact ->
            val factText = normalizeArabic(fact.text)
            queryWords.any { word -> factText.contains(word) || normalizeArabic(fact.category).contains(word) }
        }
        if (factMatch != null) {
            return Pair("أيوه فاكر يا $user: «${factMatch.text}» — قلتلي كده قبل كده.", null)
        }
        val memories = snapshot.context.memories
        if (memories.isEmpty() && facts.isEmpty()) {
            return Pair(
                "لسه ما حفظتش عنك حاجة من المحادثة يا $user — قولي عن نفسك (هدفك، روتينك، اللي بتحبه) وأنا أحفظه وأستخدمه.",
                null
            )
        }
        val keywords = query.split(Regex("\\s+")).filter { it.length >= 3 }
        val matched = memories
            .filter { memory ->
                keywords.any { word -> memory.text.contains(word) || memory.category.contains(word) }
            }
            .maxByOrNull { it.confidence * 100 + it.useCount }
        if (matched != null) {
            return Pair("أيوه فاكر يا $user: «${matched.text}» — قلتلي كده قبل كده.", matched.id)
        }
        if (memories.isEmpty()) {
            val listedFacts = facts.take(3).joinToString("؛ ") { it.text }
            return Pair("فاكر عنك يا $user: $listedFacts.", null)
        }
        val top = memories.sortedByDescending { it.confidence }.take(3)
        val listed = top.joinToString("؛ ") { it.text }
        return Pair("فاكر عنك يا $user: $listed.", top.firstOrNull()?.id)
    }

    /** نص اقتراح المهمة من كلام عادي — الشاشة تعرض زر تأكيد بجانبه. */
    private fun proposeTaskText(proposed: ClassifiedIntent.ProposeTask, snapshot: AssistantSnapshot): String {
        val user = snapshot.userName.ifBlank { "صديقي" }
        val whenText = proposed.dueAt?.let { " — وحطيت لها موعدًا من كلامك" }.orEmpty()
        return "سمعتك بتقول «${proposed.title}» يا $user$whenText — أضيفها كمهمة عشان أتابعك فيها؟ اضغط «أيوه ضيفها» وأنا أضيفها على طول."
    }

    private fun moodLabel(mood: Int): String = when (mood) {
        1 -> "يوم صعب"
        2 -> "مش أحسن حاجة"
        3 -> "عادي"
        4 -> "كويس"
        5 -> "ممتاز"
        else -> "غير مسجّل"
    }

    private fun formatMoney(amount: Double): String {
        val rounded = if (amount == amount.toLong().toDouble()) amount.toLong().toString() else "%.2f".format(amount)
        return "$rounded ج.م"
    }

    fun reply(
        input: String,
        snapshot: AssistantSnapshot,
        settings: AssistantSettings,
        facts: List<LearnedFact>,
        profileText: String,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): AssistantReply {
        val text = input.trim()
        // A1: تطبيع واحد قبل كل مطابقة — المصنّف وdecideAction وanswerFor
        // تستقبل النص المطبَّع؛ النص الأصلي يبقى لاستخراج العناوين (فلا
        // تُحفظ مهمة بعنوان مطبَّع) وللحقائق المتعلمة (تُحفظ كما قالها المستخدم).
        val originalLower = text.lowercase()
        val lower = normalizeArabic(originalLower)
        val learned = extractFact(text, lower)
        val profileFacts = parseProfileFacts(profileText)
        // فهم النية من كلام حر أولًا — قبل محلل الأوامر ذي الصيغ الثابتة:
        // «أعمل إيه دلوقتي؟» كانت تتحول خطأً إلى مهمة بعنوان «إيه دلوقتي»،
        // و«خلصت» بلا موضوع كانت تسقط في الرد الاحتياطي العام.
        when (val classified = AssistantIntentClassifier.classify(normalizeArabic(text), snapshot, today, zone)) {
            ClassifiedIntent.AskDaySummary ->
                return AssistantReply(text = toneWrap(daySummaryAnswer(snapshot, settings), settings.tone), learnedFact = learned)
            ClassifiedIntent.AskPlanAdvice ->
                return AssistantReply(text = toneWrap(planAdviceAnswer(snapshot, settings, profileFacts), settings.tone), learnedFact = learned)
            ClassifiedIntent.AskMoney ->
                return AssistantReply(text = toneWrap(moneyAnswer(snapshot, settings), settings.tone), learnedFact = learned)
            is ClassifiedIntent.AskMemory -> {
                val (answer, usedId) = memoryAnswer(classified.query, snapshot, settings, facts)
                return AssistantReply(text = toneWrap(answer, settings.tone), learnedFact = learned, usedMemoryId = usedId)
            }
            is ClassifiedIntent.ProposeTask ->
                return AssistantReply(
                    text = toneWrap(proposeTaskText(classified, snapshot), settings.tone),
                    learnedFact = learned,
                    proposedTask = ProposedTask(
                        classified.title, classified.dueAt, classified.priorityLevel, classified.recurrence,
                        plannedMinutes = classified.durationMinutes
                    )
                )
            is ClassifiedIntent.Clarify ->
                return AssistantReply(text = classified.question, learnedFact = learned, chips = classified.options)
            ClassifiedIntent.PassThrough -> Unit
        }
        // النوايا التنفيذية تُفحص أولًا: «ضيف مهمة…» و«سجّل وردي» مثلًا تحوي
        // كلمات («مهمة»، «ورد») تطابق أيضًا فروع الإجابة الإخبارية أدناه.
        val decision = decideAction(lower, originalLower, snapshot, today, zone)
        // التحية والرد الاحتياطي لهما صياغتهما الخاصة بالنبرة أصلًا، فلا
        // يُغلَّفان مرة ثانية؛ باقي فروع answerFor تُغلَّف داخلها.
        val core = decision?.text ?: answerFor(lower, snapshot, settings, facts, profileText, today, profileFacts)
        val withLearning = if (learned != null) {
            "$core\n\nحفظت إن «${learned.text}»، وهستخدمها في ردودي الجاية."
        } else {
            core
        }
        return AssistantReply(
            text = withLearning,
            learnedFact = learned,
            action = decision?.action,
            chips = decision?.chips ?: emptyList()
        )
    }

    /** نتيجة فحص نية تنفيذية: فعل يُنفَّذ مع نص تأكيده، أو نص توضيح بلا فعل.
     * [chips] رقاقات تأكيد تُعرض مع النص (مثلًا تأكيد بدء جلسة قبل تنفيذها). */
    private data class ActionDecision(
        val action: AssistantAction?,
        val text: String,
        val chips: List<String> = emptyList()
    )

    /**
     * يطابق نية تنفيذية في الرسالة ويبني فعلها ونص تأكيدها، أو null إن لم تكن
     * الرسالة أمرًا تنفيذيًا. الترتيب مقصود: تسجيل الورد قبل تعليم مهمة
     * كمنجزة («خلصت الورد» ليست مهمة)، وفتح المصحف قبل إجابة حالة الورد.
     * [lower] نص مطبَّع للمطابقة؛ [originalLower] النص الأصلي (مصغَّر) لاستخراج
     * العناوين كما كتبها المستخدم.
     */
    private fun decideAction(
        lower: String,
        originalLower: String,
        snapshot: AssistantSnapshot,
        today: LocalDate,
        zone: ZoneId
    ): ActionDecision? {
        addTaskDecision(lower, originalLower, today, zone)?.let { return it }
        logWirdDecision(lower, snapshot)?.let { return it }
        startFocusDecision(lower, snapshot, today)?.let { return it }
        openQuranDecision(lower, snapshot)?.let { return it }
        openTodayDecision(lower)?.let { return it }
        completeTaskDecision(lower, snapshot)?.let { return it }
        return null
    }

    /** يحذف كلمات الأمر من الرسالة ويبقي ما يصلح عنوانًا أو اسم مهمة.
     * علامات الترقيم الطرفية («؟» مثلًا) تُقصّ من التوكنات حتى لا تلتصق
     * بكلمة فتمنع حذفها أو مطابقتها. */
    private fun stripCommandWords(
        lower: String,
        words: Set<String>,
        dropNumbers: Boolean = true
    ): String =
        lower.split(Regex("\\s+"))
            .map { it.trim('؟', '?', '!', '.', '،', ',') }
            .filter { token ->
                token.isNotEmpty() && token !in words &&
                    (!dropNumbers || token.all { ch -> !ch.isDigit() })
            }
            .joinToString(" ").trim()

    /** يطابق نصًا مع المهام المفتوحة: تطابق تام أو احتواء في أي اتجاه.
     * الطرفان يُطبَّعان عربيًا (A1) فتطابق «مراجعه» مهمة «مراجعة». */
    private fun matchOpenTasks(query: String, tasks: List<AssistantTaskRef>): List<AssistantTaskRef> {
        val clean = normalizeArabic(query.trim())
        if (clean.isEmpty()) return emptyList()
        val exact = tasks.filter { normalizeArabic(it.title.lowercase()) == clean }
        if (exact.isNotEmpty()) return exact
        return tasks.filter { task ->
            val title = normalizeArabic(task.title.lowercase())
            title.contains(clean) || clean.contains(title)
        }
    }

    /** نص يلخّص مرشحين غامضين ليسأل المستخدم عن المقصود. */
    private fun ambiguousText(query: String, candidates: List<AssistantTaskRef>, user: String): String {
        val listed = candidates.take(3).joinToString("؛ ") { "«${it.title}»" }
        return "لقيت أكتر من مهمة شبه «$query» يا $user: $listed. قولي اسمها بالظبط، أو افتح «مهامي» واختارها."
    }

    private fun addTaskDecision(lower: String, originalLower: String, today: LocalDate, zone: ZoneId): ActionDecision? {
        // «سجّل ورد اليوم» تحوي فعل إضافة لكنها تسجيل ورد، لا مهمة.
        if (lower.contains("ورد")) return null
        val addVerbs = setOf("ضيف", "أضف", "اضف", "أضيف", "ضيفي", "سجل", "سجّل", "حط", "اعمل", "أعمل", "أنشئ", "انشئ")
        val mentionsTask = lower.contains("مهمة") || lower.contains("مهمه")
        val startsWithVerb = addVerbs.any { lower.startsWith(it) }
        if (!mentionsTask && !startsWithVerb) return null
        if (!addVerbs.any { lower.contains(it) }) return null
        // العنوان يُستخرج من النص الأصلي (لا المطبَّع) فيُحفظ كما كتبه المستخدم؛
        // والأرقام تبقى: المحلل يحتاجها لاستخراج الساعة والمدة («الساعة 6»).
        val cleaned = stripCommandWords(
            originalLower,
            addVerbs + setOf("مهمة", "مهمه", "لي", "جديدة", "جديده", "من فضلك", "لمدة", "لمده"),
            dropNumbers = false
        )
        val parsed = QuickCaptureParser.parse(cleaned, today)
        val title = parsed.title.trim()
        if (title.isBlank()) {
            return ActionDecision(
                action = null,
                text = "قولي عنوان المهمة — مثلًا «ضيف مهمة مراجعة فيزياء الساعة 6» — وأنا أضيفها لك على طول."
            )
        }
        val dueAt = if (parsed.date != null || parsed.hour != null) {
            val date = parsed.date ?: today
            val time = if (parsed.hour != null) {
                LocalTime.of(parsed.hour, parsed.minute)
            } else {
                // تاريخ بلا وقت: نهاية اليوم موعدًا.
                LocalTime.of(23, 59)
            }
            LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
        } else {
            null
        }
        val dueText = if (dueAt == null) {
            ""
        } else {
            // بلا تاريخ مذكور يكون الموعد اليوم؛ وبلا وقت مذكور يكون نهاية اليوم.
            val dateLabel = when (parsed.date) {
                null -> "النهاردة"
                today -> "النهاردة"
                today.plusDays(1) -> "بكرة"
                else -> parsed.date.toString()
            }
            val timeLabel = if (parsed.hour != null) {
                " الساعة ${parsed.hour.toString().padStart(2, '0')}:${parsed.minute.toString().padStart(2, '0')}"
            } else {
                ""
            }
            "، موعدها $dateLabel$timeLabel"
        }
        return ActionDecision(
            action = AssistantAction.AddTask(
                title = title,
                dueAt = dueAt,
                priorityLevel = parsed.priorityLevel,
                recurrence = parsed.recurrence,
                // A2: مدة المهمة من كلام المستخدم («لمدة ساعتين») بدل 30 ثابتة؛
                // بلا مدة مذكورة يبقى null فيستخدم التنفيذ الافتراضي.
                plannedMinutes = parsed.durationMinutes
            ),
            text = "تم إضافة المهمة «$title»$dueText، وهتلاقيها في «مهامي»."
        )
    }

    private fun logWirdDecision(lower: String, snapshot: AssistantSnapshot): ActionDecision? {
        if (!lower.contains("ورد")) return null
        val logVerbs = setOf("سجل", "سجّل", "سجلت", "سجّلت", "خلصت", "خلّصت", "قرأت", "قرات", "قريت", "تم", "عملت")
        if (!logVerbs.any { lower.contains(it) }) return null
        val user = snapshot.userName.ifBlank { "صديقي" }
        if (snapshot.wirdDoneToday) {
            return ActionDecision(
                action = null,
                text = "ما شاء الله يا $user، ورد النهاردة متسجّل أصلًا: ${snapshot.wirdPagesPerDay} صفحات من سورة ${snapshot.wirdSurah}."
            )
        }
        return ActionDecision(
            action = AssistantAction.LogWird,
            text = "تم تسجيل ورد اليوم: ${snapshot.wirdPagesPerDay} صفحات من سورة ${snapshot.wirdSurah}. تقبّل الله منك."
        )
    }

    private fun startFocusDecision(lower: String, snapshot: AssistantSnapshot, today: LocalDate): ActionDecision? {
        if (!lower.contains("جلسة") && !lower.contains("جلسه") && !lower.contains("تركيز")) return null
        val startVerbs = setOf("ابدأ", "ابدا", "ابدء", "شغل", "يلا", "عايز", "عايزه", "نبدأ", "نبدا")
        if (!startVerbs.any { lower.contains(it) }) return null
        val user = snapshot.userName.ifBlank { "صديقي" }
        val parsed = QuickCaptureParser.parse(lower, today)
        val minutes = (parsed.durationMinutes ?: DEFAULT_FOCUS_MINUTES).coerceIn(5, 120)
        val query = stripCommandWords(
            lower,
            setOf(
                "ابدأ", "ابدا", "ابدء", "شغل", "جلسة", "جلسه", "تركيز", "لمهمة", "لمهمه",
                "مهمة", "مهمه", "على", "في", "يلا", "عايز", "عايزه", "لمدة", "دقيقة",
                "دقيقه", "دقائق", "دقايق", "ساعة", "ساعه", "ساعات", "نص", "نصف"
            )
        )
        if (query.isBlank()) {
            // A2 (A-19): صيغة السؤال («نبدأ جلسة؟» — كما ترسلها رقاقة
            // focus_invite) تسأل أولًا ولا تبدأ جلسة فورًا؛ التأكيد برقاقة.
            if (lower.trimEnd().endsWith("؟") || lower.trimEnd().endsWith("?")) {
                return ActionDecision(
                    action = null,
                    text = "أبدأ لك جلسة تركيز عامة لمدة $minutes دقيقة؟",
                    chips = listOf("أيوه ابدأ جلسة تركيز", "لا مش دلوقتي")
                )
            }
            return ActionDecision(
                action = AssistantAction.StartFocus(taskId = null, taskTitle = null, minutes = minutes),
                text = "بدأت جلسة تركيز عامة لمدة $minutes دقيقة — بالتوفيق!"
            )
        }
        val matches = matchOpenTasks(query, snapshot.openTasks)
        // سؤال قبل البدء حين يُصاغ الطلب كسؤال (رقاقة الدعوة): رقاقة التأكيد
        // تحمل اسم المهمة نفسه فيبدأها التنفيذ بعدها بلا غموض.
        if (lower.trimEnd().endsWith("؟") || lower.trimEnd().endsWith("?")) {
            return when {
                matches.size == 1 -> {
                    val task = matches.first()
                    ActionDecision(
                        action = null,
                        text = "أبدأ لك جلسة تركيز لمهمة «${task.title}» لمدة $minutes دقيقة؟",
                        chips = listOf("أيوه ابدأ جلسة «${task.title}»", "لا مش دلوقتي")
                    )
                }
                matches.size > 1 -> ActionDecision(action = null, text = ambiguousText(query, matches, user))
                else -> ActionDecision(
                    action = null,
                    text = "مش لاقي مهمة بالاسم ده يا $user. جرّب «ابدأ جلسة تركيز» لجلسة عامة، أو قولي اسم المهمة زي ما هو في «مهامي»."
                )
            }
        }
        return when {
            matches.size == 1 -> {
                val task = matches.first()
                ActionDecision(
                    action = AssistantAction.StartFocus(taskId = task.id, taskTitle = task.title, minutes = minutes),
                    text = "بدأت جلسة تركيز لمهمة «${task.title}» لمدة $minutes دقيقة — بالتوفيق!"
                )
            }
            matches.size > 1 -> ActionDecision(action = null, text = ambiguousText(query, matches, user))
            else -> ActionDecision(
                action = null,
                text = "مش لاقي مهمة بالاسم ده يا $user. جرّب «ابدأ جلسة تركيز» لجلسة عامة، أو قولي اسم المهمة زي ما هو في «مهامي»."
            )
        }
    }

    private fun openQuranDecision(lower: String, snapshot: AssistantSnapshot): ActionDecision? {
        val openVerbs = setOf("افتح", "افتحلي", "هات", "وريني", "شوف", "عايز أقرا", "عايز اقرا")
        if (!openVerbs.any { lower.contains(it) }) return null
        if (!lower.contains("مصحف") && !lower.contains("قرآن") && !lower.contains("قران") && !lower.contains("ورد")) return null
        return ActionDecision(
            action = AssistantAction.OpenQuran,
            text = "فتحت المصحف على صفحة ورد اليوم (صفحة ${snapshot.wirdNextPage}). قراءة مباركة!"
        )
    }

    private fun openTodayDecision(lower: String): ActionDecision? {
        if (!lower.contains("خطة اليوم")) return null
        val showVerbs = setOf("افتح", "اعرض", "أعرض", "وريني", "هات", "شوف", "عرض", "اظهر", "أظهر")
        if (!showVerbs.any { lower.contains(it) }) return null
        return ActionDecision(
            action = AssistantAction.OpenToday,
            text = "فتحت خطة اليوم — دي مهامك وخطوتك التالية."
        )
    }

    private fun completeTaskDecision(lower: String, snapshot: AssistantSnapshot): ActionDecision? {
        if (lower.contains("ورد")) return null
        val doneVerbs = setOf("خلص", "خلّص", "علّم", "علم", "أنجز", "انجز")
        if (!doneVerbs.any { lower.contains(it) }) return null
        val user = snapshot.userName.ifBlank { "صديقي" }
        val query = stripCommandWords(
            lower,
            setOf(
                "خلص", "خلّص", "خلصت", "خلصتي", "علّم", "علم", "أنجز", "انجز",
                "أنجزت", "انجزت", "مهمة", "مهمه", "كمنجزة", "كمنجزه", "منجزة",
                "منجزه", "تم", "دي", "ده", "اللي"
            )
        )
        val mentionsTask = lower.contains("مهمة") || lower.contains("مهمه")
        if (query.isBlank() && !mentionsTask) return null
        val matches = matchOpenTasks(query, snapshot.openTasks)
        // بلا ذكر «مهمة» وبلا تطابق مع مهمة موجودة، الرسالة ليست أمر إنجاز
        // («علمني إنجليزي» مثلًا تحوي «علم» ولا تعني مهمة).
        if (matches.isEmpty() && !mentionsTask) return null
        return when {
            matches.size == 1 -> {
                val task = matches.first()
                ActionDecision(
                    action = AssistantAction.CompleteTask(taskId = task.id, title = task.title),
                    text = "تمام، علمت مهمة «${task.title}» كمنجزة. أحسنت!"
                )
            }
            matches.size > 1 -> ActionDecision(action = null, text = ambiguousText(query, matches, user))
            query.isBlank() -> ActionDecision(
                action = null,
                text = "قولي اسم المهمة اللي خلصتها — مثلًا «خلصت مهمة مراجعة فيزياء» — وأنا أعلّمها كمنجزة."
            )
            else -> ActionDecision(
                action = null,
                text = "مش لاقي مهمة بالاسم ده بين مهامك المفتوحة يا $user. جرّب تقول اسمها زي ما هو في «مهامي»، أو اسألني «عندي مهام إيه؟» عشان أراجعها معاك."
            )
        }
    }

    /**
     * يستخرج حقيقة واحدة من رسالة المستخدم عندما تعبّر صراحة عن معلومة عنه.
     * القواعد محافظة عمدًا: لا تُحفظ إلا الجمل القصيرة الواضحة، ولا تُستخدم
     * الحقائق إلا لعرضها على المستخدم في ردود لاحقة.
     */
    fun extractFact(text: String, lower: String = text.lowercase()): LearnedFact? {
        if (text.length < 8 || text.length > 220) return null
        val category = when {
            lower.contains("هدفي") || lower.contains("عايز أتعلم") || lower.contains("عايز اتعلم") ||
                lower.contains("نفسي أتعلم") || lower.contains("نفسي اتعلم") -> "هدف"
            lower.contains("بنوم") || lower.contains("أنام") || lower.contains("انام") ||
                lower.contains("بصحى") || lower.contains("بصحي") || lower.contains("بصحا") ||
                lower.contains("روتيني") -> "روتين"
            lower.contains("وزني") || lower.contains("طولي") ||
                lower.contains("عايز أخس") || lower.contains("عايز اخس") -> "صحة"
            lower.contains("بحب") || lower.contains("هوايتي") || lower.contains("هوايتى") -> "اهتمامات"
            lower.contains("بشتغل") || lower.contains("بدرس") || lower.contains("بذاكر") -> "دراسة وعمل"
            else -> return null
        }
        return LearnedFact(
            id = "fact-${System.currentTimeMillis()}",
            text = text,
            category = category,
            addedAt = System.currentTimeMillis()
        )
    }

    private fun answerFor(
        lower: String,
        snapshot: AssistantSnapshot,
        settings: AssistantSettings,
        facts: List<LearnedFact>,
        profileText: String,
        today: LocalDate = LocalDate.now(),
        profileFacts: List<LearnedFact> = emptyList()
    ): String {
        val name = settings.name
        val user = snapshot.userName.ifBlank { "صديقي" }
        // التحية لها صياغتها الخاصة بالنبرة أصلًا — تُعاد كما هي بلا غلاف.
        if (isGreeting(lower)) return when (settings.tone) {
            AssistantTone.FORMAL -> "أهلًا بك يا $user، يوم موفق بإذن الله. هل أراجع معك مهام اليوم أو وردك؟"
            AssistantTone.CALM -> "أهلًا يا $user. يومك ماشي بهدوء، وأنا معاك خطوة بخطوة."
            AssistantTone.PLAYFUL -> "أهلًا أهلًا يا $user! يوم جديد وفرصة جديدة. تحب نبدأ بالمهام ولا بالورد؟"
        }
        // الرد الاحتياطي له صياغته الخاصة بالنبرة — يُعاد بلا غلاف.
        var fellBack = false
        val raw = when {
            // A1: صيغ الهوية بعد التطبيع («انتا مين»→«انت مين»، «مين انتا»→«مين انت»).
            lower.contains("اسمك") || lower.contains("انت مين") || lower.contains("مين انت") ||
                lower.contains("عرف نفسك") || lower.contains("من انت") ||
                lower.contains("مين اللي بيكلمني") || lower.contains("مين بيكلمني") ->
                "أنا $name، مساعدك الشخصي في تطبيق مُنظِّم. بتكلم باللهجة المصرية، وكل اللي أعرفه عنك محفوظ على جهازك بس — مفيش حاجة بتطلع برّه."
            lower.contains("تقدر تعمل") || lower.contains("بتعمل ايه") || lower.contains("بتعمل إيه") ||
                lower.contains("ساعدني ازاي") || lower.contains("ساعدني إزاي") ->
                "أقدر أنفّذ لك حاجات على طول من هنا، على جهازك من غير إنترنت: أضيف مهمة («ضيف مهمة مراجعة فيزياء الساعة 6»)، أسجّل ورد اليوم، أبدأ جلسة تركيز، أفتح المصحف على ورد اليوم، أعرض خطة اليوم، وأعلّم مهمة كمنجزة. وكمان أتابع معاك ورد القرآن ومهام اليوم والصلاة الجاية وسعراتك وعاداتك من بيانات التطبيق نفسه."
            lower.contains("ورد") || lower.contains("قرآن") || lower.contains("قران") ||
                lower.contains("قرأت") || lower.contains("قرات") ->
                if (snapshot.wirdDoneToday) {
                    // ربط عبر الأقسام: الورد + التركيز + السلسلة، بصياغة متنوعة.
                    val streak = snapshot.context.wirdStreakDays
                    val celebration = if (streak >= 3) {
                        " " + pick(
                            listOf(
                                "وسلسلتك $streak أيام متواصلة — ما شاء الله!",
                                "دي السلسلة رقم $streak على التوالي — كمّل بنفس الإيقاع.",
                                "ما شاء الله، $streak أيام ورد ورا بعض."
                            ),
                            varietySeed(snapshot, today)
                        )
                    } else {
                        ""
                    }
                    val focusNote = if (snapshot.context.focusMinutesToday > 0) {
                        " وكمان ركزت النهاردة ${snapshot.context.focusMinutesToday} دقيقة — يوم متوازن."
                    } else {
                        ""
                    }
                    "ما شاء الله يا $user، ورد النهاردة اتسجّل: ${snapshot.wirdPagesPerDay} صفحات من سورة ${snapshot.wirdSurah}. إجمالي اللي قرأته ${snapshot.wirdTotalPages} صفحة.$celebration$focusNote"
                } else {
                    val hourNote = snapshot.context.learned.bestWirdHour?.let { hour ->
                        " بتقراه عادةً الساعة ${hour.hour}:00."
                    }.orEmpty()
                    "ورد النهاردة ${snapshot.wirdPagesPerDay} صفحات من سورة ${snapshot.wirdSurah}، بداية من صفحة ${snapshot.wirdNextPage}. لسه ما اتسجّلش$hourNote — افتح المصحف من «حياتي» واقرأ على مهلك."
                }
            lower.contains("مهام") || lower.contains("مهامي") || lower.contains("خطة اليوم") ||
                lower.contains("عندي ايه") || lower.contains("النهاردة") ->
                tasksAnswer(snapshot, user)
            lower.contains("صلاة") || lower.contains("الصلاة") || lower.contains("الفجر") ||
                lower.contains("الظهر") || lower.contains("العصر") || lower.contains("المغرب") ||
                lower.contains("العشاء") ->
                if (snapshot.nextPrayerName != null && snapshot.nextPrayerTime != null) {
                    "الصلاة الجاية صلاة ${snapshot.nextPrayerName} الساعة ${snapshot.nextPrayerTime}. لو حابب أظبط لك تذكيرها، هتلاقي الإعداد في قسم الصلاة."
                } else {
                    "لسه ما حددتش موقعك لمواقيت الصلاة. افتح قسم «الصلاة» من الإعدادات وحدد مدينتك، وهقولك الصلاة الجاية إمتى."
                }
            lower.contains("سعر") || lower.contains("سعرات") || lower.contains("أكلت") ||
                lower.contains("اكلت") || lower.contains("أكل") ->
                caloriesAnswer(snapshot, user)
            lower.contains("رياضة") || lower.contains("رياضه") || lower.contains("تمرين") ||
                lower.contains("تمارين") || lower.contains("جيم") ->
                if (snapshot.exerciseDaysThisWeek > 0) {
                    "اتمرنت ${snapshot.exerciseDaysThisWeek} من الأيام السبعة اللي فاتت، وده تقدم جميل. خطتك 3 أيام في الأسبوع — كمّل بنفس الإيقاع."
                } else {
                    "لسه ما سجّلتش تمرين في آخر 7 أيام. خطتك في «حياتي» 3 أيام أسبوعيًا، ولو حتى تمرين بيتي قصير النهاردة هيبقى بداية كويسة."
                }
            lower.contains("عادات") || lower.contains("عادة") || lower.contains("عاده") ->
                if (snapshot.habitsTotal > 0) {
                    "عندك ${snapshot.habitsTotal} عادات متسجلة، وخلّصت منها ${snapshot.habitsDoneToday} النهاردة. كمّلها واحدة واحدة من «حياتي»."
                } else {
                    "لسه ما سجّلتش عادات في «حياتي». ابدأ بعادة واحدة صغيرة — وردك مثلًا — وأنا هتابع معاك."
                }
            lower.contains("مزاج") || lower.contains("تعبان") || lower.contains("زهقان") ||
                lower.contains("حزين") || lower.contains("متضايق") || lower.contains("مكتئب") ->
                "سلامتك يا $user. لو اليوم تقيل، خفف الخطة للأساسيات بس: وردك ومهمة واحدة. أنا معاك، ومفيش داعي نستعجل."
            lower.contains("صحتي") || lower.contains("وزن") || lower.contains("وزني") ->
                caloriesAnswer(snapshot, user)
            lower.contains("تعرف عني") || lower.contains("ايه اللي تعرفه") ||
                lower.contains("إيه اللي تعرفه") || lower.contains("قول لي عن نفسي") ||
                lower.contains("قولي عن نفسي") -> aboutMeAnswer(snapshot, facts, profileText, user, profileFacts)
            lower.contains("شكرا") || lower.contains("شكرًا") ->
                "العفو يا $user، ده واجبي. أي وقت تحتاجني، أنا موجود."
            lower.contains("هدف") ->
                "هدفك خطوة مهمة يا $user. قولي هدفك بصيغة واضحة — مثلًا «هدفي أخلص مادة كذا قبل نهاية الشهر» — وهحفظه وأفكرك بيه."
            else -> {
                fellBack = true
                fallback(settings, user, snapshot, today)
            }
        }
        // كل إجابات المحتوى تُغلَّف بالنبرة (A2)؛ الاحتياطي يُعاد كما هو.
        return if (fellBack) raw else toneWrap(raw, settings.tone)
    }

    private fun tasksAnswer(snapshot: AssistantSnapshot, user: String): String {
        val next = snapshot.nextTaskTitle?.let { " والمهمة الجاية عندك: «$it»." } ?: ""
        // ربط عبر الأقسام: المتأخر وساعة التركيز المفضلة من التعلّم.
        val overdueNote = if (snapshot.context.overdueCount > 0) {
            " وفيه ${snapshot.context.overdueCount} مهام فات موعدها — أقدمها «${snapshot.context.overdueTasks.first().title}»."
        } else {
            ""
        }
        val focusNote = snapshot.context.learned.bestFocusHours.firstOrNull()?.let { hour ->
            " أفضل وقت تركيز ليك الساعة ${hour.hour}:00."
        }.orEmpty()
        return if (snapshot.openTasksToday == 0 && snapshot.doneTasksToday == 0) {
            "مفيش مهام مستحقة النهاردة يا $user — يوم هادي.$overdueNote لو حابب، ضيف مهمة صغيرة من زر الإضافة السريع."
        } else {
            "النهاردة عندك ${snapshot.openTasksToday} مهام لسه ما خلصتش، وخلّصت ${snapshot.doneTasksToday}.$next$overdueNote$focusNote ابدأ بأصغر واحدة، وأنا هتابع معاك."
        }
    }

    private fun caloriesAnswer(snapshot: AssistantSnapshot, user: String): String {
        val target = snapshot.dailyCalorieTarget
        val exerciseNote = if (snapshot.context.exerciseDays7d == 0) {
            " ولسه ما اتمرنتش في آخر 7 أيام — تمرين بيتي قصير النهاردة هيفرق."
        } else {
            ""
        }
        return if (target == null) {
            "لسه ما سجّلتش بيانات جسمك في «حياتي»، فمقدرش أحسب هدف سعراتك. سجّل وزنك وطولك ونشاطك هناك، وهقولك هدفك اليومي."
        } else {
            "هدفك اليومي $target سعرة، وسجّلت النهاردة ${snapshot.caloriesToday} سعرة يا $user. باقي لك ${maxOf(target - snapshot.caloriesToday, 0)} سعرة لحد نهاية اليوم.$exerciseNote"
        }
    }

    private fun aboutMeAnswer(
        snapshot: AssistantSnapshot,
        facts: List<LearnedFact>,
        profileText: String,
        user: String,
        profileFacts: List<LearnedFact> = emptyList()
    ): String {
        if (profileText.isBlank() && facts.isEmpty()) {
            return "لسه ما أعرفش عنك كتير يا $user — من التطبيق نفسه بس أعرف إن وردك ${snapshot.wirdTotalPages} صفحة إجمالي، وعندك ${snapshot.openTasksToday} مهام مفتوحة النهاردة. عرّفني عليك من تبويب «عرّفني عليك»، أو قولي هدفك وروتينك هنا في المحادثة."
        }
        val parts = ArrayList<String>()
        // A2: الملف المحلَّل يُعرض مصنفًا أولًا؛ الاقتباس الحرفي يبقى فقط
        // للملف بلا وسوم (لا يُفسَّر ولا يُخترع له معنى).
        if (profileFacts.isNotEmpty()) {
            val listed = profileFacts.take(4).joinToString("؛ ") { "${it.category}: ${it.text}" }
            parts.add("من ملفك التعريفي: $listed.")
        } else if (profileText.isNotBlank()) {
            // م10: الملف التعريفي يُستخدم دلاليًا — مقتطف من أول 3 أسطر غير فارغة
            // (حتى 240 حرفًا) بدل الإشارة إلى طوله فقط.
            val excerpt = profileText.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(3)
                .joinToString("؛ ")
            val shown = if (excerpt.length > 240) excerpt.take(237) + "…" else excerpt
            parts.add("من ملفك التعريفي: $shown.")
        }
        if (facts.isNotEmpty()) {
            val listed = facts.take(3).joinToString("؛ ") { it.text }
            parts.add("اتعلمت منك: $listed.")
        }
        parts.add(
            "من التطبيق نفسه: وردك ${snapshot.wirdTotalPages} صفحة إجمالي، " +
                "وعندك ${snapshot.openTasksToday} مهام مفتوحة النهاردة."
        )
        return parts.joinToString("\n")
    }

    /**
     * الرد الاحتياطي: بدل قالبين ثابتين لكل نبرة، يذكر أهم شيء معلّق فعلًا
     * (متأخر/ورد/عادة) ويتنوع بتغيّر اليوم — فلا يسمع المستخدم الجملة نفسها
     * كل مرة يسأل فيها سؤالًا غير مفهوم.
     */
    private fun fallback(
        settings: AssistantSettings,
        user: String,
        snapshot: AssistantSnapshot,
        today: LocalDate
    ): String {
        val pending = when {
            snapshot.context.overdueTasks.isNotEmpty() ->
                "عندك «${snapshot.context.overdueTasks.first().title}» متأخرة — تحب نخلصها الأول؟"
            !snapshot.wirdDoneToday ->
                "وردك النهاردة لسه ما اتسجّلش — تحب أفتح لك المصحف؟"
            snapshot.openTasksToday > 0 ->
                "عندك ${snapshot.openTasksToday} مهام مفتوحة النهاردة — تحب أرتبها لك؟"
            else -> null
        }
        val options = when (settings.tone) {
            AssistantTone.FORMAL -> listOf(
                "سؤال وجيه يا $user. لحد دلوقتي أقدر أساعدك في متابعة وردك ومهامك وصلاتك وسعراتك وعاداتك وفلوسك — جرّب تسألني عن واحد منهم، أو قولي «لخّص يومي».",
                "لم أفهم المقصود تمامًا. يمكنك سؤالي عن ورد اليوم أو مهامك أو الصلاة القادمة، وسأجيبك من بيانات التطبيق."
            )
            AssistantTone.CALM -> listOf(
                "مش متأكد إني فهمت قصدك يا $user، بس على مهلك. اسألني عن وردك أو مهامك أو صلاتك، وأنا هرد عليك بهدوء.",
                "خد وقتك يا $user. أنا هنا لو حابب تسأل عن يومك، أو تعرّفني على نفسك خطوة بخطوة."
            )
            AssistantTone.PLAYFUL -> listOf(
                "هممم، دي لسه مش من الحاجات اللي بعرف أرد عليها يا $user! بس اسألني عن وردك أو مهامك أو الصلاة الجاية — دي منطقتي.",
                "فهمت نص الكلام بس يا $user! جرّب تسألني: «وردي النهاردة إيه؟» أو «لخّص يومي» وأنا أجاوبك على طول."
            )
        }
        val base = pick(options, varietySeed(snapshot, today))
        return if (pending != null) "$base\n$pending" else base
    }

    private fun isGreeting(lower: String): Boolean =
        lower.contains("سلام") || lower.contains("صباح الخير") || lower.contains("مساء الخير") ||
            lower.contains("أهلا") || lower.contains("اهلا") || lower.contains("ازيك") ||
            lower.contains("إزيك") || lower.contains("عامل ايه") || lower.contains("عامل إيه") ||
            // A1: «عامل اي» (بلا هاء) صيغة أحمد الحرفية — كانت تسقط في الاحتياطي.
            lower.contains("عامل اي") ||
            lower == "هاي" || lower == "هلا"
}
