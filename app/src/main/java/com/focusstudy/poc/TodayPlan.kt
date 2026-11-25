package com.focusstudy.poc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One group of the daily plan. */
enum class DayBucket(val label: String) {
    OVERDUE("متأخرة عن اليوم"),
    TODAY("اليوم"),
    UNDATED("بلا موعد"),
}

data class DayGroup(val bucket: DayBucket, val tasks: List<StudyTask>)

/**
 * The plate for today: what is late, what is due today and what still has no date.
 *
 * Future work stays out of the way (it lives in مهامي), so opening the app always answers
 * “ماذا أفعل الآن؟” instead of listing everything at once.
 */
object DayPlan {

    fun build(tasks: List<StudyTask>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<DayGroup> {
        val open = tasks.filter { !it.isDone && it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED }
        fun dateOf(task: StudyTask): LocalDate? =
            task.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

        val overdue = open.filter { dateOf(it)?.isBefore(today) == true }
        val todayTasks = open.filter { dateOf(it) == today }
        val undated = open.filter { it.dueAt == null }

        fun order(list: List<StudyTask>) = list.sortedWith(
            compareBy<StudyTask> { it.dueAt ?: Long.MAX_VALUE }
                .thenByDescending { it.priority.level }
                .thenBy { it.sortOrder }
        )

        return listOf(
            DayGroup(DayBucket.OVERDUE, order(overdue)),
            DayGroup(DayBucket.TODAY, order(todayTasks)),
            DayGroup(DayBucket.UNDATED, order(undated))
        ).filter { it.tasks.isNotEmpty() }
    }

    fun timeLabel(task: StudyTask, zone: ZoneId = ZoneId.systemDefault()): String {
        val due = task.dueAt ?: return "بلا وقت"
        val dateTime = Instant.ofEpochMilli(due).atZone(zone)
        val clock = dateTime.toLocalTime()
        // Stored date-only tasks sit at midnight, so a midnight value reads as “طوال اليوم”.
        return if (clock.hour == 0 && clock.minute == 0) "طوال اليوم"
        else dateTime.format(DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("ar-EG")))
    }
}

/** Compact daily list: tick what is done, tap a row to start a focus session. */
@Composable
internal fun TodayPlanSection(
    tasks: List<StudyTask>,
    onToggleDone: (StudyTask) -> Unit,
    onStartTask: (Long) -> Unit,
    onScheduleToday: (StudyTask) -> Unit,
    onAddTask: () -> Unit
) {
    val groups = DayPlan.build(tasks, LocalDate.now())
    val openCount = groups.sumOf { it.tasks.size }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard)
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = FocusSpacing.section)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = FocusSpacing.screen),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "خطة اليوم",
                        style = MaterialTheme.typography.titleSmall,
                        color = FocusColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (openCount == 0) "كل شيء واضح، يومك خالٍ من المهام."
                        else "$openCount مهمة تنتظرك اليوم",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary
                    )
                }
            }

            if (groups.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = FocusSpacing.screen, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FocusLogo(size = 56.dp, showTile = false)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "يومك خالٍ، وكل شيء واضح",
                        style = MaterialTheme.typography.titleSmall,
                        color = FocusColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "أضف مهمة وسنضعها في خطة اليوم، مع وقتها وأولويتها.",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = onAddTask,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = FocusColors.Blue.copy(alpha = 0.18f),
                            contentColor = FocusColors.onTint(FocusColors.Blue)
                        )
                    ) { Text("أضف مهمة") }
                }
                return@Column
            }

            groups.forEach { group ->
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = FocusSpacing.screen),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        group.bucket.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = when (group.bucket) {
                            DayBucket.OVERDUE -> FocusColors.onTint(FocusColors.Rose)
                            DayBucket.TODAY -> FocusColors.onTint(FocusColors.Blue)
                            DayBucket.UNDATED -> FocusColors.TextMuted
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(FocusColors.SurfaceHigh)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${group.tasks.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = FocusColors.TextSecondary
                        )
                    }
                }
                group.tasks.forEach { task ->
                    UnifiedTaskCard(
                        task = task,
                        today = LocalDate.now(),
                        density = TaskCardDensity.COMPACT,
                        onToggleDone = { onToggleDone(task) },
                        onStart = { onStartTask(task.id) },
                        onScheduleToday = { onScheduleToday(task) }
                    )
                    HorizontalDivider(color = FocusColors.Outline.copy(alpha = 0.6f), modifier = Modifier.padding(start = 56.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
