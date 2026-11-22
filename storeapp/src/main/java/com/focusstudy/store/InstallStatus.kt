package com.focusstudy.store

/** Pure comparison logic: no Android or network dependency, so it is unit-testable. */
internal enum class InstallStatus {
    NOT_INSTALLED,
    UPDATE_AVAILABLE,
    UP_TO_DATE,
}

internal fun installStatus(installedVersionCode: Long?, publishedVersionCode: Long): InstallStatus = when {
    installedVersionCode == null -> InstallStatus.NOT_INSTALLED
    installedVersionCode < publishedVersionCode -> InstallStatus.UPDATE_AVAILABLE
    else -> InstallStatus.UP_TO_DATE
}

internal fun statusLabel(status: InstallStatus): String = when (status) {
    InstallStatus.NOT_INSTALLED -> "غير مثبّت على الهاتف"
    InstallStatus.UPDATE_AVAILABLE -> "يوجد تحديث"
    InstallStatus.UP_TO_DATE -> "محدَّث"
}
