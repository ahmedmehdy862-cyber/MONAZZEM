package com.focusstudy.poc.desktop

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
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
        val outcome = client().register(profileId, passphrase)
        assertTrue(outcome is SyncOutcome.Ok)
        assertEquals(2, requests.size)
        // المحاولة الثانية تحمل كلمة المرور نفسها كحالية.
        assertTrue(requests[0].contains("\"currentPassphrase\":null"))
        assertTrue(requests[1].contains("\"currentPassphrase\":\"$passphrase\""))
        assertTrue(requests[1].contains("\"action\":\"register\""))
    }

    @Test
    fun newAccountRegistersOnFirstAttempt() {
        scripted.add(200 to """{"ok":true}""")
        val outcome = client().register(profileId, passphrase)
        assertTrue(outcome is SyncOutcome.Ok)
        assertEquals(1, requests.size)
    }

    @Test
    fun wrongPassphraseFailsBothAttempts() {
        scripted.add(401 to """{"error":"invalid_credentials"}""")
        scripted.add(401 to """{"error":"invalid_credentials"}""")
        val outcome = client().register(profileId, "كلمة-مرور-خاطئة-123")
        assertTrue(outcome is SyncOutcome.Error)
        assertEquals("بيانات الدخول غير صحيحة", (outcome as SyncOutcome.Error).message)
        assertEquals(2, requests.size)
    }

    @Test
    fun unknownProfileDoesNotRetry() {
        scripted.add(403 to """{"error":"profile_unknown"}""")
        val outcome = client().register(profileId, passphrase)
        assertTrue(outcome is SyncOutcome.Error)
        assertEquals("هذا الحساب غير معروف على الخادم", (outcome as SyncOutcome.Error).message)
        assertEquals(1, requests.size)
    }

    @Test
    fun explicitCurrentPassphraseIsNotRetried() {
        scripted.add(401 to """{"error":"invalid_credentials"}""")
        val outcome = client().register(profileId, passphrase, currentPassphrase = "كلمة-قديمة-123456")
        assertTrue(outcome is SyncOutcome.Error)
        assertEquals(1, requests.size)
        assertTrue(requests[0].contains("\"currentPassphrase\":\"كلمة-قديمة-123456\""))
    }
}
