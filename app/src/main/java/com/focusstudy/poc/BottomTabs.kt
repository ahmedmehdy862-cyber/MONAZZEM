package com.focusstudy.poc

/**
 * One destination the person can keep in the bottom bar.
 *
 * Ids are the same route names MainActivity already renders, so adding a tab never needs new
 * navigation code — the bar just highlights a different root route.
 */
enum class BottomTab(val id: String, val label: String, val mark: String) {
    TODAY("today", "اليوم", "◷"),
    TASKS("tasks", "مهامي", "✓"),
    CALENDAR("calendar", "التقويم", "▦"),
    SUBJECTS("subjects", "موادي", "▤"),
    GOALS("goals", "أهدافي", "◎"),
    LIFE("life", "حياتي", "♥"),
    MESSAGES("messages", "الرسائل", "✉"),
    SKILLS("skills", "مهاراتي", "✦"),
    NOTES("notes", "نوتة", "✎"),
    PLANNER("planner", "الخطة", "↗"),
    ANALYTICS("analytics", "إحصاءاتي", "◔"),
    PRAYER("prayer_times", "الصلاة", "☾"),
    NOTIFICATIONS("notifications", "الإشعارات", "🔔"),
    UPDATES("updates", "التحديثات", "↻"),
    TASK_TYPES("task_types", "أنواع المهام", "◈"),
    SETTINGS("settings", "التخصيص", "⚙"),
    SUPPORT("support", "الدعم", "?"),
    MORE("more", "المزيد", "•••");

    companion object {
        /** Five is the widest row Material still labels comfortably in Arabic. */
        const val MAX = 5

        val DEFAULT: List<String> = listOf("today", "tasks", "calendar", "subjects", "more")

        fun from(id: String?): BottomTab? = entries.firstOrNull { it.id == id }

        fun labelOf(id: String): String = from(id)?.label ?: id

        fun markOf(id: String): String = from(id)?.mark ?: "•"

        /**
         * Drops ids this build no longer knows, removes duplicates, caps the row and never returns
         * an empty bar, so an old or hand-edited preference file cannot lock the person out.
         */
        fun normalize(ids: List<String>): List<String> {
            val cleaned = LinkedHashSet<String>()
            ids.forEach { id -> from(id)?.let { cleaned.add(it.id) } }
            val limited = cleaned.toList().take(MAX)
            return limited.ifEmpty { DEFAULT }
        }

        fun canAdd(ids: List<String>): Boolean = normalize(ids).size < MAX
    }
}
