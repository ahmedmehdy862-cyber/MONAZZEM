package com.focusstudy.store

import org.junit.Assert.assertEquals
import org.junit.Test

class InstallStatusTest {
    @Test
    fun missingPackageIsNotInstalled() {
        assertEquals(InstallStatus.NOT_INSTALLED, installStatus(null, 1))
        assertEquals("غير مثبّت على الهاتف", statusLabel(InstallStatus.NOT_INSTALLED))
    }

    @Test
    fun lowerInstalledVersionNeedsAnUpdate() {
        assertEquals(InstallStatus.UPDATE_AVAILABLE, installStatus(36, 37))
        assertEquals("يوجد تحديث", statusLabel(InstallStatus.UPDATE_AVAILABLE))
    }

    @Test
    fun equalOrNewerInstalledVersionIsUpToDate() {
        assertEquals(InstallStatus.UP_TO_DATE, installStatus(37, 37))
        assertEquals(InstallStatus.UP_TO_DATE, installStatus(38, 37))
        assertEquals("محدَّث", statusLabel(InstallStatus.UP_TO_DATE))
    }
}
