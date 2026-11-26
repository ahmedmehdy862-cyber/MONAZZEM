package com.focusstudy.poc

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * شاشة شرح تمرين واحد (0.34.0): رسم توضيحي مرسوم + الماكينة أو الأدوات +
 * الخطوات + العضلات المستهدفة + المجموعات/التكرارات/الراحة + زر إتمام
 * يعيد استخدام سجل التمارين القائم (`exercise_logs` عبر [onToggleExercise]).
 * تُفتح بالنقر على تمرين في كتلة اللياقة في «حياتي».
 */
@Composable
internal fun ExerciseDetailScreen(
    exercise: Exercise,
    guide: ExerciseGuide?,
    doneToday: Boolean,
    onToggleDone: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text(
                exercise.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = FocusColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
            shape = RoundedCornerShape(FocusSpacing.radiusCard)
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                ExerciseArt(exerciseId = exercise.id)
                Text(
                    "رسم توضيحي مبسّط للوضعية — ليس بديلًا عن مدرب، وتوقف إن شعرت بألم.",
                    style = MaterialTheme.typography.labelSmall,
                    color = FocusColors.TextMuted
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailChip("${exercise.sets} مجموعات")
            DetailChip(exercise.reps)
            DetailChip("راحة ${exercise.restSec} ث")
        }
        if (guide != null) {
            if (guide.machine != null) {
                DetailSection(title = "الماكينة", body = guide.machine)
            }
            if (guide.equipment.isNotEmpty()) {
                DetailSection(title = "الأدوات المطلوبة", body = guide.equipment.joinToString(" · "))
            }
            if (guide.targetMuscles.isNotEmpty()) {
                DetailSection(title = "العضلات المستهدفة", body = guide.targetMuscles.joinToString(" · "))
            }
            if (guide.steps.isNotEmpty()) {
                Text(
                    "خطوات التنفيذ",
                    style = MaterialTheme.typography.titleSmall,
                    color = FocusColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                guide.steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            "${index + 1}. ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = FocusColors.onTint(FocusColors.Blue),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            step,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FocusColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            guide.source?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = FocusColors.TextMuted)
            }
        }
        if (doneToday) {
            Button(
                onClick = { onToggleDone(false) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("✓ سجّلت إتمام التمرين اليوم — اضغط للتراجع") }
        } else {
            OutlinedButton(
                onClick = { onToggleDone(true) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("سجّلت إتمام التمرين") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DetailChip(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.SurfaceHigh),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = FocusColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun DetailSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = FocusColors.TextSecondary
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = FocusColors.TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * مسار شاشة شرح التمرين (0.34.0): يحلّ معرّف التمرين إلى التمرين، ويحمّل
 * حالة الإتمام لليوم من `exercise_logs`، ويعيد للخلف إن كان المعرّف مجهولًا.
 */
@Composable
internal fun ExerciseDetailRoute(exerciseId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val life = remember(context) { LifeRepository.get(context) }
    val todayKey = remember { LocalDate.now(AppTimeZone.zone(context)).toString() }
    val exercise = remember(exerciseId) { exerciseId?.let { id -> allExercises().firstOrNull { it.id == id } } }
    var doneToday by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(exerciseId) {
        doneToday = runCatching {
            life.exerciseLogsSince(todayKey).any { it.day == todayKey && it.exerciseId == exerciseId }
        }.getOrDefault(false)
    }
    LaunchedEffect(exercise) { if (exercise == null) onBack() }
    if (exercise == null) return
    ExerciseDetailScreen(
        exercise = exercise,
        guide = exerciseGuide(exercise.id),
        doneToday = doneToday,
        onToggleDone = { done ->
            doneToday = done
            scope.launch { runCatching { life.setExerciseDone(exercise.id, todayKey, done) } }
        },
        onBack = onBack
    )
}
