# المرحلة 7 — تطبيق متجر مُنظِّم على الهاتف

> الفرع: `phase7-store-app`. الاسم الظاهر «مُنظِّم» و«متجر مُنظِّم»؛ المعرّفات التقنية (`focus-study`, `com.focusstudy.poc`, `com.focusstudy.store`, `focus-study/*`) لم تتغيّر.

## الهدف

تطبيق أندرويد يُنزَّل من صفحة التحديثات، يقرأ نفس موجز الموقع `/api/latest`، ويعرف تطبيقات مُنظِّم المثبّتة على الهاتف وهل تحتاج تحديثًا أم لا.

## الشريحة ١ — كتالوج التطبيقات في الموجز (محلي)

### ما تغيّر

- `update-site/lib/app-catalog.js` (جديد): يبني `apps` من سجل الإصدار الحالي وسجل الإصدارات، ويضيف تطبيق المتجر `com.focusstudy.store` من `store-app-release.json` أو من سجل Blob عام اختياري `focus-study/store-app.json`.
- `/api/latest` يُعيد حقل `apps` إضافيًا بجانب الحقول الحالية؛ العملاء القدامى يتجاهلونه، ولا تُضاف أي دالة Vercel جديدة (الحد ١٢ ما زال محترمًا).
- حقول الكتالوج مصفّاة، والروابط `https` فقط، وفشل قراءة الكتالوج لا يخفي الإصدار الحالي.
- صفحة التحديثات تعرض قسم «تطبيقات مُنظِّم على الهاتف» باسم كل تطبيق وإصداره ورابط المتجر ورابط التنزيل، مع بناء العناصر عبر `textContent`.

### التحقق

- `npm run test:catalog`: ٥/٥.
- `npm run test:history`: ٦/٦ (لا انحدار بعد إضافة mock للكتالوج).
- `npm run check` + `npm run build` + `node --check`: ناجحة.
## الشريحة ٢ — تطبيق أندرويد للمتجر

### ما تغيّر

- وحدة Gradle جديدة `:storeapp` بالحزمة `com.focusstudy.store` والاسم الظاهر «متجر مُنظِّم»، بنفس مفتاح التوقيع المحلي وبنفس مصدر الموجز `https://update-site-sigma.vercel.app/api/latest`.
- `StoreFeedRepository.kt` يقرأ `apps` من الموجز (أو سجل الإصدار الحالي كاحتياطي)، ويقبل روابط `https` فقط ويحدّ حجم الاستجابة.
- `InstalledApps.kt` يسأل فقط عن الحزمتين اللتين ننشرهما (`com.focusstudy.poc` وحزمة التطبيق نفسه ضمنيًا) عبر `<queries>`؛ لا يوجد `QUERY_ALL_PACKAGES` ولا جرد عام للجهاز.
- `InstallStatus.kt` يقرر: «غير مثبّت» أو «يوجد تحديث» أو «محدَّث» بمقارنة `versionCode` المثبّت بالمنشور.
- الواجهة تعرض بطاقة لكل تطبيق مع الإصدار والحالة والوصف وزرّي «فتح المتجر» و«تنزيل APK»، مع زر تحديث للقائمة.

### التحقق

- `storeapp:testDebugUnitTest`: ٣/٣.
- `storeapp:lintDebug`: نظيف.
- `storeapp:assembleRelease`: ناجح؛ الحزمة `com.focusstudy.store` و`versionCode=1` و`versionName=0.1.0` والاسم «متجر مُنظِّم».
- بصمة شهادة APK: `f1dd1b6a24e25d3a5b7e7747df45c9e72dd2bec6ac38e89aba55dd58a2941401` — نفس شهادة مُنظِّم، فالتحديث فوق النسخة الحالية ممكن.

## الشريحة ٣ — التنزيل من صفحة التحديثات

- ملف `update-site/public/downloads/focus-store-0.1.0.apk` هو نسخة من APK الموقّع، ويُقدَّم كرابط تنزيل من بطاقة «متجر مُنظِّم» في صفحة التحديثات ومن حقل `apps.apkUrl` في الموجز.
- لا تُلمس صفحة QUBIXStore من هذا المستودع؛ صفحة التحديثات هي نقطة التنزيل المملوكة لنا، و`/api/latest` هو المصدر الوحيد للبيانات.

## التحقق الحي

- النشر: `dpl_3wDwjRWnhhYYyTNF72sbjrTAUnjH` (production/READY) على `update-site-sigma.vercel.app`.
- `/api/latest` يُعيد `apps` فيه تطبيقان: `com.focusstudy.poc / 0.29.0 / 37` و`com.focusstudy.store / 0.1.0 / 1`، ولكل منهما سجل إصدار واحد.
- رابط `https://update-site-sigma.vercel.app/downloads/focus-store-0.1.0.apk` يعيد 200 مع `Content-Type: application/vnd.android.package-archive` وطول ٧٤٨١٤٦٩ بايت.
- المتصفح يعرض قسم «تطبيقات مُنظِّم على الهاتف» ببطاقتي التطبيقين ورابط التنزيل، بلا أخطاء console.
- التثبيت والتشغيل على جهاز فعلي ما زال `Not Verified` لأن الجهاز غير متصل (`adb devices` فارغ).
