package com.focusstudy.poc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The visible, lock-screen-friendly companion to the adhan notification.
 *
 * The high-priority notification raises the heads-up banner; this activity is launched by its
 * full-screen intent so the prayer time is impossible to miss even when the phone is locked.
 */
class PrayerAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prayerName = intent?.getStringExtra(EXTRA_PRAYER_NAME).orEmpty().ifBlank { "الصلاة" }
        val atMillis = intent?.getLongExtra(EXTRA_AT_MILLIS, System.currentTimeMillis())
            ?: System.currentTimeMillis()
        val fromTest = intent?.getBooleanExtra(EXTRA_TEST, false) == true
        val alertType = PrayerAlertType.from(intent?.getStringExtra(EXTRA_TYPE))

        setContent {
            FocusTheme(dark = true) {
                PrayerAlertScreen(
                    prayerName = prayerName,
                    atMillis = atMillis,
                    fromTest = fromTest,
                    alertType = alertType,
                    onDone = ::finish,
                    onSnooze = {
                        PrayerReminderScheduler.snooze(this, prayerName)
                        finish()
                    },
                    onOpenApp = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                        finish()
                    },
                    onSettings = { openPrayerChannelSettings(this, alertType) }
                )
            }
        }
    }

    companion object {
        const val EXTRA_PRAYER_NAME = "prayer_name"
        const val EXTRA_AT_MILLIS = "at_millis"
        const val EXTRA_TEST = "prayer_test"
        const val EXTRA_TYPE = "prayer_alert_type"

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
    fromTest: Boolean,
    alertType: PrayerAlertType,
    onDone: () -> Unit,
    onSnooze: () -> Unit,
    onOpenApp: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(FocusColors.Ink, FocusColors.Surface, FocusColors.Ink)
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = FocusColors.SurfaceHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "☾",
                    fontSize = 54.sp,
                    color = FocusColors.onTint(FocusColors.Blue),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (fromTest) {
                        if (alertType == PrayerAlertType.AT) "هكذا يظهر تنبيه الأذان" else "هكذا يظهر التنبيه قبل الصلاة"
                    } else if (alertType == PrayerAlertType.AT) "حان وقت الصلاة" else "قبل الصلاة",
                    style = MaterialTheme.typography.labelLarge,
                    color = FocusColors.Blue,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "صلاة $prayerName",
                    style = MaterialTheme.typography.headlineMedium,
                    color = FocusColors.TextPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = PrayerCalculator.format(context, atMillis),
                    style = MaterialTheme.typography.titleLarge,
                    color = FocusColors.onTint(FocusColors.Teal)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "تذكير محلي من مُنظِّم حسب موقعك وطريقة الحساب التي اخترتها.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FocusColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(26.dp))
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FocusColors.Blue)
                ) { Text("حسنًا") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) { Text("ذكّرني بعد ٥ دقائق") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenApp,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) { Text("افتح مُنظِّم") }
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.TextButton(onClick = onSettings) {
                    Text("إعدادات صوت الأذان والظهور على الشاشة", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
