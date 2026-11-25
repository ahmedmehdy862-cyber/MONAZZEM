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
 * SharedPreferences على الجهاز فقط، بصيغة JSON، عمدًا بدل جدول جديد في قاعدة
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
    /** إضافة مهمة جديدة؛ [dueAt] بالمللي ثانية أو null بلا موعد محدد. */
    data class AddTask(
        val title: String,
        val dueAt: Long?,
        val priorityLevel: Int?,
        val recurrence: Recurrence
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

/** تخزين المساعد المحلي: SharedPreferences واحدة باسم ثابت، بلا قاعدة بيانات. */
internal object AssistantStore {
    private const val FILE = "assistant_preferences"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_FACTS = "facts"
    private const val KEY_PROFILE = "profile"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 200
    private const val MAX_FACTS = 100

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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

/** نتيجة رد المحرك: النص، مع حقيقة تعلّمها من الرسالة إن وُجدت، وفعل ينفَّذ إن طُلب. */
data class AssistantReply(
    val text: String,
    val learnedFact: LearnedFact? = null,
    val action: AssistantAction? = null
)

/**
 * محرك الردود: قواعد محلية فقط. يطابق نية الرسالة بكلمات مفتاحية عربية، ويبني
 * الرد من [AssistantSnapshot] (معرفة التطبيق) ومن الحقائق المحفوظة والملف
 * التعريفي، ثم يغلّف الرد بصياغة النبرة المختارة.
 */
internal object AssistantEngine {

    /** مدة جلسة التركيز الافتراضية بالدقائق عند عدم ذكر مدة (مطابقة لإعداد التطبيق). */
    private const val DEFAULT_FOCUS_MINUTES = 25

    fun greeting(settings: AssistantSettings, userName: String): String {
        val name = userName.ifBlank { "صديقي" }
        return when (settings.tone) {
            AssistantTone.FORMAL -> "أهلًا بك يا $name، أنا ${settings.name}، مساعدك الشخصي في مُنظِّم. كيف أقدر أساعدك اليوم؟"
            AssistantTone.CALM -> "أهلًا يا $name، أنا ${settings.name}. موجود معاك بهدوء، اسألني عن يومك أو قولي عن نفسك."
            AssistantTone.PLAYFUL -> "أهلًا يا $name! أنا ${settings.name}، مساعدك الشخصي. جاهز نرتب اليوم مع بعض؟ اسألني عن وردك أو مهامك أو الصلاة الجاية."
        }
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
        val lower = text.lowercase()
        val learned = extractFact(text, lower)
        // النوايا التنفيذية تُفحص أولًا: «ضيف مهمة…» و«سجّل وردي» مثلًا تحوي
        // كلمات («مهمة»، «ورد») تطابق أيضًا فروع الإجابة الإخبارية أدناه.
        val decision = decideAction(lower, snapshot, today, zone)
        val core = decision?.text ?: answerFor(lower, snapshot, settings, facts, profileText)
        val withLearning = if (learned != null) {
            "$core\n\nحفظت إن «${learned.text}»، وهستخدمها في ردودي الجاية."
        } else {
            core
        }
        return AssistantReply(text = withLearning, learnedFact = learned, action = decision?.action)
    }

    /** نتيجة فحص نية تنفيذية: فعل يُنفَّذ مع نص تأكيده، أو نص توضيح بلا فعل. */
    private data class ActionDecision(val action: AssistantAction?, val text: String)

    /**
     * يطابق نية تنفيذية في الرسالة ويبني فعلها ونص تأكيدها، أو null إن لم تكن
     * الرسالة أمرًا تنفيذيًا. الترتيب مقصود: تسجيل الورد قبل تعليم مهمة
     * كمنجزة («خلصت الورد» ليست مهمة)، وفتح المصحف قبل إجابة حالة الورد.
     */
    private fun decideAction(
        lower: String,
        snapshot: AssistantSnapshot,
        today: LocalDate,
        zone: ZoneId
    ): ActionDecision? {
        addTaskDecision(lower, today, zone)?.let { return it }
        logWirdDecision(lower, snapshot)?.let { return it }
        startFocusDecision(lower, snapshot, today)?.let { return it }
        openQuranDecision(lower, snapshot)?.let { return it }
        openTodayDecision(lower)?.let { return it }
        completeTaskDecision(lower, snapshot)?.let { return it }
        return null
    }

    /** يحذف كلمات الأمر من الرسالة ويبقي ما يصلح عنوانًا أو اسم مهمة. */
    private fun stripCommandWords(
        lower: String,
        words: Set<String>,
        dropNumbers: Boolean = true
    ): String =
        lower.split(Regex("\\s+"))
            .filter { token ->
                token.isNotEmpty() && token !in words &&
                    (!dropNumbers || token.all { ch -> !ch.isDigit() })
            }
            .joinToString(" ").trim()

    /** يطابق نصًا مع المهام المفتوحة: تطابق تام أو احتواء في أي اتجاه. */
    private fun matchOpenTasks(query: String, tasks: List<AssistantTaskRef>): List<AssistantTaskRef> {
        val clean = query.trim()
        if (clean.isEmpty()) return emptyList()
        val exact = tasks.filter { it.title.lowercase() == clean }
        if (exact.isNotEmpty()) return exact
        return tasks.filter { task ->
            val title = task.title.lowercase()
            title.contains(clean) || clean.contains(title)
        }
    }

    /** نص يلخّص مرشحين غامضين ليسأل المستخدم عن المقصود. */
    private fun ambiguousText(query: String, candidates: List<AssistantTaskRef>, user: String): String {
        val listed = candidates.take(3).joinToString("؛ ") { "«${it.title}»" }
        return "لقيت أكتر من مهمة شبه «$query» يا $user: $listed. قولي اسمها بالظبط، أو افتح «مهامي» واختارها."
    }

    private fun addTaskDecision(lower: String, today: LocalDate, zone: ZoneId): ActionDecision? {
        // «سجّل ورد اليوم» تحوي فعل إضافة لكنها تسجيل ورد، لا مهمة.
        if (lower.contains("ورد")) return null
        val addVerbs = setOf("ضيف", "أضف", "اضف", "أضيف", "ضيفي", "سجل", "سجّل", "حط", "اعمل", "أعمل", "أنشئ", "انشئ")
        val mentionsTask = lower.contains("مهمة") || lower.contains("مهمه")
        val startsWithVerb = addVerbs.any { lower.startsWith(it) }
        if (!mentionsTask && !startsWithVerb) return null
        if (!addVerbs.any { lower.contains(it) }) return null
        // الأرقام تبقى: المحلل يحتاجها لاستخراج الساعة والمدة («الساعة 6»).
        val cleaned = stripCommandWords(
            lower,
            addVerbs + setOf("مهمة", "مهمه", "لي", "جديدة", "جديده", "من فضلك"),
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
                recurrence = parsed.recurrence
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
            return ActionDecision(
                action = AssistantAction.StartFocus(taskId = null, taskTitle = null, minutes = minutes),
                text = "بدأت جلسة تركيز عامة لمدة $minutes دقيقة — بالتوفيق!"
            )
        }
        val matches = matchOpenTasks(query, snapshot.openTasks)
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
        profileText: String
    ): String {
        val name = settings.name
        val user = snapshot.userName.ifBlank { "صديقي" }
        return when {
            isGreeting(lower) -> when (settings.tone) {
                AssistantTone.FORMAL -> "أهلًا بك يا $user، يوم موفق بإذن الله. هل أراجع معك مهام اليوم أو وردك؟"
                AssistantTone.CALM -> "أهلًا يا $user. يومك ماشي بهدوء، وأنا معاك خطوة بخطوة."
                AssistantTone.PLAYFUL -> "أهلًا أهلًا يا $user! يوم جديد وفرصة جديدة. تحب نبدأ بالمهام ولا بالورد؟"
            }
            lower.contains("اسمك") || lower.contains("انت مين") || lower.contains("إنت مين") ->
                "أنا $name، مساعدك الشخصي في تطبيق مُنظِّم. بتكلم باللهجة المصرية، وكل اللي أعرفه عنك محفوظ على جهازك بس — مفيش حاجة بتطلع برّه."
            lower.contains("تقدر تعمل") || lower.contains("بتعمل ايه") || lower.contains("بتعمل إيه") ||
                lower.contains("ساعدني ازاي") || lower.contains("ساعدني إزاي") ->
                "أقدر أنفّذ لك حاجات على طول من هنا، على جهازك من غير إنترنت: أضيف مهمة («ضيف مهمة مراجعة فيزياء الساعة 6»)، أسجّل ورد اليوم، أبدأ جلسة تركيز، أفتح المصحف على ورد اليوم، أعرض خطة اليوم، وأعلّم مهمة كمنجزة. وكمان أتابع معاك ورد القرآن ومهام اليوم والصلاة الجاية وسعراتك وعاداتك من بيانات التطبيق نفسه."
            lower.contains("ورد") || lower.contains("قرآن") || lower.contains("قران") ||
                lower.contains("قرأت") || lower.contains("قرات") ->
                if (snapshot.wirdDoneToday) {
                    "ما شاء الله يا $user، ورد النهاردة اتسجّل: ${snapshot.wirdPagesPerDay} صفحات من سورة ${snapshot.wirdSurah}. إجمالي اللي قرأته ${snapshot.wirdTotalPages} صفحة."
                } else {
                    "ورد النهاردة ${snapshot.wirdPagesPerDay} صفحات من سورة ${snapshot.wirdSurah}، بداية من صفحة ${snapshot.wirdNextPage}. لسه ما اتسجّلش — افتح المصحف من «حياتي» واقرأ على مهلك."
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
                lower.contains("قولي عن نفسي") -> aboutMeAnswer(snapshot, facts, profileText, user)
            lower.contains("شكرا") || lower.contains("شكرًا") ->
                "العفو يا $user، ده واجبي. أي وقت تحتاجني، أنا موجود."
            lower.contains("هدف") ->
                "هدفك خطوة مهمة يا $user. قولي هدفك بصيغة واضحة — مثلًا «هدفي أخلص مادة كذا قبل نهاية الشهر» — وهحفظه وأفكرك بيه."
            else -> fallback(settings, user)
        }
    }

    private fun tasksAnswer(snapshot: AssistantSnapshot, user: String): String {
        val next = snapshot.nextTaskTitle?.let { " والمهمة الجاية عندك: «$it»." } ?: ""
        return if (snapshot.openTasksToday == 0 && snapshot.doneTasksToday == 0) {
            "مفيش مهام مستحقة النهاردة يا $user — يوم هادي. لو حابب، ضيف مهمة صغيرة من زر الإضافة السريع."
        } else {
            "النهاردة عندك ${snapshot.openTasksToday} مهام لسه ما خلصتش، وخلّصت ${snapshot.doneTasksToday}.$next ابدأ بأصغر واحدة، وأنا هتابع معاك."
        }
    }

    private fun caloriesAnswer(snapshot: AssistantSnapshot, user: String): String {
        val target = snapshot.dailyCalorieTarget
        return if (target == null) {
            "لسه ما سجّلتش بيانات جسمك في «حياتي»، فمقدرش أحسب هدف سعراتك. سجّل وزنك وطولك ونشاطك هناك، وهقولك هدفك اليومي."
        } else {
            "هدفك اليومي $target سعرة، وسجّلت النهاردة ${snapshot.caloriesToday} سعرة يا $user. باقي لك ${maxOf(target - snapshot.caloriesToday, 0)} سعرة لحد نهاية اليوم."
        }
    }

    private fun aboutMeAnswer(
        snapshot: AssistantSnapshot,
        facts: List<LearnedFact>,
        profileText: String,
        user: String
    ): String {
        if (profileText.isBlank() && facts.isEmpty()) {
            return "لسه ما أعرفش عنك كتير يا $user — من التطبيق نفسه بس أعرف إن وردك ${snapshot.wirdTotalPages} صفحة إجمالي، وعندك ${snapshot.openTasksToday} مهام مفتوحة النهاردة. عرّفني عليك من تبويب «عرّفني عليك»، أو قولي هدفك وروتينك هنا في المحادثة."
        }
        val parts = ArrayList<String>()
        if (profileText.isNotBlank()) parts.add("عندك ملف تعريفي مستورد (${profileText.length} حرف)، وبستخدمه عشان أفهمك.")
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

    private fun fallback(settings: AssistantSettings, user: String): String {
        val options = when (settings.tone) {
            AssistantTone.FORMAL -> listOf(
                "سؤال وجيه يا $user. لحد دلوقتي أقدر أساعدك في متابعة وردك ومهامك وصلاتك وسعراتك وعاداتك — جرّب تسألني عن واحد منهم.",
                "لم أفهم المقصود تمامًا. يمكنك سؤالي عن ورد اليوم أو مهامك أو الصلاة القادمة، وسأجيبك من بيانات التطبيق."
            )
            AssistantTone.CALM -> listOf(
                "مش متأكد إني فهمت قصدك يا $user، بس على مهلك. اسألني عن وردك أو مهامك أو صلاتك، وأنا هرد عليك بهدوء.",
                "خد وقتك يا $user. أنا هنا لو حابب تسأل عن يومك، أو تعرّفني على نفسك خطوة بخطوة."
            )
            AssistantTone.PLAYFUL -> listOf(
                "هممم، دي لسه مش من الحاجات اللي بعرف أرد عليها يا $user! بس اسألني عن وردك أو مهامك أو الصلاة الجاية — دي منطقتي.",
                "فهمت نص الكلام بس يا $user! جرّب تسألني: «وردي النهاردة إيه؟» أو «عندي مهام إيه؟» وأنا أجاوبك على طول."
            )
        }
        return options[Math.floorMod(user.hashCode(), options.size)]
    }

    private fun isGreeting(lower: String): Boolean =
        lower.contains("سلام") || lower.contains("صباح الخير") || lower.contains("مساء الخير") ||
            lower.contains("أهلا") || lower.contains("اهلا") || lower.contains("ازيك") ||
            lower.contains("إزيك") || lower.contains("عامل ايه") || lower.contains("عامل إيه") ||
            lower == "هاي" || lower == "هلا"
}
