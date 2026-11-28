package com.focusstudy.poc.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * الاختبار الحي — الجزء 2 (أندرويد → ويندوز → الخادم).
 *
 * يعمل على windows-latest في CI (workflow sync-live-test.yml) ضد نقطة
 * النهاية الحية /api/sync، على ملف الاختبار المعزول نفسه الذي أنشأه
 * اختبار أندرويد. يثبت بالكود الحقيقي (SyncClient + SyncService +
 * DesktopRepository):
 *  1) الربط لحساب موجود ينجح — إعادة المحاولة بـ currentPassphrase عند 401.
 *  2) المجموعات العشر من أندرويد تصل وتُطبَّق في قاعدة ويندوز.
 *  3) تعديلات ويندوز (مهمة جديدة بمعرّف سالب + إكمال مهمة أندرويد +
 *     حذف سجل عادة = تومبستون) تُدفع وتصل إلى الخادم فعلًا.
 */
class SyncLiveWindowsTest {

    @Test
    fun androidDataArrivesAndWindowsChangesPushBack() {
        val credentials = SyncLiveWindowsSupport.readCredentials()
        val (repo, session, service) = SyncLiveWindowsSupport.openRepoAndSession(credentials)
        val client = SyncClient()

        // 1) الربط لحساب موجود: أول محاولة بلا currentPassphrase ترد 401،
        //    فيعيد العميل المحاولة تلقائيًا بها — إصلاح مسار الربط.
        val registered = client.register(credentials.profileId, credentials.passphrase)
        assertTrue("linking Windows to the phone-registered account must succeed, got $registered",
            registered.outcome is SyncOutcome.Ok)

        // 2) سحب وتطبيق بيانات أندرويد (المجموعات العشر).
        val first = service.syncNow(session)
        assertTrue("first syncNow must succeed, got $first", first is SyncOutcome.Ok)

        // تشخيص (يظهر في system-out بتقرير XML): ماذا أعاد السحب فعلًا،
        // وماذا في القاعدة بعده — يحدد إن كان الخلل في السحب أم التطبيق.
        val probe = client.pull(credentials.profileId, credentials.passphrase)
        val probeSnap = (probe as? SyncOutcome.Ok)?.snapshot
        println("SyncLive probe: pull ok=${probe is SyncOutcome.Ok} revision=${probeSnap?.revision} " +
            "serverTasks=${probeSnap?.data?.tasks?.map { it.id to it.title }} " +
            "serverSubjects=${probeSnap?.data?.subjects?.map { it.id to it.name }}")
        println("SyncLive probe: repo tasks=${repo.tasks().map { it.id to it.title }} " +
            "subjects=${repo.subjects().map { it.id to it.name }}")

        assertEquals(
            "tasks after first sync: ${repo.tasks().map { it.id to it.title }}",
            2, repo.tasks().size)
        assertTrue(repo.tasks().any { it.title == SyncLiveWindowsSupport.ANDROID_TASK_TITLE })
        assertTrue(repo.tasks().any { it.title == "مهمة ثانية — اختبار حي" })
        assertEquals(1, repo.subjects().size)
        assertTrue(repo.subjects().any { it.name == "فيزياء — اختبار حي" })
        // أندرويد يدفع كل أنواع المهام بما فيها 4 أنواع مدمجة يزرعها
        // (ديني/دراسة/حياة/تطوّع — StudyDb.seedTaskTypes)؛ فبعد أول سحب
        // تكون في قاعدة ويندوز 5 أنواع لا نوعًا واحدًا. هذا سلوك صحيح
        // لا خلل مزامنة: المجموعة تشمل الأنواع كلها بالمعرّفات نفسها.
        assertEquals(
            "task types after first sync: ${repo.taskTypes().map { it.name }}",
            setOf("ديني", "دراسة", "حياة", "تطوّع", "مراجعة — اختبار حي"),
            repo.taskTypes().map { it.name }.toSet())
        assertEquals(1, repo.allHabits().size)
        assertTrue(repo.allHabits().any { it.name == SyncLiveWindowsSupport.HABIT_NAME })
        assertEquals(1, repo.allHabitLogs().size)
        assertTrue(repo.allHabitLogs().any { it.day == credentials.day })
        assertEquals(1, repo.allWirdLogs().size)
        assertTrue(repo.allWirdLogs().any { it.day == credentials.day && it.pages == 2 })
        assertEquals(1, repo.allHealthLogs().size)
        assertTrue(repo.allHealthLogs().any { it.day == credentials.day && it.weight == 70.5 })
        assertEquals(2, repo.allTransactions().count { it.day == credentials.day })
        assertEquals(1, repo.allMoodLogs().size)
        assertTrue(repo.allMoodLogs().any { it.day == credentials.day && it.mood == 4 })
        assertEquals(1, repo.allCalorieLogs().size)
        assertTrue(repo.allCalorieLogs().any { it.day == credentials.day && it.name == "أرز — اختبار حي" })

        // 3) تعديلات ويندوز: مهمة جديدة + إكمال مهمة أندرويد + حذف سجل عادة.
        val androidTask = repo.tasks().first { it.title == SyncLiveWindowsSupport.ANDROID_TASK_TITLE }
        val winTaskId = repo.addTask(SyncLiveWindowsSupport.WINDOWS_TASK_TITLE, 25)
        assertTrue("Windows task id must be negative (contract §7), got $winTaskId", winTaskId < 0)
        repo.setTaskDone(androidTask.id, true)
        val habit = repo.allHabits().first { it.name == SyncLiveWindowsSupport.HABIT_NAME }
        repo.deleteHabitLog(habit.id, credentials.day)

        // 4) دفع التعديلات عبر المسار الحقيقي نفسه.
        val second = service.syncNow(session)
        assertTrue("second syncNow must succeed, got $second", second is SyncOutcome.Ok)

        // 5) تحقق مستقل من الخادم: سحب خام يثبت وصول التعديلات فعلًا.
        val pulled = client.pull(credentials.profileId, credentials.passphrase)
        check(pulled is SyncOutcome.Ok) { "raw pull after Windows push failed: $pulled" }
        val snapshot = pulled.snapshot!!
        assertTrue(snapshot.data.tasks.any { it.title == SyncLiveWindowsSupport.WINDOWS_TASK_TITLE })
        val androidOnServer = snapshot.data.tasks.firstOrNull {
            it.title == SyncLiveWindowsSupport.ANDROID_TASK_TITLE
        }
        assertEquals("done", androidOnServer?.status)
        assertTrue(
            "habit log tombstone must be on the server",
            snapshot.data.habitLogs.any { it.habitId == habit.id.toString() && it.day == credentials.day && it.deleted == true },
        )
    }
}

