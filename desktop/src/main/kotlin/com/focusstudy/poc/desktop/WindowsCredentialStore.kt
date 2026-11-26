package com.focusstudy.poc.desktop

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.WinCred

/**
 * مخزن كلمة مرور المزامنة في Windows Credential Manager (خزنة بيانات
 * اعتماد نظام التشغيل) عبر JNA — CredWrite/CredRead/CredDelete.
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

/** [CredApi] حقيقي فوق JNA — يُبنى على ويندوز فقط، ولا يُلمس خارجه. */
internal class JnaCredApi : CredApi {
    override fun write(target: String, blob: ByteArray): Boolean {
        if (blob.isEmpty()) return false
        val memory = Memory(blob.size.toLong())
        memory.write(0, blob, 0, blob.size)
        val credential = WinCred.CREDENTIAL()
        credential.Flags = 0
        credential.Type = WinCred.CRED_TYPE_GENERIC
        credential.TargetName = target
        credential.Comment = "Monazzem sync passphrase"
        credential.CredentialBlobSize = blob.size
        credential.CredentialBlob = memory
        credential.Persist = WinCred.CRED_PERSIST_LOCAL_MACHINE
        credential.UserName = target
        credential.write()
        return runCatching { Advapi32.INSTANCE.CredWrite(credential, 0) }.getOrDefault(false)
    }

    override fun read(target: String): ByteArray? {
        val ref = PointerByReference()
        val ok = runCatching {
            Advapi32.INSTANCE.CredRead(target, WinCred.CRED_TYPE_GENERIC, 0, ref)
        }.getOrDefault(false)
        if (!ok) return null
        val pointer: Pointer = ref.value ?: return null
        return try {
            val credential = WinCred.CREDENTIAL(pointer)
            credential.read()
            val size = credential.CredentialBlobSize
            val blob = credential.CredentialBlob ?: return null
            if (size <= 0) null else blob.getByteArray(0, size)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { Advapi32.INSTANCE.CredFree(pointer) }
        }
    }

    override fun delete(target: String): Boolean =
        runCatching { Advapi32.INSTANCE.CredDelete(target, WinCred.CRED_TYPE_GENERIC, 0) }
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
