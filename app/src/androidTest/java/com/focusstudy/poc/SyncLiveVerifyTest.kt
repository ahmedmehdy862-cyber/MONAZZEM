package com.focusstudy.poc

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * الاختبار الحي — الجزء 3 (ويندوز → أندرويد).
 *
 * يعمل على محاكي جديد (قاعدة بيانات محلية فارغة) في مهمة android-verify،
 * بعد مهمة ويندوز. بيانات الاعتماد تُدفع إلى filesDir قبل التشغيل
 * (انظر workflow sync-live-test.yml). يسحب اللقطة المدموجة ويثبت:
 * مهمة ويندوز وصلت، ومهمة أندرويد اكتملت على ويندوز، وتومبستون سجل
 * العادة طُبّق، وباقي بيانات أندرويد سليمة — وأن ملاحظة المعاملة لم
 * تعبر السلك أبدًا (على جهاز جديد تصل المعاملة بلا ملاحظة).
 */
@RunWith(AndroidJUnit4::class)
class SyncLiveVerifyTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun windowsChangesArriveAndAndroidDataSurvives() {
        val credentials = SyncLiveTestSupport.readCredentials(context)
        val profileId = credentials.getString("profileId")
        val passphrase = credentials.getString("passphrase")
        val day = credentials.getString("day")
        val androidTaskId = credentials.getLong("androidTaskId")
        val habitId = credentials.getLong("habitId")
        UserIdentity.storeProfileId(context, profileId)

        SyncLiveTestSupport.runLive {
            assertEquals(
                SyncAccount.Result.SUCCESS,
                SyncLiveTestSupport.retrySync("sync-pull") { SyncAccount.pull(context, passphrase) },
            )
        }

        val db = StudyDb.get(context)
        val tasks = db.tasks()

        // تشخيص قبل الـ assertions: اللقطة المسحوبة مقابل القاعدة المحلية —
        // يحدد إن كان الخلل في السحب أم في التطبيق عند أي فشل لاحق.
        SyncLiveTestSupport.dumpSyncDiag(profileId, passphrase, tasks)

        // مهمة ويندوز وصلت — بمعرّف سالب (عقد §7: معرّفات ويندوز سالبة).
        val winTask = tasks.firstOrNull { it.title == SyncLiveTestSupport.WINDOWS_TASK_TITLE }
        assertNotNull("Windows task did not arrive on Android", winTask)
        assertTrue("Windows task id must be negative, got ${winTask!!.id}", winTask.id < 0)

        // مهمة أندرويد اكتملت على ويندوز.
        val androidTask = tasks.firstOrNull { it.id == androidTaskId }
        assertNotNull("Android task did not arrive back", androidTask)
        assertEquals(TaskStatus.DONE, androidTask!!.status)
        assertTrue(androidTask.isDone)

        // تومبستون سجل العادة: أُلغي على ويندوز فغاب بعد السحب.
        assertTrue(
            "habit log tombstone was not applied",
            db.allHabitLogs().none { it.habitId == habitId && it.day == day }
        )

        // باقي بيانات أندرويد سليمة بعد الجولة الكاملة.
        assertTrue(tasks.any { it.title == SyncLiveTestSupport.SEED_TASK2_TITLE })
        assertTrue(db.subjects().any { it.name == SyncLiveTestSupport.SEED_SUBJECT_NAME })
        assertTrue(db.taskTypes().any { it.name == SyncLiveTestSupport.SEED_TYPE_NAME })
        assertTrue(db.allHabits().any { it.id == habitId && it.name == SyncLiveTestSupport.SEED_HABIT_NAME })
        assertTrue(db.allWirdLogs().any { it.day == day && it.pages == 2 && it.surah == "البقرة" })
        assertTrue(db.allHealthLogs().any { it.day == day && it.weight == 70.5 })
        val txs = db.allTransactions().filter { it.day == day }
        assertEquals(2, txs.size)
        // الخصوصية: ملاحظة المعاملة لا تتزامن أبدًا — على جهاز جديد تصل بلا ملاحظة.
        val expense = txs.first { it.kind == MoneyKind.EXPENSE }
        assertTrue("transaction note must never cross the wire", expense.note.isBlank())
        assertTrue(db.allMoodLogs().any { it.day == day && it.mood == 4 })
        assertTrue(db.allCalorieLogs().any { it.day == day && it.name == SyncLiveTestSupport.SEED_CALORIE_NAME })
    }
}
