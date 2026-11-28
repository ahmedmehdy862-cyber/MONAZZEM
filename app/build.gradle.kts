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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 44
        versionName = "0.35.0"
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

    // اختبارات الوحدة (Robolectric) تحتاج موارد التطبيق وأصوله المضمّنة
    // (نص المصحف في assets/quran) — بدونها يعود AssetManager فارغًا
    // فيفشل تحميل الفهرس (FileNotFoundException) وتسقط كل اختبارات
    // QuranDataTest/QuranZoomTest في setUp. الافتراضي معطّل.
    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    // EXIF orientation for camera photos picked for the avatar (used directly
    // via ExifInterface in copyAvatarToAppFiles — no face detection anymore).
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // P-04 (see SecurePrefs): EncryptedSharedPreferences للتفضيلات الحساسة
    // (معرّفات الهوية أولًا) — AES256-SIV للمفاتيح + AES256-GCM للقيم،
    // والمفتاح الرئيسي في Android Keystore. مجاني، بلا خدمة، بلا إذن جديد.
    implementation("androidx.security:security-crypto:1.0.0")
    // P-04 — SQLCipher لقاعدة البيانات أثناء السكون (ADR_002 «تم الاعتماد»،
    // فرع sqlcipher-at-rest): تشفير AES-256 كامل لملف focus_study.db.
    // 4.5.4 هو أحدث إصدار من هذه الحزمة على Maven Central (تحقق 2026-11-28)؛
    // لا يوجد 4.6.x لهذه الحزمة (4.6.x أرقام نواة SQLCipher فقط)، والحزمة
    // نفسها مهملة رسميًا لصالح sqlcipher-android — ترحيل مستقبلي موثق في
    // ADR_002، لا في هذا الإصدار. المفتاح عشوائي في Keystore عبر
    // DatabaseKeyManager؛ لا PIN ولا مفتاح في الكود.
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    // androidx.sqlite:sqlite is a *runtime-only* transitive dependency of
    // android-database-sqlcipher 4.5.4 (its POM declares scope=runtime), so
    // the compiler cannot see SupportSQLiteDatabase — the supertype of
    // net.sqlcipher.database.SQLiteDatabase that SQLCipher 4.5.x implements.
    // Without this explicit declaration every use of the SQLCipher database
    // fails with "Cannot access 'SupportSQLiteDatabase'...". Version 2.2.0
    // is exactly the version SQLCipher 4.5.4 was built against (its POM).
    implementation("androidx.sqlite:sqlite:2.2.0")
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
    // Emulator verification for the adhan alert (AdhanAlertQaTest): the full-screen
    // alert with the one-tap stop button, and the sound actually stopping on tap.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
