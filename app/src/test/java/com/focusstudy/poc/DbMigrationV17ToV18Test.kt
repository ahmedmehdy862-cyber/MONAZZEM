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
 * Real migration test for DB v17 → v18: a database file created at version 17
 * with the pre-18 schema opens through [StudyDb] and must upgrade to v18 with all
 * existing rows intact — mirroring [DbMigrationV16ToV17Test].
 *
 * Runs under Robolectric (no device needed) with the runtime pinned to SDK 35.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DbMigrationV17ToV18Test {

    private lateinit var context: Context

    /** Minimal v17 seed: only the tables this test reads back, with the real v17 DDL. */
    private class V17SeedHelper(context: Context) :
        SQLiteOpenHelper(context, DB_FILE, null, 17) {

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
            db.execSQL(
                "CREATE TABLE user_profile(" +
                    "id INTEGER PRIMARY KEY, " +
                    "name TEXT NOT NULL DEFAULT '', " +
                    "updated_at INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE support_messages(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "kind TEXT NOT NULL, " +
                    "user_name TEXT NOT NULL DEFAULT '', " +
                    "sender_name TEXT NOT NULL DEFAULT '', " +
                    "contact TEXT NOT NULL DEFAULT '', " +
                    "body TEXT NOT NULL DEFAULT '', " +
                    "image_paths TEXT NOT NULL DEFAULT '', " +
                    "status TEXT NOT NULL DEFAULT 'sending', " +
                    "created_at INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        // أعد ضبط الـ singleton أولًا: بدونه يبقى StudyDb.get مربوطًا بملف
        // قاعدة بيانات sandbox اختبار سابق، فلا يُفتح ملف البذرة أبدًا
        // (يُعاد إنشاء الملف الأول المحذوف فارغًا) — فشل CI run 45774875788.
        StudyDb.resetForTest()
        context.deleteDatabase(DB_FILE)
    }

    @After
    fun tearDown() {
        runCatching { StudyDb.get(context).close() }
        StudyDb.resetForTest()
        runCatching { context.deleteDatabase(DB_FILE) }
    }

    @Test
    fun v17DatabaseUpgradesToV18WithDataPreserved() {
        // 1) Seed a version-17 database with marker rows, then close it.
        val seed = V17SeedHelper(context)
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
        writable.execSQL(
            "INSERT INTO user_profile(id, name, updated_at) VALUES(1, 'أحمد', 1)"
        )
        seed.close()

        // 2) Opening through StudyDb runs onUpgrade(17, 18) → migrateToV18.
        val db = StudyDb.get(context)
        db.writableDatabase.close()

        // 3) The v18 tables exist and the v17 rows survived.
        assertTrue(hasTable("assistant_memories"))
        assertTrue(hasTable("assistant_interactions"))
        assertTrue(hasTable("assistant_profile"))
        assertTrue(hasTable("assistant_followups"))
        assertEquals(1, countRows("tasks"))
        assertEquals("مهمة محفوظة", taskTitle())
        assertEquals(WirdSettings("البقرة", 2, 2), db.wirdSettings())
        assertEquals(2, db.wirdTotalPages())

        // 4) The new tables work: a memory and a follow-up round-trip.
        val memoryId = db.saveAssistantMemory("بحب جلسات 45 دقيقة", "روتين", "chat", 0.8)
        assertTrue(memoryId > 0)
        assertEquals(1, db.assistantMemories().size)
        db.upsertAssistantFollowUp(
            kind = "wird_morning", slot = "morning", day = "2026-11-25",
            attempts = 1, maxAttempts = 5, nextAt = 0L, state = "active",
            snoozeUntil = null, detail = "صفحات 2–3.", now = 1000L
        )
        assertEquals(1, db.assistantFollowUpsActive().size)

        // 5) wirdLogsSince — the learner's input — reads the preserved log.
        val logs = db.wirdLogsSince("2026-11-01")
        assertEquals(1, logs.size)
        assertEquals("2026-11-25", logs.first().day)
        assertEquals(2, logs.first().pages)
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
