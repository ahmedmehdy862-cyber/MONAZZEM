package com.focusstudy.poc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * الصورة الشخصية في 0.35.0: رفع بسيط للصورة كما هي — بلا تحويل كرتوني وبلا
 * ML Kit (أُزيل نهائيًا بأمر أحمد 2026-11-26: «شيله وخليه يرفع صورته عادي»).
 *
 * - [copyAvatarToAppFiles] تنسخ الصورة المختارة إلى ملفات التطبيق JPEG
 *   (بحد أقصى 1024px، مع تصحيح اتجاه EXIF) وتعيد مسارها.
 * - ترحيل [FocusPreferences.load]: من كان له نسخة أصلية محفوظة
 *   (`avatar_source_path`) تُستخدم هي؛ ومن كانت صورته ملف JPG مباشرة
 *   تُستخدم كما هي؛ أما الكرتون المولَّد والشخصيات الجاهزة (PNG) فتُسقَط
 *   ويُعرض الحرف الأول.
 *
 * Runs under Robolectric (no device needed) with the runtime pinned to SDK 35.
 * Run with `./gradlew testDebugUnitTest` on a machine with the Android SDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotoAvatarTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        // أغلق مساعد StudyDb: تحميل التفضيلات يفتح قاعدة البيانات (v17)
        // عبر StudyDb.get، واتصال مفتوح يبقي الملف حيًا بعد deleteDatabase
        // فيدخل صنف اختبار لاحق (DbMigrationV16ToV17Test) في
        // «Can't downgrade database from version 17 to 16».
        runCatching { StudyDb.get(context).close() }
        runCatching { context.deleteDatabase("focus_study.db") }
    }

    @Test fun plainPhotoIsCopiedAsJpeg() = runBlocking {
        val source = File(context.cacheDir, "source.png")
        Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFFF0000.toInt())
            compress(Bitmap.CompressFormat.PNG, 100, FileOutputStream(source))
            recycle()
        }
        val copied = copyAvatarToAppFiles(context, Uri.fromFile(source))
        assertTrue(copied != null)
        val file = File(copied!!.path)
        assertTrue(file.exists())
        assertEquals(AVATAR_FILE_NAME, file.name)
        val magic = file.inputStream().use { it.readNBytes(2) }
        assertEquals(0xFF.toByte(), magic[0])
        assertEquals(0xD8.toByte(), magic[1])
        val decoded = BitmapFactory.decodeFile(file.absolutePath)
        assertTrue(decoded.width <= 1024 && decoded.height <= 1024)
    }

    @Test fun invalidUriReturnsNull() = runBlocking {
        val missing = File(context.cacheDir, "does-not-exist.jpg")
        assertNull(copyAvatarToAppFiles(context, Uri.fromFile(missing)))
    }

    @Test fun avatarImageEmptyHasBlankPath() {
        assertTrue(AvatarImage.EMPTY.path.isEmpty())
    }

    @Test fun migrationPrefersOriginalPhotoOverCartoon() {
        context.getSharedPreferences(FocusPreferences.FILE, Context.MODE_PRIVATE).edit()
            .putString("avatar_path", "/files/student_companion_cartoon_0.png")
            .putString("avatar_source_path", "/files/student_companion_original.jpg")
            .apply()
        assertEquals("/files/student_companion_original.jpg", FocusPreferences.load(context).avatarPath)
    }

    @Test fun migrationDropsCartoonOnlyAvatar() {
        context.getSharedPreferences(FocusPreferences.FILE, Context.MODE_PRIVATE).edit()
            .putString("avatar_path", "/files/student_companion_cartoon_2.png")
            .putString("avatar_source_path", "")
            .apply()
        assertTrue(FocusPreferences.load(context).avatarPath.isEmpty())
    }

    @Test fun migrationKeepsPlainPhotoPath() {
        context.getSharedPreferences(FocusPreferences.FILE, Context.MODE_PRIVATE).edit()
            .putString("avatar_path", "/files/student_avatar.jpg")
            .putString("avatar_source_path", "")
            .apply()
        assertEquals("/files/student_avatar.jpg", FocusPreferences.load(context).avatarPath)
    }
}
