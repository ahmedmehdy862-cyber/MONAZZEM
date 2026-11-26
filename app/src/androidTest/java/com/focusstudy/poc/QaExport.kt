package com.focusstudy.poc

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * يصدّر ملفات جولة QA إلى مجلد التنزيلات المشترك (Download/qa-screenshots).
 *
 * لماذا لا يكفي مجلد التطبيق الخارجي؟ لأن مهمة connectedDebugAndroidTest
 * تُلغي تثبيت التطبيق بعد انتهاء الاختبارات، فيُحذف معه
 * /sdcard/Android/data/com.focusstudy.poc/files/qa-screenshots كاملًا —
 * لذلك فشل كل سحب بعد الاختبار بـ «No such file or directory» (التشغيل
 * 45756152735) وبـ «run-as: unknown package» (التشغيل 45760986613)، رغم
 * أن اللقطات التُقطت فعلًا. ملفات التنزيلات المشتركة تبقى بعد إلغاء
 * التثبيت، ويسحبها adb pull عادي بلا run-as.
 *
 * الكتابة عبر MediaStore (بلا أي إذن — الملفات من إنشاء التطبيق نفسه)،
 * والنسخة الأحدث تحل محل أي نسخة سابقة بنفس الاسم.
 */
object QaExport {

    fun exportDir(context: Context, dir: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val files = dir.listFiles()?.sortedBy { it.name } ?: return
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        for (file in files) {
            if (!file.isFile) continue
            runCatching {
                // احذف أي نسخة سابقة بنفس الاسم حتى لا تتراكم نسخ «(1)».
                resolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=?",
                    arrayOf(file.name),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        resolver.delete(
                            ContentUris.withAppendedId(collection, id), null, null,
                        )
                    }
                }
                val mime = if (file.extension == "png") "image/png" else "text/plain"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/qa-screenshots")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return@runCatching
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().copyTo(out)
                }
                val done = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
            }
        }
    }
}
