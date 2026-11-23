package com.focusstudy.poc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Arabic date formatting shared by the Today, Tasks and Smart Plan headings. */
internal object ArabicDate {
    private val formatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.forLanguageTag("ar-EG"))

    fun format(date: LocalDate): String = date.format(formatter)

    fun withSuffix(date: LocalDate, suffix: String): String = "${format(date)} · $suffix"
}

/**
 * The current day as observable state: refreshed when the screen resumes (so a returning user never
 * sees yesterday's date) and again at the next midnight (so a long-open app rolls over by itself).
 * Uses the app's selected zone, which defaults to the device zone.
 */
@Composable
internal fun rememberCurrentDate(): LocalDate {
    val context = LocalContext.current
    val zone = remember(context) { AppTimeZone.zone(context) }
    var date by remember { mutableStateOf(LocalDate.now(zone)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, zone) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) date = LocalDate.now(zone)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(zone) {
        while (isActive) {
            val now = LocalDateTime.now(zone)
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            delay(Duration.between(now, nextMidnight).toMillis().coerceAtLeast(1_000L))
            date = LocalDate.now(zone)
        }
    }
    return date
}
