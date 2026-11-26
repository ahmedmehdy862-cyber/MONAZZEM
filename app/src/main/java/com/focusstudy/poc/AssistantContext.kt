package com.focusstudy.poc

/**
 * السياق الواسع للمستخدم كما يراه المساعد: كل أقسام التطبيق في لقطة واحدة.
 *
 * هذا هو جوهر إصلاح شكوى «فهمه مقتصر على جزء محدد»: المساعد لا يجيب بعد اليوم
 * من خانة واحدة (مهام أو ورد فقط)، بل يرى المهام والخطة والورد والتركيز
 * والعادات والصحة والفلوس والمزاج والأهداف والصلاة وشخصية المستخدم
 * (طالب/موظف/خريج/متقاعد/عام) معًا، ويربط بينها في ردوده واقتراحاته.
 *
 * تُبنى اللقطة في [AssistantKnowledge.load] من المستودعات القائمة، وكل قراءة
 * ملفوفة في runCatching فلا تكسر قراءة فاشلة المحادثة أبدًا. كل البيانات
 * محلية على الجهاز فقط.
 */
data class AssistantContext(
    /** شخصية المستخدم كما اختارها في الإعدادات. */
    val profile: UserProfile = UserProfile.GENERAL,
    /** مهام متأخرة عن موعدها (مفتوحة وفات موعدها)، مرتبة بالأقدم. */
    val overdueTasks: List<AssistantTaskRef> = emptyList(),
    val overdueCount: Int = 0,
    /** الفئة التي تتأخر مهامها عادةً حسب التعلّم، ونسبة تأخرها. */
    val mostDelayedCategory: TaskCategory? = null,
    val delayedCategoryLateRate: Double = 0.0,
    /** دقائق وجلسات التركيز اليوم، ومتوسط آخر 7 أيام. */
    val focusMinutesToday: Int = 0,
    val focusSessionsToday: Int = 0,
    val focusMinutes7dAvg: Double = 0.0,
    /** سلسلة الورد الحالية والأطول. */
    val wirdStreakDays: Int = 0,
    val longestWirdStreakDays: Int = 0,
    /** نسبة إنجاز كل عادة آخر 30 يومًا، وأسماؤها. */
    val habitRates: Map<Long, Double> = emptyMap(),
    val habitNames: Map<Long, String> = emptyMap(),
    val habitsDoneTodayCount: Int = 0,
    /** مزاج اليوم ومتوسط آخر 7 أيام. */
    val moodToday: Int? = null,
    val moodAvg7d: Double? = null,
    /** مصروف اليوم والشهر، وأكثر فئة مصروف آخر 30 يومًا. */
    val spentToday: Double = 0.0,
    val spentMonth: Double = 0.0,
    val topExpenseCategory: String? = null,
    /** أيام التمرين في آخر 7 أيام. */
    val exerciseDays7d: Int = 0,
    /** الأهداف النشطة والمنجزة، وعنوان أقرب هدف نشط. */
    val goalsActive: Int = 0,
    val goalsDone: Int = 0,
    val nextGoalTitle: String? = null,
    /** الملف المتعلَّم (أنماط السلوك) — الأساس الذي تبني عليه الاقتراحات. */
    val learned: LearnedProfile = LearnedProfile(),
    /** ذكريات المحادثة المحفوظة في قاعدة البيانات (بثقة وعدد استخدام). */
    val memories: List<AssistantMemoryRow> = emptyList(),
    /** بنود الملف المتعلَّم المحفوظة (للعرض في تبويب «عرّفني عليك»). */
    val profileEntries: List<AssistantProfileEntry> = emptyList(),
    /** نسبة قبول كل نوع اقتراح (مقبول/معروض) — لترتيب الاقتراحات بما ينفع فعلًا. */
    val suggestionAcceptance: Map<String, Double> = emptyMap()
)

/** صف ذاكرة محادثة محفوظ: نص بثقة وعدد استخدام وتواريخ. */
data class AssistantMemoryRow(
    val id: Long,
    val text: String,
    val category: String,
    val source: String,
    val confidence: Double,
    val useCount: Int,
    val createdAt: Long,
    val lastUsedAt: Long
)

/** بند واحد من الملف المتعلَّم المحفوظ: مفتاح وقيمة (نصية أو رقمية) وثقة وعينات. */
data class AssistantProfileEntry(
    val key: String,
    val textValue: String,
    val realValue: Double?,
    val confidence: Double,
    val samples: Int,
    val updatedAt: Long
)

/** صف نتيجة تفاعل مع اقتراح: هل قَبِله المستخدم؟ */
data class AssistantInteractionRow(
    val id: Long,
    val kind: String,
    val detail: String,
    val accepted: Boolean,
    val createdAt: Long
)
