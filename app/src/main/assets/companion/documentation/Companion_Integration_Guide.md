# دليل تكامل رفيق MONAZZEM ثلاثي الأبعاد «منظم»
# MONAZZEM 3D Companion — Integration Guide

**الإصدار:** 0.34.0 (versionCode 43) — «النسخة النهائية»
**التاريخ:** 2026-11-26
**المواصفة:** `~/workspace/user/files/MONAZZEM_3D_Companion_Master_Prompt.md` (§10–§13 و§17 و§20 و§21C)
**الأصول الأصلية:** `~/workspace/monazzem-3d/` (نسخة محفوظة هناك أيضًا)

---

## 1. الأصول — أين كل شيء

| الأصل | المسار في الريبو | الحجم |
|---|---|---|
| النموذج | `app/src/main/assets/companion/model/MONAZZEM_Companion.glb` | 1,375,416 بايت |
| الصورة الثابتة (fallback) | `app/src/main/assets/companion/fallback/companion_static.png` | 724,327 بايت (1024×1024، خلفية شفافة) |
| مصدر Blender القابل للتحرير | `3d-source/MONAZZEM_Character.blend` | 3,378,992 بايت |
| تقرير الإنتاج | `app/src/main/assets/companion/documentation/MONAZZEM_3D_Production_Report.md` | — |
| مرجع الحركات | `app/src/main/assets/companion/documentation/MONAZZEM_Animation_Reference.md` | — |
| هذا الدليل | `app/src/main/assets/companion/documentation/Companion_Integration_Guide.md` | — |

مواصفات النموذج المقاسة من الملف نفسه (مُتحقَّق بقراءة الـ GLB مباشرة):
glTF 2.0، ‏0 أخطاء في gltf-validator؛ 19,796 مثلثًا؛ 11,805 رؤوس؛ rig واحد
بـ 22 مفصلًا؛ 7 خامات PBR بلا textures (ألوان إجرائية)؛ 9 morph targets
(+ Basis)؛ بلا `extensionsRequired` — أي أن gltfio يحمّله كاملًا بلا استثناءات.
الشخصية مبسطة مبنية من primitives برمجيًا (ليست منحوتة) — انظر قيود §7.

### الحركات الخمس (الأسماء كما في جدول `animations` في الـ GLB — لا تغيّرها)

| الاسم | المدة | النوع | قنوات الـ GLB |
|---|---|---|---|
| `IDLE` | 5.0 ث | loop | 66 rotation + ‏1 weights |
| `GREETING` | 2.5 ث | مرة واحدة ← IDLE | 66 rotation + ‏1 weights |
| `ENCOURAGEMENT` | 2.5 ث | مرة واحدة ← IDLE | 66 rotation + ‏1 weights |
| `ACHIEVEMENT` | 3.0 ث | مرة واحدة ← IDLE | 66 rotation + ‏1 weights |
| `THINKING` | 4.0 ث | loop | 66 rotation + ‏1 weights |

قناة `weights` الواحدة في كل حركة هي التي تحرّك الـ morph targets (الابتسامة،
الرمش، تعابير الوجه) — انظر قيد الـ morphs في §7.

---

## 2. المحرك — لماذا SceneView 4.18.0

| الخيار | الحكم |
|---|---|
| **SceneView 4.18.0** (`io.github.sceneview:sceneview:4.18.0`) | ✅ المختار |
| Filament الخام (`com.google.android.filament:filament-android`) | ❌ يحتاج ModelViewer يدويًا، بلا تكامل Compose ولا إدارة دورة حياة ولا تحكم بالحركات بالاسم — كود أكثر لنفس النتيجة |
| SceneView 4.73.0 (الأحدث) | ❌ يتطلب compileSdk 37 + Kotlin 2.3 + JVM 21؛ المشروع على compileSdk 36 + Kotlin 2.2.10 + JVM 17. رفع الـ toolchain خارج نطاق 0.34.0 |

الدليل على اختيار الإصدار: توثيق SceneView الرسمي نفسه يقول إن 4.18.0 هو
«أحدث إصدار يُبنى على AGP 8 / compileSdk 36 / Kotlin 2.2». المشروع على
AGP 9.5.1 وcompileSdk 36 وKotlin 2.2.10 — أي أن 4.18.0 هو أحدث ما يتوافق
مع الـ toolchain الحالية دون ترقية. مسار الترقية لاحقًا (4.73.0): رفع
compileSdk إلى 37 وKotlin إلى 2.3 وJVM إلى 21، ثم تبديل رقم الإصدار فقط —
واجهة 4.x المستخدمة هنا (`SceneView { ModelNode(...) }` و`animationName`)
هي نفسها في 4.73.

الاعتماد في `app/build.gradle.kts` فقط، وحدة 3D فقط (لا `arsceneview` —
بلا ARCore وبلا أي إذن جديد). لا خدمات مدفوعة، لا إنترنت، لا Google Play.

### أثر الـ APK (تقدير — لم يُقَس بعد)

| البند | التقدير |
|---|---|
| الـ GLB نفسه (assets) | +1.31 MB |
| الصورة الثابتة (assets) | +0.69 MB |
| SceneView + Filament (natives لأربع ABIs في APK موحد) | +6–12 MB تقريبًا |
| **الإجمالي التقديري** | **+8–14 MB على APK 0.33.1 (‏66.15 MB)** |

هذا تقدير مبني على أحجام مكتبات Filament المعتادة، **وليس قياسًا**: الرقم
الحقيقي يُؤخذ من `MONAZZEM.apk` الذي يبنيه سير عمل الإصدار (assembleRelease)
ويُدوَّن هنا بعد أول بناء. للمقارنة: ML Kit المضمّنة وحدها أضافت ~35 MB
في 0.33.0، فالزيادة هنا أصغر نسبيًا.

---

## 3. الكود — أين الرفيق وكيف يُتحكم فيه

الملف الوحيد للرفيق: `app/src/main/java/com/focusstudy/poc/Companion3D.kt`
(+ اختباراته `app/src/test/java/com/focusstudy/poc/CompanionControllerTest.kt`).

### 3.1 `CompanionController` — آلة الحالات (§17)

خالصة Kotlin بلا أي اعتماد على أندرويد (الساعة قابلة للحقن للاختبار)،
وتُختبر بـ JUnit عادي. الواجهة كما اقترحتها المواصفة:

```kotlin
controller.playIdle()              // عودة إلى IDLE
controller.greet()                 // GREETING ثم عودة تلقائية إلى IDLE
controller.encourage()             // ENCOURAGEMENT ثم عودة إلى IDLE
controller.celebrateAchievement()  // ACHIEVEMENT ثم عودة إلى IDLE
controller.think()                 // THINKING (loop حتى playIdle)
controller.setEnabled(false)       // DISABLED — بلا رندر وبلا حركات
controller.release()               // DISABLED (تحرير عند نهاية العمر)
```

قواعدها (مُختبرة):
- IDLE افتراضية. الحدث الجديد **يستبدل** الحالي — لا قائمة انتظار أبدًا،
  فلا قوائم حركات غير منضبطة مهما تكرر الحدث.
- طلب الحالة نفسها وهي loop لا يعيد تشغيلها.
- ذات المرة الواحدة تعود إلى IDLE بعد مدتها عبر `returnToIdle(المتوقعة)`؛
  والعودة مشروطة بأن الحالة ما زالت هي نفسها، فلا تُلغى حركة أحدث بعودة متأخرة.
- DISABLED يمنع كل الطلبات؛ `setEnabled(true)` تعيد إلى IDLE.
- حركة مرة واحدة معلّقة (حدث وقع والرفيق غير مرئي) تُعرض إن وصل المستخدم
  إلى الرئيسية خلال 60 ثانية، وتُسقط بصمت بعدها بدل احتفال متأخر بلا سياق
  (`STALE_ONE_SHOT_MILLIS`).

### 3.2 `MONAZZEMCompanionView` — العارض القابل لإعادة الاستخدام (§10)

```kotlin
MONAZZEMCompanionView(
    controller = controller,
    modifier = Modifier.size(148.dp),
    enabled = preferences.companion3dEnabled,
    animationsEnabled = preferences.companion3dAnimationsEnabled && !preferences.reduceMotion,
    onLoadFailed = { /* اختياري: يُستدعى مرة واحدة عند فشل التحميل */ }
)
```

- **خلفية شفافة** (`isOpaque = false`) فيندمج مع البطاقة فاتحًا وداكنًا دون
  أي لون خلفية hard-coded.
- **الحجم والموضع من الـ `modifier`**؛ الشخصية تُقاس إلى مكعب 1م
  (`scaleToUnits = 1f`) وقاعدتها على أصل العقدة (`centerOrigin = Position(y = -1f)`).
- **بلا إيماءات مدار/تقريب** (`cameraManipulator = null`) حتى لا يسرق العارض
  التمرير من العمود الذي يعيش فيه.
- **الحركات تصريحية**: `animationName`/`animationLoop` على `ModelNode`
  (واجهة SceneView 4.x الموثقة) — تغيير الحالة يوقف السابقة ويبدأ الجديدة،
  بلا استدعاءات أمرية وبلا كتابة حالة أثناء التركيب.
- **دورة الحياة**: SceneView يوقف الرندر مع دورة حياة الشاشة ويدمّر المحرك
  والنموذج عند مغادرة التركيب؛ وفوق ذلك يُوقف الرندر (`isRendering`)
  عندما تخرج المساحة من الشاشة المرئية أثناء التمرير (فحص `boundsInWindow`
  مقابل ارتفاع الشاشة). التحميل عبر `rememberModelLoadState` فيفرّق بين
  «جارٍ التحميل» (صورة ثابتة مؤقتة) و«فشل» (صورة ثابتة + `onLoadFailed`).
- **عند التعطيل** لا يُركَّب شيء أصلًا. **عند إيقاف الحركات** تُعرض الصورة
  الثابتة بدل العارض (احترامًا لتقليل الحركة ولإعداد الحركات).

### 3.3 أين يعيش الرفيق (التكامل)

| المكان | الربط | الملف |
|---|---|---|
| **الرئيسية** (تبويب «اليوم») | `CompanionHomeSlot` — بطاقة بارتفاع ثابت 168dp في عمود التمرير نفسه، بعد بطاقة المساعد وقبل بطاقة البطل؛ يمينها النموذج (148dp) وبجانبه نص وزر «افتح المحادثة»؛ البطاقة كلها قابلة للضغط وتفتح المحادثة | `MainActivity.kt` (`TasksScreen`, `dashboard == true`) |
| **فتح التطبيق** | `GREETING` مرة واحدة عند أول تركيب لـ `AppRoot` | `MainActivity.kt` |
| **إكمال مهمة** | `ACHIEVEMENT` من: تبديل المهمة إلى «منجزة» في خطة اليوم وفي قائمة المهام، ومن إكمال المساعد لمهمة | `MainActivity.kt` (`TasksScreen.onToggleDone` في الموضعين + `onAssistantCompleteTask`) |
| **فتح تذكير** | `ENCOURAGEMENT` عند فتح التطبيق من تذكير (بينما التذكير نفسه يفتح تبويب المهام كالمعتاد) | `MainActivity.kt` (`LaunchedEffect(tasksRequest)`) |
| **الإعدادات** | بطاقة «رفيقك منظم (ثلاثي الأبعاد)» في «التخصيص»: مفتاحا إظهار الرفيق وحركاته | `SettingsSupportScreens.kt` (`PersonalizationScreen`) + `FocusPreferences.kt` (`companion_3d_enabled`, `companion_3d_animations_enabled`) |
| **الإشعارات** | الأيقونة الكبيرة: صورة المستخدم أولًا إن وُجدت، وإلا الصورة الثابتة للرفيق | `DailyReminderReceiver.kt` (`StudyNotificationScheduler.post`) عبر `CompanionNotificationIcon.decode()` |

**ما لم يتغيّر:** محتوى التذكيرات وجدولتها وأذوناتها كما هي. إشعار النظام
لا يعرض نموذجًا ثلاثي الأبعاد متحركًا — الأيقونة الكبيرة صورة ثابتة فقط،
والرفيق المتحرك يظهر داخل التطبيق عند فتح التذكير (§13). لا ندّعي غير ذلك.

---

## 4. قاعدة عدم الحجب (§11) — كيف تُحترم هنا

1. **مساحة مخصصة فقط**: الرفيق بطاقة في عمود التمرير، يشارك فيها التخطيط
   كأي بطاقة. لا overlay، لا موضع مطلق، لا تغطية لمهام أو أزرار أو شريط تنقل.
2. **عند التعارض يختفي هو**: عند التعطيل لا تُركَّب البطاقة أصلًا فيستعيد
   التخطيط مساحتها؛ ولا يُصغَّر أي عنصر واجهة أو نص لإفساح مكان له.
3. **الحركة محصورة**: تأطير الكاميرا وحجم النموذج داخل حدود البطاقة،
   والبطاقة (Card) تقصّ محتواها على شكلها.
4. **الشاشات الكثيفة بلا رفيق**: لا يظهر في «مهامي» (القائمة الكاملة) ولا
   في النماذج والتقويم والجلسات — الرئيسية فقط، حيث توجد مساحة مخصصة.
5. **تعطيله لا يكسر شيئًا**: كل مسارات إكمال المهام والتذكيرات والإعدادات
   تعمل كاملة والرفيق معطّل أو فاشل التحميل — المتحكم مجرد مراقِب للحالة،
   والـ fallback صورة ثابتة، والتطبيق لا يعتمد على العارض في أي تدفق.

### اختبارات القبول (§11.6) — الحالة

- **تم ستاتيكيًا فقط** (لا Java/Android SDK على جهاز التكامل): مراجعة
  التخطيط كودًا — البطاقة في-flow، بلا overlay، وكل الأزرار والنصوص
  والمهام في عناصر تخطيط مستقلة فوقها وتحتها، لا تحتها.
- **لم تُنفَّذ بعد على جهاز/محاكي** (بوابة لاحقة قبل النشر): لقطة
  الرئيسية مع الرفيق ظاهرًا والتحقق من كل نص وزر، والتمرير، وفتح لوحة
  المفاتيح، والشاشات الصغيرة، والفاتح/الداكن، وتكبير الخط، والرفيق معطّل.
  شاشة تفشل إن غطى الرفيق أي عنصر — يُصلح التخطيط قبل اعتمادها.

---

## 5. قواعد R8 — درس تعطل 0.33.0

في 0.33.0 تعطّل التطبيق عند الإطلاق لأن ML Kit يكتشف مكوناته بالـ reflection
(`ComponentDiscovery` يحمّل المسجّلات بالاسم) وR8 جرّدها. أي مكتبة جديدة
فيها اكتشاف بالاسم يجب تغطيتها بقاعدة keep صريحة — لا تُعطَّل R8 أبدًا.

في `app/proguard-rules.pro` (قسم «SceneView 3D companion (0.34.0)»):

```proguard
-keep class io.github.sceneview.** { *; }
-keep class com.google.android.filament.** { *; }
-keep class com.google.android.filament.gltfio.** { *; }
-keep class com.google.android.filament.utils.** { *; }
-dontwarn io.github.sceneview.**
-dontwarn com.google.android.filament.**
```

SceneView يشحن consumer rules خاصة به؛ هذه القواعد تأمين صريح فوقها،
بنفس منطق keeps الخاصة بـ ML Kit وPDFBox. إن فشل بناء الإصدار على قاعدة
keep فأصلح القاعدة ولا تُعطّل R8 (نفس قاعدة المشروع منذ م7).

---

## 6. الـ fallback

| الحالة | ما يراه المستخدم |
|---|---|
| الرفيق مفعّل والحركات مفعّلة والنموذج حُمّل | النموذج ثلاثي الأبعاد المتحرك |
| جارٍ تحميل النموذج | الصورة الثابتة مؤقتًا |
| فشل تحميل النموذج (ملف تالف/عارض غير مدعوم) | الصورة الثابتة + تحذير في logcat (`Companion3D`) + `onLoadFailed` إن وُجد |
| الحركات موقوفة (من الإعدادات أو «تقليل الحركة») | الصورة الثابتة |
| الرفيق معطّل | لا شيء — البطاقة نفسها غير موجودة |

الصورة الثابتة تُفك من assets بعينة مناسبة للحجم
(`CompanionNotificationIcon.decode(context, maxPx)`، حد الإشعار 128px
مثل `NotificationAvatar`)، فلا تُفك 1024×1024 كاملة في مسار تنبيه.

---

## 7. القيود — بصراحة (قاعدة 11 في المواصفة)

1. **الـ morphs لم تُشاهَد في عارض real-time بعد.** قناة `weights` موجودة
   في كل حركة في الـ GLB (مُتحقَّق عدديًا)، ومحرّك gltfio في Filament يطبّق
   قنوات الـ weights على الـ morph targets — أي أن الابتسامة والرمش
   **يُتوقع** أن يعملا. لكن لم يُرَ ذلك بعين في SceneView/Filament على
   جهاز أو محاكي (لا SDK هنا). أول فحص بصري يجب أن يؤكد صراحةً: الرمش
   في IDLE، والابتسامة الكاملة في GREETING/ACHIEVEMENT. إن لم تظهر
   التعابير، يُوثَّق ذلك كقيد ويُفحص `setMorphWeights` على العقدة —
   ولا يُدَّعى عملها قبل رؤيتها.
2. **تأطير الكاميرا افتراضي.** لم تُضبط `cameraNode` يدويًا؛ أول فحص
   بصري يؤكد أن الشخصية كاملة (رأسًا لقدمين) داخل مساحة 148dp. إن قصّ
   التأطير الرأس أو القدمين، يُضبط في `MONAZZEMCompanionView` فقط
   (موضع/إسقاط الكاميرا أو `scaleToUnits`) — لا يُغيَّر التخطيط حوله.
3. **نقاط واجهة SceneView 4.18.0 يجب أن يؤكدها بناء CI.** الكود يستخدم
   واجهة 4.x الموثقة (`SceneView`, `rememberEngine`, `rememberModelLoader`,
   `rememberModelLoadState`, `ModelNode` مع `animationName`/`animationLoop`/
   `scaleToUnits`/`centerOrigin`، و`isOpaque`/`isRendering`/
   `cameraManipulator = null`)؛ لكن التوثيق المقروء كان لإصدار 4.73.0،
   والأرشيف المرجعي لـ 4.18.0 لم يكن متاحًا للفحص هنا. إن رفض CI أي
   معامل (الأرجح `animationName`/`animationLoop` إن كانا أحدث من 4.18)،
   فالبديل الجاهز: الإمساك بالعقدة عبر `apply = { ... }` واستدعاء
   `playAnimation(name, speed, loop)` الأمرية — وهي واجهة أقدم وأثبت.
   لا يُرفع الإصدار إلى 4.73 دون ترقية الـ toolchain أولًا (§2).
4. **حجم الـ APK تقدير** (§2) — يُقاس من أول `assembleRelease` ويُدوَّن هنا.
5. **الشخصية مبسطة** (من تقرير الإنتاج): بلا أذنين، أصابع مبسطة، الشعر
   غطاء يقرأ كقبعة من بعض الزوايا، كرات الكتف ثابتة عند رفع الذراع،
   وبلا textures (ألوان PBR إجرائية). مقبول stylized، وليست شخصية نحتية.
6. **الـ loop لم يُشاهَد بصريًا في عارض real-time** — التطابق العددي
   للبداية/النهاية مُتحقَّق في الـ GLB، والمشاهدة المستمرة معلّقة على
   أول فحص بصري.
7. **احتفال إكمال المهمة من تبويب «مهامي» معلّق** حتى زيارة الرئيسية
   خلال دقيقة (§3.1)؛ بعدها يُسقط بصمت. هذا تصميم مقصود لا خلل —
   الرفيق لا يظهر في القائمة الكثيفة (§11.2).

---

## 8. الصيانة

- **استبدال النموذج**: ضع `MONAZZEM_Companion.glb` الجديد في
  `app/src/main/assets/companion/model/` **بالأسماء نفسها للحركات الخمس**
  (`IDLE`/`GREETING`/`ENCOURAGEMENT`/`ACHIEVEMENT`/`THINKING`) — الكود
  يخاطبها بالاسم، فأي اسم مختلف يكسر الحركة بصمت (تبقى IDLE). تحقق بـ:
  `python3 -c` قراءة جدول `animations` من الـ GLB، أو
  `scripts/verify_glb.py` في `~/workspace/monazzem-3d/`.
- **تعديل الشخصية**: عدّل `3d-source/MONAZZEM_Character.blend` (Blender 4.0)،
  وأعد التصدير بنفس إعدادات سكربت البناء
  (`~/workspace/monazzem-3d/scripts/build_character.py`)، ثم أعد التحقق
  بـ gltf-validator قبل النسخ إلى assets.
- **رفع إصدار SceneView**: انظر §2 (ترقية toolchain أولًا).
- **لا ترسل أي بيانات**: النموذج والصورة محليان؛ الرفيق لا يرفع صورًا
  ولا بيانات ولا تحليلات (§18).
