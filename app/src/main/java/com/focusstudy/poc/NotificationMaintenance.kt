package com.focusstudy.poc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Shared permission, rescheduling and diagnostic path for every local alarm.
 *
 * Nothing here talks to a server. The diagnostic rows go to the existing private
 * `notification_log` table and carry only a reason code, the notification kind and an optional
 * task id — never note text, tasks titles or personal identifiers.
 */
internal object NotificationMaintenance {
    const val DIAGNOSTIC_KIND = "diagnostic"
    const val EXACT_ALARM_DENIED = "exact_alarm_denied"
    const val EXACT_ALARM_FAILED = "exact_alarm_failed"
    const val INEXACT_ALARM_FAILED = "inexact_alarm_failed"
    const val RESCHEDULE_FAILED = "reschedule_failed"
    const val PERMISSION_DENIED = "notification_permission_denied"

    private const val TAG = "FocusNotify"
    private const val DIAGNOSTIC_PREFS = "notification_diagnostics"
    private const val THROTTLE_MS = 60 * 60 * 1000L

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            runCatching {
                (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.canScheduleExactAlarms() == true
            }.getOrDefault(false)

    /** Opens the system screen where the person can allow exact alarms; never grants it silently. */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val packageUri = Uri.parse("package:${context.packageName}")
        val request = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(request) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(packageUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /**
     * Uses an exact alarm when the platform allows it and always leaves a working inexact alarm
     * behind. A refused or failed exact alarm is recorded once per hour instead of being swallowed.
     */
    fun schedule(
        context: Context,
        manager: AlarmManager,
        atMillis: Long,
        pending: PendingIntent,
        kind: String,
        taskId: Long? = null
    ) {
        val exact = canScheduleExact(context)
        val result = if (exact) {
            runCatching { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending) }
        } else {
            log(context, EXACT_ALARM_DENIED, kind = kind, taskId = taskId)
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending) }
        }
        // Some OEMs refuse exact alarms even when canScheduleExactAlarms() reports true.
        if (exact && result.isFailure) {
            log(context, EXACT_ALARM_FAILED, kind = kind, taskId = taskId)
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending) }
                .onFailure { log(context, INEXACT_ALARM_FAILED, kind = kind, taskId = taskId) }
        }
        if (!exact && result.isFailure) {
            log(context, INEXACT_ALARM_FAILED, kind = kind, taskId = taskId)
        }
    }

    /** Rebuilds every local schedule after a permission grant, reboot, time change or app update. */
    fun rescheduleAll(context: Context) {
        val app = context.applicationContext
        val steps = listOf<Pair<String, () -> Unit>>(
            "channels" to { ReminderEngine.channels(app) },
            "daily" to { StudyNotificationScheduler.update(app, FocusPreferences.load(app)) },
            "reminders" to { ReminderEngine.refresh(app) },
            "prayer" to { PrayerReminderScheduler.schedule(app) },
            "dhikr" to { DhikrReminderScheduler.schedule(app) },
            "wird" to { WirdReminderScheduler.schedule(app) },
            "skill" to { SkillReminderScheduler.schedule(app) },
            "exercise" to { ExerciseReminderScheduler.schedule(app) },
            "followup" to { AssistantFollowUpScheduler.rescheduleActive(app) },
            "update" to { AppUpdateManager.schedule(app) },
            "session" to { SessionEngine.refresh(app) }
        )
        steps.forEach { (name, step) ->
            runCatching(step).onFailure { error ->
                Log.w(TAG, "reschedule failed: $name", error)
                log(app, "$RESCHEDULE_FAILED:$name")
            }
        }
    }

    /** One reason code per hour per subject; enough to diagnose without filling the table. */
    fun log(context: Context, action: String, kind: String = DIAGNOSTIC_KIND, taskId: Long? = null) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val prefs = app.getSharedPreferences(DIAGNOSTIC_PREFS, Context.MODE_PRIVATE)
        val key = "last:$action:${taskId ?: 0}"
        if (now - prefs.getLong(key, 0L) < THROTTLE_MS) return
        prefs.edit().putLong(key, now).apply()
        runCatching { StudyDb.get(app).logNotification(kind, taskId, now, null, action) }
            .onFailure { Log.w(TAG, "could not write notification diagnostic", it) }
    }
}
