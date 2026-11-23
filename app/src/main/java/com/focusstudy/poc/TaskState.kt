package com.focusstudy.poc

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The single, user-facing state of a task. Derived from one source of truth: [StudyTask.status]. */
internal enum class DisplayTaskState {
    TODO, IN_PROGRESS, DONE, OVERDUE, POSTPONED, CANCELLED
}

/**
 * Normalises the legacy [StudyTask.isDone] mirror against [StudyTask.status] so every screen shows
 * the same badge, one due date and an explicit overdue count. No data is rewritten; the database
 * keeps both columns for backward compatibility with older installs.
 */
internal object TaskState {

    fun completed(task: StudyTask): Boolean =
        task.status == TaskStatus.DONE || (task.isDone && task.status != TaskStatus.CANCELLED)

    fun active(task: StudyTask): Boolean =
        !completed(task) && task.status != TaskStatus.CANCELLED && task.status != TaskStatus.POSTPONED

    fun dueDate(task: StudyTask, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    fun overdueDays(task: StudyTask, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int {
        if (!active(task)) return 0
        val due = dueDate(task, zone) ?: return 0
        return maxOf(0, ChronoUnit.DAYS.between(due, today).toInt())
    }

    fun of(task: StudyTask, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DisplayTaskState {
        val days = overdueDays(task, today, zone)
        return when {
            completed(task) -> DisplayTaskState.DONE
            task.status == TaskStatus.CANCELLED -> DisplayTaskState.CANCELLED
            task.status == TaskStatus.POSTPONED -> DisplayTaskState.POSTPONED
            days > 0 -> DisplayTaskState.OVERDUE
            task.status == TaskStatus.DOING -> DisplayTaskState.IN_PROGRESS
            else -> DisplayTaskState.TODO
        }
    }

    fun badge(task: StudyTask, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val overdue = overdueDays(task, today, zone)
        return when (of(task, today, zone)) {
            DisplayTaskState.DONE -> "مكتملة"
            DisplayTaskState.CANCELLED -> "ملغاة"
            DisplayTaskState.POSTPONED -> "مؤجلة"
            DisplayTaskState.OVERDUE -> "متأخرة بـ $overdue ${dayWord(overdue)}"
            DisplayTaskState.IN_PROGRESS -> "قيد التنفيذ"
            DisplayTaskState.TODO -> "لم تبدأ"
        }
    }

    /** Explicit completion without logged minutes is labelled honestly instead of "0 من 30 دقيقة". */
    fun progressText(task: StudyTask): String =
        if (completed(task) && task.doneMinutes < task.plannedMinutes) {
            "أُنجزت يدويًا · ${minuteCount(task.plannedMinutes)} مخططة"
        } else {
            "${task.doneMinutes} من ${task.plannedMinutes} دقيقة"
        }

    /** Exactly one due date. The old reschedule date is intentionally not displayed. */
    fun dueText(task: StudyTask, zone: ZoneId = ZoneId.systemDefault()): String {
        val due = dueDate(task, zone) ?: return "بدون موعد"
        return "الموعد ${due.dayOfMonth}-${due.monthValue}"
    }

    private fun dayWord(days: Int): String = when {
        days == 1 -> "يوم"
        days == 2 -> "يومين"
        else -> "أيام"
    }
}
