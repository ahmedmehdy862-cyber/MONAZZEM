package com.focusstudy.poc.desktop

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * اختبارات [SyncSession] مع مخزن مزيف — تعمل على Linux في CI.
 *
 * ملاحظة: `link` يكتب `profileId` في ملف الإعدادات الحقيقي
 * (`SyncStore.saveProfileId`)؛ تُنظَّف تلك الكتابة في [tearDown] حتى لا
 * تلوث بيئة المطوّر. كلمة المرور نفسها لا تلمس أي ملف أبدًا.
 */
class SyncSessionVaultTest {

    private val profileId = "123e4567-e89b-12d3-a456-426614174000"
    private val passphrase = "كلمة-مرور-سرية-123456"

    @Before
    fun setUp() {
        SyncStore.clear()
    }

    @After
    fun tearDown() {
        SyncStore.clear()
    }

    @Test
    fun linkPersistsPassphraseToVault() {
        val api = CredentialStoreTest.FakeCredApi()
        val session = SyncSession(WindowsCredentialStore(api))
        session.link(profileId, passphrase)
        assertTrue(session.isLinked)
        assertTrue(session.passphrasePersisted)
        assertEquals(passphrase, session.passphrase())
        assertEquals(
            passphrase,
            WindowsCredentialStore(api).load(profileId)?.concatToString(),
        )
    }

    @Test
    fun restoreFromVaultRelinksAcrossRestart() {
        val api = CredentialStoreTest.FakeCredApi()
        SyncSession(WindowsCredentialStore(api)).link(profileId, passphrase)

        // جلسة جديدة كما بعد إعادة التشغيل: بلا كلمة مرور في الذاكرة.
        val restarted = SyncSession(WindowsCredentialStore(api))
        assertFalse(restarted.isLinked)
        assertTrue(restarted.restoreFromVault())
        assertTrue(restarted.isLinked)
        assertTrue(restarted.passphrasePersisted)
        assertEquals(passphrase, restarted.passphrase())
    }

    @Test
    fun unlinkDeletesVaultEntry() {
        val api = CredentialStoreTest.FakeCredApi()
        val session = SyncSession(WindowsCredentialStore(api))
        session.link(profileId, passphrase)
        session.unlink()
        assertFalse(session.isLinked)
        assertFalse(session.passphrasePersisted)
        assertNull(WindowsCredentialStore(api).load(profileId))
        assertNull(SyncStore.loadProfileId())
    }

    @Test
    fun linkStillWorksWhenVaultUnavailable() {
        // خزنة غير متاحة (Linux / فشل JNA): الربط ينجح في الذاكرة فقط،
        // والمزامنة التلقائية تعمل طوال الجلسة؛ بلا ادعاء حفظ.
        val unavailable = WindowsCredentialStore(CredentialStoreTest.FakeCredApi(), available = false)
        val failing = object : CredentialStore by unavailable {
            override fun save(profileId: String, passphrase: CharArray): Boolean = false
            override fun load(profileId: String): CharArray? = null
            override fun delete(profileId: String): Boolean = false
        }
        val session = SyncSession(failing)
        session.link(profileId, passphrase)
        assertTrue(session.isLinked)
        assertFalse(session.passphrasePersisted)

        val restarted = SyncSession(failing)
        assertFalse(restarted.restoreFromVault())
        assertFalse(restarted.isLinked)
    }

    @Test
    fun enterPassphrasePersistsItToo() {
        val api = CredentialStoreTest.FakeCredApi()
        val session = SyncSession(WindowsCredentialStore(api))
        session.link(profileId, passphrase)
        // محاكاة فتحٍ فقدت فيه الخزنة كلمة المرور: إدخالها يدويًا يعيد حفظها.
        api.stored.clear()
        val fresh = SyncSession(WindowsCredentialStore(api))
        assertTrue(fresh.restoreProfileId())
        assertTrue(fresh.enterPassphrase(passphrase))
        assertTrue(fresh.passphrasePersisted)
        assertEquals(passphrase, WindowsCredentialStore(api).load(profileId)?.concatToString())
    }
}
