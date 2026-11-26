package com.focusstudy.poc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * بطاقات الشخصية (المرحلة 5). الشخصية نفسها اختيار محلي قديم في «التخصيص»
 * ([UserProfile] في [FocusPreferences])؛ هنا أثرها العملي فقط:
 * - الطالب: جدول محاضرات ([LectureScheduleCard]) + تقدّم كل مادة.
 * - الموظف: وضع مختصر يخفي الصلاة والرفيق وبانر الفريق من شاشة اليوم.
 * - الخريج: تذكيرات مهارات/أهداف + كتل خطة بمواعيد فعلية.
 * المتقاعد والعام بلا تغيير — لا أثر خاص بهما، وهذا مقصود لا نقص.
 */

/**
 * تقدّم كل مادة للطالب: دقائق المذاكرة الفعلية مقابل هدف المادة.
 *
 * «دقائق المذاكرة» هنا = مجموع الدقائق المنجزة على مهام المادة
 * ([StudyTask.doneMinutes])، لا جلسات التركيز الخام — فهي الدقائق المرتبطة
 * بالمادة فعلًا. المادة بلا هدف وبلا مهام لا تظهر.
 */
@Composable
internal fun SubjectProgressCard(subjects: List<StudySubject>, tasks: List<StudyTask>) {
    val rows = subjects.mapNotNull { subject ->
        val subjectTasks = tasks.filter { it.subjectId == subject.id }
        val done = subjectTasks.sumOf { it.doneMinutes }
        val planned = subjectTasks.sumOf { it.plannedMinutes }
        val goal = subject.goalMinutes
        if (goal <= 0 && subjectTasks.isEmpty()) return@mapNotNull null
        val ratio = when {
            goal > 0 -> done.toFloat() / goal
            planned > 0 -> done.toFloat() / planned
            else -> 0f
        }.coerceIn(0f, 1f)
        val targetLabel = if (goal > 0) "$done من $goal دقيقة" else "$done دقيقة"
        Triple(subject.name, targetLabel, ratio)
    }
    if (rows.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(FocusSpacing.card),
            verticalArrangement = Arrangement.spacedBy(FocusSpacing.item)
        ) {
            Text(
                "تقدّم موادك",
                style = MaterialTheme.typography.titleSmall,
                color = FocusColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            rows.forEach { (name, label, ratio) ->
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FocusColors.TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = FocusColors.TextSecondary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .height(FocusSpacing.progressHeight)
                            .clip(RoundedCornerShape(5.dp))
                            .background(FocusColors.SurfaceHigh)
                    ) {
                        Box(
                            Modifier.fillMaxWidth(ratio)
                                .height(FocusSpacing.progressHeight)
                                .clip(RoundedCornerShape(5.dp))
                                .background(FocusColors.Teal)
                        )
                    }
                }
            }
        }
    }
}

/**
 * بطاقة الخريج في الرئيسية: مهاراته وأهدافه مع حالة تذكير المهارات.
 *
 * التذكير نفسه من المرحلة 4 ([SkillReminderScheduler] يوميًا في ساعة يختارها
 * المستخدم من شاشة الإشعارات) — هذه البطاقة تربط به وتعرض حالته فقط، لا
 * تنشئ تذكيرًا موازيًا. تُعرض أقل 3 مهارات تقدّمًا وأقرب هدفين نشطين.
 */
@Composable
internal fun GraduateSkillsCard(
    onOpenSkills: () -> Unit,
    onOpenGoals: () -> Unit
) {
    val context = LocalContext.current
    var skills by remember { mutableStateOf<List<Skill>>(emptyList()) }
    var goals by remember { mutableStateOf<List<Goal>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        skills = runCatching { SkillRepository.get(context).skills() }.getOrDefault(emptyList())
        goals = runCatching { GoalRepository.get(context).goals() }.getOrDefault(emptyList())
        loaded = true
    }
    if (!loaded) return
    val reminder = remember { SkillReminderPrefs.load(context) }
    val weakestSkills = skills.sortedBy { it.progress }.take(3)
    val activeGoals = goals.filter { it.status == "active" }.sortedBy { it.progress }.take(2)

    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(FocusSpacing.card),
            verticalArrangement = Arrangement.spacedBy(FocusSpacing.item)
        ) {
            Text(
                "مهاراتك وأهدافك",
                style = MaterialTheme.typography.titleSmall,
                color = FocusColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (reminder.enabled) "تذكير المهارات مفعّل يوميًا الساعة ${formatMinuteOfDay(reminder.hour * 60 + reminder.minute)} — يفتح شاشة المهارات."
                else "تذكير المهارات متوقف — فعّله من شاشة الإشعارات ليذكّرك بالتدريب يوميًا.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            if (weakestSkills.isEmpty() && activeGoals.isEmpty()) {
                Text(
                    "أضف أول مهارة أو هدف من قسمي المهارات والأهداف ليظهر تقدّمك هنا.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
            }
            weakestSkills.forEach { skill ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            skill.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FocusColors.TextPrimary
                        )
                        Text(
                            "مستوى ${skill.level} من ${skill.targetLevel} · ${skill.minutes} دقيقة تدريب",
                            style = MaterialTheme.typography.bodySmall,
                            color = FocusColors.TextSecondary
                        )
                    }
                    Box(
                        Modifier.width(72.dp)
                            .height(FocusSpacing.progressHeight)
                            .clip(RoundedCornerShape(5.dp))
                            .background(FocusColors.SurfaceHigh)
                    ) {
                        Box(
                            Modifier.fillMaxWidth(skill.progress)
                                .height(FocusSpacing.progressHeight)
                                .clip(RoundedCornerShape(5.dp))
                                .background(FocusColors.Teal)
                        )
                    }
                }
            }
            activeGoals.forEach { goal ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            goal.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FocusColors.TextPrimary
                        )
                        Text(
                            "${goal.current} من ${goal.target} ${goal.unit}",
                            style = MaterialTheme.typography.bodySmall,
                            color = FocusColors.TextSecondary
                        )
                    }
                    Box(
                        Modifier.width(72.dp)
                            .height(FocusSpacing.progressHeight)
                            .clip(RoundedCornerShape(5.dp))
                            .background(FocusColors.SurfaceHigh)
                    ) {
                        Box(
                            Modifier.fillMaxWidth(goal.progress)
                                .height(FocusSpacing.progressHeight)
                                .clip(RoundedCornerShape(5.dp))
                                .background(FocusColors.Teal)
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onOpenSkills,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("فتح المهارات") }
                Button(
                    onClick = onOpenGoals,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("فتح الأهداف") }
            }
        }
    }
}

/**
 * مواعيد اشتقاقية لكتل يوم واحد في الخطة (للخريج): تتابعية من 09:00 مع
 * استراحة 10 دقائق بين الكتل. الكتل نفسها بلا وقت ساعة مخزّن، فالوقت عرض
 * منظّم لا موعد محجوز — يُعرض كـ«09:00–09:45» بدل التاريخ فقط.
 *
 * يعيد لكل كتلة (الكتلة، دقيقة البدء، دقيقة النهاية) منذ منتصف الليل.
 */
internal fun planDayWindows(
    blocks: List<PlannedStudyBlock>,
    startMinuteOfDay: Int = 9 * 60,
    breakMinutes: Int = 10
): List<Triple<PlannedStudyBlock, Int, Int>> {
    var cursor = startMinuteOfDay
    return blocks.map { block ->
        val start = cursor
        val end = start + block.minutes
        cursor = end + breakMinutes
        Triple(block, start, end)
    }
}
