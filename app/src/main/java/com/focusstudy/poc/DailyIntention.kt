package com.focusstudy.poc

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime

/**
 * One line of intent for the day.
 *
 * Planning gets easier when the day has a sentence behind it, so this card asks for the smallest
 * possible answer — one sentence — keeps it for the day, and shows how many days in a row the person
 * has started with an intention. Nothing is stored in the database: a date and a string.
 */
internal object DailyIntentions {
    private const val PREFS = "focus_intention"
    private const val KEY_DAY = "day"
    private const val KEY_TEXT = "text"
    private const val KEY_STREAK = "streak"
    private const val KEY_STREAK_DAY = "streak_day"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun of(context: Context, day: LocalDate = LocalDate.now()): String {
        val prefs = prefs(context)
        return if (prefs.getLong(KEY_DAY, -1L) == day.toEpochDay()) prefs.getString(KEY_TEXT, "").orEmpty() else ""
    }

    /** Saves today's line and moves the streak forward at most once per day. */
    fun save(context: Context, text: String, day: LocalDate = LocalDate.now()) {
        val prefs = prefs(context)
        val clean = text.trim().take(140)
        prefs.edit().putLong(KEY_DAY, day.toEpochDay()).putString(KEY_TEXT, clean).apply()
        if (clean.isBlank()) return
        val lastDay = prefs.getLong(KEY_STREAK_DAY, -1L)
        if (lastDay == day.toEpochDay()) return
        val yesterday = day.minusDays(1).toEpochDay()
        val streak = if (lastDay == yesterday) prefs.getInt(KEY_STREAK, 0) + 1 else 1
        prefs.edit().putLong(KEY_STREAK_DAY, day.toEpochDay()).putInt(KEY_STREAK, streak).apply()
    }

    fun streak(context: Context, day: LocalDate = LocalDate.now()): Int {
        val prefs = prefs(context)
        val lastDay = prefs.getLong(KEY_STREAK_DAY, -1L)
        return when (lastDay) {
            day.toEpochDay(), day.minusDays(1).toEpochDay() -> prefs.getInt(KEY_STREAK, 0)
            else -> 0
        }
    }

    fun prompt(hour: Int, doneToday: Int, openToday: Int): String = when {
        hour in 0..4 -> "ليلة هادئة. سطر واحد عمّا تريد أن يبقى من يومك."
        hour in 5..11 && openToday == 0 -> "لا مهام بعد — ما الجملة التي تريد أن يحملها يومك؟"
        hour in 5..11 -> "نية واحدة تكفي: ماذا تريد أن يتحقق اليوم؟"
        hour in 12..16 -> if (doneToday > 0) "أنجزت إلى الآن. ما الذي يستحق أن تكمل به؟" else "منتصف اليوم — أعد ضبط نيتك بسطر."
        else -> "قبل أن يُغلق اليوم: ما النية التي تُنهيه عليها؟"
    }
}

@Composable
internal fun DailyIntentionCard(openToday: Int, doneToday: Int) {
    val context = LocalContext.current
    val today = LocalDate.now()
    var saved by remember { mutableStateOf(DailyIntentions.of(context, today)) }
    var draft by rememberSaveable { mutableStateOf(saved) }
    var editing by remember { mutableStateOf(saved.isBlank()) }
    val streak = remember(saved) { DailyIntentions.streak(context, today) }

    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("نية اليوم", style = MaterialTheme.typography.labelMedium, color = FocusColors.Blue)
                Spacer(Modifier.weight(1f))
                if (streak > 1) {
                    Text("$streak أيام متتالية", style = MaterialTheme.typography.labelSmall, color = FocusColors.TextMuted)
                }
            }
            Spacer(Modifier.height(4.dp))
            if (saved.isNotBlank() && !editing) {
                Text(
                    "«$saved»",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = FocusColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                TextButton(onClick = { draft = saved; editing = true }, modifier = Modifier.align(Alignment.End)) {
                    Text("تعديل")
                }
            } else {
                Text(
                    DailyIntentions.prompt(LocalTime.now().hour, doneToday, openToday),
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 140) draft = it },
                    singleLine = true,
                    placeholder = { Text("مثال: أبدأ بمهمة واحدة قبل أي شيء آخر") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    AnimatedVisibility(visible = saved.isNotBlank()) {
                        TextButton(onClick = { editing = false; draft = saved }) { Text("إلغاء") }
                    }
                    TextButton(
                        onClick = {
                            DailyIntentions.save(context, draft, today)
                            saved = DailyIntentions.of(context, today)
                            editing = false
                        },
                        enabled = draft.trim().length >= 3
                    ) { Text("احفظ النية") }
                }
            }
        }
    }
}
