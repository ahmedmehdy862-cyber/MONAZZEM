package com.focusstudy.poc.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference

/**
 * مخزن كلمة مرور المزامنة في Windows Credential Manager (خزنة بيانات
 * اعتماد نظام التشغيل) عبر JNA.
 *
 * تنبيه: JNA لا يوفّر ربطًا جاهزًا لـ wincred.h — لا صنف `WinCred` ولا
 * دوال `Cred*` في jna-platform — لذلك يعرّف هذا الملف الربط بنفسه
 * ([Advapi32Cred] + [CredentialW] أدناه، حسب `CREDENTIALW` في wincred.h)،
 * ولا يحتاج إلا jna الأساسي (بلا jna-platform).
 *
 * الهدف: `MONAZZEM/sync/<profileId>` (بيانات اعتماد عامة Generic)،
 * والثبات `CRED_PERSIST_LOCAL_MACHINE`، والسرّ نفسه بايتات UTF-16LE.
 * كلمة المرور لا تُحفظ في أي ملف ولا في قاعدة البيانات أبدًا؛ الملف
 * المحلي (`SyncStore`) يحوي `profileId` وحده كما كان.
 *
 * حماية المنصة: كود JNA يعيش في [JnaCredApi] ولا يُلمس إلا على ويندوز؛
 * [WindowsCredentialStoreFactory.create] يعيد مخزنًا غير متاح (no-op)
 * على أي نظام آخر، فلا تُحمَّل أصناف JNA أصلًا خارج ويندوز — وهذا ما
 * يجعل اختبارات الوحدة تمر على Linux في CI. كل استدعاء JNA محاط
 * بـ try/catch، ففشل الخزنة لأي سبب لا يكسر التطبيق: يبقى الربط
 * في الذاكرة فقط طوال الجلسة (انظر `SyncSession.passphrasePersisted`).
 */
interface CredentialStore {
    /** هل خزنة النظام متاحة على هذا الجهاز؟ */
    val available: Boolean

    /** يحفظ كلمة المرور لحسابٍ ما؛ يعيد false عند الفشل (بلا استثناء). */
    fun save(profileId: String, passphrase: CharArray): Boolean

    /** يقرأ كلمة المرور المحفوظة، أو null عند غيابها/فشل القراءة. */
    fun load(profileId: String): CharArray?

    /** يحذف كلمة المرور المحفوظة؛ يعيد false عند الفشل (بلا استثناء). */
    fun delete(profileId: String): Boolean

    /** اسم الهدف في الخزنة لحسابٍ ما. */
    fun targetName(profileId: String): String = "MONAZZEM/sync/$profileId"
}

/** طبقة رقيقة فوق استدعاءات Credential Manager الخام — قابلة للمحاكاة في الاختبارات. */
interface CredApi {
    fun write(target: String, blob: ByteArray): Boolean
    fun read(target: String): ByteArray?
    fun delete(target: String): Boolean
}

/**
 * [CredentialStore] فوق [CredApi]؛ الترميز هنا (UTF-16LE) والخزنة نفسها
 * في [CredApi]، فيُختبر المنطق كاملًا على Linux بمحاكاة في الذاكرة.
 */
class WindowsCredentialStore(
    private val api: CredApi,
    override val available: Boolean = true,
) : CredentialStore {

    override fun save(profileId: String, passphrase: CharArray): Boolean {
        if (passphrase.isEmpty()) return false
        val bytes = passphrase.concatToString().toByteArray(Charsets.UTF_16LE)
        return try {
            api.write(targetName(profileId), bytes)
        } catch (_: Exception) {
            false
        } finally {
            bytes.fill(0)
        }
    }

    override fun load(profileId: String): CharArray? {
        val bytes = try {
            api.read(targetName(profileId))
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            String(bytes, Charsets.UTF_16LE).toCharArray()
        } finally {
            bytes.fill(0)
        }
    }

    override fun delete(profileId: String): Boolean = try {
        api.delete(targetName(profileId))
    } catch (_: Exception) {
        false
    }
}

/** ربط JNA الأدنى لـ wincred.h — يُبنى على ويندوز فقط، ولا يُلمس خارجه. */
internal interface Advapi32Cred : Library {
    fun CredWriteW(credential: CredentialW.ByReference, flags: Int): Boolean
    fun CredReadW(targetName: WString, type: Int, flags: Int, credential: PointerByReference): Boolean
    fun CredDeleteW(targetName: WString, type: Int, flags: Int): Boolean
    fun CredFree(credential: Pointer)
}

/** FILETIME من wincred.h: قيمتان 32-بت (لا نحتاجهما إلا لملء البنية). */
internal class FileTime : Structure() {
    @JvmField var dwLowDateTime: Int = 0
    @JvmField var dwHighDateTime: Int = 0
    override fun getFieldOrder(): List<String> = listOf("dwLowDateTime", "dwHighDateTime")
}

/**
 * بنية CREDENTIALW كما في wincred.h — الترتيب هنا هو الترتيب الثنائي
 * نفسه؛ أي تبديل يكسر القراءة/الكتابة بصمت. الحقول النصية WString
 * (UTF-16) لأن الدوال المستخدمة هي نسخ W. الحقول @JvmField إلزامية:
 * JNA يقرأ حقول البنية بالانعكاس ولا يرى خاصيات كوتلن بلا ظهر حقيقي.
 */
internal open class CredentialW : Structure {
    @JvmField var Flags: Int = 0
    @JvmField var Type: Int = 0
    @JvmField var TargetName: WString? = null
    @JvmField var Comment: WString? = null
    @JvmField var LastWritten: FileTime = FileTime()
    @JvmField var CredentialBlobSize: Int = 0
    @JvmField var CredentialBlob: Pointer? = null
    @JvmField var Persist: Int = 0
    @JvmField var AttributeCount: Int = 0
    @JvmField var Attributes: Pointer? = null
    @JvmField var TargetAlias: WString? = null
    @JvmField var UserName: WString? = null

    constructor() : super()
    constructor(pointer: Pointer) : super(pointer) {
        read()
    }

    override fun getFieldOrder(): List<String> = listOf(
        "Flags", "Type", "TargetName", "Comment", "LastWritten",
        "CredentialBlobSize", "CredentialBlob", "Persist", "AttributeCount",
        "Attributes", "TargetAlias", "UserName",
    )

    class ByReference : CredentialW(), Structure.ByReference

    companion object {
        const val CRED_TYPE_GENERIC = 1
        const val CRED_PERSIST_LOCAL_MACHINE = 2
    }
}

/** [CredApi] حقيقي فوق JNA — يُبنى على ويندوز فقط، ولا يُلمس خارجه. */
internal class JnaCredApi : CredApi {
    // يُحمَّل Advapi32.dll عند أول استخدام فقط؛ إنشاء JnaCredApi نفسه
    // لا يحدث إلا عبر WindowsCredentialStoreFactory على ويندوز.
    private val lib: Advapi32Cred by lazy {
        Native.load("Advapi32", Advapi32Cred::class.java)
    }

    override fun write(target: String, blob: ByteArray): Boolean {
        if (blob.isEmpty()) return false
        val memory = Memory(blob.size.toLong())
        memory.write(0, blob, 0, blob.size)
        val credential = CredentialW.ByReference()
        credential.Flags = 0
        credential.Type = CredentialW.CRED_TYPE_GENERIC
        credential.TargetName = WString(target)
        credential.Comment = WString("Monazzem sync passphrase")
        credential.CredentialBlobSize = blob.size
        credential.CredentialBlob = memory
        credential.Persist = CredentialW.CRED_PERSIST_LOCAL_MACHINE
        credential.UserName = WString(target)
        credential.write()
        return runCatching { lib.CredWriteW(credential, 0) }.getOrDefault(false)
    }

    override fun read(target: String): ByteArray? {
        val ref = PointerByReference()
        val ok = runCatching {
            lib.CredReadW(WString(target), CredentialW.CRED_TYPE_GENERIC, 0, ref)
        }.getOrDefault(false)
        if (!ok) return null
        val pointer: Pointer = ref.value ?: return null
        return try {
            val credential = CredentialW(pointer)
            val size = credential.CredentialBlobSize
            val blob = credential.CredentialBlob ?: return null
            if (size <= 0) null else blob.getByteArray(0, size)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { lib.CredFree(pointer) }
        }
    }

    override fun delete(target: String): Boolean =
        runCatching { lib.CredDeleteW(WString(target), CredentialW.CRED_TYPE_GENERIC, 0) }
            .getOrDefault(false)
}

/** [CredApi] لا يعمل شيئًا — لأنظمة غير ويندوز. */
internal object NoOpCredApi : CredApi {
    override fun write(target: String, blob: ByteArray): Boolean = false
    override fun read(target: String): ByteArray? = null
    override fun delete(target: String): Boolean = false
}

object WindowsCredentialStoreFactory {
    fun isWindowsOs(): Boolean =
        System.getProperty("os.name", "").contains("win", ignoreCase = true)

    /** على ويندوز: مخزن JNA حقيقي. خارجه: مخزن غير متاح لا يلمس JNA. */
    fun create(): CredentialStore =
        if (isWindowsOs()) WindowsCredentialStore(JnaCredApi())
        else WindowsCredentialStore(NoOpCredApi, available = false)
}
