package com.focusstudy.poc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.TimePickerDialog
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val SUPPORT_USERNAME = "techsup402"

@Composable
internal fun PrayerTimesScreen(
    onBack: () -> Unit,
    timezoneId: String = "",
    onTimeZoneChanged: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableStateOf(0) }
    fun coordinateText(value: Double) = String.format(java.util.Locale.US, "%.5f", value)
    var latitude by rememberSaveable { mutableStateOf(if (PrayerPrefs.latitude(context).isFinite()) coordinateText(PrayerPrefs.latitude(context)) else "") }
    var longitude by rememberSaveable { mutableStateOf(if (PrayerPrefs.longitude(context).isFinite()) coordinateText(PrayerPrefs.longitude(context)) else "") }
    var method by rememberSaveable { mutableStateOf(PrayerPrefs.method(context)) }
    var asrSchool by rememberSaveable { mutableStateOf(PrayerPrefs.asrSchool(context)) }
    var city by rememberSaveable { mutableStateOf(PrayerPrefs.cityName(context)) }
    var enabled by rememberSaveable { mutableStateOf(PrayerPrefs.enabled(context)) }
    var dhikrEnabled by rememberSaveable { mutableStateOf(DhikrPrefs.enabled(context)) }
    var timezone by rememberSaveable(timezoneId) { mutableStateOf(timezoneId.ifBlank { AppTimeZone.deviceId() }) }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch {
            loading = true
            val location = currentApproximateLocation(context)
            loading = false
            if (location != null) {
                latitude = coordinateText(location.latitude); longitude = coordinateText(location.longitude)
                city = "موقعي"; PrayerPrefs.saveLocation(context, location.latitude, location.longitude, city)
                PrayerReminderScheduler.schedule(context); refresh++
                message = "تم حفظ الموقع على جهازك. لا نرسل إحداثياتك إلى أي خادم."
            } else message = "تعذر تحديد الموقع الآن. أدخل الإحداثيات يدويًا أو حاول قرب نافذة."
        } else message = "لم يُمنح إذن الموقع. يمكنك إدخال الإحداثيات يدويًا."
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) NotificationMaintenance.rescheduleAll(context.applicationContext)
        else NotificationMaintenance.log(context, NotificationMaintenance.PERMISSION_DENIED)
    }
    val hasLocation = PrayerPrefs.latitude(context).isFinite() && PrayerPrefs.longitude(context).isFinite()
    val prayers = remember(refresh) { runCatching { PrayerCalculator.today(context) }.getOrNull() }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("مواقيت الصلاة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                PreferenceCard("المنطقة الزمنية", "معرّف IANA محفوظ محليًا. يُستخدم للمواقيت والأذكار وبداية اليوم في التذكيرات، ولا يُرسل لأي خادم.") {
                    TimeZonePicker(timezone, onSelected = { selected ->
                        timezone = selected
                        onTimeZoneChanged(selected)
                        refresh++
                    })
                    Text(
                        "الآن: ${PrayerCalculator.format(System.currentTimeMillis(), AppTimeZone.resolve(timezone))}",
                        color = FocusColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                PreferenceCard("مواقيت اليوم", "حساب فلكي محلي يتحدث حسب التاريخ والمنطقة الزمنية المحفوظة. التعديل بالدقائق يبقى على جهازك.") {
                    if (PrayerPrefs.cityName(context).isNotBlank()) {
                        Text("الموقع المحفوظ: ${PrayerPrefs.cityName(context)}", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (prayers == null) {
                        Text("حدد موقعك التقريبي لعرض المواقيت.", color = FocusColors.TextSecondary)
                    } else prayers.forEachIndexed { index, prayer ->
                        val key = PrayerAccuracy.KEYS[index]
                        val tune = PrayerPrefs.fineTune(context, key)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(prayer.name, Modifier.weight(1f), color = FocusColors.TextPrimary, fontWeight = FontWeight.Medium)
                            TextButton(onClick = {
                                PrayerPrefs.saveFineTune(context, key, tune - 1)
                                PrayerReminderScheduler.schedule(context); refresh++
                            }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("−") }
                            Text(PrayerCalculator.format(context, prayer.atMillis), color = FocusColors.onTint(FocusColors.Blue))
                            TextButton(onClick = {
                                PrayerPrefs.saveFineTune(context, key, tune + 1)
                                PrayerReminderScheduler.schedule(context); refresh++
                            }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("+") }
                            Text(if (tune == 0) "" else "${if (tune > 0) "+" else ""}$tune", Modifier.padding(start = 4.dp), color = FocusColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                        if (prayer != prayers.last()) androidx.compose.material3.HorizontalDivider(color = FocusColors.SurfaceHigh)
                    }
                }
            }
            item {
                PreferenceCard("الموقع وطريقة الحساب", "نطلب موقعًا تقريبيًا فقط عند ضغطك على الزر. الحساب والإحداثيات يبقيان على جهازك.") {
                    Button(
                        onClick = {
                            if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                scope.launch {
                                    loading = true
                                    val location = currentApproximateLocation(context)
                                    loading = false
                                    if (location != null) {
                                        latitude = coordinateText(location.latitude); longitude = coordinateText(location.longitude)
                                        city = "موقعي"; PrayerPrefs.saveLocation(context, location.latitude, location.longitude, city)
                                        PrayerReminderScheduler.schedule(context); refresh++
                                        message = "تم حفظ الموقع على جهازك."
                                    } else message = "تعذر تحديد الموقع؛ أدخل الإحداثيات يدويًا."
                                }
                            } else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), enabled = !loading
                    ) { Text(if (loading) "جارٍ تحديد الموقع…" else "استخدم موقعي التقريبي") }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(latitude, { latitude = it }, label = { Text("خط العرض") }, modifier = Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                        OutlinedTextField(longitude, { longitude = it }, label = { Text("خط الطول") }, modifier = Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("طريقة الحساب", color = FocusColors.TextSecondary)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PrayerAccuracy.METHODS.forEach { (key, label) ->
                            FilterChip(selected = method == key, onClick = {
                                method = key; PrayerPrefs.saveMethod(context, key); PrayerReminderScheduler.schedule(context); refresh++
                            }, label = { Text(label) })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("مدرسة العصر (الشافعي هو الافتراضي في مصر)", color = FocusColors.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PrayerAccuracy.ASR_SCHOOLS.forEach { school ->
                            FilterChip(selected = asrSchool == school, onClick = {
                                asrSchool = school; PrayerPrefs.saveAsrSchool(context, school)
                                PrayerReminderScheduler.schedule(context); refresh++
                            }, label = { Text(school.label) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("أو اختر مدينة مصرية دون GPS", color = FocusColors.TextSecondary)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PrayerCities.EGYPT.forEach { chosen ->
                            FilterChip(selected = city == chosen.name, onClick = {
                                city = chosen.name
                                latitude = coordinateText(chosen.latitude); longitude = coordinateText(chosen.longitude)
                                PrayerPrefs.saveLocation(context, chosen.latitude, chosen.longitude, chosen.name)
                                PrayerPrefs.saveMethod(context, method)
                                PrayerReminderScheduler.schedule(context); refresh++
                                message = "تم حفظ ${chosen.name} على الجهاز."
                            }, label = { Text(chosen.name) })
                        }
                    }
                    Button(onClick = {
                        val lat = latitude.replace(',', '.').toDoubleOrNull(); val lon = longitude.replace(',', '.').toDoubleOrNull()
                        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) message = "تحقق من الإحداثيات: خط العرض بين ‎-90 و90، والطول بين ‎-180 و180."
                        else {
                            PrayerPrefs.saveLocation(context, lat, lon, city); PrayerPrefs.saveMethod(context, method)
                            PrayerReminderScheduler.schedule(context); refresh++; message = "تم حفظ الموقع وطريقة الحساب."
                        }
                    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("حفظ الموقع والحساب") }
                    if (message.isNotBlank()) Text(message, color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                PreferenceCard("تذكير الصلاة", "تنبيه اختياري لكل صلاة قادمة. يعمل محليًا، ويُعاد ضبطه بعد إعادة تشغيل الجهاز.") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = enabled, onCheckedChange = { checked ->
                            enabled = checked && hasLocation
                            if (checked && !hasLocation) message = "احفظ موقعك أولًا لتفعيل التذكير."
                            else if (checked) {
                                PrayerPrefs.setEnabled(context, true); PrayerReminderScheduler.schedule(context)
                                if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else { PrayerPrefs.setEnabled(context, false); PrayerReminderScheduler.schedule(context) }
                        })
                        Text("ذكّرني عند دخول وقت الصلاة", color = FocusColors.TextPrimary)
                    }
                    if (enabled && !hasLocation) Text("احفظ موقعًا صالحًا لتبدأ التنبيهات.", color = FocusColors.TextSecondary)
                    Text(
                        "عند وقت الصلاة يظهر تنبيه عاجل على الشاشة ويُشغَّل الأذان محليًا من التطبيق، حتى والهاتف مقفل.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary
                    )
                    TextButton(onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else PrayerReminderScheduler.notifyTest(context)
                    }) { Text("جرّب صوت الأذان وظهور التنبيه") }
                }
            }
            item {
                PreferenceCard("تذكيرات الأذكار", "أربع وقفات قصيرة: بعد الفجر والظهر والمغرب والعشاء، أو في ٧:٠٠ و١٢:٣٠ و٦:٠٠ و٩:٣٠ إن لم يكن موقع محفوظ. كلها تُحسب في المنطقة الزمنية المحفوظة.") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dhikrEnabled, onCheckedChange = { checked ->
                            dhikrEnabled = checked
                            DhikrPrefs.setEnabled(context, checked)
                            DhikrReminderScheduler.schedule(context)
                        })
                        Text("فعّل تذكيرات الأذكار", color = FocusColors.TextPrimary)
                    }
                    Text(
                        "النصوص قصيرة ومحفوظة داخل التطبيق، والصوت مضبوط لكل نوع إشعار. لا تُرسل الأذكار ولا أوقاتها إلى أي خادم.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary
                    )
                    TextButton(onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else DhikrReminderScheduler.notifyTest(context)
                    }) { Text("جرّب تذكير الأذكار") }
                }
            }
        }
    }
}

@Composable
internal fun PersonalizationScreen(
    current: FocusPreferences,
    onBack: () -> Unit,
    onSave: (FocusPreferences) -> Unit,
    onRequestNotifications: () -> Unit,
    onManagePermanentApps: () -> Unit
) {
    var name by rememberSaveable(current.studentName) { mutableStateOf(current.studentName) }
    var goal by rememberSaveable(current.dailyGoalMinutes) { mutableStateOf(current.dailyGoalMinutes.toFloat()) }
    var session by rememberSaveable(current.defaultSessionMinutes) { mutableStateOf(current.defaultSessionMinutes.toFloat()) }
    var accent by rememberSaveable(current.accentIndex) { mutableStateOf(current.accentIndex) }
    var textScale by rememberSaveable(current.textScale) { mutableStateOf(current.textScale) }
    var avatarPath by rememberSaveable(current.avatarPath) { mutableStateOf(current.avatarPath) }
    var avatarSourcePath by rememberSaveable(current.avatarSourcePath) { mutableStateOf(current.avatarSourcePath) }
    var avatarStyle by rememberSaveable(current.avatarStyle) { mutableIntStateOf(current.avatarStyle) }
    var companionStyle by rememberSaveable(current.companionStyle) { mutableStateOf(current.companionStyle) }
    var generalNotifications by rememberSaveable(current.generalNotificationsEnabled) { mutableStateOf(current.generalNotificationsEnabled) }
    var morningEnabled by rememberSaveable(current.morningMessageEnabled) { mutableStateOf(current.morningMessageEnabled) }
    var eveningEnabled by rememberSaveable(current.eveningMessageEnabled) { mutableStateOf(current.eveningMessageEnabled) }
    var morningHour by rememberSaveable(current.morningMessageHour) { mutableStateOf(current.morningMessageHour) }
    var eveningHour by rememberSaveable(current.eveningMessageHour) { mutableStateOf(current.eveningMessageHour) }
    var tone by rememberSaveable(current.tonePreference) { mutableStateOf(current.tonePreference) }
    var reduceMotion by rememberSaveable(current.reduceMotion) { mutableStateOf(current.reduceMotion) }
    var themeMode by rememberSaveable(current.themeMode) { mutableStateOf(current.themeMode) }
    var profile by rememberSaveable(current.profile) { mutableStateOf(current.profile) }
    var density by rememberSaveable(current.notificationDensity) { mutableStateOf(current.notificationDensity) }
    var smartReminders by rememberSaveable(current.smartRemindersEnabled) { mutableStateOf(current.smartRemindersEnabled) }
    var quietHours by rememberSaveable(current.quietHoursEnabled) { mutableStateOf(current.quietHoursEnabled) }
    var quietStart by rememberSaveable(current.quietStartHour) { mutableStateOf(current.quietStartHour) }
    var quietEnd by rememberSaveable(current.quietEndHour) { mutableStateOf(current.quietEndHour) }
    var lead by rememberSaveable(current.leadMinutes) { mutableStateOf(current.leadMinutes) }
    var legacyReminders by rememberSaveable(current.legacyReminders) { mutableStateOf(current.legacyReminders) }
    val context = LocalContext.current
    val notificationsAllowed = android.os.Build.VERSION.SDK_INT < 33 ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("تخصيص تجربتك", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                PreferenceCard(title = "ملفك الشخصي", description = "اسمك وصورتك يخصصان رفيقك ورسائل التشجيع.") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(32) },
                        label = { Text("اسمك (اختياري)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("هدفك اليومي: ${goal.toInt()} دقيقة", color = FocusColors.TextSecondary)
                    Slider(value = goal, onValueChange = { goal = it }, valueRange = 30f..360f, steps = 10)
                    Spacer(Modifier.height(8.dp))
                    AvatarChooser(
                        avatar = AvatarImage(avatarPath, avatarSourcePath, avatarStyle),
                        name = name,
                        companionStyle = companionStyle,
                        onAvatarChanged = { image ->
                            avatarPath = image.path
                            avatarSourcePath = image.sourcePath
                            avatarStyle = image.style
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    CompanionStylePicker(companionStyle, onSelect = { companionStyle = it })
                }
            }
            item {
                PreferenceCard(title = "جلسات التركيز", description = "مدة البداية الافتراضية، ويمكنك تغييرها قبل كل جلسة.") {
                    Text("${session.toInt()} دقيقة", color = FocusColors.TextSecondary)
                    Slider(value = session, onValueChange = { session = it }, valueRange = 10f..90f, steps = 15)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(25, 45, 60).forEach { minutes ->
                            FilterChip(
                                selected = session.toInt() == minutes,
                                onClick = { session = minutes.toFloat() },
                                label = { Text("${minutes}د") }
                            )
                        }
                    }
                }
            }
            item {
                PreferenceCard(title = "الإشعارات", description = "تحكم في الرسائل العامة. تنبيهات المهام أساسية ولا يمكن إيقافها من هنا.") {
                    PreferenceSwitchRow("الرسائل العامة", "يمكنك إيقافها دون التأثير على متابعة المهام.", generalNotifications) { generalNotifications = it }
                    Card(colors = CardDefaults.cardColors(containerColor = FocusColors.SurfaceHigh), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("🔔 تنبيهات المهام · مفعّلة دائمًا", color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                            Text("كل ساعتين من 9 صباحًا إلى 9 مساءً عند وجود مهام مفتوحة، وتتوقف خلال جلسة التركيز.", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!notificationsAllowed) {
                        Text("اسمح بإشعارات مُنظِّم من أندرويد حتى تصلك تنبيهات المهام.", color = FocusColors.onTint(FocusColors.Amber), style = MaterialTheme.typography.bodySmall)
                    }
                    if (generalNotifications) {
                        PreferenceSwitchRow("رسالة صباحية", "تشجيع باسمك في بداية اليوم.", morningEnabled) { morningEnabled = it }
                        if (morningEnabled) TextButton(onClick = {
                            TimePickerDialog(context, { _, hour, _ -> morningHour = hour.coerceIn(6, 11) }, morningHour, 0, false).show()
                        }) { Text("الموعد · ${String.format(java.util.Locale.US, "%02d:00", morningHour)}") }
                        PreferenceSwitchRow("رسالة مسائية", "مراجعة لطيفة في نهاية اليوم.", eveningEnabled) { eveningEnabled = it }
                        if (eveningEnabled) TextButton(onClick = {
                            TimePickerDialog(context, { _, hour, _ -> eveningHour = hour.coerceIn(17, 23) }, eveningHour, 0, false).show()
                        }) { Text("الموعد · ${String.format(java.util.Locale.US, "%02d:00", eveningHour)}") }
                    }
                }
            }
            item {
                PreferenceCard(
                    title = "نبرة التطبيق",
                    description = "تحدد أسلوب الرسائل: موقّر في الصلاة والامتحان واليوم الصعب، وأخف في الإنجاز والصباح."
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TonePreference.entries.forEach { option ->
                            FilterChip(
                                selected = tone == option,
                                onClick = { tone = option },
                                label = { Text(option.label) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("مثال: ${Voice.render(VoiceScene.MORNING, Persona.from(companionStyle), tone.ceiling, mapOf("name" to name.ifBlank { "أحمد" }, "count" to "3", "task" to "مراجعة الفيزياء"))}", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                PreferenceCard(
                    title = "تقليل الحركة",
                    description = "تثبيت خلفية التطبيق بدل حركتها المستمرة. تهدأ تلقائيًا خلال جلسة التركيز لتوفير البطارية."
                ) {
                    PreferenceSwitchRow("حركة أقل", "الخلفية تصبح ثابتة.", reduceMotion) { reduceMotion = it }
                }
            }
            item {
                PreferenceCard(
                    title = "ملفك الشخصي",
                    description = "يضيف أقسامًا مناسبة لك (مواد، عمل، صحة…) ويمكنك تعديلها أو حذفها من «أنواع المهام»."
                ) {
                    androidx.compose.foundation.layout.FlowRow(
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
                    Text(
                        "${profile.label}: ${profile.hint}",
                        color = FocusColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            item {
                PreferenceCard(
                    title = "المظهر",
                    description = "فاتح أو داكن أو تلقائي حسب إعداد الهاتف. الألوان والتباين مضبوطة في الوضعين."
                ) {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ThemeMode.entries.forEach { option ->
                            FilterChip(
                                selected = themeMode == option,
                                onClick = { themeMode = option },
                                label = { Text(option.label) }
                            )
                        }
                    }
                }
            }
            item {
                PreferenceCard(
                    title = "الإشعارات الذكية",
                    description = "تذكير واحد لكل مهمة قبل موعدها، يتأجل تلقائيًا في الصلاة والجلسة وساعات الهدوء."
                ) {
                    PreferenceSwitchRow("التذكيرات الذكية", "بديل تذكير الساعتين؛ يعرض سبب كل تذكير ويتعلم من تجاهلك.", smartReminders) { smartReminders = it }
                    if (smartReminders) {
                        Text("كثافة التنبيه اليومي: ${density.label} (${density.dailyBudget} كحد أقصى)", color = FocusColors.TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NotificationDensity.entries.forEach { option ->
                                FilterChip(
                                    selected = density == option,
                                    onClick = { density = option },
                                    label = { Text(option.label) }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("التذكير قبل الموعد", color = FocusColors.TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 60).forEach { minutes ->
                                FilterChip(
                                    selected = lead == minutes,
                                    onClick = { lead = minutes },
                                    label = { Text("$minutes د") }
                                )
                            }
                        }
                        PreferenceSwitchRow("ساعات الهدوء", "لا تصل التنبيهات غير العاجلة في هذه الساعات.", quietHours) { quietHours = it }
                        if (quietHours) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    TimePickerDialog(context, { _, hour, _ -> quietStart = hour }, quietStart, 0, true).show()
                                }) { Text("من ${String.format(java.util.Locale.US, "%02d:00", quietStart)}") }
                                TextButton(onClick = {
                                    TimePickerDialog(context, { _, hour, _ -> quietEnd = hour }, quietEnd, 0, true).show()
                                }) { Text("إلى ${String.format(java.util.Locale.US, "%02d:00", quietEnd)}") }
                            }
                        }
                    }
                    PreferenceSwitchRow("التنبيهات الكلاسيكية", "تذكير كل ساعتين من 9 صباحًا إلى 9 مساءً كما كان.", legacyReminders) { legacyReminders = it }
                }
            }
            item {
                PreferenceCard(title = "التطبيقات الثابتة", description = "تُضاف تلقائيًا لكل جلسة، ويمكنك اختيار تطبيقات أخرى لكل مهمة.") {
                    Text("${current.permanentBlockedPackages.size} تطبيق محفوظ", color = FocusColors.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onManagePermanentApps) { Text("إدارة التطبيقات الثابتة") }
                }
            }
            item {
                PreferenceCard(title = "مظهر التطبيق", description = "اختر لونًا مميزًا وحجم قراءة مريحًا.") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        FocusColors.accents.forEachIndexed { index, color ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                androidx.compose.foundation.layout.Box(
                                    modifier = Modifier.size(42.dp).clip(CircleShape)
                                        .background(color)
                                        .clickable { accent = index },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (accent == index) Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Text(listOf("أزرق", "بنفسجي", "تركواز", "ذهبي", "أخضر")[index], style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("حجم النص: ${when { textScale < 0.96f -> "صغير"; textScale > 1.1f -> "كبير"; else -> "عادي" }}", color = FocusColors.TextSecondary)
                    Slider(value = textScale, onValueChange = { textScale = it }, valueRange = 0.9f..1.2f, steps = 2)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                if (!notificationsAllowed && android.os.Build.VERSION.SDK_INT >= 33 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) onRequestNotifications()
                onSave(current.copy(
                    studentName = name.trim(),
                    dailyGoalMinutes = goal.toInt(),
                    defaultSessionMinutes = session.toInt(),
                    accentIndex = accent,
                    textScale = textScale,
                    dailyReminderEnabled = generalNotifications,
                    remindersPerDay = 1,
                    avatarPath = avatarPath,
                    avatarSourcePath = avatarSourcePath,
                    avatarStyle = avatarStyle,
                    companionStyle = companionStyle,
                    reduceMotion = reduceMotion,
                    generalNotificationsEnabled = generalNotifications,
                    morningMessageEnabled = morningEnabled,
                    eveningMessageEnabled = eveningEnabled,
                    morningMessageHour = morningHour,
                    eveningMessageHour = eveningHour,
                    tonePreference = tone,
                    notificationDensity = density,
                    smartRemindersEnabled = smartReminders,
                    quietHoursEnabled = quietHours,
                    quietStartHour = quietStart,
                    quietEndHour = quietEnd,
                    leadMinutes = lead,
                    legacyReminders = legacyReminders,
                    themeMode = themeMode,
                    profile = profile
                ))
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) { Text("حفظ التخصيص") }
    }
}

@Composable
internal fun SupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("الدعم الفني", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("محتاج مساعدة؟", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "راسل فريق مُنظِّم عن مشكلة أو اقتراح، وتقدر أيضًا تراسلنا على واتساب.",
                    color = FocusColors.TextSecondary
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text("اسمك (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it.take(120) },
                    label = { Text("بريد أو رقم للرد (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it.take(2000) },
                    label = { Text("اكتب مشكلتك أو اقتراحك") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = {
                        if (body.trim().length >= 5 && !sending) {
                            sending = true
                            result = ""
                            scope.launch {
                                val ok = TeamMessages.sendSupport(context, name, contact, body)
                                sending = false
                                result = if (ok) "وصلت رسالتك إلى الفريق، وسنرد عليك في أقرب وقت." else "تعذر الإرسال الآن. تحقق من الاتصال أو استخدم واتساب."
                                if (ok) body = ""
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text(if (sending) "جارٍ الإرسال…" else "إرسال إلى فريق مُنظِّم") }
                if (result.isNotBlank()) {
                    Text(
                        result,
                        color = if (result.startsWith("وصلت")) FocusColors.onTint(FocusColors.Green) else FocusColors.onTint(FocusColors.Rose),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text("أو راسلنا على واتساب مباشرة:", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("اسم الدعم", color = FocusColors.TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Text("@$SUPPORT_USERNAME", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = {
                        val url = "https://wa.me/$SUPPORT_USERNAME?text=" +
                            Uri.encode("مرحبًا، أحتاج مساعدة بخصوص تطبيق مُنظِّم.")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            .onFailure { Toast.makeText(context, "تعذر فتح واتساب. انسخ اسم المستخدم وحاول مرة أخرى.", Toast.LENGTH_LONG).show() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("مراسلة الدعم على واتساب") }
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("WhatsApp support", SUPPORT_USERNAME))
                        Toast.makeText(context, "تم نسخ اسم الدعم", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text("نسخ اسم المستخدم") }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("قبل المراسلة: اكتب وصفًا مختصرًا للمشكلة، واذكر الشاشة والخطوات التي أدت إليها.", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun AppUpdatesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateCheckResult?>(null) }

    fun runCheck(manual: Boolean) {
        if (checking || !AppUpdateManager.configured()) return
        scope.launch {
            checking = true
            result = withContext(Dispatchers.IO) { AppUpdateManager.check(context, manual) }
            checking = false
        }
    }

    LaunchedEffect(Unit) { runCheck(manual = false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("تحديثات التطبيق", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        PreferenceCard(title = "إصدارك الحالي", description = "مُنظِّم ${BuildConfig.VERSION_NAME}") {
            when {
                checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("جارٍ فحص الإصدارات…", color = FocusColors.TextSecondary)
                }
                result is UpdateCheckResult.NotConfigured || !AppUpdateManager.configured() -> {
                    Text("مصدر التحديثات غير مربوط بعد. عند توفير رابط HTTPS لملف الإصدارات، سيبحث التطبيق تلقائيًا ويبلغك عند ظهور نسخة أحدث.", color = FocusColors.TextSecondary)
                }
                result is UpdateCheckResult.Available -> {
                    val release = (result as UpdateCheckResult.Available).release
                    Text("يتوفر إصدار جديد: ${release.versionName} (رقم ${release.versionCode})", style = MaterialTheme.typography.titleSmall, color = FocusColors.onTint(FocusColors.Green), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(release.releaseNotes.ifBlank { "تحسينات وإصلاحات جديدة." }, color = FocusColors.TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    if (release.storeUrl.isNotBlank()) {
                        Button(onClick = { openReleaseLink(context, release) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Text("التحديث من المتجر · ${release.versionName}")
                        }
                        Text("سيحدّث المتجر النسخة الموجودة. تبقى بياناتك معها إذا طابق التطبيق المنشور الحزمة والتوقيع الحاليين.", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Button(onClick = { openReleaseLink(context, release) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                            Text("تفاصيل التحديث")
                        }
                        Text("رابط المتجر غير مربوط لهذا الإصدار بعد.", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                result is UpdateCheckResult.UpToDate -> {
                    Text("تطبيقك محدث لآخر إصدار موجود في المصدر.", color = FocusColors.TextSecondary)
                }
                result is UpdateCheckResult.Offline -> {
                    Text("أنت غير متصل حاليًا. سيتم فحص الإصدارات تلقائيًا عند عودة الاتصال، وكل ميزات التطبيق تعمل بدون إنترنت.", color = FocusColors.TextSecondary)
                }
                result is UpdateCheckResult.Failed -> {
                    Text((result as UpdateCheckResult.Failed).message, color = FocusColors.onTint(FocusColors.Amber))
                }
                else -> Text("سيتم فحص الإصدارات عند توفر الاتصال.", color = FocusColors.TextSecondary)
            }
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { runCheck(manual = true) },
            enabled = AppUpdateManager.configured() && !checking,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) { Text(if (checking) "جارٍ الفحص…" else "فحص الآن") }
        if (!AppUpdateManager.configured()) {
            Spacer(Modifier.height(10.dp))
            Text("سيظل الإصدار الحالي يعمل. لإشعارات الإصدارات القادمة، يجب ربط موجز الإصدارات وصفحة التطبيق في المتجر.", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun openReleaseLink(context: Context, release: AppRelease) {
    val target = release.storeUrl.ifBlank { release.releasePageUrl }
    val primary = Intent(Intent.ACTION_VIEW, Uri.parse(target))
    runCatching { context.startActivity(primary) }
        .onFailure {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.releasePageUrl))) }
        }
}

@Composable
internal fun PermanentAppsScreen(
    selectedPackages: Set<String>,
    onBack: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<InstalledApp>()) }
    var selected by remember(selectedPackages) { mutableStateOf(selectedPackages) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) } }
    val filtered = apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("التطبيقات الثابتة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${selected.size} تطبيق سيُضاف تلقائيًا للجلسات", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("ابحث عن تطبيق") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                val checked = app.packageName in selected
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (checked) FocusColors.SurfaceHigh else FocusColors.Surface)
                        .clickable { selected = if (checked) selected - app.packageName else selected + app.packageName }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked, onCheckedChange = { value -> selected = if (value) selected + app.packageName else selected - app.packageName })
                    Text(app.label, Modifier.weight(1f), color = FocusColors.TextPrimary)
                }
            }
            if (apps.isEmpty()) item { Text("جارٍ تحميل التطبيقات…", modifier = Modifier.fillMaxWidth().padding(20.dp), textAlign = TextAlign.Center, color = FocusColors.TextSecondary) }
            if (apps.isNotEmpty() && filtered.isEmpty()) item { Text("لا توجد نتائج.", modifier = Modifier.fillMaxWidth().padding(20.dp), textAlign = TextAlign.Center, color = FocusColors.TextSecondary) }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = { onSave(selected); onBack() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) { Text("حفظ التطبيقات الثابتة") }
    }
}

@Composable
private fun PreferenceCard(title: String, description: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = FocusColors.Surface), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}
