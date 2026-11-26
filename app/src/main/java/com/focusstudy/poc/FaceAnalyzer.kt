package com.focusstudy.poc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
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

/**
 * كشف الوجه على الجهاز (ML Kit المضمّن) + تصحيح اتجاه EXIF لصور الأفاتار.
 *
 * النموذج مضمّن في الـ APK (face-detection 16.1.7) — الكشف يعمل دون إنترنت
 * تمامًا وبلا اعتماد على Play Services، وبلا رفع أي صورة. يُستدعى مرة واحدة
 * عند اختيار الصورة؛ عند غياب الوجه يرجع null فيُستخدم الكرتون القديم
 * (بذرة اللون) مع رسالة ودية، فلا شيء ينكسر.
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
     * يحلل صورة ويرجع ملف ملامح أكبر وجه فيها، أو null إن لم يوجد وجه واضح
     * أو فشل الكشف. لا تُستدعى من الخيط الرئيسي إلا عبر coroutine (الكشف
     * غير متزامن داخلًا)؛ وإن أُلغيت الـ coroutine يُغلق الكاشف فورًا.
     * مستمع ML Kit يستلم الوجه فقط؛ وحساب الملف (قراءة بكسلات الصورة
     * وعيّنتها) يجري على Dispatchers.Default بعد الاستئناف، لا على مستمع
     * الكشف (الذي يعمل على الخيط الرئيسي افتراضيًا).
     */
    suspend fun analyze(bitmap: Bitmap): FaceProfile? {
        val detectionBitmap = downscaleForDetection(bitmap)
        try {
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
                        closeOnce()
                        if (cont.isActive) cont.resume(null)
                    }
            } ?: return null
            return withContext(Dispatchers.Default) {
                runCatching { profileFromFace(face, detectionBitmap) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            }
        } finally {
            if (detectionBitmap !== bitmap) detectionBitmap.recycle()
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
