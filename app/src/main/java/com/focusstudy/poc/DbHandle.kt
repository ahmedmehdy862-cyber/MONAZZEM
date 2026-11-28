package com.focusstudy.poc

import android.content.ContentValues
import android.database.Cursor
import java.io.Closeable

/**
 * P-04 — طبقة تجريد رقيقة فوق قاعدتي البيانات اللتين يفتحهما التطبيق.
 *
 * لماذا التجريد بدل تبديل الفئة الأساسية مباشرةً إلى
 * `net.sqlcipher.database.SQLiteOpenHelper`؟ مكتبة SQLCipher أصلية (JNI):
 * لا يمكن تحميلها إطلاقًا على JVM، فاختبارات الوحدة (Robolectric) التي
 * تفتح القاعدة — ترحيل v16→v17 وv17→v18 وSyncApplyDbTest وغيرها — كانت
 * ستنكسر كلها بلا بديل. بهذا التصميم:
 *
 * - على الجهاز: [SqlCipherDbBackend] فوق `net.sqlcipher` — القاعدة مشفرة
 *   دائمًا (أو نص صريح فقط في مسار التراجع المصرّح به عند تعذّر Keystore).
 * - على JVM / عند تعذّر تحميل المكتبة الأصلية: [PlatformDbBackend] فوق
 *   `android.database.sqlite` — نفس SQL ونفس المخطط، بلا تشفير.
 *
 * كل SQL في [StudyDb] يمر عبر [DbHandle]؛ لا يعرف الفرق بين الواجهتين.
 * `net.sqlcipher.Cursor` يمتد `android.database.Cursor`، فـ [DbHandle.rawQuery]
 * يرجع النوع المنصوصي في الحالتين.
 */
internal interface DbHandle : Closeable {
    fun execSQL(sql: String)
    fun execSQL(sql: String, bindArgs: Array<out Any?>)
    fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor
    fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long
    fun insertWithOnConflict(
        table: String,
        nullColumnHack: String?,
        values: ContentValues,
        conflictAlgorithm: Int
    ): Long

    /** مثل SQLiteDatabase.insertOrThrow في الواجهتين: يرمي عند التعارض بدل −1. */
    fun insertOrThrow(table: String, nullColumnHack: String?, values: ContentValues): Long

    fun update(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?
    ): Int

    fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int
    fun beginTransaction()
    fun setTransactionSuccessful()
    fun endTransaction()

    companion object {
        /** مطابقة لقيم android.database.sqlite.SQLiteDatabase وnet.sqlcipher. */
        const val CONFLICT_IGNORE: Int = 4
        const val CONFLICT_REPLACE: Int = 5
    }
}

/** الواجهة الخلفية التي يمسكها [StudyDb]: مساعد يفتح القاعدة + يغلقها. */
internal interface DbBackend : Closeable {
    val writable: DbHandle
    val readable: DbHandle
}

internal class PlatformDbHandle(
    private val db: android.database.sqlite.SQLiteDatabase
) : DbHandle {
    override fun execSQL(sql: String) = db.execSQL(sql)

    @Suppress("UNCHECKED_CAST")
    override fun execSQL(sql: String, bindArgs: Array<out Any?>) =
        db.execSQL(sql, bindArgs as Array<Any?>)

    override fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor =
        db.rawQuery(sql, selectionArgs)

    override fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long =
        db.insert(table, nullColumnHack, values)

    override fun insertWithOnConflict(
        table: String,
        nullColumnHack: String?,
        values: ContentValues,
        conflictAlgorithm: Int
    ): Long = db.insertWithOnConflict(table, nullColumnHack, values, conflictAlgorithm)

    override fun insertOrThrow(table: String, nullColumnHack: String?, values: ContentValues): Long =
        db.insertOrThrow(table, nullColumnHack, values)

    override fun update(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?
    ): Int = db.update(table, values, whereClause, whereArgs)

    override fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int =
        db.delete(table, whereClause, whereArgs)

    override fun beginTransaction() = db.beginTransaction()
    override fun setTransactionSuccessful() = db.setTransactionSuccessful()
    override fun endTransaction() = db.endTransaction()
    override fun close() = db.close()
}

internal class SqlCipherDbHandle(
    private val db: net.sqlcipher.database.SQLiteDatabase
) : DbHandle {
    override fun execSQL(sql: String) = db.execSQL(sql)

    @Suppress("UNCHECKED_CAST")
    override fun execSQL(sql: String, bindArgs: Array<out Any?>) =
        db.execSQL(sql, bindArgs as Array<Any?>)

    override fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor =
        db.rawQuery(sql, selectionArgs)

    override fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long =
        db.insert(table, nullColumnHack, values)

    override fun insertWithOnConflict(
        table: String,
        nullColumnHack: String?,
        values: ContentValues,
        conflictAlgorithm: Int
    ): Long = db.insertWithOnConflict(table, nullColumnHack, values, conflictAlgorithm)

    override fun insertOrThrow(table: String, nullColumnHack: String?, values: ContentValues): Long =
        db.insertOrThrow(table, nullColumnHack, values)

    override fun update(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?
    ): Int = db.update(table, values, whereClause, whereArgs)

    override fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int =
        db.delete(table, whereClause, whereArgs)

    override fun beginTransaction() = db.beginTransaction()
    override fun setTransactionSuccessful() = db.setTransactionSuccessful()
    override fun endTransaction() = db.endTransaction()
    override fun close() = db.close()
}

/** مساعد المنصة (نص صريح): JVM والتراجع عند تعذّر المكتبة الأصلية. */
internal class PlatformDbBackend(context: android.content.Context) : DbBackend {
    private val helper = PlatformDbHelper(context.applicationContext)

    override val writable: DbHandle get() = PlatformDbHandle(helper.writableDatabase)
    override val readable: DbHandle get() = PlatformDbHandle(helper.readableDatabase)
    override fun close() = helper.close()
}

/** مساعد SQLCipher: المسار الإنتاجي الوحيد على الجهاز. */
internal class SqlCipherDbBackend(
    context: android.content.Context,
    private val password: String
) : DbBackend {
    private val helper = SqlCipherDbHelper(context.applicationContext)

    override val writable: DbHandle
        get() = SqlCipherDbHandle(helper.getWritableDatabase(password))
    override val readable: DbHandle
        get() = SqlCipherDbHandle(helper.getReadableDatabase(password))

    override fun close() = helper.close()
}

internal class PlatformDbHelper(context: android.content.Context) :
    android.database.sqlite.SQLiteOpenHelper(
        context.applicationContext, StudyDb.DB_NAME, null, StudyDb.DB_VERSION
    ) {
    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        StudyDb.createSchema(PlatformDbHandle(db))
    }

    override fun onUpgrade(
        db: android.database.sqlite.SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        StudyDb.upgradeSchema(PlatformDbHandle(db), oldVersion, newVersion)
    }
}

internal class SqlCipherDbHelper(context: android.content.Context) :
    net.sqlcipher.database.SQLiteOpenHelper(
        context.applicationContext, StudyDb.DB_NAME, null, StudyDb.DB_VERSION
    ) {
    override fun onCreate(db: net.sqlcipher.database.SQLiteDatabase) {
        StudyDb.createSchema(SqlCipherDbHandle(db))
    }

    override fun onUpgrade(
        db: net.sqlcipher.database.SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        StudyDb.upgradeSchema(SqlCipherDbHandle(db), oldVersion, newVersion)
    }
}
