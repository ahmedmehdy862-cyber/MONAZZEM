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
import androidx.compose.material3.TextButton
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
 * A calm, offline "next prayer" card for the Today dashboard. All times come from the local Adhan
 * calculator and cached coordinates; the row of five prayers can be expanded for ±1 minute tuning.
 * When location has not been set yet it offers a friendly link to prayer settings instead of an error.
 */
@Composable
internal fun PrayerCountdownCard(onOpenPrayerSettings: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var refresh by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            tick += 1
        }
    }
    val next = remember(tick, refresh) { runCatching { PrayerCalculator.next(context) }.getOrNull() }
    val moments = remember(tick, refresh) { runCatching { PrayerCalculator.today(context) }.getOrNull() }
    val now = System.currentTimeMillis()
    val city = PrayerPrefs.cityName(context)

    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("الصلاة القادمة", style = MaterialTheme.typography.labelMedium, color = FocusColors.onTint(FocusColors.Blue))
                    Spacer(Modifier.height(4.dp))
                    if (next == null) {
                        Text("حدّد موقعك لمعرفة مواقيت الصلاة", style = MaterialTheme.typography.bodyMedium, color = FocusColors.TextSecondary)
                    } else {
                        Text(next.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = FocusColors.TextPrimary)
                        Text(
                            PrayerCalculator.format(context, next.atMillis) + if (city.isBlank()) "" else " · $city",
                            style = MaterialTheme.typography.bodySmall,
                            color = FocusColors.TextSecondary
                        )
                    }
                }
                if (next != null) {
                    val minutes = ((next.atMillis - now) / 60_000L).coerceAtLeast(0)
                    Text(
                        PrayerCountdownFormat.label(minutes),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = FocusColors.onTint(FocusColors.Blue)
                    )
                } else {
                    TextButton(onClick = onOpenPrayerSettings) { Text("تحديد الموقع") }
                }
            }
            if (moments != null) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(if (expanded) "إخفاء المواقيت" else "عرض المواقيت الخمسة وتعديلها")
                }
                if (expanded) {
                    moments.forEachIndexed { index, moment ->
                        val key = PrayerAccuracy.KEYS[index]
                        val tune = PrayerPrefs.fineTune(context, key)
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                moment.name + if (next?.name == moment.name) " · القادمة" else "",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (next?.name == moment.name) FocusColors.onTint(FocusColors.Blue) else FocusColors.TextPrimary
                            )
                            TextButton(onClick = {
                                PrayerPrefs.saveFineTune(context, key, tune - 1)
                                PrayerReminderScheduler.schedule(context)
                                refresh++
                            }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("−") }
                            Text(PrayerCalculator.format(context, moment.atMillis), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                PrayerPrefs.saveFineTune(context, key, tune + 1)
                                PrayerReminderScheduler.schedule(context)
                                refresh++
                            }, contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) { Text("+") }
                            Text(if (tune == 0) "" else "${if (tune > 0) "+" else ""}$tune", style = MaterialTheme.typography.labelSmall, color = FocusColors.TextSecondary)
                        }
                    }
                }
            }
        }
    }
}
