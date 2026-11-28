package com.focusstudy.poc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private enum class JumpPanel { NONE, PAGE, SURAH, JUZ }

/**
 * قارئ المصحف داخل «حياتي»: يعرض صفحة من مصحف المدينة بالنص العثماني المضمّن،
 * ويفتح مباشرة على صفحة ورد اليوم، ويسجّل الورد عبر نظام الورد الحالي نفسه.
 */
@Composable
internal fun QuranReaderScreen(
    index: QuranIndex,
    initialPage: Int,
    wirdFirstPage: Int,
    wirdLastPage: Int,
    wirdDoneToday: Boolean,
    wirdCompleted: Boolean = false,
    onLogWirdToday: () -> Unit,
    onRestartWird: (() -> Unit)? = null,
    onBack: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf(initialPage.coerceIn(1, 604)) }
    var panel by remember { mutableStateOf(JumpPanel.NONE) }
    var jumpPageText by rememberSaveable { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val chipsScrollState = rememberScrollState()
    LaunchedEffect(page) { scrollState.scrollTo(0) }

    // زوم القارئ (5 مستويات، الافتراضي 2): يُحفظ فور تغييره فيبقى بعد إعادة
    // التشغيل. القارئ يقرأ القيمة المخزنة مرة عند أول تركيب؛ وrememberSaveable
    // تحفظ آخر قيمة داخل الجلسة. الدالة تقرأ zoomLevel حيًا عبر الـ delegate
    // فلا تُحبس قيمة قديمة داخل معالج القرص (pointerInput بمفتاح Unit).
    val context = LocalContext.current
    var zoomLevel by rememberSaveable { mutableStateOf(QuranZoom.loadZoomLevel(context)) }
    fun setZoomLevel(level: Int) {
        val clamped = QuranZoom.clamp(level)
        if (clamped != zoomLevel) {
            zoomLevel = clamped
            QuranZoom.saveZoomLevel(context, clamped)
        }
    }
    val zoomScale = QuranZoom.scaleFor(zoomLevel)

    val ayahs = remember(page) { index.ayahsForPage(page) }
    val juz = remember(page) { index.juzForPage(page) }
    val inWirdRange = page in wirdFirstPage..wirdLastPage
    // نص الصفحة بتدفق متصل واحد (انظر [buildQuranPageContent]): آيات الصفحة
    // مبرَّرة الطرفين بلا فراغات بينها، وعنوان السورة والبسملة سطران
    // متمركزان داخل التدفق — كما في المصحف المطبوع قدر ما تسمح البيانات.
    val pageContent = remember(index, page, zoomLevel) {
        buildQuranPageContent(index, ayahs, zoomLevel)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "المصحف",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "صفحة $page من 604 · الجزء ${QuranData.arabicDigits(juz)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // أزرار الزوم: «−» و«+» مستوى بمستوى، والنسبة في الوسط تعيد للافتراضي
        // (100٪) عند الضغط عليها. القرص بإصبعين على النص يفعل الشيء نفسه.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { setZoomLevel(zoomLevel - 1) },
                enabled = zoomLevel > QuranZoom.MIN_LEVEL
            ) { Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            TextButton(onClick = { setZoomLevel(QuranZoom.DEFAULT_LEVEL) }) {
                Text(
                    "حجم الخط ${(zoomScale * 100).roundToInt()}٪",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusColors.TextSecondary
                )
            }
            TextButton(
                onClick = { setZoomLevel(zoomLevel + 1) },
                enabled = zoomLevel < QuranZoom.MAX_LEVEL
            ) { Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(4.dp))

        Column(
            Modifier.weight(1f).verticalScroll(scrollState).quranPinchZoom { step ->
                setZoomLevel(zoomLevel + step)
            }
        ) {
            if (ayahs.isNotEmpty()) {
                Text(
                    text = pageContent.text,
                    inlineContent = pageContent.markers.mapValues { (_, number) ->
                        InlineTextContent(
                            Placeholder(
                                width = QuranZoom.markerSize(zoomLevel),
                                height = QuranZoom.markerSize(zoomLevel),
                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                            )
                        ) { AyahEndMarker(number = number, scale = zoomScale) }
                    },
                    fontSize = QuranZoom.ayahFontSize(zoomLevel),
                    lineHeight = QuranZoom.ayahLineHeight(zoomLevel),
                    color = FocusColors.TextPrimary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (ayahs.isEmpty()) {
                Text(
                    "تعذّر عرض هذه الصفحة.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusColors.TextSecondary
                )
            }
            Spacer(Modifier.height(8.dp))
            // A7: نسبة Tanzil انتقلت إلى «تخصيص تجربتك» ← «مصادر المحتوى»؛
            // لا سطر ترخيص تحت كل صفحة مصحف.
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { if (page > 1) page-- },
                enabled = page > 1,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) { Text("الصفحة السابقة") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (page < 604) page++ },
                enabled = page < 604,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) { Text("الصفحة التالية") }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { panel = if (panel == JumpPanel.PAGE) JumpPanel.NONE else JumpPanel.PAGE }) {
                Text("اذهب إلى صفحة")
            }
            TextButton(onClick = { panel = if (panel == JumpPanel.SURAH) JumpPanel.NONE else JumpPanel.SURAH }) {
                Text("سورة")
            }
            TextButton(onClick = { panel = if (panel == JumpPanel.JUZ) JumpPanel.NONE else JumpPanel.JUZ }) {
                Text("جزء")
            }
        }
        when (panel) {
            JumpPanel.PAGE -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = jumpPageText,
                    onValueChange = { jumpPageText = normalizeInputDigits(it).filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text("رقم الصفحة (1–604)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        jumpPageText.toIntOrNull()?.let { page = it.coerceIn(1, 604) }
                        panel = JumpPanel.NONE
                    },
                    shape = RoundedCornerShape(16.dp)
                ) { Text("اذهب") }
            }
            JumpPanel.SURAH -> {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(chipsScrollState),
                    horizontalArrangement = Arrangement.spacedBy(FocusSpacing.chip)
                ) {
                    index.surahs.forEach { surah ->
                        Text(
                            surah.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = FocusColors.onTint(FocusColors.Teal),
                            modifier = Modifier
                                .clip(RoundedCornerShape(FocusSpacing.radiusChip))
                                .background(FocusColors.SurfaceHigh)
                                .clickable {
                                    page = surah.page
                                    panel = JumpPanel.NONE
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            JumpPanel.JUZ -> {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(chipsScrollState),
                    horizontalArrangement = Arrangement.spacedBy(FocusSpacing.chip)
                ) {
                    (1..30).forEach { juzNumber ->
                        Text(
                            "الجزء ${QuranData.arabicDigits(juzNumber)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = FocusColors.onTint(FocusColors.Teal),
                            modifier = Modifier
                                .clip(RoundedCornerShape(FocusSpacing.radiusChip))
                                .background(FocusColors.SurfaceHigh)
                                .clickable {
                                    page = index.pageForJuz(juzNumber)
                                    panel = JumpPanel.NONE
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            JumpPanel.NONE -> Unit
        }
        Spacer(Modifier.height(6.dp))
        if (wirdCompleted) {
            // الختم كامل: لا نطاق وهمي ولا زر تسجيل مختفٍ دون سبب — حالة واضحة
            // مع خيار إعادة البدء، والصفحة المعروضة مقصوصة أصلًا إلى 604.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "🎉 ختمت المصحف كاملًا — آخر صفحة 604.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusColors.onTint(FocusColors.Green),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                if (onRestartWird != null) {
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = onRestartWird,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("ابدأ ختمة جديدة من صفحة 2") }
                }
            }
        } else if (inWirdRange && !wirdDoneToday) {
            Button(
                onClick = onLogWirdToday,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("سجّلت قراءة ورد اليوم") }
        } else if (wirdDoneToday) {
            Text(
                "تم تسجيل ورد اليوم ✓",
                style = MaterialTheme.typography.bodyMedium,
                color = FocusColors.onTint(FocusColors.Green),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        } else {
            Text(
                "هذه الصفحة خارج ورد اليوم (صفحات $wirdFirstPage–$wirdLastPage).",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * علامة نهاية الآية: رقم الآية داخل دائرة مرسومة.
 *
 * كان الرقم يُعرض كنص بعد حرف «۝» (U+06DD) — وحرف ۝ في الخطوط المتاحة زخرفة
 * صغيرة لا تتسع لرقم داخلها، فكان الرقم يظهر خارج الدائرة أو بجانبها. هنا
 * تُرسم الدائرة بـ Canvas ويُركَّز الرقم داخلها، فيبقى داخلها تمامًا في كل
 * الأحجام والخطوط والوضعين الفاتح والداكن (نفس لون النص في الحالتين).
 *
 * الحجم مضبوط بوحدة sp عبر الـ Placeholder في موضع الاستخدام، فيكبر مع تكبير
 * خط المستخدم؛ والرقم يصغر كلما زادت خاناته حتى يتسع داخل الدائرة (أكبر رقم
 * آية في المصحف 286 — ثلاث خانات). معامل `scale` هو معامل مستوى الزوم
 * الحالي، فيبقى الرقم داخل الدائرة في كل مستويات الزوم.
 */
@Composable
private fun AyahEndMarker(number: Int, scale: Float = 1f) {
    val digits = QuranData.arabicDigits(number)
    val numberSize = QuranZoom.markerNumberSize(digits.length, scale)
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 1.5.dp.toPx()
            drawCircle(
                color = FocusColors.TextPrimary,
                radius = (size.minDimension - stroke) / 2f,
                style = Stroke(width = stroke)
            )
        }
        Text(
            digits,
            fontSize = numberSize,
            lineHeight = numberSize,
            color = FocusColors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
