package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    }
}
