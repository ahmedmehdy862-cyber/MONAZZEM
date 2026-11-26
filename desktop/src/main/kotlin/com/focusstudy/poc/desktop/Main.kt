package com.focusstudy.poc.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * نقطة دخول نسخة ويندوز: نافذة سطح مكتب عربية RTL بعنوان «مُنظِّم».
 *
 * لا Android SDK ولا أي كود أندرويد هنا — JVM خالص (Compose Multiplatform).
 * قاعدة البيانات المحلية في %APPDATA%\Monazzem\monazzem.db.
 */
fun main() = application {
    val repository = remember { DesktopRepository(DesktopDatabase.open(SyncStore.dataDir())) }
    val appState = remember { DesktopAppState(repository) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "مُنظِّم",
        state = rememberWindowState(width = 1100.dp, height = 760.dp),
    ) {
        // الواجهة كلها RTL حتى مع لغة نظام غير عربية.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            MonazzemDesktopTheme(dark = appState.dark) {
                App(appState)
            }
        }
    }
}
