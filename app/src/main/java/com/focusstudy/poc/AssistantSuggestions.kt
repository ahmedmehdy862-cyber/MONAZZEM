package com.focusstudy.poc

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.floorMod

/**
 * اقتراحات المساعد المتكيفة: لا أسئلة ثابتة.
 *
 * كل اقتراح يُبنى من بيانات المستخدم الفعلية (الملف المتعلَّم + السياق
 * الواسع): مهمة متأخرة بالاسم، ساعة التركيز المفضلة، سلسلة الورد، عادة
 * متعثرة — لا قوالب مكررة. الصياغة تتنوع بتغيّر اليوم (بذرة اليوم + اسم
 * المستخدم) فلا تتكرر الجملة نفسها كل يوم. نوع اقتراح تجاهله المستخدم
 * مرارًا (قبول أقل من 20% بعد 5 عروض) يُخفَّض تلقائيًا — المساعد يتعلّم
 * ما ينفع فعلًا لا ما يفترضه.
 */
data class AssistantSuggestion(
    /** نوع الاقتراح (لقياس القبول وتعلّم ما ينفع). */
    val kind: String,
    /** النص المعروض كرقاقة. */
    val text: String,
    /** الرسالة التي تُرسل عند الضغط (أو الفعل المباشر إن وُجد). */
    val sendText: String,
    val action: AssistantAction? = null
)

internal object AssistantSuggestions {

    fun suggest(
        snapshot: AssistantSnapshot,
        now: LocalDateTime = LocalDateTime.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): List<AssistantSuggestion> {
        val context = snapshot.context
        val learned = context.learned
        val user = snapshot.userName.ifBlank { "صديقي" }
        val today = now.toLocalDate()
        val seed = today.toEpochDay() * 31 + user.hashCode()
        val acceptance = context.suggestionAcceptance
        val out = ArrayList<AssistantSuggestion>()

        // 1) مهمة متأخرة: الأولوية القصوى — بالاسم وبنسبة التأخر المعتادة.
        val overdue = context.overdueTasks.firstOrNull()
        if (overdue != null) {
            val lateNote = if (context.mostDelayedCategory != null) {
                " — مهام «${context.mostDelayedCategory.label}» بتتأخر منك عادةً"
            } else {
                ""
            }
            out += AssistantSuggestion(
                kind = "late_task",
                text = pick(
                    listOf(
                        "«${overdue.title}» مستحقة وفات موعدها$lateNote — نخلصها الأول؟",
                        "عندك مهمة متأخرة: «${overdue.title}»$lateNote. نبدأ بيها؟",
                        "«${overdue.title}» لسه مخلصتش رغم إن موعدها فات$lateNote."
                    ),
                    seed
                ),
                sendText = "أعمل إيه دلوقتي؟"
            )
        }

        // 2) دعوة تركيز في ساعة التركيز المفضلة، إن لم تبدأ جلسة اليوم بعد.
        val bestHour = learned.bestFocusHours.firstOrNull()
        if (bestHour != null && now.hour == bestHour.hour && context.focusSessionsToday == 0) {
            val target = snapshot.nextTaskTitle
            out += AssistantSuggestion(
                kind = "focus_invite",
                text = pick(
                    listOf(
                        "الساعة ${bestHour.hour} — ده وقت تركيزك المفضل. نبدأ جلسة ${learned.preferredFocusMinutes ?: 25} دقيقة؟",
                        "دلوقتي وقت تركيزك المعتاد (${bestHour.hour}:00). جلسة سريعة؟"
                    ),
                    seed + 1
                ),
                sendText = if (target != null) "ابدأ جلسة تركيز لمهمة $target" else "ابدأ جلسة تركيز",
                action = null
            )
        }

        // 3) تذكير ورد في ساعة الورد المعتادة إن لم يُسجَّل بعد.
        val wirdHour = learned.bestWirdHour
        if (wirdHour != null && !snapshot.wirdDoneToday && now.hour == wirdHour.hour) {
            out += AssistantSuggestion(
                kind = "wird_reminder",
                text = "وردك النهاردة لسه — بتقراه عادةً الساعة ${wirdHour.hour}:00. صفحات ${snapshot.wirdNextPage}–${snapshot.wirdNextPage + snapshot.wirdPagesPerDay - 1}.",
                sendText = "وردي النهاردة إيه؟"
            )
        }

        // 4) احتفال متنوع بسلسلة الورد — يذكر الرقم الحقيقي لا مجاملة عامة.
        if (snapshot.wirdDoneToday && context.wirdStreakDays >= 3) {
            out += AssistantSuggestion(
                kind = "celebration",
                text = pick(
                    listOf(
                        "ما شاء الله! ${context.wirdStreakDays} أيام ورد متواصل — أطول سلسلة ليك ${context.longestWirdStreakDays} يوم. كمّل!",
                        "${context.wirdStreakDays} أيام على التوالي في الورد يا $user — إنجاز يستاهل يتقال!",
                        "سلسلة الورد ${context.wirdStreakDays} أيام ولسه مكملة. عايز ألخّص لك يومك؟"
                    ),
                    seed + 2
                ),
                sendText = "لخّص يومي"
            )
        }

        // 5) عادة متعثرة: أقل نسبة إنجاز آخر 30 يومًا ولم تُنجز اليوم.
        val weakHabit = context.habitRates.entries
            .filter { it.value < 0.5 }
            .minByOrNull { it.value }
        if (weakHabit != null) {
            val name = context.habitNames[weakHabit.key]
            if (name != null) {
                out += AssistantSuggestion(
                    kind = "habit_nudge",
                    text = "عادة «$name» بتتعمل ${(weakHabit.value * 100).toInt()}% بس من الأيام — جرّبها النهاردة ولو دقيقة؟",
                    sendText = "عاداتي عاملة إيه؟"
                )
            }
        }

        // 6) ملاحظة فلوس: مصروف اليوم أعلى من المتوسط بكثير.
        if (learned.avgDailySpend30d > 0 && context.spentToday > learned.avgDailySpend30d * 1.5) {
            out += AssistantSuggestion(
                kind = "money_note",
                text = "صرفت النهاردة ${formatAmount(context.spentToday)} — أعلى من متوسطك اليومي (${formatAmount(learned.avgDailySpend30d)}).",
                sendText = "صرفت إيه النهاردة؟"
            )
        }

        // 7) ملخص اليوم مساءً — إن لم يوجد اقتراح أقوى.
        if (now.hour >= 20 && out.size < 2) {
            out += AssistantSuggestion(
                kind = "day_summary",
                text = "اليوم بيخلص — تحب ألخّص لك يومك؟",
                sendText = "لخّص يومي"
            )
        }

        // تعلّم من التجاهل: نوع قُبل أقل من 20% بعد 5 عروض يُحذف من القائمة.
        val filtered = out.filter { suggestion ->
            val rate = acceptance[suggestion.kind]
            rate == null || rate >= 0.2
        }
        return filtered.take(4)
    }

    private fun pick(pool: List<String>, seed: Long): String =
        pool[floorMod(seed, pool.size.toLong()).toInt()]

    private fun formatAmount(amount: Double): String {
        val rounded = if (amount == amount.toLong().toDouble()) amount.toLong().toString() else "%.2f".format(amount)
        return "$rounded ج.م"
    }
}
