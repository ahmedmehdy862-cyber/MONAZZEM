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
        leftBrow = listOf(FPoint(25f, 30f), FPoint(35f, 28f), FPoint(45f, 30f)),
        rightBrow = listOf(FPoint(55f, 30f), FPoint(65f, 28f), FPoint(75f, 30f)),
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
        assertEquals(0f, profile.browAngle, 0.001f)
        assertEquals(0.6f * 0.8f + 0.4f * (7f / 120f / 0.22f), profile.smile, 0.01f)
        assertEquals(10f, profile.headTilt, 0.001f)
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
        assertEquals(0.42f, profile.eyeHeight, 0.01f)
        assertEquals(0.45f, profile.smile, 0.001f)
        assertEquals(30f, profile.headTilt, 0.001f)
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
        assertEquals(1f, FaceProfile.DEFAULT.copy(hairLength = 0.9f, hairLengthAdjust = 0.5f).effectiveHairLength(), 0.001f)
        assertEquals(0f, FaceProfile.DEFAULT.copy(smile = 0.1f, smileAdjust = -0.5f).effectiveSmile(), 0.001f)
    }

    @Test fun faceSamplerPointInPolygon() {
        val square = listOf(FPoint(0f, 0f), FPoint(10f, 0f), FPoint(10f, 10f), FPoint(0f, 10f))
        assertTrue(FaceSampler.isInsidePolygon(5f, 5f, square))
        assertTrue(!FaceSampler.isInsidePolygon(15f, 5f, square))
        assertTrue(!FaceSampler.isInsidePolygon(5f, -1f, square))
        assertTrue(!FaceSampler.isInsidePolygon(5f, 5f, emptyList()))
    }

    @Test fun faceSamplerFindsSkinInsideContourOnly() {
        val pixels = IntArray(40 * 40) { i ->
            val x = i % 40
            val y = i / 40
            if (x in 10..30 && y in 0..15) 0xFFD9A27A.toInt() else 0xFF6F8FC0.toInt()
        }
        val geometry = testGeometry().copy(
            boxLeft = 0f, boxTop = 0f, boxWidth = 40f, boxHeight = 40f,
            faceContour = listOf(FPoint(4f, 4f), FPoint(36f, 4f), FPoint(36f, 36f), FPoint(4f, 36f)),
            leftEye = emptyList(), rightEye = emptyList(),
            upperLip = emptyList(), lowerLip = emptyList()
        )
        assertEquals(0xFFD9A27A.toInt(), FaceSampler.sampleSkin(pixels, 40, 40, geometry))
    }

    @Test fun faceSamplerScoresAreBounded() {
        val pixels = IntArray(20 * 20) { 0xFF202020.toInt() }
        val geometry = testGeometry().copy(boxWidth = 20f, boxHeight = 20f)
        val samples = FaceSampler.sampleAll(pixels, 20, 20, geometry, 0xFFD9A27A.toInt(), 0xFF2A1A10.toInt())
        assertTrue(samples.glassesScore in 0f..1f)
        assertTrue(samples.beardScore in 0f..1f)
        assertTrue(samples.hairLength in 0f..1f)
    }
}
