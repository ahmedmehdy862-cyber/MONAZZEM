package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderPersistenceTest {

    @Test
    fun `plain reminder kind is not treated as a deferral`() {
        val decoded = ReminderEngine.decodeKind(ReminderKind.TASK_DUE.key)
        assertEquals(ReminderKind.TASK_DUE.key, decoded.sourceKind)
        assertFalse(decoded.deferred)
        assertEquals("", decoded.deferReason)
    }

    @Test
    fun `deferred reminder keeps its original kind and reason`() {
        val encoded = ReminderEngine.encodeDeferred(ReminderKind.TASK_DUE.key, "quiet_hours")
        val decoded = ReminderEngine.decodeKind(encoded)

        assertTrue(encoded.startsWith("deferred|"))
        assertTrue(decoded.deferred)
        assertEquals(ReminderKind.TASK_DUE.key, decoded.sourceKind)
        assertEquals("quiet_hours", decoded.deferReason)
    }

    @Test
    fun `deferrals get unique stored alarm codes`() {
        val first = ReminderEngine.alarmRequestCode(9_001)
        val second = ReminderEngine.alarmRequestCode(9_002)
        assertNotEquals(first, second)
        assertNotEquals(ReminderEngine.deferredAlarmRequestCode(9_001, 1_800_000L), first)
    }
}
