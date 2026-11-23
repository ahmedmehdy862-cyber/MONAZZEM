package com.focusstudy.poc

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One place for everything that can interrupt the person.
 *
 * Each kind of notification has a switch, the system permission has a status, and one button sends a
 * real notification so «الإشعارات لا تصل» becomes something the phone answers, not a guess.
 */
@Composable
internal fun NotificationCenterScreen(
    preferences: FocusPreferences,
    onBack: () -> Unit,
    onSave: (FocusPreferences) -> Unit,
    onRequestNotifications: () -> Unit
) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(AppNotifications.canPost(context)) }
    var exactAlarms by remember { mutableStateOf(NotificationMaintenance.canScheduleExact(context)) }
    var diagnostics by remember { mutableStateOf(emptyList<NotificationRecord>()) }
    var sent by remember { mutableStateOf(false) }
    // Prayer switches live in their own preferences file; this revision makes the section reread
    // them after each change without rebuilding the whole screen.
    var prayerRevision by remember { mutableStateOf(0) }

    // The permission dialog is async: re-check when the activity resumes instead of immediately
    // after the request click, which used to leave the card stuck on "denied". A later grant of
    // exact alarms is handled the same way, then every local alarm is rebuilt.
    val lifecycleOwner = LocalLifecycleOwner.current
    val previousPermissionState = remember { mutableStateOf(allowed to exactAlarms) }
    val refreshPermission = rememberUpdatedState {
        val nowAllowed = AppNotifications.canPost(context)
        val nowExact = NotificationMaintenance.canScheduleExact(context)
        val previous = previousPermissionState.value
        allowed = nowAllowed
        exactAlarms = nowExact
        previousPermissionState.value = nowAllowed to nowExact
        if (nowAllowed != previous.first || nowExact != previous.second) {
            NotificationMaintenance.rescheduleAll(context)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermission.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        diagnostics = withContext(Dispatchers.IO) {
            runCatching {
                StudyDb.get(context.applicationContext)
                    .notificationDiagnosticsSince(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)
                    .take(6)
            }.getOrDefault(emptyList())
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("الإشعارات", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            StatusCard(allowed, exactAlarms, onAllow = onRequestNotifications)
            Spacer(Modifier.height(12.dp))
            PrayerCard(revision = prayerRevision, onChanged = { prayerRevision++ })
            Spacer(Modifier.height(12.dp))
            BatteryGuidanceCard()
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("ماذا يصلك؟", style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    PreferenceSwitchRow(
                        "تذكير المهام",
                        "عدد المهام المتأخرة وأقرب خطوة، ويتوقف أثناء جلسة التركيز.",
                        preferences.smartRemindersEnabled
                    ) { onSave(preferences.copy(smartRemindersEnabled = it)) }
                    PreferenceSwitchRow(
                        "رسالة الصباح",
                        "نية اليوم وما ينتظرك، في الساعة التي تختارها.",
                        preferences.morningMessageEnabled
                    ) { onSave(preferences.copy(morningMessageEnabled = it)) }
                    PreferenceSwitchRow(
                        "رسالة المساء",
                        "مراجعة قصيرة لما أنجزته ودعوة لتجهيز الغد.",
                        preferences.eveningMessageEnabled
                    ) { onSave(preferences.copy(eveningMessageEnabled = it)) }
                    PreferenceSwitchRow(
                        "احترام ساعات الهدوء",
                        "لا شيء يصل في أوقات الراحة الليلية.",
                        preferences.quietHoursEnabled
                    ) { onSave(preferences.copy(quietHoursEnabled = it)) }
                    PreferenceSwitchRow(
                        "رسائل الفريق",
                        "إعلانات وتحديثات الخدمة بدون تحديث التطبيق.",
                        preferences.generalNotificationsEnabled
                    ) { onSave(preferences.copy(generalNotificationsEnabled = it)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            TestCard(sent, allowed) {
                StudyNotificationScheduler.notifyTest(context)
                sent = true
            }
            Spacer(Modifier.height(12.dp))
            DiagnosticCard(diagnostics)
            Spacer(Modifier.height(12.dp))
            Text(
                "إن أرسلت إشعارًا ولم يظهر: الإذن ممنوع، أو المنبّهات الدقيقة موقوفة من النظام، أو الهاتف في وضع عدم الإزعاج، أو أوقفت إعدادات البطارية تنبيهات التطبيق (وانظر إرشادات شاومي أعلاه).",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextMuted
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Part 7: one compact section for the two prayer alerts of every prayer. All values are private
 * on-device preferences; changing any of them rewrites the seven-day exact-alarm window at once.
 */
@Composable
private fun PrayerCard(revision: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val settings = remember(revision) { PrayerAlertPrefs.load(context) }
    val master = remember(revision) { PrayerPrefs.enabled(context) }
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("الصلاة", style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(
                "تنبيه عند دخول الوقت وتنبيه قبله، لكل واحد مفتاحه وصوته. يُجدول الأسبوع القادم كاملًا حتى لو لم تفتح التطبيق.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            Spacer(Modifier.height(6.dp))
            PreferenceSwitchRow(
                "تنبيهات الصلاة",
                "المفتاح الرئيسي للمواقيت والتنبيهات معًا.",
                master
            ) {
                PrayerPrefs.setEnabled(context, it)
                PrayerNotificationScheduler.schedule(context)
                onChanged()
            }
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.HorizontalDivider(color = FocusColors.SurfaceHigh)
            PrayerAccuracy.KEYS.forEachIndexed { index, key ->
                val rule = settings.rule(key)
                val name = PrayerAccuracy.NAMES[index]
                Spacer(Modifier.height(8.dp))
                Text(name, style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "عند وقت الصلاة",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary
                    )
                    TextButton(onClick = {
                        val next = if (rule.atSound == PrayerAlertSound.ADHAN) PrayerAlertSound.TONE else PrayerAlertSound.ADHAN
                        PrayerAlertPrefs.setSound(context, key, PrayerAlertType.AT, next)
                        onChanged()
                    }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(rule.atSound.label, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(
                        checked = rule.atEnabled,
                        onCheckedChange = {
                            PrayerAlertPrefs.setAtEnabled(context, key, it)
                            PrayerNotificationScheduler.schedule(context)
                            onChanged()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                            checkedTrackColor = FocusColors.Green,
                            uncheckedThumbColor = FocusColors.TextMuted,
                            uncheckedTrackColor = FocusColors.SurfaceHigh
                        )
                    )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("قبلها", style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary)
                    Spacer(Modifier.width(6.dp))
                    PrayerAlertRule.BEFORE_OPTIONS.forEach { option ->
                        FilterChip(
                            selected = rule.beforeMinutes == option,
                            onClick = {
                                PrayerAlertPrefs.setBeforeMinutes(context, key, option)
                                PrayerNotificationScheduler.schedule(context)
                                onChanged()
                            },
                            label = { Text(if (option == 0) "إيقاف" else "$option د") }
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = {
                        val next = if (rule.beforeSound == PrayerAlertSound.ADHAN) PrayerAlertSound.TONE else PrayerAlertSound.ADHAN
                        PrayerAlertPrefs.setSound(context, key, PrayerAlertType.BEFORE, next)
                        onChanged()
                    }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(rule.beforeSound.label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.HorizontalDivider(color = FocusColors.SurfaceHigh)
            Spacer(Modifier.height(8.dp))
            Text("جرّب الآن", style = MaterialTheme.typography.labelMedium, color = FocusColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Button(onClick = { PrayerNotificationScheduler.notifyTest(context, PrayerAlertType.AT) }) {
                    Text("تنبيه وقت الصلاة")
                }
                OutlinedButton(onClick = { PrayerNotificationScheduler.notifyTest(context, PrayerAlertType.BEFORE) }) {
                    Text("تنبيه قبل الصلاة")
                }
            }
        }
    }
}

/** Short, honest battery guidance — the usual cause of missed alarms on Xiaomi/MIUI devices. */
@Composable
private fun BatteryGuidanceCard() {
    val context = LocalContext.current
    var ignoring by remember { mutableStateOf(PrayerBatteryGuidance.isIgnoringOptimizations(context)) }
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("الأذان والهاتف مغلق", style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "التنبيهات تصل بالمنبّهات الدقيقة حتى مع إغلاق التطبيق وفي وضع السكون العميق (Doze). إن تأخرت، فالسبب غالبًا توفير البطارية.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            Text(
                "على شاومي / MIUI: اسمح بالتشغيل التلقائي (Autostart) وصلاحية التشغيل في الخلفية لمُنظِّم من إعدادات التطبيق، وثبّته في ذاكرة الهاتف إن وُجد الخيار.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (ignoring) "تحسين البطارية لا يوقف مُنظِّم الآن ✓" else "استثناء مُنظِّم من تحسين البطارية موصى به لضمان الأذان.",
                style = MaterialTheme.typography.labelSmall,
                color = if (ignoring) FocusColors.Green else FocusColors.Amber
            )
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    PrayerBatteryGuidance.openBatterySettings(context)
                    ignoring = PrayerBatteryGuidance.isIgnoringOptimizations(context)
                }) { Text("استثناء من البطارية") }
                TextButton(onClick = { PrayerBatteryGuidance.openAppSettings(context) }) { Text("إعدادات التطبيق") }
            }
        }
    }
}

@Composable
private fun StatusCard(allowed: Boolean, exactAlarms: Boolean, onAllow: () -> Unit) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                if (allowed) "الإشعارات مسموحة ✓" else "الإشعارات ممنوعة الآن",
                style = MaterialTheme.typography.titleSmall,
                color = if (allowed) FocusColors.Blue else FocusColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (allowed) "سيصلك تذكير المهام ورسائل الفريق في شريط الإشعارات."
                else "بدون الإذن لن يظهر أي تذكير. اسمح به من هنا أو من إعدادات النظام.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!allowed) Button(onClick = onAllow) { Text("اسمح بالإشعارات") }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("إعدادات النظام") }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (exactAlarms) "المنبّهات الدقيقة مسموحة ✓" else "المنبّهات الدقيقة ممنوعة — قد تتأخر بعض التذكيرات",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exactAlarms) FocusColors.Blue else FocusColors.TextSecondary
                )
                if (!exactAlarms) {
                    TextButton(onClick = { NotificationMaintenance.openExactAlarmSettings(context) }) {
                        Text("اسمح بالمنبّهات الدقيقة")
                    }
                }
            }
        }
    }
}

@Composable
private fun TestCard(sent: Boolean, allowed: Boolean, onSend: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("جرّبها الآن", style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(
                "اضغط الزر ثم انزل بشريط الإشعارات: يجب أن تجد «الإشعارات تعمل ✓»، والضغط عليها يفتح مهامي.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSend) { Text("أرسل إشعارًا تجريبيًا") }
                Spacer(Modifier.width(10.dp))
                if (sent) Text(
                    if (allowed) "أُرسل — افحص الشريط" else "الإذن ممنوع",
                    style = MaterialTheme.typography.labelSmall,
                    color = FocusColors.TextMuted
                )
            }
        }
    }
}

@Composable
private fun DiagnosticCard(events: List<NotificationRecord>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("سجل تشخيصي محلي", style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            if (events.isEmpty()) {
                Text("لا توجد ملاحظات في آخر ٧ أيام.", style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary)
                return@Column
            }
            events.forEach { event ->
                Text(
                    "• ${diagnosticLabel(event.action)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
            }
        }
    }
}

private fun diagnosticLabel(action: String): String = when {
    action.startsWith("deferred:") -> "تأجيل آمن: ${action.removePrefix("deferred:")}"
    action == NotificationMaintenance.EXACT_ALARM_DENIED -> "المنبّهات الدقيقة غير مسموحة؛ استُخدم بديل غير دقيق."
    action == NotificationMaintenance.EXACT_ALARM_FAILED -> "فشل منبّه دقيق؛ استُخدم بديل غير دقيق."
    action == NotificationMaintenance.INEXACT_ALARM_FAILED -> "تعذّر جدولة منبّه بديل."
    action == NotificationMaintenance.PERMISSION_DENIED -> "وصل تذكير والإذن ممنوع؛ أُجّل retry محلي."
    action.startsWith(NotificationMaintenance.RESCHEDULE_FAILED) -> "تعذّت إعادة جدولة: ${action.substringAfter(':')}"
    else -> action
}
