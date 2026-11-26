plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    // Windows desktop module (:desktop) only. Versions are declared here so the
    // module build files stay version-free; nothing below is applied to :app.
    //   Kotlin 2.2.10 — same as :app, so both modules share one Kotlin version.
    //   Compose Multiplatform 1.9.3 — first 1.9.x line with AGP 9 + Gradle 9
    //     support (JetBrains PR #5391); based on Jetpack Compose 1.9.4 /
    //     Material3 1.4.0; requires Kotlin 2.1+ and was validated against
    //     Kotlin 2.2.21. Kotlin 2.2.10 (same 2.2 minor) is the conservative
    //     choice so :app does not move; if CI reports a Kotlin/CMP mismatch,
    //     bump Kotlin to 2.2.21 here and in :app together, never per-module.
    //   SQLDelight 2.2.1 — stable 2.2.x line (2026); JVM driver is sqlite-driver.
    //   kotlinx-serialization 1.9.0 — JSON for the sync client.
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
    id("org.jetbrains.compose") version "1.9.3" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("app.cash.sqldelight") version "2.2.1" apply false
}
