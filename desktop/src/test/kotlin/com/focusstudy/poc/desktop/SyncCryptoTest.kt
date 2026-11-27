package com.focusstudy.poc.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات تشفير المزامنة طرف-لطرف (H1).
 *
 * متجهات scrypt من RFC 7914 (§10)، ومتجهات الملح/المفتاح/GCM محسوبة
 * بـ Node crypto (نفس الخوارزميات القياسية) — فأي خطأ في تنفيذ
 * scrypt أو AES-GCM أو JSON القانوني يفشل هنا فورًا قبل أي نشر.
 */
class SyncCryptoTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    @Test
    fun `scrypt matches RFC 7914 vectors`() {
        // RFC 7914 §10: scrypt("", "", N=16, r=1, p=1, 64)
        assertEquals(
            "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442" +
                "fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
            hex(SyncCrypto.scrypt(ByteArray(0), ByteArray(0), 16, 1, 1, 64))
        )
        // RFC 7914 §10: scrypt("password", "NaCl", N=1024, r=8, p=16, 64)
        assertEquals(
            "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b3731622" +
                "eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            hex(
                SyncCrypto.scrypt(
                    "password".toByteArray(Charsets.UTF_8),
                    "NaCl".toByteArray(Charsets.UTF_8),
                    1024, 8, 16, 64
                )
            )
        )
    }

    @Test
    fun `salt and key derivation match Node vectors`() {
        val profileId = "11111111-2222-3333-4444-555555555555"
        assertEquals(
            "4d0cff6b1d717fdafc810307d09de2cd8cba40bbfbef75d7aef836673897415c",
            hex(SyncCrypto.saltFor(profileId))
        )
        assertEquals(
            "c9ee7e0c7ca09f27151e24a461128e1cd9db67f530c3facb3771dfddb83bbb68",
            hex(SyncCrypto.deriveKey("correct horse battery staple", profileId))
        )
    }

    @Test
    fun `decrypts Node-produced AES-GCM envelope`() {
        val key = SyncCrypto.deriveKey(
            "correct horse battery staple",
            "11111111-2222-3333-4444-555555555555"
        )
        val enc = "AQIDBAUGBwgJCgsMvq1lqXvIeq45UsatXGawI04oXnKqMKRRcCvsUmKhWcJEGYJUy8Nc4iZqKGf3k5x4mTzAMppSrN18EQqCnXB3a6Xc8jTE28cd/zoO3Pcm6vwFfXJ0whGrl7+gpKJFV6Diq1nAiQ=="
        val fields = SyncCrypto.decryptRecord(key, "tasks", "42", enc)
        assertEquals("study", fields?.get("category"))
        assertEquals(30L, fields?.get("minutes"))
        assertEquals(1L, fields?.get("priority"))
        assertEquals("todo", fields?.get("status"))
        assertEquals("review math", fields?.get("title"))
    }

    @Test
    fun `encrypt then decrypt round-trips including Arabic and doubles`() {
        val key = SyncCrypto.deriveKey("كلمة مرور قوية 123", "some-profile")
        val fields = mapOf<String, Any>(
            "title" to "مراجعة درس الرياضيات",
            "weight" to 70.5,
            "count" to 30.0,
            "done" to false
        )
        val enc = SyncCrypto.encryptRecord(key, "healthLogs", "h1", fields)
        val back = SyncCrypto.decryptRecord(key, "healthLogs", "h1", enc)
        assertEquals("مراجعة درس الرياضيات", back?.get("title"))
        assertEquals(70.5, back?.get("weight"))
        assertEquals(30L, back?.get("count"))
        assertEquals(false, back?.get("done"))
    }

    @Test
    fun `decrypt fails on wrong key, wrong id, and tampering`() {
        val key = SyncCrypto.deriveKey("passphrase-one", "profile-a")
        val otherKey = SyncCrypto.deriveKey("passphrase-two", "profile-a")
        val fields = mapOf<String, Any>("title" to "x")
        val enc = SyncCrypto.encryptRecord(key, "tasks", "t1", fields)
        // مفتاح خاطئ.
        assertNull(SyncCrypto.decryptRecord(otherKey, "tasks", "t1", enc))
        // AAD مختلف (سجل آخر).
        assertNull(SyncCrypto.decryptRecord(key, "tasks", "t2", enc))
        assertNull(SyncCrypto.decryptRecord(key, "habits", "t1", enc))
        // نص معطوب (قلب بايت في المنتصف).
        val raw = java.util.Base64.getDecoder().decode(enc)
        raw[20] = (raw[20].toInt() xor 0x01).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(raw)
        assertNull(SyncCrypto.decryptRecord(key, "tasks", "t1", tampered))
        // نص غير base64.
        assertNull(SyncCrypto.decryptRecord(key, "tasks", "t1", "!!!not-base64!!!"))
    }

    @Test
    fun `two encryptions of the same record differ (random IV)`() {
        val key = SyncCrypto.deriveKey("passphrase", "profile")
        val fields = mapOf<String, Any>("title" to "same")
        val a = SyncCrypto.encryptRecord(key, "tasks", "t1", fields)
        val b = SyncCrypto.encryptRecord(key, "tasks", "t1", fields)
        assertTrue(a != b)
        assertEquals("same", SyncCrypto.decryptRecord(key, "tasks", "t1", a)?.get("title"))
        assertEquals("same", SyncCrypto.decryptRecord(key, "tasks", "t1", b)?.get("title"))
    }

    @Test
    fun `canonical JSON sorts keys and formats numbers like JSON stringify`() {
        val json = SyncCrypto.canonicalJson(
            mapOf<String, Any>(
                "title" to "مراجعة",
                "category" to "study",
                "weight" to 70.5,
                "count" to 30.0,
                "done" to true
            )
        )
        assertEquals(
            """{"category":"study","count":30,"done":true,"title":"مراجعة","weight":70.5}""",
            json
        )
        // هروب علامات التنصيص والأسطر.
        assertEquals(
            """{"note":"a\"b\nc"}""",
            SyncCrypto.canonicalJson(mapOf("note" to "a\"b\nc"))
        )
    }

    @Test
    fun `parseFlatJson round-trips canonical JSON`() {
        val json = SyncCrypto.canonicalJson(
            mapOf<String, Any>("title" to "x", "n" to 3L, "f" to 1.5, "b" to false)
        )
        val parsed = SyncCrypto.parseFlatJson(json)
        assertEquals("x", parsed?.get("title"))
        assertEquals(3L, parsed?.get("n"))
        assertEquals(1.5, parsed?.get("f"))
        assertEquals(false, parsed?.get("b"))
        assertNull(SyncCrypto.parseFlatJson("not json"))
        assertNull(SyncCrypto.parseFlatJson("{\"a\":}"))
    }
}
