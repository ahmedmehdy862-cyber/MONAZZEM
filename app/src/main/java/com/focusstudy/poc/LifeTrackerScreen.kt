package com.focusstudy.poc

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** مفاتيح أقسام «حياتي» الثمانية، بترتيب ظهورها في القائمة. */
private enum class LifeSectionKey {
    HABITS, WIRD, TASKS, HEALTH, MONEY, MOOD, QURAN, ADHKAR
}

/**
 * القسم المفتوح في «حياتي» محفوظ في SharedPreferences فيبقى بعد الخروج من
 * الشاشة؛ قيمة null تعني كل الأقسام مطوية. الافتراضي: العادات مفتوحة.
 */
private object LifeSectionPrefs {
    private const val FILE = "life_tracker_ui"
    private const val KEY_EXPANDED = "expanded_section"

    fun loadExpanded(context: Context): LifeSectionKey? {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_EXPANDED, LifeSectionKey.HABITS.name) ?: return LifeSectionKey.HABITS
        if (raw.isBlank()) return null
        return runCatching { LifeSectionKey.valueOf(raw) }.getOrDefault(LifeSectionKey.HABITS)
    }

    fun saveExpanded(context: Context, key: LifeSectionKey?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_EXPANDED, key?.name ?: "").apply()
    }
}

/** كل ما تعرضه شاشة «حياتي» في تحميل واحد، فيُعاد تحميلها كاملة بعد كل تسجيل. */
private data class LifeUiState(
    val habits: List<Habit> = emptyList(),
    val habitDays: Map<Long, Set<String>> = emptyMap(),
    val wirdSettings: WirdSettings = WirdSettings(),
    val wirdDoneToday: Boolean = false,
    val wirdTotalPages: Int = 0,
    val healthToday: HealthLog? = null,
    val fitnessProfile: FitnessProfile? = null,
    val calorieEntries: List<CalorieEntry> = emptyList(),
    val exerciseDoneToday: Set<String> = emptySet(),
    val transactions: List<MoneyTransaction> = emptyList(),
    val moodToday: Int? = null,
    val moods30: List<MoodLog> = emptyList(),
    val tasksDueToday: List<StudyTask> = emptyList(),
    val overdueTasks: List<StudyTask> = emptyList()
)

/**
 * تتبع الحياة «حياتي»: عادات، ورد قرآني، مهام اليوم، صحة، مال، مزاج.
 *
 * قسم المهام هنا عرض فقط لنظام المهام القائم: يقرأ مهام اليوم والمتأخر منها من
 * [TaskRepository] ويبدّل حالتها، ولا ينشئ أي نظام مهام موازٍ.
 */
@Composable
internal fun LifeTrackerScreen(onBack: () -> Unit, onOpenTasks: () -> Unit) {
    val context = LocalContext.current
    val life = remember { LifeRepository.get(context) }
    val tasksRepo = remember { TaskRepository.get(context) }
    val scope = rememberCoroutineScope()
    val today = rememberCurrentDate()
    val todayKey = today.toString()
    val monthPrefix = today.format(DateTimeFormatter.ofPattern("yyyy-MM"))
    val listState = rememberLazyListState()

    var version by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var state by remember { mutableStateOf(LifeUiState()) }
    var quranPage by rememberSaveable { mutableStateOf<Int?>(null) }
    var adhkarMorning by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var quranIndex by remember { mutableStateOf<QuranIndex?>(null) }
    var quranLoadFailed by remember { mutableStateOf(false) }
    var quranRetry by remember { mutableStateOf(0) }
    var adhkarIndex by remember { mutableStateOf<AdhkarIndex?>(null) }
    var adhkarLoadFailed by remember { mutableStateOf(false) }
    var adhkarRetry by remember { mutableStateOf(0) }
    fun refresh() { version++ }

    LaunchedEffect(quranPage, quranRetry) {
        if (quranPage != null && quranIndex == null && !quranLoadFailed) {
            quranIndex = withContext(Dispatchers.IO) { QuranData.loadIndex(context) }
            if (quranIndex == null) quranLoadFailed = true
        }
    }
    LaunchedEffect(adhkarMorning, adhkarRetry) {
        if (adhkarMorning != null && adhkarIndex == null && !adhkarLoadFailed) {
            adhkarIndex = withContext(Dispatchers.IO) { QuranData.loadAdhkar(context) }
            if (adhkarIndex == null) adhkarLoadFailed = true
        }
    }

    LaunchedEffect(version) {
        loading = true
        val habits = runCatching { life.habits() }.getOrDefault(emptyList())
        val since60 = today.minusDays(60).toString()
        val days = habits.associate { habit ->
            habit.id to runCatching { life.habitDoneDays(habit.id, since60) }.getOrDefault(emptySet())
        }
        val settings = runCatching { life.wirdSettings() }.getOrDefault(WirdSettings())
        val doneToday = runCatching { life.wirdLoggedToday(todayKey) }.getOrDefault(false)
        val total = runCatching { life.wirdTotalPages() }.getOrDefault(0)
        val health = runCatching { life.healthLog(todayKey) }.getOrDefault(null)
        val profile = runCatching { life.fitnessProfile() }.getOrDefault(null)
        val calories = runCatching { life.calorieEntries(todayKey) }.getOrDefault(emptyList())
        val exerciseLogs = runCatching {
            life.exerciseLogsSince(today.minusDays(6).toString())
        }.getOrDefault(emptyList())
        val txs = runCatching { life.transactionsForMonth(monthPrefix) }.getOrDefault(emptyList())
        val mood = runCatching { life.moodFor(todayKey) }.getOrDefault(null)
        val moods = runCatching { life.moodLogsSince(today.minusDays(29).toString()) }.getOrDefault(emptyList())
        val allTasks = runCatching { tasksRepo.tasks() }.getOrDefault(emptyList())
        val zone = ZoneId.systemDefault()
        val dueToday = allTasks.filter { task ->
            task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } == today
        }
        val overdue = DayPlan.build(allTasks, today)
            .firstOrNull { it.bucket == DayBucket.OVERDUE }?.tasks.orEmpty()
        state = LifeUiState(
            habits = habits,
            habitDays = days,
            wirdSettings = settings,
            wirdDoneToday = doneToday,
            wirdTotalPages = total,
            healthToday = health,
            fitnessProfile = profile,
            calorieEntries = calories,
            exerciseDoneToday = exerciseLogs.filter { it.day == todayKey }.map { it.exerciseId }.toSet(),
            transactions = txs,
            moodToday = mood,
            moods30 = moods,
            tasksDueToday = dueToday,
            overdueTasks = overdue
        )
        loading = false
    }

    // نطاق ورد اليوم مقصوص عند 604 مع كشف الختم الكامل (انظر [wirdRange]).
    val wird = wirdRange(state.wirdSettings, state.wirdTotalPages)
    val wirdFirstPage = wird.firstPage
    val wirdLastPage = wird.lastPage
    val logWirdToday: () -> Unit = {
        scope.launch {
            runCatching {
                life.logWird(
                    todayKey,
                    state.wirdSettings.pagesPerDay,
                    state.wirdSettings.surah
                )
            }
            refresh()
        }
    }
    // إعادة البدء بعد الختم الكامل: تصفير العدّاد وبداية جديدة من صفحة 2
    // (بداية سورة البقرة)، مع بقاء عدد صفحات اليوم كما اختاره المستخدم.
    val restartWird: () -> Unit = {
        scope.launch {
            runCatching {
                life.resetWird()
                life.saveWirdSettings(state.wirdSettings.copy(surah = "البقرة", startPage = 2))
            }
            refresh()
        }
    }

    if (quranPage != null) {
        val index = quranIndex
        if (index != null) {
            QuranReaderScreen(
                index = index,
                initialPage = quranPage!!,
                wirdFirstPage = wirdFirstPage,
                wirdLastPage = wirdLastPage,
                wirdDoneToday = state.wirdDoneToday,
                wirdCompleted = wird.completed,
                onLogWirdToday = logWirdToday,
                onRestartWird = restartWird,
                onBack = { quranPage = null }
            )
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
                TextButton(onClick = { quranPage = null }) { Text("رجوع") }
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (quranLoadFailed) "تعذّر تحميل المصحف." else "جارٍ تحميل المصحف…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FocusColors.TextSecondary
                    )
                    if (quranLoadFailed) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { quranLoadFailed = false; quranRetry++ }) { Text("إعادة المحاولة") }
                    }
                }
            }
        }
        return
    }
    if (adhkarMorning != null) {
        val adhkar = adhkarIndex
        if (adhkar != null) {
            AdhkarScreen(
                index = adhkar,
                initialMorning = adhkarMorning!!,
                onBack = { adhkarMorning = null }
            )
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
                TextButton(onClick = { adhkarMorning = null }) { Text("رجوع") }
                Column(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (adhkarLoadFailed) "تعذّر تحميل الأذكار." else "جارٍ تحميل الأذكار…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FocusColors.TextSecondary
                    )
                    if (adhkarLoadFailed) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { adhkarLoadFailed = false; adhkarRetry++ }) { Text("إعادة المحاولة") }
                    }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("حياتي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    ArabicDate.format(today),
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (loading) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("جارٍ تحميل حياتك…", style = MaterialTheme.typography.bodyMedium, color = FocusColors.TextSecondary)
            }
            return@Column
        }

        // التخفيف (المرحلة 5): الأقسام الثمانية مجموعات قابلة للطي، واحدة مفتوحة
        // في المرة، وكل مجموعة مطوية تعرض سطر ملخص واحدًا. لا يُحذف أي قسم ولا
        // أي وظيفة — التخفيف تنظيم عرض فقط، والمحتوى كاملًا داخل كل مجموعة.
        var expandedSection by remember { mutableStateOf(LifeSectionPrefs.loadExpanded(context)) }
        fun toggleSection(key: LifeSectionKey) {
            expandedSection = if (expandedSection == key) null else key
            LifeSectionPrefs.saveExpanded(context, expandedSection)
        }

        val habitsDoneToday = state.habits.count { state.habitDays[it.id]?.contains(todayKey) == true }
        val tasksDoneToday = state.tasksDueToday.count { it.status == TaskStatus.DONE || it.isDone }
        val healthRecorded = listOf(
            state.healthToday?.weight != null,
            state.healthToday?.sleepHours != null,
            (state.healthToday?.waterCups ?: 0) > 0,
            (state.healthToday?.exerciseMinutes ?: 0) > 0
        ).count { it }
        val incomeMonth = state.transactions.filter { it.kind == MoneyKind.INCOME }.sumOf { it.amount }
        val expenseMonth = state.transactions.filter { it.kind == MoneyKind.EXPENSE }.sumOf { it.amount }
        val moodToday = state.moodToday
        val sectionSummaries = mapOf(
            LifeSectionKey.HABITS to if (state.habits.isEmpty()) "لا عادات بعد — أضف أول عادة"
            else "أنجزت $habitsDoneToday من ${state.habits.size} اليوم",
            LifeSectionKey.WIRD to if (wird.completed) "ختمت المصحف كاملًا 🎉"
            else "ورد اليوم: صفحات $wirdFirstPage–$wirdLastPage · ${if (state.wirdDoneToday) "تم تسجيله ✓" else "لم يُسجَّل بعد"}",
            LifeSectionKey.TASKS to if (state.tasksDueToday.isEmpty()) "لا مهام مجدولة لليوم"
            else "أنجزت $tasksDoneToday من ${state.tasksDueToday.size} اليوم" +
                if (state.overdueTasks.isNotEmpty()) " · متأخرة ${state.overdueTasks.size}" else "",
            LifeSectionKey.HEALTH to if (state.healthToday == null) "لم تسجّل صحة اليوم بعد"
            else "سجّلت $healthRecorded من 4 عناصر اليوم",
            LifeSectionKey.MONEY to if (state.transactions.isEmpty()) "لا معاملات هذا الشهر"
            else "دخل ${formatAmount(incomeMonth)} · مصروف ${formatAmount(expenseMonth)}",
            LifeSectionKey.MOOD to if (moodToday == null) "لم تسجّل مزاج اليوم بعد"
            else "مزاجك اليوم: ${MOOD_LABELS[moodToday - 1]}",
            LifeSectionKey.QURAN to if (wird.completed) "ختمت المصحف كاملًا 🎉"
            else "افتح المصحف على صفحة اليوم ($wirdFirstPage)",
            LifeSectionKey.ADHKAR to "أذكار الصباح والمساء · مسار مباشر في القايمة الجانبية"
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FocusSpacing.section)
        ) {
            item { DailyProgressCard(state = state, todayKey = todayKey) }
            item {
                SectionChips(
                    sections = listOf(
                        "العادات" to 2,
                        "الورد" to 3,
                        "المهام" to 4,
                        "الصحة" to 5,
                        "المال" to 6,
                        "المزاج" to 7,
                        "المصحف" to 8,
                        "الأذكار" to 9
                    ),
                    onSelect = { index ->
                        // الشريحة تفتح قسمها أيضًا، لا تمرّر إليه فقط.
                        LifeSectionKey.entries.getOrNull(index - 2)?.let { key ->
                            expandedSection = key
                            LifeSectionPrefs.saveExpanded(context, key)
                        }
                        scope.launch { listState.animateScrollToItem(index) }
                    }
                )
            }
            item {
                HabitsSection(
                    state = state,
                    today = today,
                    expanded = expandedSection == LifeSectionKey.HABITS,
                    onToggle = { toggleSection(LifeSectionKey.HABITS) },
                    summary = sectionSummaries.getValue(LifeSectionKey.HABITS),
                    onToggleHabit = { habit, done ->
                        scope.launch {
                            runCatching { life.setHabitDone(habit.id, todayKey, done) }
                            refresh()
                        }
                    },
                    onAdd = { name ->
                        scope.launch {
                            runCatching { life.addHabit(name) }
                            refresh()
                        }
                    },
                    onDelete = { habit ->
                        scope.launch {
                            runCatching { life.deleteHabit(habit.id) }
                            refresh()
                        }
                    }
                )
            }
            item {
                WirdSection(
                    state = state,
                    expanded = expandedSection == LifeSectionKey.WIRD,
                    onToggle = { toggleSection(LifeSectionKey.WIRD) },
                    summary = sectionSummaries.getValue(LifeSectionKey.WIRD),
                    onSaveSettings = { settings ->
                        scope.launch {
                            runCatching { life.saveWirdSettings(settings) }
                            refresh()
                        }
                    },
                    onLogToday = logWirdToday,
                    onRestartWird = restartWird
                )
            }
            item {
                TasksSection(
                    state = state,
                    expanded = expandedSection == LifeSectionKey.TASKS,
                    onToggle = { toggleSection(LifeSectionKey.TASKS) },
                    summary = sectionSummaries.getValue(LifeSectionKey.TASKS),
                    onToggleTask = { task, done ->
                        scope.launch {
                            runCatching {
                                tasksRepo.setStatus(
                                    task.id,
                                    if (done) TaskStatus.DONE else TaskStatus.TODO
                                )
                            }
                            refresh()
                        }
                    },
                    onOpenTasks = onOpenTasks
                )
            }
            item {
                HealthSection(
                    state = state,
                    todayKey = todayKey,
                    expanded = expandedSection == LifeSectionKey.HEALTH,
                    onToggle = { toggleSection(LifeSectionKey.HEALTH) },
                    summary = sectionSummaries.getValue(LifeSectionKey.HEALTH),
                    onSave = { log ->
                        scope.launch {
                            runCatching { life.saveHealthLog(log) }
                            refresh()
                        }
                    },
                    onSaveProfile = { profile ->
                        scope.launch {
                            runCatching { life.saveFitnessProfile(profile) }
                            refresh()
                        }
                    },
                    onAddCalorie = { name, calories ->
                        scope.launch {
                            runCatching { life.addCalorieEntry(name, calories, todayKey) }
                            refresh()
                        }
                    },
                    onDeleteCalorie = { entry ->
                        scope.launch {
                            runCatching { life.deleteCalorieEntry(entry.id) }
                            refresh()
                        }
                    },
                    onToggleExercise = { exerciseId, done ->
                        scope.launch {
                            runCatching { life.setExerciseDone(exerciseId, todayKey, done) }
                            refresh()
                        }
                    }
                )
            }
            item {
                MoneySection(
                    state = state,
                    expanded = expandedSection == LifeSectionKey.MONEY,
                    onToggle = { toggleSection(LifeSectionKey.MONEY) },
                    summary = sectionSummaries.getValue(LifeSectionKey.MONEY),
                    onAdd = { kind, amount, category, note ->
                        scope.launch {
                            runCatching { life.addTransaction(kind, amount, category, note, todayKey) }
                            refresh()
                        }
                    },
                    onDelete = { transaction ->
                        scope.launch {
                            runCatching { life.deleteTransaction(transaction.id) }
                            refresh()
                        }
                    }
                )
            }
            item {
                MoodSection(
                    state = state,
                    today = today,
                    expanded = expandedSection == LifeSectionKey.MOOD,
                    onToggle = { toggleSection(LifeSectionKey.MOOD) },
                    summary = sectionSummaries.getValue(LifeSectionKey.MOOD),
                    onSelect = { mood ->
                        scope.launch {
                            runCatching { life.logMood(todayKey, mood) }
                            refresh()
                        }
                    }
                )
            }
            item {
                QuranSection(
                    firstPage = wirdFirstPage,
                    lastPage = wirdLastPage,
                    completed = wird.completed,
                    expanded = expandedSection == LifeSectionKey.QURAN,
                    onToggle = { toggleSection(LifeSectionKey.QURAN) },
                    summary = sectionSummaries.getValue(LifeSectionKey.QURAN),
                    onOpenReader = { quranPage = wirdFirstPage },
                    onRestartWird = restartWird
                )
            }
            item {
                AdhkarSection(
                    expanded = expandedSection == LifeSectionKey.ADHKAR,
                    onToggle = { toggleSection(LifeSectionKey.ADHKAR) },
                    summary = sectionSummaries.getValue(LifeSectionKey.ADHKAR),
                    onOpen = { morning -> adhkarMorning = morning }
                )
            }
        }
    }
}

/**
 * حلقة التقدم اليومية (المرحلة 5): متوسط نسب الإنجاز الفعلية للمجالات، لا
 * متوسط الإنجاز الكامل — فتسوية المجالات بالتساوي كانت مضللة (تسجيل وزن
 * واحد = إنجاز صحة كامل). كل مجال نسبة 0..1:
 *   العادات = المنجزة / الكل (يُستبعد المجال إن لم توجد عادات)
 *   الورد   = صفحات اليوم المنجزة / المطلوبة (1 إن سُجّل، 0 إن لم يُسجَّل)
 *   الصحة  = متوسط العناصر المسجلة اليوم من 4 (وزن / نوم / ماء / تمرين)
 *   المهام  = المنجزة / الكل المستحقة اليوم (يُستبعد المجال إن لم توجد مهام)
 *   المزاج  = 1 إن سُجّل، 0 إن لم يُسجَّل
 * الإنجاز = مجموع النسب / عدد المجالات الداخلة، بأوزان متساوية على النسب.
 */
@Composable
private fun DailyProgressCard(state: LifeUiState, todayKey: String) {
    val ratios = mutableListOf<Pair<String, Float>>()
    var habitsDone = 0
    if (state.habits.isNotEmpty()) {
        habitsDone = state.habits.count { state.habitDays[it.id]?.contains(todayKey) == true }
        ratios += "العادات $habitsDone/${state.habits.size}" to
            habitsDone.toFloat() / state.habits.size
    }
    val wirdRatio = if (state.wirdDoneToday) 1f else 0f
    ratios += (if (state.wirdDoneToday) "الورد ✓" else "الورد ✗") to wirdRatio
    val tasksDone = state.tasksDueToday.count { it.status == TaskStatus.DONE || it.isDone }
    if (state.tasksDueToday.isNotEmpty()) {
        ratios += "المهام $tasksDone/${state.tasksDueToday.size}" to
            tasksDone.toFloat() / state.tasksDueToday.size
    }
    val healthItems = listOf(
        state.healthToday?.weight != null,
        state.healthToday?.sleepHours != null,
        (state.healthToday?.waterCups ?: 0) > 0,
        (state.healthToday?.exerciseMinutes ?: 0) > 0
    )
    val healthRecorded = healthItems.count { it }
    val healthRatio = healthRecorded / 4f
    ratios += "الصحة $healthRecorded/4" to healthRatio
    val moodRatio = if (state.moodToday != null) 1f else 0f
    ratios += (if (state.moodToday != null) "المزاج ✓" else "المزاج ✗") to moodRatio
    val progress = (ratios.sumOf { it.second.toDouble() } / ratios.size).toFloat().coerceIn(0f, 1f)
    val percent = (progress * 100).toInt()
    val parts = ratios.map { (label, ratio) ->
        "$label ${(ratio * 100).toInt()}٪"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(FocusSpacing.card),
            verticalArrangement = Arrangement.spacedBy(FocusSpacing.item)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "إنجاز اليوم",
                    style = MaterialTheme.typography.titleSmall,
                    color = FocusColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$percent٪",
                    style = MaterialTheme.typography.titleLarge,
                    color = FocusColors.onTint(FocusColors.Teal),
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                Modifier.fillMaxWidth()
                    .height(FocusSpacing.progressHeight)
                    .clip(RoundedCornerShape(5.dp))
                    .background(FocusColors.SurfaceHigh)
            ) {
                Box(
                    Modifier.fillMaxWidth(progress)
                        .height(FocusSpacing.progressHeight)
                        .clip(RoundedCornerShape(5.dp))
                        .background(FocusColors.Teal)
                )
            }
            Text(
                parts.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
        }
    }
}

@Composable
private fun SectionChips(sections: List<Pair<String, Int>>, onSelect: (Int) -> Unit) {
    val scrollState = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(FocusSpacing.chip)
    ) {
        sections.forEach { (label, index) ->
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = FocusColors.onTint(FocusColors.Teal),
                modifier = Modifier
                    .clip(RoundedCornerShape(FocusSpacing.radiusChip))
                    .background(FocusColors.SurfaceHigh)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * بطاقة قسم موحدة قابلة للطي: العنوان وسطر ملخص واحد وهي مطوية، والمحتوى
 * الكامل عند فتحها. النقر على رأس البطاقة يطويها أو يفتحها.
 */
@Composable
private fun LifeSectionCard(
    title: String,
    mark: String,
    subtitle: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(FocusSpacing.card),
            verticalArrangement = Arrangement.spacedBy(FocusSpacing.item)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
            ) {
                Text(
                    mark,
                    style = MaterialTheme.typography.titleMedium,
                    color = FocusColors.onTint(FocusColors.Teal)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        color = FocusColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (expanded) subtitle else summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary,
                        maxLines = if (expanded) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "طي القسم" else "فتح القسم",
                    tint = FocusColors.TextSecondary
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FocusSpacing.item)
                ) { content() }
            }
        }
    }
}

/** حقل رقمي موحد لأقسام الصحة والمال والورد. */
@Composable
private fun LifeNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        // تطبيع الأرقام المشرقية/الفارسية والفاصلة العشرية قبل تمريرها للمستدعي،
        // حتى تنجح toIntOrNull/toDoubleOrNull في كل حقول الأرقام.
        onValueChange = { onValueChange(normalizeInputDigits(it.take(8))) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
private fun HabitsSection(
    state: LifeUiState,
    today: LocalDate,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String,
    onToggleHabit: (Habit, Boolean) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (Habit) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Habit?>(null) }
    val todayKey = today.toString()

    LifeSectionCard(
        title = "العادات",
        mark = "✓",
        subtitle = "عاداتك اليومية وسلسلة التزامك",
        summary = summary,
        expanded = expanded,
        onToggle = onToggle
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(48) },
                label = { Text("عادة جديدة (مثال: رياضة، قراءة)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onAdd(name.trim())
                        name = ""
                    }
                },
                shape = RoundedCornerShape(16.dp)
            ) { Text("أضف") }
        }
        if (state.habits.isEmpty()) {
            Text(
                "لا عادات بعد — أضف أول عادة وابدأ سلسلتك.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
        }
        state.habits.forEach { habit ->
            val doneDays = state.habitDays[habit.id].orEmpty()
            val doneToday = todayKey in doneDays
            Column(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = FocusSpacing.rowMinHeight)
                ) {
                    Checkbox(
                        checked = doneToday,
                        onCheckedChange = { onToggleHabit(habit, it) },
                        colors = CheckboxDefaults.colors(checkedColor = FocusColors.Green)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            habit.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FocusColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "السلسلة: ${habitStreak(doneDays, today)} يوم",
                            style = MaterialTheme.typography.bodySmall,
                            color = FocusColors.TextSecondary
                        )
                    }
                    TextButton(onClick = { confirmDelete = habit }) {
                        Text("حذف", color = FocusColors.onTint(FocusColors.Rose))
                    }
                }
                HabitWeekRow(doneDays = doneDays, today = today)
                if (confirmDelete == habit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "احذف «${habit.name}» مع سجلها؟",
                            style = MaterialTheme.typography.bodySmall,
                            color = FocusColors.TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onDelete(habit); confirmDelete = null }) {
                            Text("تأكيد الحذف", color = FocusColors.onTint(FocusColors.Rose))
                        }
                        TextButton(onClick = { confirmDelete = null }) { Text("إلغاء") }
                    }
                }
                HorizontalDivider(
                    color = FocusColors.Outline.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/** شبكة آخر 7 أيام: دائرة مملوءة لكل يوم أُنجزت فيه العادة. */
@Composable
private fun HabitWeekRow(doneDays: Set<String>, today: LocalDate) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (6 downTo 0).forEach { back ->
            val day = today.minusDays(back.toLong())
            val done = day.toString() in doneDays
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier.size(26.dp)
                        .clip(CircleShape)
                        .background(if (done) FocusColors.Teal else FocusColors.SurfaceHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${day.dayOfMonth}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (done) Color.White else FocusColors.TextSecondary
                    )
                }
            }
        }
    }
}

/** أيام متتالية من الإنجاز تنتهي اليوم، أو أمس إن لم يُسجَّل اليوم بعد. */
private fun habitStreak(doneDays: Set<String>, today: LocalDate): Int {
    var count = 0
    var day = if (today.toString() in doneDays) today else today.minusDays(1)
    while (day.toString() in doneDays) {
        count++
        day = day.minusDays(1)
    }
    return count
}

@Composable
private fun WirdSection(
    state: LifeUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String,
    onSaveSettings: (WirdSettings) -> Unit,
    onLogToday: () -> Unit,
    onRestartWird: () -> Unit
) {
    val settings = state.wirdSettings
    var surah by remember(settings) { mutableStateOf(settings.surah) }
    var startPage by remember(settings) { mutableStateOf(settings.startPage.toString()) }
    var pagesPerDay by remember(settings) { mutableStateOf(settings.pagesPerDay.toString()) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val range = wirdRange(settings, state.wirdTotalPages)
    val firstPage = range.firstPage
    val lastPage = range.lastPage
    val pagesText = if (settings.pagesPerDay <= 1) "صفحة $firstPage" else "صفحات $firstPage–$lastPage"

    LifeSectionCard(
        title = "الورد اليومي",
        mark = "☾",
        subtitle = "وردك من القرآن، صفحة بصفحة",
        summary = summary,
        expanded = expanded,
        onToggle = onToggle
    ) {
        if (range.completed) {
            Text(
                "🎉 ختمت المصحف كاملًا — من صفحة ${settings.startPage} حتى صفحة 604.",
                style = MaterialTheme.typography.bodyMedium,
                color = FocusColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "إجمالي ما قرأت في هذه الختمة: ${state.wirdTotalPages} صفحة.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            Button(
                onClick = onRestartWird,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("ابدأ ختمة جديدة من صفحة 2") }
            TextButton(onClick = { showSettings = true }) { Text("أو اختر بداية جديدة") }
        } else {
            Text(
                "ورد اليوم: $pagesText من سورة ${settings.surah}",
                style = MaterialTheme.typography.bodyMedium,
                color = FocusColors.TextPrimary
            )
            Text(
                "إجمالي ما قرأت: ${state.wirdTotalPages} صفحة",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            Button(
                onClick = onLogToday,
                enabled = !state.wirdDoneToday,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(if (state.wirdDoneToday) "تم تسجيل ورد اليوم ✓" else "سجّلت قراءة اليوم")
            }
        }
        TextButton(onClick = { showSettings = !showSettings }) {
            Text(if (showSettings) "إخفاء الإعدادات" else "تعديل إعدادات الورد")
        }
        if (showSettings) {
            OutlinedTextField(
                value = surah,
                onValueChange = { surah = it.take(32) },
                label = { Text("السورة") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LifeNumberField(
                    value = startPage,
                    onValueChange = { startPage = it.filter { ch -> ch.isDigit() } },
                    label = "صفحة البداية",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                LifeNumberField(
                    value = pagesPerDay,
                    onValueChange = { pagesPerDay = it.filter { ch -> ch.isDigit() } },
                    label = "صفحات اليوم",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f)
                )
            }
            Button(
                onClick = {
                    val start = startPage.toIntOrNull()?.coerceAtLeast(1) ?: settings.startPage
                    val perDay = pagesPerDay.toIntOrNull()?.coerceIn(1, 30) ?: settings.pagesPerDay
                    onSaveSettings(
                        WirdSettings(
                            surah = surah.trim().ifBlank { settings.surah },
                            startPage = start,
                            pagesPerDay = perDay
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("حفظ الإعدادات") }
        }
    }
}

@Composable
private fun TasksSection(
    state: LifeUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String,
    onToggleTask: (StudyTask, Boolean) -> Unit,
    onOpenTasks: () -> Unit
) {
    LifeSectionCard(
        title = "المهام",
        mark = "✓",
        subtitle = "مهام اليوم من خطة مُنظِّم",
        summary = summary,
        expanded = expanded,
        onToggle = onToggle
    ) {
        if (state.overdueTasks.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "لديك ${state.overdueTasks.size} مهمة متأخرة عن موعدها.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusColors.onTint(FocusColors.Rose),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onOpenTasks) { Text("عرضها") }
            }
        }
        if (state.tasksDueToday.isEmpty()) {
            Text(
                "لا مهام مجدولة لليوم.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
        }
        state.tasksDueToday.forEach { task ->
            Column(Modifier.fillMaxWidth()) {
                UnifiedTaskCard(
                    task = task,
                    today = LocalDate.now(),
                    density = TaskCardDensity.COMPACT,
                    onToggleDone = { onToggleTask(task, !TaskState.completed(task)) }
                )
                HorizontalDivider(color = FocusColors.Outline.copy(alpha = 0.6f))
            }
        }
        TextButton(onClick = onOpenTasks) { Text("افتح مهامي") }
    }
}

@Composable
private fun HealthSection(
    state: LifeUiState,
    todayKey: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String,
    onSave: (HealthLog) -> Unit,
    onSaveProfile: (FitnessProfile) -> Unit,
    onAddCalorie: (String, Int) -> Unit,
    onDeleteCalorie: (CalorieEntry) -> Unit,
    onToggleExercise: (String, Boolean) -> Unit
) {
    val saved = state.healthToday
    var weight by remember(saved) { mutableStateOf(saved?.weight?.toString() ?: "") }
    var sleep by remember(saved) { mutableStateOf(saved?.sleepHours?.toString() ?: "") }
    var water by remember(saved) { mutableStateOf(saved?.waterCups?.toString() ?: "") }
    var exercise by remember(saved) { mutableStateOf(saved?.exerciseMinutes?.toString() ?: "") }

    LifeSectionCard(
        title = "الصحة",
        mark = "♥",
        subtitle = "سجّل يومك الصحي",
        summary = summary,
        expanded = expanded,
        onToggle = onToggle
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LifeNumberField(
                value = weight,
                onValueChange = { weight = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = "الوزن (كجم)",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            LifeNumberField(
                value = sleep,
                onValueChange = { sleep = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = "النوم (ساعات)",
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.weight(1f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LifeNumberField(
                value = water,
                onValueChange = { water = it.filter { ch -> ch.isDigit() } },
                label = "أكواب الماء",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            LifeNumberField(
                value = exercise,
                onValueChange = { exercise = it.filter { ch -> ch.isDigit() } },
                label = "الرياضة (دقيقة)",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f)
            )
        }
        Button(
            onClick = {
                onSave(
                    HealthLog(
                        day = todayKey,
                        weight = weight.toDoubleOrNull(),
                        sleepHours = sleep.toDoubleOrNull(),
                        waterCups = water.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        exerciseMinutes = exercise.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) { Text(if (saved == null) "حفظ سجل اليوم" else "تحديث سجل اليوم") }
        if (saved != null) {
            Text(
                "تم تسجيل صحة اليوم ✓",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.onTint(FocusColors.Green)
            )
        }
        HorizontalDivider(color = FocusColors.Outline.copy(alpha = 0.6f))
        FitnessBlock(
            profile = state.fitnessProfile,
            calories = state.calorieEntries,
            exerciseDoneToday = state.exerciseDoneToday,
            onSaveProfile = onSaveProfile,
            onAddCalorie = onAddCalorie,
            onDeleteCalorie = onDeleteCalorie,
            onToggleExercise = onToggleExercise
        )
    }
}

@Composable
private fun MoneySection(
    state: LifeUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String,
    onAdd: (MoneyKind, Double, String, String) -> Unit,
    onDelete: (MoneyTransaction) -> Unit
) {
    var amount by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(MoneyKind.EXPENSE) }
    var confirmDelete by remember { mutableStateOf<MoneyTransaction?>(null) }

    val income = state.transactions.filter { it.kind == MoneyKind.INCOME }.sumOf { it.amount }
    val expense = state.transactions.filter { it.kind == MoneyKind.EXPENSE }.sumOf { it.amount }

    LifeSectionCard(
        title = "المال",
        mark = "◈",
        subtitle = "دخلك ومصروفك هذا الشهر",
        summary = summary,
        expanded = expanded,
        onToggle = onToggle
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(FocusSpacing.item)) {
            MoneySummary(label = "الدخل", amount = income, color = FocusColors.Green)
            MoneySummary(label = "المصروف", amount = expense, color = FocusColors.Rose)
            MoneySummary(label = "الباقي", amount = income - expense, color = FocusColors.TextPrimary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "النوع:",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            Spacer(Modifier.width(8.dp))
            MoneyKind.entries.forEach { entry ->
                KindChip(
                    label = entry.label,
                    selected = kind == entry,
                    onSelect = { kind = entry }
                )
                Spacer(Modifier.width(6.dp))
            }
        }
        LifeNumberField(
            value = amount,
            onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } },
            label = "المبلغ",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = category,
            onValueChange = { category = it.take(24) },
            label = { Text("التصنيف (مثال: طعام، مواصلات)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(64) },
            label = { Text("ملاحظة (اختياري)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )
        Button(
            onClick = {
                val value = amount.toDoubleOrNull()
                if (value != null && value > 0) {
                    onAdd(kind, value, category, note)
                    amount = ""
                    category = ""
                    note = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) { Text("أضف") }
        if (state.transactions.isEmpty()) {
            Text(
                "لا معاملات هذا الشهر بعد.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
        }
        state.transactions.forEach { transaction ->
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (transaction.kind == MoneyKind.INCOME) "+" else "−",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (transaction.kind == MoneyKind.INCOME) FocusColors.onTint(FocusColors.Green) else FocusColors.onTint(FocusColors.Rose)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            transaction.category.ifBlank { transaction.kind.label },
                            style = MaterialTheme.typography.bodyMedium,
                            color = FocusColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            transaction.day + if (transaction.note.isNotBlank()) " · ${transaction.note}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = FocusColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        formatAmount(transaction.amount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FocusColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = { confirmDelete = transaction }) {
                        Text("حذف", color = FocusColors.onTint(FocusColors.Rose))
                    }
                }
                if (confirmDelete == transaction) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "احذف هذه المعاملة؟",
                            style = MaterialTheme.typography.bodySmall,
                            color = FocusColors.TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onDelete(transaction); confirmDelete = null }) {
                            Text("تأكيد الحذف", color = FocusColors.onTint(FocusColors.Rose))
                        }
                        TextButton(onClick = { confirmDelete = null }) { Text("إلغاء") }
                    }
                }
                HorizontalDivider(color = FocusColors.Outline.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun RowScope.MoneySummary(label: String, amount: Double, color: Color) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = FocusColors.TextSecondary)
        Text(
            formatAmount(amount),
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, onSelect: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) Color.White else FocusColors.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(FocusSpacing.radiusChip))
            .background(if (selected) FocusColors.Teal else FocusColors.SurfaceHigh)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** المبلغ بلا كسور إن كان صحيحًا، وإلا برقمين عشريين. */
private fun formatAmount(amount: Double): String =
    if (amount == amount.toLong().toDouble()) amount.toLong().toString()
    else String.format("%.2f", amount)

private val MOOD_FACES = listOf("😞", "😐", "🙂", "😄", "🤩")
private val MOOD_LABELS = listOf("سيئ", "عادي", "جيد", "سعيد", "رائع")

@Composable
private fun MoodSection(
    state: LifeUiState,
    today: LocalDate,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String,
    onSelect: (Int) -> Unit
) {
    val moodToday = state.moodToday
    LifeSectionCard(
        title = "المزاج",
        mark = "☺",
        subtitle = "كيف كان يومك؟",
        summary = summary,
        expanded = expanded,
        onToggle = onToggle
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            MOOD_FACES.forEachIndexed { index, face ->
                val mood = index + 1
                val selected = moodToday == mood
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onSelect(mood) }.padding(4.dp)
                ) {
                    Box(
                        modifier = Modifier.size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) FocusColors.Teal.copy(alpha = 0.25f) else FocusColors.SurfaceHigh
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(face, style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        MOOD_LABELS[index],
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) FocusColors.onTint(FocusColors.Teal) else FocusColors.TextSecondary
                    )
                }
            }
        }
        Text(
            if (moodToday == null) "لم تسجّل مزاج اليوم بعد."
            else "مزاجك اليوم: ${MOOD_LABELS[moodToday - 1]}",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.TextSecondary
        )
        if (state.moods30.isNotEmpty()) {
            Text(
                "آخر 30 يومًا",
                style = MaterialTheme.typography.labelMedium,
                color = FocusColors.TextSecondary
            )
            MoodStrip(moods = state.moods30, today = today)
        }
    }
}

/** شريط آخر 30 يومًا: عمود لكل يوم، ارتفاعه مزاج ذلك اليوم. */
@Composable
private fun MoodStrip(moods: List<MoodLog>, today: LocalDate) {
    val byDay = moods.associate { it.day to it.mood }
    val scrollState = rememberScrollState()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        (29 downTo 0).forEach { back ->
            val day = today.minusDays(back.toLong())
            val mood = byDay[day.toString()]
            Box(
                modifier = Modifier
                    .size(width = 8.dp, height = if (mood == null) 8.dp else (8 + mood * 8).dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (mood == null) FocusColors.Outline.copy(alpha = 0.4f) else FocusColors.Teal
                    )
            )
        }
    }
}

/** السعرات والتمارين داخل قسم الصحة: ملف الجسم، سجل يومي، وخطة أسبوعية. */
@Composable
private fun FitnessBlock(
    profile: FitnessProfile?,
    calories: List<CalorieEntry>,
    exerciseDoneToday: Set<String>,
    onSaveProfile: (FitnessProfile) -> Unit,
    onAddCalorie: (String, Int) -> Unit,
    onDeleteCalorie: (CalorieEntry) -> Unit,
    onToggleExercise: (String, Boolean) -> Unit
) {
    var showForm by remember(profile) { mutableStateOf(profile == null) }
    var place by rememberSaveable { mutableStateOf(WorkoutPlace.GYM) }
    var foodName by rememberSaveable { mutableStateOf("") }
    var foodCalories by rememberSaveable { mutableStateOf("") }

    Text(
        "السعرات والتمارين",
        style = MaterialTheme.typography.titleSmall,
        color = FocusColors.TextPrimary,
        fontWeight = FontWeight.SemiBold
    )
    if (profile == null || showForm) {
        FitnessProfileForm(
            profile = profile,
            onSave = { onSaveProfile(it); showForm = false }
        )
        return
    }

    val target = dailyTargetKcal(profile)
    val eaten = calories.sumOf { it.calories }
    val remaining = (target - eaten).coerceAtLeast(0)
    Text(
        "هدف اليوم: $target سعرة · أكلت: $eaten · المتبقي: $remaining",
        style = MaterialTheme.typography.bodyMedium,
        color = FocusColors.TextPrimary
    )
    Text(
        "الأساسي (BMR): ${bmrKcal(profile).toInt()} · الإجمالي (TDEE): ${tdeeKcal(profile).toInt()}",
        style = MaterialTheme.typography.bodySmall,
        color = FocusColors.TextSecondary
    )
    Box(
        Modifier.fillMaxWidth()
            .height(FocusSpacing.progressHeight)
            .clip(RoundedCornerShape(5.dp))
            .background(FocusColors.SurfaceHigh)
    ) {
        Box(
            Modifier.fillMaxWidth((eaten.toFloat() / target).coerceIn(0f, 1f))
                .height(FocusSpacing.progressHeight)
                .clip(RoundedCornerShape(5.dp))
                .background(FocusColors.Teal)
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = foodName,
            onValueChange = { foodName = it.take(48) },
            label = { Text("وجبة أو طعام") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp)
        )
        Spacer(Modifier.width(8.dp))
        LifeNumberField(
            value = foodCalories,
            onValueChange = { foodCalories = it.filter { ch -> ch.isDigit() } },
            label = "السعرات",
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(0.6f)
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = {
                val value = foodCalories.toIntOrNull()
                if (foodName.isNotBlank() && value != null && value > 0) {
                    onAddCalorie(foodName.trim(), value)
                    foodName = ""
                    foodCalories = ""
                }
            },
            shape = RoundedCornerShape(16.dp)
        ) { Text("أضف") }
    }
    if (calories.isEmpty()) {
        Text(
            "لا وجبات مسجلة اليوم بعد.",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.TextSecondary
        )
    }
    calories.forEach { entry ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${entry.calories} سعرة",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
            }
            TextButton(onClick = { onDeleteCalorie(entry) }) {
                Text("حذف", color = FocusColors.onTint(FocusColors.Rose))
            }
        }
    }
    TextButton(onClick = { showForm = true }) { Text("تعديل بيانات الجسم") }

    Text(
        "خطة التمارين الأسبوعية",
        style = MaterialTheme.typography.titleSmall,
        color = FocusColors.TextPrimary,
        fontWeight = FontWeight.SemiBold
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WorkoutPlace.entries.forEach { entry ->
            KindChip(
                label = entry.label,
                selected = place == entry,
                onSelect = { place = entry }
            )
        }
    }
    val plan = workoutPlan(place, profile.gender)
    plan.days.forEach { day ->
        Text(
            "${day.dayLabel} — ${day.focus}",
            style = MaterialTheme.typography.labelMedium,
            color = FocusColors.TextSecondary
        )
        day.exercises.forEach { exercise ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = FocusSpacing.rowMinHeight)
            ) {
                Checkbox(
                    checked = exercise.id in exerciseDoneToday,
                    onCheckedChange = { onToggleExercise(exercise.id, it) },
                    colors = CheckboxDefaults.colors(checkedColor = FocusColors.Green)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        exercise.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FocusColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${exercise.sets} مجموعات × ${exercise.reps} · راحة ${exercise.restSec} ث",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary
                    )
                }
            }
        }
        HorizontalDivider(color = FocusColors.Outline.copy(alpha = 0.6f))
    }
}

/** نموذج بيانات الجسم لحساب السعرات (معادلة Mifflin-St Jeor). */
@Composable
private fun FitnessProfileForm(profile: FitnessProfile?, onSave: (FitnessProfile) -> Unit) {
    var gender by remember(profile) { mutableStateOf(profile?.gender ?: FitnessGender.MALE) }
    var age by remember(profile) { mutableStateOf(profile?.age?.toString() ?: "30") }
    var height by remember(profile) { mutableStateOf(profile?.heightCm?.toString() ?: "170") }
    var weight by remember(profile) { mutableStateOf(profile?.weightKg?.toString() ?: "70") }
    var activity by remember(profile) { mutableStateOf(profile?.activity ?: ActivityLevel.MODERATE) }
    var goal by remember(profile) { mutableStateOf(profile?.goal ?: FitnessGoal.MAINTAIN) }

    Text(
        if (profile == null) "سجّل بيانات جسمك لحساب سعراتك اليومية."
        else "عدّل بيانات جسمك.",
        style = MaterialTheme.typography.bodySmall,
        color = FocusColors.TextSecondary
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FitnessGender.entries.forEach { entry ->
            KindChip(
                label = entry.label,
                selected = gender == entry,
                onSelect = { gender = entry }
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LifeNumberField(
            value = age,
            onValueChange = { age = it.filter { ch -> ch.isDigit() } },
            label = "العمر",
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        LifeNumberField(
            value = height,
            onValueChange = { height = it.filter { ch -> ch.isDigit() || ch == '.' } },
            label = "الطول (سم)",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        LifeNumberField(
            value = weight,
            onValueChange = { weight = it.filter { ch -> ch.isDigit() || ch == '.' } },
            label = "الوزن (كجم)",
            keyboardType = KeyboardType.Decimal,
            modifier = Modifier.weight(1f)
        )
    }
    Text(
        "مستوى النشاط:",
        style = MaterialTheme.typography.bodySmall,
        color = FocusColors.TextSecondary
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        ActivityLevel.entries.forEach { entry ->
            KindChip(
                label = entry.label,
                selected = activity == entry,
                onSelect = { activity = entry }
            )
        }
    }
    Text(
        "الهدف:",
        style = MaterialTheme.typography.bodySmall,
        color = FocusColors.TextSecondary
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FitnessGoal.entries.forEach { entry ->
            KindChip(
                label = entry.label,
                selected = goal == entry,
                onSelect = { goal = entry }
            )
        }
    }
    Button(
        onClick = {
            onSave(
                FitnessProfile(
                    gender = gender,
                    age = age.toIntOrNull()?.coerceIn(10, 100) ?: 30,
                    heightCm = height.toDoubleOrNull()?.coerceIn(100.0, 250.0) ?: 170.0,
                    weightKg = weight.toDoubleOrNull()?.coerceIn(30.0, 300.0) ?: 70.0,
                    activity = activity,
                    goal = goal
                )
            )
        },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) { Text("حفظ بيانات الجسم") }
}

/** قسم المصحف: يفتح القارئ على صفحة ورد اليوم. */
@Composable
private fun QuranSection(
    firstPage: Int,
    lastPage: Int,
    completed: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String,
    onOpenReader: () -> Unit,
    onRestartWird: () -> Unit
) {
    LifeSectionCard(
        title = "المصحف",
        mark = "۞",
        subtitle = "اقرأ وردك من المصحف مباشرة",
        summary = summary,
        expanded = expanded,
        onToggle = onToggle
    ) {
        if (completed) {
            Text(
                "🎉 ختمت المصحف كاملًا — آخر صفحة 604.",
                style = MaterialTheme.typography.bodyMedium,
                color = FocusColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Button(
                onClick = onRestartWird,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("ابدأ ختمة جديدة من صفحة 2") }
        } else {
            Text(
                "ورد اليوم: صفحات $firstPage–$lastPage من مصحف المدينة (604 صفحات).",
                style = MaterialTheme.typography.bodyMedium,
                color = FocusColors.TextPrimary
            )
            Button(
                onClick = onOpenReader,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("افتح المصحف على صفحة اليوم") }
        }
        Text(
            "له مسار مباشر في القايمة الجانبية (المصحف) دون المرور بـ«حياتي».",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.TextSecondary
        )
        Text(
            "النص العثماني: مشروع تنزيل (Tanzil.net) — رخصة CC Attribution 3.0.",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.TextSecondary
        )
    }
}

/** قسم الأذكار: أذكار الصباح والمساء مع عدّاد. */
@Composable
private fun AdhkarSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String,
    onOpen: (Boolean) -> Unit
) {
    LifeSectionCard(
        title = "الأذكار",
        mark = "✦",
        subtitle = "أذكار الصباح والمساء مع عدّاد",
        summary = summary,
        expanded = expanded,
        onToggle = onToggle
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onOpen(true) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) { Text("أذكار الصباح") }
            Button(
                onClick = { onOpen(false) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) { Text("أذكار المساء") }
        }
        Text(
            "له مسار مباشر في القايمة الجانبية (الأذكار) دون المرور بـ«حياتي»، وتقدّمك محفوظ في كل مرة.",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.TextSecondary
        )
        Text(
            "المصدر: حصن المسلم من أذكار الكتاب والسنة — سعيد بن علي بن وهف القحطاني (hisnmuslim.com)",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.TextSecondary
        )
    }
}
