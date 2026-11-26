package com.focusstudy.poc

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Test
import org.junit.runner.RunWith

/**
 * المشكلة 2 من أحمد: تحويل الصورة يعطي شخصية ثابتة مهما رُفعت أي صورة.
 *
 * يرسم نفس المصدر مرتين بملفي ملامح متطرفين الاختلاف (وجه عريض/ضيق، عينان
 * متباعدتان/متقاربتان، بشرة فاتحة/داكنة، شعر قصير/طويل، نظارة ولحية مقابل
 * لا شيء، ابتسامة 0.95 مقابل 0.45، ميل رأس -20 مقابل 0) ويقيس نسبة البكسلات
 * المختلفة. إن كانت النسبة ضئيلة فالرسم قالبي فعليًا مهما اختلف الملف — وهذا
 * يثبت شكوى أحمد بالأرقام. كما يرسم مسار الرجوع القديم (stylize) للمقارنة.
 *
 * بلا واجهة وبلا منتقي صور: الرسم الخالص فقط، فيعمل على أي محاكي.
 */
@RunWith(AndroidJUnit4::class)
class AvatarRenderQaTest {

    private fun savePng(dir: File, name: String, bitmap: Bitmap) {
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun diffPercent(a: Bitmap, b: Bitmap): Double {
        require(a.width == b.width && a.height == b.height) { "size mismatch" }
        val w = a.width
        val h = a.height
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h)
        b.getPixels(pb, 0, w, 0, 0, w, h)
        var diff = 0L
        for (i in pa.indices) if (pa[i] != pb[i]) diff++
        return diff * 100.0 / pa.size
    }

    @Test
    fun profileChangesDrawing() {
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "qa-screenshots").apply { mkdirs() }
        val source = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(0xFFD9A27A.toInt()) }

        val profileA = FaceProfile.DEFAULT
        val profileB = FaceProfile.DEFAULT.copy(
            faceAspect = 1.05f,
            eyeSpacing = 0.52f,
            eyeSize = 0.16f,
            eyeHeight = 0.30f,
            browAngle = 12f,
            smile = 0.95f,
            headTilt = -20f,
            skinColor = 0xFF6B4226.toInt(),
            hairColor = 0xFF111111.toInt(),
            hairLength = 1.0f,
            hasGlasses = true,
            hasBeard = true,
        )
        val bmpA = CartoonAvatar.stylizeWithProfile(source, profileA, 0)
        val bmpB = CartoonAvatar.stylizeWithProfile(source, profileB, 0)
        val bmpFallback = CartoonAvatar.stylize(source, 0)
        savePng(dir, "avatar-profile-A.png", bmpA)
        savePng(dir, "avatar-profile-B.png", bmpB)
        savePng(dir, "avatar-fallback.png", bmpFallback)
        val ab = diffPercent(bmpA, bmpB)
        val af = diffPercent(bmpA, bmpFallback)
        val report = "AVATAR DIFF profileA-vs-profileB=%.2f%% profileA-vs-fallback=%.2f%%\n".format(ab, af)
        File(dir, "avatar-diff.txt").writeText(report)
        Log.i("EmuQA", report)
        QaExport.exportDir(ctx, dir)
        bmpA.recycle()
        bmpB.recycle()
        bmpFallback.recycle()
        source.recycle()
    }
}
