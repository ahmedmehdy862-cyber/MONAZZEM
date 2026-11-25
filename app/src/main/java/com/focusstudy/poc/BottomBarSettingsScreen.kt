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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Edits which sections live in the bottom bar: add, remove and reorder, then save.
 *
 * The first five stay in the order chosen here, and «المزيد» is only a normal entry — the person
 * can drop it as long as at least one section is left.
 */
@Composable
internal fun BottomBarSettingsScreen(
    selected: List<String>,
    onBack: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var current by remember { mutableStateOf(BottomTab.normalize(selected)) }

    fun move(id: String, delta: Int) {
        val index = current.indexOf(id)
        val target = index + delta
        if (index < 0 || target !in current.indices) return
        current = current.toMutableList().apply { add(target, removeAt(index)) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("الشريط السفلي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { current = BottomTab.DEFAULT.toList() }) { Text("الافتراضي") }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "أضف ما تفتحه كثيرًا واحذف ما لا تحتاجه. حتى ${BottomTab.MAX} أقسام، والترتيب هنا هو ترتيب الأزرار.",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.TextSecondary
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(BottomTab.entries, key = { it.id }) { tab ->
                val position = current.indexOf(tab.id)
                val picked = position >= 0
                Card(
                    colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            current = when {
                                picked -> current.filterNot { it == tab.id }.ifEmpty { current }
                                BottomTab.canAdd(current) -> current + tab.id
                                else -> current
                            }
                        }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(FocusColors.SurfaceHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = FocusColors.onTint(if (picked) FocusColors.Blue else FocusColors.TextMuted)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = FocusColors.TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                when {
                                    !picked && !BottomTab.canAdd(current) -> "الشريط ممتلئ — احذف قسمًا أولًا"
                                    picked -> "في الشريط · الترتيب ${position + 1}"
                                    else -> "غير مضاف"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = FocusColors.TextMuted
                            )
                        }
                        if (picked) {
                            TextButton(onClick = { move(tab.id, -1) }, enabled = position > 0) { Text("↑") }
                            TextButton(onClick = { move(tab.id, 1) }, enabled = position < current.lastIndex) { Text("↓") }
                            TextButton(onClick = { current = current.filterNot { it == tab.id }.ifEmpty { current } }) {
                                Text("حذف", color = FocusColors.TextSecondary)
                            }
                        } else {
                            TextButton(onClick = { if (BottomTab.canAdd(current)) current = current + tab.id }) { Text("إضافة") }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "المعروض الآن (${current.size}/${BottomTab.MAX}): " + current.joinToString(" · ") { BottomTab.labelOf(it) },
            style = MaterialTheme.typography.labelMedium,
            color = FocusColors.TextMuted
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = { onSave(BottomTab.normalize(current)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("حفظ الشريط", fontWeight = FontWeight.Bold)
        }
    }
}
