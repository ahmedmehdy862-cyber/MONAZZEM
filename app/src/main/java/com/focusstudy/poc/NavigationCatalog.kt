package com.focusstudy.poc

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GridView
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One shelf in the all-sections directory.
 *
 * The directory used to be written twice — once for the dock sheet and once for the «المزيد»
 * screen — and the two lists drifted apart. Both now read from [NavigationCatalog], so adding or
 * moving a destination is a single edit and no old route can be lost by accident.
 */
internal enum class SectionShelf(val title: String, val subtitle: String) {
    PLANNING("التخطيط والتقدّم", "كيف يتحوّل يومك إلى خطة، وكيف تقيس ما أنجزته"),
    GROWTH("أهدافي وتطويري", "الصورة الكبيرة والمهارات التي تبنيها"),
    CUSTOMIZE("التخصيص والتنبيهات", "شكل التطبيق وما يذكّرك به"),
    TEAM("الفريق والتحديثات", "ما يصل منّا، وما يصل للتطبيق")
}

/** A single destination in the directory. `id` is a route `MainActivity` already renders. */
internal data class SectionEntry(
    val id: String,
    val title: String,
    val description: String,
    val mark: String,
    val shelf: SectionShelf,
    /** Real icon drawn by the directory; [mark] stays as the plain-text fallback. */
    val icon: ImageVector
)

/** Kept as a small value type so the dock sheet keeps its existing signature. */
internal data class SectionGroup(val title: String, val ids: List<String>)

/**
 * The single source of truth for every section a person can open.
 *
 * Ids are deliberately the existing route names: nothing here invents a destination, and every
 * entry is reachable from both the dock sheet and «المزيد». Only the four transient screens
 * (`onboarding`, `session`, `setup`, `picker`) and the `more` container itself stay outside the
 * directory — they are entered through setup, the dock, or a notification instead.
 */
internal object NavigationCatalog {

    val entries: List<SectionEntry> = listOf(

        SectionEntry("today", BottomTab.TODAY.label, "خطّة يومك والمهمة التالية.", BottomTab.TODAY.mark, SectionShelf.PLANNING, BottomTab.TODAY.icon),
        SectionEntry("tasks", BottomTab.TASKS.label, "كل مهامك في قائمة واحدة.", BottomTab.TASKS.mark, SectionShelf.PLANNING, BottomTab.TASKS.icon),
        SectionEntry("calendar", BottomTab.CALENDAR.label, "مواعيدك وجلساتك على التقويم.", BottomTab.CALENDAR.mark, SectionShelf.PLANNING, BottomTab.CALENDAR.icon),
        SectionEntry("planner", BottomTab.PLANNER.label, "حوّل مهامك إلى جلسات قابلة للتنفيذ.", BottomTab.PLANNER.mark, SectionShelf.PLANNING, BottomTab.PLANNER.icon),
        SectionEntry("task_types", BottomTab.TASK_TYPES.label, "أضف أنواعك المخصّصة، وأعد ترتيبها أو أرشفها.", BottomTab.TASK_TYPES.mark, SectionShelf.PLANNING, BottomTab.TASK_TYPES.icon),
        SectionEntry("notes", BottomTab.NOTES.label, "ملاحظاتك الخاصة — محفوظة على جهازك فقط.", BottomTab.NOTES.mark, SectionShelf.PLANNING, BottomTab.NOTES.icon),
        SectionEntry("analytics", BottomTab.ANALYTICS.label, "راجع وقت التركيز والمهام المنجزة.", BottomTab.ANALYTICS.mark, SectionShelf.PLANNING, BottomTab.ANALYTICS.icon),

        SectionEntry("goals", BottomTab.GOALS.label, "أهداف بمقياس ومهلة، ومهام ومهارات مرتبطة بها.", BottomTab.GOALS.mark, SectionShelf.GROWTH, BottomTab.GOALS.icon),
        SectionEntry("skills", BottomTab.SKILLS.label, "سجّل المهارات التي تطوّرها ودقائق تدريبك.", BottomTab.SKILLS.mark, SectionShelf.GROWTH, BottomTab.SKILLS.icon),
        SectionEntry("life", BottomTab.LIFE.label, "عاداتك ووردك وصحتك ومالك ومزاجك في مكان واحد.", BottomTab.LIFE.mark, SectionShelf.GROWTH, BottomTab.LIFE.icon),
        SectionEntry("subjects", BottomTab.SUBJECTS.label, "نظّم موادك ومحاضراتك.", BottomTab.SUBJECTS.mark, SectionShelf.GROWTH, BottomTab.SUBJECTS.icon),

        SectionEntry("settings", BottomTab.SETTINGS.label, "اسمك وهدفك اليومي والمظهر والملف.", BottomTab.SETTINGS.mark, SectionShelf.CUSTOMIZE, BottomTab.SETTINGS.icon),
        SectionEntry("notifications", BottomTab.NOTIFICATIONS.label, "ما يصلك ومتى.", BottomTab.NOTIFICATIONS.mark, SectionShelf.CUSTOMIZE, BottomTab.NOTIFICATIONS.icon),
        SectionEntry("prayer_times", BottomTab.PRAYER.label, "احسب المواقيت حسب موقعك واضبط تذكيرها.", BottomTab.PRAYER.mark, SectionShelf.CUSTOMIZE, BottomTab.PRAYER.icon),
        SectionEntry("bottom_bar", "الشريط السفلي", "اختر أقسام الشريط السفلي ورتّبها.", "▥", SectionShelf.CUSTOMIZE, Icons.Filled.GridView),
        SectionEntry("permanent_apps", "التطبيقات الدائمة", "التطبيقات التي تبقى مفتوحة أثناء الجلسة.", "⊞", SectionShelf.CUSTOMIZE, Icons.Filled.Apps),

        SectionEntry("messages", BottomTab.MESSAGES.label, "إعلانات ومستجدّات بدون تحديث التطبيق.", BottomTab.MESSAGES.mark, SectionShelf.TEAM, BottomTab.MESSAGES.icon),
        SectionEntry("updates", BottomTab.UPDATES.label, "افحص الإصدارات الجديدة واقرأ سجل التغييرات.", BottomTab.UPDATES.mark, SectionShelf.TEAM, BottomTab.UPDATES.icon),
        SectionEntry("support", BottomTab.SUPPORT.label, "تواصل معنا إذا احتجت إلى مساعدة.", BottomTab.SUPPORT.mark, SectionShelf.TEAM, BottomTab.SUPPORT.icon),
        SectionEntry("sync", "المزامنة", "فعّل حسابك واستخدمه في تطبيق الويب.", "☁", SectionShelf.TEAM, Icons.Filled.Cloud)
    )

    /** Every route the directory can open — a regression test compares this with the old map. */
    fun allRouteIds(): Set<String> = entries.map { it.id }.toSet()

    fun entry(id: String): SectionEntry? = entries.firstOrNull { it.id == id }

    /** Destinations the dock knows how to pin; settings sub-pages stay openable but not pinnable. */
    fun pinnableEntries(): List<SectionEntry> = entries.filter { BottomTab.from(it.id) != null }

    /** The dock sheet's grouped view, derived from the same catalog as «المزيد». */
    fun pinnableGroups(): List<SectionGroup> = SectionShelf.entries.mapNotNull { shelf ->
        val ids = pinnableEntries().filter { it.shelf == shelf }.map { it.id }
        ids.takeIf { it.isNotEmpty() }?.let { SectionGroup(shelf.title, it) }
    }
}
