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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.launch

/**
 * Skills the person is building: not tasks, not courses — a practice log with a level they own.
 * Every row stays one tap away from logging practice, so the tracker is usable in a spare minute.
 */
@Composable
internal fun SkillsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { SkillRepository.get(context) }
    val scope = rememberCoroutineScope()

    var skills by remember { mutableStateOf<List<Skill>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var version by remember { mutableStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf(3) }
    var confirmDelete by remember { mutableStateOf<Skill?>(null) }

    LaunchedEffect(version) {
        loading = true
        skills = runCatching { repository.skills() }.getOrDefault(emptyList())
        loading = false
    }

    fun refresh() { version++ }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("مهاراتي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
                    shape = RoundedCornerShape(FocusSpacing.radiusCard)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("مهارة جديدة", style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(48) },
                            label = { Text("اسم المهارة (مثال: تصميم، إنجليزي، خط)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it.take(24) },
                            label = { Text("المجال (اختياري)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("أريد الوصول للمستوى:", color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(10.dp))
                            LevelDots(level = target, onSelect = { target = it })
                        }
                        Button(
                            onClick = {
                                if (name.isNotBlank()) {
                                    scope.launch {
                                        repository.add(name, category, target)
                                        name = ""
                                        category = ""
                                        target = 3
                                        refresh()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text("أضف المهارة") }
                    }
                }
            }

            if (skills.isNotEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
                        shape = RoundedCornerShape(FocusSpacing.radiusCard)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${skills.size} مهارة", color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "مجموع التدريب: ${skills.sumOf { it.minutes }} دقيقة",
                                color = FocusColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            if (!loading && skills.isEmpty()) {
                item {
                    Text(
                        "لم تسجّل مهارات بعد. ابدأ بمهارة واحدة، وسجّل كل جلسة تدريب لترى التقدّم.",
                        color = FocusColors.TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                    )
                }
            }

            items(skills, key = { it.id }) { skill ->
                SkillCard(
                    skill = skill,
                    onPractice = { minutes ->
                        scope.launch { repository.logPractice(skill.id, minutes); refresh() }
                    },
                    onLevel = { level ->
                        scope.launch { repository.setLevel(skill.id, level); refresh() }
                    },
                    onDelete = { confirmDelete = skill }
                )
            }
        }
    }

    confirmDelete?.let { skill ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            shape = FocusShapes.large,
            containerColor = FocusColors.SurfaceHigh,
            title = { Text("حذف المهارة؟") },
            text = { Text("«${skill.name}» و${skill.minutes} دقيقة تدريب ستُحذف. لا يمكن التراجع.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.delete(skill.id); confirmDelete = null; refresh() }
                }) { Text("حذف", color = FocusColors.onTint(FocusColors.Rose)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    onPractice: (Int) -> Unit,
    onLevel: (Int) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(skill.name, style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            skill.category.takeIf { it.isNotBlank() },
                            "المستوى ${skill.level} من ${skill.targetLevel}",
                            "${skill.minutes} دقيقة تدريب"
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary
                    )
                }
                TextButton(onClick = onDelete) { Text("حذف", color = FocusColors.onTint(FocusColors.Rose)) }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(FocusColors.SurfaceHigh)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(skill.progress.coerceIn(0f, 1f))
                        .height(8.dp)
                        .background(FocusColors.accents[0])
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelDots(level = skill.level, onSelect = onLevel)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onPractice(15) }) { Text("+15 د", color = FocusColors.onTint(FocusColors.Blue)) }
                TextButton(onClick = { onPractice(30) }) { Text("+30 د", color = FocusColors.onTint(FocusColors.Blue)) }
                TextButton(onClick = { onPractice(60) }) { Text("+60 د", color = FocusColors.onTint(FocusColors.Blue)) }
            }
        }
    }
}

@Composable
private fun LevelDots(level: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { index ->
            Box(
                Modifier
                    .size(if (index <= level) 15.dp else 12.dp)
                    .clip(CircleShape)
                    .background(if (index <= level) FocusColors.Blue else FocusColors.Outline)
                    .clickable { onSelect(index) }
            )
        }
    }
}
