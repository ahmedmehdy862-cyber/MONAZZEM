package com.focusstudy.poc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * معرفة المساعد: لقطة للقراءة فقط من مستودعات التطبيق الحالية.
 *
 * يقرأ الورد والسعرات والتمارين والمهام والصلاة من [LifeRepository] و[TaskRepository]
 * و[PrayerCalculator] — بلا أي كتابة في قواعد بيانات التطبيق، وكل قراءة ملفوفة
 * في runCatching حتى لا تكسر قراءة فاشلة المحادثة أبدًا.
 *
 * منذ v18 تبني اللقطة أيضًا [AssistantContext]: السياق الواسع عبر كل أقسام
 * التطبيق (مهام متأخرة، تركيز اليوم، سلاسل الورد، عادات، مزاج، فلوس، تمارين،
 * أهداف، شخصية) مع الملف المتعلَّم من [AssistantLearner] — فلا يجيب المساعد
 * بعد اليوم من خانة واحدة. تحديث بنود الملف المحفوظة يُخنق بساعة واحدة.
 */
/** مرجع خفيف لمهمة مفتوحة، يكفي المساعد لمطابقة اسم تذكره في المحادثة. */
data class AssistantTaskRef(val id: Long, val title: String)

data class AssistantSnapshot(
    val userName: String = "",
    val wirdDoneToday: Boolean = false,
    val wirdTotalPages: Int = 0,
    val wirdNextPage: Int = 2,
    val wirdSurah: String = "البقرة",
    val wirdPagesPerDay: Int = 2,
    val openTasksToday: Int = 0,
    val doneTasksToday: Int = 0,
    val nextTaskTitle: String? = null,
    val nextTaskDueText: String? = null,
    /** كل المهام المفتوحة (مرتبة بالاستحقاق) ليطابق المساعد اسم مهمة يذكره المستخدم. */
    val openTasks: List<AssistantTaskRef> = emptyList(),
    val dailyCalorieTarget: Int? = null,
    val caloriesToday: Int = 0,
    val exerciseDaysThisWeek: Int = 0,
    val moodToday: Int? = null,
    val nextPrayerName: String? = null,
    val nextPrayerTime: String? = null,
    val habitsDoneToday: Int = 0,
    val habitsTotal: Int = 0,
    val hasProfile: Boolean = false,
    val factsCount: Int = 0,
    /** السياق الواسع عبر كل الأقسام + الملف المتعلَّم (v18). */
    val context: AssistantContext = AssistantContext()
)

internal object AssistantKnowledge {

    /** يبني اللقطة من المستودعات؛ أي قراءة تفشل تُستبدل بقيمتها الافتراضية. */
    suspend fun load(context: Context, userName: String): AssistantSnapshot {
        val zone = AppTimeZone.zone(context)
        val today = LocalDate.now(zone)
        val todayKey = today.toString()
        val life = LifeRepository.get(context)
        val tasksRepo = TaskRepository.get(context)
        val db = StudyDb.get(context)

        val wirdSettings = runCatching { life.wirdSettings() }.getOrDefault(WirdSettings())
        val wirdDone = runCatching { life.wirdLoggedToday(todayKey) }.getOrDefault(false)
        val wirdTotal = runCatching { life.wirdTotalPages() }.getOrDefault(0)
        val fitness = runCatching { life.fitnessProfile() }.getOrDefault(null)
        val calories = runCatching { life.calorieEntries(todayKey) }.getOrDefault(emptyList())
        val exerciseLogs = runCatching {
            life.exerciseLogsSince(today.minusDays(6).toString())
        }.getOrDefault(emptyList())
        val mood = runCatching { life.moodFor(todayKey) }.getOrDefault(null)
        val habits = runCatching { life.habits() }.getOrDefault(emptyList())
        var habitsDone = 0
        habits.forEach { habit ->
            val days = runCatching {
                life.habitDoneDays(habit.id, today.minusDays(60).toString())
            }.getOrDefault(emptySet())
            if (todayKey in days) habitsDone++
        }

        val allTasks = runCatching { tasksRepo.tasks() }.getOrDefault(emptyList())
        val todaysTasks = allTasks.filter { task ->
            task.dueAt?.let { due ->
                Instant.ofEpochMilli(due).atZone(zone).toLocalDate() == today
            } ?: false
        }
        val openToday = todaysTasks.count { !it.isDone && it.status != TaskStatus.DONE }
        val doneToday = todaysTasks.count { it.isDone || it.status == TaskStatus.DONE }
        val nextTask = allTasks
            .filter { !it.isDone && it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED }
            .sortedBy { it.dueAt ?: Long.MAX_VALUE }
            .firstOrNull()
        val nextDueText = nextTask?.dueAt?.let { due ->
            SimpleDateFormat("h:mm a", Locale("ar")).apply {
                timeZone = TimeZone.getTimeZone(zone.id)
            }.format(Date(due))
        }
        val openTaskRefs = allTasks
            .filter { !it.isDone && it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED }
            .sortedBy { it.dueAt ?: Long.MAX_VALUE }
            .map { AssistantTaskRef(it.id, it.title) }

        val nextPrayer = runCatching { PrayerCalculator.next(context) }.getOrNull()
        val nextPrayerTime = nextPrayer?.let { PrayerCalculator.format(context, it.atMillis) }

        val store = AssistantStore
        val broadContext = runCatching {
            loadBroadContext(context, db, life, allTasks, habits, today, zone)
        }.getOrDefault(AssistantContext())
        return AssistantSnapshot(
            userName = userName,
            wirdDoneToday = wirdDone,
            wirdTotalPages = wirdTotal,
            wirdNextPage = wirdSettings.startPage + wirdTotal,
            wirdSurah = wirdSettings.surah,
            wirdPagesPerDay = wirdSettings.pagesPerDay,
            openTasksToday = openToday,
            doneTasksToday = doneToday,
            nextTaskTitle = nextTask?.title,
            nextTaskDueText = nextDueText,
            openTasks = openTaskRefs,
            dailyCalorieTarget = fitness?.let { dailyTargetKcal(it) },
            caloriesToday = calories.sumOf { it.calories },
            exerciseDaysThisWeek = exerciseLogs.map { it.day }.toSet().size,
            moodToday = mood,
            nextPrayerName = nextPrayer?.name,
            nextPrayerTime = nextPrayerTime,
            habitsDoneToday = habitsDone,
            habitsTotal = habits.size,
            hasProfile = store.loadProfile(context).isNotBlank(),
            factsCount = store.loadFacts(context).size,
            context = broadContext
        )
    }

    /**
     * يبني السياق الواسع والملف المتعلَّم من الإشارات السلوكية (آخر 60 يومًا
     * للمهام والجلسات، وسنة للورد لحساب السلاسل). كل قراءة ملفوفة في
     * runCatching داخل [load]، وهنا تُجمع الإشارات ثم يُحسب الملف ثم تُحفظ
     * بنوده (مخنوقة بساعة) وتُقرأ الذكريات.
     */
    private suspend fun loadBroadContext(
        context: Context,
        db: StudyDb,
        life: LifeRepository,
        allTasks: List<StudyTask>,
        habits: List<Habit>,
        today: LocalDate,
        zone: java.time.ZoneId
    ): AssistantContext = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val todayKey = today.toString()
        val windowStart = today.minusDays(AssistantLearner.WINDOW_DAYS)
        val windowStartMillis = windowStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayStartMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val profile = runCatching { FocusPreferences.load(context).profile }.getOrDefault(UserProfile.GENERAL)

        val overdue = allTasks
            .filter { !it.isDone && it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED }
            .filter { it.dueAt != null && it.dueAt < now }
            .sortedBy { it.dueAt }
            .map { AssistantTaskRef(it.id, it.title) }

        val sessions60d = runCatching {
            db.finishedSessionsBetween(windowStartMillis, now)
        }.getOrDefault(emptyList())
        val taskCategoryById = allTasks.associate { it.id to it.category }
        val sessionsToday = sessions60d.filter { it.startedAt in dayStartMillis until dayEndMillis }
        val focusMinutesToday = sessionsToday.sumOf { (it.endsAt - it.startedAt) / 60_000L }.toInt()

        val wirdLogs = runCatching {
            db.wirdLogsSince(today.minusDays(365).toString())
        }.getOrDefault(emptyList())
        val (streak, longest, _) = AssistantLearner.wirdStreaks(
            wirdLogs.map { WirdLearningRow(it.day, it.loggedAt) }, today
        )

        val habitLogs = ArrayList<HabitLearningRow>()
        val habitRates = HashMap<Long, Double>()
        val habitNames = habits.associate { it.id to it.name }
        habits.forEach { habit ->
            val days = runCatching {
                db.habitDoneDays(habit.id, today.minusDays(29).toString())
            }.getOrDefault(emptySet())
            days.forEach { habitLogs += HabitLearningRow(habit.id, it) }
            habitRates[habit.id] = days.size / 30.0
        }
        val habitsDoneToday = habits.count { habit ->
            runCatching { db.habitDoneDays(habit.id, todayKey) }.getOrDefault(emptySet()).contains(todayKey)
        }

        val moods = runCatching {
            life.moodLogsSince(today.minusDays(29).toString())
        }.getOrDefault(emptyList())
        val moodToday = moods.firstOrNull { it.day == todayKey }?.mood
            ?: runCatching { life.moodFor(todayKey) }.getOrNull()
        val moodAvg7d = moods.filter {
            runCatching { LocalDate.parse(it.day) }.getOrNull()?.let { d -> !d.isBefore(today.minusDays(6)) } == true
        }.let { recent -> if (recent.isEmpty()) null else recent.sumOf { it.mood }.toDouble() / recent.size }

        val monthPrefix = todayKey.substring(0, 7)
        val prevMonthPrefix = today.minusMonths(1).toString().substring(0, 7)
        val transactions = runCatching {
            life.transactionsForMonth(monthPrefix) + life.transactionsForMonth(prevMonthPrefix)
        }.getOrDefault(emptyList())
        val spentToday = transactions
            .filter { it.kind == MoneyKind.EXPENSE && it.day == todayKey }
            .sumOf { it.amount }
        val spentMonth = transactions
            .filter { it.kind == MoneyKind.EXPENSE && it.day.startsWith(monthPrefix) }
            .sumOf { it.amount }

        val goals = runCatching { db.goals() }.getOrDefault(emptyList())
        val goalsActive = goals.count { it.status == "active" }
        val goalsDone = goals.count { it.status == "done" }
        val nextGoal = goals.firstOrNull { it.status == "active" }?.title

        val signals = LearningSignals(
            tasks = allTasks.map {
                TaskLearningRow(it.category, it.createdAt, it.completedAt, it.dueAt, it.status)
            },
            sessions = sessions60d.map {
                SessionLearningRow(it.startedAt, it.endsAt, taskCategoryById[it.taskId])
            },
            wirdLogs = wirdLogs.map { WirdLearningRow(it.day, it.loggedAt) },
            habitLogs = habitLogs,
            habitIds = habits.map { it.id },
            moods = moods.map { MoodLearningRow(it.day, it.mood) },
            transactions = transactions.map {
                MoneyLearningRow(it.kind, it.amount, it.category, it.day)
            },
            exerciseDays = runCatching {
                life.exerciseLogsSince(today.minusDays(6).toString())
            }.getOrDefault(emptyList()).map { it.day },
            zone = zone
        )
        val learned = AssistantLearner.learn(signals, today, now)
        runCatching { AssistantMemoryStore.refreshProfileEntries(context, learned, now) }

        AssistantContext(
            profile = profile,
            overdueTasks = overdue,
            overdueCount = overdue.size,
            mostDelayedCategory = learned.mostDelayedCategory,
            delayedCategoryLateRate = learned.categoryStats
                .firstOrNull { it.category == learned.mostDelayedCategory }?.lateRate ?: 0.0,
            focusMinutesToday = focusMinutesToday,
            focusSessionsToday = sessionsToday.size,
            focusMinutes7dAvg = learned.focusMinutesPerDayAvg,
            wirdStreakDays = streak,
            longestWirdStreakDays = longest,
            habitRates = habitRates,
            habitNames = habitNames,
            habitsDoneTodayCount = habitsDoneToday,
            moodToday = moodToday,
            moodAvg7d = moodAvg7d?.let { (it * 10).toInt() / 10.0 },
            spentToday = spentToday,
            spentMonth = spentMonth,
            topExpenseCategory = learned.topExpenseCategory,
            exerciseDays7d = learned.exerciseDays7d,
            goalsActive = goalsActive,
            goalsDone = goalsDone,
            nextGoalTitle = nextGoal,
            learned = learned,
            memories = runCatching { AssistantMemoryStore.loadMemories(context) }.getOrDefault(emptyList()),
            profileEntries = runCatching { AssistantMemoryStore.loadProfileEntries(context) }.getOrDefault(emptyList()),
            suggestionAcceptance = runCatching { AssistantMemoryStore.suggestionAcceptance(context) }.getOrDefault(emptyMap())
        )
    }
}
