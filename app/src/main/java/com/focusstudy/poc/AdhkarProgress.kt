package com.focusstudy.poc

import android.content.Context

/**
 * تقدّم الأذكار المحفوظ محليًا (المرحلة 5): التاريخ + العدّادات المتبقية لكل فترة.
 *
 * الأذكار كانت تُحفظ في `rememberSaveable` فقط فتضيع عند الخروج من الشاشة؛ هنا
 * تُحفظ في SharedPreferences وتُستعاد عند فتح الشاشة، وتُصفَّر تلقائيًا مع يوم
 * جديد. لا تُحفظ إلا الأرقام المتبقية، لا نصوص الأذكار.
 */
internal class AdhkarProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class Saved(val morning: List<Int>, val evening: List<Int>)

    /** يعيد المحفوظ ليوم [date] إن كان بنفس عدد الأذكار، وإلا null فيبدأ من جديد. */
    fun load(date: String, size: Int): Saved? {
        if (size <= 0) return null
        if (prefs.getString(KEY_DATE, "") != date) return null
        val morning = parse(prefs.getString(KEY_MORNING, null), size) ?: return null
        val evening = parse(prefs.getString(KEY_EVENING, null), size) ?: return null
        return Saved(morning, evening)
    }

    fun save(date: String, morning: List<Int>, evening: List<Int>) {
        prefs.edit()
            .putString(KEY_DATE, date)
            .putString(KEY_MORNING, morning.joinToString(","))
            .putString(KEY_EVENING, evening.joinToString(","))
            .apply()
    }

    private fun parse(raw: String?, size: Int): List<Int>? {
        if (raw.isNullOrBlank()) return null
        val values = raw.split(",").mapNotNull { it.toIntOrNull() }
        return if (values.size == size && values.all { it >= 0 }) values else null
    }

    companion object {
        private const val FILE = "adhkar_progress"
        private const val KEY_DATE = "date"
        private const val KEY_MORNING = "morning_left"
        private const val KEY_EVENING = "evening_left"
    }
}
