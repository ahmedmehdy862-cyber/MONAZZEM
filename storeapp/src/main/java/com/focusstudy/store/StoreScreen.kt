package com.focusstudy.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal data class StoreAppView(
    val app: StoreAppFeed,
    val status: InstallStatus,
)

private sealed interface FeedState {
    data object Loading : FeedState
    data class Ready(val apps: List<StoreAppView>) : FeedState
    data class Failed(val message: String) : FeedState
}

private val Background = Brush.verticalGradient(listOf(Color(0xFF08131F), Color(0xFF142B45), Color(0xFF0E7C74)))
private val CardColor = Color(0xFF16283C)
private val Mint = Color(0xFF5FE0CE)
private val Muted = Color(0xFFC6D5E4)

@Composable
internal fun StoreScreen() {
    val context = LocalContext.current
    var state by remember { mutableStateOf<FeedState>(FeedState.Loading) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        state = try {
            val apps = StoreFeedRepository.load().map { feed ->
                StoreAppView(
                    app = feed,
                    status = installStatus(installedVersion(context, feed.packageName)?.versionCode, feed.versionCode),
                )
            }
            FeedState.Ready(apps)
        } catch (error: Exception) {
            FeedState.Failed(error.message?.take(160) ?: "تعذر قراءة مصدر التحديثات.")
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Color(0xFF08131F))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("متجر مُنظِّم", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("نفس تحديثات الموقع، على هاتفك.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { reloadKey += 1 }, modifier = Modifier.fillMaxWidth()) {
                Text("تحديث القائمة")
            }
            when (val current = state) {
                FeedState.Loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("جارٍ قراءة مصدر التحديثات…", color = Muted, modifier = Modifier.padding(start = 12.dp))
                }
                is FeedState.Failed -> Text(current.message, color = Color(0xFFFFB4A8), style = MaterialTheme.typography.bodyMedium)
                is FeedState.Ready -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(current.apps, key = { it.app.packageName }) { view ->
                        StoreAppCard(view, context)
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreAppCard(view: StoreAppView, context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(view.app.name, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${view.app.versionName} · ${view.app.versionCode}", color = Muted, style = MaterialTheme.typography.labelMedium)
            }
            Text(statusLabel(view.status), color = if (view.status == InstallStatus.UP_TO_DATE) Mint else Color(0xFFFFD083), style = MaterialTheme.typography.bodySmall)
            val details = view.app.description.ifBlank { view.app.releaseNotes }
            if (details.isNotBlank()) {
                Text(details, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (view.app.storeUrl.isNotBlank()) {
                    Button(onClick = { openUrl(context, view.app.storeUrl) }) { Text("فتح المتجر") }
                }
                if (view.app.apkUrl.isNotBlank()) {
                    OutlinedButton(onClick = { openUrl(context, view.app.apkUrl) }) { Text("تنزيل APK") }
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
