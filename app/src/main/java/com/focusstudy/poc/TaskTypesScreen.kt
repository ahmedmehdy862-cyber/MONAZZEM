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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Names of the task types, keyed by id, for screens that only show a label. */
val LocalTaskTypeNames = androidx.compose.runtime.compositionLocalOf<Map<String, String>> { emptyMap() }

@Composable
fun TaskTypesScreen(onBack: () -> Unit) {
    val viewModel: TaskTypesViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<TaskType?>(null) }
    var deleteTarget by remember { mutableStateOf<TaskType?>(null) }

    val duplicate = state.types.any { it.name.trim().equals(draft.trim(), ignoreCase = true) && draft.isNotBlank() }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text(
                "أنواع المهام",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = FocusColors.TextPrimary
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "النوع اختياري تمامًا: المهمة تُحفظ بالعنوان وحده، والنوع مجرّد تصنيف يساعدك على الترتيب.",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.TextSecondary
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                label = { Text("نوع جديد") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.add(draft); draft = "" },
                enabled = draft.isNotBlank() && !duplicate,
                shape = RoundedCornerShape(16.dp)
            ) { Text("إضافة") }
        }
        if (duplicate) {
            Spacer(Modifier.height(6.dp))
            Text("هذا النوع موجود بالفعل.", style = MaterialTheme.typography.labelSmall, color = FocusColors.onTint(FocusColors.Amber))
        }
        Spacer(Modifier.height(12.dp))
        if (state.types.isEmpty() && !state.loading) {
            Text("لا توجد أنواع بعد.", color = FocusColors.TextSecondary)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(state.types, key = { _, type -> type.id }) { index, type ->
                TypeRow(
                    type = type,
                    canMoveUp = index > 0,
                    canMoveDown = index < state.types.lastIndex,
                    onUp = { viewModel.move(type.id, true) },
                    onDown = { viewModel.move(type.id, false) },
                    onRename = { renameTarget = type },
                    onArchive = { viewModel.setArchived(type.id, !type.archived) },
                    onDelete = { deleteTarget = type }
                )
            }
        }
    }

    renameTarget?.let { target ->
        var name by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = FocusColors.SurfaceHigh,
            title = { Text("تعديل النوع") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("اسم النوع") },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.rename(target.id, name); renameTarget = null },
                    enabled = name.isNotBlank()
                ) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("إلغاء") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = FocusColors.SurfaceHigh,
            title = { Text("حذف النوع؟") },
            text = { Text("ستبقى كل المهام المرتبطة به، لكنها ستصبح بلا نوع. لا يمكن التراجع عن حذف النوع نفسه.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(target.id); deleteTarget = null }) {
                    Text("حذف", color = FocusColors.onTint(FocusColors.Rose))
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun TypeRow(
    type: TaskType,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        type.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (type.archived) FocusColors.TextSecondary else FocusColors.TextPrimary
                    )
                    Text(
                        when {
                            type.archived -> "مؤرشف"
                            type.builtIn -> "نوع أساسي"
                            else -> "نوع مخصّص"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = FocusColors.TextSecondary
                    )
                }
                TextButton(onClick = onUp, enabled = canMoveUp) { Text("↑", color = if (canMoveUp) FocusColors.TextPrimary else FocusColors.TextSecondary) }
                TextButton(onClick = onDown, enabled = canMoveDown) { Text("↓", color = if (canMoveDown) FocusColors.TextPrimary else FocusColors.TextSecondary) }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onRename) { Text("تعديل") }
                TextButton(onClick = onArchive) { Text(if (type.archived) "استرجاع" else "أرشفة") }
                if (!type.builtIn) {
                    TextButton(onClick = onDelete) { Text("حذف", color = FocusColors.Error) }
                }
            }
        }
    }
}
