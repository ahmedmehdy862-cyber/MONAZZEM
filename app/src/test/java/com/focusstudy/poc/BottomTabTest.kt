package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomTabTest {

    @Test fun notesIsARoutableDestination() {
        assertNotNull(BottomTab.from("notes"))
        assertEquals("نوتة", BottomTab.from("notes")?.label)
    }

    @Test fun notesCanBePinnedToTheDockWithoutChangingTheDefault() {
        assertEquals(listOf("today", "tasks", "calendar", "subjects", "more"), BottomTab.DEFAULT)
        val pinned = BottomTab.normalize(listOf("notes", "today", "notes"))
        assertEquals(listOf("notes", "today"), pinned)
    }

    @Test fun normalizeDropsUnknownIdsAndCapsTheRow() {
        val ids = listOf("notes", "unknown", "notes", "today", "tasks", "calendar", "subjects")
        assertEquals(BottomTab.MAX, BottomTab.normalize(ids).size)
        assertEquals("notes", BottomTab.normalize(ids).first())
        assertEquals(BottomTab.DEFAULT, BottomTab.normalize(listOf("unknown")))
    }

    @Test fun movedShiftsItemForwardAndBackward() {
        val ids = listOf("today", "tasks", "calendar", "subjects", "more")
        assertEquals(
            listOf("tasks", "calendar", "today", "subjects", "more"),
            BottomTab.moved(ids, 0, 2)
        )
        assertEquals(
            listOf("today", "tasks", "subjects", "calendar", "more"),
            BottomTab.moved(ids, 3, 2)
        )
        assertEquals(
            listOf("tasks", "calendar", "subjects", "more", "today"),
            BottomTab.moved(ids, 0, 4)
        )
    }

    @Test fun movedIgnoresSameIndexAndOutOfRange() {
        val ids = listOf("today", "tasks")
        assertEquals(ids, BottomTab.moved(ids, 0, 0))
        assertEquals(ids, BottomTab.moved(ids, 1, 1))
        assertEquals(ids, BottomTab.moved(ids, -1, 1))
        assertEquals(ids, BottomTab.moved(ids, 0, 5))
        assertEquals(ids, BottomTab.moved(ids, 2, 0))
        assertEquals(emptyList<String>(), BottomTab.moved(emptyList(), 0, 0))
    }

    @Test fun movedKeepsEveryTabExactlyOnce() {
        val ids = listOf("today", "tasks", "calendar", "subjects", "more")
        val moved = BottomTab.moved(ids, 4, 0)
        assertEquals(listOf("more", "today", "tasks", "calendar", "subjects"), moved)
        assertEquals(ids.toSet(), moved.toSet())
    }
}
