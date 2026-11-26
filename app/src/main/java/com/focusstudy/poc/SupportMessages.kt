package com.focusstudy.poc

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** شكوى أم اقتراح — يُخزَّن المفتاح في قاعدة البيانات ويُعرض التسمية في «رسائلي». */
enum class SupportMessageKind(val key: String, val label: String) {
    COMPLAINT("complaint", "شكوى"),
    SUGGESTION("suggestion", "اقتراح");

    companion object {
        fun from(key: String?): SupportMessageKind =
            entries.firstOrNull { it.key == key } ?: COMPLAINT
    }
}

/** حالة إرسال رسالة دعم محفوظة محليًا. */
enum class SupportMessageStatus(val key: String, val label: String) {
    SENDING("sending", "قيد الإرسال"),
    SENT("sent", "مرسلة"),
    FAILED("failed", "فاشلة");

    companion object {
        fun from(key: String?): SupportMessageStatus =
            entries.firstOrNull { it.key == key } ?: FAILED
    }
}

/**
 * رسالة دعم محفوظة محليًا (جدول support_messages، v17).
 *
 * [userName] هو اسم المستخدم الحالي من user_profile وقت الإرسال، مرفق بكل رسالة؛
 * [senderName] هو الاسم المكتوب في نموذج الدعم (يُرسَل في حقل name)؛ [body] هو النص
 * الخام كما كتبه المستخدم؛ [imagePaths] مسارات النسخ الخاصة في filesDir، لا روابط
 * المحتوى الأصلية ولا BLOBs.
 */
data class SupportMessage(
    val id: Long,
    val kind: SupportMessageKind,
    val userName: String,
    val senderName: String,
    val contact: String,
    val body: String,
    val imagePaths: List<String>,
    val status: SupportMessageStatus,
    val createdAt: Long
)

/**
 * مسارات الصور تُخزَّن في عمود TEXT واحد مفصولًا بسطر جديد؛ أسماء النسخ مولّدة من
 * التطبيق (UUID) فلا تحتوي سطرًا جديدًا أبدًا.
 */
internal fun joinSupportImagePaths(paths: List<String>): String = paths.joinToString("\n")

internal fun splitSupportImagePaths(raw: String): List<String> =
    raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

/**
 * ينسخ الصور المختارة إلى تخزين خاص بالتطبيق (filesDir/support_images) ويعيد
 * المسارات المطلقة للنسخ الناجحة فقط. روابط المحتوى الأصلية من منتقي الصور قد
 * تفقد صلاحيتها بعد إغلاق التطبيق، أما النسخ فتبقى — وهي نفسها التي تُشارَك مع
 * واتساب وتُحفَظ مساراتها في قاعدة البيانات. يُستدعى من خيط IO فقط.
 */
fun copySupportImages(context: Context, uris: List<Uri>): List<String> {
    if (uris.isEmpty()) return emptyList()
    val dir = File(context.filesDir, "support_images").apply { if (!exists()) mkdirs() }
    val resolver = context.contentResolver
    return uris.mapNotNull { uri ->
        runCatching {
            val extension = when (resolver.getType(uri)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val target = File(dir, "support_${UUID.randomUUID()}.$extension")
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("cannot open $uri")
            target.absolutePath
        }.getOrNull()
    }
}

/**
 * روابط FileProvider للنسخ الخاصة، لمشاركتها مع واتساب (filesDir لا يراه أي تطبيق
 * آخر مباشرة). المسارات غير الموجودة تُتخطى بصمت.
 */
fun supportImageShareUris(context: Context, paths: List<String>): List<Uri> =
    paths.mapNotNull { path ->
        val file = File(path)
        if (!file.exists()) null
        else runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

/** تاريخ رسالة في «رسائلي»، مثل «26 نوفمبر 2026 · 1:58 م». */
fun formatSupportDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy · h:mm a", Locale("ar")).format(Date(millis))
