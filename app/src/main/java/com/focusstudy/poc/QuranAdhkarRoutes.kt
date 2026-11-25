package com.focusstudy.poc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Direct routes for the Quran reader and the adhkar (phase 2).
 *
 * Both screens used to live two levels deep inside «حياتي»: [LifeTrackerScreen] held the
 * internal state and loaded the bundled indexes itself. These thin wrappers load the same
 * indexes and the same wird state ([WirdSettings], total pages, whether today is logged)
 * and hand them to the unchanged [QuranReaderScreen] / [AdhkarScreen] — no screen is copied
 * or edited, and the old path from inside «حياتي» keeps working exactly as before.
 */

/** Direct «المصحف» route: opens on today's wird page and logs through the wird system. */
@Composable
internal fun QuranRouteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val life = remember { LifeRepository.get(context) }
    val scope = rememberCoroutineScope()
    val today = rememberCurrentDate()
    val todayKey = today.toString()

    var index by remember { mutableStateOf<QuranIndex?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    var settings by remember { mutableStateOf(WirdSettings()) }
    var totalPages by remember { mutableStateOf(0) }
    var doneToday by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }

    LaunchedEffect(retry) {
        withContext(Dispatchers.IO) {
            index = QuranData.loadIndex(context)
            if (index == null) loadFailed = true
        }
    }
    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            settings = runCatching { life.wirdSettings() }.getOrDefault(WirdSettings())
            totalPages = runCatching { life.wirdTotalPages() }.getOrDefault(0)
            doneToday = runCatching { life.wirdLoggedToday(todayKey) }.getOrDefault(false)
        }
    }

    // نطاق ورد اليوم مقصوص عند 604 مع كشف الختم الكامل (انظر [wirdRange]).
    val wird = wirdRange(settings, totalPages)
    val wirdFirstPage = wird.firstPage
    val wirdLastPage = wird.lastPage
    val restartWird: () -> Unit = {
        scope.launch {
            runCatching {
                life.resetWird()
                life.saveWirdSettings(settings.copy(surah = "البقرة", startPage = 2))
            }
            version++
        }
    }
    val loaded = index
    if (loaded != null) {
        QuranReaderScreen(
            index = loaded,
            initialPage = wirdFirstPage,
            wirdFirstPage = wirdFirstPage,
            wirdLastPage = wirdLastPage,
            wirdDoneToday = doneToday,
            wirdCompleted = wird.completed,
            onLogWirdToday = {
                scope.launch {
                    runCatching { life.logWird(todayKey, settings.pagesPerDay, settings.surah) }
                    version++
                }
            },
            onRestartWird = restartWird,
            onBack = onBack
        )
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    if (loadFailed) "تعذّر تحميل المصحف." else "جارٍ تحميل المصحف…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusColors.TextSecondary
                )
                if (loadFailed) {
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.Button(onClick = { loadFailed = false; retry++ }) { Text("إعادة المحاولة") }
                }
            }
        }
    }
}

/**
 * Direct «الأذكار» route. The period defaults to the time of day (morning before noon,
 * evening after) — the screen itself lets the person switch, as it always did.
 */
@Composable
internal fun AdhkarRouteScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var index by remember { mutableStateOf<AdhkarIndex?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    val initialMorning = remember { LocalTime.now(AppTimeZone.zone(context)).hour < 12 }

    LaunchedEffect(retry) {
        withContext(Dispatchers.IO) {
            index = QuranData.loadAdhkar(context)
            if (index == null) loadFailed = true
        }
    }

    val loaded = index
    if (loaded != null) {
        AdhkarScreen(index = loaded, initialMorning = initialMorning, onBack = onBack)
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    if (loadFailed) "تعذّر تحميل الأذكار." else "جارٍ تحميل الأذكار…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusColors.TextSecondary
                )
                if (loadFailed) {
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.Button(onClick = { loadFailed = false; retry++ }) { Text("إعادة المحاولة") }
                }
            }
        }
    }
}
