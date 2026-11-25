package com.focusstudy.poc

import kotlin.math.roundToInt

/**
 * حساب السعرات وخطط التمارين المدمجة في قسم الصحة.
 *
 * السعرات بمعادلة Mifflin-St Jeor: للذكر 10×الوزن + 6.25×الطول − 5×العمر + 5،
 * وللأنثى −161 بدل +5. ثم TDEE = BMR × معامل النشاط، والهدف اليومي = TDEE مع
 * تعديل الهدف (−500 إنقاص، 0 حفاظ، +300 زيادة) وبحد أدنى 1200 سعرة.
 * التمارين خطط ثابتة مدمجة (جيم/منزل × ذكر/أنثى)، 3 أيام أسبوعيًا.
 */

/** معدل الأيض الأساسي بالسعرات. */
fun bmrKcal(profile: FitnessProfile): Double {
    val base = 10.0 * profile.weightKg + 6.25 * profile.heightCm - 5.0 * profile.age
    return base + if (profile.gender == FitnessGender.MALE) 5.0 else -161.0
}

/** إجمالي الطاقة اليومية (BMR × معامل النشاط). */
fun tdeeKcal(profile: FitnessProfile): Double = bmrKcal(profile) * profile.activity.factor

/** هدف السعرات اليومي حسب الهدف، بحد أدنى 1200 سعرة. */
fun dailyTargetKcal(profile: FitnessProfile): Int =
    (tdeeKcal(profile) + profile.goal.adjustmentKcal).roundToInt().coerceAtLeast(1200)

enum class WorkoutPlace(val key: String, val label: String) {
    GYM("gym", "الجيم"),
    HOME("home", "المنزل");

    companion object {
        fun from(key: String?): WorkoutPlace =
            entries.firstOrNull { it.key == key } ?: GYM
    }
}

/** تمرين واحد: مجموعات وتكرارات وراحة بالثواني. */
data class Exercise(
    val id: String,
    val name: String,
    val sets: Int,
    val reps: String,
    val restSec: Int
)

/** يوم تدريب واحد في الخطة الأسبوعية. */
data class WorkoutDay(
    val dayLabel: String,
    val focus: String,
    val exercises: List<Exercise>
)

/** خطة أسبوعية كاملة (3 أيام) لمكان وجنس معينين. */
data class WorkoutPlan(
    val place: WorkoutPlace,
    val gender: FitnessGender,
    val days: List<WorkoutDay>
)

private fun ex(id: String, name: String, sets: Int, reps: String, restSec: Int) =
    Exercise(id, name, sets, reps, restSec)

/** خطة الجيم للذكر: دفع/سحب/رجلين. */
private fun gymMalePlan(): WorkoutPlan = WorkoutPlan(
    place = WorkoutPlace.GYM,
    gender = FitnessGender.MALE,
    days = listOf(
        WorkoutDay(
            dayLabel = "السبت", focus = "صدر وكتف وتراي",
            exercises = listOf(
                ex("gym_m_a1", "بنش مستوٍ بالبار", 4, "8–12", 90),
                ex("gym_m_a2", "بنش مائل بالدمبل", 3, "10–12", 90),
                ex("gym_m_a3", "ضغط كتف بالبار", 3, "8–10", 90),
                ex("gym_m_a4", "رفرفة جانبية", 3, "12–15", 60),
                ex("gym_m_a5", "تراي بالكابل", 3, "10–12", 60),
                ex("gym_m_a6", "متوازي (دبس)", 3, "8–12", 60)
            )
        ),
        WorkoutDay(
            dayLabel = "الاثنين", focus = "ظهر وباي",
            exercises = listOf(
                ex("gym_m_b1", "سحب علوي (لات)", 4, "8–12", 90),
                ex("gym_m_b2", "تجديف بالبار", 4, "8–12", 90),
                ex("gym_m_b3", "تجديف بالكابل جالس", 3, "10–12", 60),
                ex("gym_m_b4", "سحب وجه بالكابل", 3, "12–15", 60),
                ex("gym_m_b5", "باي بالبار", 3, "10–12", 60),
                ex("gym_m_b6", "باي بالمطرقة", 3, "12", 60)
            )
        ),
        WorkoutDay(
            dayLabel = "الأربعاء", focus = "رجلين وبطن",
            exercises = listOf(
                ex("gym_m_c1", "سكوات بالبار", 4, "8–10", 120),
                ex("gym_m_c2", "ضغط رجل", 3, "10–12", 90),
                ex("gym_m_c3", "ديدليفت روماني", 3, "8–10", 90),
                ex("gym_m_c4", "رجل خلفي (ليج كيرل)", 3, "12", 60),
                ex("gym_m_c5", "سمانة", 4, "15", 45),
                ex("gym_m_c6", "بلانك", 3, "45 ثانية", 45)
            )
        )
    )
)

/** خطة الجيم للأنثى: تركيز أكبر على الأرداف والبطن مع الجزء العلوي. */
private fun gymFemalePlan(): WorkoutPlan = WorkoutPlan(
    place = WorkoutPlace.GYM,
    gender = FitnessGender.FEMALE,
    days = listOf(
        WorkoutDay(
            dayLabel = "السبت", focus = "رجلين وأرداف",
            exercises = listOf(
                ex("gym_f_a1", "سكوات بالبار", 4, "10–12", 90),
                ex("gym_f_a2", "دفع الورك (هيب ثراست)", 4, "10–12", 90),
                ex("gym_f_a3", "ضغط رجل", 3, "12", 90),
                ex("gym_f_a4", "طعنات مشي", 3, "12 لكل رجل", 60),
                ex("gym_f_a5", "ركل خلفي بالكابل", 3, "15", 45),
                ex("gym_f_a6", "جسر الأرداف", 3, "15", 45)
            )
        ),
        WorkoutDay(
            dayLabel = "الاثنين", focus = "علوي وبطن",
            exercises = listOf(
                ex("gym_f_b1", "سحب علوي (لات)", 3, "10–12", 90),
                ex("gym_f_b2", "تجديف جالس", 3, "10–12", 60),
                ex("gym_f_b3", "ضغط كتف بالدمبل", 3, "10–12", 60),
                ex("gym_f_b4", "رفرفة جانبية", 3, "15", 45),
                ex("gym_f_b5", "بلانك", 3, "40 ثانية", 45),
                ex("gym_f_b6", "الحشرة الميتة (ديد بج)", 3, "12", 45)
            )
        ),
        WorkoutDay(
            dayLabel = "الأربعاء", focus = "جسم كامل",
            exercises = listOf(
                ex("gym_f_c1", "سكوات بالدمبل (جوبلت)", 3, "12", 90),
                ex("gym_f_c2", "ديدليفت روماني بالدمبل", 3, "12", 90),
                ex("gym_f_c3", "ضغط مائل", 3, "10", 60),
                ex("gym_f_c4", "صعود على صندوق", 3, "12 لكل رجل", 60),
                ex("gym_f_c5", "لف روسي", 3, "15", 45),
                ex("gym_f_c6", "ثبات جسر الأرداف", 3, "30 ثانية", 45)
            )
        )
    )
)

/** خطة المنزل للذكر: وزن الجسم فقط. */
private fun homeMalePlan(): WorkoutPlan = WorkoutPlan(
    place = WorkoutPlace.HOME,
    gender = FitnessGender.MALE,
    days = listOf(
        WorkoutDay(
            dayLabel = "السبت", focus = "صدر وتراي",
            exercises = listOf(
                ex("home_m_a1", "ضغط عادي", 4, "12–15", 60),
                ex("home_m_a2", "ضغط واسع", 3, "10–12", 60),
                ex("home_m_a3", "ضغط مثلث (تراي)", 3, "8–10", 60),
                ex("home_m_a4", "ضغط بايك (كتف)", 3, "8–10", 60),
                ex("home_m_a5", "متوازي على كرسي", 3, "10–12", 60),
                ex("home_m_a6", "بلانك متحرك", 3, "10", 45)
            )
        ),
        WorkoutDay(
            dayLabel = "الاثنين", focus = "رجلين وبطن",
            exercises = listOf(
                ex("home_m_b1", "سكوات حر", 4, "15–20", 60),
                ex("home_m_b2", "سكوات بلغاري", 3, "10 لكل رجل", 60),
                ex("home_m_b3", "طعنات", 3, "12 لكل رجل", 60),
                ex("home_m_b4", "جسر الأرداف", 3, "15", 45),
                ex("home_m_b5", "سمانة", 4, "20", 45),
                ex("home_m_b6", "رفع الرجلين", 3, "12", 45)
            )
        ),
        WorkoutDay(
            dayLabel = "الأربعاء", focus = "ظهر وبطن",
            exercises = listOf(
                ex("home_m_c1", "سحب سوبرمان", 3, "12", 60),
                ex("home_m_c2", "ملاك الثلج العكسي", 3, "12", 45),
                ex("home_m_c3", "تجديف بمنشفة على الباب", 3, "10–12", 60),
                ex("home_m_c4", "بلانك", 3, "45 ثانية", 45),
                ex("home_m_c5", "تسلق الجبال", 3, "30 ثانية", 45),
                ex("home_m_c6", "الحشرة الميتة", 3, "12", 45)
            )
        )
    )
)

/** خطة المنزل للأنثى: وزن الجسم مع تركيز على الأرداف والبطن والمرونة. */
private fun homeFemalePlan(): WorkoutPlan = WorkoutPlan(
    place = WorkoutPlace.HOME,
    gender = FitnessGender.FEMALE,
    days = listOf(
        WorkoutDay(
            dayLabel = "السبت", focus = "أرداف ورجلين",
            exercises = listOf(
                ex("home_f_a1", "جسر الأرداف", 4, "15", 45),
                ex("home_f_a2", "سكوات حر", 3, "15", 60),
                ex("home_f_a3", "رفسات الحمار", 3, "15 لكل رجل", 45),
                ex("home_f_a4", "صنبور النار", 3, "15 لكل رجل", 45),
                ex("home_f_a5", "رفع الرجل الجانبي", 3, "15 لكل رجل", 45),
                ex("home_f_a6", "سمانة", 3, "20", 45)
            )
        ),
        WorkoutDay(
            dayLabel = "الاثنين", focus = "بطن وعلوي",
            exercises = listOf(
                ex("home_f_b1", "بلانك", 3, "40 ثانية", 45),
                ex("home_f_b2", "لف روسي", 3, "15", 45),
                ex("home_f_b3", "ضغط على الركبتين", 3, "10–12", 60),
                ex("home_f_b4", "الطائر والكلب", 3, "10 لكل جانب", 45),
                ex("home_f_b5", "الحشرة الميتة", 3, "12", 45),
                ex("home_f_b6", "دوائر الذراعين", 3, "30 ثانية", 30)
            )
        ),
        WorkoutDay(
            dayLabel = "الأربعاء", focus = "جسم كامل ومرونة",
            exercises = listOf(
                ex("home_f_c1", "سكوات مع رفع السمانة", 3, "12", 60),
                ex("home_f_c2", "طعنات مشي", 3, "10 لكل رجل", 60),
                ex("home_f_c3", "ضغط مائل على طاولة", 3, "10", 60),
                ex("home_f_c4", "ثبات جسر الأرداف", 3, "30 ثانية", 45),
                ex("home_f_c5", "القطة والبقرة", 3, "10", 30),
                ex("home_f_c6", "وضعية الطفل", 3, "30 ثانية", 30)
            )
        )
    )
)

/** الخطة الأسبوعية حسب المكان والجنس. */
fun workoutPlan(place: WorkoutPlace, gender: FitnessGender): WorkoutPlan =
    when (place) {
        WorkoutPlace.GYM -> when (gender) {
            FitnessGender.MALE -> gymMalePlan()
            FitnessGender.FEMALE -> gymFemalePlan()
        }
        WorkoutPlace.HOME -> when (gender) {
            FitnessGender.MALE -> homeMalePlan()
            FitnessGender.FEMALE -> homeFemalePlan()
        }
    }
