package com.focusstudy.poc

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * أذكار الصباح والمساء من حصن المسلم (الفصل 27: أذكار الصباح والمساء).
 *
 * القائمة واحدة للصباح والمساء كما في الكتاب، والتقدم يُحفَظ منفصلًا لكل فترة
 * داخل الجلسة. النقر على الذكر يُنقص عدّاده حتى يكتمل.
 */
@Composable
internal fun AdhkarScreen(index: AdhkarIndex, initialMorning: Boolean, onBack: () -> Unit) {
    var morning by rememberSaveable { mutableStateOf(initialMorning) }
    var morningLeft by rememberSaveable { mutableStateOf<List<Int>?>(null) }
    var eveningLeft by rememberSaveable { mutableStateOf<List<Int>?>(null) }

    val defaults = index.items.map { it.repeat }
    val left = if (morning) (morningLeft ?: defaults) else (eveningLeft ?: defaults)
    fun setLeft(next: List<Int>) {
        if (morning) morningLeft = next else eveningLeft = next
    }
    val doneCount = left.count { it == 0 }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "الأذكار",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    index.chapter,
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PeriodChip(label = "أذكار الصباح", selected = morning, onSelect = { morning = true })
            PeriodChip(label = "أذكار المساء", selected = !morning, onSelect = { morning = false })
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "أكملت $doneCount من ${index.items.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = FocusColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { setLeft(defaults) }) { Text("إعادة من البداية") }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(FocusSpacing.item)
        ) {
            itemsIndexed(index.items) { position, item ->
                val remaining = left.getOrNull(position) ?: item.repeat
                val done = remaining == 0
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (done) FocusColors.Teal.copy(alpha = 0.15f) else FocusColors.Surface
                    ),
                    shape = RoundedCornerShape(FocusSpacing.radiusCard),
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (remaining > 0) {
                            val next = left.toMutableList()
                            next[position] = remaining - 1
                            setLeft(next)
                        }
                    }
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(FocusSpacing.card),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            item.text,
                            fontSize = 19.sp,
                            lineHeight = 34.sp,
                            color = FocusColors.TextPrimary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (done) "تم ✓" else "المتبقي: $remaining من ${item.repeat}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (done) FocusColors.onTint(FocusColors.Green) else FocusColors.TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "اضغط للعدّ",
                                style = MaterialTheme.typography.labelSmall,
                                color = FocusColors.TextSecondary
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "المصدر: ${index.source} — ${index.sourceUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun PeriodChip(label: String, selected: Boolean, onSelect: () -> Unit) {
    TextButton(onClick = onSelect) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) FocusColors.onTint(FocusColors.Teal) else FocusColors.TextSecondary
        )
    }
}
