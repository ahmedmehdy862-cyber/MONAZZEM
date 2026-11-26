package com.focusstudy.poc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin

/**
 * رسوم توضيحية مرسومة بـ Compose للتمارين — قرار أحمد (2026-11-26):
 * رسوم فقط، بلا صور فوتوغرافية وبلا أصول خارجية.
 *
 * شخصية عصوية بارامترية: كل تمرين يُرسم من وضعية (زوايا الجذع والذراعين
 * والرجلين + أداة) في جدول [poseByExercise]، فالـ 72 تمرينًا لهم رسم
 * توضيحي دون 72 رسمة يدوية. الألوان من [FocusColors] فقط، فالرسم يقرأ
 * صحيحًا في الفاتح والداكن دون ألوان hard-coded.
 *
 * الزوايا بالدرجات باتفاقية واحدة: 0 = للأسفل، 90 = لليمين، 180 = لأعلى،
 * 270 = لليسار؛ وانحناء المفصل يُضاف لزاوية الطرف. الذراع/الرجل الثانية
 * مرآة الأولى افتراضيًا، وتُحدَّد صراحةً في الوضعيات غير المتناسقة
 * (طعنات، طائر وكلب...).
 */

/** أداة تُرسم مع الشخصية. */
internal enum class Equipment {
    NONE, BARBELL, DUMBBELL, CABLE, CHAIR, BOX, TABLE, BARS, PLATFORM
}

/** أين يستقر البار: في اليدين، أو على الكتفين (سكوات)، أو على الوركين (دفع ورك). */
internal enum class BarAnchor { HANDS, SHOULDERS, HIP }

/** وضعية جسم واحدة — أرقام في مساحة افتراضية 200×160، والأرض عند y=140. */
internal data class FigurePose(
    val hipX: Float = 100f,
    val hipY: Float = 88f,
    val torsoAngle: Float = 180f,
    val torsoLen: Float = 42f,
    val armAngle: Float = 0f,
    val armBend: Float = 0f,
    val armAngle2: Float? = null,
    val armBend2: Float? = null,
    val legAngle: Float = 0f,
    val legBend: Float = 0f,
    val legAngle2: Float? = null,
    val legBend2: Float? = null,
    val thighLen: Float = 26f,
    val shinLen: Float = 26f,
    val upperArmLen: Float = 22f,
    val foreArmLen: Float = 20f,
    val equipment: Equipment = Equipment.NONE,
    val barAnchor: BarAnchor = BarAnchor.HANDS,
    val bench: Boolean = false
)

private fun dir(angleDeg: Float): Offset {
    val r = Math.toRadians(angleDeg.toDouble())
    return Offset(sin(r).toFloat(), cos(r).toFloat())
}

private fun mirrorAngle(angleDeg: Float): Float = (360f - angleDeg) % 360f

private fun Offset.plusScaled(other: Offset, scale: Float) = Offset(x + other.x * scale, y + other.y * scale)

/** مفاصل الشخصية المحسوبة من الوضعية. */
private data class Joints(
    val hip: Offset,
    val shoulder: Offset,
    val head: Offset,
    val elbow: Offset,
    val hand: Offset,
    val elbow2: Offset,
    val hand2: Offset,
    val knee: Offset,
    val ankle: Offset,
    val knee2: Offset,
    val ankle2: Offset
)

private fun jointsOf(pose: FigurePose): Joints {
    val hip = Offset(pose.hipX, pose.hipY)
    val torsoDir = dir(pose.torsoAngle)
    val shoulder = hip.plusScaled(torsoDir, pose.torsoLen)
    val head = shoulder.plusScaled(torsoDir, 13f)
    val armA = pose.armAngle
    val armB = armA + pose.armBend
    val armA2 = pose.armAngle2 ?: mirrorAngle(armA)
    val armB2 = armA2 + (pose.armBend2 ?: pose.armBend)
    val elbow = shoulder.plusScaled(dir(armA), pose.upperArmLen)
    val hand = elbow.plusScaled(dir(armB), pose.foreArmLen)
    val elbow2 = shoulder.plusScaled(dir(armA2), pose.upperArmLen)
    val hand2 = elbow2.plusScaled(dir(armB2), pose.foreArmLen)
    val legA = pose.legAngle
    val legB = legA + pose.legBend
    val legA2 = pose.legAngle2 ?: mirrorAngle(legA)
    val legB2 = legA2 + (pose.legBend2 ?: pose.legBend)
    val knee = hip.plusScaled(dir(legA), pose.thighLen)
    val ankle = knee.plusScaled(dir(legB), pose.shinLen)
    val knee2 = hip.plusScaled(dir(legA2), pose.thighLen)
    val ankle2 = knee2.plusScaled(dir(legB2), pose.shinLen)
    return Joints(hip, shoulder, head, elbow, hand, elbow2, hand2, knee, ankle, knee2, ankle2)
}

private fun DrawScope.line(a: Offset, b: Offset, color: Color, width: Float, scale: Float, ox: Float, oy: Float) {
    drawLine(color, Offset(a.x * scale + ox, a.y * scale + oy), Offset(b.x * scale + ox, b.y * scale + oy), width * scale, StrokeCap.Round)
}

private fun DrawScope.drawPose(pose: FigurePose) {
    val scale = minOf(size.width / 200f, size.height / 160f)
    val ox = (size.width - 200f * scale) / 2f
    val oy = (size.height - 160f * scale) / 2f
    val j = jointsOf(pose)
    val figure = FocusColors.TextPrimary
    val accent = FocusColors.onTint(FocusColors.Blue)
    val muted = FocusColors.TextMuted
    // الأرض.
    drawLine(muted, Offset(8f * scale + ox, 140f * scale + oy), Offset(192f * scale + ox, 140f * scale + oy), 2.5f * scale, StrokeCap.Round)
    // الأداة أولًا (خلف الجسم). البنش يُرسم عبر علامة pose.bench لا عبر
    // Equipment (فالبنش يجتمع مع البار في تمرين واحد).
    when (pose.equipment) {
        Equipment.CHAIR -> {
            val ax = j.ankle2.x
            val ay = j.ankle2.y
            drawLine(accent, Offset((ax - 14f) * scale + ox, ay * scale + oy), Offset((ax + 14f) * scale + ox, ay * scale + oy), 5f * scale, StrokeCap.Round)
            drawLine(accent, Offset((ax - 11f) * scale + ox, ay * scale + oy), Offset((ax - 11f) * scale + ox, 140f * scale + oy), 3f * scale, StrokeCap.Round)
            drawLine(accent, Offset((ax + 11f) * scale + ox, ay * scale + oy), Offset((ax + 11f) * scale + ox, 140f * scale + oy), 3f * scale, StrokeCap.Round)
        }
        Equipment.BOX -> {
            val raised = if (j.ankle.y <= j.ankle2.y) j.ankle else j.ankle2
            drawLine(accent, Offset((raised.x - 14f) * scale + ox, raised.y * scale + oy), Offset((raised.x + 14f) * scale + ox, raised.y * scale + oy), 5f * scale, StrokeCap.Round)
            drawLine(accent, Offset((raised.x - 14f) * scale + ox, raised.y * scale + oy), Offset((raised.x - 14f) * scale + ox, 140f * scale + oy), 3f * scale, StrokeCap.Round)
            drawLine(accent, Offset((raised.x + 14f) * scale + ox, raised.y * scale + oy), Offset((raised.x + 14f) * scale + ox, 140f * scale + oy), 3f * scale, StrokeCap.Round)
        }
        Equipment.TABLE -> {
            val hy = minOf(j.hand.y, j.hand2.y)
            val hx = (j.hand.x + j.hand2.x) / 2f
            drawLine(accent, Offset((hx - 26f) * scale + ox, hy * scale + oy), Offset((hx + 26f) * scale + ox, hy * scale + oy), 5f * scale, StrokeCap.Round)
            drawLine(accent, Offset((hx - 22f) * scale + ox, hy * scale + oy), Offset((hx - 22f) * scale + ox, 140f * scale + oy), 3f * scale, StrokeCap.Round)
            drawLine(accent, Offset((hx + 22f) * scale + ox, hy * scale + oy), Offset((hx + 22f) * scale + ox, 140f * scale + oy), 3f * scale, StrokeCap.Round)
        }
        Equipment.BARS -> {
            val hy = (j.hand.y + j.hand2.y) / 2f
            drawLine(accent, Offset((j.hip.x - 48f) * scale + ox, hy * scale + oy), Offset((j.hip.x + 48f) * scale + ox, hy * scale + oy), 5f * scale, StrokeCap.Round)
        }
        Equipment.PLATFORM -> {
            val ax = (j.ankle.x + j.ankle2.x) / 2f
            val ay = (j.ankle.y + j.ankle2.y) / 2f
            drawLine(accent, Offset((ax - 24f) * scale + ox, (ay - 14f) * scale + oy), Offset((ax + 24f) * scale + ox, (ay + 14f) * scale + oy), 6f * scale, StrokeCap.Round)
        }
        Equipment.CABLE -> {
            val hx = (j.hand.x + j.hand2.x) / 2f
            val hy = (j.hand.y + j.hand2.y) / 2f
            drawLine(accent, Offset(hx * scale + ox, 2f * scale + oy), Offset(hx * scale + ox, hy * scale + oy), 2.5f * scale, StrokeCap.Round)
            drawCircle(accent, 4f * scale, Offset(hx * scale + ox, hy * scale + oy))
        }
        else -> Unit
    }
    if (pose.bench) {
        val top = j.hip.y + 8f
        drawLine(accent, Offset((j.hip.x - 55f) * scale + ox, top * scale + oy), Offset((j.hip.x + 55f) * scale + ox, top * scale + oy), 6f * scale, StrokeCap.Round)
        drawLine(accent, Offset((j.hip.x - 45f) * scale + ox, top * scale + oy), Offset((j.hip.x - 45f) * scale + ox, 140f * scale + oy), 3f * scale, StrokeCap.Round)
        drawLine(accent, Offset((j.hip.x + 45f) * scale + ox, top * scale + oy), Offset((j.hip.x + 45f) * scale + ox, 140f * scale + oy), 3f * scale, StrokeCap.Round)
    }
    // الجسم: جذع وذراعان ورجلان + رأس.
    line(j.hip, j.shoulder, figure, 7f, scale, ox, oy)
    line(j.shoulder, j.elbow, figure, 6f, scale, ox, oy)
    line(j.elbow, j.hand, figure, 6f, scale, ox, oy)
    line(j.shoulder, j.elbow2, figure, 6f, scale, ox, oy)
    line(j.elbow2, j.hand2, figure, 6f, scale, ox, oy)
    line(j.hip, j.knee, figure, 7f, scale, ox, oy)
    line(j.knee, j.ankle, figure, 7f, scale, ox, oy)
    line(j.hip, j.knee2, figure, 7f, scale, ox, oy)
    line(j.knee2, j.ankle2, figure, 7f, scale, ox, oy)
    drawCircle(figure, 10f * scale, Offset(j.head.x * scale + ox, j.head.y * scale + oy))
    // البار والدمبل بعد الجسم (فوق اليدين).
    when (pose.equipment) {
        Equipment.BARBELL -> {
            val anchor = when (pose.barAnchor) {
                BarAnchor.SHOULDERS -> j.shoulder
                BarAnchor.HIP -> j.hip
                BarAnchor.HANDS -> Offset((j.hand.x + j.hand2.x) / 2f, (j.hand.y + j.hand2.y) / 2f)
            }
            val y = anchor.y
            val cx = anchor.x
            drawLine(accent, Offset((cx - 30f) * scale + ox, y * scale + oy), Offset((cx + 30f) * scale + ox, y * scale + oy), 5f * scale, StrokeCap.Round)
            drawCircle(accent, 6f * scale, Offset((cx - 30f) * scale + ox, y * scale + oy))
            drawCircle(accent, 6f * scale, Offset((cx + 30f) * scale + ox, y * scale + oy))
        }
        Equipment.DUMBBELL -> {
            drawCircle(accent, 6f * scale, Offset(j.hand.x * scale + ox, j.hand.y * scale + oy))
            drawCircle(accent, 6f * scale, Offset(j.hand2.x * scale + ox, j.hand2.y * scale + oy))
        }
        else -> Unit
    }
}

// ——— الوضعيات المشتركة ———

private val POSE_STAND = FigurePose()
private val POSE_PRESS_OVERHEAD = FigurePose(armAngle = 180f, equipment = Equipment.BARBELL)
private val POSE_CURL = FigurePose(armAngle = 0f, armBend = 150f, equipment = Equipment.BARBELL)
private val POSE_HAMMER_CURL = FigurePose(armAngle = 0f, armBend = 140f, equipment = Equipment.DUMBBELL)
private val POSE_LATERAL_RAISE = FigurePose(armAngle = 90f, equipment = Equipment.DUMBBELL)
private val POSE_ARM_CIRCLES = FigurePose(armAngle = 90f)
private val POSE_SQUAT = FigurePose(hipY = 100f, torsoAngle = 170f, armAngle = 200f, armBend = -60f, legAngle = 35f, legBend = -45f, equipment = Equipment.BARBELL, barAnchor = BarAnchor.SHOULDERS)
private val POSE_DEADLIFT = FigurePose(hipY = 92f, torsoAngle = 130f, armAngle = 20f, legAngle = 25f, legBend = -25f, equipment = Equipment.BARBELL)
private val POSE_ROW_BENT = FigurePose(torsoAngle = 100f, armAngle = 60f, armBend = 100f, legAngle = 15f, legBend = -10f, equipment = Equipment.BARBELL)
private val POSE_LUNGE = FigurePose(hipY = 95f, legAngle = 40f, legBend = -30f, legAngle2 = 320f, legBend2 = 20f)
private val POSE_BULGARIAN = FigurePose(hipY = 95f, legAngle = 40f, legBend = -30f, legAngle2 = 320f, legBend2 = -50f, equipment = Equipment.CHAIR)
private val POSE_LEG_PRESS = FigurePose(hipX = 60f, hipY = 110f, torsoAngle = 100f, armAngle = 40f, legAngle = 45f, equipment = Equipment.PLATFORM)
private val POSE_LEG_CURL = FigurePose(hipY = 115f, torsoAngle = 90f, armAngle = 60f, legAngle = 270f, legBend = -120f)
private val POSE_BENCH_PRESS = FigurePose(hipY = 118f, torsoAngle = 90f, armAngle = 170f, legAngle = 20f, legBend = 70f, equipment = Equipment.BARBELL, bench = true)
private val POSE_LAT_PULLDOWN = FigurePose(hipY = 110f, armAngle = 150f, armBend = 20f, legAngle = 60f, legBend = -60f, thighLen = 22f, shinLen = 20f, equipment = Equipment.CABLE)
private val POSE_TRICEP_PUSHDOWN = FigurePose(armAngle = 40f, armBend = -30f, equipment = Equipment.CABLE)
private val POSE_FACE_PULL = FigurePose(armAngle = 120f, armBend = 60f, equipment = Equipment.CABLE)
private val POSE_DIP = FigurePose(hipY = 95f, armAngle = 330f, armBend = 30f, legAngle = 20f, legBend = 40f, equipment = Equipment.BARS)
private val POSE_HIP_THRUST = FigurePose(hipY = 108f, torsoAngle = 70f, legAngle = 90f, equipment = Equipment.BARBELL, barAnchor = BarAnchor.HIP)
private val POSE_GLUTE_BRIDGE = FigurePose(hipY = 105f, torsoAngle = 90f, legAngle = 40f, legBend = -40f, thighLen = 22f, shinLen = 22f)
private val POSE_STEP_UP = FigurePose(legAngle = 45f, legBend = -20f, legAngle2 = 0f, equipment = Equipment.BOX)
private val POSE_PUSH_UP = FigurePose(hipY = 108f, torsoAngle = 90f, armAngle = 40f, armBend = 40f, legAngle = 270f)
private val POSE_KNEE_PUSH_UP = FigurePose(hipY = 105f, torsoAngle = 90f, armAngle = 40f, armBend = 40f, legAngle = 20f, legBend = 60f, thighLen = 20f, shinLen = 20f)
private val POSE_PIKE_PUSH_UP = FigurePose(hipY = 110f, torsoAngle = 150f, armAngle = 0f, armBend = 30f, legAngle = 300f)
private val POSE_INCLINE_PUSH_UP = FigurePose(hipY = 100f, torsoAngle = 80f, armAngle = 30f, armBend = 20f, legAngle = 270f, equipment = Equipment.TABLE)
private val POSE_PLANK = FigurePose(hipY = 100f, torsoAngle = 90f, armAngle = 10f, legAngle = 270f)
private val POSE_SUPERMAN = FigurePose(hipY = 118f, torsoAngle = 90f, armAngle = 120f, legAngle = 250f, legBend = -30f)
private val POSE_SNOW_ANGEL = FigurePose(hipY = 118f, torsoAngle = 90f, armAngle = 100f, legAngle = 250f, legBend = -30f)
private val POSE_MOUNTAIN_CLIMBER = FigurePose(hipY = 100f, torsoAngle = 90f, armAngle = 10f, legAngle = 270f, legAngle2 = 60f, legBend2 = 60f)
private val POSE_LEG_RAISE = FigurePose(hipY = 120f, torsoAngle = 90f, legAngle = 180f)
private val POSE_DEAD_BUG = FigurePose(hipY = 118f, torsoAngle = 90f, armAngle = 160f, legAngle = 200f, legBend = 20f)
private val POSE_RUSSIAN_TWIST = FigurePose(hipY = 115f, torsoAngle = 150f, armAngle = 60f, armBend = 20f, legAngle = 30f, legBend = 30f, thighLen = 22f, shinLen = 22f)
private val POSE_DONKEY_KICK = FigurePose(hipY = 95f, torsoAngle = 90f, armAngle = 10f, legAngle = 20f, legBend = 60f, legAngle2 = 300f, legBend2 = -20f)
private val POSE_FIRE_HYDRANT = FigurePose(hipY = 95f, torsoAngle = 90f, armAngle = 10f, legAngle = 20f, legBend = 60f, legAngle2 = 320f, legBend2 = 40f)
private val POSE_SIDE_LEG_RAISE = FigurePose(hipY = 125f, torsoAngle = 90f, armAngle = 90f, legAngle = 270f, legAngle2 = 200f)
private val POSE_BIRD_DOG = FigurePose(hipY = 95f, torsoAngle = 90f, armAngle = 120f, armAngle2 = 10f, legAngle = 270f, legAngle2 = 290f, legBend2 = -40f)
private val POSE_CAT_COW = FigurePose(hipY = 95f, torsoAngle = 90f, armAngle = 10f, legAngle = 20f, legBend = 60f)
private val POSE_CHILD = FigurePose(hipX = 95f, hipY = 122f, torsoAngle = 55f, armAngle = 100f, legAngle = 260f, legBend = 10f)
private val POSE_SEATED_ROW = FigurePose(hipY = 105f, torsoAngle = 175f, armAngle = 40f, armBend = 80f, legAngle = 60f, legBend = -50f, thighLen = 22f, shinLen = 22f)
// نسخ بأدوات مختلفة عن الأصل (data class فيسمح copy):
private val POSE_BENCH_PRESS_DB = POSE_BENCH_PRESS.copy(equipment = Equipment.DUMBBELL)
private val POSE_PRESS_DB = POSE_PRESS_OVERHEAD.copy(equipment = Equipment.DUMBBELL)
private val POSE_DEADLIFT_DB = POSE_DEADLIFT.copy(equipment = Equipment.DUMBBELL)
private val POSE_ROW_BODYWEIGHT = POSE_ROW_BENT.copy(equipment = Equipment.NONE)
private val POSE_DIP_BODYWEIGHT = POSE_DIP.copy(equipment = Equipment.NONE)
private val POSE_SQUAT_GOBLET = FigurePose(hipY = 100f, torsoAngle = 170f, armAngle = 60f, armBend = 40f, legAngle = 35f, legBend = -45f, equipment = Equipment.DUMBBELL)
private val POSE_KICKBACK_STAND = FigurePose(torsoAngle = 175f, legAngle2 = 300f, legBend2 = -20f)

/**
 * وضعية الرسم لتمرين بمعرّفه؛ وضعية الوقوف الافتراضية إن لم تُسجَّل واحدة
 * (لا يحدث — اختبار التغطية يفرض تسجيل الـ 72 كلها).
 */
internal fun exercisePoseFor(id: String): FigurePose = poseByExercise[id] ?: POSE_STAND

/** هل للتمرين وضعية مسجّلة صراحةً (يستخدمها اختبار التغطية)؟ */
internal fun hasExercisePose(id: String): Boolean = poseByExercise.containsKey(id)

// ——— خريطة التمارين الـ 72 إلى وضعياتها ———

private val poseByExercise: Map<String, FigurePose> = mapOf(
    "gym_m_a1" to POSE_BENCH_PRESS,
    "gym_m_a2" to POSE_BENCH_PRESS_DB,
    "gym_m_a3" to POSE_PRESS_OVERHEAD,
    "gym_m_a4" to POSE_LATERAL_RAISE,
    "gym_m_a5" to POSE_TRICEP_PUSHDOWN,
    "gym_m_a6" to POSE_DIP,
    "gym_m_b1" to POSE_LAT_PULLDOWN,
    "gym_m_b2" to POSE_ROW_BENT,
    "gym_m_b3" to POSE_SEATED_ROW,
    "gym_m_b4" to POSE_FACE_PULL,
    "gym_m_b5" to POSE_CURL,
    "gym_m_b6" to POSE_HAMMER_CURL,
    "gym_m_c1" to POSE_SQUAT,
    "gym_m_c2" to POSE_LEG_PRESS,
    "gym_m_c3" to POSE_DEADLIFT,
    "gym_m_c4" to POSE_LEG_CURL,
    "gym_m_c5" to POSE_STAND,
    "gym_m_c6" to POSE_PLANK,
    "gym_f_a1" to POSE_SQUAT,
    "gym_f_a2" to POSE_HIP_THRUST,
    "gym_f_a3" to POSE_LEG_PRESS,
    "gym_f_a4" to POSE_LUNGE,
    "gym_f_a5" to POSE_KICKBACK_STAND,
    "gym_f_a6" to POSE_GLUTE_BRIDGE,
    "gym_f_b1" to POSE_LAT_PULLDOWN,
    "gym_f_b2" to POSE_SEATED_ROW,
    "gym_f_b3" to POSE_PRESS_DB,
    "gym_f_b4" to POSE_LATERAL_RAISE,
    "gym_f_b5" to POSE_PLANK,
    "gym_f_b6" to POSE_DEAD_BUG,
    "gym_f_c1" to POSE_SQUAT_GOBLET,
    "gym_f_c2" to POSE_DEADLIFT_DB,
    "gym_f_c3" to POSE_BENCH_PRESS_DB,
    "gym_f_c4" to POSE_STEP_UP,
    "gym_f_c5" to POSE_RUSSIAN_TWIST,
    "gym_f_c6" to POSE_GLUTE_BRIDGE,
    "home_m_a1" to POSE_PUSH_UP,
    "home_m_a2" to POSE_PUSH_UP,
    "home_m_a3" to POSE_PUSH_UP,
    "home_m_a4" to POSE_PIKE_PUSH_UP,
    "home_m_a5" to POSE_DIP_BODYWEIGHT,
    "home_m_a6" to POSE_PLANK,
    "home_m_b1" to POSE_SQUAT,
    "home_m_b2" to POSE_BULGARIAN,
    "home_m_b3" to POSE_LUNGE,
    "home_m_b4" to POSE_GLUTE_BRIDGE,
    "home_m_b5" to POSE_STAND,
    "home_m_b6" to POSE_LEG_RAISE,
    "home_m_c1" to POSE_SUPERMAN,
    "home_m_c2" to POSE_SNOW_ANGEL,
    "home_m_c3" to POSE_ROW_BODYWEIGHT,
    "home_m_c4" to POSE_PLANK,
    "home_m_c5" to POSE_MOUNTAIN_CLIMBER,
    "home_m_c6" to POSE_DEAD_BUG,
    "home_f_a1" to POSE_GLUTE_BRIDGE,
    "home_f_a2" to POSE_SQUAT,
    "home_f_a3" to POSE_DONKEY_KICK,
    "home_f_a4" to POSE_FIRE_HYDRANT,
    "home_f_a5" to POSE_SIDE_LEG_RAISE,
    "home_f_a6" to POSE_STAND,
    "home_f_b1" to POSE_PLANK,
    "home_f_b2" to POSE_RUSSIAN_TWIST,
    "home_f_b3" to POSE_KNEE_PUSH_UP,
    "home_f_b4" to POSE_BIRD_DOG,
    "home_f_b5" to POSE_DEAD_BUG,
    "home_f_b6" to POSE_ARM_CIRCLES,
    "home_f_c1" to POSE_SQUAT,
    "home_f_c2" to POSE_LUNGE,
    "home_f_c3" to POSE_INCLINE_PUSH_UP,
    "home_f_c4" to POSE_GLUTE_BRIDGE,
    "home_f_c5" to POSE_CAT_COW,
    "home_f_c6" to POSE_CHILD
)

/** الرسم التوضيحي لتمرين: شخصية عصوية بوضعية التمرين وأداته. */
@Composable
internal fun ExerciseArt(exerciseId: String, modifier: Modifier = Modifier) {
    val pose = exercisePoseFor(exerciseId)
    Canvas(modifier = modifier.fillMaxWidth().aspectRatio(200f / 160f)) {
        drawPose(pose)
    }
}
