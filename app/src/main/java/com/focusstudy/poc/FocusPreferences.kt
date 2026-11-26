package com.focusstudy.poc

import android.content.Context
import android.content.SharedPreferences

/** How the app picks its palette: follow the phone, or force one mode. */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "حسب النظام"),
    LIGHT("light", "فاتح"),
    DARK("dark", "داكن");

    companion object {
        fun from(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/** Who is organizing with the app; it seeds sections and wording without limiting anything. */
enum class UserProfile(val key: String, val label: String, val hint: String) {
    STUDENT("student", "طالب", "مواد وامتحانات وجلسات مذاكرة"),
    GRADUATE("graduate", "خريج", "مشروع تخرج ومهارات وخطة عمل"),
    PROFESSIONAL("professional", "موظف", "اجتماعات ومهام عمل وأهداف"),
    RETIRED("retired", "متقاعد", "صحة وعائلة ومتابعة يومية"),
    GENERAL("general", "عام", "تنظيم يومي يناسب أي شخص");

    companion object {
        fun from(key: String?): UserProfile = entries.firstOrNull { it.key == key } ?: GENERAL
    }
}

/** كيف تُعرض الصورة الشخصية: كرتوني مسطّح، أو «ثلاثي الأبعاد»، أو الصورة كما هي. */
enum class AvatarMode(val key: String, val label: String) {
    CARTOON("cartoon", "كرتوني"),
    DEPTH_3D("depth3d", "ثلاثي الأبعاد"),
    PHOTO("photo", "صورتي كما هي");

    companion object {
        fun from(key: String?): AvatarMode = entries.firstOrNull { it.key == key } ?: CARTOON
    }
}

data class FocusPreferences(
    val studentName: String = "",
    val dailyGoalMinutes: Int = 120,
    val defaultSessionMinutes: Int = 25,
    val accentIndex: Int = 0,
    val textScale: Float = 1f,
    val dailyReminderEnabled: Boolean = true,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val remindersPerDay: Int = 1,
    val permanentBlockedPackages: Set<String> = emptySet(),
    val onboardingCompleted: Boolean = false,
    /** Generated cartoon shown in the profile, Today companion and achievements. */
    val avatarPath: String = "",
    /** Original photo copy, kept only in app-private files so the cartoon can be regenerated. */
    val avatarSourcePath: String = "",
    /** Selected on-device cartoon style (0–3). */
    val avatarStyle: Int = 0,
    /** Face likeness profile (ML Kit, bundled model) as JSON; empty means no face was detected. */
    val avatarFaceProfileJson: String = "",
    /** كيف تُعرض الصورة الشخصية: كرتوني / ثلاثي الأبعاد / الصورة كما هي. */
    val avatarMode: AvatarMode = AvatarMode.CARTOON,
    val companionStyle: Int = 0,
    val generalNotificationsEnabled: Boolean = true,
    /**
     * رسائل الفريق لها مفتاحها المستقل منذ المرحلة 4. قبلها كان مفتاح «رسائل الفريق» هو نفسه
     * `generalNotificationsEnabled` الذي تشترطه رسالتا الصباح/المساء، فإيقاف الفريق كان يُسكت
     * التشجيع دون أن يُقال ذلك. `generalNotificationsEnabled` نفسه بقي مخزّنًا للتوافق فقط
     * ولم يعد يتحكم في أي إرسال.
     */
    val teamMessagesEnabled: Boolean = true,
    val morningMessageEnabled: Boolean = true,
    val eveningMessageEnabled: Boolean = true,
    val morningMessageHour: Int = 8,
    val eveningMessageHour: Int = 20,
    val tonePreference: TonePreference = TonePreference.BALANCED,
    val smartRemindersEnabled: Boolean = true,
    val notificationDensity: NotificationDensity = NotificationDensity.BALANCED,
    val quietHoursEnabled: Boolean = true,
    val leadMinutes: Int = 30,
    val legacyReminders: Boolean = false,
    val quietStartHour: Int = 23,
    val quietEndHour: Int = 7,
    val reduceMotion: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val profile: UserProfile = UserProfile.GENERAL,
    /** Saved IANA id for all wall-clock work; empty means follow the device. */
    val timezoneId: String = "",
    /** Ids of the sections kept in the bottom bar, in display order. */
    val bottomTabs: List<String> = BottomTab.DEFAULT
) {
    /** Parsed face profile, or null when no face was detected yet. */
    val avatarFaceProfile: FaceProfile?
        get() = FaceProfile.fromJson(avatarFaceProfileJson)

    companion object {
        internal const val FILE = "focus_study_preferences"

        /**
         * Display name, phase 7b: the database (user_profile, v17) is the source of truth
         * and the preference is a synchronous mirror/fallback. First run after the update,
         * a name that only exists in preferences is copied into the database once, so
         * nothing typed before the update is lost; afterwards the database row wins.
         */
        private fun loadStudentName(context: Context, prefs: SharedPreferences): String {
            val prefsName = prefs.getString("student_name", "").orEmpty()
            val dbName = runCatching { StudyDb.get(context).userName() }.getOrNull()
            if (dbName == null && prefsName.isNotBlank()) {
                runCatching { StudyDb.get(context).saveUserName(prefsName) }
                return prefsName
            }
            return dbName ?: prefsName
        }

        fun load(context: Context): FocusPreferences {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return FocusPreferences(
                studentName = loadStudentName(context, prefs),
                dailyGoalMinutes = prefs.getInt("daily_goal_minutes", 120).coerceIn(30, 720),
                defaultSessionMinutes = prefs.getInt("session_minutes", 25).coerceIn(5, 120),
                accentIndex = prefs.getInt("accent_index", 0).coerceIn(0, FocusColors.accents.lastIndex),
                textScale = prefs.getFloat("text_scale", 1f).coerceIn(0.9f, 1.2f),
                dailyReminderEnabled = prefs.getBoolean("daily_reminder_enabled", true),
                reminderHour = prefs.getInt("reminder_hour", 20).coerceIn(0, 23),
                reminderMinute = prefs.getInt("reminder_minute", 0).coerceIn(0, 59),
                remindersPerDay = prefs.getInt("reminders_per_day", 1).coerceIn(1, 3),
                permanentBlockedPackages = prefs.getStringSet("permanent_blocked_packages", emptySet()).orEmpty().toSet(),
                onboardingCompleted = prefs.getBoolean(
                    "onboarding_completed",
                    prefs.getString("student_name", "").orEmpty().isNotBlank()
                ),
                avatarPath = prefs.getString("avatar_path", "").orEmpty(),
                avatarSourcePath = prefs.getString("avatar_source_path", "").orEmpty(),
                avatarStyle = prefs.getInt("avatar_style", 0).coerceIn(0, CartoonAvatar.STYLES.lastIndex),
                avatarFaceProfileJson = prefs.getString("avatar_face_profile_json", "").orEmpty(),
                avatarMode = AvatarMode.from(prefs.getString("avatar_mode", "cartoon")),
                companionStyle = prefs.getInt("companion_style", 0).coerceIn(0, 3),
                generalNotificationsEnabled = prefs.getBoolean("general_notifications_enabled", prefs.getBoolean("daily_reminder_enabled", true)),
                // First run after the split: inherit the old shared switch, so a person who had
                // silenced team messages does not get them back without asking.
                teamMessagesEnabled = prefs.getBoolean("team_messages_enabled", prefs.getBoolean("general_notifications_enabled", true)),
                morningMessageEnabled = prefs.getBoolean("morning_message_enabled", true),
                eveningMessageEnabled = prefs.getBoolean("evening_message_enabled", true),
                morningMessageHour = prefs.getInt("morning_message_hour", 8).coerceIn(6, 11),
                eveningMessageHour = prefs.getInt("evening_message_hour", 20).coerceIn(17, 23),
                tonePreference = TonePreference.from(prefs.getString("tone_preference", "balanced")),
                smartRemindersEnabled = prefs.getBoolean("smart_reminders_enabled", true),
                notificationDensity = NotificationDensity.from(prefs.getString("notification_density", "balanced")),
                quietHoursEnabled = prefs.getBoolean("quiet_hours_enabled", true),
                leadMinutes = prefs.getInt("reminder_lead_minutes", 30).coerceIn(5, 120),
                legacyReminders = prefs.getBoolean("legacy_reminders", false),
                quietStartHour = prefs.getInt("quiet_start_hour", 23).coerceIn(0, 23),
                quietEndHour = prefs.getInt("quiet_end_hour", 7).coerceIn(0, 23),
                reduceMotion = prefs.getBoolean("reduce_motion", false),
                themeMode = ThemeMode.from(prefs.getString("theme_mode", "system")),
                profile = UserProfile.from(prefs.getString("profile", "general")),
                timezoneId = AppTimeZone.sanitize(prefs.getString(AppTimeZone.PREF_KEY, null)),
                bottomTabs = BottomTab.normalize(
                    prefs.getString("bottom_tabs", null)
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: BottomTab.DEFAULT
                )
            )
        }

        fun save(context: Context, value: FocusPreferences) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("student_name", value.studentName.trim())
                .putInt("daily_goal_minutes", value.dailyGoalMinutes.coerceIn(30, 720))
                .putInt("session_minutes", value.defaultSessionMinutes.coerceIn(5, 120))
                .putInt("accent_index", value.accentIndex.coerceIn(0, FocusColors.accents.lastIndex))
                .putFloat("text_scale", value.textScale.coerceIn(0.9f, 1.2f))
                .putBoolean("daily_reminder_enabled", value.dailyReminderEnabled)
                .putInt("reminder_hour", value.reminderHour.coerceIn(0, 23))
                .putInt("reminder_minute", value.reminderMinute.coerceIn(0, 59))
                .putInt("reminders_per_day", value.remindersPerDay.coerceIn(1, 3))
                .putStringSet("permanent_blocked_packages", value.permanentBlockedPackages.toSet())
                .putBoolean("onboarding_completed", value.onboardingCompleted)
                .putString("avatar_path", value.avatarPath)
                .putString("avatar_source_path", value.avatarSourcePath)
                .putInt("avatar_style", value.avatarStyle.coerceIn(0, CartoonAvatar.STYLES.lastIndex))
                .putString("avatar_face_profile_json", value.avatarFaceProfileJson)
                .putString("avatar_mode", value.avatarMode.key)
                .putInt("companion_style", value.companionStyle.coerceIn(0, 3))
                .putBoolean("general_notifications_enabled", value.generalNotificationsEnabled)
                .putBoolean("team_messages_enabled", value.teamMessagesEnabled)
                .putBoolean("morning_message_enabled", value.morningMessageEnabled)
                .putBoolean("evening_message_enabled", value.eveningMessageEnabled)
                .putInt("morning_message_hour", value.morningMessageHour.coerceIn(6, 11))
                .putInt("evening_message_hour", value.eveningMessageHour.coerceIn(17, 23))
                .putString("tone_preference", value.tonePreference.key)
                .putBoolean("smart_reminders_enabled", value.smartRemindersEnabled)
                .putString("notification_density", value.notificationDensity.key)
                .putBoolean("quiet_hours_enabled", value.quietHoursEnabled)
                .putInt("reminder_lead_minutes", value.leadMinutes.coerceIn(5, 120))
                .putBoolean("legacy_reminders", value.legacyReminders)
                .putInt("quiet_start_hour", value.quietStartHour.coerceIn(0, 23))
                .putInt("quiet_end_hour", value.quietEndHour.coerceIn(0, 23))
                .putBoolean("reduce_motion", value.reduceMotion)
                .putString("theme_mode", value.themeMode.key)
                .putString("profile", value.profile.key)
                .putString(AppTimeZone.PREF_KEY, AppTimeZone.sanitize(value.timezoneId))
                .putString("bottom_tabs", BottomTab.normalize(value.bottomTabs).joinToString(","))
                .apply()
            // The database is the source of truth for the name (user_profile, v17); the
            // preference above stays as a synchronous mirror and a fallback. A failure
            // here never blocks saving preferences, and the next save retries it.
            runCatching { StudyDb.get(context).saveUserName(value.studentName) }
        }
    }
}
