package com.focusstudy.poc

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A short guided first run: name, daily goal, one first task. Three steps maximum, then the person
 * lands on Today. Everything else (reminder times, notification switches) keeps its sensible
 * default and stays editable in Settings — no second wall of options before the app is useful.
 */
@Composable
internal fun FirstLaunchSetupScreen(
    current: FocusPreferences,
    onComplete: (FocusPreferences) -> Unit,
    onRequestNotifications: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(0) }
    var name by rememberSaveable { mutableStateOf(current.studentName) }
    var goal by rememberSaveable { mutableStateOf(current.dailyGoalMinutes.toFloat()) }
    var avatar by rememberSaveable { mutableStateOf(current.avatarPath) }
    var avatarSource by rememberSaveable { mutableStateOf(current.avatarSourcePath) }
    var avatarStyle by rememberSaveable { mutableIntStateOf(current.avatarStyle) }
    var style by rememberSaveable { mutableStateOf(current.companionStyle) }
    var profile by rememberSaveable { mutableStateOf(current.profile) }
    var timezoneId by rememberSaveable { mutableStateOf(current.timezoneId.ifBlank { AppTimeZone.deviceId() }) }
    var firstTask by rememberSaveable { mutableStateOf("") }
    var firstMinutes by rememberSaveable { mutableIntStateOf(30) }

    val titles = listOf("ما اسمك؟", "هدفك اليومي", "أول مهمة لك")
    val hints = listOf(
        "خطوة واحدة فقط لنعرف كيف نناديك. الباقي اختياري ويمكن تغييره لاحقًا.",
        "نبدأ بهدف بسيط، ويمكنك تعديله في أي وقت من الإعدادات.",
        "أضف مهمة واحدة لتبدأ يومك. عند المتابعة نعرض لك شرحًا عن إذن الإشعارات اللازم للأذان والتنبيهات، ونطلبه مرة واحدة فقط إن وافقت، ثم لا نطلبه مجددًا."
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("مُنظِّم", style = MaterialTheme.typography.labelLarge, color = FocusColors.onTint(FocusColors.Blue))
        Text(titles[page], style = MaterialTheme.typography.headlineSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.Bold)
        Text("الخطوة ${page + 1} من 3 · ${hints[page]}", style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (page) {
                0 -> {
                    item {
                        SetupCard("اسمك", "يظهر في الترحيب والرسائل التشجيعية على جهازك فقط.") {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it.take(32) },
                                label = { Text("الاسم الذي تحب أن نناديك به") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            )
                            if (name.trim().length < 2) Text("اكتب اسمًا من حرفين على الأقل للمتابعة.", color = FocusColors.onTint(FocusColors.Amber))
                        }
                    }
                    item {
                        SetupCard("من أنت؟", "نجهّز لك أقسامًا مناسبة. يمكنك تغييرها في أي وقت.") {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                UserProfile.entries.forEach { option ->
                                    FilterChip(
                                        selected = profile == option,
                                        onClick = { profile = option },
                                        label = { Text(option.label) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(profile.hint, color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        SetupCard("صورتك (اختياري)", "صورتك تبقى على الجهاز ولا تُرفع لأي خدمة.") {
                            AvatarChooser(
                                avatar = AvatarImage(avatar, avatarSource, avatarStyle),
                                name = name,
                                companionStyle = style,
                                onAvatarChanged = { image ->
                                    avatar = image.path
                                    avatarSource = image.sourcePath
                                    avatarStyle = image.style
                                }
                            )
                            Spacer(Modifier.height(10.dp))
                            CompanionStylePicker(style, onSelect = { style = it })
                        }
                    }
                }
                1 -> {
                    item {
                        SetupCard("وقت التركيز اليومي", "نبدأ بهدف بسيط، ثم تنمو تدريجيًا.") {
                            Text("${goal.toInt()} دقيقة تركيز يوميًا", color = FocusColors.TextSecondary)
                            Slider(value = goal, onValueChange = { goal = it }, valueRange = 30f..360f, steps = 10)
                        }
                    }
                    item {
                        SetupCard("منطقتك الزمنية", "تضبط مواقيت الصلاة والأذكار وتتحرك معك عند السفر أو التوقيت الصيفي.") {
                            TimeZonePicker(timezoneId, onSelected = { timezoneId = it })
                        }
                    }
                }
                2 -> {
                    item {
                        SetupCard("مهمة واحدة للبداية", "اكتب ما تريد إنجازه اليوم. يمكنك تخطي هذه الخطوة.") {
                            OutlinedTextField(
                                value = firstTask,
                                onValueChange = { firstTask = it.take(80) },
                                label = { Text("مثال: مراجعة محاضرة الرياضيات") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("المدة", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15, 30, 60, 90).forEach { minutes ->
                                    FilterChip(
                                        selected = firstMinutes == minutes,
                                        onClick = { firstMinutes = minutes },
                                        label = { Text("$minutes د") }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (page > 0) TextButton(onClick = { page -= 1 }, modifier = Modifier.weight(1f)) { Text("رجوع") }
            Button(
                onClick = {
                    when {
                        page < 2 -> page += 1
                        else -> {
                            val updated = current.copy(
                                studentName = name.trim(),
                                dailyGoalMinutes = goal.toInt(),
                                avatarPath = avatar,
                                avatarSourcePath = avatarSource,
                                avatarStyle = avatarStyle,
                                companionStyle = style,
                                profile = profile,
                                timezoneId = AppTimeZone.sanitize(timezoneId),
                                onboardingCompleted = true
                            )
                            onComplete(updated)
                            onRequestNotifications()
                            val title = firstTask.trim()
                            if (title.length >= 2) {
                                scope.launch(Dispatchers.IO) {
                                    TaskRepository.get(context).add(
                                        title = title,
                                        plannedMinutes = firstMinutes,
                                        blockedPackages = emptySet(),
                                        category = TaskCategory.STUDY,
                                        priority = TaskPriority.MEDIUM,
                                        dueAt = java.time.LocalDate.now().atTime(23, 59)
                                            .atZone(AppTimeZone.zone(context)).toInstant().toEpochMilli()
                                    )
                                    SessionEngine.notifyTasksChanged()
                                }
                            }
                        }
                    }
                },
                enabled = page != 0 || name.trim().length >= 2,
                modifier = Modifier.weight(2f),
                shape = RoundedCornerShape(16.dp)
            ) { Text(if (page < 2) "متابعة" else "ابدأ رحلتك") }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CompanionStylePicker(selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("اختار شخصية رفيقك", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CompanionStyle.all.forEachIndexed { index, style ->
                FilterChip(
                    selected = selected == index,
                    onClick = { onSelect(index) },
                    label = { Text("${style.mark} ${style.title}") }
                )
            }
        }
    }
}

@Composable
internal fun PreferenceSwitchRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = FocusColors.TextPrimary)
            Text(description, color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = FocusColors.onAccentFill(FocusColors.onTint(FocusColors.Green)),
                checkedTrackColor = FocusColors.onTint(FocusColors.Green),
                uncheckedThumbColor = FocusColors.TextMuted,
                uncheckedTrackColor = FocusColors.SurfaceHigh,
                disabledCheckedTrackColor = FocusColors.SurfaceHigh,
                disabledUncheckedTrackColor = FocusColors.SurfaceHigh
            )
        )
    }
}

@Composable
internal fun TimeChoice(context: Context, label: String, hour: Int, minHour: Int, maxHour: Int, onSelected: (Int) -> Unit) {
    TextButton(onClick = {
        TimePickerDialog(context, { _, chosen, _ -> onSelected(chosen.coerceIn(minHour, maxHour)) }, hour, 0, false).show()
    }) { Text("$label · ${String.format(java.util.Locale.US, "%02d:00", hour)}") }
}

@Composable
private fun SetupCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = FocusColors.Surface), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary)
            content()
        }
    }
}
