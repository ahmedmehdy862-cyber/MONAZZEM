package com.focusstudy.poc

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * ملف ملامح الوجه — تحويل خالص قابل للاختبار، بلا أي اعتماد على Android.
 *
 * الفكرة: كاشف الوجه (ML Kit، في [FaceAnalyzer]) يعطي هندسة الوجه الخام
 * (كونتورات + زوايا + احتمال ابتسامة) وعيّنات لونية ([FaceSamples])، وهذا
 * الملف يحوّلها إلى معاملات رسم مفهومة (0..1 غالبًا) يستخدمها
 * [CartoonAvatar.drawProfiled]. كل الدوال هنا حتمية ومجرّبة في
 * [Part5AvatarTest] دون محاكاة Android.
 */

/** نقطة ثنائية الأبعاد خالصة (بديل `android.graphics.PointF` ليبقى الكود قابلًا للاختبار). */
internal data class FPoint(val x: Float, val y: Float)

/** هندسة الوجه المكتشف كما يراها الكاشف، بإحداثيات بكسل الصورة. */
internal data class FaceGeometry(
    val boxLeft: Float,
    val boxTop: Float,
    val boxWidth: Float,
    val boxHeight: Float,
    val faceContour: List<FPoint>,
    val leftEye: List<FPoint>,
    val rightEye: List<FPoint>,
    val leftBrow: List<FPoint>,
    val rightBrow: List<FPoint>,
    val upperLip: List<FPoint>,
    val lowerLip: List<FPoint>,
    /** ميل الرأس حول المحور الأمامي (Euler Z) بالدرجات. */
    val eulerZ: Float,
    /** احتمال أن يكون الوجه مبتسمًا (0..1) أو null إن لم يقدّمه الكاشف. */
    val smilingProbability: Float?,
)

/** عيّنات لونية محسوبة من بكسلات الصورة داخل مناطق الوجه وحولها (انظر [FaceSampler]). */
internal data class FaceSamples(
    val skinColor: Int,
    val hairColor: Int,
    /** طول الشعر المستنتج 0 (قصير) .. 1 (طويل). */
    val hairLength: Float,
    /** كثافة الظلمة حول العينين 0..1 — فوق العتبة تعني نظارة غالبًا. */
    val glassesScore: Float,
    /** كثافة الظلمة أسفل الوجه 0..1 — فوق العتبة تعني لحية غالبًا. */
    val beardScore: Float,
)

/**
 * معاملات رسم الأفاتار المستنتجة من صورة المستخدم. كل القيم مُقيَّدة بنطاقات
 * آمنة للرسم، والتصحيحات اليدوية ([glassesOverride] وأخواتها) تُطبَّق فوقها
 * عبر دوال `effective*` — فالكشف التلقائي اقتراح لا حكم نهائي.
 */
internal data class FaceProfile(
    /** عرض الوجه / ارتفاعه (0.6..1.1). */
    val faceAspect: Float,
    /** المسافة بين مركزي العينين / عرض الوجه (0.25..0.55). */
    val eyeSpacing: Float,
    /** عرض العين / عرض الوجه (0.05..0.2). */
    val eyeSize: Float,
    /** موضع العينين من أعلى الوجه 0..1 (0.2..0.6). */
    val eyeHeight: Float,
    /** ميل الحاجبين بالدرجات (-15..15)؛ الموجب يعني الطرف الخارجي أخفض. */
    val browAngle: Float,
    /** الابتسامة 0..1. */
    val smile: Float,
    /** ميل الرأس بالدرجات (-30..30). */
    val headTilt: Float,
    /** لون البشرة (ARGB) مأخوذ من داخل كونتور الوجه فقط. */
    val skinColor: Int,
    /** لون الشعر (ARGB) مأخوذ من الشريط فوق الوجه. */
    val hairColor: Int,
    /** طول الشعر 0..1. */
    val hairLength: Float,
    val hasGlasses: Boolean,
    val hasBeard: Boolean,
    /** تصحيح يدوي للنظارة: -1 إخفاء، 0 تلقائي، 1 إظهار. */
    val glassesOverride: Int = 0,
    /** تصحيح يدوي للحية: -1 إخفاء، 0 تلقائي، 1 إظهار. */
    val beardOverride: Int = 0,
    /** تصحيح يدوي لطول الشعر (-1..1) يضاف إلى [hairLength]. */
    val hairLengthAdjust: Float = 0f,
    /** تصحيح يدوي للابتسامة (-1..1) يضاف إلى [smile]. */
    val smileAdjust: Float = 0f,
) {
    fun effectiveGlasses(): Boolean = when (glassesOverride) {
        1 -> true
        -1 -> false
        else -> hasGlasses
    }

    fun effectiveBeard(): Boolean = when (beardOverride) {
        1 -> true
        -1 -> false
        else -> hasBeard
    }

    fun effectiveHairLength(): Float = (hairLength + hairLengthAdjust).coerceIn(0f, 1f)

    fun effectiveSmile(): Float = (smile + smileAdjust).coerceIn(0f, 1f)

    /** JSON مضغوط بإصدار، يُحفظ في التفضيلات (`avatar_face_profile_json`) بلا مخطط DB. */
    fun toJson(): String = buildString {
        append("{\"v\":1,")
        append("\"fa\":${num(faceAspect)},\"es\":${num(eyeSpacing)},\"ez\":${num(eyeSize)},")
        append("\"eh\":${num(eyeHeight)},\"ba\":${num(browAngle)},\"sm\":${num(smile)},")
        append("\"ht\":${num(headTilt)},\"sk\":$skinColor,\"hc\":$hairColor,")
        append("\"hl\":${num(hairLength)},\"gl\":${if (hasGlasses) 1 else 0},\"bd\":${if (hasBeard) 1 else 0},")
        append("\"go\":$glassesOverride,\"bo\":$beardOverride,")
        append("\"ha\":${num(hairLengthAdjust)},\"sa\":${num(smileAdjust)}}")
    }

    companion object {
        /** عتبة اعتبار كثافة ظلمة العينين نظارة. */
        const val GLASSES_THRESHOLD = 0.25f

        /** عتبة اعتبار كثافة ظلمة أسفل الوجه لحية. */
        const val BEARD_THRESHOLD = 0.30f

        /** ملف محايد يطابق هندسة الرسم القديم — يُستخدم عند غياب الوجه أو فشل الكشف. */
        val DEFAULT = FaceProfile(
            faceAspect = 0.86f,
            eyeSpacing = 0.43f,
            eyeSize = 0.095f,
            eyeHeight = 0.44f,
            browAngle = 0f,
            smile = 0.45f,
            headTilt = 0f,
            skinColor = 0xFFD9A27A.toInt(),
            hairColor = 0xFF3A2A1E.toInt(),
            hairLength = 0.35f,
            hasGlasses = false,
            hasBeard = false
        )

        /** يحوّل هندسة كاشف وعيّنات لونية إلى ملف ملامح. لا يرمي أبدًا — القيم الناقصة تُستبدل بالمحايد. */
        fun fromGeometry(geometry: FaceGeometry, samples: FaceSamples): FaceProfile {
            val boxW = geometry.boxWidth.takeIf { it > 0f } ?: 100f
            val boxH = geometry.boxHeight.takeIf { it > 0f } ?: 120f
            val (leftEyeCenter, rightEyeCenter) = eyeCenters(geometry)
            val eyeDistance = hypot(rightEyeCenter.x - leftEyeCenter.x, rightEyeCenter.y - leftEyeCenter.y)
            val eyeWidth = (eyeContourWidth(geometry.leftEye) + eyeContourWidth(geometry.rightEye)) / 2f
            val eyeCenterY = (leftEyeCenter.y + rightEyeCenter.y) / 2f
            val mouthOpenness = mouthOpenness(geometry, boxH)
            val probability = geometry.smilingProbability?.coerceIn(0f, 1f)
            val smile = when {
                probability != null -> (0.6f * probability + 0.4f * mouthOpenness).coerceIn(0f, 1f)
                geometry.upperLip.isNotEmpty() && geometry.lowerLip.isNotEmpty() -> mouthOpenness
                else -> 0.45f
            }
            return FaceProfile(
                faceAspect = (boxW / boxH).coerceIn(0.6f, 1.1f),
                eyeSpacing = (eyeDistance / boxW).coerceIn(0.25f, 0.55f),
                eyeSize = (eyeWidth / boxW).coerceIn(0.05f, 0.2f),
                eyeHeight = ((eyeCenterY - geometry.boxTop) / boxH).coerceIn(0.2f, 0.6f),
                browAngle = browAngle(geometry).coerceIn(-15f, 15f),
                smile = smile,
                headTilt = geometry.eulerZ.coerceIn(-30f, 30f),
                skinColor = samples.skinColor,
                hairColor = samples.hairColor,
                hairLength = samples.hairLength.coerceIn(0f, 1f),
                hasGlasses = samples.glassesScore >= GLASSES_THRESHOLD,
                hasBeard = samples.beardScore >= BEARD_THRESHOLD
            )
        }

        /** يقرأ JSON كتبه [toJson]؛ يرجع null لأي نص تالف أو بإصدار غير معروف. */
        fun fromJson(json: String): FaceProfile? {
            if (json.isBlank()) return null
            val version = readInt(json, "v") ?: return null
            if (version != 1) return null
            val faceAspect = readFloat(json, "fa") ?: return null
            val eyeSpacing = readFloat(json, "es") ?: return null
            val eyeSize = readFloat(json, "ez") ?: return null
            val eyeHeight = readFloat(json, "eh") ?: return null
            val browAngle = readFloat(json, "ba") ?: return null
            val smile = readFloat(json, "sm") ?: return null
            val headTilt = readFloat(json, "ht") ?: return null
            val skinColor = readInt(json, "sk") ?: return null
            val hairColor = readInt(json, "hc") ?: return null
            val hairLength = readFloat(json, "hl") ?: return null
            val hasGlasses = (readInt(json, "gl") ?: return null) != 0
            val hasBeard = (readInt(json, "bd") ?: return null) != 0
            return FaceProfile(
                faceAspect = faceAspect.coerceIn(0.6f, 1.1f),
                eyeSpacing = eyeSpacing.coerceIn(0.25f, 0.55f),
                eyeSize = eyeSize.coerceIn(0.05f, 0.2f),
                eyeHeight = eyeHeight.coerceIn(0.2f, 0.6f),
                browAngle = browAngle.coerceIn(-15f, 15f),
                smile = smile.coerceIn(0f, 1f),
                headTilt = headTilt.coerceIn(-30f, 30f),
                skinColor = skinColor,
                hairColor = hairColor,
                hairLength = hairLength.coerceIn(0f, 1f),
                hasGlasses = hasGlasses,
                hasBeard = hasBeard,
                glassesOverride = (readInt(json, "go") ?: 0).coerceIn(-1, 1),
                beardOverride = (readInt(json, "bo") ?: 0).coerceIn(-1, 1),
                hairLengthAdjust = (readFloat(json, "ha") ?: 0f).coerceIn(-1f, 1f),
                smileAdjust = (readFloat(json, "sa") ?: 0f).coerceIn(-1f, 1f)
            )
        }

        /** مركزي العينين (متوسط نقاط الكونتور)؛ افتراضي متناسق عند غياب الكونتور. */
        internal fun eyeCenters(geometry: FaceGeometry): Pair<FPoint, FPoint> {
            val left = averagePoint(geometry.leftEye)
                ?: FPoint(geometry.boxLeft + 0.36f * geometry.boxWidth, geometry.boxTop + 0.42f * geometry.boxHeight)
            val right = averagePoint(geometry.rightEye)
                ?: FPoint(geometry.boxLeft + 0.64f * geometry.boxWidth, geometry.boxTop + 0.42f * geometry.boxHeight)
            return left to right
        }

        /** شريط الفم (أعلى الشفة العليا .. أسفل الشفة السفلى)؛ افتراضي عند غياب الكونتور. */
        internal fun mouthBand(geometry: FaceGeometry): Pair<Float, Float> {
            val upper = geometry.upperLip.minOfOrNull { it.y }
                ?: (geometry.boxTop + 0.68f * geometry.boxHeight)
            val lower = geometry.lowerLip.maxOfOrNull { it.y }
                ?: (geometry.boxTop + 0.78f * geometry.boxHeight)
            return upper to lower
        }

        private fun averagePoint(points: List<FPoint>): FPoint? {
            if (points.isEmpty()) return null
            var x = 0f
            var y = 0f
            for (p in points) {
                x += p.x
                y += p.y
            }
            return FPoint(x / points.size, y / points.size)
        }

        private fun eyeContourWidth(points: List<FPoint>): Float {
            if (points.isEmpty()) return 0f
            val minX = points.minOf { it.x }
            val maxX = points.maxOf { it.x }
            return maxX - minX
        }

        /** انفتاح الفم 0..1 من المسافة بين الشفتين نسبةً إلى ارتفاع الوجه. */
        private fun mouthOpenness(geometry: FaceGeometry, boxHeight: Float): Float {
            if (geometry.upperLip.isEmpty() || geometry.lowerLip.isEmpty()) return 0f
            val upperBottom = geometry.upperLip.maxOf { it.y }
            val lowerTop = geometry.lowerLip.minOf { it.y }
            val gap = (lowerTop - upperBottom).coerceAtLeast(0f)
            return (gap / boxHeight / 0.22f).coerceIn(0f, 1f)
        }

        /**
         * ميل الحاجبين: متوسط ميل خط كل حاجب، والموجب يعني الطرف الخارجي
         * أخفض من الداخلي. الطرف الخارجي للحاجب الأيسر هو أصغر x، وللأيمن أكبر x.
         */
        private fun browAngle(geometry: FaceGeometry): Float {
            val left = browTilt(geometry.leftBrow, outerIsMinX = true)
            val right = browTilt(geometry.rightBrow, outerIsMinX = false)
            val angles = listOfNotNull(left, right)
            if (angles.isEmpty()) return 0f
            return angles.average().toFloat()
        }

        private fun browTilt(points: List<FPoint>, outerIsMinX: Boolean): Float? {
            if (points.size < 2) return null
            val outer = if (outerIsMinX) points.minByOrNull { it.x }!! else points.maxByOrNull { it.x }!!
            val inner = if (outerIsMinX) points.maxByOrNull { it.x }!! else points.minByOrNull { it.x }!!
            val dx = kotlin.math.abs(outer.x - inner.x)
            if (dx < 1f) return null
            return Math.toDegrees(atan2((outer.y - inner.y).toDouble(), dx.toDouble())).toFloat()
        }

        private fun num(value: Float): String {
            val rounded = (kotlin.math.round(value * 1000f) / 1000f)
            return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
        }

        private fun readFloat(json: String, key: String): Float? {
            val match = Regex("\"$key\":(-?[0-9]+(?:\\.[0-9]+)?)").find(json) ?: return null
            return match.groupValues[1].toFloatOrNull()
        }

        private fun readInt(json: String, key: String): Int? {
            val match = Regex("\"$key\":(-?[0-9]+)").find(json) ?: return null
            return match.groupValues[1].toIntOrNull()
        }
    }
}
