package com.focusstudy.poc

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * تدقيق بيانات المصحف المضمّنة (نص تنزيل العثماني v1.1 + خريطة صفحات مصحف
 * المدينة في pages.json) — حارس ضد أي خطأ في نص القرآن أو ترقيمه.
 *
 * كُتب بعد تدقيق 2026-11-26 الذي وجد خطأين حقيقيين: شدة زائدة في بسملة
 * الآية الأولى من سورتي التين (95) والقدر (97) — «بِّسْمِ» بدل «بِسْمِ» —
 * كانت تمنع فصل البسملة سطرًا مستقلًا فيهما. اختبار البسملة الموحدة أدناه
 * يفشل قبل الإصلاح وينجح بعده، فلا يعود الخطأ أبدًا بصمت.
 *
 * الجداول المرجعية هي المعايير الثابتة لمصحف المدينة (عدد آيات كل سورة،
 * صفحة بداية كل سورة، صفحة بداية كل جزء) — وتحققت منها مقابل AlQuran Cloud
 * وQuran.com في التدقيق نفسه.
 *
 * Runs under Robolectric (no device needed) with the runtime pinned to SDK 35.
 * Run with `./gradlew testDebugUnitTest` on a machine with the Android SDK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuranDataTest {

    private lateinit var context: Context
    private lateinit var index: QuranIndex

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        index = QuranData.loadIndex(context) ?: error("تعذّر تحميل فهرس المصحف")
    }

    @Test
    fun `loads the complete quran`() {
        assertEquals(6236, index.ayahs.size)
        assertEquals(604, index.pages.size)
        assertEquals(114, index.surahs.size)
        assertEquals(30, index.juzStartPage.size)
    }

    @Test
    fun `per-surah ayah counts match the standard`() {
        for (surah in 1..114) {
            val have = index.ayahs.count { it.surah == surah }
            assertEquals("surah $surah", EXPECTED_AYAH_COUNTS[surah - 1], have)
            assertEquals("surah $surah in index", EXPECTED_AYAH_COUNTS[surah - 1], index.surahs[surah - 1].ayahs)
        }
    }

    @Test
    fun `basmalah is identical at the head of every surah except At-Tawbah`() {
        val basmalah = index.ayahs.first { it.surah == 1 && it.ayah == 1 }.text
        for (surah in 2..114) {
            val first = index.ayahs.first { it.surah == surah && it.ayah == 1 }
            if (surah == 9) {
                assertFalse("surah 9 must not start with the basmalah", first.text.startsWith(basmalah))
            } else {
                assertTrue(
                    "surah $surah ayah 1 must start with the exact basmalah",
                    first.text.startsWith(basmalah)
                )
            }
        }
    }

    @Test
    fun `splitBasmalah separates the basmalah in every surah except 1 and 9`() {
        for (ayah in index.ayahs.filter { it.ayah == 1 }) {
            val (basmalah, rest) = QuranData.splitBasmalah(index, ayah)
            if (ayah.surah == 1 || ayah.surah == 9) {
                assertNull("surah ${ayah.surah}", basmalah)
            } else {
                assertNotNull("surah ${ayah.surah}", basmalah)
                assertTrue("surah ${ayah.surah} rest must not be blank", rest.isNotBlank())
            }
        }
    }

    @Test
    fun `pages cover every ayah exactly once in order`() {
        val all = (1..604).flatMap { index.ayahsForPage(it) }
        assertEquals(index.ayahs, all)
    }

    @Test
    fun `surah start pages match the Madinah mushaf`() {
        for (surah in 1..114) {
            assertEquals("surah $surah", EXPECTED_SURAH_START_PAGE[surah - 1], index.pageForSurah(surah))
            assertEquals("surah $surah in index", EXPECTED_SURAH_START_PAGE[surah - 1], index.surahs[surah - 1].page)
        }
    }

    @Test
    fun `juz start pages match the Madinah mushaf`() {
        for (juz in 1..30) {
            assertEquals("juz $juz", EXPECTED_JUZ_START_PAGE[juz - 1], index.pageForJuz(juz))
        }
        assertEquals(1, index.juzForPage(1))
        assertEquals(30, index.juzForPage(597))
        assertEquals(30, index.juzForPage(604))
    }

    @Test
    fun `surah names are the reference spellings`() {
        assertEquals("الفاتحة", index.surahs[0].name)
        assertEquals("البقرة", index.surahs[1].name)
        // «سبإ» و«النبإ» بهمزة على الياء — هكذا في مرجع الأسماء (AlQuran Cloud /
        // بيانات تنزيل)، وليستا «سبأ»/«النبأ».
        assertEquals("سبإ", index.surahs[33].name)
        assertEquals("النبإ", index.surahs[77].name)
        assertEquals("الناس", index.surahs[113].name)
    }

    @Test
    fun `arabic digits use Eastern Arabic numerals`() {
        assertEquals("٠", QuranData.arabicDigits(0))
        assertEquals("١", QuranData.arabicDigits(1))
        assertEquals("١٢", QuranData.arabicDigits(12))
        assertEquals("٢٨٦", QuranData.arabicDigits(286))
        assertEquals("٦٠٤", QuranData.arabicDigits(604))
    }

    @Test
    fun `wird range starts at page 2 with two pages a day`() {
        val first = wirdRange(WirdSettings(), 0)
        assertEquals(2, first.firstPage)
        assertEquals(3, first.lastPage)
        assertFalse(first.completed)
        val next = wirdRange(WirdSettings(), 2)
        assertEquals(4, next.firstPage)
        assertEquals(5, next.lastPage)
        val done = wirdRange(WirdSettings(), 603)
        assertTrue(done.completed)
        assertEquals(604, done.lastPage)
    }

    companion object {
        /** عدد آيات كل سورة (114 سورة) — المعيار الثابت، مجموعه 6236. */
        private val EXPECTED_AYAH_COUNTS = intArrayOf(
            7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128,
            111, 110, 98, 135, 112, 78, 118, 64, 77, 227, 93, 88, 69, 60, 34, 30, 73,
            54, 45, 83, 182, 88, 75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60,
            49, 62, 55, 78, 96, 29, 22, 24, 13, 14, 11, 11, 18, 12, 12, 30, 52, 52,
            44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42, 29, 19, 36, 25, 22, 17, 19,
            26, 30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3,
            6, 3, 5, 4, 5, 6
        )

        /** صفحة بداية كل سورة في مصحف المدينة (604 صفحات). */
        private val EXPECTED_SURAH_START_PAGE = intArrayOf(
            1, 2, 50, 77, 106, 128, 151, 177, 187, 208, 221, 235, 249, 255, 262, 267,
            282, 293, 305, 312, 322, 332, 342, 350, 359, 367, 377, 385, 396, 404, 411,
            415, 418, 428, 434, 440, 446, 453, 458, 467, 477, 483, 489, 496, 499, 502,
            507, 511, 515, 518, 520, 523, 526, 528, 531, 534, 537, 542, 545, 549, 551,
            553, 554, 556, 558, 560, 562, 564, 566, 568, 570, 572, 574, 575, 577, 578,
            580, 582, 583, 585, 586, 587, 587, 589, 590, 591, 591, 592, 593, 594, 595,
            595, 596, 596, 597, 597, 598, 598, 599, 599, 600, 600, 601, 601, 601, 602,
            602, 602, 603, 603, 603, 604, 604, 604
        )

        /** صفحة بداية كل جزء (30 جزءًا) في مصحف المدينة. */
        private val EXPECTED_JUZ_START_PAGE = intArrayOf(
            1, 22, 42, 62, 82, 102, 121, 142, 162, 182, 201, 222, 242, 262, 282, 302,
            322, 342, 362, 382, 402, 422, 442, 462, 482, 502, 522, 542, 562, 582
        )
    }
}
