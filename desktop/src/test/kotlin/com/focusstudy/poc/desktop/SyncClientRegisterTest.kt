package com.focusstudy.poc.desktop

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * اختبارات إصلاح مسار الربط في [SyncClient.register] — خادم HTTP محلي
 * يحاكي `/api/sync` (JDK فقط، بلا شبكة خارجية)، فيعمل على Linux في CI.
 *
 * يغطي السبب الجذري: `register` لحساب موجود بلا `currentPassphrase`
 * يرفضه الخادم بـ 401؛ فالربط الأول من ويندوز لحساب سُجّل من الهاتف
 * كان يفشل بكلمة المرور الصحيحة نفسها.
 */
class SyncClientRegisterTest {

    private val profileId = "123e4567-e89b-12d3-a456-426614174000"
    private val passphrase = "كلمة-مرور-سرية-123456"

    private lateinit var server: HttpServer
    private val requests = ArrayList<String>()

    /** سلوك الخادم المحاكى: قائمة ردود (رمز، جسم) تُستهلك بالترتيب. */
    private var scripted = ArrayList<Pair<Int, String>>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/sync") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            synchronized(requests) { requests.add(body) }
            val (status, response) = synchronized(scripted) {
                if (scripted.isEmpty()) 500 to """{"error":"no_scripted_response"}"""
                else scripted.removeAt(0)
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = null
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun client(): SyncClient {
        val port = server.address.port
        return SyncClient("http://127.0.0.1:$port")
    }

    @Test
    fun linksExistingAccountWithSamePassphrase() {
        scripted.add(401 to """{"error":"invalid_credentials"}""")
        scripted.add(200 to """{"ok":true}""")
        val outcome = client().register(profileId, passphrase).outcome
        assertTrue(outcome is SyncOutcome.Ok)
        assertEquals(2, requests.size)
        // المحاولة الأولى بلا currentPassphrase أصلًا (kotlinx-serialization
        // تحذف الحقول null من JSON افتراضيًا)؛ والثانية تحمل كلمة المرور
        // نفسها كحالية.
        assertFalse(requests[0].contains("currentPassphrase"))
        assertTrue(requests[1].contains("\"currentPassphrase\":\"$passphrase\""))
        assertTrue(requests[1].contains("\"action\":\"register\""))
    }

    @Test
    fun newAccountRegistersOnFirstAttempt() {
        scripted.add(200 to """{"ok":true,"created":true}""")
        val result = client().register(profileId, passphrase)
        assertTrue(result.outcome is SyncOutcome.Ok)
        assertTrue(result.created)
        assertEquals(1, requests.size)
    }

    @Test
    fun existingAccountLinkReportsNotCreated() {
        scripted.add(401 to """{"error":"invalid_credentials"}""")
        scripted.add(200 to """{"ok":true,"created":false}""")
        val result = client().register(profileId, passphrase)
        assertTrue(result.outcome is SyncOutcome.Ok)
        assertFalse(result.created)
    }

    @Test
    fun wrongPassphraseFailsBothAttempts() {
        scripted.add(401 to """{"error":"invalid_credentials"}""")
        scripted.add(401 to """{"error":"invalid_credentials"}""")
        val outcome = client().register(profileId, "كلمة-مرور-خاطئة-123").outcome
        assertTrue(outcome is SyncOutcome.Error)
        assertEquals("بيانات الدخول غير صحيحة", (outcome as SyncOutcome.Error).message)
        assertEquals(2, requests.size)
    }

    @Test
    fun unknownProfileDoesNotRetry() {
        scripted.add(403 to """{"error":"profile_unknown"}""")
        val outcome = client().register(profileId, passphrase).outcome
        assertTrue(outcome is SyncOutcome.Error)
        assertEquals("هذا الحساب غير معروف على الخادم", (outcome as SyncOutcome.Error).message)
        assertEquals(1, requests.size)
    }

    @Test
    fun changePassphraseSendsCurrentAndNew() {
        scripted.add(200 to """{"ok":true}""")
        val outcome = client().changePassphrase(profileId, "كلمة-قديمة-123456", passphrase)
        assertTrue(outcome is SyncOutcome.Ok)
        assertEquals(1, requests.size)
        assertTrue(requests[0].contains("\"action\":\"changePassphrase\""))
        assertTrue(requests[0].contains("\"currentPassphrase\":\"كلمة-قديمة-123456\""))
        assertTrue(requests[0].contains("\"passphrase\":\"$passphrase\""))
    }

    @Test
    fun changePassphraseRejectsWrongCurrent() {
        scripted.add(401 to """{"error":"invalid_credentials"}""")
        val outcome = client().changePassphrase(profileId, "كلمة-خاطئة-123456", passphrase)
        assertTrue(outcome is SyncOutcome.Error)
        assertEquals(401, (outcome as SyncOutcome.Error).status)
    }

    @Test
    fun setRecoveryCodeSendsNormalizedCode() {
        scripted.add(200 to """{"ok":true}""")
        val code = "abcd-efgh-jkmn-pqrs-tuvw-xyz2-3456-789a"
        val outcome = client().setRecoveryCode(profileId, passphrase, code)
        assertTrue(outcome is SyncOutcome.Ok)
        assertTrue(requests[0].contains("\"action\":\"setRecoveryCode\""))
        assertTrue(requests[0].contains("\"recoveryCode\":\"ABCD-EFGH-JKMN-PQRS-TUVW-XYZ2-3456-789A\""))
    }

    @Test
    fun resetWithRecoveryCodeSendsCodeWithoutPassphrase() {
        scripted.add(200 to """{"ok":true}""")
        val outcome = client().resetWithRecoveryCode(profileId, "ABCD-EFGH-JKMN-PQRS-TUVW-XYZ2-3456-789A")
        assertTrue(outcome is SyncOutcome.Ok)
        assertTrue(requests[0].contains("\"action\":\"reset\""))
        assertTrue(requests[0].contains("\"recoveryCode\":\"ABCD-EFGH-JKMN-PQRS-TUVW-XYZ2-3456-789A\""))
    }

    @Test
    fun resetWithBadCodeFormatFailsLocally() {
        val outcome = client().resetWithRecoveryCode(profileId, "short")
        assertTrue(outcome is SyncOutcome.Error)
        assertEquals(0, requests.size)
    }

    @Test
    fun pushSendsBaseRevisionAndSurfacesStaleRevision() {
        scripted.add(409 to """{"error":"stale_revision","revision":7}""")
        val outcome = client().push(profileId, passphrase, SyncData(), baseRevision = 3L)
        assertTrue(outcome is SyncOutcome.Error)
        assertEquals(409, (outcome as SyncOutcome.Error).status)
        assertTrue(requests[0].contains("\"baseRevision\":3"))
    }

    @Test
    fun pushWithoutBaseRevisionOmitsTheField() {
        scripted.add(200 to """{"revision":1,"updatedAt":0,"data":{}}""")
        val outcome = client().push(profileId, passphrase, SyncData())
        assertTrue(outcome is SyncOutcome.Ok)
        assertFalse(requests[0].contains("baseRevision"))
    }
}
