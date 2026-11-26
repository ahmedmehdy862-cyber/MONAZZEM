package com.focusstudy.poc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** نتيجة تحليل صورة: وجه بملف ملامح، أو لا وجه واضح، أو الكاشف نفسه غير متاح. */
internal sealed interface FaceAnalysisResult {
    data class Detected(val profile: FaceProfile) : FaceAnalysisResult
    data object NoFace : FaceAnalysisResult
    data object Unavailable : FaceAnalysisResult
}

/**
 * كشف الوجه على الجهاز (ML Kit المضمّن) + تصحيح اتجاه EXIF لصور الأفاتار.
 *
 * النموذج مضمّن في الـ APK (face-detection 16.1.7) — الكشف يعمل دون إنترنت
 * تمامًا وبلا اعتماد على Play Services، وبلا رفع أي صورة. يُستدعى مرة واحدة
 * عند اختيار الصورة؛ عند غياب الوجه يرجع null فيُستخدم الكرتون القديم
 * (بذرة اللون) مع رسالة ودية، فلا شيء ينكسر.
 *
 * التهيئة كسولة هنا (hotfix 0.33.1): موفّر التهيئة التلقائي
 * (MlKitInitProvider) معطَّل في الـ manifest لأنه كان يعمل قبل
 * Application.onCreate ويُسقط التطبيق عند الإقلاع على بعض الأجهزة دون أي
 * واجهة — فلا شيء من ML Kit يعمل عند بدء التطبيق، والتهيئة تتم فقط عند
 * أول تحليل صورة فعلًا.
 */
internal object FaceAnalyzer {

    private fun detectorOptions(): FaceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.15f)
        .build()

    /**
     * مهلة تذكّر فشل التهيئة (M2): على جهاز تفشل فيه التهيئة دائمًا، إعادة
     * المحاولة عند كل اختيار صورة عقوبة متكررة بلا معنى. يُخزَّن وقت
     * آخر فشل، وأي محاولة خلال هذه المهلة ترجع false فورًا دون محاولة؛
     * بعد انقضائها تُعاد محاولة كاملة واحدة — فالفشل قد يكون عابرًا
     * (تحديث خدمات مثلًا)، لذا لا يُخزَّن للأبد.
     */
    private const val INIT_FAILURE_COOLDOWN_MS = 5 * 60 * 1000L

    @Volatile
    private var mlKitFailedAtMillis = 0L

    /**
     * تهيئة ML Kit كسولة (hotfix 0.33.1): موفّر التهيئة التلقائي معطَّل في
     * الـ manifest، فالتهيئة تتم هنا فقط عند أول تحليل صورة.
     *
     * تصحيح مهم (2026-11-26، كشفه بناء CI): `MlKit.initialize()` ترجع
     * `void` في mlkit-common — مثبَت من الكلاس نفسه — فلا `Task` تُنتظَر
     * أصلًا؛ الافتراض السابق (مهمة تُرمى قبل اكتمالها) كان خاطئًا،
     * والاستدعاء متزامن: النجاح = عودته دون رمي استثناء، والفشل = استثناء.
     * يُخزَّن النجاح فلا تُعاد التهيئة في كل صورة، ويُخزَّن الفشل مع
     * مهلة 5 دقائق (M2) فلا تُعاد المحاولة عند كل صورة على جهاز يفشل
     * فيه دائمًا.
     */
    @Volatile
    private var mlKitReady = false

    private suspend fun ensureMlKitInitialized(context: Context): Boolean {
        if (mlKitReady) return true
        // فشل حديث (أقل من 5 دقائق): لا محاولة جديدة — ارجع فورًا.
        if (mlKitFailedAtMillis != 0L &&
            SystemClock.uptimeMillis() - mlKitFailedAtMillis < INIT_FAILURE_COOLDOWN_MS
        ) return false
        // التهيئة متزامنة (void): النجاح = عودة الاستدعاء دون رمي.
        val initialized = runCatching {
            MlKit.initialize(context.applicationContext)
        }.isSuccess
        if (initialized) {
            mlKitReady = true
            mlKitFailedAtMillis = 0L
        } else {
            mlKitFailedAtMillis = SystemClock.uptimeMillis()
        }
        return initialized
    }

    /**
     * يحلل صورة ويرجع ملف ملامح أكبر وجه فيها، أو null إن لم يوجد وجه واضح
     * أو فشل الكشف — اختصار فوق [analyzeDetailed] للمتّصلين القدامى.
     * لا ترمي أبدًا (عدا إلغاء الـ coroutine).
     */
    suspend fun analyze(context: Context, bitmap: Bitmap): FaceProfile? =
        when (val result = analyzeDetailed(context, bitmap)) {
            is FaceAnalysisResult.Detected -> result.profile
            else -> null
        }

    /**
     * يحلل صورة ويرجع نتيجة مفصّلة: [FaceAnalysisResult.Detected] بملف
     * ملامح أكبر وجه، أو [FaceAnalysisResult.NoFace] إن لم يوجد وجه واضح،
     * أو [FaceAnalysisResult.Unavailable] إن تعذّر تشغيل الكاشف نفسه
     * (تهيئة لم تكتمل، فشل الكشف، أو تعذّر بناء الملف من وجه وُجد) —
     * فيُعرض للشخص سبب حقيقي بدل رسالة «لا وجه» المضللة.
     * لا تُستدعى من الخيط الرئيسي إلا عبر coroutine (الكشف غير متزامن
     * داخلًا)؛ وإن أُلغيت الـ coroutine يُغلق الكاشف فورًا. مستمع ML Kit
     * يستلم الوجه فقط؛ وحساب الملف (قراءة بكسلات الصورة وعيّنتها) يجري
     * على Dispatchers.Default بعد الاستئناف، لا على مستمع الكشف (الذي
     * يعمل على الخيط الرئيسي افتراضيًا).
     * لا ترمي أبدًا (عدا إلغاء الـ coroutine): أي فشل في ML Kit — غياب
     * النموذج، فشل تحميل المكتبة الأصلية، تهيئة غير مكتملة — يرجع
     * [FaceAnalysisResult.Unavailable].
     */
    suspend fun analyzeDetailed(context: Context, bitmap: Bitmap): FaceAnalysisResult {
        if (!ensureMlKitInitialized(context)) return FaceAnalysisResult.Unavailable
        return try {
            val detectionBitmap = downscaleForDetection(bitmap)
            try {
                // فشل مهمة الكشف نفسها يُميَّز عن «لا وجوه»: الأول عطل في
                // الكاشف (Unavailable)، والثاني صورة بلا وجه واضح (NoFace).
                val failed = AtomicBoolean(false)
                val face = suspendCancellableCoroutine<Face?> { cont ->
                    val detector = FaceDetection.getClient(detectorOptions())
                    // إغلاق مرة واحدة فقط: قد يتسابق الإلغاء مع نجاح/فشل الكشف.
                    val closed = AtomicBoolean(false)
                    fun closeOnce() {
                        if (closed.compareAndSet(false, true)) detector.close()
                    }
                    cont.invokeOnCancellation { closeOnce() }
                    val image = InputImage.fromBitmap(detectionBitmap, 0)
                    detector.process(image)
                        .addOnSuccessListener { faces ->
                            val largest = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                            closeOnce()
                            if (cont.isActive) cont.resume(largest)
                        }
                        .addOnFailureListener {
                            failed.set(true)
                            closeOnce()
                            if (cont.isActive) cont.resume(null)
                        }
                }
                when {
                    failed.get() -> FaceAnalysisResult.Unavailable
                    face == null -> FaceAnalysisResult.NoFace
                    else -> {
                        val profile = withContext(Dispatchers.Default) {
                            runCatching { profileFromFace(face, detectionBitmap) }
                                .onFailure { if (it is CancellationException) throw it }
                                .getOrNull()
                        }
                        if (profile != null) FaceAnalysisResult.Detected(profile)
                        else FaceAnalysisResult.Unavailable
                    }
                }
            } finally {
                if (detectionBitmap !== bitmap) detectionBitmap.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            FaceAnalysisResult.Unavailable
        }
    }

    /**
     * تصغير للكشف: أطول ضلع 512px كحد أقصى. الصور الأكبر تبطئ الكشف وتستهلك
     * ذاكرة دون فائدة لملف الملامح؛ ويُعاد نفس الـ Bitmap إن كان ضمن الحد.
     * هندسة الوجه وعيّنتها تُقاسان على الصورة المصغّرة نفسها فتبقيان متسقتين.
     */
    private fun downscaleForDetection(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= 512) return bitmap
        val scale = 512f / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun profileFromFace(face: Face, bitmap: Bitmap): FaceProfile? {
        val box = face.boundingBox
        if (box.width() <= 0 || box.height() <= 0) return null
        val geometry = FaceGeometry(
            boxLeft = box.left.toFloat(),
            boxTop = box.top.toFloat(),
            boxWidth = box.width().toFloat(),
            boxHeight = box.height().toFloat(),
            faceContour = face.contourPoints(FaceContour.FACE),
            leftEye = face.contourPoints(FaceContour.LEFT_EYE),
            rightEye = face.contourPoints(FaceContour.RIGHT_EYE),
            leftBrow = face.contourPoints(FaceContour.LEFT_EYEBROW_TOP),
            rightBrow = face.contourPoints(FaceContour.RIGHT_EYEBROW_TOP),
            upperLip = face.contourPoints(FaceContour.UPPER_LIP_BOTTOM),
            lowerLip = face.contourPoints(FaceContour.LOWER_LIP_TOP),
            eulerZ = face.headEulerAngleZ,
            smilingProbability = face.smilingProbability
        )
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val palette = CartoonAvatar.paletteFrom(downscalePixels(bitmap), 0)
        val samples = FaceSampler.sampleAll(pixels, width, height, geometry, palette.skin, palette.hair)
        return FaceProfile.fromGeometry(geometry, samples)
    }

    private fun Face.contourPoints(type: Int): List<FPoint> =
        getContour(type)?.points?.map { FPoint(it.x, it.y) }.orEmpty()

    /** عيّنة 32×32 من الصورة لاشتقاق ألوان احتياطية عند فشل عيّنة الوجه. */
    private fun downscalePixels(bitmap: Bitmap): IntArray {
        val sample = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        return try {
            val pixels = IntArray(32 * 32)
            sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            pixels
        } finally {
            if (sample !== bitmap) sample.recycle()
        }
    }

    /** اتجاه EXIF من URI (منتقي صور النظام)؛ NORMAL عند غيابه أو فشل القراءة. */
    fun orientationFromUri(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /**
     * يصحح اتجاه صورة حسب EXIF (صور الكاميرا غالبًا مخزنة مائلة 90°).
     * يعيد نفس الصورة إن لم تحتج تغييرًا؛ ويعيد null إن فشل التحويل.
     * قيم القلب (2، 4، 5، 7) معالَجة بالمصفوفة لا مهملة: القلب الأفقي
     * والعمودي عبر قلب + إزاحة، والمقلوبتان عبر تدوير + قلب أفقي.
     */
    fun normalizeOrientation(bitmap: Bitmap, orientation: Int): Bitmap? {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_NORMAL -> return bitmap
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.postScale(-1f, 1f)
                matrix.postTranslate(bitmap.width.toFloat(), 0f)
            }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.postScale(1f, -1f)
                matrix.postTranslate(0f, bitmap.height.toFloat())
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull()
    }
}
