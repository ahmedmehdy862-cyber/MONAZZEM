package com.focusstudy.poc

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * «نوتة» — a small private notebook for the thoughts that do not fit a task or a calendar event.
 *
 * Everything shown here is read from and written to [NoteRepository], which only touches the
 * local database. No note text is passed to the messaging or registration code.
 */
@Composable
internal fun NotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { NoteRepository.get(context) }
    val scope = rememberCoroutineScope()

    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var version by remember { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var categoryFilter by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf<Note?>(null) }

    LaunchedEffect(version) {
        notes = runCatching { repository.notes() }.getOrDefault(emptyList())
    }

    fun refresh() { version++ }

    val categories = buildList {
        add("")
        addAll(NotesText.SUGGESTED_CATEGORIES)
        notes.map { it.category }.filter { it.isNotBlank() }.forEach { add(it) }
    }.distinct()

    val visible = NotesText.sort(notes).filter { note ->
        NotesText.matches(note, query) &&
            (categoryFilter.isBlank() || note.category == categoryFilter)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("نوتة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(
            "ملاحظاتك الخاصة — محفوظة على جهازك فقط، ولا تُرسل إلى أي خادم.",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.TextMuted
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
                    shape = RoundedCornerShape(FocusSpacing.radiusCard)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            if (editingId == null) "ملاحظة جديدة" else "تعديل الملاحظة",
                            style = MaterialTheme.typography.titleSmall,
                            color = FocusColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = NotesText.title(it) },
                            label = { Text("العنوان (اختياري)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = NotesText.category(it) },
                            label = { Text("التصنيف (اختياري)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            NotesText.SUGGESTED_CATEGORIES.forEach { option ->
                                FilterChip(
                                    selected = category == option,
                                    onClick = { category = if (category == option) "" else option },
                                    label = { Text(option) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = body,
                            onValueChange = { body = NotesText.body(it) },
                            label = { Text("اكتب ملاحظتك") },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (title.isNotBlank() || body.isNotBlank()) {
                                        scope.launch {
                                            if (editingId == null) repository.add(title, body, category)
                                            else repository.update(editingId!!, title, body, category)
                                            title = ""; category = ""; body = ""; editingId = null
                                            refresh()
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(if (editingId == null) "أضف الملاحظة" else "حفظ التعديلات")
                            }
                            if (editingId != null) {
                                TextButton(onClick = {
                                    title = ""; category = ""; body = ""; editingId = null
                                }) { Text("إلغاء") }
                            }
                        }
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(80) },
                    label = { Text("ابحث في نوتتك…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
            }

            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { option ->
                        FilterChip(
                            selected = categoryFilter == option,
                            onClick = { categoryFilter = option },
                            label = { Text(if (option.isBlank()) "الكل" else option) }
                        )
                    }
                }
            }

            if (notes.isNotEmpty()) {
                item {
                    Text(
                        "${visible.size} من ${notes.size} ملاحظة",
                        style = MaterialTheme.typography.labelSmall,
                        color = FocusColors.TextMuted
                    )
                }
            }

            if (notes.isEmpty()) {
                item {
                    Text(
                        "لا ملاحظات بعد. اكتب فكرة أو تذكيرًا صغيرًا أعلاه — ستجدها هنا في أي وقت.",
                        color = FocusColors.TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                    )
                }
            }

            items(visible, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    onEdit = {
                        editingId = note.id
                        title = note.title
                        category = note.category
                        body = note.body
                    },
                    onPin = {
                        scope.launch { repository.setPinned(note.id, !note.pinned); refresh() }
                    },
                    onDelete = { confirmDelete = note }
                )
            }
        }
    }

    confirmDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            shape = FocusShapes.large,
            containerColor = FocusColors.SurfaceHigh,
            title = { Text("حذف الملاحظة؟") },
            text = { Text("«${note.title.ifBlank { note.body.take(40) }}» وكل ما كتبته فيها سيُحذف نهائيًا.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.delete(note.id); confirmDelete = null; refresh() }
                }) { Text("حذف", color = FocusColors.onTint(FocusColors.Rose)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun NoteCard(
    note: Note,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (note.pinned) {
                    Text("⚑ مثبّتة", style = MaterialTheme.typography.labelSmall, color = FocusColors.onTint(FocusColors.Blue))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    note.title.ifBlank { "ملاحظة بلا عنوان" },
                    style = MaterialTheme.typography.titleSmall,
                    color = FocusColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            val preview = NotesText.preview(note)
            if (preview.isNotBlank()) {
                Text(preview, style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val meta = buildList {
                    if (note.category.isNotBlank()) add(note.category)
                    add("قراءة ~${NotesText.readingMinutes(note.body)} د")
                    NotesText.relativeTime(note.updatedAt, System.currentTimeMillis()).takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.labelSmall, color = FocusColors.TextMuted, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onPin) { Text(if (note.pinned) "إلغاء التثبيت" else "تثبيت") }
                TextButton(onClick = onEdit) { Text("تعديل") }
                TextButton(onClick = onDelete) { Text("حذف", color = FocusColors.onTint(FocusColors.Rose)) }
            }
        }
    }
}
