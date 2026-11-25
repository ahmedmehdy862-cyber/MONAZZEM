package com.focusstudy.poc

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * الكثافة الوحيدة المسموح بها لعرض المهمة — نمطان من المكوّن نفسه، لا مكوّنان منفصلان:
 *
 * - [COMPACT]: صف مدمج يعيش داخل بطاقة خارجية (خطة اليوم، مهام «حياتي»، كتل «الخطة»،
 *   مهام «الأهداف»): عنوان سطر واحد + سطر بيانات واحد + زر واحد على الأكثر.
 * - [FULL]: بطاقة كاملة مستقلة («مهامي»): شريط تقدّم وشارات وكل الإجراءات.
 *
 * اللون المميز دلالي دائمًا ([priorityColor]): وردي للعاجلة، كهرماني للمرتفعة، أزرق
 * للمتوسطة، رمادي للمنخفضة. لا يوجد لون دوري حسب موضع الصف إطلاقًا.
 */
enum class TaskCardDensity {
    COMPACT,
    FULL
}

/** Priority at a glance: urgent reads red, high amber, medium blue, low stays quiet. */
internal fun priorityColor(priority: TaskPriority): Color = when (priority) {
    TaskPriority.URGENT -> FocusColors.Rose
    TaskPriority.HIGH -> FocusColors.Amber
    TaskPriority.MEDIUM -> FocusColors.Blue
    TaskPriority.LOW -> FocusColors.TextMuted
}

/**
 * العرض الوحيد للمهمة في كل التطبيق.
 *
 * الإجراءات كلها اختيارية: الصف المدمج يعرض منها ما يُمرَّر فقط (مربع الإنجاز وزر البدء
 * مثلًا)، والبطاقة الكاملة تعرضها كلها. لا يعرض المكوّن زرًا بلا إجراء خلفه.
 *
 * @param subtitle سطر بيانات بديل للصف المدمج؛ عند غيابه يُبنى من وقت المهمة وأولويتها
 * وتكرارها. يُتجاهل في البطاقة الكاملة حيث تظهر الشارات كاملة.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun UnifiedTaskCard(
    task: StudyTask,
    today: LocalDate,
    modifier: Modifier = Modifier,
    typeLabel: String = "",
    subjectName: String? = null,
    density: TaskCardDensity = TaskCardDensity.FULL,
    subtitle: String? = null,
    onToggleDone: (() -> Unit)? = null,
    onStart: (() -> Unit)? = null,
    onScheduleToday: (() -> Unit)? = null,
    onEditBlocked: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    onReschedule: ((Long?) -> Unit)? = null,
    onSubtasks: (() -> Unit)? = null
) {
    // اللون المميز دلالي فقط — من الأولوية، لا من موضع الصف في القائمة.
    val accent = priorityColor(task.priority)
    val accentText = FocusColors.onTint(accent)

    if (density == TaskCardDensity.COMPACT) {
        Row(
            modifier = modifier.fillMaxWidth()
                .then(if (onStart != null) Modifier.clickable(onClick = onStart) else Modifier)
                .heightIn(min = FocusSpacing.rowMinHeight)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onToggleDone != null) {
                Checkbox(
                    checked = TaskState.completed(task),
                    onCheckedChange = { onToggleDone() },
                    colors = CheckboxDefaults.colors(checkedColor = FocusColors.Green)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(6.dp).clip(CircleShape)
                            .background(accent)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = subtitle ?: compactMetaLine(task),
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            if (task.dueAt == null && onScheduleToday != null) {
                BoxedAction("اليوم") { onScheduleToday() }
            } else if (onStart != null) {
                BoxedAction("ابدأ", onClick = onStart)
            }
        }
        return
    }

    val animated by animateFloatAsState(
        targetValue = task.progress,
        animationSpec = tween(800),
        label = "task-progress"
    )
    val zone = AppTimeZone.zone(LocalContext.current)
    fun startOfDayAfter(days: Long): Long =
        today.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
    val todayStart = startOfDayAfter(0)
    val tomorrowStart = startOfDayAfter(1)
    var moreExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth()
            .then(if (onStart != null) Modifier.clickable(onClick = onStart) else Modifier),
        shape = RoundedCornerShape(FocusSpacing.radiusCard),
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.05f))))
            )
            Column(modifier = Modifier.padding(FocusSpacing.card)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(accent))
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (onToggleDone != null) {
                        Checkbox(
                            checked = TaskState.completed(task),
                            onCheckedChange = { onToggleDone() },
                            colors = CheckboxDefaults.colors(checkedColor = FocusColors.Green)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.22f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = TaskState.badge(task, today, zone),
                            style = MaterialTheme.typography.labelMedium,
                            color = accentText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(FocusSpacing.section))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(FocusSpacing.progressHeight)
                            .clip(RoundedCornerShape(FocusSpacing.progressHeight))
                            .background(FocusColors.SurfaceHigh)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animated.coerceIn(0f, 1f))
                                .fillMaxSize()
                                .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.55f))))
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${(animated * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = accentText
                    )
                }

                Spacer(Modifier.height(FocusSpacing.item))

                Text(
                    text = TaskState.progressText(task),
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )

                Spacer(Modifier.height(FocusSpacing.item))

                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(FocusSpacing.chip),
                    verticalArrangement = Arrangement.spacedBy(FocusSpacing.chip)
                ) {
                    MetaChip(
                        label = (subjectName?.let { "$it · " } ?: "") + typeLabel.ifBlank { task.category.label }
                    )
                    MetaChip(label = task.priority.label, dot = accent)
                    MetaChip(label = TaskState.dueText(task, zone))
                    if (task.recurrence != Recurrence.NONE) {
                        MetaChip(label = RecurrenceEngine.label(task.recurrence))
                    }
                    MetaChip(
                        label = if (task.blockedPackages.isEmpty()) "بلا تطبيقات محددة"
                        else "${task.blockedPackages.size} تطبيقًا محجوبًا"
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = FocusColors.Outline
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onStart != null) {
                        FilledTonalButton(
                            onClick = onStart,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = accent.copy(alpha = 0.22f),
                                contentColor = accentText
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text("ابدأ جلسة", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (onEditBlocked != null) {
                        TextButton(onClick = onEditBlocked) {
                            Text("التطبيقات", color = FocusColors.TextSecondary)
                        }
                    }

                    // المجموعة الخلفية (حذف/تعديل/المزيد) داخل Box بعرض المساحة الحرة كلها،
                    // والأزرار مثبّتة عند طرفها (اليسار في RTL). قائمة «المزيد» ابنة مباشرة
                    // لهذا الـ Box فترتكز عليه: تنفتح يسارًا من حافته اليمنى وتبقى داخل الشاشة.
                    if (onDelete != null || onEdit != null || onReschedule != null || onDuplicate != null || onSubtasks != null) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (onDelete != null) {
                                    TextButton(onClick = onDelete) {
                                        Text("حذف", color = FocusColors.onTint(FocusColors.Rose))
                                    }
                                }
                                if (onEdit != null) {
                                    TextButton(onClick = onEdit) {
                                        Text("تعديل", color = FocusColors.onTint(FocusColors.Green))
                                    }
                                }
                                if (onReschedule != null || onDuplicate != null || onSubtasks != null) {
                                    TextButton(onClick = { moreExpanded = true }) { Text("المزيد") }
                                }
                            }
                            DropdownMenu(
                                expanded = moreExpanded,
                                onDismissRequest = { moreExpanded = false },
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                if (onSubtasks != null) {
                                    DropdownMenuItem(
                                        text = { Text("المهام الفرعية", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = { moreExpanded = false; onSubtasks() }
                                    )
                                }
                                if (onReschedule != null) {
                                    DropdownMenuItem(
                                        text = { Text("اليوم", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = { moreExpanded = false; onReschedule(todayStart) },
                                        enabled = !task.isDone
                                    )
                                    DropdownMenuItem(
                                        text = { Text("غدًا", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = { moreExpanded = false; onReschedule(tomorrowStart) },
                                        enabled = !task.isDone
                                    )
                                    DropdownMenuItem(
                                        text = { Text("بعد 3 أيام", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = { moreExpanded = false; onReschedule(startOfDayAfter(3)) },
                                        enabled = !task.isDone
                                    )
                                    DropdownMenuItem(
                                        text = { Text("الأسبوع القادم", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = { moreExpanded = false; onReschedule(startOfDayAfter(7)) },
                                        enabled = !task.isDone
                                    )
                                    DropdownMenuItem(
                                        text = { Text("بدون موعد", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = { moreExpanded = false; onReschedule(null) },
                                        enabled = !task.isDone && task.dueAt != null
                                    )
                                }
                                if (onDuplicate != null) {
                                    DropdownMenuItem(
                                        text = { Text("نسخ المهمة", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        onClick = { moreExpanded = false; onDuplicate() }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** سطر بيانات الصف المدمج: الأولوية دلالية أولًا، ثم وقت المهمة وتكرارها. */
private fun compactMetaLine(task: StudyTask): String {
    val time = DayPlan.timeLabel(task)
    val recurrence = if (task.recurrence != Recurrence.NONE) " · ${RecurrenceEngine.label(task.recurrence)}" else ""
    return "${task.priority.label} · $time$recurrence"
}

@Composable
private fun BoxedAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = FocusColors.onTint(FocusColors.Blue),
        modifier = Modifier
            .clip(RoundedCornerShape(FocusSpacing.radiusChip))
            .background(FocusColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

/**
 * Small info pill for the task meta row: type/subject, priority, due date, recurrence and
 * blocked apps. One visual language instead of a long comma-separated sentence.
 */
@Composable
private fun MetaChip(label: String, dot: Color? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(FocusSpacing.radiusChip))
            .background(FocusColors.SurfaceHigh)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = FocusColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
