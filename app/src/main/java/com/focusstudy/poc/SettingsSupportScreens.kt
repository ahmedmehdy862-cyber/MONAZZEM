package com.focusstudy.poc

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.widget.Toast
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// رقم الدعم على واتساب بالصيغة الدولية بدون "+" أو مسافات — قدّمه أحمد
// 2026-11-26. رابط wa.me لا يقبل اسم مستخدم (مثل "techsup402") فلا يفتح
// محادثة؛ إن فُرّغ الرقم يبقى زر المراسلة المباشرة معطّلًا وتعمل مشاركة
// الصور عبر ورقة المشاركة دون رقم.
private const val SUPPORT_WHATSAPP_NUMBER = "201110200193"

/** مرجع قصير لرسالة دعم محفوظة، يظهر في سطر ملاحظة الصور وفي نص واتساب لربط الصور بالرسالة. */
private fun supportReference(localId: Long): String = "دعم-$localId"

@Composable
internal fun PrayerTimesScreen(
    onBack: () -> Unit,
    timezoneId: String = "",
    onTimeZoneChanged: (String) -> Unit = {},
    onOpenNotifications: () -> Unit = {}
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
    var timezone by rememberSaveable(timezoneId) { mutableStateOf(timezoneId.ifBlank { AppTimeZone.deviceId() }) }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    /** جلب الموقع التقريبي مرة واحدة بعد منح الإذن (من البوابة أو من طلب النظام). */
    fun fetchApproximateLocation() {
        scope.launch {
            loading = true
            val location = currentApproximateLocation(context)
            loading = false
            if (location != null) {
                latitude = coordinateText(location.latitude); longitude = coordinateText(location.longitude)
                city = "موقعي"; PrayerPrefs.saveLocation(context, location.latitude, location.longitude, city)
                PrayerReminderScheduler.schedule(context); refresh++
                message = "تم حفظ الموقع على جهازك. لا نرسل إحداثياتك إلى أي خادم."
            } else message = "تعذر تحديد الموقع الآن. أدخل الإحداثيات يدويًا أو حاول قرب نافذة."
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fetchApproximateLocation()
        else message = "لم يُمنح إذن الموقع. يمكنك إدخال الإحداثيات يدويًا."
    }
    // البوابة المركزية (المرحلة 7أ): شرح داخل التطبيق قبل أول طلب نظام، وطلب
    // النظام مرة واحدة فقط في عمر التثبيت، ثم تلميح يدوي بعد أي رفض.
    val locationGate = rememberPermissionGate(
        permission = GatedPermission.LOCATION,
        launchSystemRequest = { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
        onGranted = ::fetchApproximateLocation
    )
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
                        onClick = { locationGate.attempt() },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), enabled = !loading
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
                // Phase 4: the prayer master switch used to live here as a duplicate of the one
                // in the notification center's prayer card. It now lives only there.
                PreferenceCard("تنبيهات الصلاة", "المفتاح الرئيسي للمواقيت والتنبيهات، والتنبيه قبل كل صلاة، وصوت الأذان — كلها في شاشة الإشعارات.") {
                    Text(
                        "عند وقت الصلاة يظهر تنبيه عاجل على الشاشة ويُشغَّل الأذان محليًا من التطبيق، حتى والهاتف مقفل.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary
                    )
                    TextButton(onClick = onOpenNotifications) { Text("فتح شاشة الإشعارات") }
                }
            }
            item {
                // Phase 4: the dhikr switch and its test button moved to the notification center.
                PreferenceCard("تذكيرات الأذكار", "أربع وقفات قصيرة: بعد الفجر والظهر والمغرب والعشاء، أو في ٧:٠٠ و١٢:٣٠ و٦:٠٠ و٩:٣٠ إن لم يكن موقع محفوظ. كلها تُحسب في المنطقة الزمنية المحفوظة.") {
                    Text(
                        "مفتاح الأذكار وزر التجربة في شاشة الإشعارات، مع باقي التذكيرات. النصوص قصيرة ومحفوظة داخل التطبيق، ولا تُرسل الأذكار ولا أوقاتها إلى أي خادم.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary
                    )
                    TextButton(onClick = onOpenNotifications) { Text("فتح شاشة الإشعارات") }
                }
            }
        }
    }
    // نافذة بوابة الموقع (شرح قبل أول طلب، وتلميح يدوي بعد الرفض).
    locationGate.Dialog()
}

@Composable
internal fun PersonalizationScreen(
    current: FocusPreferences,
    onBack: () -> Unit,
    onSave: (FocusPreferences) -> Unit,
    onManagePermanentApps: () -> Unit,
    onOpenNotifications: () -> Unit = {}
) {
    var name by rememberSaveable(current.studentName) { mutableStateOf(current.studentName) }
    var goal by rememberSaveable(current.dailyGoalMinutes) { mutableStateOf(current.dailyGoalMinutes.toFloat()) }
    var session by rememberSaveable(current.defaultSessionMinutes) { mutableStateOf(current.defaultSessionMinutes.toFloat()) }
    var accent by rememberSaveable(current.accentIndex) { mutableStateOf(current.accentIndex) }
    var textScale by rememberSaveable(current.textScale) { mutableStateOf(current.textScale) }
    var avatarPath by rememberSaveable(current.avatarPath) { mutableStateOf(current.avatarPath) }
    var companionStyle by rememberSaveable(current.companionStyle) { mutableStateOf(current.companionStyle) }
    var companion3dEnabled by rememberSaveable(current.companion3dEnabled) { mutableStateOf(current.companion3dEnabled) }
    var companion3dAnimationsEnabled by rememberSaveable(current.companion3dAnimationsEnabled) { mutableStateOf(current.companion3dAnimationsEnabled) }
    var tone by rememberSaveable(current.tonePreference) { mutableStateOf(current.tonePreference) }
    var reduceMotion by rememberSaveable(current.reduceMotion) { mutableStateOf(current.reduceMotion) }
    var themeMode by rememberSaveable(current.themeMode) { mutableStateOf(current.themeMode) }
    var profile by rememberSaveable(current.profile) { mutableStateOf(current.profile) }
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
                    Text(
                        if (goal.toInt() == 0) "هدفك اليومي: بلا هدف — ركّز كما تشاء"
                        else "هدفك اليومي: ${goal.toInt()} دقيقة",
                        color = FocusColors.TextSecondary
                    )
                    Slider(value = goal, onValueChange = { goal = it }, valueRange = 0f..360f, steps = 11)
                    Spacer(Modifier.height(8.dp))
                    PhotoAvatarPicker(
                        avatar = AvatarImage(avatarPath),
                        name = name,
                        companionStyle = companionStyle,
                        onAvatarChanged = { image -> avatarPath = image.path }
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
                // Phase 4: every notification control moved to the notification center screen;
                // this card is only a link there, so nothing is controlled twice.
                PreferenceCard(title = "الإشعارات والتذكيرات", description = "كل إعدادات الإشعارات في مكان واحد: الرسائل، ساعات الهدوء، الكثافة، التذكيرات الذكية، الأذكار، الورد.") {
                    if (!notificationsAllowed) {
                        Text("اسمح بإشعارات مُنظِّم من أندرويد حتى تصلك تنبيهات المهام.", color = FocusColors.onTint(FocusColors.Amber), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onOpenNotifications) { Text("فتح شاشة الإشعارات") }
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
                    title = "رفيقك منظم (ثلاثي الأبعاد)",
                    description = "شخصية «منظم» ثلاثية الأبعاد على الرئيسية: ترحب بك وتحتفل بمهامك المنجزة. تعطيله لا يغيّر أي شيء آخر في التطبيق."
                ) {
                    PreferenceSwitchRow("إظهار الرفيق", "يظهر في مساحته الخاصة على الرئيسية.", companion3dEnabled) { companion3dEnabled = it }
                    PreferenceSwitchRow("حركات الرفيق", "إيقافها يعرض صورته الثابتة بدل الحركة.", companion3dAnimationsEnabled) { companion3dAnimationsEnabled = it }
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
                                    if (accent == index) Text("✓", color = FocusColors.onAccentFill(color), fontWeight = FontWeight.Bold)
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
            // A7: نسبة نص القرآن (Tanzil) هنا في الإعدادات بدل سطر تحت كل
            // صفحة مصحف.
            item {
                PreferenceCard(title = "مصادر المحتوى", description = "نص القرآن الكريم: مشروع تنزيل (Tanzil.net) — النص العثماني v1.1 — رخصة CC Attribution 3.0. خريطة صفحات مصحف المدينة: AlQuran Cloud.") {
                    Text(
                        "مواقيت الصلاة: حساب فلكي محلي داخل التطبيق.",
                        color = FocusColors.TextSecondary
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                // Notification fields are no longer saved from here: they live only in the
                // notification center screen now, so this copy keeps their stored values.
                onSave(current.copy(
                    studentName = name.trim(),
                    dailyGoalMinutes = goal.toInt(),
                    defaultSessionMinutes = session.toInt(),
                    accentIndex = accent,
                    textScale = textScale,
                    remindersPerDay = 1,
                    avatarPath = avatarPath,
                    companionStyle = companionStyle,
                    companion3dEnabled = companion3dEnabled,
                    companion3dAnimationsEnabled = companion3dAnimationsEnabled,
                    reduceMotion = reduceMotion,
                    tonePreference = tone,
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

/** حد أقصى للصور المرفقة مع كل رسالة دعم أو اقتراح. */
private const val MAX_SUPPORT_IMAGES = 4

/**
 * يبني نص الرسالة المُرسلة إلى الفريق عبر [TeamMessages.sendSupport].
 * الاقتراحات تُوسم بسطر "[اقتراح]" في أول النص لتمييزها عن الشكاوى في صندوق الفريق،
 * والصور لا تمر عبر هذه القناة إطلاقًا (انظر تعليق [SupportImageAttachments]):
 * يُضاف سطر واحد فقط بعددها حتى يعرف الفريق أن صورًا ستصله عبر واتساب.
 */
private fun buildSupportBody(raw: String, isSuggestion: Boolean, imageCount: Int, reference: String? = null): String {
    val tag = if (isSuggestion) "[اقتراح] " else ""
    val note = if (imageCount > 0) {
        val countText = if (imageCount == 1) "صورة واحدة" else "$imageCount صور"
        val ref = if (reference != null) " (مرجع الرسالة: $reference)" else ""
        "\n[أرفق المستخدم $countText؛ الصور لا تصل عبر هذه الرسالة النصية وستُرسل عبر واتساب$ref.]"
    } else ""
    return (tag + raw.trim()).take((2000 - note.length).coerceAtLeast(0)) + note
}

/**
 * نص واتساب المرفق بصور رسالة دعم: يحمل مرجع الرسالة القصير (وربما معرّف
 * الخادم) حتى يربط الفريق الصور بالرسالة النصية التي وصلته عبر القناة.
 */
private fun supportWhatsAppText(intro: String, bodyExcerpt: String, reference: String?, serverId: String?): String {
    val refs = listOfNotNull(
        reference?.let { "مرجع الرسالة: $it" },
        serverId?.let { "معرّف الخادم: $it" }
    )
    val suffix = if (refs.isNotEmpty()) " (${refs.joinToString("، ")})" else ""
    return intro + suffix + if (bodyExcerpt.isNotBlank()) "\n" + bodyExcerpt.trim().take(300) else ""
}

/** ملاحظة تُلحق بنتيجة الإرسال حين تعذّر نسخ بعض الصور، حتى لا تختفي بصمت. */
private fun copyFailureNote(failed: Int): String =
    if (failed <= 0) "" else " (تعذّر نسخ ${if (failed == 1) "صورة واحدة" else "$failed صور"} من جهازك؛ أُرسلت الرسالة دونها.)"

/**
 * يشارك الصور المرفقة مع دعم واتساب كمرفقات حقيقية (ACTION_SEND_MULTIPLE مع
 * EXTRA_STREAM ومنح قراءة مؤقت)، لأن قناة الدعم داخل التطبيق نصية فقط.
 * تُفتح ورقة مشاركة يختار منها المستخدم واتساب؛ فإن لم يكن مثبّتًا ظهرت التطبيقات
 * الأخرى التي تقبل مشاركة الصور.
 */
private fun shareSupportImages(
    context: Context,
    imageUris: List<String>,
    message: String,
    copiedPaths: List<String> = emptyList()
): Boolean {
    // Phase 7b: the WhatsApp button shares the private filesDir copies (via FileProvider),
    // not the picked content URIs — those can lose their grant after the app closes, while
    // the copies stay. Before sending (or when a copy failed) there is no copy yet, so the
    // picked URIs are shared as before.
    val uris = supportImageShareUris(context, copiedPaths)
        .ifEmpty { imageUris.map { Uri.parse(it) } }
    if (uris.isEmpty()) return false
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_TEXT, message)
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        clipData = ClipData("صور الدعم", arrayOf("image/*"), ClipData.Item(uris.first())).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // يعيد true إن فُتحت ورقة المشاركة فعلًا، حتى يمسح المستدعي النموذج بعدها فقط.
    return runCatching { context.startActivity(Intent.createChooser(intent, "إرسال الصور إلى الدعم عبر واتساب")) }
        .onFailure {
            Toast.makeText(context, "تعذّر فتح المشاركة. تأكد من تثبيت واتساب وحاول مرة أخرى.", Toast.LENGTH_LONG).show()
        }.isSuccess
}

/** يفكّ مصغّرة للصورة (بحد أقصى 256 بكسل للضلع) عبر ContentResolver دون أي إذن تخزين. */
private fun decodeSupportThumbnail(context: Context, uri: Uri): ImageBitmap? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return@runCatching null
    bitmap.asImageBitmap()
}.getOrNull()

@Composable
private fun SupportImageThumbnail(uri: Uri, onRemove: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { decodeSupportThumbnail(context, uri) }
    }
    Box(
        modifier = Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)).background(FocusColors.SurfaceHigh),
        contentAlignment = Alignment.Center
    ) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = "صورة مرفقة",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Text("…", color = FocusColors.TextMuted)
        }
        Box(
            modifier = Modifier.align(Alignment.TopStart).padding(2.dp).size(22.dp)
                .clip(CircleShape).background(FocusColors.Surface).clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Text("×", color = FocusColors.Error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * إرفاق الصور في شاشة الدعم — لماذا لا تصل الصور مع الرسالة النصية؟
 * قناة الدعم الحالية (TeamMessages.sendSupport: POST JSON إلى /api/support) نصية فقط:
 * الخادم (update-site/api/support.js) يقرأ name/contact/body/version/uid ويتجاهل أي حقل
 * آخر، ويقصّ النص عند 2000 حرف، ولا توجد في التطبيق أو الخادم أي واجهة رفع صور
 * (لا multipart ولا حقل base64 ولا تخزين ملفات للمستخدمين). إنشاء خادم رفع جديد
 * كان سيغيّر القناة نفسها، فبقيت الصور في الجهاز: يُرسل نص الرسالة عبر القناة
 * مع سطر يذكر عدد الصور، وتُشارك الصور نفسها كمرفقات إلى واتساب الدعم بالزر أدناه.
 * المنتقي (PickMultipleVisualMedia) هو منتقي صور النظام ولا يحتاج إذن تخزين إضافيًا،
 * وCoil غير مضمّنة في المشروع فالمصغّرات تُفكّ عبر ContentResolver وBitmapFactory.
 * ومنذ المرحلة 7ب تُنسخ الصور عند الإرسال إلى filesDir/support_images وتُحفَظ مساراتها
 * في قاعدة البيانات (support_messages)، ويشارك زر واتساب هذه النسخ عبر FileProvider.
 */
@Composable
private fun SupportImageAttachments(
    images: List<String>,
    onAddPhotos: () -> Unit,
    onRemoveImage: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onAddPhotos) { Text("+ إرفاق صور") }
            Spacer(Modifier.width(8.dp))
            Text(
                if (images.isEmpty()) "حتى $MAX_SUPPORT_IMAGES صور — بلا إذن تخزين"
                else "${images.size} من $MAX_SUPPORT_IMAGES صور",
                color = FocusColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (images.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                images.forEach { raw ->
                    SupportImageThumbnail(uri = Uri.parse(raw), onRemove = { onRemoveImage(raw) })
                }
            }
            Text(
                "الصور لا تُرسل مع رسالتك النصية لأن قناة الدعم نصية فقط؛ بعد الإرسال استخدم زر واتساب أدناه لإرسال الصور نفسها إلى الفريق.",
                color = FocusColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SupportMessageForm(
    bodyLabel: String,
    body: String,
    onBodyChange: (String) -> Unit,
    images: List<String>,
    onAddPhotos: () -> Unit,
    onRemoveImage: (String) -> Unit,
    sending: Boolean,
    sendLabel: String,
    result: String,
    onSend: () -> Unit,
    onShareImages: () -> Unit
) {
    OutlinedTextField(
        value = body,
        onValueChange = { onBodyChange(it.take(2000)) },
        label = { Text(bodyLabel) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    )
    SupportImageAttachments(images = images, onAddPhotos = onAddPhotos, onRemoveImage = onRemoveImage)
    Button(
        onClick = onSend,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) { Text(if (sending) "جارٍ الإرسال…" else sendLabel) }
    if (result.isNotBlank()) {
        Text(
            result,
            color = if (result.startsWith("وصل")) FocusColors.onTint(FocusColors.Green) else FocusColors.onTint(FocusColors.Rose),
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (images.isNotEmpty()) {
        Button(
            onClick = onShareImages,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) { Text("إرسال الصور المرفقة عبر واتساب") }
    }
}

@Composable
private fun SupportTabButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) FocusColors.Blue else FocusColors.SurfaceHigh)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) FocusColors.onAccentFill(FocusColors.Blue) else FocusColors.TextSecondary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
internal fun SupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var complaintBody by rememberSaveable { mutableStateOf("") }
    var suggestionBody by rememberSaveable { mutableStateOf("") }
    var complaintImages by rememberSaveable { mutableStateOf(listOf<String>()) }
    var suggestionImages by rememberSaveable { mutableStateOf(listOf<String>()) }
    var sending by remember { mutableStateOf(false) }
    var complaintResult by remember { mutableStateOf("") }
    var suggestionResult by remember { mutableStateOf("") }
    // Phase 7b: local copies of sent messages («رسائلي») and the private filesDir copies
    // of their images, which are what the WhatsApp button shares after sending.
    var savedMessages by remember { mutableStateOf(emptyList<SupportMessage>()) }
    var messagesRefresh by remember { mutableIntStateOf(0) }
    var complaintCopiedPaths by remember { mutableStateOf(listOf<String>()) }
    var suggestionCopiedPaths by remember { mutableStateOf(listOf<String>()) }
    var retryingId by remember { mutableStateOf<Long?>(null) }
    // مرجع الرسالة المحلية ومعرّف الخادم لآخر رسالة أُرسلت في كل تبويب، ليحملهما
    // نص واتساب عند مشاركة الصور (ح1(ب)).
    var complaintSentReference by remember { mutableStateOf<String?>(null) }
    var complaintServerId by remember { mutableStateOf<String?>(null) }
    var suggestionSentReference by remember { mutableStateOf<String?>(null) }
    var suggestionServerId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(messagesRefresh) {
        savedMessages = withContext(Dispatchers.IO) {
            runCatching { StudyDb.get(context).supportMessages() }.getOrDefault(emptyList())
        }
    }

    // م11: رسالة عالقة بحالة «قيد الإرسال» تعني أن التطبيق قُتل أثناء الإرسال؛
    // تُسوَّى إلى «فاشلة» مرة واحدة عند فتح الشاشة حتى تظهر مع زر إعادة الإرسال.
    // لا تُستدعى عند كل تحديث حتى لا تقلب رسالة يُرسلها المستخدم الآن.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { StudyDb.get(context).failStuckSendingSupportMessages() }
        }
        messagesRefresh++
    }

    val complaintPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_SUPPORT_IMAGES)
    ) { uris ->
        if (uris.isNotEmpty()) {
            complaintImages = (complaintImages + uris.map { it.toString() }).distinct().take(MAX_SUPPORT_IMAGES)
        }
    }
    val suggestionPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_SUPPORT_IMAGES)
    ) { uris ->
        if (uris.isNotEmpty()) {
            suggestionImages = (suggestionImages + uris.map { it.toString() }).distinct().take(MAX_SUPPORT_IMAGES)
        }
    }

    /**
     * Phase 7b send flow: the images are first copied to private storage, then the message
     * is saved in the database as «sending» — before any network call — and only then sent
     * through the unchanged TeamMessages.sendSupport channel. The status is updated from
     * the result, so no message is lost when the network is down; failed ones can be
     * retried from «رسائلي». [onCopied] receives the private copies for the WhatsApp button.
     * [onResult] receives success, how many images failed to copy, the short local
     * reference and the server id, so the WhatsApp text can link the photos to the
     * message (ح1(ب)) and the user is told about any uncopied images (م12).
     */
    fun submit(
        isSuggestion: Boolean,
        text: String,
        images: List<String>,
        onResult: (Boolean, Int, String?, String?) -> Unit,
        onCopied: (List<String>) -> Unit
    ) {
        if (text.trim().length < 5 || sending) return
        sending = true
        scope.launch {
            val copyResult = withContext(Dispatchers.IO) {
                runCatching { copySupportImages(context, images.map { Uri.parse(it) }) }
                    .getOrDefault(SupportImageCopyResult(emptyList(), images.size))
            }
            val copied = copyResult.copied
            onCopied(copied)
            val messageId = withContext(Dispatchers.IO) {
                runCatching {
                    // The profile name is attached to every message; the name typed in the
                    // form is kept separately as the POST payload's name.
                    val profileName = FocusPreferences.load(context).studentName
                    StudyDb.get(context).saveSupportMessage(
                        kind = if (isSuggestion) SupportMessageKind.SUGGESTION else SupportMessageKind.COMPLAINT,
                        userName = profileName.ifBlank { name.trim() },
                        senderName = name.trim(),
                        contact = contact.trim(),
                        body = text.trim(),
                        imagePaths = copied
                    )
                }.getOrNull()
            }
            val reference = messageId?.let { supportReference(it) }
            val serverId = TeamMessages.sendSupport(
                context, name, contact,
                buildSupportBody(text, isSuggestion, images.size, reference)
            )
            val ok = serverId != null
            if (messageId != null) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        StudyDb.get(context).updateSupportMessageStatus(
                            messageId,
                            if (ok) SupportMessageStatus.SENT else SupportMessageStatus.FAILED
                        )
                    }
                }
                messagesRefresh++
            }
            sending = false
            onResult(ok, copyResult.failed, reference, serverId)
        }
    }

    /**
     * Re-sends a failed saved message through the same channel, rebuilding its payload.
     * ح1(هـ): إعادة الإرسال تعيد النص والصور معًا — بعد نجاح النص تُشارك النسخ
     * المحفوظة عبر واتساب بنفس مرجع الرسالة، وإلا بقيت الصور بلا رسالة تربطها.
     */
    fun retry(message: SupportMessage) {
        if (retryingId != null || sending) return
        retryingId = message.id
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { StudyDb.get(context).updateSupportMessageStatus(message.id, SupportMessageStatus.SENDING) }
            }
            messagesRefresh++
            val serverId = TeamMessages.sendSupport(
                context,
                message.senderName.ifBlank { message.userName },
                message.contact,
                buildSupportBody(
                    message.body,
                    message.kind == SupportMessageKind.SUGGESTION,
                    message.imagePaths.size,
                    supportReference(message.id)
                )
            )
            val ok = serverId != null
            withContext(Dispatchers.IO) {
                runCatching {
                    StudyDb.get(context).updateSupportMessageStatus(
                        message.id,
                        if (ok) SupportMessageStatus.SENT else SupportMessageStatus.FAILED
                    )
                }
            }
            retryingId = null
            messagesRefresh++
            if (ok && message.imagePaths.isNotEmpty()) {
                shareSupportImages(
                    context, emptyList(),
                    supportWhatsAppText(
                        if (message.kind == SupportMessageKind.SUGGESTION) "مرحبًا، هذه صور مرفقة مع اقتراحي إلى فريق مُنظِّم عبر التطبيق."
                        else "مرحبًا، هذه صور مرفقة مع شكواي إلى فريق مُنظِّم عبر التطبيق.",
                        message.body, supportReference(message.id), serverId
                    ),
                    copiedPaths = message.imagePaths
                )
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("الدعم الفني", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(FocusColors.SurfaceHigh).padding(4.dp)
        ) {
            SupportTabButton("شكوى", selected = tab == 0, onClick = { tab = 0 }, modifier = Modifier.weight(1f))
            SupportTabButton("اقترح فكرة", selected = tab == 1, onClick = { tab = 1 }, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (tab == 0) "محتاج مساعدة؟" else "عندك فكرة تحسّن مُنظِّم؟",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                )
                Text(
                    if (tab == 0) "راسل فريق مُنظِّم عن مشكلة، وتقدر أيضًا تراسلنا على واتساب."
                    else "اقترح ميزة أو تحسينًا، وسيصل اقتراحك إلى الفريق موسومًا كاقتراح لا كشكوى.",
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
                if (tab == 0) {
                    SupportMessageForm(
                        bodyLabel = "اكتب مشكلتك",
                        body = complaintBody,
                        onBodyChange = { complaintBody = it },
                        images = complaintImages,
                        onAddPhotos = { complaintPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                        onRemoveImage = { raw -> complaintImages = complaintImages - raw },
                        sending = sending,
                        sendLabel = "إرسال إلى فريق مُنظِّم",
                        result = complaintResult,
                        onSend = {
                            submit(false, complaintBody, complaintImages, onResult = { ok, copyFailures, reference, serverId ->
                                complaintSentReference = reference
                                complaintServerId = serverId
                                complaintResult = if (ok) {
                                    if (complaintImages.isNotEmpty()) "وصلت شكوتك إلى الفريق. الصور المرفقة لا تصل مع هذه الرسالة النصية — أرسلها عبر واتساب بالزر أدناه."
                                    else "وصلت رسالتك إلى الفريق، وسنرد عليك في أقرب وقت."
                                } else "تعذّر الإرسال الآن. تحقق من الاتصال أو استخدم واتساب."
                                complaintResult += copyFailureNote(copyFailures)
                                // ح1(ج): يُمسح النص فورًا فقط حين لا توجد صور تنتظر المشاركة؛
                                // مع وجود صور يبقى النص حتى تُشارك الصور بالزر أدناه.
                                if (ok && complaintImages.isEmpty()) complaintBody = ""
                            },
                                onCopied = { copied -> complaintCopiedPaths = copied }
                            )
                        },
                        onShareImages = {
                            val shared = shareSupportImages(
                                context, complaintImages,
                                supportWhatsAppText(
                                    "مرحبًا، هذه صور مرفقة مع شكواي إلى فريق مُنظِّم عبر التطبيق.",
                                    complaintBody, complaintSentReference, complaintServerId
                                ),
                                copiedPaths = complaintCopiedPaths
                            )
                            // ح1(ج): يُمسح النموذج بعد فتح ورقة المشاركة فعلًا فقط، لا قبلها.
                            if (shared) {
                                complaintBody = ""
                                complaintImages = emptyList()
                                complaintCopiedPaths = emptyList()
                                complaintSentReference = null
                                complaintServerId = null
                            }
                        }
                    )
                } else {
                    SupportMessageForm(
                        bodyLabel = "اكتب اقتراحك",
                        body = suggestionBody,
                        onBodyChange = { suggestionBody = it },
                        images = suggestionImages,
                        onAddPhotos = { suggestionPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                        onRemoveImage = { raw -> suggestionImages = suggestionImages - raw },
                        sending = sending,
                        sendLabel = "إرسال الاقتراح",
                        result = suggestionResult,
                        onSend = {
                            submit(true, suggestionBody, suggestionImages, onResult = { ok, copyFailures, reference, serverId ->
                                suggestionSentReference = reference
                                suggestionServerId = serverId
                                suggestionResult = if (ok) {
                                    if (suggestionImages.isNotEmpty()) "وصل اقتراحك إلى الفريق، شكرًا لك. الصور المرفقة لا تصل مع هذه الرسالة النصية — أرسلها عبر واتساب بالزر أدناه."
                                    else "وصل اقتراحك إلى الفريق، شكرًا لك."
                                } else "تعذّر الإرسال الآن. تحقق من الاتصال أو استخدم واتساب."
                                suggestionResult += copyFailureNote(copyFailures)
                                // ح1(ج): يُمسح النص فورًا فقط حين لا توجد صور تنتظر المشاركة.
                                if (ok && suggestionImages.isEmpty()) suggestionBody = ""
                            },
                                onCopied = { copied -> suggestionCopiedPaths = copied }
                            )
                        },
                        onShareImages = {
                            val shared = shareSupportImages(
                                context, suggestionImages,
                                supportWhatsAppText(
                                    "مرحبًا، هذه صور مرفقة مع اقتراحي إلى فريق مُنظِّم عبر التطبيق.",
                                    suggestionBody, suggestionSentReference, suggestionServerId
                                ),
                                copiedPaths = suggestionCopiedPaths
                            )
                            // ح1(ج): يُمسح النموذج بعد فتح ورقة المشاركة فعلًا فقط، لا قبلها.
                            if (shared) {
                                suggestionBody = ""
                                suggestionImages = emptyList()
                                suggestionCopiedPaths = emptyList()
                                suggestionSentReference = null
                                suggestionServerId = null
                            }
                        }
                    )
                }
                Text("أو راسلنا على واتساب مباشرة:", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (SUPPORT_WHATSAPP_NUMBER.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("رقم الدعم", color = FocusColors.TextSecondary)
                        Spacer(Modifier.width(8.dp))
                        Text(SUPPORT_WHATSAPP_NUMBER, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            val url = "https://wa.me/$SUPPORT_WHATSAPP_NUMBER?text=" +
                                Uri.encode("مرحبًا، أحتاج مساعدة بخصوص تطبيق مُنظِّم.")
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                .onFailure { Toast.makeText(context, "تعذّر فتح واتساب. انسخ الرقم وحاول مرة أخرى.", Toast.LENGTH_LONG).show() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("مراسلة الدعم على واتساب") }
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("WhatsApp support", SUPPORT_WHATSAPP_NUMBER))
                            Toast.makeText(context, "تم نسخ رقم الدعم", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) { Text("نسخ رقم الدعم") }
                } else {
                    // احتياطي إن فُرّغ الرقم أعلاه: لا زر وهمي برابط مكسور، بل تنبيه صريح
                    // بأن المراسلة المباشرة غير مفعّلة.
                    Text(
                        "مراسلة واتساب المباشرة غير مفعّلة بعد (لم يُضبط رقم الدعم في التطبيق). يمكنك إرسال رسالتك هنا، ومشاركة صورك عبر زر واتساب أعلاه.",
                        color = FocusColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            if (tab == 0) "قبل المراسلة: اكتب وصفًا مختصرًا للمشكلة، واذكر الشاشة والخطوات التي أدت إليها."
            else "قبل الإرسال: اشرح الفكرة باختصار، وما المشكلة التي تحلها إن وجدت.",
            color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(14.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("رسائلي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "رسائلك المحفوظة على جهازك مع حالة كل رسالة. الرسالة الفاشلة تبقى هنا ويمكن إعادة إرسالها.",
                    color = FocusColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                if (savedMessages.isEmpty()) {
                    Text("لا توجد رسائل محفوظة بعد.", color = FocusColors.TextSecondary)
                } else {
                    savedMessages.forEach { saved ->
                        SupportSavedMessageRow(
                            message = saved,
                            retrying = retryingId == saved.id,
                            onRetry = { retry(saved) },
                            onShareImages = {
                                shareSupportImages(
                                    context, emptyList(),
                                    supportWhatsAppText(
                                        if (saved.kind == SupportMessageKind.SUGGESTION) "مرحبًا، هذه صور مرفقة مع اقتراحي إلى فريق مُنظِّم عبر التطبيق."
                                        else "مرحبًا، هذه صور مرفقة مع شكواي إلى فريق مُنظِّم عبر التطبيق.",
                                        saved.body, supportReference(saved.id), null
                                    ),
                                    copiedPaths = saved.imagePaths
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * One saved support message in «رسائلي»: kind, send status, date, the profile name it
 * was sent with, a short preview, and a retry button for failed sends.
 */
@Composable
private fun SupportSavedMessageRow(
    message: SupportMessage,
    retrying: Boolean,
    onRetry: () -> Unit,
    onShareImages: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FocusColors.SurfaceHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message.kind.label, fontWeight = FontWeight.SemiBold, color = FocusColors.TextPrimary)
            Spacer(Modifier.width(8.dp))
            Text(
                message.status.label,
                color = when (message.status) {
                    SupportMessageStatus.SENT -> FocusColors.onTint(FocusColors.Green)
                    SupportMessageStatus.FAILED -> FocusColors.onTint(FocusColors.Rose)
                    SupportMessageStatus.SENDING -> FocusColors.onTint(FocusColors.Amber)
                },
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.weight(1f))
            Text(formatSupportDate(message.createdAt), color = FocusColors.TextMuted, style = MaterialTheme.typography.labelSmall)
        }
        if (message.userName.isNotBlank()) {
            Text("باسم: ${message.userName}", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            message.body,
            color = FocusColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "مرجع الرسالة: ${supportReference(message.id)}",
            color = FocusColors.TextMuted,
            style = MaterialTheme.typography.labelSmall
        )
        if (message.imagePaths.isNotEmpty()) {
            val count = message.imagePaths.size
            Text(
                if (count == 1) "صورة واحدة مرفقة، محفوظة على جهازك" else "$count صور مرفقة، محفوظة على جهازك",
                color = FocusColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // ح1(د): زر واتساب لكل رسالة يعيد مشاركة نسخها المحفوظة بنفس المرجع.
            if (message.imagePaths.isNotEmpty()) {
                TextButton(onClick = onShareImages) { Text("إرسال الصور عبر واتساب") }
            }
            if (message.status == SupportMessageStatus.FAILED) {
                TextButton(onClick = onRetry, enabled = !retrying) {
                    Text(if (retrying) "جارٍ إعادة الإرسال…" else "إعادة الإرسال")
                }
            }
        }
    }
}

@Composable
internal fun AppUpdatesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateCheckResult?>(null) }
    // التنزيل المباشر (0.32.0): حالة التنزيل الحالية، ونافذة بوابة إذن التثبيت
    // المعلّقة، والإصدار الذي طُلب تنزيله قبل فتح إعدادات النظام.
    var downloadState by remember { mutableStateOf<DirectDownloadState>(DirectDownloadState.Idle) }
    var installDialog by remember { mutableStateOf<InstallGateDialogKind?>(null) }
    var pendingRelease by remember { mutableStateOf<AppRelease?>(null) }
    // نسخة ويندوز من الموجز: تظهر حتى عندما يكون تطبيق أندرويد محدثًا، لذا تُقرأ أيضًا
    // من آخر إصدار محفوظ لا من نتيجة الفحص وحدها.
    var windowsRelease by remember { mutableStateOf<WindowsRelease?>(null) }

    fun runCheck(manual: Boolean) {
        if (checking || !AppUpdateManager.configured()) return
        scope.launch {
            checking = true
            result = withContext(Dispatchers.IO) { AppUpdateManager.check(context, manual) }
            checking = false
        }
    }

    LaunchedEffect(Unit) { runCheck(manual = false) }

    // يستعلم عن التنزيل كل ثانية حتى ينتهي؛ يبقى DownloadManager نفسه يعمل
    // حتى لو غادر المستخدم الشاشة، وتُستعاد الحالة عند العودة (انظر أدناه).
    fun pollDownload(downloadId: Long, release: AppRelease) {
        scope.launch {
            while (true) {
                delay(1000)
                val query = withContext(Dispatchers.IO) {
                    DirectUpdateDownloader.queryDownload(context, downloadId)
                }
                when (query.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        downloadState = DirectDownloadState.ReadyToInstall
                        return@launch
                    }
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_PAUSED -> {
                        val progress = if (query.totalBytes > 0) {
                            query.downloadedBytes.toFloat() / query.totalBytes.toFloat()
                        } else -1f
                        downloadState = DirectDownloadState.Downloading(progress)
                    }
                    else -> {
                        // م2: تنزيل فاشل لا يترك ملفًا جزئيًا يُظن جاهزًا لاحقًا.
                        withContext(Dispatchers.IO) { DirectUpdateDownloader.deleteApk(context, release) }
                        downloadState = DirectDownloadState.Failed(
                            "تعذّر تنزيل التحديث. تحقق من الاتصال وحاول مجددًا، أو حمّله من المتجر."
                        )
                        return@launch
                    }
                }
            }
        }
    }

    fun doDownload(release: AppRelease) {
        downloadState = DirectDownloadState.Downloading(-1f)
        scope.launch {
            val downloadId = withContext(Dispatchers.IO) {
                runCatching { DirectUpdateDownloader.startDownload(context, release) }.getOrNull()
            }
            if (downloadId == null) {
                downloadState = DirectDownloadState.Failed(
                    "تعذّر بدء التنزيل. تحقق من الاتصال وحاول مجددًا، أو حمّله من المتجر."
                )
            } else {
                pollDownload(downloadId, release)
            }
        }
    }

    // نقطة الدخول الوحيدة لضغطة التنزيل المباشر: الإذن ممنوح ← التنزيل فورًا؛
    // سبق فتح الإعدادات ورُفض ← التلميح اليدوي فقط؛ لم يُطلب قط ← الشرح أولًا.
    fun requestDirectDownload(release: AppRelease) {
        when {
            InstallPermissionGate.canInstall(context) -> doDownload(release)
            InstallPermissionGate.wasAsked(context) -> {
                pendingRelease = release
                installDialog = InstallGateDialogKind.DENIED_HINT
            }
            else -> {
                pendingRelease = release
                installDialog = InstallGateDialogKind.EXPLAIN
            }
        }
    }

    // العودة من إعدادات التثبيت: إن مُنح الإذن يبدأ التنزيل فورًا، وإلا تُعرض
    // نافذة التلميح اليدوي مرة واحدة (بلا أي فتح تلقائي آخر للإعدادات).
    val installSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val release = pendingRelease
        pendingRelease = null
        if (release != null && InstallPermissionGate.canInstall(context)) {
            doDownload(release)
        } else if (release != null) {
            installDialog = InstallGateDialogKind.DENIED_HINT
        }
    }

    // استعادة حالة تنزيل سابق عند ظهور إصدار متاح: ملف مكتمل ← جاهز للتثبيت،
    // وتنزيل جارٍ لنفس الإصدار ← متابعة الاستعلام عنه (بلا إعادة تنزيل).
    LaunchedEffect(result) {
        val release = (result as? UpdateCheckResult.Available)?.release ?: return@LaunchedEffect
        windowsRelease = (result as? UpdateCheckResult.Available)?.release?.windows
            ?: withContext(Dispatchers.IO) { AppUpdateManager.cachedRelease(context)?.windows }
        if (downloadState !is DirectDownloadState.Idle) return@LaunchedEffect
        if (DirectUpdateDownloader.hasDownloadedApk(context, release)) {
            downloadState = DirectDownloadState.ReadyToInstall
            return@LaunchedEffect
        }
        val lastId = DirectUpdateDownloader.lastDownloadId(context)
        if (lastId != 0L && DirectUpdateDownloader.lastDownloadVersionCode(context) == release.versionCode) {
            val query = withContext(Dispatchers.IO) {
                DirectUpdateDownloader.queryDownload(context, lastId)
            }
            if (query.status == DownloadManager.STATUS_RUNNING ||
                query.status == DownloadManager.STATUS_PENDING ||
                query.status == DownloadManager.STATUS_PAUSED
            ) {
                val progress = if (query.totalBytes > 0) {
                    query.downloadedBytes.toFloat() / query.totalBytes.toFloat()
                } else -1f
                downloadState = DirectDownloadState.Downloading(progress)
                pollDownload(lastId, release)
            }
        }
    }

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
                    Text("يتوفر إصدار جديد: ${release.versionName}", style = MaterialTheme.typography.titleSmall, color = FocusColors.onTint(FocusColors.Green), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(release.releaseNotes.ifBlank { "تحسينات وإصلاحات جديدة." }, color = FocusColors.TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    if (release.apkUrl.isNotBlank()) {
                        // التنزيل المباشر: زر أساسي ينزّل APK من الموجز ويثبّته من داخل
                        // التطبيق؛ المتجر يبقى خيارًا احتياطيًا تحته.
                        val downloading = downloadState is DirectDownloadState.Downloading
                        Button(
                            onClick = {
                                if (downloadState is DirectDownloadState.ReadyToInstall) {
                                    if (!DirectUpdateDownloader.installDownloadedApk(context, release)) {
                                        downloadState = DirectDownloadState.Failed("تعذّر فتح ملف التحديث. أعد التنزيل أو حمّله من المتجر.")
                                    }
                                } else {
                                    requestDirectDownload(release)
                                }
                            },
                            enabled = !downloading,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(
                                when (downloadState) {
                                    is DirectDownloadState.ReadyToInstall -> "تثبيت التحديث · ${release.versionName}"
                                    is DirectDownloadState.Failed -> "إعادة تنزيل التحديث"
                                    else -> "تنزيل وتثبيت · ${release.versionName}"
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        when (val state = downloadState) {
                            is DirectDownloadState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                Text(if (state.progress >= 0f) "جارٍ التنزيل… ${(state.progress * 100).toInt()}%" else "جارٍ التنزيل…", color = FocusColors.TextSecondary)
                            }
                            is DirectDownloadState.ReadyToInstall -> Text("اكتمل التنزيل. اضغط «تثبيت التحديث» وسيُفتح مثبّت النظام؛ وافق على التثبيت لتحديث النسخة الحالية مع بقاء بياناتك.", color = FocusColors.TextSecondary)
                            is DirectDownloadState.Failed -> Text(state.message, color = FocusColors.Error)
                            is DirectDownloadState.Idle -> Text("يُنزَّل التحديث مباشرةً من داخل التطبيق دون فتح المتجر، ويُثبَّت فوق النسخة الحالية مع بقاء بياناتك.", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        if (release.storeUrl.isNotBlank()) {
                            TextButton(onClick = { openReleaseLink(context, release) }, modifier = Modifier.fillMaxWidth()) {
                                Text("أو حمّل من المتجر")
                            }
                        } else {
                            TextButton(onClick = { openReleaseLink(context, release) }, modifier = Modifier.fillMaxWidth()) {
                                Text("تفاصيل التحديث")
                            }
                        }
                    } else if (release.storeUrl.isNotBlank()) {
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
        // مدخل ويندوز (windows-sync): يظهر فقط عندما ينشر الموجز نسخة ويندوز، وضغطته
        // تفتح رابط MSI في المتصفح — بلا تنزيل داخل التطبيق وبلا أذونات جديدة.
        windowsRelease?.let { windows ->
            Spacer(Modifier.height(14.dp))
            PreferenceCard(
                title = "نسخة ويندوز",
                description = "مُنظِّم على حاسوبك بنفس مهامك وبيانات «حياتي»، وتتزامن مع هاتفك."
            ) {
                Text(
                    "الإصدار ${windows.version} لنظام ويندوز · الحجم ${formatWindowsSize(windows.sizeBytes)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "ثبّت نسخة ويندوز على حاسوبك، ثم سجّل فيها بنفس معرّف حسابك وكلمة مرور المزامنة لتظهر مهامك وبياناتك هناك.",
                    color = FocusColors.TextSecondary
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { openWindowsLink(context, windows) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("تنزيل نسخة ويندوز") }
            }
        }
        installDialog?.let { kind ->
            InstallPermissionDialog(
                kind = kind,
                onOpenSettings = {
                    installDialog = null
                    InstallPermissionGate.markAsked(context)
                    installSettingsLauncher.launch(InstallPermissionGate.installSettingsIntent(context))
                },
                onDismiss = { installDialog = null; pendingRelease = null }
            )
        }
        if (!AppUpdateManager.configured()) {
            Spacer(Modifier.height(10.dp))
            Text("سيظل الإصدار الحالي يعمل. لإشعارات الإصدارات القادمة، يجب ربط موجز الإصدارات وصفحة التطبيق في المتجر.", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatWindowsSize(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 100) "${mb.toInt()} م.ب" else "${"%.1f".format(mb)} م.ب"
}

private fun openWindowsLink(context: Context, windows: WindowsRelease) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(windows.downloadUrl)))
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
