package com.focusstudy.poc

import android.content.Context
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
    val factsCount: Int = 0
)

internal object AssistantKnowledge {

    /** يبني اللقطة من المستودعات؛ أي قراءة تفشل تُستبدل بقيمتها الافتراضية. */
    suspend fun load(context: Context, userName: String): AssistantSnapshot {
        val zone = AppTimeZone.zone(context)
        val today = LocalDate.now(zone)
        val todayKey = today.toString()
        val life = LifeRepository.get(context)
        val tasksRepo = TaskRepository.get(context)

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
            factsCount = store.loadFacts(context).size
        )
    }
}
