package com.focusstudy.poc

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The visible, lock-screen-friendly companion to the adhan notification.
 *
 * The high-priority notification raises the heads-up banner; this activity is launched by both the
 * full-screen intent and a dedicated exact alarm, so the prayer time appears by itself in the middle
 * of the screen. Closing this screen never stops the adhan — only the explicit stop button does.
 *
 * Phase 4 redesign: the layout follows the unified design system (FocusColors / FocusSpacing,
 * Cairo through the theme typography) and the person's own light/dark choice instead of forcing
 * dark, with the prayer name and time large and the actions easy to reach. The wake behaviour is
 * unchanged: show-when-locked / turn-screen-on stay in the manifest, the full-screen intent
 * stays the fallback, and FLAG_KEEP_SCREEN_ON is still held while the alert is visible.
 *
 * The double launch is deduped here, not by removing a wake path: the AT alarm schedules both a
 * broadcast (whose notification carries the full-screen intent) and a direct activity alarm, and
 * both used to open the screen — sometimes as two stacked copies. The activity is `singleTop`,
 * so a second launch for the alert already on screen is delivered to this instance through
 * [onNewIntent] and ignored; a different prayer replaces the one on screen instead. The
 * one-minute static guard below only covers the race where two instances are created before
 * either is on top — without `singleTop`, a `CLEAR_TOP` relaunch could destroy the visible
 * screen and then finish its own replacement, leaving no alert at all.
 */
class PrayerAlertActivity : ComponentActivity() {

    // The shown alert lives in state so a second launch delivered to this same instance
    // (singleTop + onNewIntent) can replace it — e.g. a later prayer while this screen is open.
    private var alertName by mutableStateOf("الصلاة")
    private var alertAtMillis by mutableLongStateOf(0L)
    private var alertTypeState by mutableStateOf(PrayerAlertType.AT)
    private var alertVoice by mutableStateOf(PrayerAdhanVoice.DEFAULT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dedupe the double launch (direct alarm + full-screen intent) for the same alert.
        // Checked before playback and before the UI starts, so the second copy never flashes.
        if (isDuplicateLaunch(intent)) {
            finish()
            return
        }
        applyAlert(intent)

        // The alert must stay readable while the person is looking at it; the flag is released in
        // onDestroy so an abandoned alarm never keeps the phone awake after the screen is dismissed.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            // The old screen forced dark; the redesign follows the person's theme choice like
            // every other screen (light/dark/system), with all colours coming from FocusColors.
            val systemDark = isSystemInDarkTheme()
            val dark = when (FocusPreferences.load(this).themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> systemDark
            }
            FocusTheme(dark = dark) {
                PrayerAlertScreen(
                    prayerName = alertName,
                    atMillis = alertAtMillis,
                    alertType = alertTypeState,
                    voice = alertVoice,
                    onDone = ::finish,
                    onStopAdhan = {
                        AdhanPlaybackService.stop(this)
                        finish()
                    },
                    onSnooze = {
                        PrayerReminderScheduler.snooze(this, alertName)
                        AdhanPlaybackService.stop(this)
                        finish()
                    },
                    onOpenApp = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        finish()
                    },
                    onOpenPrayerTimes = {
                        NotificationNavigation.requestPrayerTimes()
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        finish()
                    },
                    onSettings = { openPrayerChannelSettings(this, alertTypeState) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A duplicate of the alert already on screen is ignored; a new prayer replaces it.
        if (!isDuplicateLaunch(intent)) applyAlert(intent)
    }

    /** True when this intent is the same alert this instance (or a just-created twin) already shows. */
    private fun isDuplicateLaunch(intent: Intent?): Boolean {
        val key = launchKey(intent) ?: return false
        return key == lastShownKey && System.currentTimeMillis() - lastShownAt < DUPLICATE_WINDOW_MS
    }

    private fun launchKey(intent: Intent?): String? {
        if (intent == null) return null
        val name = intent.getStringExtra(EXTRA_PRAYER_NAME).orEmpty().ifBlank { "الصلاة" }
        val atMillis = intent.getLongExtra(EXTRA_AT_MILLIS, System.currentTimeMillis())
        val type = PrayerAlertType.from(intent.getStringExtra(EXTRA_TYPE))
        val key = intent.getStringExtra(EXTRA_PRAYER_KEY)
        return "${key ?: name}|$atMillis|${type.key}"
    }

    /** Shows this alert (or replaces the one on screen) and starts its adhan if it has one. */
    private fun applyAlert(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_PRAYER_NAME).orEmpty().ifBlank { "الصلاة" }
        val atMillis = intent?.getLongExtra(EXTRA_AT_MILLIS, System.currentTimeMillis())
            ?: System.currentTimeMillis()
        val type = PrayerAlertType.from(intent?.getStringExtra(EXTRA_TYPE))
        val voice = PrayerAdhanVoice.from(intent?.getStringExtra(EXTRA_VOICE))
        val key = intent?.getStringExtra(EXTRA_PRAYER_KEY)
        alertName = name
        alertAtMillis = atMillis
        alertTypeState = type
        alertVoice = voice
        lastShownKey = "${key ?: name}|$atMillis|${type.key}"
        lastShownAt = System.currentTimeMillis()
        // The alarm receiver already starts playback. Re-asking from this activity covers devices
        // that block foreground-service starts from the background: the service ignores duplicates.
        // The current preference is read here, at fire time, so a just-changed sound rule is honoured.
        if (type == PrayerAlertType.AT && key != null &&
            PrayerAlertPrefs.load(this).rule(key).atSound == PrayerAlertSound.ADHAN
        ) {
            AdhanPlaybackService.play(this, name, voice)
        }
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PRAYER_NAME = "prayer_name"
        const val EXTRA_PRAYER_KEY = "prayer_key"
        const val EXTRA_AT_MILLIS = "at_millis"
        const val EXTRA_TEST = "prayer_test"
        const val EXTRA_TYPE = "prayer_alert_type"
        const val EXTRA_VOICE = "adhan_voice"

        /** Two launches of the same alert closer than this are the same wake-up, not a new one. */
        private const val DUPLICATE_WINDOW_MS = 60_000L
        private var lastShownKey: String? = null
        private var lastShownAt: Long = 0L

        internal fun openPrayerChannelSettings(context: Context, type: PrayerAlertType = PrayerAlertType.AT) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(
                        Settings.EXTRA_CHANNEL_ID,
                        if (type == PrayerAlertType.AT) AppNotifications.PRAYER_AT.id else AppNotifications.PRAYER_BEFORE.id
                    )
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

@Composable
private fun PrayerAlertScreen(
    prayerName: String,
    atMillis: Long,
    alertType: PrayerAlertType,
    voice: PrayerAdhanVoice,
    onDone: () -> Unit,
    onStopAdhan: () -> Unit,
    onSnooze: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenPrayerTimes: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    var confirmStop by remember { mutableStateOf(false) }
    LaunchedEffect(confirmStop) {
        if (confirmStop) {
            delay(2_500)
            confirmStop = false
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(FocusColors.Ink, FocusColors.Surface, FocusColors.Ink)
                )
            )
            .padding(horizontal = FocusSpacing.screen, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(260)) + scaleIn(initialScale = 0.94f, animationSpec = tween(260))
        ) {
            Card(
                shape = RoundedCornerShape(FocusSpacing.radiusCard),
                colors = CardDefaults.cardColors(containerColor = FocusColors.SurfaceHigh),
                elevation = CardDefaults.cardElevation(defaultElevation = 20.dp),
                modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "مُنظِّم  ·  ${if (alertType == PrayerAlertType.AT) "الأذان" else "تنبيه الصلاة"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = FocusColors.onTint(FocusColors.Blue),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .size(74.dp)
                            .background(FocusColors.Blue.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("☾", fontSize = 40.sp, color = FocusColors.onTint(FocusColors.Blue))
                    }
                    Spacer(Modifier.height(FocusSpacing.section))
                    Text(
                        text = if (alertType == PrayerAlertType.AT) "حان وقت الأذان" else "قبل الصلاة",
                        style = MaterialTheme.typography.labelLarge,
                        color = FocusColors.onTint(FocusColors.Blue),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "صلاة $prayerName",
                        style = MaterialTheme.typography.headlineSmall,
                        color = FocusColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = PrayerCalculator.format(context, atMillis),
                        style = MaterialTheme.typography.titleLarge,
                        color = FocusColors.onTint(FocusColors.Teal)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "الصوت: ${voice.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextSecondary
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FocusColors.Blue)
                    ) { Text("إغلاق") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { if (confirmStop) onStopAdhan() else confirmStop = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (confirmStop) FocusColors.onTint(FocusColors.Amber) else FocusColors.TextSecondary
                        )
                    ) {
                        Text(if (confirmStop) "اضغط مجددًا لإيقاف الأذان" else "إيقاف الأذان")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onSnooze,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) { Text("ذكّرني بعد ٥ دقائق") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onOpenPrayerTimes,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp)
                    ) { Text("فتح مواقيت الصلاة") }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onOpenApp) { Text("افتح مُنظِّم") }
                    TextButton(onClick = onSettings) {
                        Text("إعدادات صوت الأذان والظهور على الشاشة", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
