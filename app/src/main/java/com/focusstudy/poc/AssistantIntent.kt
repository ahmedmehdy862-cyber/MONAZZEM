package com.focusstudy.poc

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * فهم نية المستخدم من كلام طبيعي حر — لا أوامر بصيغ ثابتة.
 *
 * هذه هي النقطة التي كان المساعد الحالي يفشل فيها فيفهم «جزءًا محددًا» فقط:
 * أي رسالة لا تطابق قالب أمر ثابت كانت تسقط في الرد الاحتياطي العام. المصنّف
 * هنا يفحص الرسالة *قبل* محلل الأوامر القديم، فيلتقط النوايا التي لا صيغة
 * أمر لها (تلخيص اليوم، اقتراح خطة، سؤال عن الفلوس، استرجاع ذاكرة، حدث
 * مستقبلي يستحق مهمة)، ويكشف الغموض فيسأل سؤالًا توضيحيًا ذكيًا يسمّي
 * المرشحين الحقيقيين من بيانات المستخدم بدل «مش فاهم».
 *
 * المصنّف نقي (بلا Context): يُختبَر بوحدات JVM عادية. النوايا التنفيذية
 * نفسها ما زالت تُبنى في [AssistantEngine] بمحلله القائم.
 */
sealed class ClassifiedIntent {
    /** ليست نية جديدة: يكمل المحرك القديم (أوامر تنفيذية وأسئلة معروفة). */
    data object PassThrough : ClassifiedIntent()

    /** «لخّص يومي» — ملخص عبر كل الأقسام. */
    data object AskDaySummary : ClassifiedIntent()

    /** «أعمل إيه دلوقتي؟» — اقتراح خطة متكيف. */
    data object AskPlanAdvice : ClassifiedIntent()

    /** سؤال عن الفلوس والمصاريف. */
    data object AskMoney : ClassifiedIntent()

    /** «فاكر إني قلتلك…؟» — استرجاع ذاكرة محادثة سابقة. */
    data class AskMemory(val query: String) : ClassifiedIntent()

    /** حدث مستقبلي من كلام عادي يستحق أن يُقترح كمهمة («عندي امتحان بكرة»). */
    data class ProposeTask(
        val title: String,
        val dueAt: Long?,
        val priorityLevel: Int?,
        val recurrence: Recurrence
    ) : ClassifiedIntent()

    /** غامض: سؤال توضيحي يسمّي الخيارات الحقيقية من بيانات المستخدم. */
    data class Clarify(val question: String, val options: List<String>) : ClassifiedIntent()
}

internal object AssistantIntentClassifier {

    fun classify(
        text: String,
        snapshot: AssistantSnapshot,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): ClassifiedIntent {
        val lower = text.trim().lowercase()
        if (lower.isBlank()) return ClassifiedIntent.PassThrough

        // الغموض أولًا: رسالة بلا موضوع واضح لا تُترك للرد الاحتياطي الغبي.
        clarifyIfAmbiguous(lower, snapshot)?.let { return it }
        askMemoryIntent(lower)?.let { return it }
        askDaySummaryIntent(lower)?.let { return it }
        askPlanAdviceIntent(lower)?.let { return it }
        askMoneyIntent(lower)?.let { return it }
        proposeTaskIntent(lower, today, zone)?.let { return it }
        return ClassifiedIntent.PassThrough
    }

    /**
     * «خلصت» بلا موضوع، أو «ابدأ»/«يلا» بلا هدف: السؤال يسمّي المرشحين
     * الحقيقيين (الورد إن لم يُسجَّل، والمهام المفتوحة بالاسم) بدل «مش فاهم».
     */
    private fun clarifyIfAmbiguous(lower: String, snapshot: AssistantSnapshot): ClassifiedIntent.Clarify? {
        val user = snapshot.userName.ifBlank { "صديقي" }
        val bareDone = (lower == "خلصت" || lower == "خلصت كل حاجة" || lower == "خلصت اللي عليا" ||
            lower == "خلصت كل اللي عليا" || lower == "تم" || lower == "تمام") &&
            !lower.contains("ورد")
        if (bareDone) {
            val options = ArrayList<String>()
            if (!snapshot.wirdDoneToday) options += "خلصت الورد"
            snapshot.openTasks.take(2).forEach { options += "خلصت مهمة ${it.title}" }
            if (options.isEmpty()) return null
            return ClassifiedIntent.Clarify(
                question = "خلصت إيه بالظبط يا $user؟",
                options = options
            )
        }
        val bareStart = (lower == "ابدأ" || lower == "ابدا" || lower == "يلا" || lower == "يلا نبدأ" ||
            lower == "يلا نبدا" || lower == "نبدأ" || lower == "نبدا") &&
            !lower.contains("جلسة") && !lower.contains("جلسه") && !lower.contains("تركيز")
        if (bareStart) {
            val options = ArrayList<String>()
            options += "ابدأ جلسة تركيز"
            snapshot.nextTaskTitle?.let { options += "ابدأ جلسة تركيز لمهمة $it" }
            return ClassifiedIntent.Clarify(
                question = "نبدأ بإيه يا $user؟",
                options = options
            )
        }
        return null
    }

    private fun askMemoryIntent(lower: String): ClassifiedIntent.AskMemory? {
        if (lower.contains("فاكر") || lower.contains("تفتكر") || lower.contains("قلتلك") ||
            lower.contains("قلت لك") || lower.contains("زي ما قلت") || lower.contains("تتذكر") ||
            lower.contains("بتفتكر")
        ) {
            return ClassifiedIntent.AskMemory(lower)
        }
        return null
    }

    private fun askDaySummaryIntent(lower: String): ClassifiedIntent? {
        if ((lower.contains("لخص") || lower.contains("لخّص") || lower.contains("ملخص")) && lower.contains("يوم")) {
            return ClassifiedIntent.AskDaySummary
        }
        if (lower.contains("يومي عامل") || lower.contains("يومي كان") || lower.contains("يومي النهاردة") ||
            lower.contains("إيه اللي حصل النهاردة") || lower.contains("ايه اللي حصل النهاردة") ||
            lower.contains("يومي إزاي") || lower.contains("يومي ازاي")
        ) {
            return ClassifiedIntent.AskDaySummary
        }
        return null
    }

    private fun askPlanAdviceIntent(lower: String): ClassifiedIntent? {
        // «أعمل إيه» كان يتحوّل خطأً إلى مهمة بعنوان «إيه دلوقتي» في المحلل
        // القديم («أعمل» فعل إضافة) — هنا يُلتقط أولًا كطلب خطة.
        if (lower.contains("أعمل إيه") || lower.contains("اعمل ايه") || lower.contains("اعمل إيه") ||
            lower.contains("أعمل ايه") || lower.contains("اعمل ايه دلوقتي") || lower.contains("أعمل إيه دلوقتي")
        ) {
            return ClassifiedIntent.AskPlanAdvice
        }
        if ((lower.contains("اقترح") || lower.contains("رتب يومي") || lower.contains("نظم يومي") ||
                lower.contains("أنظم يومي") || lower.contains("انظم يومي") || lower.contains("نظملي يومي") ||
                lower.contains("نظّم يومي")) && !lower.contains("مهمة")
        ) {
            return ClassifiedIntent.AskPlanAdvice
        }
        return null
    }

    private fun askMoneyIntent(lower: String): ClassifiedIntent? {
        if (lower.contains("صرفت") || lower.contains("فلوس") || lower.contains("مصاريف") ||
            lower.contains("مصروفي") || lower.contains("ميزانية") || lower.contains("ميزانيه") ||
            lower.contains("معايا كام") || lower.contains("رصيدي") || lower.contains("مصاريفي")
        ) {
            return ClassifiedIntent.AskMoney
        }
        return null
    }

    /**
     * حدث مستقبلي من كلام عادي: «عندي امتحان فيزياء بكرة»، «لازم أخلص
     * المشروع»، «محتاج أذاكر رياضيات». بلا فعل إضافة صريح، فالمساعد يقترح
     * إنشاء المهمة بدل إضافتها صامتًا أو تجاهلها — والشاشة تعرض زر تأكيد.
     */
    private fun proposeTaskIntent(
        lower: String,
        today: LocalDate,
        zone: ZoneId
    ): ClassifiedIntent.ProposeTask? {
        val triggers = listOf(
            "عندي امتحان", "عندي مقابلة", "عندي اجتماع", "عندي تسليم", "عندي واجب",
            "عندي مذاكرة", "عندي مراجعه", "عندي مراجعة", "لازم أخلص", "لازم اخلص",
            "محتاج أذاكر", "محتاج اذاكر", "عايز أذاكر", "عايز اذاكر", "عندي شغل",
            "عندي محاضرة", "عندي درس"
        )
        if (triggers.none { lower.contains(it) }) return null
        if (lower.contains("ضيف") || lower.contains("أضف") || lower.contains("اضف")) return null
        val cleaned = lower
            .replace("عندي", "")
            .replace("لازم", "")
            .replace("محتاج", "")
            .replace("عايز", "")
            .trim()
        if (cleaned.isBlank()) return null
        val parsed = QuickCaptureParser.parse(cleaned, today)
        val title = parsed.title.trim()
        if (title.isBlank()) return null
        val dueAt = if (parsed.date != null || parsed.hour != null) {
            val date = parsed.date ?: today
            val time = if (parsed.hour != null) LocalTime.of(parsed.hour, parsed.minute) else LocalTime.of(23, 59)
            LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
        } else {
            null
        }
        return ClassifiedIntent.ProposeTask(
            title = title,
            dueAt = dueAt,
            priorityLevel = parsed.priorityLevel,
            recurrence = parsed.recurrence
        )
    }
}
