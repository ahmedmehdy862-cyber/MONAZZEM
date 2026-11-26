package com.focusstudy.poc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CompanionStyle(val title: String, val mark: String, val motto: String, val tint: Color) {
    companion object {
        val all = listOf(
            CompanionStyle("المستكشف", "🧭", "خطوة ورا خطوة", FocusColors.Blue),
            CompanionStyle("العالِم", "🔭", "فضولك يقودك", FocusColors.Teal),
            CompanionStyle("البطل", "⚡", "أنت قدّها", FocusColors.Rose),
            CompanionStyle("الفنان", "🎨", "اصنع يومك بطريقتك", FocusColors.Amber)
        )
    }
}

/** [path] is the generated cartoon shown everywhere; [sourcePath] is the private original copy. */
internal data class AvatarImage(val path: String, val sourcePath: String, val style: Int) {
    companion object { val EMPTY = AvatarImage("", "", 0) }
}

private const val AVATAR_SOURCE_NAME = "student_companion_original.jpg"

/** Older installs saved the raw photo as the displayed avatar; it can still seed a cartoon. */
internal fun avatarSource(avatar: AvatarImage): String =
    if (avatar.sourcePath.isNotBlank()) avatar.sourcePath else avatar.path

/** Picks a photo, keeps the original in app-private storage and draws the cartoon offline. */
internal fun copyAvatarToAppFiles(context: Context, uri: Uri, style: Int, depth: AvatarDepth = AvatarDepth.FLAT): AvatarImage? = runCatching {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: return@runCatching null
    val scale = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height))
    val prepared = if (scale < 1f) Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true
    ) else bitmap
    val source = File(context.filesDir, AVATAR_SOURCE_NAME)
    source.outputStream().use { prepared.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    val cartoon = CartoonAvatar.stylize(prepared, style, depth)
    val output = File(context.filesDir, CartoonAvatar.fileName(style))
    output.outputStream().use { cartoon.compress(Bitmap.CompressFormat.PNG, 100, it) }
    cartoon.recycle()
    if (prepared !== bitmap) prepared.recycle()
    bitmap.recycle()
    AvatarImage(output.absolutePath, source.absolutePath, style)
}.getOrNull()

/** Regenerates from the saved original with another style; still fully on-device. */
internal fun regenerateCartoon(context: Context, sourcePath: String, style: Int, depth: AvatarDepth = AvatarDepth.FLAT): String? = runCatching {
    val source = BitmapFactory.decodeFile(sourcePath) ?: return@runCatching null
    val cartoon = CartoonAvatar.stylize(source, style, depth)
    val output = File(context.filesDir, CartoonAvatar.fileName(style))
    output.outputStream().use { cartoon.compress(Bitmap.CompressFormat.PNG, 100, it) }
    cartoon.recycle()
    source.recycle()
    output.absolutePath
}.getOrNull()

/**
 * مختار الصورة الشخصية (المرحلة 5): صورة المستخدم تتحول إلى كرتوني أو إلى
 * «ثلاثي الأبعاد» المنمّق (عمق وتظليل على الجهاز، لا نموذج حقيقي — انظر
 * [AvatarDepth])، أو تبقى الصورة الأصلية كما هي، أو تُستبدل بإحدى الشخصيات
 * التوضيحية المدمجة دون صورة. كل شيء على الجهاز، بلا رفع وبلا خدمة خارجية.
 */
@Composable
internal fun AvatarChooser(
    avatar: AvatarImage,
    name: String,
    companionStyle: Int,
    onAvatarChanged: (AvatarImage) -> Unit,
    modifier: Modifier = Modifier,
    mode: AvatarMode = AvatarMode.CARTOON,
    onModeChanged: (AvatarMode) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedStyle by rememberSaveable(avatar.style) { mutableIntStateOf(avatar.style) }
    var selectedPresetSeed by rememberSaveable(avatar.path) { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    val depth = if (mode == AvatarMode.DEPTH_3D) AvatarDepth.DEPTH else AvatarDepth.FLAT
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val saved = withContext(Dispatchers.IO) { copyAvatarToAppFiles(context, uri, selectedStyle, depth) }
            saved?.let {
                selectedPresetSeed = null
                onAvatarChanged(it)
            }
            busy = false
        }
    }
    fun regenerate(style: Int, regenerateDepth: AvatarDepth = depth) {
        val source = avatarSource(avatar)
        if (source.isBlank()) return
        scope.launch {
            busy = true
            val path = withContext(Dispatchers.IO) { regenerateCartoon(context, source, style, regenerateDepth) }
            if (path != null) {
                selectedPresetSeed = null
                onAvatarChanged(AvatarImage(path, if (avatar.sourcePath.isNotBlank()) avatar.sourcePath else source, style))
            }
            busy = false
        }
    }
    fun applyPreset(preset: PresetCharacter) {
        scope.launch {
            busy = true
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = CartoonAvatar.stylizePreset(preset.seed, selectedStyle, depth)
                    val output = File(context.filesDir, CartoonAvatar.presetFileName(preset.seed, selectedStyle, depth))
                    output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    output.absolutePath
                }.getOrNull()
            }
            if (path != null) {
                selectedPresetSeed = preset.seed
                onAvatarChanged(AvatarImage(path, "", selectedStyle))
            }
            busy = false
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarPortrait(avatar.path, name, companionStyle, 76.dp, mode = mode, sourcePath = avatar.sourcePath)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("رفيقك الدراسي", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "نحوّل صورتك إلى شخصية كارتون على جهازك فقط. الصورة والكارتون ما بيخرجوا من الجوال.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch("image/*") }, enabled = !busy) {
                        Text(if (avatar.path.isBlank()) "اختيار صورة" else "تغيير الصورة")
                    }
                    if (avatar.path.isNotBlank() && mode != AvatarMode.PHOTO) {
                        OutlinedButton(onClick = { regenerate(selectedStyle) }, enabled = !busy) { Text("إعادة التوليد") }
                    }
                }
                if (busy) Text("جارٍ رسم الكارتون على الجهاز...", style = MaterialTheme.typography.labelSmall, color = FocusColors.TextSecondary)
            }
        }
        Text("طريقة العرض", style = MaterialTheme.typography.labelMedium, color = FocusColors.TextSecondary)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AvatarMode.entries.forEach { entry ->
                FilterChip(
                    selected = mode == entry,
                    onClick = {
                        val previous = mode
                        onModeChanged(entry)
                        // التبديل بين الكرتوني وثلاثي الأبعاد يعيد توليد الكرتون
                        // بالعمق الجديد؛ الصورة كما هي لا تحتاج توليدًا.
                        if (avatar.path.isNotBlank() && entry != AvatarMode.PHOTO && previous != AvatarMode.PHOTO) {
                            regenerate(selectedStyle, if (entry == AvatarMode.DEPTH_3D) AvatarDepth.DEPTH else AvatarDepth.FLAT)
                        }
                    },
                    label = { Text(entry.label) }
                )
            }
        }
        if (mode == AvatarMode.PHOTO) {
            Text(
                if (avatar.sourcePath.isBlank()) "اختر صورة أولًا لتظهر كما هي."
                else "تظهر صورتك الأصلية كما هي دون تحويل.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
        }
        if (mode == AvatarMode.DEPTH_3D) {
            Text(
                "«ثلاثي الأبعاد» هنا مظهر منمّق بعمق وتظليل على الجهاز، لا نموذجًا حقيقيًا — التفاصيل في قسم الصورة.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
        }
        Text("الأسلوب", style = MaterialTheme.typography.labelMedium, color = FocusColors.TextSecondary)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CartoonAvatar.STYLES.forEachIndexed { index, label ->
                FilterChip(
                    selected = selectedStyle == index,
                    onClick = {
                        selectedStyle = index
                        if (avatar.path.isNotBlank() && mode != AvatarMode.PHOTO) regenerate(index)
                    },
                    label = { Text(label) }
                )
            }
        }
        Text("شخصيات جاهزة بدون صورة", style = MaterialTheme.typography.labelMedium, color = FocusColors.TextSecondary)
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CartoonAvatar.PRESETS.forEach { preset ->
                PresetChip(
                    preset = preset,
                    style = selectedStyle,
                    selected = selectedPresetSeed == preset.seed,
                    enabled = !busy,
                    onSelect = { applyPreset(preset) }
                )
            }
        }
        TextButton(onClick = { onAvatarChanged(avatar.copy(path = "", sourcePath = "", style = selectedStyle)) }) {
            Text("تخطي الصورة")
        }
    }
}

/** شريحة شخصية جاهزة: نقطة بلون الشخصية واسمها. */
@Composable
private fun PresetChip(
    preset: PresetCharacter,
    style: Int,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    val accent = remember(preset.seed, style) {
        CartoonAvatar.paletteForSeed(preset.seed, style).accent
    }
    FilterChip(
        selected = selected,
        onClick = onSelect,
        enabled = enabled,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(14.dp).clip(CircleShape)
                        .background(Color(accent))
                )
                Spacer(Modifier.width(6.dp))
                Text(preset.name)
            }
        }
    )
}

@Composable
internal fun AvatarPortrait(
    avatarPath: String,
    name: String,
    styleIndex: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    mode: AvatarMode = AvatarMode.CARTOON,
    sourcePath: String = ""
) {
    // في وضع «صورتي كما هي» تُعرض الصورة الأصلية، لا الكرتون المولَّد.
    val displayPath = if (mode == AvatarMode.PHOTO && sourcePath.isNotBlank()) sourcePath else avatarPath
    val bitmap = remember(displayPath) {
        if (displayPath.isNotBlank()) runCatching { BitmapFactory.decodeFile(displayPath)?.asImageBitmap() }.getOrNull() else null
    }
    val style = CompanionStyle.all[styleIndex.coerceIn(0, CompanionStyle.all.lastIndex)]
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().padding(3.dp)
                .border(3.dp, Brush.linearGradient(listOf(style.tint, FocusColors.Teal, style.tint)), CircleShape)
                .padding(4.dp).clip(CircleShape).background(FocusColors.SurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = "صورة رفيق المذاكرة", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(name.trim().take(1).ifBlank { "✦" }, color = FocusColors.onTint(FocusColors.Blue), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).size(size * 0.38f).clip(CircleShape)
                .background(FocusColors.Surface).border(2.dp, FocusColors.Surface, CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(style.mark, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** رسالة الرفيق من حالة اليوم — تُستخدم في البطاقة وفي ملخص صف «تفاصيل يومك». */
internal fun companionMessage(sessionActive: Boolean, completedToday: Int, urgentTitle: String?, hasPending: Boolean): String = when {
    sessionActive -> "جلسة رائعة! أنا جنبك لحد ما تخلص."
    completedToday > 0 -> "أنجزت $completedToday اليوم. كمّل بنفس الهدوء!"
    urgentTitle != null -> "نبدأ بـ $urgentTitle؟ خطوة واحدة تكفي للبداية."
    hasPending -> "اختار مهمة واحدة، وأنا هفضل أشجعك."
    else -> "يوم جديد يعني فرصة جديدة. أضف أول خطوة!"
}

@Composable
internal fun StudyCompanionCard(preferences: FocusPreferences, tasks: List<StudyTask>) {
    val session by SessionEngine.active.collectAsState()
    val today = LocalDate.now()
    val todayStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val tomorrowStart = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val completedToday = tasks.count { it.completedAt?.let { at -> at in todayStart until tomorrowStart } == true }
    val pending = tasks.filter { !it.isDone && it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED }
    val urgent = pending.firstOrNull { it.priority == TaskPriority.URGENT }
    val style = CompanionStyle.all[preferences.companionStyle.coerceIn(0, CompanionStyle.all.lastIndex)]
    val message = companionMessage(
        sessionActive = session != null,
        completedToday = completedToday,
        urgentTitle = urgent?.title,
        hasPending = pending.isNotEmpty()
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarPortrait(preferences.avatarPath, preferences.studentName, preferences.companionStyle, 62.dp, mode = preferences.avatarMode, sourcePath = preferences.avatarSourcePath)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${style.mark} رفيقك ${style.title}", style = MaterialTheme.typography.labelLarge, color = FocusColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(message, style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(style.motto, style = MaterialTheme.typography.labelSmall, color = FocusColors.onTint(FocusColors.Blue), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("✦", color = FocusColors.Amber, style = MaterialTheme.typography.titleLarge)
        }
    }
}
