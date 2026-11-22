package com.focusstudy.poc

/**
 * The values collected by the add-task form, with "just a name" as the complete happy path.
 *
 * Keeping the defaults and the validation in one small pure type makes the rule testable without a
 * device: a non-empty name is enough, an empty minutes box means "use the default", and every
 * advanced field keeps the value it always had. The form and the view model still receive the same
 * primitive values as before — nothing is stored or sent differently.
 */
internal data class TaskDraft(
    val title: String,
    val minutes: Int = DEFAULT_MINUTES,
    val category: TaskCategory = TaskCategory.STUDY,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val dueAt: String? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    val notes: String? = null,
    val subjectId: Long? = null,
    val typeId: String? = null
) {

    /** True when the draft can safely be handed to the task view model. */
    fun isValid(): Boolean = title.isNotBlank() && minutes in MIN_MINUTES..MAX_MINUTES

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 720
        const val DEFAULT_MINUTES = 30

        /** A typed name alone creates a valid task with the long-standing defaults. */
        fun fromName(name: String): TaskDraft? =
            name.trim().takeIf(String::isNotEmpty)?.let { TaskDraft(title = it) }

        /** Blank minutes fall back to the default instead of blocking a name-only add. */
        fun minutesFrom(input: String): Int =
            input.trim().toIntOrNull()?.takeIf { it in MIN_MINUTES..MAX_MINUTES } ?: DEFAULT_MINUTES

        /** The enabled state of the form's add button. */
        fun canAdd(name: String, minutes: String): Boolean {
            val typed = minutes.trim()
            return name.trim().isNotEmpty() &&
                (typed.isEmpty() || typed.toIntOrNull() in MIN_MINUTES..MAX_MINUTES)
        }
    }
}
