package com.focusstudy.store

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class StoreAppFeed(
    val id: String,
    val name: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val storeUrl: String,
    val apkUrl: String,
    val description: String,
    val releaseNotes: String,
)

/** Reads the exact feed the website reads, so the phone store cannot drift from the site. */
internal object StoreFeedRepository {
    private const val MAX_FEED_BYTES = 128 * 1024

    suspend fun load(): List<StoreAppFeed> = withContext(Dispatchers.IO) {
        val root = JSONObject(readFeed())
        val apps = ArrayList<StoreAppFeed>()
        root.optJSONArray("apps")?.let { array ->
            for (index in 0 until array.length()) {
                parse(array.optJSONObject(index))?.let(apps::add)
            }
        }
        // Older deployments may not have the `apps` field yet; the top-level release is the seed.
        if (apps.isEmpty()) parse(root)?.let(apps::add)
        if (apps.isEmpty()) error("لم يتم العثور على تطبيقات في مصدر التحديثات.")
        apps.distinctBy { it.packageName }
    }

    private fun parse(json: JSONObject?): StoreAppFeed? {
        if (json == null) return null
        val packageName = json.optString("packageName").trim()
        val versionCode = json.optLong("versionCode", 0L)
        val versionName = json.optString("versionName").trim()
        if (packageName.isBlank() || versionCode < 1 || versionName.isBlank()) return null
        return StoreAppFeed(
            id = json.optString("id").trim().ifBlank { packageName },
            name = json.optString("name").trim().ifBlank { packageName },
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            storeUrl = httpsOrEmpty(json.optString("storeUrl")),
            apkUrl = httpsOrEmpty(json.optString("apkUrl")),
            description = json.optString("description").trim(),
            releaseNotes = json.optString("releaseNotes").trim(),
        )
    }

    private fun httpsOrEmpty(value: String): String {
        val text = value.trim()
        val uri = Uri.parse(text)
        return if (uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) text else ""
    }

    private fun readFeed(): String {
        val url = URL(BuildConfig.UPDATE_FEED_URL)
        require(url.protocol.equals("https", ignoreCase = true)) { "مصدر التحديثات يجب أن يستخدم HTTPS." }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = connection.responseCode
            require(code in 200..299) { "تعذر الوصول إلى مصدر التحديثات (HTTP $code)." }
            val declared = connection.contentLengthLong
            require(declared <= 0 || declared <= MAX_FEED_BYTES) { "ملف التحديثات أكبر من الحجم المسموح." }
            val output = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_FEED_BYTES) { "ملف التحديثات أكبر من الحجم المسموح." }
                    output.write(buffer, 0, count)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}
