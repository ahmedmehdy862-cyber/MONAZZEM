package com.focusstudy.poc

import android.content.Context

/**
 * مخزن ذاكرة المساعد: ذكريات المحادثة وبنود الملف المتعلَّم ونتائج الاقتراحات.
 *
 * كل شيء محلي على الجهاز فقط (جداول `assistant_memories` و`assistant_profile`
 * و`assistant_interactions` في قاعدة البيانات، بلا أي رفع أو مشاركة)، وبوابة
 * واحدة لكل قراءة/كتابة حتى لا تلمس الشاشات [StudyDb] مباشرة.
 *
 * الذكريات لها ثقة: ترتفع كلما استُخدمت، وتضمحل إن لم تُستخدم 30 يومًا،
 * وتُحذف إن هبطت ثقتها تحت 0.15 أو تجاوز عمرها 120 يومًا — فلا تتراكم
 * ذكريات قديمة خاطئة إلى الأبد. المستخدم يحذف أي ذكرى من تبويب
 * «عرّفني عليك» متى شاء.
 */
object AssistantMemoryStore {

    /** تُحفظ بنود الملف المتعلَّم مرة كل ساعة على الأكثر. */
    private const val PROFILE_REFRESH_MS = 60 * 60 * 1000L

    /** تُحذف ذكرى لم تُستخدم منذ 120 يومًا. */
    private const val MEMORY_MAX_AGE_MS = 120L * 24 * 60 * 60 * 1000

    /** تُحذف ذكرى هبطت ثقتها تحت هذا الحد. */
    private const val MEMORY_MIN_CONFIDENCE = 0.15

    /** معامل اضمحلال الثقة لكل 30 يومًا بلا استخدام. */
    private const val DECAY_PER_MONTH = 0.9

    fun loadMemories(context: Context): List<AssistantMemoryRow> {
        val db = StudyDb.get(context.applicationContext)
        pruneMemories(context, System.currentTimeMillis())
        return runCatching { db.assistantMemories() }.getOrDefault(emptyList())
    }

    fun saveMemory(
        context: Context,
        text: String,
        category: String,
        source: String = "chat",
        confidence: Double = 0.8
    ): Long {
        if (text.isBlank()) return -1
        return runCatching {
            StudyDb.get(context.applicationContext)
                .saveAssistantMemory(text.trim(), category, source, confidence.coerceIn(0.0, 1.0))
        }.getOrDefault(-1)
    }

    fun deleteMemory(context: Context, id: Long) {
        runCatching { StudyDb.get(context.applicationContext).deleteAssistantMemory(id) }
    }

    /** سُجّل استخدام ذكرى: يرتفع عدّاد استخدامها وثقتها قليلًا. */
    fun touchMemory(context: Context, id: Long, now: Long = System.currentTimeMillis()) {
        runCatching { StudyDb.get(context.applicationContext).touchAssistantMemory(id, now) }
    }

    fun recordInteraction(context: Context, kind: String, detail: String, accepted: Boolean) {
        runCatching {
            StudyDb.get(context.applicationContext)
                .recordAssistantInteraction(kind, detail, accepted)
        }
    }

    /** نسبة قبول كل نوع اقتراح (مقبول/معروض) — لترتيب الاقتراحات بما ينفع فعلًا. */
    fun suggestionAcceptance(context: Context): Map<String, Double> {
        val db = StudyDb.get(context.applicationContext)
        return runCatching { db.assistantInteractionStats() }.getOrDefault(emptyList())
            .groupBy { it.kind }
            .mapValues { (_, rows) ->
                val total = rows.size
                if (total == 0) 0.0 else rows.count { it.accepted }.toDouble() / total
            }
    }

    fun loadProfileEntries(context: Context): List<AssistantProfileEntry> =
        runCatching { StudyDb.get(context.applicationContext).assistantProfile() }
            .getOrDefault(emptyList())

    /**
     * يحفظ بنود الملف المتعلَّم إن مرّت ساعة على آخر حفظ — فلا تُكتب قاعدة
     * البيانات في كل فتح للمحادثة.
     */
    fun refreshProfileEntries(context: Context, profile: LearnedProfile, now: Long = System.currentTimeMillis()) {
        val db = StudyDb.get(context.applicationContext)
        val last = runCatching { db.assistantProfile() }.getOrDefault(emptyList())
            .maxOfOrNull { it.updatedAt } ?: 0L
        if (now - last < PROFILE_REFRESH_MS) return
        runCatching { db.saveAssistantProfile(entriesFrom(profile, now)) }
    }

    /** بنود الملف المتعلَّم القابلة للعرض: عنوان عربي وقيمة وثقة وعدد عينات. */
    fun entriesFrom(profile: LearnedProfile, now: Long): List<AssistantProfileEntry> {
        val entries = ArrayList<AssistantProfileEntry>()
        profile.bestFocusHours.firstOrNull()?.let { hour ->
            entries += AssistantProfileEntry(
                key = "best_focus_hour",
                textValue = "أفضل وقت تركيز: الساعة ${hour.hour}:00 (${hour.count} جلسات)",
                realValue = hour.hour.toDouble(),
                confidence = hour.share.coerceIn(0.0, 1.0),
                samples = hour.count,
                updatedAt = now
            )
        }
        profile.bestWirdHour?.let { hour ->
            entries += AssistantProfileEntry(
                key = "best_wird_hour",
                textValue = "وقت الورد المعتاد: الساعة ${hour.hour}:00 (${hour.count} مرات)",
                realValue = hour.hour.toDouble(),
                confidence = hour.share.coerceIn(0.0, 1.0),
                samples = hour.count,
                updatedAt = now
            )
        }
        profile.preferredFocusMinutes?.let { minutes ->
            entries += AssistantProfileEntry(
                key = "preferred_focus_minutes",
                textValue = "مدة الجلسة المفضلة: $minutes دقيقة",
                realValue = minutes.toDouble(),
                confidence = 0.7,
                samples = 0,
                updatedAt = now
            )
        }
        profile.mostDelayedCategory?.let { category ->
            val stats = profile.categoryStats.firstOrNull { it.category == category }
            entries += AssistantProfileEntry(
                key = "most_delayed_category",
                textValue = "مهام «${category.label}» تتأخر عادةً (${((stats?.lateRate ?: 0.0) * 100).toInt()}% منها)",
                realValue = stats?.lateRate,
                confidence = (stats?.lateRate ?: 0.0).coerceIn(0.0, 1.0),
                samples = stats?.total ?: 0,
                updatedAt = now
            )
        }
        if (profile.wirdStreakDays >= 2) {
            entries += AssistantProfileEntry(
                key = "wird_streak",
                textValue = "سلسلة الورد الحالية: ${profile.wirdStreakDays} أيام متواصلة",
                realValue = profile.wirdStreakDays.toDouble(),
                confidence = 0.9,
                samples = profile.wirdTotalDays,
                updatedAt = now
            )
        }
        if (profile.exerciseDays7d > 0) {
            entries += AssistantProfileEntry(
                key = "exercise_days_7d",
                textValue = "اتمرنت ${profile.exerciseDays7d} من آخر 7 أيام",
                realValue = profile.exerciseDays7d.toDouble(),
                confidence = 0.8,
                samples = profile.exerciseDays7d,
                updatedAt = now
            )
        }
        return entries
    }

    /** يضمحل ثقة الذكريات غير المستخدمة ويحذف المتهالكة. */
    private fun pruneMemories(context: Context, now: Long) {
        val db = StudyDb.get(context.applicationContext)
        val memories = runCatching { db.assistantMemories() }.getOrDefault(emptyList())
        memories.forEach { memory ->
            val monthsUnused = ((now - memory.lastUsedAt) / (30L * 24 * 60 * 60 * 1000)).coerceAtLeast(0)
            val ageMs = now - memory.createdAt
            when {
                ageMs > MEMORY_MAX_AGE_MS -> runCatching { db.deleteAssistantMemory(memory.id) }
                memory.confidence < MEMORY_MIN_CONFIDENCE -> runCatching { db.deleteAssistantMemory(memory.id) }
                monthsUnused > 0 -> {
                    var confidence = memory.confidence
                    repeat(monthsUnused.toInt().coerceAtMost(4)) { confidence *= DECAY_PER_MONTH }
                    runCatching { db.updateAssistantMemoryConfidence(memory.id, confidence) }
                }
            }
        }
    }
}
