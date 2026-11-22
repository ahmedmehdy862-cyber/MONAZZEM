import java.io.FileInputStream
import java.util.Properties

import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The phone store app is signed with the same local release key as MONAZZEM, so both apps can be
// updated from the same feed without shipping any secret in the repository.
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

val defaultUpdateFeedUrl = "https://update-site-sigma.vercel.app/api/latest"
val updateFeedUrl = providers.gradleProperty("focusStudyUpdateFeedUrl").orElse(defaultUpdateFeedUrl).get()
val escapedUpdateFeedUrl = updateFeedUrl.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.focusstudy.store"
    compileSdk = 36

    lint {
        lintConfig = file("../lint.xml")
        abortOnError = true
    }

    defaultConfig {
        applicationId = "com.focusstudy.store"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
