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
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
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
     */
    suspend fun analyze(bitmap: Bitmap): FaceProfile? = suspendCancellableCoroutine { cont ->
        val detector = FaceDetection.getClient(detectorOptions())
        cont.invokeOnCancellation { detector.close() }
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces ->
                val profile = faces
                    .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    ?.let { face -> runCatching { profileFromFace(face, bitmap) }.getOrNull() }
                detector.close()
                if (cont.isActive) cont.resume(profile)
            }
            .addOnFailureListener {
                detector.close()
                if (cont.isActive) cont.resume(null)
            }
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
        val pixels = IntArray(32 * 32)
        sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        if (sample !== bitmap) sample.recycle()
        return pixels
    }

    /** اتجاه EXIF من URI (منتقي صور النظام)؛ NORMAL عند غيابه أو فشل القراءة. */
    fun orientationFromUri(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /** اتجاه EXIF من ملف محلي. */
    fun orientationFromFile(file: File): Int = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /**
     * يصحح اتجاه صورة حسب EXIF (صور الكاميرا غالبًا مخزنة مائلة 90°).
     * يعيد نفس الصورة إن لم تحتج تدويرًا؛ ويعيد null إن فشل التدوير.
     * انعكاسات المرآة (FLIP_*) نادرة من منتقي النظام فتُترك كما هي.
     */
    fun normalizeOrientation(bitmap: Bitmap, orientation: Int): Bitmap? {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        return runCatching {
            val matrix = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull()
    }
}
