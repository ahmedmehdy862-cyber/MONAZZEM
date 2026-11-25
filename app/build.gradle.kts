import java.io.FileInputStream
import java.util.Properties

import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing material lives outside the project so it is never committed or shared:
//   ~/.focusstudy/keystore.properties  (path, alias, passwords)
//   ~/.focusstudy/release.jks          (the key itself)
// assembleRelease fails fast when this is missing; only local experiments may opt out with
// -PallowUnsignedRelease=true. Never ship an unsigned APK through the release workflow.
val keystorePropertiesFile = File(System.getProperty("user.home"), ".focusstudy/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}
val hasReleaseKey = keystorePropertiesFile.isFile &&
    !keystoreProperties.getProperty("storeFile").isNullOrBlank()
val allowUnsignedRelease = providers.gradleProperty("allowUnsignedRelease")
    .orNull?.equals("true", ignoreCase = true) == true
val assemblingRelease = gradle.startParameter.taskNames.any { name ->
    name == "assembleRelease" || name.substringAfterLast(":") == "assembleRelease"
}
if (assemblingRelease && !hasReleaseKey && !allowUnsignedRelease) {
    throw GradleException(
        "assembleRelease requires a configured release signing key at $keystorePropertiesFile. " +
            "For local experiments only, pass -PallowUnsignedRelease=true."
    )
}
// Defaults to the live feed so a plain `assembleRelease` still receives updates;
// override with -PfocusStudyUpdateFeedUrl=... when testing another feed.
val defaultUpdateFeedUrl = "https://update-site-sigma.vercel.app/api/latest"
val updateFeedUrl = providers.gradleProperty("focusStudyUpdateFeedUrl").orElse(defaultUpdateFeedUrl).get()
val escapedUpdateFeedUrl = updateFeedUrl.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.focusstudy.poc"
    compileSdk = 36

    lint {
        lintConfig = file("../lint.xml")
        abortOnError = true
    }

    defaultConfig {
        applicationId = "com.focusstudy.poc"
        minSdk = 26
        targetSdk = 36
        versionCode = 39
        versionName = "0.31.0"
        buildConfigField("String", "UPDATE_FEED_URL", "\"$escapedUpdateFeedUrl\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = File(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    // Real icons for the bottom dock and the sections directory (BottomTabs.kt):
    // material-icons-core alone does not carry Today/Checklist/CalendarMonth/Book and the
    // other destinations, so the extended set is declared explicitly (BOM-managed version).
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.batoulapps.adhan:adhan:1.2.1")
    // Assistant profile PDF import: PDFBox-Android (com.tom-roush:pdfbox-android:2.0.27.0,
    // verified on Maven Central 2026-11-26; the Android port of Apache PDFBox 2.0.27 —
    // plain PDFBox needs java.awt and cannot run on Android). Used only for on-device
    // text extraction; requires PDFBoxResourceLoader.init(context) before first use
    // (see AssistantPdfImport) and adds roughly 10 MB to the APK.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
