package com.focusstudy.poc

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * زوم قارئ المصحف + بناء نص الصفحة بتدفق متصل.
 *
 * يحرس: خريطة المستويات الخمسة، التقييد، الحفظ/الاستعادة عبر إعادة
 * التشغيل، وأن بناء الصفحة لا يُسقط آية ولا يضيفها في أي مستوى زوم، وأن
 * عنوان السورة والبسملة يظهران داخل التدفق حيث يبدآن.
 *
 * Runs under Robolectric (no device needed) with the runtime pinned to SDK 35.
 * Run with `./gradlew testDebugUnitTest` on a machine with the Android SDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuranZoomTest {

    private lateinit var context: Context
    private lateinit var index: QuranIndex

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        index = QuranData.loadIndex(context) ?: error("تعذّر تحميل فهرس المصحف")
    }

    @Test
    fun `zoom levels map to the documented scales`() {
        assertEquals(0.75f, QuranZoom.scaleFor(0))
        assertEquals(0.88f, QuranZoom.scaleFor(1))
        assertEquals(1f, QuranZoom.scaleFor(2))
        assertEquals(1.15f, QuranZoom.scaleFor(3))
        assertEquals(1.30f, QuranZoom.scaleFor(4))
    }

    @Test
    fun `zoom level is clamped to 0-4`() {
        assertEquals(0, QuranZoom.clamp(-1))
        assertEquals(0, QuranZoom.clamp(-100))
        assertEquals(4, QuranZoom.clamp(5))
        assertEquals(4, QuranZoom.clamp(100))
        assertEquals(2, QuranZoom.clamp(2))
    }

    @Test
    fun `default level keeps the historical sizes`() {
        assertEquals(21f, QuranZoom.ayahFontSize(QuranZoom.DEFAULT_LEVEL).value)
        assertEquals(40f, QuranZoom.ayahLineHeight(QuranZoom.DEFAULT_LEVEL).value)
        assertEquals(22f, QuranZoom.basmalahFontSize(QuranZoom.DEFAULT_LEVEL).value)
        assertEquals(30f, QuranZoom.markerSize(QuranZoom.DEFAULT_LEVEL).value)
    }

    @Test
    fun `line height stays proportional to font size at every level`() {
        for (level in QuranZoom.MIN_LEVEL..QuranZoom.MAX_LEVEL) {
            val ratio = QuranZoom.ayahLineHeight(level).value / QuranZoom.ayahFontSize(level).value
            assertEquals("level $level", 40f / 21f, ratio, 0.001f)
        }
    }

    @Test
    fun `zoom level survives a reload`() {
        QuranZoom.saveZoomLevel(context, 4)
        assertEquals(4, QuranZoom.loadZoomLevel(context))
        QuranZoom.saveZoomLevel(context, 0)
        assertEquals(0, QuranZoom.loadZoomLevel(context))
        QuranZoom.saveZoomLevel(context, 2)
        assertEquals(2, QuranZoom.loadZoomLevel(context))
    }

    @Test
    fun `out-of-range saved levels are clamped on load`() {
        QuranZoom.saveZoomLevel(context, 99)
        assertEquals(4, QuranZoom.loadZoomLevel(context))
        QuranZoom.saveZoomLevel(context, -5)
        assertEquals(0, QuranZoom.loadZoomLevel(context))
        QuranZoom.saveZoomLevel(context, 2)
    }

    @Test
    fun `page content keeps every ayah exactly once at every zoom level`() {
        for (page in listOf(1, 2, 48, 100, 597, 604)) {
            val ayahs = index.ayahsForPage(page)
            assertTrue("page $page must not be empty", ayahs.isNotEmpty())
            for (level in QuranZoom.MIN_LEVEL..QuranZoom.MAX_LEVEL) {
                val content = buildQuranPageContent(index, ayahs, level)
                assertEquals("page $page level $level", ayahs.size, content.markers.size)
                for (ayah in ayahs) {
                    assertTrue(
                        "page $page level $level missing marker ${ayah.surah}:${ayah.ayah}",
                        content.markers.containsKey("m${ayah.surah}_${ayah.ayah}")
                    )
                    assertEquals(
                        "page $page level $level marker value",
                        ayah.ayah,
                        content.markers["m${ayah.surah}_${ayah.ayah}"]
                    )
                }
            }
        }
    }

    @Test
    fun `page 2 separates the basmalah of Al-Baqarah inside the flow`() {
        val ayahs = index.ayahsForPage(2)
        val content = buildQuranPageContent(index, ayahs, QuranZoom.DEFAULT_LEVEL)
        val basmalah = index.ayahs.first { it.surah == 1 && it.ayah == 1 }.text
        assertTrue("basmalah must appear in page 2 text", content.text.contains(basmalah))
        assertTrue("surah heading must appear in page 2 text", content.text.contains("سُورَةُ البقرة"))
    }

    @Test
    fun `page 597 shows At-Tin heading and its basmalah after the audit fix`() {
        val ayahs = index.ayahsForPage(597)
        val content = buildQuranPageContent(index, ayahs, QuranZoom.DEFAULT_LEVEL)
        assertTrue("At-Tin heading must appear", content.text.contains("سُورَةُ التين"))
        val basmalah = index.ayahs.first { it.surah == 1 && it.ayah == 1 }.text
        assertTrue("basmalah must appear in page 597 text", content.text.contains(basmalah))
    }

    @Test
    fun `page 48 keeps the three-digit marker of ayah 282`() {
        val ayahs = index.ayahsForPage(48)
        assertTrue("page 48 must contain 2:282", ayahs.any { it.surah == 2 && it.ayah == 282 })
        val content = buildQuranPageContent(index, ayahs, QuranZoom.DEFAULT_LEVEL)
        assertEquals(282, content.markers["m2_282"])
        // الرقم من ثلاث خانات يصغر داخل الدائرة في كل مستوى زوم.
        for (level in QuranZoom.MIN_LEVEL..QuranZoom.MAX_LEVEL) {
            val size = QuranZoom.markerNumberSize(3, QuranZoom.scaleFor(level))
            assertTrue("level $level", size.value < QuranZoom.markerSize(level).value)
        }
    }

    @Test
    fun `zoom never changes page membership across all 604 pages`() {
        for (page in 1..604) {
            val ayahs = index.ayahsForPage(page)
            val min = buildQuranPageContent(index, ayahs, QuranZoom.MIN_LEVEL)
            val max = buildQuranPageContent(index, ayahs, QuranZoom.MAX_LEVEL)
            assertEquals("page $page", ayahs.size, min.markers.size)
            assertEquals("page $page", min.markers, max.markers)
        }
    }
}
