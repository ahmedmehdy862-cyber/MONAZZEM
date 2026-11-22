# تقرير التدقيق الشامل — MONAZZEM / مُنظِّم

> **مرحلة التدقيق فقط (Audit Only).** هذا التقرير هو التغيير الوحيد المسموح به في هذه المرحلة.
> لم يُعدّل أي كود، أو قاعدة بيانات، أو ترحيل، أو إعداد إنتاج، أو ملف قانوني/حكومي ضمن المستودع.
> المصدر: `C:\Users\Power tech\Desktop\MONAZZEM_Master_Development_Prompt.md`.
> المستودع: `F:\focus study\poc` — الفرع `main` عند `0ee95a4`، أحدث وسم `focus-v0.29.0`، الإصدار `versionCode 37 / versionName 0.29.0`.
> التاريخ: 2026-11-20.

## دليل التصنيف وحدود الفحص

- **Confirmed**: دليل مباشر في الكود أو الإعدادات أو `git` داخل المستودع.
- **Suspected**: استنتاج معقول من الكود ولم يتحقق منه زمن التشغيل أو من بيئة حية.
- **Not Verified**: لا يمكن إثباته محليًا؛ يحتاج جهازًا أو حساب إدارة أو بيئة Supabase/Vercel حية.

**حدود مهمة:** لا توجد أسرار إدارة صالحة، ولا جهاز Android متصل بـ ADB، ولا وصول Supabase في هذه الجلسة. لذلك كل ما يتعلق بالسلوك الحي (وصول الإشعارات، استلام رسالة على جهاز، حالة RLS الحية) يُصنّف `Not Verified` حتى لو كان الكود واضحًا. لم تُعاد تشغيل الاختبارات في هذه المرحلة؛ الأرقام المذكورة من `docs/MONAZZEM_TEST_LOG.md` هي سجل سابق، وليست نتيجة تشغيل جديد.

---

## 1. ملخص تنفيذي (Output 1)

| # | النتيجة | التصنيف |
|---|---|---|
| 1.1 | المشروع قائم بالفعل وناضج: Android Native + Jetpack Compose، `minSdk 26`، `targetSdk 36`، `versionCode 37 / 0.29.0`. لا يوجد بناء من الصفر مطلوب. | **Confirmed** |
| 1.2 | **أخطر اختلاف عن Master Prompt: لا يوجد Supabase إطلاقًا.** لا `@supabase/*`، ولا `supabase.co`، ولا `service_role`، ولا ملفات `.sql`، ولا RLS migrations في المستودع. الخلفية الفعلية هي Vercel Functions + Vercel Blob داخل `update-site/`. | **Confirmed** |
| 1.3 | البيانات الشخصية الأساسية محلية: `focus_study.db` بـ `DB_VERSION = 14`، وملفات `SharedPreferences` متعددة. لا يوجد مزامنة سحابية لل مهام/الملاحظات/الأهداف بين Android وأي طرف آخر. | **Confirmed** |
| 1.4 | هوية المستخدم الحالية هي **تثبيت مجهول** (`UUID` عشوائي في `focus_team_messages/user_id`)، وليست حسابًا موثقًا. لا يوجد جدول `users` ولا `devices` ولا `profiles` بالمعنى التقليدي. | **Confirmed** |
| 1.5 | استهداف الرسالة الفردية أُصلح في `0.29.0` على مستوى الكود الجديد، لكن سجلات قديمة تحمل `targetCode` فقط قد تُعامل كبث عام، وت needle public Blob يعرض حقول الاستهداف. | **Confirmed** (code/history) / **Not Verified** (live) |
| 1.6 | واجهة `MessageCodeCard` تعرض رمزًا قابلًا للنسخ والإرسال وتقول «يمكنك إعطاء الرمز ليصلك رد خاص». هذا يتعارض مع قاعدة «لا تطلب من المستخدم نسخ Device ID أو Push Token أو إرساله يدويًا»؛ الرمز ليس الـ ID الخام ولا Push Token، لكنه مشتق من معرّف التثبيت ويُطلب إرساله يدويًا. | **Confirmed** |
| 1.7 | نظام الإشعارات غني لكنه يعتمد `AlarmManager` مع **عدم طلب صلاحية المنبّهات الدقيقة**، وحد أقصى 10 منبّهات، واستنزاف غير مقصود عبر `ignoredStreak`، وتأجيلات غير محفوظة. | **Confirmed** |
| 1.8 | لا توجد خطوة اختيار منطقة زمنية في `FirstLaunchSetupScreen`؛ كل الحسابات تستخدم `ZoneId.systemDefault()` / `TimeZone.getDefault()`. لا يوجد IANA محفوظ، ولا معالجة DST صريحة. | **Confirmed** |
| 1.9 | مواقيت الصلاة محسوبة خوارزميًا عبر `com.batoulapps.adhan:adhan:1.2.1` بطريقة `EGYPTIAN` افتراضيًا؛ ليست أوقاتًا مصرية ثابتة في الكود. | **Confirmed** |
| 1.10 | التنقل الحالي يحتوي 17 معرّف تبويبًا وخمسة مثبتة افتراضيًا، مع تكرار دليل الأقسام داخل «المزيد». | **Confirmed** |
| 1.11 | لا توجد نسخة Web App للتطبيق؛ يوجد فقط موقع تحديثات/installable PWA في `update-site/public`. | **Confirmed** |
| 1.12 | عدد Vercel Functions = 12 (9 إدارية + 3 عامة)، داخل حد Hobby. لم يُضف function جديد في أعمال التحديث السابقة. | **Confirmed** |
| 1.13 | المستندات القانونية/Mعمارية القديمة (`PRIVACY_POLICY_AR.md`, `MONAZZEM_ARCHITECTURE.md`, `PRE_LAUNCH_CHECKLIST.md`, `FEEDBACK_CONSENT_AR.md`) لا تعكس التنفيذ الحالي للشبكة والتسجيل. | **Confirmed** |
| 1.14 | 161 `@Test` موجودة statically في 17 ملف اختبار، وسجل سابق يسجّل 161/161، لكن CI لا يشغّل unit tests. | **Confirmed** (count) / **Not Verified** (run) |

**الخلاصة:** لا حاجة لإعادة البناء، لكن قبل أي تنفيذ يجب حسم سؤال معماري واحد: هل ننتقل فعليًا إلى Supabase أم نُبقي على Vercel Blob ونبني عليه نموذج الهوية/الرسائل؟ بقية المراحل تتوقف على هذا القرار.

---

## 2. خريطة المعمارية الحالية (Output 2)

**Confirmed** — الطبقات كما هي فعليًا:

```text
Android (com.focusstudy.poc)
 ├─ UI: MainActivity.AppRoot  ── Crossfade حسب rootTab/session/setup/picker/onboarding
 │   └─ Compose screens: Today/Tasks/Subjects/Goals/Skills/Notes/Planner/Calendar/
 │      Analytics/TaskTypes/Messages/Notifications/Prayer/Updates/Settings/Support…
 ├─ Local state
 │   ├─ StudyDb: focus_study.db, DB_VERSION=14, SQLiteOpenHelper (15 جدولًا تقريبًا)
 │   ├─ FocusPreferences: focus_study_preferences
 │   ├─ focus_team_messages (uid, cache, seen, dismissed, signature…)
 │   ├─ prayer_preferences / dhikr_preferences / focus_notification_sounds …
 │   └─ UserIdentity: UUID ثابت لكل تثبيت + رمز عرض 8 أحرف
 ├─ Network (HttpURLConnection, بلا مكتبة HTTP)
 │   ├─ TeamMessages → GET/POST https://update-site-sigma.vercel.app/api/messages
 │   ├─ TeamMessages.sendSupport → POST /api/support
 │   └─ AppUpdateManager → BuildConfig.UPDATE_FEED_URL → /api/latest
 └─ Schedulers: AlarmManager + receivers + JobScheduler(4741) + channels _v3
        ↓
Vercel (update-site/, 12 functions)
 ├─ Public: /api/latest  /api/messages  /api/support
 ├─ Admin : login/logout/session/password/messages/publish/publish-ci/store-link/upload-apk
 ├─ Libs  : admin-auth, admin-credentials, messaging-store, release-store, user-code
 └─ Storage: Vercel Blob JSON
     ├─ public : focus-study/team-messages.json , focus-study/latest.json , releases/*.apk
     └─ private: focus-study/users.json , support-inbox.json ,
                 notification-sounds.json , admin-credential.json
        ↓
Distribution: GitHub Release MONAZZEM.apk → /api/latest → QUBIXStore (/api/latest proxy, FOCUS_STUDY_FEED_URL)
Web: update-site/public = موقع تحديثات + PWA shell فقط (manifest.webmanifest + sw.js)، لا تطبيق مهام.
```

**Not Verified**: مطابقة الكود المنشور فعليًا على Vercel مع نسخة `update-site/` المحلية؛ حالة الأسرار في البيئة الحية؛ سلوك الأجهزة.

---

## 3. جرد كامل للخصائص والشاشات (Output 3)

الحالة تعني:working بالكود (`Confirmed`) أو تحتاج تحققًا حيًا (`Not Verified`). لم يُحذف أي عنصر.

| التصنيف | الاسم الحالي / الملف | ما يفعله فعلًا | البيانات | ارتباطات | الحالة |
|---|---|---|---|---|---|
| يومي | `Today` / `MainActivity.TasksScreen(dashboard=true)` | لوحة مهام اليوم: تحية، نية يومية، رفيق، بطاقة رسالة الفريق، خطة اليوم، مهام | `tasks`, `FocusPreferences`, `focus_team_messages` | FAB، جلسة، إشعارات | **Confirmed** |
| يومي | `NotificationCenterScreen` | حالة إذن الإشعارات، إشعار تجريبي، مفاتيح الرسائل/الهدوء | `focus_study_preferences` | `AppNotifications`, `NotificationPolicy` | **Confirmed** |
| يومي | `PrayerTimesScreen` (`SettingsSupportScreens.kt`) | طريقة الحساب، إحداثيات، تفعيل أذكار | `prayer_preferences` | `PrayerTimes`, `DhikrReminders` | **Confirmed** |
| يومي | `PrayerAlertActivity` | شاشة تنبيه صلاة بملء الشاشة/قفل الشاشة | إشعار `prayer_adhan_v3` | `PrayerTimes` | **Confirmed** |
| تخطيط/mهام | `TasksScreen` / `TaskListViewModel` / `TaskListControls` | عرض، بحث، فلترة، ترتيب، حالة/أولوية/نوع/موعد | `StudyDb.tasks` | `AddTaskDialog`, `SessionEngine`, `NextStep` | **Confirmed** |
| تخطيط/mهام | `AddTaskDialog` (`MainActivity.kt:3794`) | اسم المهمة أولًا؛ خيارات إضافية مطوية | `StudyTask` | `QuickCaptureParser` | **Confirmed** |
| تخطيط/mهام | `QuickAddFab` + `QuickCaptureParser` | التقاط سريع بالعربية: وقت/مدة/تكرار/أولوية | نص حر | `AddTaskDialog` | **Confirmed** |
| تخطيط/mهام | `TaskTypesScreen` / `TaskTypesViewModel` | أنواع مخصصة، أرشفة، ترتيب؛ النوع اختياري | `task_types` | مهام | **Confirmed** |
| تخطيط/mهام | `StudyPlannerScreen` / `StudyPlanner` | خطة 7 أيام من المهام المفتوحة والهدف اليومي | `tasks`, `FocusPreferences.dailyGoalMinutes` | جلسة | **Confirmed** |
| تخطيط/mهام | `StudyCalendarScreen` / `StudyCalendarViewModel` | يوم/أسبوع/شهر + أحداث + كشف تعارض | `tasks.dueAt`, `events` | `ScheduleConflictDetector`, `PrayerWindows` | **Confirmed** |
| تخطيط/mهام | `RecurrenceEngine`, `TodayPlan`, `NextStep` | تكرار، تجميع يومي، ترتيب الخطوة التالية | `tasks`, `exams` | واجهة اليوم | **Confirmed** |
| دراسة | `SubjectsScreen` / `SubjectListViewModel` / `SubjectRepository` | مواد، مدرس، هدف أسبوعي، امتحان | `subjects`, `exams` | تقويم، إحصاء، تذكير امتحان | **Confirmed** |
| دراسة | `SessionSetupScreen`, `SessionScreen`, `SessionEngine`, `SessionAlarm` | جلسة تركيز، حظر تطبيقات، VPN/Service/DeviceOwner، مؤقت | `sessions`, `ConsentStore` | إشعارات، حظر | **Confirmed** ( runtime **Not Verified**) |
| دراسة | `StudyAnalyticsScreen` / `StudyAnalytics` | إحصاء أسبوعي؛ يتطلب 3 أيام نشطة و5 مهام مكتملة | `sessions`, `tasks` | اليوم | **Confirmed** |
| أهداف | `GoalsScreen` / `GoalRepository` | أهداف، وحدة، فئة، موعد، ربط مهام/مهارات | `goals`, `goal_links` | مهام، مهارات | **Confirmed** |
| أهداف | `SkillsScreen` / `SkillRepository` | مهارات، مستويات، دقائق ممارسة | `skills` | أهداف | **Confirmed** |
| أدوات | `NotesScreen` / `NoteRepository` / `NotesText` | ملاحظات خاصة: إضافة/تعديل/تثبيت/بحث/تصنيف | `notes` (DB v14) | لا يوجد أي شبكة | **Confirmed** |
| أدوات | `BlockGate`, `BlockVpnService`, `AppBlockAccessibilityService`, `DeviceOwnerController`, `PermanentAppsScreen` | حظر تطبيقات أثناء الجلسة | `FocusPreferences`, `ConsentStore` | جلسة | **Confirmed** (قيود OEM **Not Verified**) |
| أدوات | `Voice`, `WelcomeGreeting`, `DailyIntention`, `StudentCompanion`, `ArabicCount` | نصوص تشجيعية عربية، نبرة/شخصية | `FocusPreferences` | اليوم/الإشعارات | **Confirmed** |
| عبادات | `PrayerTimes`, `PrayerWindows`, `DhikrReminders` | حساب Adhan، نوافذ صلاة، أذكار صباح/مساء | `prayer_preferences`, `dhikr_preferences` | منبّهات، سياسة إشعار | **Confirmed** |
| حساب/تخصيص | `FirstLaunchSetupScreen` | صفحتان: اسم، ملف، رفيق، هدف يومي، إشعارات | `FocusPreferences` | `UserRegistry` | **Confirmed**؛ المنطقة الزمنية **غائبة** |
| حساب/تخصيص | `PersonalizationScreen`, `BottomBarSettingsScreen`, `Theme`, `FocusPreferences` | اسم، ملف، نبرة، كثافة، مظهر، تبويبات، هدوء | `focus_study_preferences` | كل الشاشات | **Confirmed** |
| حساب/تخصيص | `ConsentStore` | سجل قرارات الأذونات | `consents`, `last_granted_at` | دعم فني | **Confirmed** |
| تواصل | `MessageCenter`, `TeamMessageBanner`, `UserIdentity`, `UserRegistry` | سحب رسائل الفريق، عرض رمز خاص، تسجيل تلقائي | `focus_team_messages/users.json` | `/api/messages` | **Confirmed** |
| تواصل | `SupportScreen` + `/api/support` | إرسال دعم مع الاسم/وسيلة تواصل/النص | `support-inbox.json` (private) | إدارة | **Confirmed** |
| إدارة/تحديث | `public/admin/app.js`, `messages.html`, `api/admin/*` | إدارة المستخدمين والرسائل والإصدارات والأصوات | Blob JSON | `/api/messages` | **Confirmed** |
| إدارة/تحديث | `AppUpdatesScreen`, `AppUpdateManager`, `AppUpdateReceiver` | فحص `/api/latest`، تنبيه إصدار جديد، تثبيت APK | `focus_study_update_state`, `latest.json` | GitHub Release + QUBIXStore | **Confirmed** |
| إدارة/تحديث | `.github/workflows/focus-study-release.yml` | بناء موقّع، نشر GitHub Release، مزامنة التغذية | secrets | `/api/admin/publish-ci` | **Confirmed** |

**ملاحظة جردية:** `bottom_bar` و`permanent_apps` ليسا `BottomTab` id؛ يصل إليهما عبر `MoreSectionsScreen` / `PersonalizationScreen`. الشاشات غير المثبتة يختفي منها الـ dock، لكن أزرار «رجوع» موجودة.

---

## 4. خريطة التنقل الحالية والمقترحة (Output 4)

### الحالية — **Confirmed**

- `MainActivity` يستخدم متغيرًا نصيًا `rootTab` (افتراضي `today`) مع أولوية:
  `session > setup > picker > onboarding > rootTab` (`MainActivity.kt:559-598`).
- مسارات فعلية في `when (current)`:
  `onboarding, session, setup, picker, subjects, task_types, skills, notes, bottom_bar, messages, notifications, goals, calendar, more, prayer_times, updates, settings, permanent_apps, support, planner, analytics, today, tasks`.
- `BottomTabs.DEFAULT = [today, tasks, calendar, subjects, more]` و`MAX = 5`؛ `normalize()` يزيل التكرار والمجهول ويقصّ القائمة (`BottomTabs.kt:9-49`).
- `FocusDock` يجميع المسارات في ثلاث مجموعات: التخطيط، التطوّر، الفريق والنظام، ثم «الأقسام» (`FocusDock.kt:42-205`).
- «المزيد» يعيد عرض دليل الأقسام بصورة ثانية (`MainActivity.kt:1397-1462`): تكرار وظيفي لا حذف فيه.
- `today` و`tasks` يستخدمان `TasksScreen` نفسه بعلم `dashboard`.

### المقترحة — **Suspected / proposal only**

الهيكل المقترح في Master Prompt (اليوم، مهامي، التخطيط، موادي، المزيد) قابل للتنفيذ **دون حذف أي مسار**:

| التبويب المقترح | يستقبل من الحالي |
|---|---|
| اليوم | `today` + بطاقات `DailyIntention`, `NextStep`, تنبيهات الفريق |
| مهامي | `tasks`, `task_types`, `QuickAddFab`, فلاتر `TaskListControls` |
| التخطيط | `planner`, `calendar`, `analytics`, `reminders/notification center` |
| موادي | `subjects`, `exams`, `study calendar events` |
| المزيد | `goals`, `skills`, `notes`, `prayer_times`, `messages`, `updates`, `settings`, `support`, `bottom_bar`, `permanent_apps` |

**قواعد مقترحة:** الحفاظ على RTL، تقليل النقرات، عدم تغيير سلوك أي ميزة أثناء النقل، توثيق أي إعادة تسمية، اختبار الوصول لكل المسارات القديمة بعد التغيير. لم يُنفّذ شيء.

---

## 5. مخطط قاعدة البيانات وعلاقاتها (Output 5)

### 5.1 محلي — **Confirmed**

`StudyDb.kt`: `DB_NAME = focus_study.db`, `DB_VERSION = 14`, ترحيلات تراكمية `migrateToV2..V14` عبر `addColumnIfMissing` (`PRAGMA table_info` + `ALTER TABLE`).

| الإصدار | الجداول/الأعمدة | الغرض |
|---|---|---|
| v1 | `tasks`, `sessions` + indexes | المهام والجلسات الأساسية |
| v2 | `subtasks`, أعمدة `description/category/priority/status/dates/subject/notes/recurrence/sort_order/completed_at` | تفاصيل المهمة |
| v3 | `semesters`, `subjects`, `exams` | المواد والامتحانات |
| v4 | `tasks.original_due_at` | حفظ الموعد الأصلي |
| v5 | `events` | أحداث التقويم |
| v7 | `task_types` + بذور أنواع | أنواع المهام |
| v8 | `reminders`, `notification_log` | التذكيرات وسجلها |
| v9/v10 | `consents`, `last_granted_at` | سجل الأذونات |
| v11 | `skills` | المهارات |
| v12 | `goals` | الأهداف |
| v13 | `goal_links` | ربط الأهداف بالمهام/المهارات |
| v14 | `notes` | ملاحظات خاصة محلية فقط |

**علاقات منطقية** (عبر أعمدة id؛ وجود `FOREIGN KEY` مفروض على مستوى SQLite **Not Verified**):
`tasks.subject_id → subjects.id`، `tasks.type_id → task_types.id`، `subtasks.task_id → tasks.id`، `reminders.task_id → tasks.id` (معرف سالب للامتحان مثل `-subjectId`)، `notification_log` يرتبط بـ `task_id/kind`، `goal_links → goals + tasks/skills`، `events` مستقل تقويميًا.

### 5.2 خادمي (ليس Supabase) — **Confirmed**

| المسار في Blob | النمط | الوصول | الحد |
|---|---|---|---|
| `focus-study/team-messages.json` | public | `public` | أحدث 40 رسالة |
| `focus-study/users.json` | private | `private` | 1000 مستخدم (`MAX_USERS`) |
| `focus-study/support-inbox.json` | private | `private` | 500 عنصر |
| `focus-study/notification-sounds.json` | private | `private` | مفاتيح أصوات |
| `focus-study/admin-credential.json` | private | `private` | scrypt hash/salt |
| `focus-study/latest.json` | public | `public` | بيانات الإصدار |
| `focus-study/releases/*.apk` | public | `public` | ≤200MB |

### 5.3 Supabase — **Confirmed absent**

لا جداول، ولا أعمدة، ولا علاقات، ولا فهارس، ولا قيود، ولا RLS، ولا triggers/functions/views، ولا Storage buckets، ولا cron، ولا Edge Functions. لا ملفات `.sql` في المستودع. المقارنة بالمخطط «المتوقع» في Master Prompt غير ممكنة لأن المخطط غير موجود أصلًا.

---

## 6. تحليل تكامل Supabase (Output 6)

| البند | النتيجة | التصنيف |
|---|---|---|
| وجود Supabase | غائب تمامًا؛ البحث عن `supabase`, `service_role`, `anon key` في المسارات النشطة أعاد 0 نتائج، ولا تبعية `@supabase/*` في `app/build.gradle.kts` أو `update-site/package.json`. | **Confirmed** |
| أثر في git | `git log -S'supabase' -i` لا يُظهر التزامات بهذا المعنى. | **Confirmed** |
| قطعة أثرية مشبوهة | `work/qubixstore-current.js` (مجلد `work/` مستبعد بـ `.gitignore`) يستورد `./supabase-FckNHHsq.js` غير الموجود محليًا؛ لا يوجد دليل على أنه كود مُنشَر. | **Suspected** (ignored artifact) |
| البديل الفعلي | Vercel Functions + `@vercel/blob`، تفاصيلها في القسمين 2 و5. | **Confirmed** |
| المصادقة | المستخدم: لا تسجيل دخول؛ `UUID` مجهول. الإدارة: كلمة مرور بيئية + كوكي HMAC `__Host-focusstudy_admin`. CI: Bearer token. | **Confirmed** |
| الأسرار | أسماء فقط: `ADMIN_USERNAME`, `ADMIN_PASSWORD`, `ADMIN_SESSION_SECRET`, `BLOB_READ_WRITE_TOKEN`, `FOCUS_RELEASE_SYNC_TOKEN`, `FOCUS_STORE_URL` و`ANDROID_KEYSTORE_*` في CI. القيم لا تُعرض في هذا التقرير؛ الملفات الحاملة لها gitignored. | **Confirmed** |
| بيئات dev/prod | عنوان إنتاج واحد `https://update-site-sigma.vercel.app` مكتوب في `MessageCenter.BASE` و`app/build.gradle.kts`؛ لا عناوين staging. | **Confirmed** |
| تعارض توثيقي | `docs/MONAZZEM_ARCHITECTURE.md` و`PRE_LAUNCH_CHECKLIST.md` و`docs/legal/PRIVACY_POLICY_AR.md` و`FEEDBACK_CONSENT_AR.md` تنفي وجود خادم/إرسال تلقائي؛ الكود يرسل `uid`, `name`, `profile`, `versionName/Code` ورسائل الدعم. | **Confirmed** |

**النتيجة العملية:** المرحلة 1 في Master Prompt («Identity & Data Foundation») لا يمكن تنفيذها كما كُتبت دون قرار: إما إدخال Supabase فعليًا (جداول + RLS + Auth) أو إعادة صياغة المخطط ليناسب Vercel Blob الحالي مع تحسينات أمنية صريحة.

---

## 7. تحليل لوحة الإدارة ومسار الرسائل (Output 7)

### 7.1 نقاط النهاية — **Confirmed**

| Endpoint | الوظيفة | الحماية |
|---|---|---|
| `GET /api/messages` | تغذية رسائل/flavors + أصوات، مع `profile`, `version`, `uid` | عام؛ `private, no-store` عند وجود uid، وإلا cache عامة |
| `POST /api/messages` | `action=register`: uid/name/profile/version | عام، تحقق regex وطول وقائمة ملفات مسموحة |
| `POST /api/support` | inbox دعم | عام |
| `GET/HEAD /api/latest` | بيانات إصدار | عام، cache 60/300 |
| `GET /api/admin/session` | حالة الجلسة | كوكي |
| `POST /api/admin/login` | تسجيل دخول | Origin + rate limit 8/15min في الذاكرة |
| `POST /api/admin/logout`, `/password` | خروج/تغيير كلمة مرور | كوكي (Origin على logout) |
| `GET/POST/DELETE /api/admin/messages` | inbox/users/sounds/messages | كوكي |
| `POST /api/admin/publish` | نشر إصدار | Origin + كوكي + version أعلى صرامةً |
| `POST /api/admin/publish-ci` | مزامنة CI | Bearer `FOCUS_RELEASE_SYNC_TOKEN` |
| `POST /api/admin/store-link`, `/upload-apk` | رابط المتجر/رفع APK | Origin + كوكي |

### 7.2 المسار الكامل — **Confirmed**

`public/admin/messages.html` → `POST /api/admin/messages` → `lib/messaging-store.appendMessage()` → Blob عام `team-messages.json` → `TeamMessages.fetch()` في Android (سحب عند فتح التطبيق) → `NotificationSounds.cache()` → `TeamNotifier.notifyEach()` → `seen` محلي. **لا يوجد Push/FCM في MONAZZEM**؛ الإشعارات الإدارية تصل بالسحب لا بالدفع.

### 7.3 الاستهداف — **Confirmed**

- `api/admin/messages.js:50-67`: يحلّ `targetRef` إما كرمز قصير (`normalizeUserCode`) فيُبحث في `readUsers()` ويُخزّن `targetUserId` الكامل، أو كـ id كاملDIRECT.
- `api/messages.js:30-44`: إن وُجد `targetUserId` فعلى الجهاز أن يرسل `uid` مطابقًا تمامًا؛ وإلا تُطبّق قواعد `audienceProfile` و`minVersionCode`. لا يوجد fallback إلى «الجميع» في الكود الحالي لرسالة تحمل `targetUserId`.
- الرموز القصيرة للعرض فقط؛ `targetCode` لا يُستخدم كمفتاح استلام.
- `scripts/test-targeting.cjs` يغطي: تحويل الرمز إلى id، 404 لرمز مجهول، استلام بالـ id فقط، استبعاد الرمز كـ uid/بادئة/جهاز آخر/بدون uid، وقابلية بث عام للتخزين المؤقت.
- `Cache-Control: private, no-store` للردود الشخصية.

### 7.4 قيود مؤكدة — **Confirmed**

| القيد | الدليل | الأثر |
|---|---|---|
| لا أدوار متعددة | `isAdmin()` واحد في `lib/admin-auth.js` | لا تدرج صلاحيات، لا تدقيق تغييرات |
| لا سجل تسليم | لا حقل `sent/delivered/failed/retry` في الرسائل | لا يمكن للإدارة معرفة الوصول |
| لا idempotency للرسائل | `appendMessage` بلا مفتاح إعادة محاولة | إعادة الإرسال تنشئ نسخة |
| Rate limiting محدود | `login.js` فقط، بمفتاح `x-forwarded-for` قابل للتزوير | إساءة على `/api/messages`, `/api/support` |
| uid/profile/version من العميل | `api/messages.js` يثق بـ query | انتحال ملف/نسخة/معرّف ممكن تقنيًا |
| مسح عام | لا CORS headers؛ public Blob بمسار ثابت | الوصول المباشر للمسار يتجاوز فلترة API |
| التسجيل محدود | `MAX_USERS = 1000`, `runCatching` يبتلع الفشل | أسماء قديمة قد تُستبدل بمرور الوقت |
| Function budget | 12 function | أي ميزة جديدة يجب أن تُضاف داخل functions موجودة |

---

## 8. السبب الجذري لمشكلة الرسائل الفردية / ما يلزم لإثباته (Output 8)

| الفرضية | الدليل | التصنيف |
|---|---|---|
| سجلات قديمة بـ `targetCode` فقط تسقط إلى قواعد البث؛ لأن `addressedToMe()` يُعيد `null` عند غياب `targetUserId`، فيُطبَّق `audienceProfile/minVersion` على الجميع. | `git show eb03f56^:update-site/api/admin/messages.js` + `api/messages.js:30-44` الحالي؛ لا يوجد ترحيل/تنظيف للسجلات القديمة. | **Confirmed** (code/history)، الأثر الحي **Not Verified** |
| الرمز القصير وحده لا يكفي لقراءة رسالة، لكن الـ uid الكامل إذا عُرف يكفي؛ ولا يوجد تحقق سحابي من هوية مقدّم الطلب. | `api/messages.js:24,31-34`؛ `scripts/test-targeting.cjs`. | **Confirmed** |
| محتوى الرسائل المستهدفة وحقول `targetUserId/targetCode` منشورة في Blob عام بمسار ثابت. | `lib/messaging-store.js:5,12` + `writeJson` بـ `addRandomSuffix:false`. | **Confirmed** |
| واجهة التطبيق تطلب مشاركة الرمز يدويًا، رغم أن التسجيل التلقائي يرسل الاسم والملف إلى السجل. | `MessageCenter.kt:337-350` مقابل `UserIdentity.UserRegistry.register`. | **Confirmed** |
| لا يوجد سجل تسليم ولا idempotency، لذا لا يمكن إثبات «وصل لشخص آخر» منային السيرفر. | القسم 7.4. | **Confirmed** |
| وصول رسالة فعلية إلى مستخدم آخر على جهاز حقيقي. | لا جهاز/إدارة حية. | **Not Verified** |

**ما يلزم لإثباته:** (1) فحص آخر 40 سجلًا في `team-messages.json` بحثًا عن سجلات `targetUserId == "" && targetCode != ""`، (2) جهازان بـ uid مختلفين + حساب إدارة، (3) إرسال رسالة لرمز جهاز واحد والتحقق من أن الجهاز الآخر لا يراها، (4) مراجعة ما إذا كان أي طرف يستخدم المسار المباشر للـ Blob. كل ذلك خارج نطاق التدقيق الثابت.

**الحل المقترح (دون تنفيذ):** حقل `targetMode` صريح (`all|profile|version|user`)، رفض أي رسالة بلا جمهور محدد، تنظيف/ترحيل السجلات القديمة، نقل الرسائل إلى Blob خاص أو Edge Function مخول، إزالة طلب مشاركة الرمز من الواجهة، وإضافة `delivery_log`.

---

## 9. تحليل الإشعارات والتذكيرات (Output 9)

| العنصر | الحقيقة الكودية | التصنيف |
|---|---|---|
| القنوات | 7 قنوات `_v3` في `AppNotifications.AlertChannel` + `session_summary`; importance ثابت عند الإنشاء. | **Confirmed** |
| إذن التشغيل | `POST_NOTIFICATIONS` في Manifest ويُطلب من `MainActivity.requestNotificationPermissionIfNeeded()`؛ **رد الـ launcher فارغ** (`MainActivity.kt:286-290`) فلا إعادة جدولة بعد المنح اللاحق. | **Confirmed** |
| المنبّهات الدقيقة | `SCHEDULE_EXACT_ALARM` مُعلن، لكن لا `USE_EXACT_ALARM` ولا `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`؛ عند `canScheduleExactAlarms()==false` يتم `setAndAllowWhileIdle` صامتًا. | **Confirmed** |
| سقف الجدولة | `MAX_ALARMS = 10` و`.take(10)`؛ التذكير رقم 11+ بلا منبّه حتى إعادة جدولة. | **Confirmed** |
| استنزاف غير مقصود | `deliver()` لا يكتب `ACTION_IGNORED`، لكن `StudyDb.ignoredStreak()` يحسب السجل الفارغ كتجاهل؛ بعد 3 يُسقط `NotificationPolicy` التذكيرات غير الحرجة نهائيًا. لا `setDeleteIntent`. | **Confirmed** |
| استهلاك قبل التحقق | `dispatch` يستدعي `markReminderDone` ثم `deliver` الذي يعود إن لم يكن الإذن مسموحًا؛ `ReminderEngine.notificationsAllowed` يتجاهل `areNotificationsEnabled()` بخلاف `AppNotifications.canPost()`. | **Confirmed** |
| تأجيلات هشة | `StoredReminder(-1, …)` لا يُحفظ في DB؛ يضيع عند reboot/force-stop، و`sessionEndsAt()` معرّف لكن غير مستدعى. | **Confirmed** |
| سياسة الإشعار | `NotificationPolicy.decide` يطبّق جلسة/صلاة/هدوء/كثافة/فجوة على `StoredReminder` فقط؛ Dhikr والصلاة ورسائل الصباح/المساء تتجاوزها. | **Confirmed** |
| إعادة تشغيل | `FocusBootReceiver` يتعامل مع BOOT/TIME/TIMEZONE/DATE ويعيد 8 خطوات؛ لا مستقبل `MY_PACKAGE_REPLACED` — التحديث يعتمد على `MainActivity.onCreate` أو JobScheduler كل 6 ساعات. | **Confirmed** |
| JobScheduler | `ReminderRescheduleJob` (id 4741) يعيد القنوات/التذكيرات/الصلاة فقط، لا Dhikr ولا الجلسة. | **Confirmed** |
| سجل تشخيصي | `notification_log` + LogCat `FocusBoot/FocusJob`، لكن فشل AlarmManager/الإذن مُبتلع بـ `runCatching` بلا log. | **Confirmed** |
| Push إداري | غائب؛ الرسائل تُسحب عند فتح التطبيق. | **Confirmed** |
| تشغيل فعلي على جهاز | غير متحقق. | **Not Verified** |

**ربط بالمشاكل المعروفة (5 و6):**
- «أحيانًا تظهر وأحيانًا تفشل»: **Confirmed** (exact alarm+sقف 10+ignoredStreak+إذن callback فارغ+تأجيلات ضائعة).
- «مبكرة/متأخرة/غير متسقة»: **Confirmed** ( inexact fallback، حساب امتحان bằng `/DAY` في `ReminderEngine.kt:98-105`، تجاوز السياسات، DST في القسم 10).

---

## 10. تحليل المنطقة الزمنية والتوقيت ومواقيت الصلاة (Output 10)

| البند | النتيجة | التصنيف |
|---|---|---|
| اختيار IANA في أول تشغيل | **غير موجود**: `FirstLaunchSetupScreen` صفحتان بلا حقل منطقة زمنية؛ `FocusPreferences` لا يحتوي مفتاح `timezone`. | **Confirmed** |
| المصدر المستخدم | `ZoneId.systemDefault()` في `ReminderEngine`, `PrayerWindows`, `RecurrenceEngine`, `NextStep`, `StudyPlanner`, `GoalsScreen`, `MainActivity` و`TimeZone.getDefault()` في `PrayerTimes.format`. | **Confirmed** |
| حفظ Offset ثابت | لا يوجد offset ثابت مكتوب في الكود؛ المشكلة هي الاعتماد على منطقة الجهاز ضمنيًا. | **Confirmed** |
| DST | كل المنبّهات `RTC_WAKEUP` بزمن مطلق؛ `FocusBootReceiver` يستمع `TIMEZONE_CHANGED` لكن تغيّر إزاحة داخل نفس الـ ZoneId لا يولّد تغيّرًا في المعرّف؛ لا كود DST صريح. `RecurrenceEngine.next` هو الاستثناء الوحيد الدقيق. | **Confirmed** (mechanism) / حدوثه الميداني **Not Verified** |
| مواقيت الصلاة | مكتبة `adhan:1.2.1`، طريقة `EGYPTIAN` افتراضيًا مع `UMM_AL_QURA`, `MUSLIM_WORLD_LEAGUE`, `KARACHI` قابلة للاختيار؛ لا أوقات مصر ثابتة. | **Confirmed** |
| الموقع | `ACCESS_COARSE_LOCATION` يُطلب عند الحاجة في `PrayerTimesScreen`؛ إحداثيات يدوية مسموحة؛ الحفظ كـ `Float` (فقد دقة). لا شبكة لحساب المواقيت. | **Confirmed** |
| افتراض خفي | يُفترض أن منطقة الجهاز تطابق منطقة الإحداثيات؛ السفر أو إحداثيات يدوية من منطقة أخرى تعطي أوقاتًا خاطئة. | **Confirmed** (code assumption) |
| الأذكار | أوقات بديلة ثابتة `07:00/12:30/18:00/21:30` عند غياب الموقع، وتُعاد تعبئة منبّه واحد عند كل تسليم؛ لا تخضع لساعات الهدوء أو نوافذ الصلاة. | **Confirmed** |
| تغيير المنطقة لاحقًا | غير متاح من الإعدادات؛ لا إعادة حساب للمواعيد المستقبلية. | **Confirmed** |

المشكلة 7 («تأثر المواعيد بالتوقيت الصيفي/المنطقة») سببها الجذري: **غياب تخزين IANA مستقل وغياب إعادة جدولة عند تغيّر القواعد**، وليس خطأً في مكتبة Adhan.

---

## 11. تقييم الأمان وRLS (Output 11)

| البند | التقييم | التصنيف |
|---|---|---|
| Supabase RLS | غير مطبق لأن Supabase غير موجود. | **Confirmed** |
| مكافئ RLS | Vercel Blob: `private` للـ users/inbox/sounds/credential مع قراءة صارمة (`private_store_unavailable`)؛ `public` للرسائل والإصدارات. لا ACL لكل مستخدم. | **Confirmed** |
| كوكي الإدارة | `__Host-focusstudy_admin`، HMAC-SHA256، HttpOnly/Secure/SameSite=Strict، 12 ساعة، timing-safe. `logout` يمسح الكوكي فقط بلا إبطال خادمي. | **Confirmed** |
| Origin checks | على login/logout/publish/store-link/upload-apk؛ `admin/messages` و`password` يعتمدان على الكوكي/Cookie policy. | **Confirmed** |
| أسرار العميل | لا مفاتيح Supabase ولا service role في Android/Web؛ الأسرار في env/server/CI فقط، والملفات الحاملة gitignored. | **Confirmed** |
| كشف المسارات | `team-messages.json` عام بمسار ثابت ويحتوي `uid`/`targetCode`؛ تجاوز مباشر لفلترة `/api/messages`. | **Confirmed** |
| تحقق المدخلات | طول/قوائم/HTTPS/.apk على الإدارة؛ **لا** host allowlist لروابط المتجر/APK، ولا Rate Limiting على مسارات عامة. | **Confirmed** |
| تسجيل بيانات حساسة | لا Push Tokens في MONAZZEM؛ `notification_log` يسجل kind/task/time/status. كود المستخدم يظهر في لوحة الإدارة. | **Confirmed** |
| سياسة الاحتفاظ/الحذف | غير موثقة؛ `team-messages` 40، `users` 1000، `inbox` 500 كحدود تشغيلية فقط. | **Confirmed** |
| خصوصية المستندات | `PRIVACY_POLICY_AR.md` و`FEEDBACK_CONSENT_AR.md` تقول إن الإنترنت للتحقق من التحديث فقط وإن الفيدباك محلي؛ الكود يرسل بيانات تلقائيًا. خطر امتثال/ثقة. | **Confirmed** |
| النسخ الاحتياطي | `allowBackup=false`. | **Confirmed** |
| حد الدوال | 12 function ضمن Hobby. | **Confirmed** |
| بيئة حية | حالة الأسرار المنشورة وسلوك الحماية الفعلي. | **Not Verified** |

---

## 12. توصية معمارية لنسخة Web App (Output 12)

**Confirmed** — ما يمكن إعادة استخدامه: منطق Kotlin النقي (`QuickCaptureParser`, `StudyPlanner`, `RecurrenceEngine`, `ScheduleConflictDetector`, `NotesText`, `NotificationPolicy`, `ConsentPolicy`) قابل للنقل إلى TypeScript؛ عقود `/api/latest` و`/api/messages` و`/api/support` جاهزة؛ `update-site` يعمل على Node/Vercel و esbuild بالفعل.

**Suspected / recommendation** — ما يجب بناؤه حديثًا:

1. **الواجهة**: TypeScript + Vite + React مع RTL أصلي وتصميم Responsive (شبكة على الشاشات الكبيرة لا تكبير عناصر الهاتف). السبب: المشروع الحالي Node/Vercel بلا إطار واجهة، وReact يوفر نظام مكوّنات مناسبًا لحجم الشاشات وصيانة طويلة. لا يوجد حاليًا KMP/WASM، لذا «إعادة استخدام Kotlin مباشرة» غير متاحة.
2. **البيانات**: لا يمكن إعادة استخدام SQLite؛ يلزم IndexedDB (أو localStorage للإعدادات الخفيفة) مع طبقة repository مستقلة. الملاحظات تبقى محلية/خاصة ولا تُزامن إلا بقرار صريح جديد.
3. **المصادقة**: لا يمكن مزامنة آمنة بهوية `UUID` مجهولة فقط؛ يلزم اختيار Supabase Auth أو مصادقة مخصصة على الخادم قبل Phase 6. هذه هي التبعية الحرجة مع القسم 6.
4. **PWA**: يوجد `manifest.webmanifest` و`sw.js` للموقع الحالي فقط؛ يحتاج تطبيق الويب manifest وservice worker وأيقونات أكبر واختبار تثبيت على Windows/macOS/Linux.
5. **الفروق الصريحة للويب**: لا VPN/accessibility/device-owner/AlarmManager/POST_NOTIFICATIONS بنفس السلوك؛ Web Push يحتاج مزود دفع وإذنًا، والعمل في الخلفية محدود؛ مواقيت الصلاة تعتمد `Geolocation` أو إدخال يدوي، ولا تستخدم إذن الموقع لمجرد المنطقة الزمنية.
6. **لا تطبيق Windows Native الآن**، كما要求较高 في Master Prompt.

---

## 13. الملفات والجداول والدوال المتوقع تعديلها (Output 13)

> كل ما يلي **تقديري للتنفيذ اللاحق**؛ لم يُعدّل شيء في التدقيق.

| المرحلة | الملفات/الدوال المتوقعة |
|---|---|
| Phase 1 | `UserIdentity.kt`, `UserRegistry.register`, `FocusPreferences.kt`, `FirstLaunchSetupScreen.kt`, `api/messages.js`, `lib/messaging-store.js`, `lib/user-code.js`, `public/admin/app.js/messages.html`؛ جداول هوية جديدة إما في Supabase أو مكافئات Blob (حسب القرار). |
| Phase 2 | `api/admin/messages.js`, `api/messages.js`, `public/admin/messages.html`, `lib/messaging-store.js`, `scripts/test-targeting.cjs`, وي一个新 سجل تسليم داخل function موجود. |
| Phase 3 | `ReminderEngine.kt`, `ReminderRescheduleJob.kt`, `AppNotifications.kt`, `MainActivity.kt` (permission launcher), `DailyReminderReceiver.kt`, `DhikrReminders.kt`, `PrayerTimes.kt`, `NotificationPolicy.kt`, `StudyDb.kt` (`notification_log`, migrations), `AndroidManifest.xml`. |
| Phase 4 | `FirstLaunchSetupScreen.kt`, `FocusPreferences.kt`, `SettingsSupportScreens.kt`, `PrayerTimes.kt`, `PrayerWindows.kt`, `RecurrenceEngine.kt`, `NextStep.kt`, `DhikrReminders.kt`. |
| Phase 5 | `BottomTabs.kt`, `FocusDock.kt`, `MainActivity.kt` (`AppRoot`, `AddTaskDialog`, `MoreSectionsScreen`), `QuickAddFab.kt`, `BottomBarSettingsScreen.kt`. |
| Phase 6 | مجلد web جديد (لا يوجد حاليًا)، طبقة API/auth مشتركة، `update-site/vercel.json` فقط عند الحاجة؛ لا function جديد إلا بعد مراجعة حد Hobby. |
| Phase 7 | `.github/workflows/focus-study-release.yml`, `app/src/test/**`, `update-site/scripts/*`, `docs/legal/*`, `docs/MONAZZEM_TEST_LOG.md`. |

**جداول/مفاتيح تقاعدية محتملة:** `focus_team_messages/user_id` يصبح `installation_id` بجانب `user_id` حقيقي؛ `focus_study_preferences` يكتسب `timezone_iana`؛ `DB_VERSION 14 → 15` بشكل **إضافي فقط**.

---

## 14. مخاطر التغيير وخطة التراجع (Output 14)

| الخطر | التخفيف | التراجع | التصنيف |
|---|---|---|---|
| كسر هوية التثبيت المجهول | إبقاء `UUID` الحالي وعدم إعادة توليده؛ إضافة حقول بجانبه لا استبداله | `git revert` للفرع + الإبقاء على `user_id` القديم | **Confirmed** |
| فقدان بيانات محلية | ترحيل إضافي `ALTER TABLE ADD COLUMN` فقط، بلا `DROP`/`DELETE` | استعادة نسخة `focus_study.db` ورفع `DB_VERSION` إلى الأعلى التالي | **Confirmed** |
| تحويل رسالة خاصة إلى broadcast | `targetMode` صريح ورفض الرسائل غير المحددة + اختبار عزل | إيقاف النشر والعودة إلى آخر Blob سليم | **Confirmed** |
| كشف معرّفات في Blob عام | نقل الرسائل إلى private/Edge مخول | استعادة Blob سابق + تدوير الأسرار إن لزم | **Confirmed** |
| إشعارات تتأخر بعد تغيير جدولة | ميزة تفعيل تدريجية عبر `SharedPreferences` + اختبار جهاز | إيقاف الميزة بflag والعودة إلى `ReminderEngine` الحالي | **Confirmed** |
| DST/منطقة زمنية | تخزين IANA + إعادة جدولة عند التغيير؛ اختبار `Africa/Cairo` | العودة إلى `ZoneId.systemDefault()` عبر flag | **Suspected** |
| تجاوز Vercel Hobby (12 function) | لا functions جديدة؛ توسيع الموجودة | حذف التعديل داخل function وإعادة النشر | **Confirmed** |
| تعارض توثيقي/خصوصية | تحديث `docs/legal` في نفس المرحلة التي تغيّر السلوك | إعادة المستند السابق من git | **Confirmed** |
| إصدار قديم لا يفهم حقولًا جديدة | حفظ شكل `/api/latest` و`/api/messages` متوافقًا مع 0.29.0 | إبقاء الحقول القديمة بجانب الجديدة | **Confirmed** |

**قاعدة عامة:** فرع Git لكل مرحلة، أصغر تغيير متماسك، تشغيل الاختبارات، مراجعة Diff، توثيق الملفات، ثم بوابة قبول قبل المرحلة التالية. لا ترحيل تدميري، لا أسرار في العميل، لا broadcast بالخطأ.

---

## 15. خطة تنفيذ مرتبة بالأولوية والاعتماديات (Output 15)

| الترتيب | المرحلة | يعتمد على | بوابة الدخول | التصنيف |
|---|---|---|---|---|
| 0 | قرار معماري: Supabase أم Vercel Blob + نموذج هوية | مراجعة هذا التقرير | موافقة صريحة منك | **Not Verified** (قرار معلوماتي ناقص) |
| 1 | Phase 1 — Identity & Data Foundation | 0 | فصل `installation_id` عن `user_id`، ربط الاسم بالملف الصحيح، retry، بلا أسرار | **Suspected** |
| 2 | Phase 2 — Admin Messaging | 1 | `targetMode` + اختبارات عزل + delivery log + تحقق صلاحية | **Suspected** |
| 3 | Phase 3 — Notifications | 1 (uid للسجل التشخيصي إن لزم) | طلب exact alarms، معالجة الإذن، إزالة ignoredStreak الخاطئة، حفظ التأجيلات | **Suspected** |
| 4 | Phase 4 — Time & Prayer | 3 (إعادة الجدولة) | IANA في onboarding/الإعدادات + إعادة حساب + اختبار DST | **Suspected** |
| 5 | Phase 5 — Product & Navigation | 1–4 لا تعتمد عليها تقنيًا، لكن يُفضّل بعد استقرار السلوك | كل المسارات القديمة قابلة للوصول + إضافة مهمة بالاسم فقط | **Suspected** |
| 6 | Phase 6 — Web App | 1 (مصادقة) و2 (بيانات مشتركة) | اختيار التقنية موثقًا + RTL + offline + PWA | **Suspected** |
| 7 | Phase 7 — Hardening & Release | كل ما سبق | regression + security + performance + rollout | **Suspected** |

الترتيب في Master Prompt نقطة بداية صحيحة، لكن **البوابة 0** غير محسومة: لا معنى لتصميم جداول Supabase قبل حسم إن كانت ستُستخدم.

---

## 16. اختبارات القبول لكل مرحلة (Output 16)

| المرحلة | اختبارات القبول المطلوبة | الحالة الحالية |
|---|---|---|
| Phase 1 | مستخدمان باسمين مختلفين يظهران في ملفيهما؛ جهازان للمستخدم نفسه؛ تغيير الاسم يزامن ولا ينشئ نسخة مكررة؛ offline يعيد المحاولة؛ لا أسرار في السجلات | **Not Verified** |
| Phase 2 | رسالة لمستخدم واحد لا تصل لغيره حتى مع عدة أجهزة؛ رسالة الطلاب لا تصل للخريجين/الموظفين؛ للجميع يصل للجمهور المقصود؛ إعادة المحاولة بلا تكرار؛ غير المصرح لا يرسل؛ لا fallback broadcast؛ delivery log | **Not Verified** (يوجد `scripts/test-targeting.cjs` جزئيًا — **Confirmed**) |
| Phase 3 | أول تثبيت/رفض/منح لاحق/إغلاق/إعادة تشغيل/تحديث/تغيير وقت/تجديد token/انقطاع إنترنت/تكرار متأخر؛ `notification_log` يوضح «لم يُجدول / جُدول ولم يظهر / فشل الخادم» | **Not Verified** |
| Phase 4 | `Africa/Cairo`، صيفي/شتوي، تغيير المنطقة من الإعدادات، تذكير يومي متكرر، إعادة تشغيل، تغيير التاريخ، قرب منتصف الليل، لا تكرار/فقدان | **Not Verified** |
| Phase 5 | إضافة مهمة بالاسم فقط تنجح؛ بالحقول الاختيارية تنجح؛ البحث/الفلاتر/الأنواع؛ كل الأقسام القديمة قابلة للوصول؛ لا روابط مكسورة؛ RTL | **Not Verified** (161 test موجودة، أبرزها `BottomTabTest`, `QuickCaptureParserTest` — **Confirmed**) |
| Phase 6 | أحجام شاشة، RTL، login/logout، مزامنة Android↔Web، offline/online، صلاحيات، PWA، توثيق فروق Android-only | **Not Verified** |
| Phase 7 | regression كامل، security review، performance، سجل إصدارات/ترحيلات، rollout تدريجي ومراقبة | **Not Verified** |

**قاعدة Master Prompt:** نجاح البناء لا يكفي؛ يجب تشغيل الاختبارات وتوثيق ما لا يمكن اختباره.

---

## ملحق أ — تحليل المشكلات التسع المعروفة (Output 3.4)

| # | المشكلة | السبب الجذري | التصنيف | الحل المقترح | مخاطر | الاختبارات |
|---|---|---|---|---|---|---|
| 1 | الاسم لا يظهر/لا يتزامن في الإدارة | سبب تاريخي مؤكد: إنشاء uid بـ `apply()` غير ذري + تسجيل单次 بلا retry؛ أُصلح بـ `@Synchronized + commit()` وsignature retry. المتبقي: حد 1000 مستخدم، `runCatching` صامت، أجهزة بإصدار قديم. | **Confirmed** (historical) / **Not Verified** (live) | مراجعة سجل users، إضافة retry متين، رفع/إزالة الحد مع حذف آمن | فقدان أسماء قديمة | مستخدمان، offline، تغيير الاسم |
| 2 | أكثر من جهاز بلا علاقة واضحة | `UUID` لكل تثبيت في SharedPreferences؛ لا حساب ولا ربط بين الأجهزة؛ إعادة التثبيت تغيّر المعرّف. | **Confirmed** | نموذج `User` + `Installation`، ربط اختياري بحساب، عرض «أجهزة هذا المستخدم» | خلط الهويات | جهازان، إعادة تثبيت، حذف |
| 3 | رسالة لمستخدم وصلت للجميع | سجلات قديمة بـ `targetCode` فقط تسقط لقواعد البث؛ Blob عام بن مسار ثابت. | **Confirmed** (code/history) / **Not Verified** (live) | `targetMode` صريح، تنظيف السجلات، Blob خاص/Edge، منع fallback | إسقاط رسائل صحيحة | عزل جهازين + فحص آخر 40 سجلًا |
| 4 | تعذر استهداف موثوق | uid/profile/version قادمة من العميل بلا تحقق؛ لا search by name في السجل؛ الرمز يُطلب مشاركته يدويًا. | **Confirmed** | تحقق_backend، بحث بالاسم/المعرّف، إزالة «إرسال الرمز» من الواجهة | تغيير سلوك معتاد | البحث بالاسم، رمز خاطئ، uid مزيف |
| 5 | إشعارات أحيانًا وأحيانًا لا | exact alarm غير مطلوب، سقف 10، ignoredStreak، تأجيلات غير محفوظة، permission callback فارغ. | **Confirmed** | طلب الصلاحية، رفع السقف/إدارة طوابير، إصلاح ignoredStreak، حفظ التأجيلات | Doze/OEM | مصفوفة الحالات في Phase 3 |
| 6 | تذكيرات مبكرة/متأخرة | inexact fallback، حساب `/DAY` للامتحان، تجاوز السياسات في Dhikr/الصلاة، DST. | **Confirmed** | جدولة محلية دقيقة، استخدام IANA، توحيد السياسة | تغيّر نبرة التنبيهات | exam DST، ليل/نهار، قرب منتصف الليل |
| 7 | تأثر المواعيد بالصيف/الشتاء والمنطقة | `ZoneId.systemDefault()` everywhere، بلا IANA محفوظ، منبّهات epoch، لا إعادة جدولة عند تغيّر القواعد. | **Confirmed** (mechanism) / **Not Verified** (field) | IANA في onboarding/الإعدادات + إعادة جدولة | تعارض مع أجهزة مسافرة | `Africa/Cairo` صيفي/شتوي |
| 8 | كثرة الأقسام وتداخلها | 17 tab id، خمسة مثبتة، دليل أقسام مكرر في «المزيد»، إعدادات موزعة بين مركز الإشعارات والتخصيص. | **Confirmed** | الهيكل الخماسي مع الإبقاء على كل المسارات | كسر اختصارات مستخدمين | اختبار الوصول لكل مسار قديم |
| 9 | الحاجة إلى Web App دون الإضرار بـ Android | لا يوجد web app للتطبيق؛ فقط موقع تحديثات PWA؛ الهوية مجهولة وبيانات محلية ولا مزامنة. | **Confirmed** | Phase 6 بعد حسم المصادقة والبيانات المشتركة | وعود بوظائف Android-only مستحيلة | مصفوفة RTL/offline/PWA/مزامنة |

---

## الأسئلة الضرورية قبل الموافقة (لا أطلب معلومات يمكن استخراجها من المستودع)

1. **Backend target:** هل نُدخل Supabase فعليًا (جداول + RLS + Auth) أم نعتمد صراحةً على Vercel Blob الحالي ونعيد صياغة نموذج الهوية/الرسائل عليه؟
   **السبب:** Master Prompt يفترض Supabase، بينما الكود لا يحتويه؛ هذا القرار يحدد كل ملفات Phase 1–2.
2. **التحقق الحي:** هل تتوفر طريقة آمنة لإجراء فحوص على Vercel/الإدارة وجهاز Android من طرفك دون مشاركة الأسرار أو كلمات المرور معي؟
   **السبب:** كل بنود `Not Verified` أعلاه تحتاج بيئة حية/ADB؛ ولن أطلب ولا أخزّن أسرارًا.
3. **تصنيف «الموظفون»:** هل يقابل `professional` الحالي أم يجب إضافة `employee` كقيمة جديدة في `audienceProfile`؟ (`UserProfile` يملك `student/graduate/professional/retired/general`).
   **السبب:** استهداف الفئات في Phase 2 يحتاج قيمة مستقرة ومتوافقة مع الإصدارات القديمة.
4. **رمز الرسائل الخاص:** هل نُزيل زرَي «نسخ/إرسال» والنص الذي يطلب إعطاء الرمز من `MessageCodeCard`، ونكتفي بالبحث التلقائي بالاسم/المعرّف في الإدارة؟
   **السبب:** القاعدة 6 تمنع طلب إرسال المعرّف يدويًا؛ الرمز الحالي مشتق من معرّف التثبيت ويُشار يدويًا.

---

## التوقف الإلزامي

**توقف هنا.** لم أنفّذ أيًا من Phase 1–7، ولم أغيّر كودًا أو قاعدة بيانات أو ترحيلًا أو إعداد إنتاج. أطلب مراجعة هذا التقرير وإعطاء **موافقة صريحة** قبل بدء أي تنفيذ، مع الإجابة عن الأسئلة الضرورية أعلاه إن أمكن.
