package com.focusstudy.store

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal data class InstalledAppVersion(
    val versionCode: Long,
    val versionName: String,
)

/** Reads only the versions of packages we publish; no device-wide app inventory is collected. */
internal fun installedVersion(context: Context, packageName: String): InstalledAppVersion? = try {
    val info = context.packageManager.getPackageInfo(packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    InstalledAppVersion(code, info.versionName.orEmpty())
} catch (error: PackageManager.NameNotFoundException) {
    null
}
