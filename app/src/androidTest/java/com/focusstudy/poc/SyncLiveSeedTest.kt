package com.focusstudy.poc

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * الاختبار الحي — الجزء 1 (أندرويد → الخادم).
 *
 * يعمل على محاكي API 34 في CI (workflow sync-live-test.yml) ضد نقطة النهاية
 * الحية /api/sync، بملف اختبار معزول جديد. يزرع المجموعات العشر عبر دوال
 * الكتابة الحقيقية في StudyDb، يدفعها عبر SyncAccount، يسحبها، ويتحقق
 * محليًا ومن الخادم مباشرةً. ثم يصدّر بيانات الاعتماد لجزئي ويندوز والتحقق.
 *
 * بلا واجهة: لا يفتح أي Activity — يعمل مباشرة مع StudyDb وSyncAccount.
 */
@RunWith(AndroidJUnit4::class)
class SyncLiveSeedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun seedPushPullRoundTrip() {
        val day = LocalDate.now().toString()
        val passphrase = SyncLiveTestSupport.randomPassphrase()

        // 1) ملف اختبار معزول جديد — لا يمس حساب أحمد إطلاقًا.
        // المصافحة كاملة (تسجيل messages + تسجيل مزامنة مع جولات إعادة
        // تسجيل عند رفض مستمر — انظر SyncLiveTestSupport.handshake).
        val profileId = SyncLiveTestSupport.runLive(300_000) {
            SyncLiveTestSupport.handshake(context, passphrase)
        }
        assertEquals(profileId, UserIdentity.profileId(context))

        // 2) زرع المجموعات العشر عبر دوال الكتابة الحقيقية.
        val db = StudyDb.get(context)
        val subjectId = db.insertSubject(SyncLiveTestSupport.SEED_SUBJECT_NAME, "—", 120)
        val typeId = db.addTaskType(SyncLiveTestSupport.SEED_TYPE_NAME)
        val taskId = db.insertTask(
            title = SyncLiveTestSupport.SEED_TASK_TITLE,
            plannedMinutes = 45,
            blockedPackages = emptySet(),
            category = TaskCategory.STUDY,
            priority = TaskPriority.HIGH,
            status = TaskStatus.TODO,
            dueAt = System.currentTimeMillis() + 86_400_000L,
            subjectId = subjectId,
            typeId = typeId,
        )
        val task2Id = db.insertTask(
            title = SyncLiveTestSupport.SEED_TASK2_TITLE,
            plannedMinutes = 30,
            blockedPackages = emptySet(),
            category = TaskCategory.PERSONAL,
        )
        val habitId = db.addHabit(SyncLiveTestSupport.SEED_HABIT_NAME)
        db.setHabitDone(habitId, day, true)
        assertTrue(db.logWird(day, 2, "البقرة"))
        db.saveHealthLog(HealthLog(day, weight = 70.5, sleepHours = 7.5, waterCups = 6, exerciseMinutes = 30))
        db.addTransaction(MoneyKind.EXPENSE, 150.0, "طعام", "ملاحظة محلية لا تتزامن", day)
        db.addTransaction(MoneyKind.INCOME, 500.0, "راتب", "", day)
        db.logMood(day, 4)
        db.addCalorieEntry(SyncLiveTestSupport.SEED_CALORIE_NAME, 350, day)

        // 3) دفع ثم سحب عبر الكود الحقيقي للمزامنة (مع إعادة محاولة عند بدء بارد).
        SyncLiveTestSupport.runLive {
            assertEquals(
                SyncAccount.Result.SUCCESS,
                SyncLiveTestSupport.retrySync("sync-push") { SyncAccount.push(context, passphrase) },
            )
            assertEquals(
                SyncAccount.Result.SUCCESS,
                SyncLiveTestSupport.retrySync("sync-pull") { SyncAccount.pull(context, passphrase) },
            )
        }

        // 4) تحقق محلي: كل المجموعات العشر موجودة بعد الجولة.
        assertTrue(db.tasks().any { it.id == taskId && it.title == SyncLiveTestSupport.SEED_TASK_TITLE })
        assertTrue(db.tasks().any { it.id == task2Id && it.title == SyncLiveTestSupport.SEED_TASK2_TITLE })
        assertTrue(db.subjects().any { it.id == subjectId && it.name == SyncLiveTestSupport.SEED_SUBJECT_NAME })
        assertTrue(db.taskTypes().any { it.id == typeId && it.name == SyncLiveTestSupport.SEED_TYPE_NAME })
        assertTrue(db.allHabits().any { it.id == habitId && it.name == SyncLiveTestSupport.SEED_HABIT_NAME })
        assertTrue(db.allHabitLogs().any { it.habitId == habitId && it.day == day })
        assertTrue(db.allWirdLogs().any { it.day == day && it.pages == 2 && it.surah == "البقرة" })
        assertTrue(db.allHealthLogs().any { it.day == day && it.weight == 70.5 && it.exerciseMinutes == 30 })
        assertEquals(2, db.allTransactions().count { it.day == day })
        assertTrue(db.allMoodLogs().any { it.day == day && it.mood == 4 })
        assertTrue(db.allCalorieLogs().any { it.day == day && it.name == SyncLiveTestSupport.SEED_CALORIE_NAME })

        // 5) تحقق مستقل من الخادم: سحب خام يثبت أن البيانات وصلت فعلًا (لا عبر منطق التطبيق).
        val raw = SyncLiveTestSupport.rawPull(profileId, passphrase)
        assertTrue("server snapshot has no revision", raw.has("revision"))
        assertTrue(raw.getInt("revision") >= 1)
        val rawText = raw.toString()
        assertTrue(rawText.contains(SyncLiveTestSupport.SEED_TASK_TITLE))
        assertTrue(rawText.contains(SyncLiveTestSupport.SEED_HABIT_NAME))
        assertTrue(rawText.contains(SyncLiveTestSupport.SEED_SUBJECT_NAME))

        // 6) تصدير بيانات الاعتماد لجزئي ويندوز والتحقق.
        val credentials = JSONObject()
            .put("profileId", profileId)
            .put("passphrase", passphrase)
            .put("day", day)
            .put("androidTaskId", taskId)
            .put("habitId", habitId)
        val file = SyncLiveTestSupport.writeCredentials(context, credentials)
        assertTrue(file.exists())
    }
}
