package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part 5: offline cartoon avatar is deterministic, styleable and photo-seeded. */
class Part5AvatarTest {

    private val warmPixels = IntArray(64) { 0xFFD9A27A.toInt() }
    private val coolPixels = IntArray(64) { 0xFF6F8FC0.toInt() }

    @Test fun fourOnDeviceStylesAreOffered() {
        assertEquals(4, CartoonAvatar.STYLES.size)
        assertEquals(4, CartoonAvatar.STYLES.toSet().size)
    }

    @Test fun eachStyleHasItsOwnPrivateOutputFile() {
        val names = (0..3).map { CartoonAvatar.fileName(it) }
        assertEquals(4, names.toSet().size)
        assertTrue(names.all { it.startsWith("student_companion_cartoon_") && it.endsWith(".png") })
        assertEquals(names[3], CartoonAvatar.fileName(99))
    }

    @Test fun seedIsStableForTheSamePhotoAndDifferentForAnother() {
        assertEquals(CartoonAvatar.seedFor(warmPixels), CartoonAvatar.seedFor(warmPixels.copyOf()))
        assertNotEquals(CartoonAvatar.seedFor(warmPixels), CartoonAvatar.seedFor(coolPixels))
    }

    @Test fun paletteFollowsThePhotoSeed() {
        val warm = CartoonAvatar.paletteFrom(warmPixels, 0)
        val cool = CartoonAvatar.paletteFrom(coolPixels, 0)
        assertTrue(warm.skin != cool.skin)
        assertTrue(warm.background != cool.background)
        assertTrue(warm.hair != 0)
        assertTrue(warm.accent != 0)
    }

    @Test fun paletteAndSeedHandleBlankOrTransparentImages() {
        val blank = IntArray(64) { 0 }
        val palette = CartoonAvatar.paletteFrom(blank, 3)
        assertTrue(palette.skin != 0)
        assertEquals(palette.skin, CartoonAvatar.paletteFrom(blank, 3).skin)
        assertEquals(CartoonAvatar.seedFor(blank), CartoonAvatar.seedFor(blank.copyOf()))
    }

    @Test fun avatarSelectionKeepsOriginalSeparateFromGeneratedImage() {
        val image = AvatarImage("/files/cartoon.png", "/files/original.jpg", 2)
        assertEquals("/files/original.jpg", avatarSource(image))
        assertEquals("/files/legacy.jpg", avatarSource(AvatarImage("/files/legacy.jpg", "", 0)))
        assertTrue(AvatarImage.EMPTY.path.isEmpty())
    }

    @Test fun preferencesDefaultToNoPhotoAndFirstStyle() {
        val prefs = FocusPreferences()
        assertTrue(prefs.avatarPath.isEmpty())
        assertTrue(prefs.avatarSourcePath.isEmpty())
        assertEquals(0, prefs.avatarStyle)
        assertTrue(prefs.avatarFaceProfileJson.isEmpty())
        assertNull(prefs.avatarFaceProfile)
    }

    // --- ML Kit face profile (bundled model): pure mapping + JSON + sampler ---

    private fun testGeometry() = FaceGeometry(
        boxLeft = 0f, boxTop = 0f, boxWidth = 100f, boxHeight = 120f,
        faceContour = listOf(FPoint(10f, 10f), FPoint(90f, 10f), FPoint(90f, 110f), FPoint(10f, 110f)),
        leftEye = listOf(FPoint(30f, 48f), FPoint(40f, 48f), FPoint(35f, 52f)),
        rightEye = listOf(FPoint(60f, 48f), FPoint(70f, 48f), FPoint(65f, 52f)),
        // الحاجبان بوضع ML Kit الحقيقي: LEFT_BROW هي حاجب الشخص الأيسر
        // ويظهر على يمين الصورة — فطرفه الخارجي (الأبعد عن مركز الوجه)
        // أخفض هنا، والميل المتوقع موجب (atan2(4, 20) ≈ 11.31°).
        leftBrow = listOf(FPoint(55f, 30f), FPoint(65f, 28f), FPoint(75f, 34f)),
        rightBrow = listOf(FPoint(25f, 34f), FPoint(35f, 28f), FPoint(45f, 30f)),
        upperLip = listOf(FPoint(40f, 85f), FPoint(60f, 85f)),
        lowerLip = listOf(FPoint(40f, 92f), FPoint(60f, 92f)),
        eulerZ = 10f,
        smilingProbability = 0.8f
    )

    private fun testSamples() = FaceSamples(
        skinColor = 0xFFD9A27A.toInt(),
        hairColor = 0xFF2A1A10.toInt(),
        hairLength = 0.6f,
        glassesScore = 0.1f,
        beardScore = 0.1f
    )

    @Test fun faceProfileGeometryMapsEyesAndSmile() {
        val profile = FaceProfile.fromGeometry(testGeometry(), testSamples())
        assertEquals(100f / 120f, profile.faceAspect, 0.001f)
        assertEquals(0.30f, profile.eyeSpacing, 0.001f)
        assertEquals(0.10f, profile.eyeSize, 0.001f)
        assertEquals(49.33f / 120f, profile.eyeHeight, 0.01f)
        assertEquals(11.31f, profile.browAngle, 0.01f)
        assertEquals(0.6f * 0.8f + 0.4f * (7f / 120f / 0.22f), profile.smile, 0.01f)
        assertEquals(-10f, profile.headTilt, 0.001f)
        assertEquals(0xFFD9A27A.toInt(), profile.skinColor)
        assertEquals(0xFF2A1A10.toInt(), profile.hairColor)
        assertEquals(0.6f, profile.hairLength, 0.001f)
        assertEquals(false, profile.hasGlasses)
        assertEquals(false, profile.hasBeard)
    }

    @Test fun faceProfileGeometryHandlesMissingContours() {
        val geometry = testGeometry().copy(
            leftEye = emptyList(), rightEye = emptyList(),
            leftBrow = emptyList(), rightBrow = emptyList(),
            upperLip = emptyList(), lowerLip = emptyList(),
            smilingProbability = null, eulerZ = 45f
        )
        val profile = FaceProfile.fromGeometry(geometry, testSamples())
        assertEquals(0.28f, profile.eyeSpacing, 0.01f)
        assertEquals(0.05f, profile.eyeSize, 0.001f)
        assertEquals(0.42f, profile.eyeHeight, 0.01f)
        assertEquals(0f, profile.browAngle, 0.001f)
        assertEquals(0.45f, profile.smile, 0.001f)
        assertEquals(-30f, profile.headTilt, 0.001f)
    }

    @Test fun faceProfileJsonRoundTrips() {
        val original = FaceProfile(
            faceAspect = 0.9f, eyeSpacing = 0.4f, eyeSize = 0.12f, eyeHeight = 0.4f,
            browAngle = -5f, smile = 0.7f, headTilt = 8f,
            skinColor = 0xFFC08A5A.toInt(), hairColor = 0xFF1A1210.toInt(), hairLength = 0.8f,
            hasGlasses = true, hasBeard = false,
            glassesOverride = -1, beardOverride = 1, hairLengthAdjust = 0.1f, smileAdjust = -0.2f
        )
        val parsed = FaceProfile.fromJson(original.toJson())
            ?: throw AssertionError("face profile JSON round-trip failed")
        assertEquals(original.faceAspect, parsed.faceAspect, 0.001f)
        assertEquals(original.eyeSpacing, parsed.eyeSpacing, 0.001f)
        assertEquals(original.eyeSize, parsed.eyeSize, 0.001f)
        assertEquals(original.eyeHeight, parsed.eyeHeight, 0.001f)
        assertEquals(original.browAngle, parsed.browAngle, 0.001f)
        assertEquals(original.smile, parsed.smile, 0.001f)
        assertEquals(original.headTilt, parsed.headTilt, 0.001f)
        assertEquals(original.skinColor, parsed.skinColor)
        assertEquals(original.hairColor, parsed.hairColor)
        assertEquals(original.hairLength, parsed.hairLength, 0.001f)
        assertEquals(original.hasGlasses, parsed.hasGlasses)
        assertEquals(original.hasBeard, parsed.hasBeard)
        assertEquals(original.glassesOverride, parsed.glassesOverride)
        assertEquals(original.beardOverride, parsed.beardOverride)
        assertEquals(original.hairLengthAdjust, parsed.hairLengthAdjust, 0.001f)
        assertEquals(original.smileAdjust, parsed.smileAdjust, 0.001f)
        assertEquals(original.toJson(), parsed.toJson())
    }

    @Test fun faceProfileJsonRejectsGarbage() {
        assertNull(FaceProfile.fromJson(""))
        assertNull(FaceProfile.fromJson("hello"))
        assertNull(FaceProfile.fromJson("{}"))
        assertNull(FaceProfile.fromJson("{\"v\":2,\"fa\":0.9}"))
        assertNull(FaceProfile.fromJson("{\"v\":1,\"fa\":0.9}"))
    }

    @Test fun faceCorrectionOverridesWinAndClamp() {
        val noGlasses = FaceProfile.DEFAULT.copy(hasGlasses = false, glassesOverride = 1)
        assertTrue(noGlasses.effectiveGlasses())
        val withGlasses = FaceProfile.DEFAULT.copy(hasGlasses = true, glassesOverride = -1)
        assertTrue(!withGlasses.effectiveGlasses())
        assertTrue(FaceProfile.DEFAULT.copy(hasBeard = true, beardOverride = 0).effectiveBeard())
        assertTrue(!FaceProfile.DEFAULT.copy(hasBeard = false, beardOverride = 5).effectiveBeard())
        assertTrue(FaceProfile.DEFAULT.copy(hasBeard = true, beardOverride = -2).effectiveBeard())
        assertEquals(1f, FaceProfile.DEFAULT.copy(hairLength = 0.9f, hairLengthAdjust = 0.5f).effectiveHairLength(), 0.001f)
        assertEquals(0f, FaceProfile.DEFAULT.copy(smile = 0.1f, smileAdjust = -0.5f).effectiveSmile(), 0.001f)
    }

    @Test fun faceSamplerPointInPolygon() {
        val square = listOf(FPoint(0f, 0f), FPoint(10f, 0f), FPoint(10f, 10f), FPoint(0f, 10f))
        assertTrue(FaceSampler.isInsidePolygon(5f, 5f, square))
        assertTrue(!FaceSampler.isInsidePolygon(15f, 5f, square))
        assertTrue(!FaceSampler.isInsidePolygon(5f, -1f, square))
        assertTrue(!FaceSampler.isInsidePolygon(5f, 5f, emptyList()))
        // سلوك الحافة في خوارزمية الشعاع: نقطة على الضلع الأيسر داخل،
        // والرأس (10,10) خارج — مثبَّت هنا حتى لا يتغير بصمت.
        assertTrue(FaceSampler.isInsidePolygon(0f, 5f, square))
        assertTrue(!FaceSampler.isInsidePolygon(10f, 10f, square))
    }

    @Test fun faceSamplerFindsSkinInsideContourOnly() {
        val pixels = IntArray(40 * 40) { i ->
            val x = i % 40
            val y = i / 40
            if (x in 10..33 && y in 0..15) 0xFFD9A27A.toInt() else 0xFF6F8FC0.toInt()
        }
        val geometry = testGeometry().copy(
            boxLeft = 0f, boxTop = 0f, boxWidth = 40f, boxHeight = 40f,
            faceContour = listOf(FPoint(4f, 4f), FPoint(36f, 4f), FPoint(36f, 36f), FPoint(4f, 36f)),
            leftEye = emptyList(), rightEye = emptyList(),
            upperLip = emptyList(), lowerLip = emptyList()
        )
        assertEquals(0xFFD9A27A.toInt(), FaceSampler.sampleSkin(pixels, 40, 40, geometry))
    }

    @Test fun faceSamplerSkinIgnoresWarmBackgroundOutsideContour() {
        // الخلفية دافئة أيضًا بلون مختلف — فلو أخذ السامبلر بكسلات خارج
        // الكونتور لاختلط المتوسط ولما ساوى لون الداخل تمامًا.
        val pixels = IntArray(40 * 40) { i ->
            val x = i % 40
            val y = i / 40
            if (x in 4..36 && y in 4..36) 0xFFD9A27A.toInt() else 0xFFC08A5A.toInt()
        }
        val geometry = testGeometry().copy(
            boxLeft = 0f, boxTop = 0f, boxWidth = 40f, boxHeight = 40f,
            faceContour = listOf(FPoint(4f, 4f), FPoint(36f, 4f), FPoint(36f, 36f), FPoint(4f, 36f)),
            leftEye = emptyList(), rightEye = emptyList(),
            upperLip = emptyList(), lowerLip = emptyList()
        )
        assertEquals(0xFFD9A27A.toInt(), FaceSampler.sampleSkin(pixels, 40, 40, geometry))
    }

    @Test fun faceSamplerSkinExcludesEyeBand() {
        // بكسلات دافئة في شريط العينين فقط؛ المقبول منها خارج الشريط بارد،
        // فالنتيجة يجب أن تساوي اللون البارد تمامًا لا الدافئ.
        val pixels = IntArray(40 * 40) { i ->
            val x = i % 40
            val y = i / 40
            if (y in 8..26) 0xFFD9A27A.toInt() else 0xFF6F8FC0.toInt()
        }
        val geometry = testGeometry().copy(
            boxLeft = 0f, boxTop = 0f, boxWidth = 40f, boxHeight = 40f,
            faceContour = listOf(FPoint(4f, 4f), FPoint(36f, 4f), FPoint(36f, 36f), FPoint(4f, 36f)),
            leftEye = emptyList(), rightEye = emptyList(),
            upperLip = emptyList(), lowerLip = emptyList()
        )
        assertEquals(0xFF6F8FC0.toInt(), FaceSampler.sampleSkin(pixels, 40, 40, geometry))
    }

    // هندسة متسقة مع الصورة لاختبارات النتائج: صورة 40×40 وصندوق وجه
    // 20×20 داخلها، بكونتور داخل الصندوق وعينين وفم معروفين.
    private fun scoresGeometry() = FaceGeometry(
        boxLeft = 10f, boxTop = 10f, boxWidth = 20f, boxHeight = 20f,
        faceContour = listOf(FPoint(12f, 12f), FPoint(28f, 12f), FPoint(28f, 28f), FPoint(12f, 28f)),
        leftEye = listOf(FPoint(15f, 18f), FPoint(19f, 18f)),
        rightEye = listOf(FPoint(21f, 18f), FPoint(25f, 18f)),
        leftBrow = listOf(FPoint(15f, 14f), FPoint(19f, 14f)),
        rightBrow = listOf(FPoint(21f, 14f), FPoint(25f, 14f)),
        upperLip = listOf(FPoint(16f, 24f), FPoint(24f, 24f)),
        lowerLip = listOf(FPoint(16f, 26f), FPoint(24f, 26f)),
        eulerZ = 0f,
        smilingProbability = null
    )

    private fun warmBasePixels() = IntArray(40 * 40) { 0xFFD9A27A.toInt() }

    @Test fun faceSamplerGlassesScoreDetectsDarkTopBar() {
        // شريط داكن فوق العينين (حيث العارضة العليا للنظارة): النتيجة فوق
        // العتبة 0.25، وكل النتائج في نطاقها.
        val pixels = warmBasePixels()
        for (y in 3..8) for (x in 8..31) pixels[y * 40 + x] = 0xFF202020.toInt()
        val samples = FaceSampler.sampleAll(pixels, 40, 40, scoresGeometry(), 0xFFD9A27A.toInt(), 0xFF2A1A10.toInt())
        assertTrue(samples.glassesScore > 0.25f)
        assertTrue(samples.glassesScore in 0f..1f)
        assertTrue(samples.beardScore in 0f..1f)
        assertTrue(samples.hairLength in 0f..1f)
    }

    @Test fun faceSamplerBeardScoreDetectsDarkLowerThird() {
        // ثلث سفلي داكن تحت الفم وداخل الكونتور: النتيجة فوق العتبة 0.30.
        val pixels = warmBasePixels()
        for (y in 26..29) for (x in 13..26) pixels[y * 40 + x] = 0xFF202020.toInt()
        val samples = FaceSampler.sampleAll(pixels, 40, 40, scoresGeometry(), 0xFFD9A27A.toInt(), 0xFF2A1A10.toInt())
        assertTrue(samples.beardScore > 0.30f)
        assertTrue(samples.glassesScore in 0f..1f)
        assertTrue(samples.beardScore in 0f..1f)
        assertTrue(samples.hairLength in 0f..1f)
    }

    @Test fun faceSamplerSkinFallsBackWithoutWarmPixels() {
        // بلا بكسلات دافئة داخل الكونتور: لون البشرة يساوي الاحتياطي تمامًا.
        val pixels = IntArray(40 * 40) { 0xFF6F8FC0.toInt() }
        val samples = FaceSampler.sampleAll(pixels, 40, 40, scoresGeometry(), 0xFFD9A27A.toInt(), 0xFF2A1A10.toInt())
        assertEquals(0xFFD9A27A.toInt(), samples.skinColor)
    }

    @Test fun faceSamplerHairLengthDetectsTopStrip() {
        // شعر يغطي الشريط العلوي فوق الوجه: الطول أكبر من صفر وفي نطاقه.
        val pixels = warmBasePixels()
        for (y in 0..9) for (x in 10..29) pixels[y * 40 + x] = 0xFF202020.toInt()
        val samples = FaceSampler.sampleAll(pixels, 40, 40, scoresGeometry(), 0xFFD9A27A.toInt(), 0xFF2A1A10.toInt())
        assertTrue(samples.hairLength > 0f)
        assertTrue(samples.hairLength in 0f..1f)
        assertTrue(samples.glassesScore in 0f..1f)
        assertTrue(samples.beardScore in 0f..1f)
    }

    @Test fun faceProfileJsonAcceptsSpacesAndRejectsStringNumbers() {
        val original = FaceProfile.DEFAULT.copy(headTilt = 8f)
        val spaced = original.toJson().replace(":", ": ").replace(",", ", ")
        assertEquals(original.toJson(), FaceProfile.fromJson(spaced)?.toJson())
        val stringNumber = original.toJson().replace("\"ht\":8", "\"ht\":\"8\"")
        assertNull(FaceProfile.fromJson(stringNumber))
    }
}
