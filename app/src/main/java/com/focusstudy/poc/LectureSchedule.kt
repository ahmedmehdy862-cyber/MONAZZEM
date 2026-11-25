package com.focusstudy.poc

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * جدول المحاضرات الأسبوعي للطالب (المرحلة 5): محاضرات بسيطة — اليوم، وقت
 * البدء/النهاية، المادة، والمكان الاختياري — محفوظة محليًا في
 * SharedPreferences بصيغة JSON، بلا جدول في قاعدة البيانات لأنها بيانات
 * عرض أسبوعية لا تستحق ترحيلًا.
 */
internal data class LectureSlot(
    val id: String,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val subject: String,
    val place: String
)

internal object LectureScheduleStore {
    private const val FILE = "lecture_schedule"
    private const val KEY_SLOTS = "slots"

    fun load(context: Context): List<LectureSlot> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SLOTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                LectureSlot(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    dayOfWeek = obj.optInt("day", DayOfWeek.SATURDAY.value),
                    startMinute = obj.optInt("start", 9 * 60),
                    endMinute = obj.optInt("end", 10 * 60),
                    subject = obj.optString("subject", ""),
                    place = obj.optString("place", "")
                )
            }.filter { it.subject.isNotBlank() && it.endMinute > it.startMinute }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, slots: List<LectureSlot>) {
        val array = JSONArray()
        slots.forEach { slot ->
            array.put(
                JSONObject()
                    .put("id", slot.id)
                    .put("day", slot.dayOfWeek)
                    .put("start", slot.startMinute)
                    .put("end", slot.endMinute)
                    .put("subject", slot.subject)
                    .put("place", slot.place)
            )
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_SLOTS, array.toString()).apply()
    }
}

/** اسم اليوم بالعربية؛ `dayOfWeek` بقيم [DayOfWeek] (1 = الاثنين … 7 = الأحد). */
internal fun arabicDayName(dayOfWeek: Int): String = when (dayOfWeek) {
    DayOfWeek.SATURDAY.value -> "السبت"
    DayOfWeek.SUNDAY.value -> "الأحد"
    DayOfWeek.MONDAY.value -> "الاثنين"
    DayOfWeek.TUESDAY.value -> "الثلاثاء"
    DayOfWeek.WEDNESDAY.value -> "الأربعاء"
    DayOfWeek.THURSDAY.value -> "الخميس"
    DayOfWeek.FRIDAY.value -> "الجمعة"
    else -> "اليوم"
}

/** دقائق منذ منتصف الليل إلى «09:30» بأرقام عربية. */
internal fun formatMinuteOfDay(minuteOfDay: Int): String {
    val time = LocalTime.of((minuteOfDay / 60).coerceIn(0, 23), (minuteOfDay % 60).coerceIn(0, 59))
    return time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("ar-EG")))
}

/** يقبل «9:30» أو «0930» أو «9.30» ويعيد دقائق منذ منتصف الليل، وإلا null. */
internal fun parseClock(raw: String): Int? {
    val digits = normalizeInputDigits(raw).filter { it.isDigit() || it == ':' }
    val parts = digits.split(":")
    val hour: Int
    val minute: Int
    if (parts.size == 2) {
        hour = parts[0].toIntOrNull() ?: return null
        minute = parts[1].toIntOrNull() ?: return null
    } else if (digits.length in 3..4 && ':' !in digits) {
        hour = digits.dropLast(2).toIntOrNull() ?: return null
        minute = digits.takeLast(2).toIntOrNull() ?: return null
    } else {
        return null
    }
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

/** أيام الأسبوع بترتيب مصري: السبت أولًا. */
private val WEEK_DAYS = listOf(
    DayOfWeek.SATURDAY.value,
    DayOfWeek.SUNDAY.value,
    DayOfWeek.MONDAY.value,
    DayOfWeek.TUESDAY.value,
    DayOfWeek.WEDNESDAY.value,
    DayOfWeek.THURSDAY.value,
    DayOfWeek.FRIDAY.value
)

/**
 * بطاقة جدول المحاضرات في الرئيسية للطالب: محاضرات اليوم أولًا، ثم إضافة
 * محاضرة جديدة وحذف محاضرة. تُعرض فقط عندما تكون الشخصية «طالب».
 */
@Composable
internal fun LectureScheduleCard() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val slots = remember(version) { LectureScheduleStore.load(context) }
    val today = LocalDate.now().dayOfWeek.value
    val todaySlots = slots.filter { it.dayOfWeek == today }.sortedBy { it.startMinute }

    var subject by rememberSaveable { mutableStateOf("") }
    var startText by rememberSaveable { mutableStateOf("") }
    var endText by rememberSaveable { mutableStateOf("") }
    var place by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableIntStateOf(today) }
    var showForm by rememberSaveable { mutableStateOf(false) }

    fun persist(next: List<LectureSlot>) {
        LectureScheduleStore.save(context, next)
        version++
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(FocusSpacing.card),
            verticalArrangement = Arrangement.spacedBy(FocusSpacing.item)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "جدول محاضراتك",
                        style = MaterialTheme.typography.titleSmall,
                        color = FocusColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "محاضرات ${arabicDayName(today)}: ${todaySlots.size} · هذا الأسبوع: ${slots.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary
                    )
                }
                TextButton(onClick = { showForm = !showForm }) {
                    Text(if (showForm) "إخفاء الإضافة" else "أضف محاضرة")
                }
            }
            if (todaySlots.isEmpty()) {
                Text(
                    "لا محاضرات اليوم — يومك حر للمذاكرة أو الراحة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
            }
            todaySlots.forEach { slot ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(FocusColors.SurfaceHigh)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "${formatMinuteOfDay(slot.startMinute)}–${formatMinuteOfDay(slot.endMinute)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = FocusColors.onTint(FocusColors.Teal)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            slot.subject,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FocusColors.TextPrimary
                        )
                        if (slot.place.isNotBlank()) {
                            Text(
                                slot.place,
                                style = MaterialTheme.typography.bodySmall,
                                color = FocusColors.TextSecondary
                            )
                        }
                    }
                    TextButton(onClick = { persist(slots.filter { it.id != slot.id }) }) {
                        Text("حذف", color = FocusColors.onTint(FocusColors.Rose))
                    }
                }
            }
            if (showForm) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WEEK_DAYS.forEach { value ->
                        FilterChip(
                            selected = day == value,
                            onClick = { day = value },
                            label = { Text(arabicDayName(value)) }
                        )
                    }
                }
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it.take(48) },
                    label = { Text("المادة (مثال: فيزياء)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it.take(5) },
                        label = { Text("البدء (9:00)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it.take(5) },
                        label = { Text("النهاية (10:30)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
                OutlinedTextField(
                    value = place,
                    onValueChange = { place = it.take(32) },
                    label = { Text("المكان (اختياري)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = {
                        val start = parseClock(startText)
                        val end = parseClock(endText)
                        if (subject.isNotBlank() && start != null && end != null && end > start) {
                            persist(
                                slots + LectureSlot(
                                    id = UUID.randomUUID().toString(),
                                    dayOfWeek = day,
                                    startMinute = start,
                                    endMinute = end,
                                    subject = subject.trim(),
                                    place = place.trim()
                                )
                            )
                            subject = ""
                            startText = ""
                            endText = ""
                            place = ""
                            showForm = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("حفظ المحاضرة") }
            }
        }
    }
}
