package com.focusstudy.poc

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Picks "الخطوة الآن" and explains why.
 *
 * Pure and deterministic: same tasks, same clock, same answer — so the card and its tests agree.
 */
internal data class NextStep(
    val task: StudyTask,
    val reason: String,
    val suggestedMinutes: Int
)

internal object NextStepPlanner {
    fun pick(
        tasks: List<StudyTask>,
        now: Long,
        subjectNames: Map<Long, String> = emptyMap(),
        examBySubject: Map<Long, Long?> = emptyMap(),
        prayerAtMillis: Long? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ): NextStep? {
        val open = tasks.filter { !it.isDone && it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED }
        if (open.isEmpty()) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val best = open.maxWithOrNull(
            compareBy<StudyTask> { score(it, now, examBySubject, zone, today) }
                .thenByDescending { it.priority.level }
                .thenBy { it.dueAt ?: Long.MAX_VALUE }
        ) ?: return null

        val reason = reasonFor(best, now, subjectNames, examBySubject, zone, today, prayerAtMillis)
        val minutes = best.remainingMinutes.coerceAtMost(25).coerceAtLeast(5)
        return NextStep(best, reason, minutes)
    }

    fun score(task: StudyTask, now: Long, examBySubject: Map<Long, Long?>, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()): Double {
        var score = task.priority.level * 20.0
        val due = task.dueAt
        if (due != null) {
            val dueDate = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
            when {
                dueDate.isBefore(today) -> score += 60 + (today.toEpochDay() - dueDate.toEpochDay()).coerceAtMost(7) * 5
                dueDate == today -> score += 30 - (due - now).coerceAtLeast(0) / 3_600_000.0
                else -> score += 8 - (dueDate.toEpochDay() - today.toEpochDay()).coerceAtMost(14)
            }
        }
        val examAt = task.subjectId?.let { examBySubject[it] }
        if (examAt != null) {
            val days = ((examAt - now) / 86_400_000L).toInt()
            score += when {
                days < 0 -> 0.0
                days <= 2 -> 80.0
                days <= 3 -> 55.0
                days <= 7 -> (7 - days) * 8.0
                days <= 14 -> (14 - days) * 3.0
                else -> 0.0
            }
        }
        score -= task.plannedMinutes * 0.05
        return score
    }

    private fun reasonFor(
        task: StudyTask,
        now: Long,
        subjectNames: Map<Long, String>,
        examBySubject: Map<Long, Long?>,
        zone: ZoneId,
        today: LocalDate,
        prayerAtMillis: Long?
    ): String {
        val examAt = task.subjectId?.let { examBySubject[it] }
        val subject = task.subjectId?.let { subjectNames[it] }
        val daysToExam = examAt?.let { ((it - now) / 86_400_000L).toInt() }
        if (subject != null && daysToExam != null && daysToExam in 0..3) {
            return "امتحان $subject " + daysText(daysToExam)
        }
        val dueDate = task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        if (dueDate != null && dueDate.isBefore(today)) {
            val days = (today.toEpochDay() - dueDate.toEpochDay()).toInt()
            return if (days <= 1) "موعدها كان أمس" else "تأخرت $days أيام عن موعدها"
        }
        if (dueDate == today) {
            val hour = Instant.ofEpochMilli(task.dueAt!!).atZone(zone).toLocalTime()
            val time = String.format(java.util.Locale.US, "%d:%02d", hour.hour, hour.minute)
            return "موعدها اليوم $time"
        }
        if (prayerAtMillis != null && prayerAtMillis - now in 0..(20 * 60_000L)) {
            return "الصلاة القريبة أولى؛ ابدأ بعدها"
        }
        if (task.originalDueAt != null && task.originalDueAt != task.dueAt) return "أُجّلت مرة، والأفضل إنهاؤها الآن"
        if (task.priority == TaskPriority.URGENT) return "أولوية عاجلة"
        if (task.priority == TaskPriority.HIGH) return "أولوية عالية ومدة قصيرة"
        return "أول مهمة مفتوحة"
    }

    private fun daysText(days: Int): String = when (days) {
        0 -> "اليوم"
        1 -> "بعد يوم"
        2 -> "بعد يومين"
        else -> "بعد $days أيام"
    }
}

/** Urgency ranking behind the "الأذكى" task sort; the four older sorts stay untouched. */
internal object SmartOrder {
    fun sort(
        tasks: List<StudyTask>,
        now: Long,
        examBySubject: Map<Long, Long?>,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<StudyTask> = tasks.sortedWith(
        compareByDescending<StudyTask> { NextStepPlanner.score(it, now, examBySubject, zone) }
            .thenBy { it.dueAt ?: Long.MAX_VALUE }
            .thenByDescending { it.priority.level }
    )
}
