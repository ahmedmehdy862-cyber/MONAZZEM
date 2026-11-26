package com.focusstudy.poc.desktop

import java.io.File
import java.util.Properties

/**
 * حفظ هوية حساب المزامنة على ويندوز.
 *
 * يُحفظ profileId فقط (معرّف UUID يصدره الخادم) في ملف إعدادات محلي:
 *   %APPDATA%\Monazzem\sync.properties
 * كلمة المرور (passphrase) تُحفظ في Windows Credential Manager (خزنة
 * بيانات اعتماد نظام التشغيل، عبر JNA — انظر WindowsCredentialStore)
 * بهدف `MONAZZEM/sync/<profileId>`، ولا تُحفظ في أي ملف ولا في قاعدة
 * البيانات أبدًا. هذا يختلف عن أندرويد (SyncAccount: «لا تُحفظ على
 * الجهاز») عمدًا: بلا حفظٍ آمن لا تعيش المزامنة التلقائية بعد إعادة
 * التشغيل، والخزنة هي المكان الذي صُمم لهذا السرّ في ويندوز — مقصورة
 * على حساب مستخدم ويندوز نفسه، ولا يقرؤها إلا هذا التطبيق.
 *
 * إن تعذّرت الخزنة لأي سبب (نظام غير ويندوز، أو فشل JNA)، يبقى الربط
 * في الذاكرة فقط طوال الجلسة (`passphrasePersisted == false`)، وتُطلب
 * كلمة المرور مرة واحدة عند كل فتح — والمزامنة التلقائية تعمل طوال
 * الجلسة بعدها. لا تُدَّعى تلقائية غير موجودة.
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
 * جلسة المزامنة: كلمة المرور في الذاكرة طوال الجلسة، وتُحفظ أيضًا في
 * Windows Credential Manager عند الربط (انظر توثيق SyncStore أعلاه)،
 * فتُستعاد عند بدء التشغيل وتعمل المزامنة التلقائية بلا إدخال جديد.
 * كلمة المرور صالحة إن كان طولها بعد القص 10..200 حرفًا (نفس حدي الخادم
 * PASSWORD_MIN/MAX في sync-store.js وSyncPassphrase في أندرويد).
 *
 * @param credentialStore مخزن كلمة المرور؛ افتراضيًا خزنة النظام،
 *   ويُحقن مخزن مزيف في الاختبارات.
 */
class SyncSession(
    private val credentialStore: CredentialStore = WindowsCredentialStoreFactory.create(),
) {
    var profileId: String? = null
        private set
    private var passphrase: CharArray? = null

    /** هل خزنة النظام متاحة على هذا الجهاز؟ */
    val vaultAvailable: Boolean get() = credentialStore.available

    /** هل كلمة المرور الحالية محفوظة في الخزنة (فتعيش بعد إعادة التشغيل)؟ */
    var passphrasePersisted: Boolean = false
        private set

    val isLinked: Boolean get() = !profileId.isNullOrBlank() && passphrase != null

    fun link(profileId: String, passphrase: String) {
        require(isValidProfileId(profileId)) { "معرّف الحساب غير صالح" }
        require(isValidPassphrase(passphrase)) { "كلمة المرور بين 10 و 200 حرف" }
        this.profileId = profileId.trim()
        this.passphrase = passphrase.toCharArray()
        SyncStore.saveProfileId(this.profileId!!)
        // أفضل جهد: فشل الخزنة لا يمنع الربط؛ يبقى في الذاكرة فقط.
        passphrasePersisted = credentialStore.save(this.profileId!!, this.passphrase!!)
    }

    /** ربط من ملف الإعدادات عند بدء التشغيل؛ كلمة المرور تُطلب من المستخدم. */
    fun restoreProfileId(): Boolean {
        val saved = SyncStore.loadProfileId() ?: return false
        if (!isValidProfileId(saved)) return false
        profileId = saved
        return true
    }

    /**
     * استعادة كاملة عند بدء التشغيل: profileId من الملف + كلمة المرور
     * من خزنة النظام. تعيد false إن غاب أحدهما (فيُطلب من المستخدم
     * مرة واحدة، ثم تعمل المزامنة التلقائية طوال الجلسة).
     */
    fun restoreFromVault(): Boolean {
        if (!restoreProfileId()) return false
        val saved = credentialStore.load(profileId!!) ?: return false
        if (!isValidPassphrase(saved.concatToString())) {
            saved.fill('\u0000')
            return false
        }
        passphrase = saved
        passphrasePersisted = true
        return true
    }

    fun enterPassphrase(passphrase: String): Boolean {
        if (!isValidPassphrase(passphrase)) return false
        this.passphrase = passphrase.toCharArray()
        // إدخالها يدويًا بعد فتحٍ بلا خزنة يُحفظها أيضًا للمزامنة التلقائية لاحقًا.
        if (profileId != null) {
            passphrasePersisted = credentialStore.save(profileId!!, this.passphrase!!)
        }
        return true
    }

    fun passphrase(): String = passphrase?.concatToString().orEmpty()

    fun unlink() {
        val id = profileId
        if (id != null) runCatching { credentialStore.delete(id) }
        passphrase?.fill('\u0000')
        passphrase = null
        profileId = null
        passphrasePersisted = false
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
