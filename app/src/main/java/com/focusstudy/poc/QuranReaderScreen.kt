package com.focusstudy.poc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class JumpPanel { NONE, PAGE, SURAH, JUZ }

/**
 * قارئ المصحف داخل «حياتي»: يعرض صفحة من مصحف المدينة بالنص العثماني المضمّن،
 * ويفتح مباشرة على صفحة ورد اليوم، ويسجّل الورد عبر نظام الورد الحالي نفسه.
 */
@Composable
internal fun QuranReaderScreen(
    index: QuranIndex,
    initialPage: Int,
    wirdFirstPage: Int,
    wirdLastPage: Int,
    wirdDoneToday: Boolean,
    onLogWirdToday: () -> Unit,
    onBack: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf(initialPage.coerceIn(1, 604)) }
    var panel by remember { mutableStateOf(JumpPanel.NONE) }
    var jumpPageText by rememberSaveable { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val chipsScrollState = rememberScrollState()
    LaunchedEffect(page) { scrollState.scrollTo(0) }

    val ayahs = remember(page) { index.ayahsForPage(page) }
    val surahNames = remember(page) { index.pageSurahNames(page) }
    val juz = remember(page) { index.juzForPage(page) }
    val inWirdRange = page in wirdFirstPage..wirdLastPage

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "المصحف",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "صفحة $page من 604 · الجزء ${QuranData.arabicDigits(juz)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        Column(
            Modifier.weight(1f).verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (surahNames.isNotBlank()) {
                Text(
                    surahNames,
                    style = MaterialTheme.typography.titleMedium,
                    color = FocusColors.onTint(FocusColors.Teal),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            ayahs.forEach { ayah ->
                val (basmalah, rest) = QuranData.splitBasmalah(index, ayah)
                if (basmalah != null) {
                    Text(
                        basmalah,
                        fontSize = 22.sp,
                        lineHeight = 40.sp,
                        color = FocusColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }
                val display = if (rest.isBlank()) ayah.text else rest
                Text(
                    display + "  ۝" + QuranData.arabicDigits(ayah.ayah),
                    fontSize = 21.sp,
                    lineHeight = 40.sp,
                    color = FocusColors.TextPrimary
                )
            }
            if (ayahs.isEmpty()) {
                Text(
                    "تعذّر عرض هذه الصفحة.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusColors.TextSecondary
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "نص القرآن الكريم: مشروع تنزيل (Tanzil.net) — النص العثماني v1.1 — رخصة CC Attribution 3.0. " +
                    "خريطة صفحات مصحف المدينة: AlQuran Cloud.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { if (page > 1) page-- },
                enabled = page > 1,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) { Text("الصفحة السابقة") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (page < 604) page++ },
                enabled = page < 604,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) { Text("الصفحة التالية") }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { panel = if (panel == JumpPanel.PAGE) JumpPanel.NONE else JumpPanel.PAGE }) {
                Text("اذهب إلى صفحة")
            }
            TextButton(onClick = { panel = if (panel == JumpPanel.SURAH) JumpPanel.NONE else JumpPanel.SURAH }) {
                Text("سورة")
            }
            TextButton(onClick = { panel = if (panel == JumpPanel.JUZ) JumpPanel.NONE else JumpPanel.JUZ }) {
                Text("جزء")
            }
        }
        when (panel) {
            JumpPanel.PAGE -> Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = jumpPageText,
                    onValueChange = { jumpPageText = it.filter { ch -> ch.isDigit() }.take(3) },
                    label = { Text("رقم الصفحة (1–604)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        jumpPageText.toIntOrNull()?.let { page = it.coerceIn(1, 604) }
                        panel = JumpPanel.NONE
                    },
                    shape = RoundedCornerShape(16.dp)
                ) { Text("اذهب") }
            }
            JumpPanel.SURAH -> {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(chipsScrollState),
                    horizontalArrangement = Arrangement.spacedBy(FocusSpacing.chip)
                ) {
                    index.surahs.forEach { surah ->
                        Text(
                            surah.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = FocusColors.onTint(FocusColors.Teal),
                            modifier = Modifier
                                .clip(RoundedCornerShape(FocusSpacing.radiusChip))
                                .background(FocusColors.SurfaceHigh)
                                .clickable {
                                    page = surah.page
                                    panel = JumpPanel.NONE
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            JumpPanel.JUZ -> {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(chipsScrollState),
                    horizontalArrangement = Arrangement.spacedBy(FocusSpacing.chip)
                ) {
                    (1..30).forEach { juzNumber ->
                        Text(
                            "الجزء ${QuranData.arabicDigits(juzNumber)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = FocusColors.onTint(FocusColors.Teal),
                            modifier = Modifier
                                .clip(RoundedCornerShape(FocusSpacing.radiusChip))
                                .background(FocusColors.SurfaceHigh)
                                .clickable {
                                    page = index.pageForJuz(juzNumber)
                                    panel = JumpPanel.NONE
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            JumpPanel.NONE -> Unit
        }
        Spacer(Modifier.height(6.dp))
        if (inWirdRange && !wirdDoneToday) {
            Button(
                onClick = onLogWirdToday,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("سجّلت قراءة ورد اليوم") }
        } else if (wirdDoneToday) {
            Text(
                "تم تسجيل ورد اليوم ✓",
                style = MaterialTheme.typography.bodyMedium,
                color = FocusColors.onTint(FocusColors.Green),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        } else {
            Text(
                "هذه الصفحة خارج ورد اليوم (صفحات $wirdFirstPage–$wirdLastPage).",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}
