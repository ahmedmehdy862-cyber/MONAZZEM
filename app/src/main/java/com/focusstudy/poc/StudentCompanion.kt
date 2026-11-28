package com.focusstudy.poc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
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

/**
 * الصورة الشخصية كما رفعها المستخدم (0.35.0: بلا تحويل إلى كرتوني — الصورة
 * تُعرض كما هي في كل مكان؛ [path] فارغ يعني بلا صورة فيُعرض الحرف الأول).
 */
internal data class AvatarImage(val path: String) {
    companion object { val EMPTY = AvatarImage("") }
}

internal const val AVATAR_FILE_NAME = "student_avatar.jpg"

/** يصحح اتجاه الصورة من EXIF؛ يعيد نفس الـ Bitmap إن لم يلزم تدوير. */
private fun orientedBitmap(decoded: Bitmap, orientation: Int): Bitmap {
    val angle = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (angle == 0f) return decoded
    val matrix = Matrix().apply { postRotate(angle) }
    val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    decoded.recycle()
    return rotated
}

/**
 * يحفظ صورة المستخدم كما هي في ملفات التطبيق الخاصة (بلا تحويل وبلا كشف
 * وجه وبلا رفع). يعمل على Dispatchers.IO داخليًا؛ يقصّر الأبعاد إلى 1024px
 * ويصحح الاتجاه من EXIF (صور الكاميرا مخزنة غالبًا مائلة 90°).
 */
internal suspend fun copyAvatarToAppFiles(context: Context, uri: Uri): AvatarImage? = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@withContext null
        val prepared = orientedBitmap(decoded, orientation)
        try {
            val output = File(context.filesDir, AVATAR_FILE_NAME)
            output.outputStream().use { prepared.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            AvatarImage(output.absolutePath)
        } finally {
            prepared.recycle()
        }
    } catch (_: Throwable) {
        null
    }
}

/**
 * مختار الصورة الشخصية (0.35.0 — بعد إزالة التحويل إلى كرتوني بأمر أحمد):
 * اختيار صورة ← حفظها كما هي ← عرضها كما هي في كل مكان. كل شيء على الجهاز،
 * بلا رفع وبلا خدمة خارجية وبلا كشف وجه.
 */
@Composable
internal fun PhotoAvatarPicker(
    avatar: AvatarImage,
    name: String,
    companionStyle: Int,
    onAvatarChanged: (AvatarImage) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    /** رسالة خطأ عابرة لاختيار الصورة؛ تُمسح عند نجاح العملية التالية. */
    var notice by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val result = copyAvatarToAppFiles(context, uri)
            if (result != null) {
                notice = null
                onAvatarChanged(result)
            } else {
                notice = "تعذّر تحميل الصورة، جرّب صورة أخرى"
            }
            busy = false
        } else {
            notice = "تعذّر تحميل الصورة، جرّب صورة أخرى"
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarPortrait(avatar.path, name, companionStyle, 76.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("رفيقك الدراسي", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "صورتك تظهر كما هي — بلا تحويل. الصورة تبقى على جهازك فقط ولا تُرفع لأي خدمة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch("image/*") }, enabled = !busy) {
                        Text(if (avatar.path.isBlank()) "اختيار صورة" else "تغيير الصورة")
                    }
                    if (avatar.path.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                val oldPath = avatar.path
                                onAvatarChanged(AvatarImage.EMPTY)
                                scope.launch(Dispatchers.IO) {
                                    runCatching { File(oldPath).delete() }
                                }
                            },
                            enabled = !busy
                        ) { Text("إزالة الصورة") }
                    }
                }
                if (busy) Text("جارٍ حفظ الصورة...", style = MaterialTheme.typography.labelSmall, color = FocusColors.TextSecondary)
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary) }
            }
        }
        TextButton(onClick = { onAvatarChanged(AvatarImage.EMPTY) }) {
            Text("تخطي الصورة")
        }
    }
}

/**
 * صورة المستخدم كما هي؛ وإن لم توجد صورة يُعرض الحرف الأول من الاسم.
 * (0.35.0: بلا وضع عرض وبلا مسار مصدر منفصل — مسار واحد فقط.)
 */
@Composable
internal fun AvatarPortrait(
    avatarPath: String,
    name: String,
    styleIndex: Int,
    size: Dp,
    modifier: Modifier = Modifier
) {
    // A4 (A-04): فك ترميز JPEG على IO لا على الخيط الرئيسي أثناء التركيب —
    // كان decodeFile متزامنًا في remember فيعيق كل إعادة تركيب.
    var bitmap by remember(avatarPath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(avatarPath) {
        bitmap = withContext(Dispatchers.IO) {
            if (avatarPath.isNotBlank()) {
                runCatching { BitmapFactory.decodeFile(avatarPath)?.asImageBitmap() }.getOrNull()
            } else {
                null
            }
        }
    }
    val style = CompanionStyle.all[styleIndex.coerceIn(0, CompanionStyle.all.lastIndex)]
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().padding(3.dp)
                .border(3.dp, Brush.linearGradient(listOf(style.tint, FocusColors.Teal, style.tint)), CircleShape)
                .padding(4.dp).clip(CircleShape).background(FocusColors.SurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            val currentBitmap = bitmap
            if (currentBitmap != null) {
                Image(currentBitmap, contentDescription = "صورة رفيق المذاكرة", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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

/**
 * بطاقة الرفيق في «تفاصيل يومك»: الضغط عليها يفتح المساعد (إصلاح 0.34.0)
 * — كانت عرضًا صامتًا لا يوصّل للمساعد، فبقي مدخل المحادثة مخفيًا.
 */
@Composable
internal fun StudyCompanionCard(
    preferences: FocusPreferences,
    tasks: List<StudyTask>,
    onOpenAssistant: () -> Unit = {}
) {
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
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAssistant),
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarPortrait(preferences.avatarPath, preferences.studentName, preferences.companionStyle, 62.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${style.mark} رفيقك ${style.title}", style = MaterialTheme.typography.labelLarge, color = FocusColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(message, style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(style.motto, style = MaterialTheme.typography.labelSmall, color = FocusColors.onTint(FocusColors.Blue), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("اضغط لفتح المساعد", style = MaterialTheme.typography.labelSmall, color = FocusColors.onTint(FocusColors.Blue), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("✦", color = FocusColors.Amber, style = MaterialTheme.typography.titleLarge)
        }
    }
}
