package com.focusstudy.poc.desktop

/**
 * نماذج نسخة ويندوز — نسخة مبسطة من Models.kt في :app، بنفس مفاتيح
 * category/status/priority/recurrence حتى تتطابق حمولة المزامنة حرفيًا.
 */
data class DesktopTask(
    val id: Long,
    val title: String,
    val plannedMinutes: Int,
    val doneMinutes: Int = 0,
    val isDone: Boolean = false,
    val createdAt: Long,
    val category: String = "study",
    val typeId: String? = null,
    val priority: Int = 1,
    val status: String = "todo",
    val dueAt: Long? = null,
    val subjectId: Long? = null,
    val recurrence: String = "none",
    val completedAt: Long? = null,
)

data class DesktopSubject(val id: Long, val name: String)

data class DesktopTaskType(val id: String, val name: String, val archived: Boolean)

data class DesktopHabit(val id: Long, val name: String, val createdAt: Long, val archived: Boolean = false)

data class HabitLogRow(val habitId: Long, val day: String)

data class WirdLogRow(val day: String, val pages: Int, val surah: String, val loggedAt: Long)

data class WirdSettings(val surah: String = "البقرة", val startPage: Int = 2, val pagesPerDay: Int = 2) {
    /** صفحة اليوم التالية، كما في أندرويد: بداية الختمة + ما سُجّل من صفحات. */
    fun nextPage(totalLoggedPages: Int): Int = (startPage + totalLoggedPages).coerceIn(1, 604)
    fun isCompleted(totalLoggedPages: Int): Boolean = startPage + totalLoggedPages > 604
}

data class HealthLog(
    val day: String,
    val weight: Double? = null,
    val sleepHours: Double? = null,
    val waterCups: Int = 0,
    val exerciseMinutes: Int = 0,
)

data class CalorieEntry(val id: Long, val day: String, val name: String, val calories: Int, val createdAt: Long)

/** kind: "income" دخل أو "expense" مصروف — نفس مفاتيح MoneyKind في :app. */
data class MoneyTransaction(
    val id: Long,
    val kind: String,
    val amount: Double,
    val category: String,
    val note: String,
    val day: String,
    val createdAt: Long,
)

data class MoodLog(val day: String, val mood: Int)

/** يوم بصيغة yyyy-MM-dd في المنطقة الزمنية المحلية للجهاز. */
fun todayKey(): String =
    java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

fun monthPrefix(): String =
    java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))

/** عرض عربي مختصر لتاريخ بصيغة yyyy-MM-dd. */
fun displayDay(day: String): String = try {
    java.time.LocalDate.parse(day).format(
        java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale("ar"))
    )
} catch (_: Exception) {
    day
}
