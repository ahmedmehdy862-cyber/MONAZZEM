package com.focusstudy.poc

/**
 * Pure choices for the redesigned Add Task sheet. The old separate "Type" and "Category" rows are
 * merged into one selector, so these lists are the single source for its labels and ordering.
 */
internal object TaskComposer {
    val QUICK_MINUTES: List<Int> = listOf(15, 30, 60, 90)

    /** The eight visible categories; OTHER is kept only for data saved by older versions. */
    val CATEGORIES: List<TaskCategory> = listOf(
        TaskCategory.STUDY,
        TaskCategory.UNIVERSITY,
        TaskCategory.WORK,
        TaskCategory.PERSONAL,
        TaskCategory.RELIGIOUS,
        TaskCategory.LIFE,
        TaskCategory.HABITS,
        TaskCategory.VOLUNTEERING
    )

    fun isQuick(minutes: Int): Boolean = minutes in QUICK_MINUTES

    fun quickOrDefault(minutes: Int): Int = if (isQuick(minutes)) minutes else 30
}
