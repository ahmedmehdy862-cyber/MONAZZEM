package com.focusstudy.poc

/** Task classification used by filters, the dashboard and analytics. */
enum class TaskCategory(val key: String, val label: String) {
    STUDY("study", "دراسة"),
    UNIVERSITY("university", "جامعة"),
    WORK("work", "عمل"),
    PERSONAL("personal", "شخصي"),
    RELIGIOUS("religious", "ديني"),
    LIFE("life", "حياة"),
    HABITS("habits", "عادات"),
    VOLUNTEERING("volunteering", "تطوع"),
    OTHER("other", "أخرى");

    companion object {
        fun from(key: String?): TaskCategory =
            entries.firstOrNull { it.key == key } ?: STUDY
    }
}

enum class TaskPriority(val level: Int, val label: String) {
    LOW(0, "منخفضة"),
    MEDIUM(1, "متوسطة"),
    HIGH(2, "عالية"),
    URGENT(3, "عاجلة");

    companion object {
        fun from(level: Int): TaskPriority =
            entries.firstOrNull { it.level == level } ?: MEDIUM
    }
}

enum class TaskStatus(val key: String, val label: String) {
    TODO("todo", "لم تبدأ"),
    DOING("doing", "قيد التنفيذ"),
    DONE("done", "مكتملة"),
    POSTPONED("postponed", "مؤجلة"),
    CANCELLED("cancelled", "ملغاة");

    companion object {
        fun from(key: String?): TaskStatus =
            entries.firstOrNull { it.key == key } ?: TODO
    }
}

enum class Recurrence(val key: String, val label: String) {
    NONE("none", "بدون تكرار"),
    DAILY("daily", "يوميًا"),
    WEEKLY("weekly", "أسبوعيًا"),
    MONTHLY("monthly", "شهريًا");

    companion object {
        fun from(key: String?): Recurrence =
            entries.firstOrNull { it.key == key } ?: NONE
    }
}

/**
 * A task as stored in the local database.
 *
 * [isDone] is kept for backward compatibility with the v1 schema and stays in sync with
 * [status] / accumulated focus minutes; [progress] is derived from minutes.
 */
/** A task type; built-in rows keep stable ids so a rename never breaks stored tasks. */
data class TaskType(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val archived: Boolean = false,
    val builtIn: Boolean = false
)

data class StudyTask(
    val id: Long,
    val title: String,
    val plannedMinutes: Int,
    val doneMinutes: Int,
    val isDone: Boolean,
    val blockedPackages: Set<String>,
    val createdAt: Long,
    val description: String = "",
    val category: TaskCategory = TaskCategory.STUDY,
    val typeId: String? = null,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val status: TaskStatus = TaskStatus.TODO,
    val startAt: Long? = null,
    val dueAt: Long? = null,
    val subjectId: Long? = null,
    val notes: String = "",
    val recurrence: Recurrence = Recurrence.NONE,
    val completedAt: Long? = null,
    val sortOrder: Int = 0,
    val originalDueAt: Long? = null
) {
    val progress: Float
        get() = if (plannedMinutes <= 0) 0f else (doneMinutes.toFloat() / plannedMinutes).coerceIn(0f, 1f)

    val remainingMinutes: Int
        get() = (plannedMinutes - doneMinutes).coerceAtLeast(0)
}

data class Subtask(
    val id: Long,
    val taskId: Long,
    val title: String,
    val isDone: Boolean,
    val sortOrder: Int
)

data class StudySubject(
    val id: Long,
    val semesterId: Long?,
    val name: String,
    val color: Int,
    val icon: String,
    val instructor: String,
    val goalMinutes: Int,
    val createdAt: Long,
    val nextExamAt: Long?
)

data class StudyEvent(
    val id: Long,
    val title: String,
    val kind: String,
    val startsAt: Long,
    val endsAt: Long,
    val notes: String = ""
)

/**
 * A private note in the in-app notebook «نوتة».
 *
 * Notes never leave the device: there is no network call anywhere in their repository, and no
 * field is copied into the user-registration or support payloads.
 */
data class Note(
    val id: Long = 0L,
    val title: String = "",
    val body: String = "",
    val category: String = "",
    val pinned: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

/** A skill the person is deliberately developing, with practice time and a level they set. */
data class Skill(
    val id: Long,
    val name: String,
    val category: String = "",
    val level: Int = 1,
    val targetLevel: Int = 3,
    val minutes: Int = 0,
    val lastPracticedAt: Long = 0L,
    val createdAt: Long = 0L
) {
    /** 0f..1f, so the UI can draw a quiet progress bar without extra state. */
    val progress: Float
        get() = if (targetLevel <= 0) 0f else (level.toFloat() / targetLevel).coerceIn(0f, 1f)
}

/** A long-term goal measured by a number the person picks (دروس، ساعات، كتب…). */
/** One task or skill that serves a goal; links are what make the three sections a path. */
data class GoalLink(val kind: String, val refId: Long)

data class Goal(
    val id: Long,
    val title: String,
    val target: Int,
    val current: Int = 0,
    val unit: String = "خطوة",
    val category: String = "",
    val dueAt: Long? = null,
    val status: String = "active",
    val createdAt: Long = 0L
) {
    val progress: Float
        get() = if (target <= 0) 0f else (current.toFloat() / target).coerceIn(0f, 1f)
}

data class FocusSessionMetric(val startedAt: Long, val endsAt: Long, val taskId: Long)

/** عادة يومية في تتبع الحياة «حياتي»؛ سجل إنجازها اليومي في habit_logs. */
data class Habit(
    val id: Long,
    val name: String,
    val createdAt: Long = 0L,
    /** مؤرشفة: تبقى في المزامنة (تُرسل archived=true) لكن شاشة «حياتي» تخفيها. */
    val archived: Boolean = false
)

/** سطر واحد في habit_logs: إنجاز عادة في يوم؛ الغياب يعني عدم الإنجاز. */
data class HabitLogRow(
    val habitId: Long,
    val day: String
)

/** سجل ورد يوم واحد؛ المعرّف المتزامَن هو `day` نفسه. */
data class WirdLog(
    val day: String,
    val pages: Int,
    val surah: String,
    val loggedAt: Long = 0L
)

/** إعدادات الورد اليومي؛ الافتراضي صفحتان يوميًا من سورة البقرة بداية من صفحة 2. */
data class WirdSettings(
    val surah: String = "البقرة",
    val startPage: Int = 2,
    val pagesPerDay: Int = 2
)

/** نطاق ورد اليوم بعد قصّه عند نهاية المصحف، مع كشف الختم الكامل. */
data class WirdRange(val firstPage: Int, val lastPage: Int, val completed: Boolean)

/**
 * نطاق ورد اليوم من الإعدادات وإجمالي الصفحات المقروءة.
 *
 * القاعدة: أول صفحة = صفحة البداية + ما قُرئ، مقصوصة إلى 604 كحد أقصى؛ والختم
 * كامل عندما يتجاوز مجموع البداية وما قُرئ صفحة 604، أي عندما لا تبقى صفحة
 * لم تُقرأ من صفحة البداية حتى نهاية المصحف. عند الختم لا يوجد نطاق وهمي
 * (مثل «صفحات 606–607»): آخر صفحة 604، ويعرض المستدعي حالة الختم بدل النطاق.
 */
fun wirdRange(settings: WirdSettings, totalPages: Int): WirdRange {
    val completed = settings.startPage + totalPages > 604
    val first = (settings.startPage + totalPages).coerceAtMost(604)
    val last = if (completed) 604 else (first + settings.pagesPerDay - 1).coerceAtMost(604)
    return WirdRange(first, last, completed)
}

/** تسجيل صحي ليوم واحد؛ الوزن والنوم اختياريان، والماء والرياضة صفر عند عدم التسجيل. */
data class HealthLog(
    val day: String,
    val weight: Double? = null,
    val sleepHours: Double? = null,
    val waterCups: Int = 0,
    val exerciseMinutes: Int = 0
)

enum class MoneyKind(val key: String, val label: String) {
    INCOME("income", "دخل"),
    EXPENSE("expense", "مصروف");

    companion object {
        fun from(key: String?): MoneyKind =
            entries.firstOrNull { it.key == key } ?: EXPENSE
    }
}

data class MoneyTransaction(
    val id: Long,
    val kind: MoneyKind,
    val amount: Double,
    val category: String = "",
    val note: String = "",
    val day: String,
    val createdAt: Long = 0L
)

/** مزاج يوم واحد على مقياس 1..5. */
data class MoodLog(
    val day: String,
    val mood: Int
)

/** جنس المستخدم لحساب السعرات (معادلة Mifflin-St Jeor). */
enum class FitnessGender(val key: String, val label: String) {
    MALE("male", "ذكر"),
    FEMALE("female", "أنثى");

    companion object {
        fun from(key: String?): FitnessGender =
            entries.firstOrNull { it.key == key } ?: MALE
    }
}

/** مستوى النشاط البدني مع معامل TDEE. */
enum class ActivityLevel(val key: String, val label: String, val factor: Double) {
    SEDENTARY("sedentary", "قليل الحركة (عمل مكتبي)", 1.2),
    LIGHT("light", "نشاط خفيف (1–3 أيام أسبوعيًا)", 1.375),
    MODERATE("moderate", "نشاط متوسط (3–5 أيام أسبوعيًا)", 1.55),
    ACTIVE("active", "نشاط عالٍ (6–7 أيام أسبوعيًا)", 1.725),
    VERY_ACTIVE("very_active", "نشاط عالٍ جدًا + عمل بدني", 1.9);

    companion object {
        fun from(key: String?): ActivityLevel =
            entries.firstOrNull { it.key == key } ?: MODERATE
    }
}

/** هدف السعرات اليومية. */
enum class FitnessGoal(val key: String, val label: String, val adjustmentKcal: Int) {
    LOSE("lose", "إنقاص الوزن", -500),
    MAINTAIN("maintain", "الحفاظ على الوزن", 0),
    GAIN("gain", "زيادة الوزن", 300);

    companion object {
        fun from(key: String?): FitnessGoal =
            entries.firstOrNull { it.key == key } ?: MAINTAIN
    }
}

/** ملف الجسم المحفوظ محليًا؛ صف واحد في fitness_profile. */
data class FitnessProfile(
    val gender: FitnessGender = FitnessGender.MALE,
    val age: Int = 30,
    val heightCm: Double = 170.0,
    val weightKg: Double = 70.0,
    val activity: ActivityLevel = ActivityLevel.MODERATE,
    val goal: FitnessGoal = FitnessGoal.MAINTAIN
)

/** وجبة أو طعام سُجّل يدويًا في يوم معين (إدخال يدوي فقط، بلا قاعدة بيانات أغذية). */
data class CalorieEntry(
    val id: Long,
    val day: String,
    val name: String,
    val calories: Int,
    val createdAt: Long = 0L
)

/** إتمام تمرين في يوم معين؛ يُبنى منه سجل الإنجاز الأسبوعي. */
data class ExerciseDone(
    val day: String,
    val exerciseId: String
)
