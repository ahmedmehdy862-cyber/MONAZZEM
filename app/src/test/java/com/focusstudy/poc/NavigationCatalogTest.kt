package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 regression guard: reorganising the directory must not make an old destination
 * unreachable, and the dock sheet and «المزيد» must read from one list.
 */
class NavigationCatalogTest {

    /** Every section route the 0.29.0 navigation map could reach. */
    private val historicalSectionRoutes = listOf(
        "subjects", "task_types", "skills", "notes", "bottom_bar", "messages",
        "notifications", "goals", "calendar", "prayer_times", "updates", "settings",
        "permanent_apps", "support", "planner", "analytics", "today", "tasks"
    )

    @Test fun everyHistoricalSectionRouteIsStillInTheDirectory() {
        val routes = NavigationCatalog.allRouteIds()
        historicalSectionRoutes.forEach { id ->
            assertTrue("missing route: $id", id in routes)
            assertNotNull(NavigationCatalog.entry(id))
        }
    }

    /** Phase 2: the reader and the adhkar are direct routes, not buried inside «حياتي». */
    @Test fun quranAndAdhkarAreDirectRoutes() {
        val routes = NavigationCatalog.allRouteIds()
        listOf("quran", "adhkar").forEach { id ->
            assertTrue("missing route: $id", id in routes)
            assertNotNull(NavigationCatalog.entry(id))
        }
        val pinnable = NavigationCatalog.pinnableGroups().flatMap { it.ids }.toSet()
        assertTrue("quran must be pinnable", "quran" in pinnable)
        assertTrue("adhkar must be pinnable", "adhkar" in pinnable)
    }

    /** Phase 2: «المزيد» keeps only the settings shelves; the rest moved to the drawer. */
    @Test fun moreShowsOnlySettingsShelves() {
        assertEquals(setOf(SectionShelf.CUSTOMIZE, SectionShelf.TEAM), NavigationCatalog.moreShelves)
        val moreIds = NavigationCatalog.entries
            .filter { it.shelf in NavigationCatalog.moreShelves }.map { it.id }
        assertEquals(
            listOf("settings", "notifications", "prayer_times", "bottom_bar", "permanent_apps",
                "messages", "updates", "support", "sync"),
            moreIds
        )
    }

    @Test fun directoryHasNoDuplicateRoutes() {
        val ids = NavigationCatalog.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun defaultDockAndCapAreUnchanged() {
        assertEquals(listOf("today", "tasks", "calendar", "subjects", "more"), BottomTab.DEFAULT)
        assertEquals(5, BottomTab.MAX)
    }

    @Test fun everyPinnableTabIsReachableFromTheDockSheet() {
        val pinnedIds = NavigationCatalog.pinnableGroups().flatMap { it.ids }.toSet()
        BottomTab.entries.filter { it != BottomTab.MORE }.forEach { tab ->
            assertTrue("tab not in dock sheet: ${tab.id}", tab.id in pinnedIds)
        }
        assertFalse("more is the directory itself, not a row", pinnedIds.contains("more"))
    }

    @Test fun settingsSubPagesAreOpenableButNotPinnable() {
        val routes = NavigationCatalog.allRouteIds()
        val pinnable = NavigationCatalog.pinnableGroups().flatMap { it.ids }.toSet()
        listOf("bottom_bar", "permanent_apps").forEach { id ->
            assertTrue("$id must stay reachable", id in routes)
            assertFalse("$id is a settings sub-page, not a dock tab", id in pinnable)
        }
    }

    @Test fun syncIsOpenableButNotPinnable() {
        val routes = NavigationCatalog.allRouteIds()
        val pinnable = NavigationCatalog.pinnableGroups().flatMap { it.ids }.toSet()
        assertTrue("sync must stay reachable", "sync" in routes)
        assertFalse("sync is a settings page, not a dock tab", "sync" in pinnable)
    }

    @Test fun everyEntryHasAShelfAndAllShelvesAreUsed() {
        assertTrue(NavigationCatalog.entries.isNotEmpty())
        NavigationCatalog.entries.forEach { entry ->
            assertTrue(SectionShelf.entries.contains(entry.shelf))
            assertTrue(entry.title.isNotBlank())
            assertTrue(entry.mark.isNotBlank())
        }
        SectionShelf.entries.forEach { shelf ->
            assertTrue("empty shelf: ${shelf.title}", NavigationCatalog.entries.any { it.shelf == shelf })
        }
    }
}
