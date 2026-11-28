package com.focusstudy.poc

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.security.SecureRandom

/**
 * P-04 — إدارة مفتاح تشفير قاعدة البيانات (ADR_002 «تم الاعتماد»).
 *
 * القرار (أحمد 2026-11-28 13:05): مفتاح عشوائي 32 بايت يُولَّد مرة واحدة
 * ويُحفظ في Android Keystore عبر EncryptedSharedPreferences — أبدًا PIN،
 * أبدًا مفتاح في الكود، أبدًا نص صريح.
 *
 * التخزين: ملف تفضيلات مشفّر مخصص `db_encryption_key_secure` (عبر
 * [SecurePrefs.defaultEncryptedPrefs] مباشرةً — لا عبر [SecurePrefs.prefs]،
 * لأن تراجعه إلى ملف عادي عند تعذّر Keystore كان سيعني حفظ مفتاح القاعدة
 * نصًا صريحًا بجانب القاعدة، أي لا تشفير أصلًا). إن تعذّر Keystore يُرجع
 * null: لا مفتاح يُنشأ ولا قاعدة مشفرة تُفتح — انظر [DatabaseEncryption].
 *
 * الصيغة: سلسلة hex من 64 حرفًا (32 بايت). SQLCipher يعامل سلسلة hex
 * بطول 64 حرفًا بالضبط كمادة مفتاح خام 256-بت، فتُمرَّر كما هي كلمة مرور.
 *
 * حالة الترحيل تعيش في الملف نفسه (`migration_state`):
 * NOT_STARTED / IN_PROGRESS / DONE — انظر [DatabaseEncryption].
 */
internal object DatabaseKeyManager {
    private const val TAG = "DatabaseKeyManager"
    const val PREFS_FILE = "db_encryption_key"
    private const val KEY_HEX = "key_hex"
    const val STATE_KEY = "migration_state"
    const val STATE_NOT_STARTED = "NOT_STARTED"
    const val STATE_IN_PROGRESS = "IN_PROGRESS"
    const val STATE_DONE = "DONE"

    /** قابل للحقن في الاختبارات دون لمس Keystore (نمط SecurePrefs نفسه). */
    internal var encryptedProvider: (Context, String) -> SharedPreferences? =
        SecurePrefs::defaultEncryptedPrefs

    private fun encryptedPrefs(context: Context): SharedPreferences? = runCatching {
        encryptedProvider(context.applicationContext, SecurePrefs.secureName(PREFS_FILE))
    }.getOrNull()

    /**
     * المفتاح الحالي، أو يُولَّد ويُحفظ إن لم يوجد. null فقط عندما يتعذّر
     * Keystore/التفضيلات المشفرة على هذا الجهاز — وعندها لا تشفير إطلاقًا.
     */
    fun getOrCreateKey(context: Context): String? {
        val prefs = encryptedPrefs(context) ?: run {
            Log.w(TAG, "Encrypted prefs unavailable — no database key (staying plaintext)")
            return null
        }
        prefs.getString(KEY_HEX, null)?.takeIf { isValidKeyHex(it) }?.let { return it }
        val fresh = generateKeyHex()
        if (!prefs.edit().putString(KEY_HEX, fresh).commit()) {
            Log.w(TAG, "Failed to persist generated database key — no encryption")
            return null
        }
        // تحقق بإعادة القراءة: مفتاح لا يُقرأ = مفتاح لا يُعتمد عليه.
        return prefs.getString(KEY_HEX, null)?.takeIf { it == fresh }
    }

    /** المفتاح المخزّن فقط — لا توليد. null إن لم يوجد أو تعذّر Keystore. */
    fun getExistingKey(context: Context): String? =
        encryptedPrefs(context)?.getString(KEY_HEX, null)?.takeIf { isValidKeyHex(it) }

    fun migrationState(context: Context): String =
        encryptedPrefs(context)?.getString(STATE_KEY, null) ?: STATE_NOT_STARTED

    fun setMigrationState(context: Context, state: String) {
        runCatching {
            encryptedPrefs(context)?.edit()?.putString(STATE_KEY, state)?.commit()
        }.onFailure { Log.w(TAG, "Failed to persist migration state $state", it) }
    }

    /** يحذف المفتاح وحالة الترحيل — يُستدعى فقط من مسح البيانات (P-02). */
    fun clearKey(context: Context) {
        val app = context.applicationContext
        runCatching { app.deleteSharedPreferences(SecurePrefs.secureName(PREFS_FILE)) }
        runCatching { app.deleteSharedPreferences(PREFS_FILE) }
    }

    /** 32 بايت عشوائية من SecureRandom كسلسلة hex من 64 حرفًا. */
    fun generateKeyHex(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** صالح = 64 حرف hex بالضبط (الصيغة التي يعاملها SQLCipher مفتاحًا خامًا). */
    fun isValidKeyHex(value: String?): Boolean =
        value != null && value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
