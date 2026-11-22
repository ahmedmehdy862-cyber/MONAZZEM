package com.focusstudy.poc

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A one-way broadcast from the team, fetched at runtime so no store update is needed. */
data class TeamMessage(val id: String, val title: String, val body: String, val at: String)

/**
 * Team → user channel and user → team support form.
 *
 * Messages live on the update site, so the team can announce something today and every installed
 * app shows it on the next launch. The last successful fetch is cached so the inbox still reads
 * offline, and dismissals are remembered per message id.
 */
object TeamMessages {

    internal const val BASE = "https://update-site-sigma.vercel.app"
    private const val PREFS = "focus_team_messages"
    private const val CACHE = "cache"
    private const val DISMISSED = "dismissed"
    private const val REPORTED = "delivery_reported"

    suspend fun fetch(context: Context): List<TeamMessage> = withContext(Dispatchers.IO) {
        val profile = FocusPreferences.load(context).profile.key
        val pid = UserIdentity.profileId(context)
        val query = "?profile=$profile&version=${versionCode(context)}&uid=${UserIdentity.id(context)}" +
            if (pid.isBlank()) "" else "&pid=$pid"
        val fresh = runCatching { request("$BASE/api/messages$query", "GET", null) }
            .getOrNull()?.takeIf { it.isNotBlank() }
        if (fresh != null) {
            // The same response carries broadcast messages and the admin's sound choices. Cache the
            // whole thing so a receiver can use the latest sound even with no network connection.
            prefs(context).edit().putString(CACHE, fresh).apply()
            NotificationSounds.cache(context, fresh)
            reportDelivered(context, parseMessages(fresh))
        }
        val raw = fresh ?: prefs(context).getString(CACHE, "").orEmpty()
        parseMessages(raw)
    }

    /**
     * One-way receipt for messages actually received by this install. The local set prevents a
     * second report on another screen; the server deduplicates by message and recipient as well.
     * Offline failures leave the ids pending, so the next successful fetch retries them.
     */
    private fun reportDelivered(context: Context, messages: List<TeamMessage>) {
        val ids = messages.map { it.id }.filter { it.isNotBlank() }
        if (ids.isEmpty()) return
        val store = prefs(context)
        val reported = store.getStringSet(REPORTED, emptySet()).orEmpty().toMutableSet()
        val pending = ids.filterNot { it in reported }
        if (pending.isEmpty()) return
        val payload = JSONObject()
            .put("action", "delivered")
            .put("uid", UserIdentity.id(context))
            .put("pid", UserIdentity.profileId(context))
            .put("ids", JSONArray(pending))
        runCatching { request("$BASE/api/messages", "POST", payload.toString()) }
            .onSuccess { store.edit().putStringSet(REPORTED, reported + pending).apply() }
    }

    fun cached(context: Context): List<TeamMessage> =
        parseMessages(prefs(context).getString(CACHE, "").orEmpty())

    fun dismiss(context: Context, id: String) {
        val current = prefs(context).getStringSet(DISMISSED, emptySet()).orEmpty().toMutableSet()
        current.add(id)
        prefs(context).edit().putStringSet(DISMISSED, current).apply()
    }

    fun isDismissed(context: Context, id: String): Boolean =
        prefs(context).getStringSet(DISMISSED, emptySet()).orEmpty().contains(id)

    /**
     * Fetches the channel and raises one notification for the newest unseen message.
     * Messages are pulled, not pushed: this runs on every launch, no background service needed.
     */
    suspend fun checkForNew(context: Context) {
        val messages = fetch(context)
        if (messages.isEmpty()) return
        val seen = prefs(context).getStringSet(SEEN, emptySet()).orEmpty().toMutableSet()
        val unseen = messages.filter { it.id.isNotBlank() && it.id !in seen }
        if (unseen.isEmpty()) return
        // Every message reaches the notification shade, not just the newest one: after a few days
        // offline a person would otherwise never learn about the ones in between. Mark them seen
        // only after a notification was actually posted, so permission denials can be retried.
        val delivered = TeamNotifier.notifyEach(context, unseen)
        if (!delivered) return
        seen.addAll(unseen.map { it.id })
        prefs(context).edit().putStringSet(SEEN, seen).apply()
    }

    private const val SEEN = "seen"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun parseMessages(raw: String): List<TeamMessage> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONObject(raw).optJSONArray("messages") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        TeamMessage(
                            id = item.optString("id"),
                            title = item.optString("title"),
                            body = item.optString("body"),
                            at = item.optString("at")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    internal fun request(url: String, method: String, payload: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.setRequestProperty("Accept", "application/json")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            stream?.bufferedReader()?.use { it.readText() }.orEmpty().also {
                if (connection.responseCode !in 200..299) error("http ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun versionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    internal fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** Sends a support message to the team inbox; returns true when the server accepted it. */
    suspend fun sendSupport(context: Context, name: String, contact: String, body: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject()
                    .put("name", name)
                    .put("contact", contact)
                    .put("body", body)
                    .put("version", versionName(context))
                    .put("uid", UserIdentity.id(context))
                    .toString()
                request("$BASE/api/support", "POST", payload)
                true
            }.getOrDefault(false)
        }
}

/** Newest unread announcement, right on the day screen; hidden once the person taps «تم». */
@Composable
internal fun TeamMessageBanner(onOpenAll: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<TeamMessage?>(null) }

    LaunchedEffect(Unit) {
        message = TeamMessages.cached(context).firstOrNull { !TeamMessages.isDismissed(context, it.id) }
        val fresh = TeamMessages.fetch(context).firstOrNull { !TeamMessages.isDismissed(context, it.id) }
        if (fresh != null) message = fresh
    }

    val current = message ?: return
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("رسالة من الفريق", style = MaterialTheme.typography.labelMedium, color = FocusColors.onTint(FocusColors.Blue))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { TeamMessages.dismiss(context, current.id); message = null }) { Text("تم") }
            }
            Text(current.title, style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(current.body, style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary)
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onOpenAll) { Text("كل الرسائل") }
        }
    }
}

@Composable
internal fun TeamMessagesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var messages by remember { mutableStateOf(TeamMessages.cached(context)) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        messages = TeamMessages.fetch(context)
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("رسائل الفريق", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { reload++ }) { Text(if (loading) "…" else "تحديث") }
        }
        Spacer(Modifier.height(10.dp))
        MessageCodeCard()
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (messages.isEmpty() && !loading) {
                item {
                    Text(
                        "لا توجد رسائل الآن. ستظهر هنا رسائل الفريق وتحديثات الخدمة بدون تحديث التطبيق.",
                        color = FocusColors.TextSecondary
                    )
                }
            }
            items(messages, key = { it.id }) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(item.body, style = MaterialTheme.typography.bodyMedium, color = FocusColors.TextSecondary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            item.at.take(16).replace("T", " · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = FocusColors.TextMuted
                        )
                    }
                }
            }
        }
    }
}

/** Local notification so an announcement is not missed when the app is closed. */
internal object TeamNotifier {
    private const val MAX_INDIVIDUAL = 4

    /** One notification per message, then a count for the rest so the shade never floods. */
    fun notifyEach(context: Context, messages: List<TeamMessage>): Boolean {
        var delivered = false
        messages.take(MAX_INDIVIDUAL).forEach { delivered = notify(context, it) || delivered }
        val rest = messages.size - MAX_INDIVIDUAL
        if (rest > 0) {
            delivered = notify(
                context,
                TeamMessage(
                    id = "team-digest-${messages.first().id}",
                    title = "وصلتك $rest رسائل أخرى من الفريق",
                    body = "افتح «رسائل الفريق» لقراءتها كلها.",
                    at = messages.first().at
                )
            ) || delivered
        }
        return delivered
    }

    fun notify(context: Context, message: TeamMessage): Boolean {
        if (!AppNotifications.canPost(context)) return false
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = AppNotifications.builder(
            context = context,
            channel = AppNotifications.TEAM,
            title = message.title,
            message = message.body,
            contentIntent = intent
        ).build()
        return runCatching {
            AppNotifications.manager(context).notify(message.id.hashCode(), notification)
        }.isSuccess
    }
}

/**
 * Read-only short code for the dashboard's user list.
 *
 * It is derived from the install id, but the server resolves it to the profile id before storing a
 * message. The person never has to copy, dictate or send it — the registration handshake already
 * introduced this install to the team, and no device id or push token is shown here.
 */
@Composable
private fun MessageCodeCard() {
    val context = LocalContext.current
    val code = remember(context) { UserIdentity.code(context) }

    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.SurfaceHigh),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("رمزك للرسائل الخاصة", style = MaterialTheme.typography.labelMedium, color = FocusColors.Blue)
            Text(
                code,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = FocusColors.TextPrimary
            )
            Text(
                "يظهر اسمك ورمزك تلقائيًا في لوحة الفريق عند أول فتح. لا نطلب منك نسخ الرمز ولا إرساله، ولا نطلب بريدك أو رقم هاتفك.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
        }
    }
}
