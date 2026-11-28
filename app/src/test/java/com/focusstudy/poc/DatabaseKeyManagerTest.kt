package com.focusstudy.poc

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P-04: إدارة مفتاح تشفير قاعدة البيانات. موفّر التشفير قابل للحقن
 * فيُختبر التوليد والتخزين وحالة الترحيل دون Keystore حقيقي —
 * دون أي استدعاء SQLCipher أصلي (منطق JVM خالص + SharedPreferences).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseKeyManagerTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val defaultProvider = DatabaseKeyManager.encryptedProvider

    @After
    fun restoreProvider() {
        DatabaseKeyManager.encryptedProvider = defaultProvider
        DatabaseKeyManager.clearKey(context)
    }

    /** بديل «مشفر» في الاختبار: ملف تفضيلات عادي باسم مختلف. */
    private fun fakeEncrypted(): (Context, String) -> SharedPreferences? =
        { ctx, name -> ctx.getSharedPreferences(name, Context.MODE_PRIVATE) }

    @Test
    fun generateKeyHexIs64LowerHexCharsAndUnique() {
        val first = DatabaseKeyManager.generateKeyHex()
        val second = DatabaseKeyManager.generateKeyHex()
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(DatabaseKeyManager.isValidKeyHex(first))
        assertNotEquals(first, second)
    }

    @Test
    fun isValidKeyHexRejectsBadValues() {
        assertFalse(DatabaseKeyManager.isValidKeyHex(null))
        assertFalse(DatabaseKeyManager.isValidKeyHex(""))
        assertFalse(DatabaseKeyManager.isValidKeyHex("ab".repeat(31))) // 62
        assertFalse(DatabaseKeyManager.isValidKeyHex("ab".repeat(32) + "c")) // 65
        assertFalse(DatabaseKeyManager.isValidKeyHex("zz".repeat(32))) // not hex
        assertFalse(DatabaseKeyManager.isValidKeyHex("ab ".repeat(16)))
        assertTrue(DatabaseKeyManager.isValidKeyHex("AB".repeat(32))) // hex, any case
    }

    @Test
    fun getOrCreateKeyGeneratesOnceAndReturnsStableKey() {
        DatabaseKeyManager.encryptedProvider = fakeEncrypted()
        val first = DatabaseKeyManager.getOrCreateKey(context)
        assertTrue(DatabaseKeyManager.isValidKeyHex(first))
        val second = DatabaseKeyManager.getOrCreateKey(context)
        assertEquals(first, second)
        // مخزّن فعلًا في ملف التفضيلات المشفرة، لا يُعاد توليده.
        val stored = context.getSharedPreferences(
            SecurePrefs.secureName(DatabaseKeyManager.PREFS_FILE), Context.MODE_PRIVATE
        ).getString("key_hex", null)
        assertEquals(first, stored)
    }

    @Test
    fun getExistingKeyDoesNotGenerate() {
        DatabaseKeyManager.encryptedProvider = fakeEncrypted()
        assertNull(DatabaseKeyManager.getExistingKey(context))
        val prefs = context.getSharedPreferences(
            SecurePrefs.secureName(DatabaseKeyManager.PREFS_FILE), Context.MODE_PRIVATE
        )
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun getOrCreateKeyReturnsNullWhenEncryptionUnavailable() {
        DatabaseKeyManager.encryptedProvider = { _, _ -> null }
        assertNull(DatabaseKeyManager.getOrCreateKey(context))
        assertNull(DatabaseKeyManager.getExistingKey(context))
        assertEquals(
            DatabaseKeyManager.STATE_NOT_STARTED,
            DatabaseKeyManager.migrationState(context)
        )
    }

    @Test
    fun migrationStateDefaultsToNotStartedAndRoundTrips() {
        DatabaseKeyManager.encryptedProvider = fakeEncrypted()
        assertEquals(
            DatabaseKeyManager.STATE_NOT_STARTED,
            DatabaseKeyManager.migrationState(context)
        )
        DatabaseKeyManager.setMigrationState(context, DatabaseKeyManager.STATE_IN_PROGRESS)
        assertEquals(
            DatabaseKeyManager.STATE_IN_PROGRESS,
            DatabaseKeyManager.migrationState(context)
        )
        DatabaseKeyManager.setMigrationState(context, DatabaseKeyManager.STATE_DONE)
        assertEquals(
            DatabaseKeyManager.STATE_DONE,
            DatabaseKeyManager.migrationState(context)
        )
    }

    @Test
    fun clearKeyDeletesStoredKey() {
        DatabaseKeyManager.encryptedProvider = fakeEncrypted()
        val key = DatabaseKeyManager.getOrCreateKey(context)
        assertTrue(DatabaseKeyManager.isValidKeyHex(key))
        DatabaseKeyManager.clearKey(context)
        assertNull(DatabaseKeyManager.getExistingKey(context))
    }
}
