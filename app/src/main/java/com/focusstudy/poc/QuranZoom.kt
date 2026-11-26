package com.focusstudy.poc

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * زوم قارئ المصحف + بناء نص الصفحة بتدفق متصل كما في المصحف.
 *
 * الزوم 5 مستويات (0..4، الافتراضي 2 = الحجم الحالي): تصغير الخط يُظهر آيات
 * أكثر من الصفحة نفسها على الشاشة، وتكبيره يوضح الآيات. انتماء الآيات
 * للصفحات الـ 604 لا يتغير أبدًا بالزوم — الزوم يغيّر ما يظهر على الشاشة
 * فقط، فتبقى خريطة صفحات مصحف المدينة صحيحة.
 *
 * حدود الصدق (انظر تقرير المهمة): ملف النص المضمّن يحمل حدود الصفحات لا
 * حدود السطور؛ فالتدفق المتصل المبرَّر وارتفاع السطر الثابت هما أقرب ما
 * يمكن بالبيانات الحالية، أما مطابقة فواصل السطور الـ 15 حرفيًا لكل صفحة
 * فتحتاج بيانات فواصل سطور غير مضمّنة — فلا يُدَّعى هنا تطابق لم يتحقق.
 */
object QuranZoom {
    const val MIN_LEVEL = 0
    const val MAX_LEVEL = 4
    const val DEFAULT_LEVEL = 2

    /** معامل حجم كل مستوى: 75٪، 88٪، 100٪، 115٪، 130٪. */
    fun scaleFor(level: Int): Float = when (clamp(level)) {
        0 -> 0.75f
        1 -> 0.88f
        2 -> 1f
        3 -> 1.15f
        else -> 1.30f
    }

    fun clamp(level: Int): Int = level.coerceIn(MIN_LEVEL, MAX_LEVEL)

    /** حجم خط الآية: 21sp عند الافتراضي (كما كان قبل الزوم). */
    fun ayahFontSize(level: Int): TextUnit = (21f * scaleFor(level)).sp

    /** ارتفاع سطر ثابت متناسب مع الخط: 40sp عند الافتراضي. */
    fun ayahLineHeight(level: Int): TextUnit = (40f * scaleFor(level)).sp

    /** حجم خط البسملة: 22sp عند الافتراضي. */
    fun basmalahFontSize(level: Int): TextUnit = (22f * scaleFor(level)).sp

    fun basmalahLineHeight(level: Int): TextUnit = (40f * scaleFor(level)).sp

    /** حجم خط عنوان السورة داخل الصفحة: 18sp عند الافتراضي. */
    fun headingFontSize(level: Int): TextUnit = (18f * scaleFor(level)).sp

    fun headingLineHeight(level: Int): TextUnit = (34f * scaleFor(level)).sp

    /** قطر علامة نهاية الآية: 30sp عند الافتراضي. */
    fun markerSize(level: Int): TextUnit = (30f * scaleFor(level)).sp

    /** حجم رقم الآية داخل الدائرة، يصغر مع زيادة خاناته كما كان. */
    fun markerNumberSize(digits: Int, scale: Float): TextUnit {
        val base = when (digits) {
            1 -> 14f
            2 -> 13f
            else -> 11f
        }
        return (base * scale).sp
    }

    // ——— الحفظ: نفس ملف تفضيلات التطبيق، مفتاح مستقل ———

    private const val KEY_ZOOM_LEVEL = "quran_zoom_level"

    fun loadZoomLevel(context: Context): Int {
        val prefs = context.getSharedPreferences(FocusPreferences.FILE, Context.MODE_PRIVATE)
        return clamp(prefs.getInt(KEY_ZOOM_LEVEL, DEFAULT_LEVEL))
    }

    fun saveZoomLevel(context: Context, level: Int) {
        context.getSharedPreferences(FocusPreferences.FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ZOOM_LEVEL, clamp(level))
            .apply()
    }
}

/**
 * نص صفحة كاملة بتدفق متصل واحد (لا نص مستقل لكل آية):
 * - آيات الصفحة تتدفق وراء بعضها مبرَّرة الطرفين كما في المصحف المطبوع،
 *   بلا فراغات بين الآيات؛
 * - عنوان السورة سطر متمركز داخل التدفق عند بدء سورة جديدة في الصفحة؛
 * - البسملة سطر متمركز مستقل كما في المصحف؛
 * - علامة نهاية كل آية محتوى مضمّن بمعرّف فريد (سورة_آية) ورقمها.
 *
 * ارتفاع السطر ثابت لكل مستوى زوم (لا ارتفاع تلقائي)، وانتماء الآيات
 * للصفحة مأخوذ من خريطة الصفحات الـ 604 نفسها — فلا تتغير الصفحة بالزوم.
 */
data class QuranPageContent(
    val text: AnnotatedString,
    /** معرّف المحتوى المضمّن ← رقم الآية، لبناء AyahEndMarker في الشاشة. */
    val markers: Map<String, Int>
)

fun buildQuranPageContent(index: QuranIndex, ayahs: List<QuranAyah>, level: Int): QuranPageContent {
    val markers = LinkedHashMap<String, Int>()
    val text = buildAnnotatedString {
        for (ayah in ayahs) {
            // عنوان سورة تبدأ في هذه الصفحة (آية 1 تعني بدء السورة هنا).
            if (ayah.ayah == 1) {
                val surahName = index.surahs.getOrNull(ayah.surah - 1)?.name
                if (surahName != null) {
                    withStyle(
                        ParagraphStyle(
                            textAlign = TextAlign.Center,
                            lineHeight = QuranZoom.headingLineHeight(level)
                        )
                    ) {
                        withStyle(
                            SpanStyle(
                                fontSize = QuranZoom.headingFontSize(level),
                                fontWeight = FontWeight.SemiBold,
                                color = FocusColors.onTint(FocusColors.Teal)
                            )
                        ) {
                            append("سُورَةُ $surahName")
                        }
                    }
                    append("\n")
                }
            }
            val (basmalah, rest) = QuranData.splitBasmalah(index, ayah)
            if (basmalah != null) {
                withStyle(
                    ParagraphStyle(
                        textAlign = TextAlign.Center,
                        lineHeight = QuranZoom.basmalahLineHeight(level)
                    )
                ) {
                    withStyle(
                        SpanStyle(
                            fontSize = QuranZoom.basmalahFontSize(level),
                            fontWeight = FontWeight.SemiBold
                        )
                    ) {
                        append(basmalah)
                    }
                }
                append("\n")
            }
            val display = if (rest.isBlank()) ayah.text else rest
            withStyle(ParagraphStyle(textAlign = TextAlign.Justify)) {
                append(display)
                append(" ")
                val markerId = "m${ayah.surah}_${ayah.ayah}"
                markers[markerId] = ayah.ayah
                appendInlineContent(markerId, "[" + QuranData.arabicDigits(ayah.ayah) + "]")
            }
        }
    }
    return QuranPageContent(text, markers)
}

/**
 * قرص (pinch) للتكبير/التصغير يتعايش مع التمرير العمودي: لا يستهلك أي حدث
 * إلا عندما يكون إصبعان على الشاشة فعلًا؛ فإيماءة الإصبع الواحد (تمرير)
 * تمر كما هي دون مساس. كل تغير تراكمي ±12٪ في المسافة بين الإصبعين =
 * مستوى زوم واحد، فينقر الزوم بين المستويات الخمسة بدل الانزلاق المستمر.
 */
fun Modifier.quranPinchZoom(onStep: (Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var previousDistance: Float? = null
        var cumulative = 1f
        while (true) {
            // awaitPointerEvent دالة عضو في AwaitPointerEventScope — لا تُستورد.
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size < 2) {
                if (pressed.isEmpty()) break
                previousDistance = null
                cumulative = 1f
                continue
            }
            val distance = (pressed[0].position - pressed[1].position).getDistance()
            val prev = previousDistance
            previousDistance = distance
            if (prev != null && prev > 0f && distance > 0f) {
                cumulative *= distance / prev
                if (cumulative >= 1.12f) {
                    onStep(+1)
                    cumulative = 1f
                } else if (cumulative <= 1f / 1.12f) {
                    onStep(-1)
                    cumulative = 1f
                }
                event.changes.forEach { it.consume() }
            }
        }
    }
}
