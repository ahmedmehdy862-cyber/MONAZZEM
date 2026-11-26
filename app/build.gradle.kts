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
        versionCode = 43
        versionName = "0.34.0"
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
            // م7: تصغير R8 مفعّل — الزيادة ~10MB (PDFBox + Compose + الأيقونات)
            // لا تُقبل بصمت. قواعد الحفظ في proguard-rules.pro (PDFBox/adhan/
            // الخدمات). بناء سير عمل الإصدار هو البوابة الحقيقية: إن فشل على
            // قاعدة keep فأصلح القاعدة ولا تُعطّل R8.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // Avatar photo → cartoon likeness (phase 1, bundled ML Kit): on-device face
    // detection with the model inside the APK — fully offline, no Play Services,
    // no download. Adds roughly 35 MB to the APK (native libs for 4 ABIs ≈ 33 MB
    // + bundled models ≈ 4.3 MB — measured from the published 0.33.0 APK; the
    // earlier 6.9 MB estimate was wrong). The detector is used once per photo
    // pick (see FaceAnalyzer); when no face is found the old colour-seed cartoon
    // is kept, so nothing regresses.
    implementation("com.google.mlkit:face-detection:16.1.7")
    // Tasks API ‏(play-services-tasks): يعتمد عليها FaceAnalyzer مباشرةً
    // (MlKit.initialize()‎ يرجع Task، وaddOnSuccessListener/addOnFailureListener
    // من هذه المكتبة) — كانت تصل ترانزيتيف فقط فلا تظهر في compile classpath.
    implementation("com.google.android.gms:play-services-tasks:18.4.1")
    // EXIF orientation for camera photos picked for the avatar (see FaceAnalyzer).
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // 3D companion «منظم» (0.34.0): SceneView فوق Filament — العارض التصريحي
    // لـ Compose، خليفة Sceneform المدعوم. الاختيار مقابل Filament الخام:
    // Filament وحده يحتاج ModelViewer يدويًا بلا تكامل Compose ولا إدارة
    // دورة حياة؛ SceneView يعطي SceneView { ModelNode(...) } مع تدمير
    // تلقائي للمحرك والنموذج، وتحميل GLB من assets، وتحكم بالحركات
    // بالاسم (animationName)، وخلفية شفافة (isOpaque=false)، وإيقاف
    // رندر (isRendering) عند عدم الظهور. minSdk 24 في المكتبة ≤ 26 هنا،
    // وبلا ARCore (نستخدم وحدة 3D فقط، لا arsceneview).
    // الإصدار 4.18.0 تحديدًا — وليس 4.73.0 الأحدث — لأن 4.73 يتطلب
    // compileSdk 37 + Kotlin 2.3 + JVM 21 (توثيق SceneView الرسمي)،
    // والمشروع على compileSdk 36 + Kotlin 2.2.10 + JVM 17؛ و4.18.0 هو
    // «أحدث إصدار يُبنى على AGP 8 / compileSdk 36 / Kotlin 2.2» حسب
    // التوثيق نفسه. رفع toolchain المشروع إلى 4.73 خارج نطاق 0.34.0.
    // تكلفة APK تقديرية (تُقاس من assembleRelease): الـ GLB ‏1.31MB +
    // ‏SceneView/Filament بنحو 6–12MB إضافية في APK موحد (natives لأربع
    // ‏ABIs)؛ القياس الحقيقي في دليل التكامل عند أول بناء إصدار.
    implementation("io.github.sceneview:sceneview:4.18.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Robolectric for the DB migration test (DbMigrationV16ToV17Test): the migration
    // is pure SQLite, so no device is needed. Pinned to 4.14.1 (supports the SDK 35
    // runtime the test pins itself to with @Config).
    testImplementation("org.robolectric:robolectric:4.14.1")
}
