package com.focusstudy.poc

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * محرك تعلّم المساعد «منظم»: يتعلّم من سلوك المستخدم على الجهاز فقط.
 *
 * لا شبكة ولا نموذج سحابي ولا رفع بيانات: المدخلات صفوف مقروءة أصلًا من قواعد
 * بيانات التطبيق (مهام، جلسات، ورد، عادات، مزاج، فلوس، تمارين)، والمخرجات
 * أنماط مجمّعة (أفضل ساعات التركيز، مدة الجلسة المفضلة، الفئات التي تتأخر،
 * سلاسل الورد، ارتباط المزاج بالتركيز) يستخدمها المساعد في ردوده واقتراحاته.
 *
 * المحرك نقي تمامًا (بلا Context وبلا قاعدة بيانات) حتى يُختبَر بوحدات اختبار
 * JVM عادية بمدخلات ثابتة. التجميع من المستودعات يتم في [AssistantKnowledge]،
 * والحفظ في جدول `assistant_profile` يتم عبر [AssistantMemoryStore].
 */

/** صف مهمة واحد كما يراه المتعلم: فئة وتواريخ إنشاء وإنجاز واستحقاق وحالة. */
data class TaskLearningRow(
    val category: TaskCategory,
    val createdAt: Long,
    val completedAt: Long?,
    val dueAt: Long?,
    val status: TaskStatus
)

/** جلسة تركيز منتهية كما يراها المتعلم، مع فئة مهمتها إن وُجدت. */
data class SessionLearningRow(
    val startedAt: Long,
    val endsAt: Long,
    val category: TaskCategory?
)

/** سجل ورد يوم واحد كما يراه المتعلم: اليوم ووقت التسجيل الفعلي. */
data class WirdLearningRow(val day: String, val loggedAt: Long)

/** إنجاز عادة في يوم كما يراه المتعلم. */
data class HabitLearningRow(val habitId: Long, val day: String)

/** كل الإشارات السلوكية التي يتعلّم منها المساعد في دورة تعلّم واحدة. */
data class LearningSignals(
    val tasks: List<TaskLearningRow> = emptyList(),
    val sessions: List<SessionLearningRow> = emptyList(),
    val wirdLogs: List<WirdLearningRow> = emptyList(),
    val habitLogs: List<HabitLearningRow> = emptyList(),
    val habitIds: List<Long> = emptyList(),
    val moods: List<MoodLearningRow> = emptyList(),
    val transactions: List<MoneyLearningRow> = emptyList(),
    val exerciseDays: List<String> = emptyList(),
    val zone: ZoneId = ZoneId.systemDefault()
)

/** مزاج يوم واحد كما يراه المتعلم. */
data class MoodLearningRow(val day: String, val mood: Int)

/** معاملة مالية واحدة كما يراها المتعلم. */
data class MoneyLearningRow(
    val kind: MoneyKind,
    val amount: Double,
    val category: String,
    val day: String
)

/** ساعة من اليوم مع عدد مرات حدوث النمط فيها ونسبتها من الإجمالي. */
data class HourPattern(val hour: Int, val count: Int, val share: Double)

/** ما تعلّمه المساعد عن فئة مهام واحدة خلال آخر 60 يومًا. */
data class CategoryLearning(
    val category: TaskCategory,
    val completed: Int,
    val total: Int,
    val completionRate: Double,
    val late: Int,
    val lateRate: Double,
    val avgDelayDays: Double
)

/**
 * الملف الشخصي المتعلَّم: كل الأنماط التي يبني عليها المساعد ردوده واقتراحاته.
 * أي نمط بلا عينات كافية يبقى null أو فارغًا، فيتصرف المساعد كمساعد جديد
 * يتعلم — لا يدّعي معرفة غير موجودة.
 */
data class LearnedProfile(
    val bestFocusHours: List<HourPattern> = emptyList(),
    val bestWirdHour: HourPattern? = null,
    val preferredFocusMinutes: Int? = null,
    val focusMinutesPerDayAvg: Double = 0.0,
    val categoryStats: List<CategoryLearning> = emptyList(),
    val mostDelayedCategory: TaskCategory? = null,
    val wirdStreakDays: Int = 0,
    val longestWirdStreakDays: Int = 0,
    val wirdTotalDays: Int = 0,
    val habitRates: Map<Long, Double> = emptyMap(),
    val moodAvg7d: Double? = null,
    val moodOnFocusDaysAvg: Double? = null,
    val moodOnNoFocusDaysAvg: Double? = null,
    val topExpenseCategory: String? = null,
    val avgDailySpend30d: Double = 0.0,
    val exerciseDays7d: Int = 0,
    val samples: Int = 0,
    val computedAt: Long = 0L
) {
    /** هل لدى المساعد ما يكفي من الأدلة ليقدّم اقتراحات متكيفة؟ */
    val hasEnoughData: Boolean get() = samples >= MIN_SAMPLES_TOTAL

    companion object {
        /** أقل عدد إجمالي من صفوف الأدلة قبل أن يبدأ المساعد في التكيّف. */
        const val MIN_SAMPLES_TOTAL = 5
    }
}

object AssistantLearner {

    /** نافذة التعلّم بالأيام للمهام والجلسات والفلوس. */
    const val WINDOW_DAYS = 60L

    /** أقل عينات لنمط ساعة قبل الوثوق به (3 جلسات في نفس الساعة مثلًا). */
    const val MIN_HOUR_SAMPLES = 3

    /** أقل مهام في فئة قبل حساب نسبة إنجاز/تأخر لها. */
    const val MIN_CATEGORY_TASKS = 3

    /** أقل عينات مزاج قبل مقارنة أيام التركيز بغيرها. */
    const val MIN_MOOD_SAMPLES = 3

    fun learn(signals: LearningSignals, today: LocalDate, nowMillis: Long): LearnedProfile {
        val zone = signals.zone
        val windowStart = today.minusDays(WINDOW_DAYS)
        val windowStartMillis = windowStart.atStartOfDay(zone).toInstant().toEpochMilli()

        val sessionsInWindow = signals.sessions.filter {
            it.startedAt >= windowStartMillis && it.endsAt > it.startedAt
        }
        val tasksInWindow = signals.tasks.filter { it.createdAt >= windowStartMillis }

        val bestFocusHours = focusHours(sessionsInWindow, zone)
        val bestWirdHour = wirdHour(signals.wirdLogs, zone)
        val preferredMinutes = preferredFocusMinutes(sessionsInWindow)
        val focusAvg = focusMinutesPerDayAvg(sessionsInWindow, zone, today)
        val categoryStats = categoryStats(tasksInWindow)
        val mostDelayed = categoryStats
            .filter { it.total >= MIN_CATEGORY_TASKS && it.late > 0 }
            .maxByOrNull { it.lateRate }
            ?.category
        val (streak, longest, totalWirdDays) = wirdStreaks(signals.wirdLogs, today)
        val habitRates = habitRates(signals.habitLogs, signals.habitIds, today)
        val (mood7d, moodFocus, moodNoFocus) = moodAnalysis(signals, today, zone)
        val (topExpense, avgSpend) = moneyAnalysis(signals.transactions, today)
        val exercise7d = signals.exerciseDays
            .toSet()
            .count { day -> runCatching { LocalDate.parse(day) } .getOrNull()?.let { !it.isBefore(today.minusDays(6)) } == true }

        val samples = tasksInWindow.size + sessionsInWindow.size + signals.wirdLogs.size +
            signals.habitLogs.size + signals.moods.size + signals.transactions.size

        return LearnedProfile(
            bestFocusHours = bestFocusHours,
            bestWirdHour = bestWirdHour,
            preferredFocusMinutes = preferredMinutes,
            focusMinutesPerDayAvg = focusAvg,
            categoryStats = categoryStats,
            mostDelayedCategory = mostDelayed,
            wirdStreakDays = streak,
            longestWirdStreakDays = longest,
            wirdTotalDays = totalWirdDays,
            habitRates = habitRates,
            moodAvg7d = mood7d,
            moodOnFocusDaysAvg = moodFocus,
            moodOnNoFocusDaysAvg = moodNoFocus,
            topExpenseCategory = topExpense,
            avgDailySpend30d = avgSpend,
            exerciseDays7d = exercise7d,
            samples = samples,
            computedAt = nowMillis
        )
    }

    /** أفضل ساعات بدء جلسات التركيز: الساعات التي تكررت 3 مرات فأكثر، مرتبة. */
    fun focusHours(sessions: List<SessionLearningRow>, zone: ZoneId): List<HourPattern> {
        if (sessions.isEmpty()) return emptyList()
        val counts = sessions.groupingBy {
            Instant.ofEpochMilli(it.startedAt).atZone(zone).hour
        }.eachCount()
        return counts.entries
            .filter { it.value >= MIN_HOUR_SAMPLES }
            .sortedByDescending { it.value }
            .take(3)
            .map { HourPattern(it.key, it.value, it.value.toDouble() / sessions.size) }
    }

    /** الساعة التي يُسجَّل فيها الورد غالبًا (3 مرات فأكثر في نفس الساعة). */
    fun wirdHour(logs: List<WirdLearningRow>, zone: ZoneId): HourPattern? {
        val withTime = logs.filter { it.loggedAt > 0 }
        if (withTime.isEmpty()) return null
        val counts = withTime.groupingBy {
            Instant.ofEpochMilli(it.loggedAt).atZone(zone).hour
        }.eachCount()
        val best = counts.entries.filter { it.value >= MIN_HOUR_SAMPLES }
            .maxByOrNull { it.value } ?: return null
        return HourPattern(best.key, best.value, best.value.toDouble() / withTime.size)
    }

    /** مدة الجلسة المفضلة: وسيط مدد الجلسات المنتهية (5..180 دقيقة). */
    fun preferredFocusMinutes(sessions: List<SessionLearningRow>): Int? {
        val minutes = sessions
            .map { (it.endsAt - it.startedAt) / 60_000L }
            .filter { it in 5..180 }
            .sorted()
        if (minutes.size < MIN_HOUR_SAMPLES) return null
        return minutes[minutes.size / 2].toInt()
    }

    /** متوسط دقائق التركيز اليومية خلال آخر 7 أيام. */
    fun focusMinutesPerDayAvg(
        sessions: List<SessionLearningRow>,
        zone: ZoneId,
        today: LocalDate
    ): Double {
        val since = today.minusDays(6)
        val minutes = sessions
            .filter {
                val day = Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()
                !day.isBefore(since) && !day.isAfter(today)
            }
            .sumOf { (it.endsAt - it.startedAt) / 60_000.0 }
        return (minutes / 7.0 * 10.0).roundToInt() / 10.0
    }

    /** إحصاءات كل فئة: نسبة الإنجاز ونسبة التأخر ومتوسط أيام التأخر. */
    fun categoryStats(tasks: List<TaskLearningRow>): List<CategoryLearning> {
        return tasks.groupBy { it.category }
            .filter { it.value.size >= MIN_CATEGORY_TASKS }
            .map { (category, rows) ->
                val completed = rows.filter { it.status == TaskStatus.DONE || it.completedAt != null }
                val late = completed.filter {
                    it.dueAt != null && it.completedAt != null && it.completedAt > it.dueAt
                }
                val avgDelay = if (late.isEmpty()) 0.0 else late.sumOf {
                    (it.completedAt!! - it.dueAt!!) / 86_400_000.0
                } / late.size
                CategoryLearning(
                    category = category,
                    completed = completed.size,
                    total = rows.size,
                    completionRate = completed.size.toDouble() / rows.size,
                    late = late.size,
                    lateRate = if (completed.isEmpty()) 0.0 else late.size.toDouble() / completed.size,
                    avgDelayDays = (avgDelay * 10.0).roundToInt() / 10.0
                )
            }
            .sortedByDescending { it.lateRate }
    }

    /**
     * سلاسل الورد: الحالية (أيام متتالية تنتهي اليوم أو أمس — فاليوم قد لا
     * يُسجَّل بعد) والأطول في السجل، وعدد أيام الورد الكلي.
     */
    fun wirdStreaks(logs: List<WirdLearningRow>, today: LocalDate): Triple<Int, Int, Int> {
        val days = logs.mapNotNull { runCatching { LocalDate.parse(it.day) }.getOrNull() }.toSet()
        if (days.isEmpty()) return Triple(0, 0, 0)
        var streak = 0
        var cursor = if (today in days) today else today.minusDays(1)
        while (cursor in days) {
            streak++
            cursor = cursor.minusDays(1)
        }
        var longest = 0
        var current = 0
        var previous: LocalDate? = null
        days.sorted().forEach { day ->
            current = if (previous != null && day == previous!!.plusDays(1)) current + 1 else 1
            if (current > longest) longest = current
            previous = day
        }
        return Triple(streak, longest, days.size)
    }

    /** نسبة إنجاز كل عادة خلال آخر 30 يومًا (0..1). */
    fun habitRates(
        logs: List<HabitLearningRow>,
        habitIds: List<Long>,
        today: LocalDate
    ): Map<Long, Double> {
        val since = today.minusDays(29)
        val doneDays = logs
            .filter { runCatching { LocalDate.parse(it.day) }.getOrNull()?.let { d -> !d.isBefore(since) } == true }
            .groupBy { it.habitId }
            .mapValues { it.value.map { row -> row.day }.toSet().size }
        return habitIds.associateWith { id ->
            (doneDays[id] ?: 0) / 30.0
        }
    }

    /** متوسط المزاج آخر 7 أيام، ومقارنة أيام التركيز بأيام بلا تركيز. */
    fun moodAnalysis(
        signals: LearningSignals,
        today: LocalDate,
        zone: ZoneId
    ): Triple<Double?, Double?, Double?> {
        val since = today.minusDays(6)
        val recent = signals.moods.filter {
            runCatching { LocalDate.parse(it.day) }.getOrNull()?.let { d -> !d.isBefore(since) && !d.isAfter(today) } == true
        }
        if (recent.isEmpty()) return Triple(null, null, null)
        val avg7d = recent.sumOf { it.mood }.toDouble() / recent.size
        val focusDays = signals.sessions
            .map { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
            .toSet()
        val withFocus = recent.filter { LocalDate.parse(it.day) in focusDays }
        val withoutFocus = recent.filter { LocalDate.parse(it.day) !in focusDays }
        val avgFocus = if (withFocus.size >= MIN_MOOD_SAMPLES) {
            withFocus.sumOf { it.mood }.toDouble() / withFocus.size
        } else {
            null
        }
        val avgNoFocus = if (withoutFocus.size >= MIN_MOOD_SAMPLES) {
            withoutFocus.sumOf { it.mood }.toDouble() / withoutFocus.size
        } else {
            null
        }
        return Triple((avg7d * 10.0).roundToInt() / 10.0, avgFocus, avgNoFocus)
    }

    /** أكثر فئة مصروف خلال آخر 30 يومًا، ومتوسط المصروف اليومي. */
    fun moneyAnalysis(
        transactions: List<MoneyLearningRow>,
        today: LocalDate
    ): Pair<String?, Double> {
        val since = today.minusDays(29)
        val expenses = transactions.filter {
            it.kind == MoneyKind.EXPENSE &&
                runCatching { LocalDate.parse(it.day) }.getOrNull()?.let { d -> !d.isBefore(since) && !d.isAfter(today) } == true
        }
        if (expenses.isEmpty()) return Pair(null, 0.0)
        val byCategory = expenses.groupBy { it.category.ifBlank { "أخرى" } }
            .mapValues { it.value.sumOf { row -> row.amount } }
        val top = byCategory.maxByOrNull { it.value }?.key
        val total = expenses.sumOf { it.amount }
        return Pair(top, (total / 30.0 * 100.0).roundToInt() / 100.0)
    }
}
