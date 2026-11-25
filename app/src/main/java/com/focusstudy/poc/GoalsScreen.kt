package com.focusstudy.poc

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Goals are the top of the path: each one can carry the tasks that move it forward and the skills
 * it develops, so finishing a linked task advances the goal automatically, and a skill session
 * leaves a visible trace of why the goal matters.
 */
@Composable
internal fun GoalsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { GoalRepository.get(context) }
    val scope = rememberCoroutineScope()

    var goals by remember { mutableStateOf<List<Goal>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<StudyTask>>(emptyList()) }
    var skills by remember { mutableStateOf<List<Skill>>(emptyList()) }
    var links by remember { mutableStateOf<Map<Long, List<GoalLink>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var version by remember { mutableStateOf(0) }

    var title by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("10") }
    var unit by rememberSaveable { mutableStateOf("دروس") }
    var category by rememberSaveable { mutableStateOf("") }
    var dueDays by rememberSaveable { mutableStateOf("30") }
    var noDeadline by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Goal?>(null) }
    var editTarget by remember { mutableStateOf<Goal?>(null) }
    var linkPicker by remember { mutableStateOf<Pair<Goal, String>?>(null) }

    LaunchedEffect(version) {
        loading = true
        goals = runCatching { repository.goals() }.getOrDefault(emptyList())
        tasks = runCatching { repository.tasks() }.getOrDefault(emptyList())
        skills = runCatching { repository.skills() }.getOrDefault(emptyList())
        links = goals.associate { it.id to runCatching { repository.links(it.id) }.getOrDefault(emptyList()) }
        loading = false
    }
    fun refresh() { version++ }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("أهدافي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
                    shape = RoundedCornerShape(FocusSpacing.radiusCard)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("هدف جديد", style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it.take(60) },
                            label = { Text("ماذا تريد أن تحقق؟") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = target,
                                onValueChange = { target = it.filter(Char::isDigit).take(5) },
                                label = { Text("الهدف") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            OutlinedTextField(
                                value = unit,
                                onValueChange = { unit = it.take(14) },
                                label = { Text("الوحدة") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("دروس", "ساعات", "كتب", "تمارين", "خطوات").forEach { preset ->
                                TextButton(onClick = { unit = preset }) {
                                    Text(
                                        preset,
                                        color = if (unit == preset) FocusColors.onTint(FocusColors.Blue) else FocusColors.TextMuted,
                                        fontWeight = if (unit == preset) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = category,
                                onValueChange = { category = it.take(20) },
                                label = { Text("المجال (اختياري)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            )
                            TextButton(onClick = { noDeadline = !noDeadline }) {
                                Text(
                                    if (noDeadline) "بدون مهلة ✓" else "بدون مهلة",
                                    color = if (noDeadline) FocusColors.onTint(FocusColors.Green) else FocusColors.TextSecondary
                                )
                            }
                        }
                        if (!noDeadline) {
                            OutlinedTextField(
                                value = dueDays,
                                onValueChange = { dueDays = it.filter(Char::isDigit).take(3) },
                                label = { Text("خلال أيام") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        Button(
                            onClick = {
                                val days = dueDays.toIntOrNull()
                                if (title.isNotBlank() && (target.toIntOrNull() ?: 0) > 0) {
                                    scope.launch {
                                        repository.add(
                                            title = title,
                                            target = target.toInt(),
                                            unit = unit.ifBlank { "خطوة" },
                                            dueAt = if (noDeadline) null else days?.let {
                                                LocalDate.now().plusDays(it.toLong())
                                                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                                            },
                                            category = category
                                        )
                                        title = ""; category = ""; refresh()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text("أضف الهدف") }
                        Text(
                            "الهدف يمكن أن يحمل مهامك ومهاراتك: كل مهمة مرتبطة تُنجزها تضيف خطوة تلقائيًا.",
                            style = MaterialTheme.typography.labelSmall,
                            color = FocusColors.TextMuted
                        )
                    }
                }
            }

            if (!loading && goals.isEmpty()) {
                item {
                    Text(
                        "لا أهداف بعد. الهدف يختلف عن المهمة: له مقياس ومهلة (أو بلا مهلة)، وتتقدّم فيه خطوة كل مرة.",
                        color = FocusColors.TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    )
                }
            }

            items(goals, key = { it.id }) { goal ->
                val goalLinks = links[goal.id].orEmpty()
                val linkedTasks = goalLinks.filter { it.kind == "task" }.mapNotNull { link -> tasks.firstOrNull { it.id == link.refId } }
                val linkedSkills = goalLinks.filter { it.kind == "skill" }.mapNotNull { link -> skills.firstOrNull { it.id == link.refId } }
                GoalCard(
                    goal = goal,
                    linkedTasks = linkedTasks,
                    linkedSkills = linkedSkills,
                    onStep = { delta -> scope.launch { repository.addProgress(goal.id, delta); refresh() } },
                    onComplete = {
                        scope.launch {
                            repository.setStatus(goal.id, if (goal.status == "done") "active" else "done")
                            refresh()
                        }
                    },
                    onEdit = { editTarget = goal },
                    onLinkTask = { linkPicker = goal to "task" },
                    onLinkSkill = { linkPicker = goal to "skill" },
                    onOpenTask = { task ->
                        scope.launch { runCatching { repository.setTaskStatus(task.id, if (task.isDone) TaskStatus.TODO else TaskStatus.DONE) }; refresh() }
                    },
                    onUnlink = { kind, refId -> scope.launch { repository.unlink(goal.id, kind, refId); refresh() } },
                    onDelete = { confirmDelete = goal }
                )
            }
        }
    }

    confirmDelete?.let { goal ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            shape = FocusShapes.large,
            containerColor = FocusColors.SurfaceHigh,
            title = { Text("حذف الهدف؟") },
            text = { Text("«${goal.title}» وتقدّمه (${goal.current} من ${goal.target}) سيُحذف، وروابط مهامه ومهاراته تُفك.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { repository.delete(goal.id); confirmDelete = null; refresh() } }) {
                    Text("حذف", color = FocusColors.onTint(FocusColors.Rose))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("إلغاء") } }
        )
    }

    editTarget?.let { goal ->
        GoalEditDialog(
            goal = goal,
            onDismiss = { editTarget = null },
            onSave = { newTitle, newTarget, newUnit, newCategory, newDueDays ->
                scope.launch {
                    repository.edit(
                        goalId = goal.id,
                        title = newTitle,
                        target = newTarget,
                        unit = newUnit,
                        category = newCategory,
                        dueAt = newDueDays?.let {
                            LocalDate.now().plusDays(it.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        }
                    )
                    editTarget = null
                    refresh()
                }
            }
        )
    }

    linkPicker?.let { (goal, kind) ->
        val candidates: List<Pair<Long, String>> = when (kind) {
            "task" -> {
                val linked = links[goal.id].orEmpty().filter { it.kind == "task" }.map { it.refId }.toSet()
                tasks.filter { it.id !in linked }.map { it.id to it.title }
            }
            else -> {
                val linked = links[goal.id].orEmpty().filter { it.kind == "skill" }.map { it.refId }.toSet()
                skills.filter { it.id !in linked }.map { it.id to it.name }
            }
        }
        AlertDialog(
            onDismissRequest = { linkPicker = null },
            shape = FocusShapes.large,
            containerColor = FocusColors.SurfaceHigh,
            title = { Text(if (kind == "task") "اربط مهمة بهذا الهدف" else "اربط مهارة بهذا الهدف") },
            text = {
                if (candidates.isEmpty()) {
                    Text(
                        if (kind == "task") "لا مهام غير مرتبطة. أضف مهمة من «مهامي» ثم ارجع."
                        else "لا مهارات بعد. أضف مهارة من «مهاراتي» ثم ارجع.",
                        color = FocusColors.TextSecondary
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(candidates, key = { it.first }) { (id, name) ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    scope.launch { repository.link(goal.id, kind, id); linkPicker = null; refresh() }
                                }
                            ) {
                                Text(name, color = FocusColors.TextPrimary, modifier = Modifier.padding(14.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { linkPicker = null }) { Text("إغلاق") } }
        )
    }
}

@Composable
private fun GoalEditDialog(
    goal: Goal,
    onDismiss: () -> Unit,
    onSave: (String, Int, String, String, Int?) -> Unit
) {
    var title by remember { mutableStateOf(goal.title) }
    var target by remember { mutableStateOf(goal.target.toString()) }
    var unit by remember { mutableStateOf(goal.unit) }
    var category by remember { mutableStateOf(goal.category) }
    var dueDays by remember {
        mutableStateOf(
            goal.dueAt?.let {
                val days = java.time.Duration.ofMillis(it - System.currentTimeMillis()).toDays().toInt()
                if (days > 0) days.toString() else ""
            }.orEmpty()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = FocusShapes.large,
        containerColor = FocusColors.SurfaceHigh,
        title = { Text("تعديل الهدف") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(60) }, label = { Text("العنوان") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it.filter(Char::isDigit).take(5) },
                        label = { Text("الهدف") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it.take(14) },
                        label = { Text("الوحدة") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
                OutlinedTextField(value = category, onValueChange = { category = it.take(20) }, label = { Text("المجال") }, singleLine = true, shape = RoundedCornerShape(14.dp))
                OutlinedTextField(
                    value = dueDays,
                    onValueChange = { dueDays = it.filter(Char::isDigit).take(3) },
                    label = { Text("المهلة (أيام) — اتركه فارغًا لبلا مهلة") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = target.toIntOrNull() ?: 0
                    if (title.isNotBlank() && value > 0) {
                        onSave(title, value, unit.ifBlank { "خطوة" }, category, dueDays.toIntOrNull())
                    }
                }
            ) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun GoalCard(
    goal: Goal,
    linkedTasks: List<StudyTask>,
    linkedSkills: List<Skill>,
    onStep: (Int) -> Unit,
    onComplete: () -> Unit,
    onEdit: () -> Unit,
    onLinkTask: () -> Unit,
    onLinkSkill: () -> Unit,
    onOpenTask: (StudyTask) -> Unit,
    onUnlink: (String, Long) -> Unit,
    onDelete: () -> Unit
) {
    val done = goal.status == "done"
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        goal.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = FocusColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        listOfNotNull(
                            goal.category.takeIf { it.isNotBlank() },
                            "${goal.current} من ${goal.target} ${goal.unit}",
                            goal.dueAt?.let { "حتى " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ar-EG"))) } ?: "بلا مهلة"
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary
                    )
                }
                if (done) Text("✓ تم", color = FocusColors.onTint(FocusColors.Green), fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(FocusColors.SurfaceHigh)) {
                Box(
                    Modifier
                        .fillMaxWidth(goal.progress.coerceIn(0f, 1f))
                        .height(8.dp)
                        .background(if (done) FocusColors.Green else FocusColors.Blue)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onStep(1) }) { Text("+1", color = FocusColors.onTint(FocusColors.Blue)) }
                TextButton(onClick = { onStep(-1) }) { Text("‑1", color = FocusColors.TextSecondary) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("تعديل", color = FocusColors.TextSecondary) }
                TextButton(onClick = onComplete) {
                    Text(if (done) "إعادة فتح" else "أنجزت", color = FocusColors.onTint(FocusColors.Green))
                }
                TextButton(onClick = onDelete) { Text("حذف", color = FocusColors.onTint(FocusColors.Rose)) }
            }

            if (linkedTasks.isNotEmpty() || linkedSkills.isNotEmpty()) {
                Text(
                    "مسار الهدف: " + listOfNotNull(
                        linkedTasks.size.takeIf { it > 0 }?.let { "$it مهام" },
                        linkedSkills.size.takeIf { it > 0 }?.let { "$it مهارات" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = FocusColors.TextMuted
                )
                Spacer(Modifier.height(4.dp))
            }
            linkedTasks.forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (task.isDone || task.status == TaskStatus.DONE) "✓ " else "○ ") + task.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.isDone || task.status == TaskStatus.DONE) FocusColors.TextMuted else FocusColors.TextPrimary,
                        modifier = Modifier.weight(1f).clickable { onOpenTask(task) }
                    )
                    TextButton(onClick = { onOpenTask(task) }) {
                        Text(if (task.isDone || task.status == TaskStatus.DONE) "إعادة فتح" else "أنجز", color = FocusColors.TextSecondary)
                    }
                    TextButton(onClick = { onUnlink("task", task.id) }) { Text("×", color = FocusColors.TextMuted) }
                }
            }
            linkedSkills.forEach { skill ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "✦ " + skill.name + " · مستوى ${skill.level}/${skill.targetLevel} · ${skill.minutes} دقيقة",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onUnlink("skill", skill.id) }) { Text("×", color = FocusColors.TextMuted) }
                }
            }
            Row {
                TextButton(onClick = onLinkTask) { Text("+ مهمة", color = FocusColors.onTint(FocusColors.Blue)) }
                TextButton(onClick = onLinkSkill) { Text("+ مهارة", color = FocusColors.onTint(FocusColors.Blue)) }
            }
        }
    }
}
