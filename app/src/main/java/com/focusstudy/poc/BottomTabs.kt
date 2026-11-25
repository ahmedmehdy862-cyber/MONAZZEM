package com.focusstudy.poc

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One destination the person can keep in the bottom bar.
 *
 * Ids are the same route names MainActivity already renders, so adding a tab never needs new
 * navigation code — the bar just highlights a different root route.
 *
 * Each tab carries a real Material icon ([icon]) instead of a text glyph: the old marks
 * (◷ ✓ ▦ …) rendered differently per font and one of them was an emoji (🔔). [mark] is
 * kept as a plain-text fallback for places that cannot draw an icon (accessibility text,
 * logs); the dock, the directory and the bar editor all draw [icon].
 */
enum class BottomTab(val id: String, val label: String, val mark: String, val icon: ImageVector) {
    TODAY("today", "اليوم", "◷", Icons.Filled.Today),
    TASKS("tasks", "مهامي", "✓", Icons.Filled.Checklist),
    CALENDAR("calendar", "التقويم", "▦", Icons.Filled.CalendarMonth),
    SUBJECTS("subjects", "موادي", "▤", Icons.Filled.Book),
    GOALS("goals", "أهدافي", "◎", Icons.Filled.Flag),
    LIFE("life", "حياتي", "♥", Icons.Filled.Favorite),
    /**
     * Direct routes out of «حياتي» (phase 2): the reader and the adhkar used to live two
     * levels deep inside the life tracker. They are tabs here so the drawer and the catalog
     * can point at them with a real icon; [DEFAULT] is unchanged, so nobody's bar moves.
     */
    QURAN("quran", "المصحف", "۞", Icons.Filled.MenuBook),
    ADHKAR("adhkar", "الأذكار", "☀", Icons.Filled.WbSunny),
    MESSAGES("messages", "الرسائل", "✉", Icons.Filled.Email),
    SKILLS("skills", "مهاراتي", "✦", Icons.Filled.Star),
    NOTES("notes", "نوتة", "✎", Icons.Filled.Note),
    PLANNER("planner", "الخطة", "↗", Icons.Filled.Dashboard),
    ANALYTICS("analytics", "إحصاءاتي", "◔", Icons.Filled.BarChart),
    PRAYER("prayer_times", "الصلاة", "☾", Icons.Filled.Nightlight),
    NOTIFICATIONS("notifications", "الإشعارات", "🔔", Icons.Filled.Notifications),
    UPDATES("updates", "التحديثات", "↻", Icons.Filled.Refresh),
    TASK_TYPES("task_types", "أنواع المهام", "◈", Icons.Filled.Category),
    SETTINGS("settings", "التخصيص", "⚙", Icons.Filled.Settings),
    SUPPORT("support", "الدعم", "?", Icons.Filled.Help),
    MORE("more", "المزيد", "•••", Icons.Filled.MoreHoriz);

    companion object {
        /** Five is the widest row Material still labels comfortably in Arabic. */
        const val MAX = 5

        val DEFAULT: List<String> = listOf("today", "tasks", "calendar", "subjects", "more")

        fun from(id: String?): BottomTab? = entries.firstOrNull { it.id == id }

        fun labelOf(id: String): String = from(id)?.label ?: id

        fun markOf(id: String): String = from(id)?.mark ?: "•"

        fun iconOf(id: String): ImageVector = from(id)?.icon ?: Icons.Filled.MoreHoriz

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
