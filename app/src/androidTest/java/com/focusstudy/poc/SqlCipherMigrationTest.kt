package com.focusstudy.poc

import android.content.Context
import android.database.sqlite.SQLiteDatabase as PlatformDb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P-04 — إثبات ترحيل SQLCipher على جهاز/محاكٍ حقيقي (ADR_002 يشترط
 * ترحيلًا مختبَرًا بقاعدة فعلية، بما فيها حالة الانقطاع أثناء الترحيل).
 *
 * البذرة: قاعدة نص صريح كاملة المخطط v18 عبر
 * [StudyDb.createPlaintextDatabaseForTest] + صفوف تمثيلية في 20 جدولًا
 * (منها كل الجداول اليومية: habit_logs وwird_logs وhealth_logs و
 * transactions وmood_logs وcalorie_logs + جداول assistant_* و
 * support_messages)، ثم [StudyDb.get] فيشغّل الترحيل.
 *
 * الإثبات لكل حالة: الترويسة ليست «SQLite format 3»، الفتح بدون مفتاح
 * يفشل، كل الصفوف سليمة عبر واجهة StudyDb نفسها، والنسخة الاحتياطية
 * من النص الصريح موجودة وسليمة.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherMigrationTest {

    private lateinit var context: Context

    private fun dbFile(): File = context.getDatabasePath("focus_study.db")
    private fun tmpFile(): File = File(dbFile().path + DatabaseEncryption.TEMP_SUFFIX)
    private fun backupFile(): File = File(dbFile().path + DatabaseEncryption.BACKUP_SUFFIX)

    /** (جدول → عبارات INSERT) — صفوف تمثيلية بأعداد معروفة للتحقق. */
    private val seedInserts: Map<String, List<String>> = mapOf(
        "tasks" to listOf(
            "INSERT INTO tasks(title, planned_minutes, done_minutes, is_done, blocked_packages, created_at) VALUES('مهمة محفوظة', 30, 0, 0, '', 1000)",
            "INSERT INTO tasks(title, planned_minutes, done_minutes, is_done, blocked_packages, created_at) VALUES('مهمة ثانية', 45, 10, 0, '', 1001)"
        ),
        "sessions" to listOf(
            "INSERT INTO sessions(task_id, started_at, ends_at, blocked_packages, state) VALUES(1, 1000, 2000, '', 'done')"
        ),
        "habits" to listOf(
            "INSERT INTO habits(name, created_at, archived) VALUES('قراءة', 1000, 0)"
        ),
        "habit_logs" to listOf(
            "INSERT INTO habit_logs(habit_id, day, done) VALUES(1, '2026-11-28', 1)",
            "INSERT INTO habit_logs(habit_id, day, done) VALUES(1, '2026-11-27', 1)"
        ),
        "wird_settings" to listOf(
            "INSERT INTO wird_settings(id, surah, start_page, pages_per_day) VALUES(1, 'البقرة', 2, 2)"
        ),
        "wird_logs" to listOf(
            "INSERT INTO wird_logs(day, pages, surah, logged_at) VALUES('2026-11-28', 2, 'البقرة', 1000)",
            "INSERT INTO wird_logs(day, pages, surah, logged_at) VALUES('2026-11-27', 2, 'البقرة', 1000)"
        ),
        "health_logs" to listOf(
            "INSERT INTO health_logs(day, weight, sleep_hours, water_cups, exercise_minutes) VALUES('2026-11-28', 70.5, 7.0, 6, 30)"
        ),
        "transactions" to listOf(
            "INSERT INTO transactions(kind, amount, category, note, day, created_at) VALUES('expense', 50.0, 'طعام', '', '2026-11-28', 1000)",
            "INSERT INTO transactions(kind, amount, category, note, day, created_at) VALUES('income', 1000.0, 'راتب', '', '2026-11-28', 1000)"
        ),
        "mood_logs" to listOf(
            "INSERT INTO mood_logs(day, mood, logged_at) VALUES('2026-11-28', 4, 1000)"
        ),
        "fitness_profile" to listOf(
            "INSERT INTO fitness_profile(id, gender, age, height_cm, weight_kg, activity, goal) VALUES(1, 'male', 30, 170.0, 70.0, 'moderate', 'maintain')"
        ),
        "calorie_logs" to listOf(
            "INSERT INTO calorie_logs(day, name, calories, created_at) VALUES('2026-11-28', 'فطور', 350, 1000)"
        ),
        "exercise_logs" to listOf(
            "INSERT INTO exercise_logs(day, exercise_id, done) VALUES('2026-11-28', 'pushups', 1)"
        ),
        "notes" to listOf(
            "INSERT INTO notes(title, body, category, pinned, created_at, updated_at) VALUES('ملاحظة', 'نص الملاحظة', 'عام', 0, 1000, 1000)"
        ),
        "support_messages" to listOf(
            "INSERT INTO support_messages(kind, user_name, sender_name, contact, body, image_paths, status, created_at) VALUES('suggestion', 'أحمد', 'أحمد', '', 'اقتراح للتحسين', '', 'sent', 1000)"
        ),
        "user_profile" to listOf(
            "INSERT INTO user_profile(id, name, updated_at) VALUES(1, 'أحمد', 1000)"
        ),
        "assistant_memories" to listOf(
            "INSERT INTO assistant_memories(text, category, source, confidence, use_count, created_at, last_used_at) VALUES('بحب جلسات 45 دقيقة', 'روتين', 'chat', 0.8, 0, 1000, 1000)"
        ),
        "assistant_interactions" to listOf(
            "INSERT INTO assistant_interactions(kind, detail, accepted, created_at) VALUES('suggestion', 'ورد', 1, 1000)"
        ),
        "assistant_profile" to listOf(
            "INSERT INTO assistant_profile(key, text_value, real_value, confidence, samples, updated_at) VALUES('best_hour', '', 9.0, 0.9, 3, 1000)"
        ),
        "assistant_followups" to listOf(
            "INSERT INTO assistant_followups(kind, slot, day, attempts, max_attempts, next_at, state, snooze_until, detail, created_at, updated_at) VALUES('wird_morning', 'morning', '2026-11-28', 1, 5, 0, 'active', NULL, 'صفحات 2–3.', 1000, 1000)"
        ),
        "consents" to listOf(
            "INSERT INTO consents(key, granted, version, at, last_granted_at) VALUES('notifications', 1, 1, 1000, 1000)"
        )
    )

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        StudyDb.resetForTest()
        deleteAllDbFiles()
        DatabaseKeyManager.clearKey(context)
    }

    @After
    fun tearDown() {
        StudyDb.resetForTest()
        deleteAllDbFiles()
        DatabaseKeyManager.clearKey(context)
    }

    private fun deleteAllDbFiles() {
        val base = dbFile()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            runCatching { File(base.path + suffix).takeIf { it.exists() }?.delete() }
        }
        runCatching { tmpFile().delete() }
        runCatching { backupFile().delete() }
    }

    /** يزرع قاعدة نص صريح v18 كاملة بصفوف تمثيلية؛ يرجع عدد الصفوف لكل جدول. */
    private fun seedPlaintextDb(): Map<String, Int> {
        StudyDb.createPlaintextDatabaseForTest(context)
        assertEquals("SQLite format 3\u0000", readHeader(dbFile()))
        val db = PlatformDb.openDatabase(dbFile().path, null, PlatformDb.OPEN_READWRITE)
        try {
            seedInserts.forEach { (_, inserts) -> inserts.forEach { db.execSQL(it) } }
            assertEquals(18, db.version)
        } finally {
            db.close()
        }
        return seedInserts.mapValues { it.value.size }
    }

    private fun countRows(db: StudyDb, table: String): Long =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        }

    private fun readHeader(file: File): String? {
        if (!file.exists() || file.length() < 16) return null
        val bytes = ByteArray(16)
        file.inputStream().use { it.read(bytes) }
        return String(bytes, Charsets.UTF_8)
    }

    /** الفتح بمنصة SQLite (بلا مفتاح) يجب أن يفشل عند أول قراءة. */
    private fun platformOpenFails(file: File): Boolean = runCatching {
        PlatformDb.openDatabase(file.path, null, PlatformDb.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM tasks", null).use { it.moveToFirst() }
        }
    }.isFailure

    @Test
    fun plaintextDatabaseMigratesToEncryptedWithAllRowsIntact() {
        val expected = seedPlaintextDb()

        val db = StudyDb.get(context)

        // 1) القاعدة فعلًا مشفرة: الترويسة ليست نصًا صريحًا، والفتح بلا مفتاح يفشل.
        assertNotEquals("SQLite format 3\u0000", readHeader(dbFile()))
        assertTrue(platformOpenFails(dbFile()))
        // 2) النسخة الاحتياطية من النص الصريح موجودة وسليمة (لا تُحذف أبدًا هنا).
        assertTrue(backupFile().exists())
        assertEquals("SQLite format 3\u0000", readHeader(backupFile()))
        assertFalse(platformOpenFails(backupFile()))
        // 3) لا ملف مؤقت متبقٍ، والحالة DONE.
        assertFalse(tmpFile().exists())
        assertEquals(DatabaseKeyManager.STATE_DONE, DatabaseKeyManager.migrationState(context))
        // 4) كل الجداول/الصفوف سليمة عبر واجهة StudyDb نفسها.
        expected.forEach { (table, count) ->
            assertEquals("rows in $table", count.toLong(), countRows(db, table))
        }
        // 5) قيم فعلية لا أعداد فقط.
        db.readableDatabase.rawQuery(
            "SELECT title FROM tasks WHERE id = 1", null
        ).use { assertTrue(it.moveToFirst()); assertEquals("مهمة محفوظة", it.getString(0)) }
        db.readableDatabase.rawQuery(
            "SELECT pages FROM wird_logs WHERE day = '2026-11-28'", null
        ).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
        // 6) الكتابة بعد الترحيل تعمل، وإعادة الفتح بالمفتاح تقرؤها.
        db.writableDatabase.execSQL(
            "INSERT INTO tasks(title, planned_minutes, done_minutes, is_done, blocked_packages, created_at) " +
                "VALUES('بعد الترحيل', 20, 0, 0, '', 2000)"
        )
        assertEquals(3L, countRows(db, "tasks"))
        StudyDb.resetForTest()
        val reopened = StudyDb.get(context)
        assertEquals(3L, countRows(reopened, "tasks"))
        assertEquals(2L, countRows(reopened, "wird_logs"))
    }

    @Test
    fun freshInstallCreatesEncryptedDatabaseDirectly() {
        assertFalse(dbFile().exists())

        val db = StudyDb.get(context)
        db.writableDatabase.execSQL(
            "INSERT INTO tasks(title, planned_minutes, done_minutes, is_done, blocked_packages, created_at) " +
                "VALUES('أول مهمة', 30, 0, 0, '', 1000)"
        )

        assertNotEquals("SQLite format 3\u0000", readHeader(dbFile()))
        assertTrue(platformOpenFails(dbFile()))
        assertFalse(backupFile().exists()) // لا نص صريح أصلًا ليُنسخ احتياطيًا
        assertEquals(DatabaseKeyManager.STATE_DONE, DatabaseKeyManager.migrationState(context))
        StudyDb.resetForTest()
        val reopened = StudyDb.get(context)
        assertEquals(1L, countRows(reopened, "tasks"))
    }

    @Test
    fun interruptedMigrationRetriesFromIntactPlaintext() {
        val expected = seedPlaintextDb()
        // محاكاة انقطاع: حالة IN_PROGRESS + ملف مؤقت جزئي (بقايا فاسدة).
        tmpFile().writeBytes(ByteArray(1024) { 0x7F.toByte() })
        DatabaseKeyManager.setMigrationState(context, DatabaseKeyManager.STATE_IN_PROGRESS)

        val db = StudyDb.get(context)

        // المؤقت الجزئي حُذف وأُعيد الترحيل بنجاح من النص الصريح السليم.
        assertFalse(tmpFile().exists())
        assertNotEquals("SQLite format 3\u0000", readHeader(dbFile()))
        assertTrue(platformOpenFails(dbFile()))
        assertTrue(backupFile().exists())
        assertEquals(DatabaseKeyManager.STATE_DONE, DatabaseKeyManager.migrationState(context))
        expected.forEach { (table, count) ->
            assertEquals("rows in $table", count.toLong(), countRows(db, table))
        }
    }
}
