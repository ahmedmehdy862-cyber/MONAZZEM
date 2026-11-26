package com.focusstudy.poc

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Real migration test for DB v16 → v17 (م6): a database file created at version 16
 * with the pre-17 schema opens through [StudyDb] and must upgrade to v17 with all
 * existing rows intact.
 *
 * Runs under Robolectric (no device needed) with the runtime pinned to SDK 35 via
 * [Config] — the migration is pure SQLite, so the pinned runtime is enough.
 * Run with `./gradlew testDebugUnitTest` on a machine with the Android SDK; the
 * release workflow does not run unit tests, so run this before merging.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DbMigrationV16ToV17Test {

    private lateinit var context: Context

    /** Minimal v16 seed: only the tables this test reads back, with the real v16 DDL. */
    private class V16SeedHelper(context: Context) :
        SQLiteOpenHelper(context, DB_FILE, null, 16) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE tasks(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "title TEXT NOT NULL, " +
                    "planned_minutes INTEGER NOT NULL, " +
                    "done_minutes INTEGER NOT NULL DEFAULT 0, " +
                    "is_done INTEGER NOT NULL DEFAULT 0, " +
                    "blocked_packages TEXT NOT NULL DEFAULT '', " +
                    "created_at INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE wird_settings(" +
                    "id INTEGER PRIMARY KEY, " +
                    "surah TEXT NOT NULL DEFAULT 'البقرة', " +
                    "start_page INTEGER NOT NULL DEFAULT 2, " +
                    "pages_per_day INTEGER NOT NULL DEFAULT 2)"
            )
            db.execSQL(
                "CREATE TABLE wird_logs(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "day TEXT NOT NULL, " +
                    "pages INTEGER NOT NULL, " +
                    "surah TEXT NOT NULL DEFAULT 'البقرة', " +
                    "logged_at INTEGER NOT NULL, " +
                    "UNIQUE(day))"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_FILE)
    }

    @After
    fun tearDown() {
        // Close the StudyDb helper opened in the test first: its readable
        // connection stays open otherwise, and the seed file can survive
        // deleteDatabase into the next test class (it shares this same file
        // name) — a v16 seed opened through StudyDb upgrades to v17 without
        // the v15 life tables (CI run 45756885728: "no such table: habit_logs"
        // in SyncApplyDbTest).
        runCatching { StudyDb.get(context).close() }
        runCatching { context.deleteDatabase(DB_FILE) }
    }

    @Test
    fun v16DatabaseUpgradesToV17WithDataPreserved() {
        // 1) Seed a version-16 database with marker rows, then close it.
        val seed = V16SeedHelper(context)
        val writable = seed.writableDatabase
        writable.execSQL(
            "INSERT INTO tasks(title, planned_minutes, done_minutes, is_done, blocked_packages, created_at) " +
                "VALUES('مهمة محفوظة', 30, 0, 0, '', 1)"
        )
        writable.execSQL(
            "INSERT INTO wird_settings(id, surah, start_page, pages_per_day) VALUES(1, 'البقرة', 2, 2)"
        )
        writable.execSQL(
            "INSERT INTO wird_logs(day, pages, surah, logged_at) VALUES('2026-11-25', 2, 'البقرة', 1)"
        )
        seed.close()

        // 2) Opening through StudyDb runs onUpgrade(16, 17) → migrateToV17.
        val db = StudyDb.get(context)
        // get() only constructs the helper; the upgrade runs on first open,
        // so force it before asserting the new tables exist.
        db.writableDatabase.close()

        // 3) The v17 tables exist and the v16 rows survived.
        assertTrue(hasTable("user_profile"))
        assertTrue(hasTable("support_messages"))
        assertEquals(1, countRows("tasks"))
        assertEquals("مهمة محفوظة", taskTitle())
        assertEquals(WirdSettings("البقرة", 2, 2), db.wirdSettings())
        assertEquals(2, db.wirdTotalPages())

        // 4) The new tables work: a support message round-trips.
        val id = db.saveSupportMessage(
            kind = SupportMessageKind.COMPLAINT,
            userName = "أحمد",
            senderName = "أحمد",
            contact = "",
            body = "رسالة اختبار",
            imagePaths = emptyList()
        )
        assertTrue(id > 0)
        val saved = db.supportMessages()
        assertEquals(1, saved.size)
        assertEquals("رسالة اختبار", saved.first().body)
        assertEquals(SupportMessageStatus.SENDING, saved.first().status)

        // 5) م1: changing wird settings no longer wipes the log.
        db.saveWirdSettings(WirdSettings("آل عمران", 50, 2))
        assertEquals(WirdSettings("آل عمران", 50, 2), db.wirdSettings())
        assertEquals(2, db.wirdTotalPages())
    }

    private fun hasTable(name: String): Boolean =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(DB_FILE).path, null, SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
                arrayOf(name)
            ).use { it.moveToFirst() }
        }

    private fun countRows(table: String): Int =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(DB_FILE).path, null, SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM $table", null)
                .use { if (it.moveToFirst()) it.getInt(0) else -1 }
        }

    private fun taskTitle(): String =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(DB_FILE).path, null, SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery("SELECT title FROM tasks LIMIT 1", null)
                .use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
        }

    private companion object {
        const val DB_FILE = "focus_study.db"
    }
}
