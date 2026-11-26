package com.focusstudy.poc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * شاشة ترحيبية بسيطة (0.35.0): ترحيب قصير قبل الدخول يخفف حدة الإقلاع.
 *
 * تنتقل تلقائيًا بعد [WELCOME_AUTO_ADVANCE_MS]، أو بالنقر في أي مكان —
 * ولا توقف الدخول أبدًا. خفيفة تمامًا: نص فقط بألوان الثيم، بلا GLB وبلا
 * Filament وبلا شبكة وبلا أي عمل ثقيل، فلا تضيف أي خطر تعطل جديد.
 * تُعرض بعد الإعداد الأولي فقط، ولا تُعرض أثناء جلسة تركيز جارية
 * (يقرر ذلك AppRoot).
 */
@Composable
internal fun WelcomeScreen(
    userName: String,
    onDone: () -> Unit
) {
    LaunchedEffect(Unit) {
        delay(WELCOME_AUTO_ADVANCE_MS)
        onDone()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FocusColors.background)
            .clickable { onDone() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Text(
                text = "مُنظِّم",
                style = MaterialTheme.typography.headlineLarge,
                color = FocusColors.TextPrimary,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (userName.isBlank()) "أهلاً بك" else "أهلاً بك يا $userName",
                style = MaterialTheme.typography.titleMedium,
                color = FocusColors.TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "نرتّب يومك معًا",
                style = MaterialTheme.typography.bodyMedium,
                color = FocusColors.TextSecondary
            )
            Spacer(Modifier.height(32.dp))
            Text(
                text = "اضغط في أي مكان للمتابعة",
                style = MaterialTheme.typography.labelMedium,
                color = FocusColors.TextMuted
            )
        }
    }
}

/** مدة الترحيب قبل الانتقال التلقائي — قصيرة عمدًا حتى لا تؤخر الدخول. */
private const val WELCOME_AUTO_ADVANCE_MS = 1_200L
