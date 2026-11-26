package com.focusstudy.poc

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import java.io.File

/**
 * التنزيل المباشر للتحديث من داخل التطبيق (0.32.0):
 * يقرأ `apkUrl` من الموجز المركزي عبر [AppRelease]، وينزّل ملف APK عبر
 * [DownloadManager] إلى مجلد خاص بالتطبيق (`getExternalFilesDir(DOWNLOADS)` —
 * لا يراه أي تطبيق آخر ولا يحتاج إذن تخزين)، ثم يثبّته عبر [FileProvider]
 * الموجود أصلًا (نفس الـ authority المستخدم لصور الدعم) مع
 * `FLAG_GRANT_READ_URI_PERMISSION`.
 *
 * لا يمسّ خط التحديث الذاتي: الفحص والمقارنة والإشعار مرة لكل إصدار تبقى
 * كلها في [AppUpdateManager]، وهذا الملف يضيف فقط طريق التنزيل والتثبيت.
 * وصفحة المتجر تبقى خيارًا احتياطيًا من الشاشة نفسها.
 */

/** حالة التنزيل المباشر كما تعرضها شاشة التحديثات. */
internal sealed interface DirectDownloadState {
    data object Idle : DirectDownloadState
    /** progress من 0f إلى 1f، و‎-1f عندما يكون الحجم الكلي غير معروف بعد. */
    data class Downloading(val progress: Float) : DirectDownloadState
    data object ReadyToInstall : DirectDownloadState
    data class Failed(val message: String) : DirectDownloadState
}

/** نتيجة استعلام [DownloadManager] عن تنزيل واحد. */
internal data class DownloadQuery(
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long
)

internal object DirectUpdateDownloader {

    private const val PREFS = "focus_study_direct_update"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_DOWNLOAD_VERSION = "download_version_code"
    private const val APK_PREFIX = "MONAZZEM-"

    /** اسم ملف APK لهذا الإصدار؛ يُطهَّر فلا يحمل إلا حروفًا وأرقامًا آمنة للمسار. */
    fun apkFileName(release: AppRelease): String {
        val safeVersion = release.versionName
            .map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("").ifBlank { release.versionCode.toString() }
        return "$APK_PREFIX$safeVersion.apk"
    }

    private fun updatesDir(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("تعذر الوصول إلى مجلد التنزيلات الخاص بالتطبيق.")

    fun apkFile(context: Context, release: AppRelease): File =
        File(updatesDir(context), apkFileName(release))

    /**
     * true إذا كان ملف APK لهذا الإصدار منزَّلًا كاملًا من قبل (فلا يُعاد تنزيله).
     *
     * الطول وحده لا يكفي (م2): ملف جزئي أو فاشل له طول أكبر من صفر. لا يُعتبر
     * الملف جاهزًا إلا بحالة STATUS_SUCCESSFUL لآخر تنزيل بدأه التطبيق لهذا
     * الإصدار، مع مطابقة طول الملف للحجم الكلي حين يعرفه DownloadManager.
     * تنزيل منتهٍ بحالة نهائية غير ناجحة يُحذف ملفه حتى لا يُعرض زر تثبيت
     * وهمي. وبلا سجل تنزيل مطابق (سجل مُسح أو ملف من مصدر آخر) لا يُعتبر
     * الملف جاهزًا أبدًا — إعادة التنزيل آمنة ورخيصة.
     */
    fun hasDownloadedApk(context: Context, release: AppRelease): Boolean {
        val file = runCatching { apkFile(context, release) }.getOrNull() ?: return false
        val lastId = lastDownloadId(context)
        if (lastId == 0L || lastDownloadVersionCode(context) != release.versionCode) return false
        val query = queryDownload(context, lastId)
        if (query.status != DownloadManager.STATUS_SUCCESSFUL) {
            // الحالة النهائية غير الناجحة (ومنها غياب السجل) تُحذف ملفها.
            if (query.status == DownloadManager.STATUS_FAILED) file.delete()
            return false
        }
        if (!file.isFile || file.length() <= 0L) return false
        if (query.totalBytes > 0 && file.length() != query.totalBytes) {
            file.delete()
            return false
        }
        return true
    }

    /** يحذف ملف APK لإصدار معين (يُستدعى عند فشل تنزيله حتى لا يبقى ملف جزئي). */
    fun deleteApk(context: Context, release: AppRelease) {
        runCatching { apkFile(context, release) }.getOrNull()?.delete()
    }

    /** يحذف ملفات APK لإصدارات أخرى من مجلد التحديثات؛ ملف الإصدار الحالي يبقى. */
    private fun clearStaleApks(context: Context, keep: File?) {
        runCatching {
            updatesDir(context).listFiles()
                ?.filter { it.name.startsWith(APK_PREFIX) && it.name.endsWith(".apk") && it != keep }
                ?.forEach { it.delete() }
        }
    }

    /**
     * يبدأ التنزيل ويعيد معرّف [DownloadManager] الخاص به.
     * يرمي استثناءً عند فشل الإدراج (مثلًا بلا اتصال) فيلتقطه المستدعي ويعرض فشلًا.
     */
    fun startDownload(context: Context, release: AppRelease): Long {
        require(release.apkUrl.isNotBlank()) { "لا يوجد رابط تنزيل مباشر لهذا الإصدار." }
        val target = apkFile(context, release)
        clearStaleApks(context, keep = target)
        if (target.exists()) target.delete()
        val request = DownloadManager.Request(Uri.parse(release.apkUrl)).apply {
            setTitle("مُنظِّم ${release.versionName}")
            setDescription("جارٍ تنزيل التحديث…")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, apkFileName(release))
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = manager.enqueue(request)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putLong(KEY_DOWNLOAD_VERSION, release.versionCode)
            .apply()
        return downloadId
    }

    /** معرّف آخر تنزيل بدأه التطبيق (للمطابقة عند العودة للشاشة)؛ 0 إن لم يوجد. */
    fun lastDownloadId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DOWNLOAD_ID, 0L)

    /** رقم إصدار آخر تنزيل بدأه التطبيق؛ لمطابقة التنزيل الجاري مع الإصدار المعروض. */
    fun lastDownloadVersionCode(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_DOWNLOAD_VERSION, 0L)

    /** يسأل [DownloadManager] عن حالة تنزيل واحد؛ غياب السجل يُعامل كفشل. */
    fun queryDownload(context: Context, downloadId: Long): DownloadQuery {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor?.use {
            if (it.moveToFirst()) {
                val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val downloadedIndex = it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                return DownloadQuery(
                    status = if (statusIndex >= 0) it.getInt(statusIndex) else DownloadManager.STATUS_FAILED,
                    downloadedBytes = if (downloadedIndex >= 0) it.getLong(downloadedIndex) else 0L,
                    totalBytes = if (totalIndex >= 0) it.getLong(totalIndex) else -1L
                )
            }
        }
        return DownloadQuery(DownloadManager.STATUS_FAILED, 0L, -1L)
    }

    /**
     * يفتح مثبّت النظام على ملف APK المنزَّل عبر FileProvider.
     * يعيد false عند غياب الملف أو غياب تطبيق يعالج نية التثبيت.
     */
    fun installDownloadedApk(context: Context, release: AppRelease): Boolean {
        // فحص أخير قبل فتح المثبّت: زر «تثبيت التحديث» قد يكون قديمًا (حالة
        // ReadyToInstall من جلسة سابقة) فلا يُفتح المثبّت على ملف ناقص أو فاشل.
        if (!hasDownloadedApk(context, release)) return false
        val file = runCatching { apkFile(context, release) }.getOrNull()
            ?.takeIf { it.isFile && it.length() > 0L } ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}

/**
 * بوابة إذن تثبيت التطبيقات (`REQUEST_INSTALL_PACKAGES`) — على نهج
 * [PermissionGate] نفسه، مع فرق جوهري واحد: هذا الإذن خاص، فلا حوار
 * تشغيل (runtime) له؛ يُفعَّل من إعدادات النظام فقط عبر
 * `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`، والتحقق منه عبر
 * `packageManager.canRequestPackageInstalls()`.
 *
 * القاعدة كما في البوابة المركزية: يُطلب مرة واحدة فقط في عمر التثبيت، وعند
 * أول استخدام فعلي للتنزيل المباشر — لا عند بدء التطبيق ولا عند فتح الشاشة.
 * وقبل فتح الإعدادات يُعرض شرح داخل التطبيق يوضح السبب، ولا تُفتح الإعدادات
 * إلا بعد موافقة المستخدم على الشرح. بعد الرفض لا تُفتح الإعدادات تلقائيًا
 * أبدًا: عند محاولة التحديث مجددًا يُعرض فقط تلميح يدوي يوجّه للإعدادات
 * مع خيار المتجر الاحتياطي.
 */
enum class InstallGateDialogKind { EXPLAIN, DENIED_HINT }

internal object InstallPermissionGate {

    /** نفس ملف تفضيلات البوابة المركزية، بمفتاح مستقل لهذا الإذن. */
    private const val PREFS = "permission_gate"
    private const val KEY_ASKED = "asked_install_packages"

    /** الحالة الحقيقية من النظام، تُقرأ في كل مرة (تفعيل يدوي لاحق يُرى فورًا). */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** true إذا فُتحت إعدادات التثبيت لهذا الإذن من قبل — ولو مرة واحدة. */
    fun wasAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    /** تُستدعى لحظة فتح إعدادات النظام فعليًا فقط — لا عند عرض الشرح. */
    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ASKED, true).apply()
    }

    /** نية صفحة «تثبيت تطبيقات غير معروفة» الخاصة بهذا التطبيق في إعدادات النظام. */
    fun installSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * نافذة البوابة لإذن التثبيت، بنفس شكل [PermissionGateDialog] وألوانه:
 * في صيغة الشرح «فهمت، افتح الإعدادات» تفتح إعدادات النظام، و«ليس الآن»
 * تغلق فقط. في صيغة الرفض السابق: زر واحد يفتح إعدادات التثبيت، ولا يوجد
 * أي مسار هنا يفتح الإعدادات تلقائيًا.
 */
@Composable
internal fun InstallPermissionDialog(
    kind: InstallGateDialogKind,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = FocusShapes.large,
        containerColor = FocusColors.SurfaceHigh,
        title = { Text("إذن تثبيت التحديثات", color = FocusColors.TextPrimary) },
        text = {
            Text(
                if (kind == InstallGateDialogKind.EXPLAIN) {
                    "لتنزيل التحديث وتثبيته من داخل التطبيق، يحتاج مُنظِّم إذن تثبيت التطبيقات من مصادر غير معروفة. سنفتح لك إعدادات النظام لتفعيله لهذا التطبيق فقط، ولا يُستخدم الإذن لأي تطبيق آخر. وبدون هذا الإذن يمكنك دائمًا التحديث من صفحة المتجر."
                } else {
                    "إذن التثبيت غير مفعّل. يمكنك تفعيله يدويًا من الإعدادات (التطبيقات ← مُنظِّم ← تثبيت تطبيقات غير معروفة ← السماح)، أو حمّل التحديث من صفحة المتجر بدلًا من ذلك."
                },
                color = FocusColors.TextSecondary
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(if (kind == InstallGateDialogKind.EXPLAIN) "فهمت، افتح الإعدادات" else "فتح إعدادات التثبيت")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (kind == InstallGateDialogKind.EXPLAIN) "ليس الآن" else "إغلاق")
            }
        }
    )
}
