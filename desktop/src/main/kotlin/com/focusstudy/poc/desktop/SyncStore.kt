package com.focusstudy.poc.desktop

import java.io.File
import java.util.Properties

/**
 * حفظ هوية حساب المزامنة على ويندوز.
 *
 * يُحفظ profileId فقط (معرّف UUID يصدره الخادم) في ملف إعدادات محلي:
 *   %APPDATA%\Monazzem\sync.properties
 * كلمة المرور (passphrase) لا تُحفظ أبدًا — لا هنا ولا في قاعدة البيانات —
 * مطابقةً لسلوك أندرويد (SyncAccount: «لا تُحفظ على الجهاز»)؛ تُكتب عند كل
 * تشغيل وتبقى في الذاكرة فقط طوال الجلسة (SyncSession).
 *
 * قيد موثّق: الملف المحلي ليس مخزن نظام مشفّرًا (لا DPAPI/Credential Manager
 * في v1 بلا اعتماديات إضافية)؛ لكنه لا يحوي سرًا — profileId وحده لا يكفي
 * للمصادقة دون كلمة المرور، وملف الإعدادات مقصور على حساب مستخدم ويندوز
 * نفسه. تحسين لاحق ممكن: Windows Credential Manager عبر JNA.
 */
object SyncStore {
    private const val FILE_NAME = "sync.properties"
    private const val KEY_PROFILE_ID = "profileId"

    fun configDir(): File {
        val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
        return if (appData != null) File(appData, "Monazzem")
        else File(System.getProperty("user.home"), ".monazzem")
    }

    /** مجلد بيانات التطبيق كاملًا (قاعدة البيانات + الإعدادات). */
    fun dataDir(): File = configDir()

    fun loadProfileId(): String? {
        val file = File(configDir(), FILE_NAME)
        if (!file.isFile) return null
        return try {
            Properties().apply { file.inputStream().use { load(it) } }
                .getProperty(KEY_PROFILE_ID)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun saveProfileId(profileId: String) {
        val dir = configDir().apply { mkdirs() }
        val props = Properties().apply { setProperty(KEY_PROFILE_ID, profileId.trim()) }
        File(dir, FILE_NAME).outputStream().use { props.store(it, "Monazzem sync account — profileId only, no passphrase") }
    }

    fun clear() {
        File(configDir(), FILE_NAME).delete()
    }
}

/**
 * جلسة المزامنة: كلمة المرور في الذاكرة فقط، وتُمسح عند تسجيل الخروج.
 * كلمة المرور صالحة إن كان طولها بعد القص 10..200 حرفًا (نفس حدي الخادم
 * PASSWORD_MIN/MAX في sync-store.js وSyncPassphrase في أندرويد).
 */
class SyncSession {
    var profileId: String? = null
        private set
    private var passphrase: CharArray? = null

    val isLinked: Boolean get() = !profileId.isNullOrBlank() && passphrase != null

    fun link(profileId: String, passphrase: String) {
        require(isValidProfileId(profileId)) { "معرّف الحساب غير صالح" }
        require(isValidPassphrase(passphrase)) { "كلمة المرور بين 10 و 200 حرف" }
        this.profileId = profileId.trim()
        this.passphrase = passphrase.toCharArray()
        SyncStore.saveProfileId(this.profileId!!)
    }

    /** ربط من ملف الإعدادات عند بدء التشغيل؛ كلمة المرور تُطلب من المستخدم. */
    fun restoreProfileId(): Boolean {
        val saved = SyncStore.loadProfileId() ?: return false
        if (!isValidProfileId(saved)) return false
        profileId = saved
        return true
    }

    fun enterPassphrase(passphrase: String): Boolean {
        if (!isValidPassphrase(passphrase)) return false
        this.passphrase = passphrase.toCharArray()
        return true
    }

    fun passphrase(): String = passphrase?.concatToString().orEmpty()

    fun unlink() {
        passphrase?.fill('\u0000')
        passphrase = null
        profileId = null
        SyncStore.clear()
    }

    companion object {
        fun isValidPassphrase(value: String): Boolean =
            value.trim().length >= 10 && value.length <= 200

        /** UUID كما يصدره الخادم (normalizeProfileId في messaging-store). */
        fun isValidProfileId(value: String): Boolean =
            value.trim().matches(
                Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
            )
    }
}
