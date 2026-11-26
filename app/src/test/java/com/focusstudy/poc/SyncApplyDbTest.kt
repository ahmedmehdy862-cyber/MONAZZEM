package com.focusstudy.poc

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pull end-to-end against a real [StudyDb] under Robolectric: [SyncApply.plan] decides and
 * [StudyDb.applySyncPlan] executes, in one transaction, with stamps saved only after success.
 *
 * Runs under Robolectric (no device needed) with the runtime pinned to SDK 35.
 * Run with `./gradlew testDebugUnitTest` on a machine with the Android SDK; the release
 * workflow does not run unit tests, so run this before merging.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncApplyDbTest {

    private lateinit var context: Context
    private lateinit var db: StudyDb

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // Close any cached helper first: DbMigrationV16ToV17Test seeds a partial v16
        // database in this same shared file, and a stale open connection can keep
        // that file alive past deleteDatabase (CI run 45756885728: a v16 seed opened
        // through StudyDb upgrades to v17 without the v15 life tables, so
        // setHabitDone threw "no such table: habit_logs").
        runCatching { StudyDb.get(context).close() }
        context.deleteDatabase(DB_FILE)
        db = StudyDb.get(context)
        // If a stale partial-schema file survived the delete, recreate it now.
        if (!hasTable("habit_logs")) {
            runCatching { db.close() }
            context.deleteDatabase(DB_FILE)
            db = StudyDb.get(context)
            check(hasTable("habit_logs")) { "fresh test database is missing habit_logs" }
        }
    }

    private fun hasTable(name: String): Boolean =
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(name)
        ).use { it.moveToFirst() }

    @After
    fun tearDown() {
        // Close the singleton helper first: otherwise it keeps the (deleted) database open and
        // the next test would see this test's rows through the same cached connection.
        runCatching { db.close() }
        runCatching { context.deleteDatabase(DB_FILE) }
    }

    private fun localSnapshot(tracked: Map<String, SyncStamp>, now: Long): SyncBuilt =
        SyncPayload.build(
            tasks = db.tasks(),
            subjects = db.subjects(),
            types = db.taskTypes(includeArchived = true),
            tracked = tracked,
            now = now,
            life = LifeSyncSnapshot(
                habits = db.allHabits(),
                habitLogs = db.allHabitLogs(),
                wirdLogs = db.allWirdLogs(),
                healthLogs = db.allHealthLogs(),
                transactions = db.allTransactions(),
                moodLogs = db.allMoodLogs(),
                calorieLogs = db.allCalorieLogs()
            )
        )

    private fun pull(incoming: Map<String, List<Map<String, Any>>>, tracked: Map<String, SyncStamp>, now: Long) {
        val built = localSnapshot(tracked, now)
        val plan = SyncApply.plan(built.data, tracked, incoming)
        db.applySyncPlan(plan)
    }

    @Test fun newerWirdWinsAndNewerMoodTombstoneDeletes() {
        db.logWird("2026-11-25", 2, "البقرة")
        db.logMood("2026-11-25", 3)
        val tracked = localSnapshot(emptyMap(), 1_000L).stamps
        pull(
            incoming = mapOf(
                "wirdLogs" to listOf(
                    mapOf<String, Any>("id" to "2026-11-25", "day" to "2026-11-25", "pages" to 4, "surah" to "البقرة", "updatedAt" to 2_000L)
                ),
                "moodLogs" to listOf(
                    mapOf<String, Any>("id" to "2026-11-25", "day" to "2026-11-25", "deleted" to true, "updatedAt" to 2_000L)
                )
            ),
            tracked = tracked,
            now = 1_500L
        )
        assertEquals(4, db.wirdTotalPages())
        assertNull(db.moodFor("2026-11-25"))
    }

    @Test fun unsyncedLocalHabitKeptWhileRemoteTransactionInserts() {
        val habitId = db.addHabit("قراءة")
        db.setHabitDone(habitId, "2026-11-25", true)
        db.addTransaction(MoneyKind.EXPENSE, 12.5, "طعام", "ملاحظة سرية", "2026-11-25")
        // No stamps: nothing here was ever synced, so the remote habit rename must not win.
        pull(
            incoming = mapOf(
                "habits" to listOf(
                    mapOf<String, Any>("id" to habitId.toString(), "name" to "قراءة جديدة", "updatedAt" to 500L)
                ),
                "transactions" to listOf(
                    mapOf<String, Any>("id" to "-1700000000000", "kind" to "income", "amount" to 50.0, "category" to "راتب", "day" to "2026-11-25", "updatedAt" to 500L),
                    mapOf<String, Any>("id" to "w-abc", "kind" to "income", "amount" to 50.0, "category" to "راتب", "day" to "2026-11-25", "updatedAt" to 500L)
                )
            ),
            tracked = emptyMap(),
            now = 1_000L
        )
        assertEquals("قراءة", db.habits().single().name)
        assertEquals(setOf("2026-11-25"), db.habitDoneDays(habitId, "2026-11-01"))
        val ids = db.allTransactions().map { it.id }.toSet()
        assertTrue(ids.contains(-1_700_000_000_000L))
        assertEquals(2, db.allTransactions().size)
        // The Windows-style negative id was stored as-is; the non-numeric id was skipped.
        assertEquals("راتب", db.allTransactions().first { it.id < 0 }.category)
        assertEquals("", db.allTransactions().first { it.id < 0 }.note)
    }

    @Test fun newerTaskTitleAppliesButNotesStayLocal() {
        val taskId = db.insertTask(
            title = "مراجعة قديمة",
            plannedMinutes = 30,
            blockedPackages = emptySet(),
            notes = "ملاحظة سرية"
        )
        val tracked = localSnapshot(emptyMap(), 1_000L).stamps
        pull(
            incoming = mapOf(
                "tasks" to listOf(
                    mapOf<String, Any>(
                        "id" to taskId.toString(), "title" to "مراجعة جديدة", "minutes" to 45,
                        "category" to "study", "priority" to 1, "status" to "todo",
                        "recurrence" to "none", "updatedAt" to 2_000L
                    )
                )
            ),
            tracked = tracked,
            now = 1_500L
        )
        val updated = db.task(taskId)
        assertEquals("مراجعة جديدة", updated?.title)
        assertEquals(45, updated?.plannedMinutes)
        assertEquals("ملاحظة سرية", updated?.notes)
    }

    @Test fun habitTombstoneDeletesHabitAndItsLogs() {
        val habitId = db.addHabit("قراءة")
        db.setHabitDone(habitId, "2026-11-25", true)
        val tracked = localSnapshot(emptyMap(), 1_000L).stamps
        pull(
            incoming = mapOf(
                "habits" to listOf(
                    mapOf<String, Any>("id" to habitId.toString(), "deleted" to true, "updatedAt" to 2_000L)
                )
            ),
            tracked = tracked,
            now = 1_500L
        )
        assertTrue(db.habits().isEmpty())
        assertTrue(db.allHabitLogs().isEmpty())
    }

    private companion object {
        const val DB_FILE = "focus_study.db"
    }
}
