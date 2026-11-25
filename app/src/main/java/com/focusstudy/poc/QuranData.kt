package com.focusstudy.poc

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject

/** آية واحدة من نص تنزيل العثماني. */
data class QuranAyah(
    val surah: Int,
    val ayah: Int,
    val text: String
)

/** نطاق آيات متصل داخل صفحة واحدة من مصحف المدينة. */
data class QuranPageRange(
    val surah: Int,
    val fromAyah: Int,
    val toAyah: Int
)

/** بيانات سورة: رقمها واسمها وعدد آياتها وصفحة بدايتها. */
data class SurahInfo(
    val number: Int,
    val name: String,
    val ayahs: Int,
    val page: Int
)

/** فهرس المصحف كاملًا: النص والخريطة وأسماء السور. */
data class QuranIndex(
    val ayahs: List<QuranAyah>,
    val byKey: Map<Pair<Int, Int>, Int>,
    val pages: Map<Int, List<QuranPageRange>>,
    val juzStartPage: Map<Int, Int>,
    val surahs: List<SurahInfo>
) {
    /** آيات صفحة واحدة بترتيب المصحف. */
    fun ayahsForPage(page: Int): List<QuranAyah> {
        val ranges = pages[page] ?: return emptyList()
        val out = ArrayList<QuranAyah>()
        for (range in ranges) {
            for (ayah in range.fromAyah..range.toAyah) {
                val index = byKey[range.surah to ayah] ?: continue
                out.add(ayahs[index])
            }
        }
        return out
    }

    /** أسماء السور الظاهرة في صفحة واحدة. */
    fun pageSurahNames(page: Int): String {
        val names = (pages[page] ?: return "").mapNotNull { range ->
            surahs.getOrNull(range.surah - 1)?.name
        }.distinct()
        return names.joinToString("، ")
    }

    /** جزء الصفحة (1..30) من خريطة بدايات الأجزاء. */
    fun juzForPage(page: Int): Int {
        var juz = 1
        for (candidate in 1..30) {
            val start = juzStartPage[candidate] ?: continue
            if (start <= page) juz = candidate else break
        }
        return juz
    }

    fun pageForSurah(surah: Int): Int = surahs.getOrNull(surah - 1)?.page ?: 1

    fun pageForJuz(juz: Int): Int = juzStartPage[juz.coerceIn(1, 30)] ?: 1
}

/** ذكر واحد من حصن المسلم مع عدد مرات تكراره. */
data class AdhkarItem(
    val id: Int,
    val text: String,
    val repeat: Int
)

/** أذكار الصباح والمساء (الفصل 27 من حصن المسلم) مع مصدرها. */
data class AdhkarIndex(
    val source: String,
    val sourceUrl: String,
    val chapter: String,
    val items: List<AdhkarItem>
)

/**
 * تحميل بيانات المصحف والأذكار من الأصول المضمّنة؛ يعمل دون إنترنت تمامًا.
 *
 * نص القرآن: مشروع تنزيل (Tanzil) — النص العثماني، الإصدار 1.1 (فبراير 2021)،
 * رخصة CC Attribution 3.0 — دون أي تغيير. خريطة الصفحات (604 صفحات) مبنية من
 * بيانات AlQuran Cloud (أرقام الصفحات لمصحف المدينة) وتُطابَق مع نص تنزيل.
 * الأذكار: الفصل 27 من حصن المسلم (أذكار الصباح والمساء) من الموقع الرسمي.
 */
object QuranData {
    private const val QURAN_ASSET = "quran/quran-uthmani.txt"
    private const val PAGES_ASSET = "quran/pages.json"
    private const val ADHKAR_ASSET = "quran/adhkar.json"

    @Volatile
    private var cachedIndex: QuranIndex? = null

    @Volatile
    private var cachedAdhkar: AdhkarIndex? = null

    /** يحمّل فهرس المصحف مرة واحدة ويحتفظ به؛ يعيد null إن تعذّر التحميل. */
    fun loadIndex(context: Context): QuranIndex? {
        cachedIndex?.let { return it }
        return runCatching {
            val ayahs = ArrayList<QuranAyah>(6236)
            val byKey = HashMap<Pair<Int, Int>, Int>(6236)
            context.assets.open(QURAN_ASSET).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val first = line.indexOf('|')
                    val second = line.indexOf('|', first + 1)
                    if (first <= 0 || second <= first) return@forEachLine
                    val surah = line.substring(0, first).toIntOrNull() ?: return@forEachLine
                    val ayah = line.substring(first + 1, second).toIntOrNull() ?: return@forEachLine
                    val text = line.substring(second + 1)
                    if (text.isBlank()) return@forEachLine
                    byKey[surah to ayah] = ayahs.size
                    ayahs.add(QuranAyah(surah, ayah, text))
                }
            }
            require(ayahs.size == 6236) { "عدد آيات المصحف غير مكتمل: ${ayahs.size}" }
            val pagesJson = context.assets.open(PAGES_ASSET).use { stream ->
                JSONObject(stream.readBytes().toString(Charsets.UTF_8))
            }
            val pages = HashMap<Int, List<QuranPageRange>>(604)
            val pagesObject = pagesJson.getJSONObject("pages")
            val pageKeys = pagesObject.keys()
            while (pageKeys.hasNext()) {
                val key = pageKeys.next()
                val page = key.toIntOrNull() ?: continue
                val rangesJson = pagesObject.getJSONArray(key)
                val ranges = ArrayList<QuranPageRange>(rangesJson.length())
                for (i in 0 until rangesJson.length()) {
                    val range = rangesJson.getJSONArray(i)
                    ranges.add(QuranPageRange(range.getInt(0), range.getInt(1), range.getInt(2)))
                }
                pages[page] = ranges
            }
            require(pages.size == 604) { "خريطة الصفحات غير مكتملة: ${pages.size}" }
            val juzStartPage = HashMap<Int, Int>(30)
            val juzObject = pagesJson.getJSONObject("juz")
            val juzKeys = juzObject.keys()
            while (juzKeys.hasNext()) {
                val key = juzKeys.next()
                juzStartPage[key.toIntOrNull() ?: continue] = juzObject.getInt(key)
            }
            val surahs = ArrayList<SurahInfo>(114)
            val surahsJson = pagesJson.getJSONArray("surahs")
            for (i in 0 until surahsJson.length()) {
                val item = surahsJson.getJSONObject(i)
                surahs.add(
                    SurahInfo(
                        number = item.getInt("n"),
                        name = item.getString("name"),
                        ayahs = item.getInt("ayahs"),
                        page = item.getInt("page")
                    )
                )
            }
            require(surahs.size == 114) { "أسماء السور غير مكتملة: ${surahs.size}" }
            QuranIndex(ayahs, byKey, pages, juzStartPage, surahs)
        }.getOrNull()?.also { cachedIndex = it }
    }

    /** يحمّل الأذكار مرة واحدة ويحتفظ بها؛ يعيد null إن تعذّر التحميل. */
    fun loadAdhkar(context: Context): AdhkarIndex? {
        cachedAdhkar?.let { return it }
        return runCatching {
            val json = context.assets.open(ADHKAR_ASSET).use { stream ->
                JSONObject(stream.readBytes().toString(Charsets.UTF_8))
            }
            val itemsJson = json.getJSONArray("items")
            val items = ArrayList<AdhkarItem>(itemsJson.length())
            for (i in 0 until itemsJson.length()) {
                val item = itemsJson.getJSONObject(i)
                items.add(
                    AdhkarItem(
                        id = item.getInt("id"),
                        text = item.getString("text"),
                        repeat = item.getInt("repeat").coerceAtLeast(1)
                    )
                )
            }
            require(items.isNotEmpty()) { "قائمة الأذكار فارغة" }
            AdhkarIndex(
                source = json.optString("source"),
                sourceUrl = json.optString("sourceUrl"),
                chapter = json.optString("chapter"),
                items = items
            )
        }.getOrNull()?.also { cachedAdhkar = it }
    }

    /**
     * يفصل البسملة عن أول آية في السورة (عدا الفاتحة والتوبة) لعرضها سطرًا مستقلًا
     * كما في المصحف؛ يعيد (البسملة أو null، باقي نص الآية). البسملة نفسها مأخوذة من
     * أول آية في النص المضمّن (1:1) فتطابق حروفه تمامًا دون كتابتها يدويًا.
     */
    fun splitBasmalah(index: QuranIndex, ayah: QuranAyah): Pair<String?, String> {
        if (ayah.surah == 1 || ayah.surah == 9 || ayah.ayah != 1) return null to ayah.text
        val basmalah = index.ayahs.firstOrNull()?.text ?: return null to ayah.text
        if (!ayah.text.startsWith(basmalah)) return null to ayah.text
        val rest = ayah.text.substring(basmalah.length).trim()
        return basmalah to rest
    }

    /** رقم الآية بالأرقام العربية المشرقية (١٢٣) كما في المصحف. */
    fun arabicDigits(number: Int): String {
        val eastern = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
        return number.toString().map { ch ->
            if (ch in '0'..'9') eastern[ch - '0'] else ch
        }.joinToString("")
    }
}

/**
 * تطبيع الأرقام المدخلة قبل تحليلها: الأرقام العربية المشرقية (٠–٩) والفارسية
 * (۰–۹) تتحول إلى أرقام لاتينية، والفاصلة العشرية «,» و«٫» إلى «.»؛ لأن
 * `toIntOrNull` و`toDoubleOrNull` يرفضانها رغم أن `isDigit()` يقبلها، فكان
 * الإدخال بها يفشل بصمت في حقول الأرقام.
 */
fun normalizeInputDigits(input: String): String {
    val sb = StringBuilder(input.length)
    for (ch in input) {
        sb.append(
            when (ch) {
                in '٠'..'٩' -> '0' + (ch - '٠')
                in '۰'..'۹' -> '0' + (ch - '۰')
                ',', '٫' -> '.'
                else -> ch
            }
        )
    }
    return sb.toString()
}
