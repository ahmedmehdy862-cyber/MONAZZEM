# مُنظِّم — نسخة ويندوز (سطح المكتب)

تطبيق JVM بـ Compose Multiplatform: نفس بيانات الهاتف (مهام، ورد، عادات،
صحة، مال، مزاج) مع مزامنة ثنائية الاتجاه عبر `/api/sync`، ومؤقت تركيز
**بلا حجب تطبيقات** — الحجب في أندرويد يعتمد على Accessibility وVPN، ولا
مقابل لهما على سطح المكتب، وتوضح الواجهة ذلك صراحةً.

## التشغيل محليًا (ويندوز + JDK 17)

```bat
.\gradlew.bat :desktop:run
```

أول تشغيل يفتح شاشة ربط حساب المزامنة (profileId + كلمة المرور). يمكن
أيضًا «استخدام دون مزامنة» — البيانات تبقى على هذا الجهاز فقط.

## البنية

```
desktop/
  build.gradle.kts            # compose.desktop.application + MSI
  src/main/sqldelight/.../Monazzem.sq   # مخطط StudyDb كاملًا (v1..v17) + استعلامات v1
  src/main/kotlin/com/focusstudy/poc/desktop/
    Main.kt        # نافذة RTL بعنوان «مُنظِّم»
    Theme.kt       # كحلي #142B45 / تركوازي #16B8A6، فاتح/داكن
    App.kt         # الشاشات السبع + شاشة الدخول + زر «مزامنة الآن»
    Models.kt      # نماذج مبسطة بنفس مفاتيح أندرويد
    Database.kt    # فتح قاعدة SQLite + مستودع فوق استعلامات SQLDelight
    SyncClient.kt  # HttpClient + بناء الحمولة + تطبيق pull + دورة syncNow
    SyncStore.kt   # profileId في ملف محلي؛ كلمة المرور في الذاكرة فقط
    FocusTimer.kt  # عدّ تنازلي + تذكير؛ بلا حجب
```

### قرار المشاركة: بروتوكول لا وحدة مشتركة

فصل وحدة pure-Kotlin مشتركة كان يتطلب نقل `SyncPayload` و`SyncMetadata`
ونماذج `StudyDb` خارج `:app` — إعادة هيكلة لوحدة أندرويد، مرفوضة في هذه
المرحلة. كود ويندوز إذن مستقل، ويشارك أندرويد على مستوى البروتوكول فقط:
نفس نقطة `/api/sync`، ونفس أفعال register/pull/push، ونفس أشكال السجلات
والقائمة البيضاء، ونفس مخطط جداول SQLite منسوخًا من `StudyDb.kt`.
أي جدول جديد في أندرويد يجب أن يُضاف في `Monazzem.sq` معه.

### الإصدارات (مصدر الحقيقة: `build.gradle.kts` الجذري)

| المكوّن | الإصدار | لماذا |
|---|---|---|
| Kotlin | 2.2.10 | نفس `:app` — إصدار واحد للمشروع كله |
| Compose Multiplatform (`org.jetbrains.compose`) | 1.9.3 | أول 1.9.x بدعم AGP 9 وGradle 9 (PR #5391)؛ مبني على Jetpack Compose 1.9.4 / Material3 1.4.0؛ يتطلب Kotlin 2.1+ وقد وُثّق مع Kotlin 2.2.21 — و2.2.10 من نفس الفرع 2.2. إن أبلغ CI عن عدم تطابق Kotlin/CMP، ارفع Kotlin إلى 2.2.21 هنا وفي `:app` معًا، لا لكل وحدة وحدها |
| SQLDelight | 2.2.1 | خط 2.2.x المستقر (2026)؛ محرك JVM هو `sqlite-driver` (يضم Xerial sqlite-jdbc) |
| kotlinx-serialization-json | 1.9.0 | JSON لعميل المزامنة |
| kotlinx-coroutines | 1.10.2 | المؤقت والمزامنة في الخلفية |
| JDK في CI | 17 Temurin | jpackage يتطلب 17+؛ وMSI يتطلب WiX على Runner |
| Gradle | 9.6.0 | غلاف الريبو نفسه |

إصدار التطبيق (`packageVersion` في MSI) يُقرأ من `app/build.gradle.kts`
(`versionName`/`versionCode`)، أو يُمرَّر من الـ workflow
(`-PdesktopVersion` / `-PdesktopVersionCode`) — فيبقى MSI مطابقًا لإصدار
APK المنشور معه.

## المزامنة

مصدر الحقيقة: `~/workspace/windows-sync-contract.md` (المجموعات العشر،
أشكال السجلات، قاعدة الدمج §5، معرّفات ويندوز §7). عميل ويندوز مرآة
`SyncAccount`/`SyncPayload`/`SyncApply` في `:app`، حتى في اصطلاح البصمة
القانونية (حقول مرتبة بالمفتاح، وأرقام تُطبَّع) وقواعد تطبيق pull.

- النقطة: `POST https://update-site-sigma.vercel.app/api/sync`
  (نفس `TeamMessages.BASE` في أندرويد)، بأفعال `register` / `pull` / `push`.
- المصادقة: `profileId` (UUID يصدره الخادم) + كلمة مرور 10..200 حرفًا
  (تُرسل في جسم الطلب عبر HTTPS فقط، وتُجزَّأ scrypt على الخادم).
- كلمة المرور **لا تُحفظ أبدًا** — مطابقةً لأندرويد؛ تُكتب عند كل تشغيل
  وتبقى في الذاكرة فقط. يُحفظ `profileId` وحده في
  `%APPDATA%\Monazzem\sync.properties`.
- قيد موثّق: الملف المحلي ليس مخزن نظام مشفّرًا (لا DPAPI/Credential
  Manager في v1 بلا اعتماديات)؛ لكنه لا يحوي سرًا — `profileId` وحده لا
  يكفي للمصادقة دون كلمة المرور.
- المجموعات العشر: `tasks` و`subjects` و`taskTypes` و`habits` و`habitLogs`
  و`wirdLogs` و`healthLogs` و`transactions` و`moodLogs` و`calorieLogs`.
  بصمة لكل سجل، وطابع زمني ثابت ما دام السجل لم يتغير، وشواهد حذف
  (tombstones)، ودمج last-write-wins لكل سجل، وحفظ البصمات بعد قبول
  الخادم للـ push فقط. الملاحظات («نوتة») وملاحظات المهام وملاحظة
  المعاملة المالية لا تعبر السلك أبدًا.
- دورة «مزامنة الآن»: pull ← تخطيط (`SyncApply.plan`) وتطبيق على القاعدة
  المحلية ← push. سجل محلي بلا ختم سابق لا يُكتب فوقه pull أبدًا
  (العقد §5): إن طابق الوارد محتواه اعتُمد الختم بلا كتابة، وإن اختلف
  بقي المحلي وفاز في الدفع التالي بختم جديد.
- **معرّفات ويندوز (§7):** كل سجل رقمي ينشئه ويندوز (مهمة، عادة، معاملة،
  سعرات) يأخذ معرّفًا سالبًا (`-epochMillis`، متناقص باطراد عبر
  `WindowsIds`) حتى لا يصطدم أبدًا بمعرّف AUTOINCREMENT موجب ينشئه
  أندرويد. معرّف `habitLogs` مشتق (`habitId:day`) فلا يصطدم بطبيعته،
  ومعرّفات السجلات اليومية هي `day` نفسه. معرّفات `taskTypes` النصية
  الجديدة تبدأ بـ `w_` إن أُنشئت.
- **محلي فقط (العقد §6):** `wird_settings` و`fitness_profile` و
  `exercise_logs` والجلسات والأحداث والتذكيرات وسجل الإشعارات وبيانات
  الدعم واسم المستخدم — لا تعبر السلك في أي اتجاه.

## النشر (MSI)

`.github/workflows/windows-desktop-release.yml` — يُشغَّل عند نشر GitHub
Release لوسم `focus-v*` (أي بعد أن يبني workflow أندرويد الـ APK وينشئ
الـ Release):

1. يقرأ `versionCode`/`versionName` من `app/build.gradle.kts` ويتحقق من
   مطابقة الوسم، ويقرأ `apkUrl` و`releaseNotes` من الـ Release عبر `gh api`.
2. يبني MSI: `.\gradlew.bat :desktop:packageMsi` على `windows-latest`
   (JDK 17 Temurin + WiX Toolset).
3. يرفع `MONAZZEM-Setup-<version>.msi` إلى الـ Release نفسه
   (`gh release upload --clobber`). لا ينشئ Release ولا وسمًا.
4. ينشر على `POST /api/admin/publish-ci` حمولة الإصدار الكاملة
   (versionCode/versionName/apkUrl/releaseNotes) مع
   `windows: {downloadUrl, version, sizeBytes}`.
5. يتحقق أن `/api/latest` يعرض `windows` لنفس الإصدار.

### دعم الخادم لحقل `windows` — موجود في أساس هذا الفرع

دعم `windows` في `publish-ci` و`release-store` و`latest` هو جزء من أساس
هذا الفرع نفسه (commit `901ddd7` «مزامنة «حياتي» ومدخل ويندوز في الخادم»):
`publish-ci` يقبل `windows` الاختياري مع تحقق صارم (رابط https ينتهي
بـ `.msi`، وإصدار `x.y.z`، و`sizeBytes` ≤ 2 GiB)، و`decideCiPublish`
يعامل أي تغيير في `windows` تغييرًا يستدعي الكتابة فوق نفس `versionCode`،
و`/api/latest` يعيده في المستوى الأعلى وداخل `app`. بدمج هذا الفرع في
`main` يُنشر الدعم مع موقع التحديثات تلقائيًا (تكامل Vercel مع الريبو)،
فخطوة التحقق في الـ workflow يجب أن تمر على الوسم التالي. إن فشلت رغم
ذلك، فالسبب الأرجح أن نشر Vercel لم يلتقط آخر `main` بعد — أعد التحقق
من حالة النشر قبل أي تغيير في الـ workflow.

## بوابات قبل النشر

### CI (تشغيل الـ workflow على الوسم التالي)

1. بعد دمج هذا الفرع ونشر الوسم التالي (`focus-v*`)، راقب تشغيل
   `windows-desktop-release.yml`: يجب أن ينجح البناء ورفع MSI وظهور
   `windows` في `https://update-site-sigma.vercel.app/api/latest`.
2. إن فشل البناء على عدم تطابق Kotlin/CMP، ارفع Kotlin إلى 2.2.21
   (انظر جدول الإصدارات أعلاه).
3. إن فشل `packageMsi` على WiX، تحقق من خطوة WiX في الـ workflow
   (التثبيت الاحتياطي عبر `choco install wixtoolset`).

### الجهاز (بعد نجاح CI)

1. ثبّت الـ MSI على ويندوز نظيف (بلا JDK): يجب أن يعمل التطبيق مباشرةً
   (JVM مضمّنة في الحزمة عبر jpackage).
2. اربط حساب المزامنة نفسه في الهاتف، واضغط «مزامنة الآن» في الاتجاهين:
   مهمة تُضاف على ويندوز تظهر على الهاتف، ومهمة تُضاف على الهاتف تظهر
   على ويندوز، والحذف والإكمال ينتقلان أيضًا — وكرر الاختبار نفسه لعادات
   اليوم وورد اليوم والوزن/السعرات ومعاملة مال ومزاج (المجموعات العشر كلها
   في العقد). تحقق أيضًا أن حذف إنجاز عادة على جهاز يلغيها على الآخر
   (تومبستون)، وأن ملاحظة معاملة مالية تبقى على جهازها ولا تعبر أبدًا.
3. تحقق من فاتح/داكن على كل شاشة، ومن بقاء كلمة المرور غير محفوظة بعد
   إعادة التشغيل (تُطلب من جديد)، ومن أن المؤقت يعمل دون أي حجب.
