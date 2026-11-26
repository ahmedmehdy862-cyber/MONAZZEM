package com.focusstudy.poc.desktop

import com.sun.jna.Native
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مخزن كلمة المرور — تعمل على Linux في CI بلا ويندوز وبلا JNA
 * حقيقي: [FakeCredApi] تحاكي الخزنة في الذاكرة، والترميز (UTF-16LE)
 * والتسمية يُختبران عليها. مسار ويندوز الحقيقي (JnaCredApi) لا يُلمس
 * هنا؛ يتحقق منه الاختبار الحي على جهاز ويندوز.
 */
class CredentialStoreTest {

    /** محاكاة خزنة في الذاكرة. */
    class FakeCredApi : CredApi {
        val stored = LinkedHashMap<String, ByteArray>()
        var writes = 0

        override fun write(target: String, blob: ByteArray): Boolean {
            stored[target] = blob.copyOf()
            writes++
            return true
        }

        override fun read(target: String): ByteArray? = stored[target]?.copyOf()
        override fun delete(target: String): Boolean = stored.remove(target) != null
    }

    private val profileId = "123e4567-e89b-12d3-a456-426614174000"
    private val passphrase = "كلمة-مرور-سرية-123456"

    @Test
    fun targetNameFormat() {
        val store = WindowsCredentialStore(FakeCredApi())
        assertEquals("MONAZZEM/sync/$profileId", store.targetName(profileId))
    }

    @Test
    fun saveLoadRoundTrip() {
        val store = WindowsCredentialStore(FakeCredApi())
        assertTrue(store.save(profileId, passphrase.toCharArray()))
        assertEquals(passphrase, store.load(profileId)?.concatToString())
    }

    @Test
    fun loadMissingReturnsNull() {
        val store = WindowsCredentialStore(FakeCredApi())
        assertNull(store.load(profileId))
    }

    @Test
    fun deleteRemovesEntry() {
        val api = FakeCredApi()
        val store = WindowsCredentialStore(api)
        store.save(profileId, passphrase.toCharArray())
        assertTrue(store.delete(profileId))
        assertNull(store.load(profileId))
        assertFalse(api.stored.containsKey(store.targetName(profileId)))
    }

    @Test
    fun overwriteReplacesPassphrase() {
        val store = WindowsCredentialStore(FakeCredApi())
        store.save(profileId, passphrase.toCharArray())
        val replacement = "كلمة-مرور-جديدة-abcdef"
        assertTrue(store.save(profileId, replacement.toCharArray()))
        assertEquals(replacement, store.load(profileId)?.concatToString())
    }

    @Test
    fun blobIsUtf16Le() {
        val api = FakeCredApi()
        val store = WindowsCredentialStore(api)
        store.save(profileId, passphrase.toCharArray())
        val bytes = api.stored.getValue(store.targetName(profileId))
        assertEquals(passphrase, String(bytes, Charsets.UTF_16LE))
    }

    @Test
    fun emptyPassphraseIsNotSaved() {
        val api = FakeCredApi()
        val store = WindowsCredentialStore(api)
        assertFalse(store.save(profileId, CharArray(0)))
        assertEquals(0, api.writes)
    }

    @Test
    fun credentialStructureLayoutMatchesWincred() {
        // CREDENTIALW على 64-بت = 80 بايتًا (مؤشرات 8 + FILETIME 8 +
        // حشو المحاذاة)؛ أي تبديل في ترتيب الحقول أو نوعها يغيّر الحجم
        // ويفشل هذا الاختبار على Linux قبل أن يكسر ويندوز بصمت.
        if (Native.POINTER_SIZE == 8) {
            assertEquals(80, CredentialW.ByReference().size())
        }
    }

    @Test
    fun factoryAvailabilityMatchesOs() {
        val store = WindowsCredentialStoreFactory.create()
        assertEquals(WindowsCredentialStoreFactory.isWindowsOs(), store.available)
        if (!WindowsCredentialStoreFactory.isWindowsOs()) {
            // على Linux: مخزن غير متاح، وعملياته no-op بلا استثناء.
            assertFalse(store.save(profileId, passphrase.toCharArray()))
            assertNull(store.load(profileId))
            assertFalse(store.delete(profileId))
        }
    }
}
