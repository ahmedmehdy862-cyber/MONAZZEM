package com.focusstudy.poc

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * A calm, read-only "next prayer" card for the Today dashboard. It never fetches anything: all
 * times come from the offline Adhan calculator and the locally cached coordinates. When location
 * has not been set yet it offers a friendly link to the prayer settings instead of an error.
 */
@Composable
internal fun PrayerCountdownCard(onOpenPrayerSettings: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            tick += 1
        }
    }
    val next = remember(tick) { runCatching { PrayerCalculator.next(context) }.getOrNull() }
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("الصلاة القادمة", style = MaterialTheme.typography.labelMedium, color = FocusColors.onTint(FocusColors.Blue))
                Spacer(Modifier.height(4.dp))
                if (next == null) {
                    Text("حدّد موقعك لمعرفة مواقيت الصلاة", style = MaterialTheme.typography.bodyMedium, color = FocusColors.TextSecondary)
                } else {
                    Text(next.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = FocusColors.TextPrimary)
                    Text(PrayerCalculator.format(context, next.atMillis), style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary)
                }
            }
            if (next != null) {
                val minutes = ((next.atMillis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
                Text(
                    PrayerCountdownFormat.label(minutes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = FocusColors.Blue
                )
            } else {
                androidx.compose.material3.TextButton(onClick = onOpenPrayerSettings) { Text("تحديد الموقع") }
            }
        }
    }
}
