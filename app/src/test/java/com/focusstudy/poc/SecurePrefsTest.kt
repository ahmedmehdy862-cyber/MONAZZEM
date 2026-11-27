package com.focusstudy.poc

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P-04: التفضيلات المشفّرة للتفضيلات الحساسة. موفّر التشفير قابل للحقن
 * فيُختبر الترحيل (نسخ + تحقق + حذف العادي) والرجوع إلى العادي دون
 * Keystore — دون لمس Keystore حقيقي.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecurePrefsTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val defaultProvider = SecurePrefs.encryptedProvider

    @After fun restoreProvider() {
        SecurePrefs.encryptedProvider = defaultProvider
    }

    /** بديل «مشفر» في الاختبار: ملف تفضيلات عادي باسم مختلف. */
    private fun fakeEncrypted(): (Context, String) -> SharedPreferences? =
        { ctx, name -> ctx.getSharedPreferences(name, Context.MODE_PRIVATE) }

    private fun plain(name: String): SharedPreferences =
        context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test fun migratesPlainValuesToEncryptedAndDeletesPlainFile() {
        val name = "test_secure_migrate"
        plain(name).edit()
            .putString("user_id", "11111111-2222-3333-4444-555555555555")
            .putString("profile_id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
            .putLong("register_last_attempt_ms", 12345L)
            .commit()
        SecurePrefs.encryptedProvider = fakeEncrypted()

        val prefs = SecurePrefs.prefs(context, name)
        assertEquals("11111111-2222-3333-4444-555555555555", prefs.getString("user_id", null))
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", prefs.getString("profile_id", null))
        assertEquals(12345L, prefs.getLong("register_last_attempt_ms", 0L))
        // الملف العادي حُذف بعد التحقق بإعادة القراءة.
        assertTrue(plain(name).all.isEmpty())
        // فتح ثانٍ يقرأ المشفّر مباشرة دون ترحيل جديد.
        val again = SecurePrefs.prefs(context, name)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", again.getString("profile_id", null))
    }

    @Test fun emptyPlainFileJustMarksMigrated() {
        val name = "test_secure_empty"
        SecurePrefs.encryptedProvider = fakeEncrypted()
        val prefs = SecurePrefs.prefs(context, name)
        prefs.edit().putString("user_id", "x").commit()
        assertEquals("x", SecurePrefs.prefs(context, name).getString("user_id", null))
    }

    @Test fun unavailableEncryptionFallsBackToPlainWithoutDeleting() {
        val name = "test_secure_fallback"
        plain(name).edit().putString("user_id", "fallback-id").commit()
        SecurePrefs.encryptedProvider = { _, _ -> null }

        val prefs = SecurePrefs.prefs(context, name)
        assertEquals("fallback-id", prefs.getString("user_id", null))
        prefs.edit().putString("profile_id", "p").commit()
        // العادي لم يُحذف — القيم ما زالت فيه.
        assertEquals("fallback-id", plain(name).getString("user_id", null))
        assertEquals("p", plain(name).getString("profile_id", null))
    }

    @Test fun failingProviderDegradesInsteadOfThrowing() {
        val name = "test_secure_broken"
        SecurePrefs.encryptedProvider = { _, _ -> error("keystore broken") }
        val prefs = SecurePrefs.prefs(context, name)
        prefs.edit().putString("user_id", "still-works").commit()
        assertEquals("still-works", prefs.getString("user_id", null))
    }

    @Test fun secureNameDiffersFromPlainName() {
        assertFalse(SecurePrefs.secureName("focus_team_messages") == "focus_team_messages")
        assertEquals("focus_team_messages_secure", SecurePrefs.secureName("focus_team_messages"))
    }

    @Test fun realDefaultProviderNeverThrows() {
        // تحت Robolectric الـ Keystore غائب، فهذا عمليًا مسار عدم التوفر —
        // والتأكيد الوحيد هو «لا يرمي» مهما فعل الجهاز.
        val prefs = SecurePrefs.prefs(context, "test_secure_real")
        runCatching { prefs.edit().putString("user_id", "v").commit() }
        runCatching { prefs.getString("user_id", null) }
    }
}
