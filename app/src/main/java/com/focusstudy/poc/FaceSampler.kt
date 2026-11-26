package com.focusstudy.poc

/**
 * أخذ عينات لونية من بكسلات الصورة — خالص وقابل للاختبار، بلا اعتماد على Android.
 *
 * القاعدة: لون البشرة يُؤخذ من داخل كونتور الوجه فقط (لا من متوسط الصورة
 * كاملًا، فالخلفية كانت تلوّثه)، ولون الشعر وطوله من الشريط فوق الوجه،
 * والنظارة واللحية من كثافة الظلمة في منطقتيهما. كلها استدلالات تقريبية —
 * منزلقات التصحيح اليدوي في [AvatarChooser] هي شبكة الأمان.
 */
internal object FaceSampler {

    /** عتبة اعتبار البكسل داكنًا عند قياس الشعر (إضاءة 0..255). */
    private const val HAIR_DARK_LUMINANCE = 110

    /** عتبة اعتبار البكسل داكنًا عند قياس إطار النظارة. */
    private const val GLASSES_DARK_LUMINANCE = 90

    /** عتبة اعتبار البكسل داكنًا عند قياس اللحية. */
    private const val BEARD_DARK_LUMINANCE = 80

    /** هل النقطة داخل مضلع (خوارزمية الشعاع — ray casting). */
    fun isInsidePolygon(x: Float, y: Float, polygon: List<FPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val xi = polygon[i].x
            val yi = polygon[i].y
            val xj = polygon[j].x
            val yj = polygon[j].y
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /** إضاءة بكسل ARGB (0..255) بمعادلة Rec. 601. */
    fun luminance(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** هل البكسل دافئ بما يكفي ليكون بشرة غالبًا (نفس مرشح لوحة الأفاتار القديمة). */
    fun isWarmSkinPixel(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return r > g + 10 && g > b + 4 && r in 70..249 && g in 45..220
    }

    /** متوسط لون البكسلات المقبولة داخل مستطيل؛ null إن لم يوجد بكسل مقبول. */
    fun averageColor(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        accept: (Int) -> Boolean = { true },
    ): Int? {
        val l = left.coerceIn(0, width)
        val t = top.coerceIn(0, height)
        val r = right.coerceIn(0, width)
        val b = bottom.coerceIn(0, height)
        if (l >= r || t >= b) return null
        var count = 0
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val pixel = pixels[y * width + x]
                if (((pixel ushr 24) >= 128) && accept(pixel)) {
                    sr += (pixel shr 16) and 0xFF
                    sg += (pixel shr 8) and 0xFF
                    sb += pixel and 0xFF
                    count++
                }
                x++
            }
            y++
        }
        if (count == 0) return null
        return rgb((sr / count).toInt(), (sg / count).toInt(), (sb / count).toInt())
    }

    /** نسبة البكسلات الداكنة داخل مستطيل (0..1)؛ 0 إن كانت المنطقة فارغة. */
    fun darkFraction(
        pixels: IntArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        threshold: Int,
    ): Float {
        val l = left.coerceIn(0, width)
        val t = top.coerceIn(0, height)
        val r = right.coerceIn(0, width)
        val b = bottom.coerceIn(0, height)
        if (l >= r || t >= b) return 0f
        var total = 0
        var dark = 0
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val pixel = pixels[y * width + x]
                if ((pixel ushr 24) >= 128) {
                    total++
                    if (luminance(pixel) < threshold) dark++
                }
                x++
            }
            y++
        }
        if (total == 0) return 0f
        return dark.toFloat() / total
    }

    /**
     * عيّنة كاملة من صورة وملامح: بشرة من داخل الكونتور، شعر من الشريط فوق
     * الوجه، ونتيجتا نظارة ولحية. الألوان الاحتياطية تُستخدم عند فشل العيّنة.
     */
    fun sampleAll(
        pixels: IntArray,
        width: Int,
        height: Int,
        geometry: FaceGeometry,
        fallbackSkin: Int,
        fallbackHair: Int,
    ): FaceSamples {
        val skin = sampleSkin(pixels, width, height, geometry) ?: fallbackSkin
        val (hairColor, hairLength) = sampleHair(pixels, width, height, geometry)
        val glasses = glassesScore(pixels, width, height, geometry)
        val beard = beardScore(pixels, width, height, geometry)
        return FaceSamples(
            skinColor = skin,
            hairColor = hairColor ?: fallbackHair,
            hairLength = hairLength,
            glassesScore = glasses,
            beardScore = beard
        )
    }

    /**
     * لون البشرة: شبكة داخل صندوق الوجه، تُقبل فقط النقاط داخل كونتور الوجه،
     * باستثناء شريط العينين وشريط الفم. تُفضَّل البكسلات الدافئة؛ وإن قلّت
     * يُؤخذ متوسط كل النقاط المقبولة.
     */
    fun sampleSkin(pixels: IntArray, width: Int, height: Int, geometry: FaceGeometry): Int? {
        val boxW = geometry.boxWidth
        val boxH = geometry.boxHeight
        if (boxW <= 0f || boxH <= 0f || geometry.faceContour.size < 3) return null
        val (leftEye, rightEye) = FaceProfile.eyeCenters(geometry)
        val eyeY = (leftEye.y + rightEye.y) / 2f
        val eyeRadius = (geometry.boxWidth * FaceProfile.DEFAULT.eyeSize).coerceAtLeast(6f)
        val (mouthTop, mouthBottom) = FaceProfile.mouthBand(geometry)
        val step = maxOf(3, (boxW / 28).toInt())
        val left = geometry.boxLeft.toInt()
        val top = geometry.boxTop.toInt()
        val right = (geometry.boxLeft + boxW).toInt()
        val bottom = (geometry.boxTop + boxH).toInt()
        var warmCount = 0
        var wr = 0L
        var wg = 0L
        var wb = 0L
        var allCount = 0
        var ar = 0L
        var ag = 0L
        var ab = 0L
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val fx = x + step / 2f
                val fy = y + step / 2f
                val inEyes = fy in (eyeY - eyeRadius * 1.8f)..(eyeY + eyeRadius * 1.8f)
                val inMouth = fy in (mouthTop - 2f)..(mouthBottom + 2f)
                if (!inEyes && !inMouth && isInsidePolygon(fx, fy, geometry.faceContour)) {
                    val pixel = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
                    if ((pixel ushr 24) >= 128) {
                        ar += (pixel shr 16) and 0xFF
                        ag += (pixel shr 8) and 0xFF
                        ab += pixel and 0xFF
                        allCount++
                        if (isWarmSkinPixel(pixel)) {
                            wr += (pixel shr 16) and 0xFF
                            wg += (pixel shr 8) and 0xFF
                            wb += pixel and 0xFF
                            warmCount++
                        }
                    }
                }
                x += step
            }
            y += step
        }
        if (warmCount >= 8) return rgb((wr / warmCount).toInt(), (wg / warmCount).toInt(), (wb / warmCount).toInt())
        if (allCount >= 8) return rgb((ar / allCount).toInt(), (ag / allCount).toInt(), (ab / allCount).toInt())
        return null
    }

    /**
     * لون الشعر وطوله: الشريط فوق الوجه حتى نصف ارتفاعه، وجانبا الوجه للشعر
     * الطويل. اللون متوسط البكسلات الداكنة في الشريط؛ والطول امتداد الظلمة
     * لأعلى (0 = لا شعر ظاهر فوق الجبهة، 1 = يغطي الشريط كاملًا أو الجانبين).
     */
    fun sampleHair(pixels: IntArray, width: Int, height: Int, geometry: FaceGeometry): Pair<Int?, Float> {
        val boxW = geometry.boxWidth
        val boxH = geometry.boxHeight
        if (boxW <= 0f || boxH <= 0f) return null to 0f
        val left = geometry.boxLeft.toInt()
        val right = (geometry.boxLeft + boxW).toInt()
        val faceTop = geometry.boxTop.toInt()
        val stripTop = (geometry.boxTop - 0.5f * boxH).toInt()
        val hairColor = averageColor(
            pixels, width, height, left, stripTop, right, faceTop
        ) { luminance(it) < HAIR_DARK_LUMINANCE }
        // الطول: كم شريطًا أفقيًا (من الجبهة لأعلى) فيه ظلمة كافية.
        val bands = 8
        var filled = 0
        for (band in 0 until bands) {
            val bandTop = faceTop - (band + 1) * (faceTop - stripTop) / bands
            val bandBottom = faceTop - band * (faceTop - stripTop) / bands
            if (darkFraction(pixels, width, height, left, bandTop, right, bandBottom, HAIR_DARK_LUMINANCE) > 0.15f) {
                filled++
            } else {
                break
            }
        }
        var length = filled.toFloat() / bands
        // الشعر الطويل يظهر على جانبي الوجه حتى لو لم يغطِّ الشريط العلوي.
        val sideTop = (geometry.boxTop + 0.3f * boxH).toInt()
        val sideBottom = (geometry.boxTop + 0.75f * boxH).toInt()
        val sideWidth = (0.18f * boxW).toInt().coerceAtLeast(2)
        val leftSide = darkFraction(pixels, width, height, left - sideWidth, sideTop, left, sideBottom, HAIR_DARK_LUMINANCE)
        val rightSide = darkFraction(pixels, width, height, right, sideTop, right + sideWidth, sideBottom, HAIR_DARK_LUMINANCE)
        if (maxOf(leftSide, rightSide) > 0.4f) length = maxOf(length, 0.75f)
        return hairColor to length.coerceIn(0f, 1f)
    }

    /**
     * نتيجة النظارة: متوسط كثافة الظلمة في ثلاثة أشرطة رفيعة حول كل عين
     * (جانبا العين حيث يمتد الإطار نحو الأذن، وفوقها حيث العارضة العليا).
     */
    fun glassesScore(pixels: IntArray, width: Int, height: Int, geometry: FaceGeometry): Float {
        val (leftEye, rightEye) = FaceProfile.eyeCenters(geometry)
        val radius = (geometry.boxWidth * FaceProfile.DEFAULT.eyeSize).coerceAtLeast(6f)
        var sum = 0f
        var count = 0
        for (center in listOf(leftEye, rightEye)) {
            val cx = center.x.toInt()
            val cy = center.y.toInt()
            val r = radius.toInt()
            val templeLeft = darkFraction(pixels, width, height, cx - (2.2f * r).toInt(), cy - r, cx - r, cy + r, GLASSES_DARK_LUMINANCE)
            val templeRight = darkFraction(pixels, width, height, cx + r, cy - r, cx + (2.2f * r).toInt(), cy + r, GLASSES_DARK_LUMINANCE)
            val topBar = darkFraction(pixels, width, height, cx - (1.5f * r).toInt(), cy - (2.2f * r).toInt(), cx + (1.5f * r).toInt(), cy - r, GLASSES_DARK_LUMINANCE)
            sum += templeLeft + templeRight + topBar
            count += 3
        }
        if (count == 0) return 0f
        return (sum / count).coerceIn(0f, 1f)
    }

    /** نتيجة اللحية: كثافة الظلمة في الثلث السفلي من الوجه تحت الفم وداخل الكونتور. */
    fun beardScore(pixels: IntArray, width: Int, height: Int, geometry: FaceGeometry): Float {
        val boxW = geometry.boxWidth
        val boxH = geometry.boxHeight
        if (boxW <= 0f || boxH <= 0f) return 0f
        val (_, mouthBottom) = FaceProfile.mouthBand(geometry)
        val left = (geometry.boxLeft + 0.15f * boxW).toInt()
        val right = (geometry.boxLeft + 0.85f * boxW).toInt()
        val top = mouthBottom.toInt()
        val bottom = (geometry.boxTop + boxH).toInt()
        return darkFraction(pixels, width, height, left, top, right, bottom, BEARD_DARK_LUMINANCE)
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        0xFF000000.toInt() or ((r.coerceIn(0, 255) shl 16)) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}
