import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    // إلزامي منذ Kotlin 2.0: مترجم Compose مطلوب لأي وحدة تستخدم
    // Compose Multiplatform؛ بدونه يفشل تهيئة :desktop بخطأ
    // «Since Kotlin 2.0.0 to use Compose Multiplatform you must apply
    // "org.jetbrains.kotlin.plugin.compose" plugin» — وهذا ما كسر
    // تشغيل إصدار أندرويد 0.34.0 (تهيئة كل المشاريع تتم حتى عند بناء
    // :app وحدها). الإصدار من الجذري (2.2.10، نفس :app).
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.sqldelight")
}

/**
 * نسخة ويندوز من مُنظِّم — Compose Multiplatform لسطح المكتب (JVM).
 *
 * لا يعاد هيكلة :app هنا: كود سطح المكتب مستقل تمامًا ويشارك أندرويد على مستوى
 * البروتوكول فقط (نفس نقاط /api/sync ونفس أشكال السجلات ونفس مخطط جداول
 * SQLite المنسوخ من StudyDb). فصل وحدة pure-Kotlin مشتركة كان يتطلب نقل
 * SyncPayload/SyncMetadata/نماذج StudyDb خارج :app، أي إعادة هيكلة لوحدة
 * أندرويد — مرفوض في هذه المرحلة (انظر desktop/README.md).
 *
 * الإصدارات (مصدر الحقيقة: build.gradle.kts الجذري):
 *   Kotlin 2.2.10، Compose Multiplatform 1.9.3، SQLDelight 2.2.1،
 *   kotlinx-serialization-json 1.9.0، kotlinx-coroutines 1.10.2، JDK 17.
 */

// مصدر واحد للإصدار: نسخة تطبيق أندرويد (app/build.gradle.kts). الـ workflow
// يمرّر -PdesktopVersion / -PdesktopVersionCode لنفس القيمتين بعد قراءتهما
// من الـ Release، فيبقى MSI مطابقًا لإصدار APK المنشور معه.
val appBuildScript = rootProject.file("app/build.gradle.kts").readText()
fun appVersion(pattern: String, property: String, fallback: String): String {
    val fromApp = Regex(pattern).find(appBuildScript)?.groupValues?.getOrNull(1)
    // مصدر الحقيقة هو app/build.gradle.kts دائمًا: خاصية Gradle
    // (-PdesktopVersion=0.34.0) وصلت في run 45752350562 بقيمة "0" لأن سطر
    // أوامر ويندوز يقطّع القيمة المنقّطة (خاصية "0" + مهمة ".34.0" كما
    // أثبت run 45753643685) — فلا يُعتمد على -P للإصدار أبدًا. الخاصية
    // تبقى احتياطيًا فقط إن غاب الإصدار من الملف.
    return fromApp ?: providers.gradleProperty(property).orNull ?: fallback
}
val desktopVersionName = appVersion(
    """versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"""",
    "desktopVersion",
    "0.33.0"
)
val desktopVersionCode = appVersion(
    """versionCode\s*=\s*(\d+)""",
    "desktopVersionCode",
    "41"
)


group = "com.focusstudy.poc"
version = desktopVersionName

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // SQLDelight على JVM: يضم Xerial sqlite-jdbc؛ لا اعتماديات تشغيل أخرى.
    implementation("app.cash.sqldelight:sqlite-driver:2.2.1")
    // JNA: Windows Credential Manager لحفظ كلمة مرور المزامنة في خزنة
    // النظام — بلا خدمات مدفوعة، ومكتبة مجانية. JNA لا يوفّر ربطًا جاهزًا
    // لـ wincred.h، فالكود يعرّف الربط بنفسه (Advapi32Cred/CredentialW)
    // ولا يحتاج إلا jna الأساسي. على Linux لا يُلمس JNA أبدًا
    // (انظر WindowsCredentialStoreFactory).
    implementation("net.java.dev.jna:jna:5.14.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

sqldelight {
    databases {
        create("MonazzemDatabase") {
            packageName.set("com.focusstudy.poc.desktop.db")
            // بلا dialect صريح: الافتراضي SQLite 3.18 يكفي؛ كل SQL في Monazzem.sq
            // أساسي (بلا UPSERT ولا دوال نافذة) ليعمل على هذا الـ dialect.
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.focusstudy.poc.desktop.MainKt"
        nativeDistributions {
            // MSI فقط في v1؛ يُبنى على windows-latest حصرًا (jpackage + WiX).
            targetFormats(TargetFormat.Msi)
            packageName = "com.focusstudy.poc"
            packageVersion = desktopVersionName
            // نصوص MSI لاتينية فقط (ASCII): jpackage يمرّرها إلى WiX،
            // وقاعدة بيانات MSI لا تدعم Unicode (ترميز الصفحة 0 = ASCII
            // فقط) — نص عربي هنا (vendor/description/copyright) كسر
            // jpackage بالكود 1 في run 45756216948 دون أي تفاصيل في السجل.
            // واجهة التطبيق نفسها تبقى عربية؛ هذا يخص مثبّت ويندوز فقط.
            vendor = "Monazzem"
            description = "Organize your tasks, wird, habits, health, money and mood - the Windows version of Monazzem"
            copyright = "(c) Monazzem"
        }
    }
}
