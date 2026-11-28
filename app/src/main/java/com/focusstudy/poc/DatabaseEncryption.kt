package com.focusstudy.poc

import android.content.Context
import android.util.Log
import java.io.File

/**
 * P-04 — الترحيل الآمن لمرة واحدة من قاعدة نص صريح إلى SQLCipher
 * (ADR_002 «تم الاعتماد»). يُستدعى قبل أي فتح للقاعدة، من [StudyDb.get].
 *
 * الحالات (العلامة في [DatabaseKeyManager] — `migration_state`):
 *
 * - تثبيت جديد (لا ملف): تُنشأ القاعدة مشفرة مباشرة بالمفتاح.
 * - مستخدم حالي بنص صريح: تُفتح قراءةً (أو كتابةً فقط لاسترداد journal
 *   ساخن بعد انقطاع)، يُرفَق ملف مؤقت مشفر بالمفتاح،
 *   `SELECT sqlcipher_export('encrypted', 'main')`، ثم التحقق الكامل
 *   (عدد الصفوف لكل جدول + `integrity_check` + فتح بالمفتاح + فشل الفتح
 *   بدون مفتاح + الترويسة ليست نصًا صريحًا) — وبعد التحقق فقط تُبدَّل
 *   الملفات: النص الصريح → `focus_study.db.plaintext-backup` (لا تُحذف
 *   أبدًا — حذفها قرار لاحق)، والمؤقت → `focus_study.db`.
 * - انقطاع أثناء الترحيل (IN_PROGRESS + مؤقت جزئي): يُحذف المؤقت ويُعاد
 *   الترحيل من النص الصريح السليم — النص الصريح لا يُعاد تسميته إلا بعد
 *   التحقق الكامل، فهو سليم دائمًا حتى المبادلة.
 * - المفتاح مفقود (Keystore تعطّل): لا تُنشأ أبدًا قاعدة مشفرة جديدة فوق
 *   بيانات موجودة. نص صريح موجود → يُفتح مع تحذير ويُواصَل غير مشفر.
 *   قاعدة مشفرة ولا مفتاح → [Outcome.KeyLost]: لا فتح ولا حذف — سجّل
 *   خطأً وبلّغ؛ فقدان المفتاح = فقدان البيانات، وهذه حالة Keystore
 *   الصادقة ويجب إظهارها لا إخفاؤها.
 * - المكتبة الأصلية لا تُحمَّل (JVM/Robolectric أو ABI مفقود): نص صريح
 *   مع تحذير — بهذا تبقى اختبارات الوحدة خضراء دون محاكاة SQLCipher.
 *
 * القاعدة بلا WAL (لا `enableWriteAheadLogging` في أي مكان)، فوضع
 * الـ journal افتراضي (delete)؛ مع ذلك تُغلق كل الاتصالات قبل أي نقل
 * ملفات، وتُحذف بقايا `-wal`/`-shm`/`-journal` مع المبادلة والمسح.
 */
internal object DatabaseEncryption {
    private const val TAG = "DatabaseEncryption"
    private const val PLAINTEXT_HEADER = "SQLite format 3\u0000"
    const val TEMP_SUFFIX = ".migrate-tmp"
    const val BACKUP_SUFFIX = ".plaintext-backup"

    sealed interface Outcome {
        data class Encrypted(val keyHex: String) : Outcome
        data class Plaintext(val reason: String) : Outcome
        data object KeyLost : Outcome
    }

    private val lock = Any()

    fun ensureEncrypted(context: Context): Outcome = synchronized(lock) {
        val app = context.applicationContext
        val libsLoaded = runCatching {
            net.sqlcipher.database.SQLiteDatabase.loadLibs(app)
        }.onFailure {
            Log.w(TAG, "SQLCipher native libs unavailable — staying plaintext", it)
        }.isSuccess
        if (!libsLoaded) return Outcome.Plaintext("sqlcipher-native-libs-unavailable")

        val dbFile = app.getDatabasePath(StudyDb.DB_NAME)
        if (!dbFile.exists() || dbFile.length() == 0L) {
            val key = DatabaseKeyManager.getOrCreateKey(app)
                ?: return Outcome.Plaintext("no-encryption-key")
            DatabaseKeyManager.setMigrationState(app, DatabaseKeyManager.STATE_DONE)
            return Outcome.Encrypted(key)
        }
        return when (readHeader(dbFile)) {
            PLAINTEXT_HEADER -> migratePlaintext(app, dbFile)
            else -> {
                val key = DatabaseKeyManager.getExistingKey(app)
                if (key == null) {
                    Log.e(
                        TAG,
                        "Encrypted database present but no key in Keystore — " +
                            "refusing to open or delete anything (key loss = data loss)"
                    )
                    Outcome.KeyLost
                } else {
                    DatabaseKeyManager.setMigrationState(app, DatabaseKeyManager.STATE_DONE)
                    Outcome.Encrypted(key)
                }
            }
        }
    }

    private fun migratePlaintext(app: Context, dbFile: File): Outcome {
        val key = DatabaseKeyManager.getOrCreateKey(app)
            ?: return Outcome.Plaintext("no-encryption-key")
        DatabaseKeyManager.setMigrationState(app, DatabaseKeyManager.STATE_IN_PROGRESS)
        val tmpFile = File(dbFile.path + TEMP_SUFFIX)
        val backupFile = File(dbFile.path + BACKUP_SUFFIX)
        deleteTempFiles(tmpFile)
        try {
            // قراءة فقط في الحالة العادية؛ كتابة فقط إن وُجد journal ساخن
            // (انقطاع أثناء معاملة) حتى يستطيع SQLite استرداده — حذف
            // الـ journal الساخن دون استرداد كان سيُبقي كتابات جزئية.
            val hotJournal = File(dbFile.path + "-journal").exists()
            val flags = if (hotJournal) {
                Log.w(TAG, "Hot journal found — opening read-write for recovery")
                net.sqlcipher.database.SQLiteDatabase.OPEN_READWRITE
            } else {
                net.sqlcipher.database.SQLiteDatabase.OPEN_READONLY
            }
            val plain = net.sqlcipher.database.SQLiteDatabase.openDatabase(
                dbFile.path, "", null, flags
            )
            val userVersion: Int
            val counts: Map<String, Long>
            try {
                userVersion = plain.version
                counts = tableRowCounts(plain)
                plain.execSQL(
                    "ATTACH DATABASE ${sqlLiteral(tmpFile.path)} AS encrypted " +
                        "KEY ${sqlLiteral(key)}"
                )
                // sqlcipher_export دالة تُنفَّذ عبر SELECT؛ execSQL يرفض
                // العبارات التي ترجع صفوفًا، فتُنفَّذ عبر rawQuery.
                plain.rawQuery("SELECT sqlcipher_export('encrypted', 'main')", null)
                    .use { it.moveToFirst() }
                plain.execSQL("PRAGMA encrypted.user_version = $userVersion")
                plain.execSQL("DETACH DATABASE encrypted")
            } finally {
                plain.close()
            }
            verifyEncrypted(tmpFile, key, counts)
            // المبادلة — فقط بعد نجاح كل التحقق.
            deleteQuietly(backupFile)
            check(dbFile.renameTo(backupFile)) { "rename plaintext to backup failed" }
            try {
                check(tmpFile.renameTo(dbFile)) { "rename temp to db failed" }
            } catch (t: Throwable) {
                runCatching { backupFile.renameTo(dbFile) }
                throw t
            }
            deleteQuietly(File(dbFile.path + "-wal"))
            deleteQuietly(File(dbFile.path + "-shm"))
            deleteQuietly(File(dbFile.path + "-journal"))
            DatabaseKeyManager.setMigrationState(app, DatabaseKeyManager.STATE_DONE)
            Log.i(TAG, "Plaintext database migrated to SQLCipher (${counts.size} tables verified)")
            Outcome.Encrypted(key)
        } catch (t: Throwable) {
            // النص الصريح سليم (لم يُمس قبل التحقق) — يُحذف المؤقت فقط
            // ويُعاد الترحيل في الإقلاع التالي؛ التطبيق يواصل على النص الصريح.
            Log.e(TAG, "Migration failed — staying on plaintext, will retry next launch", t)
            deleteTempFiles(tmpFile)
            Outcome.Plaintext("migration-failed: ${t.message}")
        }
    }

    private fun verifyEncrypted(
        file: File,
        key: String,
        expectedCounts: Map<String, Long>
    ) {
        check(readHeader(file) != PLAINTEXT_HEADER) {
            "migrated file still has a plaintext header"
        }
        val db = net.sqlcipher.database.SQLiteDatabase.openDatabase(
            file.path, key, null, net.sqlcipher.database.SQLiteDatabase.OPEN_READONLY
        )
        try {
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use {
                if (it.moveToFirst()) it.getString(0) else ""
            }
            check(integrity == "ok") { "integrity_check=$integrity" }
            val actual = tableRowCounts(db)
            check(actual == expectedCounts) {
                "row counts differ: expected=$expectedCounts actual=$actual"
            }
        } finally {
            db.close()
        }
        val opensWithoutKey = runCatching {
            net.sqlcipher.database.SQLiteDatabase.openDatabase(
                file.path, "", null,
                net.sqlcipher.database.SQLiteDatabase.OPEN_READONLY
            ).use { bad ->
                bad.rawQuery("SELECT COUNT(*) FROM sqlite_master", null).use { it.moveToFirst() }
            }
        }.isSuccess
        check(!opensWithoutKey) { "encrypted file opened without the key" }
    }

    private fun tableRowCounts(
        db: net.sqlcipher.database.SQLiteDatabase
    ): Map<String, Long> {
        val tables = mutableListOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            while (c.moveToNext()) tables += c.getString(0)
        }
        return tables.associateWith { table ->
            db.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use {
                if (it.moveToFirst()) it.getLong(0) else -1L
            }
        }
    }

    private fun readHeader(file: File): String? {
        if (!file.exists() || file.length() < 16) return null
        val bytes = ByteArray(16)
        return runCatching {
            file.inputStream().use { it.read(bytes) }
            String(bytes, Charsets.UTF_8)
        }.getOrNull()
    }

    private fun sqlLiteral(value: String): String =
        "'" + value.replace("'", "''") + "'"

    private fun deleteTempFiles(tmpFile: File) {
        deleteQuietly(tmpFile)
        deleteQuietly(File(tmpFile.path + "-wal"))
        deleteQuietly(File(tmpFile.path + "-shm"))
        deleteQuietly(File(tmpFile.path + "-journal"))
    }

    private fun deleteQuietly(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }
}
