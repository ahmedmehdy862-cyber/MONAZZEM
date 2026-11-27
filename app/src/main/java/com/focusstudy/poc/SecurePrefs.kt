package com.focusstudy.poc

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * P-04 — تعميم التفضيلات المشفّرة على التفضيلات الحساسة (MA-H4).
 *
 * يفتح ملف تفضيلات مشفّرًا (`<name>_secure`: مفاتيح AES256-SIV وقيم
 * AES256-GCM، والمفتاح الرئيسي في Android Keystore ولا يدخل مساحة
 * التطبيق) أو يرجع الملف العادي إن تعذّر التشفير على هذا الجهاز
 * (Keystore معطوب أو محاكٍ بلا Keystore) — **ولا يرمي أبدًا**.
 *
 * الترحيل (مرة واحدة): عند أول فتح مشفّر تُنسخ قيم الملف العادي كاملةً
 * إلى المشفّر، وتُتحقق بإعادة القراءة، ثم يُحذف الملف العادي فقط بعد
 * نجاح التحقق. أي فشل في أي خطوة يبقي الملف العادي كما هو ويُرجع
 * التفضيلات العادية — فلا تفقد الهوية أبدًا بسبب التشفير.
 *
 * حدود معلنة: إن تعطّل الـ Keystore *بعد* اكتمال الترحيل، يرجع هذا
 * الكائن ملفًا عاديًا فارغًا فيُولّد [UserIdentity.id] معرّف تثبيت
 * جديدًا (سلوك تثبيت جديد؛ واستعادة الحساب ممكنة بإثبات الملكية H2 —
 * كلمة مرور المزامنة). التشفير هنا يخص التفضيلات فقط؛ قاعدة بيانات
 * Room ما زالت نصًا صريحًا (قرار SQLCipher موثّق في
 * `docs/decisions/ADR_002_AT_REST_ENCRYPTION.md`).
 */
internal object SecurePrefs {
    /** قابل للحقن في الاختبارات (تفضيلات عادية أو null) دون لمس Keystore. */
    internal var encryptedProvider: (Context, String) -> SharedPreferences? = ::defaultEncryptedPrefs
    private const val MARKER = "__secure_migrated__"
    private val lock = Any()

    fun prefs(context: Context, fileName: String): SharedPreferences {
        synchronized(lock) {
            val encrypted = runCatching {
                encryptedProvider(context.applicationContext, secureName(fileName))
            }.getOrNull() ?: return plain(context, fileName)
            runCatching { migrateOnce(context, fileName, encrypted) }
            return encrypted
        }
    }

    internal fun secureName(fileName: String) = "${fileName}_secure"

    private fun plain(context: Context, fileName: String): SharedPreferences =
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    /**
     * ينسخ قيم الملف العادي إلى المشفّر مرة واحدة. الاسم المشفّر مختلف
     * عن العادي عمدًا: فتح EncryptedSharedPreferences على ملف XML عادي
     * موجود يفشل (لا يمكن فك تشفيره)، فلا ترحيل بنفس الاسم أبدًا.
     */
    private fun migrateOnce(context: Context, fileName: String, encrypted: SharedPreferences) {
        if (encrypted.getBoolean(MARKER, false)) return
        val entries = plain(context, fileName).all
        if (entries.isEmpty()) {
            encrypted.edit().putBoolean(MARKER, true).commit()
            return
        }
        val editor = encrypted.edit()
        entries.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
            }
        }
        editor.putBoolean(MARKER, true)
        if (!editor.commit()) return // لا يُحذف العادي إن فشل الحفظ المشفّر
        val verified = entries.all { (key, value) -> encrypted.all[key] == value }
        if (!verified) return
        context.deleteSharedPreferences(fileName)
    }

    /** Null عندما يتعذّر فتح الـ Keystore/التفضيلات المشفّرة على هذا الجهاز. */
    fun defaultEncryptedPrefs(context: Context, fileName: String): SharedPreferences? = runCatching {
        // security-crypto 1.0.0 (مستقر): MasterKeys.getOrCreate هي API هذا
        // الإصدار — MasterKey.Builder موجود فقط من 1.1.0-alpha.
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            fileName,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()
}
