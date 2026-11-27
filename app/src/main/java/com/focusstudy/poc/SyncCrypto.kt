package com.focusstudy.poc

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * تشفير المزامنة طرف-لطرف (H1 — تصميم التدقيق §11.1).
 *
 * - المفتاح مشتق من كلمة مرور المزامنة بـ scrypt (RFC 7914) بملح/سياق
 *   مختلفين عن مفتاح المصادقة: الملح = SHA-256("monazzem-sync-encryption-v1:" + profileId)
 *   (حتمي لكل حساب، فيُشتق على أي جهاز من كلمة المرور وحدها؛ والخادم لا
 *   يعرفه ولا يستطيع فك التشفير). معايير scrypt: N=16384, r=8, p=1, مفتاح 32 بايت.
 * - كل سجل يُشفَّر منفردًا بـ AES-256-GCM (IV عشوائي 12 بايت لكل سجل،
 *   وسم 16 بايت)، والبيانات المصادق عليها AAD = "kind:id" فلا يُنقَل
 *   نص مشفر لسجل آخر. المغلف على السلك: {id, updatedAt, [deleted], v:2, enc}
 *   حيث enc = base64(IV || ciphertext || tag)؛ ويبقى id/updatedAt/deleted
 *   نصًا صريحًا لأن الخادم يدمج عليها (LWW) دون رؤية المحتوى.
 * - النص الصريح = JSON قانوني لحقول السجل (عدا id/updatedAt/deleted)،
 *   مفاتيح مرتبة، فيُطابَق عبر أندرويد وسطح المكتب بايتًا بايتًا.
 * - لا اعتماديات جديدة: scrypt منفَّذ هنا (PBKDF2-HMAC-SHA256 عبر
 *   javax.crypto.Mac + Salsa20/8 + ROMix حسب RFC 7914)، وAES-GCM عبر
 *   javax.crypto. يُتحقق منه بمتجهات RFC 7914 ومتجه Node المحسوب
 *   (انظر SyncCryptoTest).
 */
internal object SyncCrypto {
    const val VERSION = 2
    const val SYNC_VERSION = 2
    private const val SALT_CONTEXT = "monazzem-sync-encryption-v1:"
    private const val SCRYPT_N = 16384
    private const val SCRYPT_R = 8
    private const val SCRYPT_P = 1
    private const val KEY_BYTES = 32
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    const val MAX_ENC_CHARS = 8192

    /** الملح الحتمي لكل حساب (ليس سرًا؛ كلمة المرور هي السر). */
    fun saltFor(profileId: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest((SALT_CONTEXT + profileId).toByteArray(Charsets.UTF_8))

    /** اشتقاق مفتاح التشفير من كلمة المرور ومعرّف الحساب. */
    fun deriveKey(passphrase: String, profileId: String): ByteArray =
        scrypt(
            passphrase.toByteArray(Charsets.UTF_8),
            saltFor(profileId),
            SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_BYTES
        )

    /** تشفير حقول سجل واحد؛ يعيد base64(IV || ciphertext || tag). */
    fun encryptRecord(
        key: ByteArray,
        kind: String,
        id: String,
        fields: Map<String, Any>
    ): String = encryptRecordWithIv(key, kind, id, fields, randomIv())

    /** نسخة بحقن IV (للاختبار فقط — الإنتاج يستخدم IV عشوائيًا دائمًا). */
    internal fun encryptRecordWithIv(
        key: ByteArray,
        kind: String,
        id: String,
        fields: Map<String, Any>,
        iv: ByteArray
    ): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aadFor(kind, id))
        val plain = canonicalJson(fields).toByteArray(Charsets.UTF_8)
        val cipherText = cipher.doFinal(plain)
        val out = ByteArray(iv.size + cipherText.size)
        iv.copyInto(out, 0)
        cipherText.copyInto(out, iv.size)
        return Base64.getEncoder().encodeToString(out)
    }

    /**
     * فك تشفير سجل؛ يعيد خريطة الحقول أو null عند أي فشل (مفتاح خاطئ،
     * نص معطوب، AAD غير مطابق) — لا يرمي أبدًا، فالمتصل يتخطى السجل.
     */
    fun decryptRecord(
        key: ByteArray,
        kind: String,
        id: String,
        enc: String
    ): Map<String, Any>? {
        val raw = try {
            Base64.getDecoder().decode(enc)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (raw.size <= GCM_IV_BYTES + GCM_TAG_BITS / 8) return null
        val iv = raw.copyOfRange(0, GCM_IV_BYTES)
        val cipherText = raw.copyOfRange(GCM_IV_BYTES, raw.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aadFor(kind, id))
            val plain = cipher.doFinal(cipherText)
            parseFlatJson(String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    internal fun aadFor(kind: String, id: String): ByteArray =
        "$kind:$id".toByteArray(Charsets.UTF_8)

    private fun randomIv(): ByteArray =
        ByteArray(GCM_IV_BYTES).also { java.security.SecureRandom().nextBytes(it) }

    // ---- JSON قانوني (مفاتيح مرتبة؛ يطابق JSON.stringify لنفس الحقول) ----

    fun canonicalJson(fields: Map<String, Any>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((key, value) in fields.entries.sortedBy { it.key }) {
            if (!first) sb.append(",")
            first = false
            sb.append(jsonString(key)).append(":").append(jsonValue(value))
        }
        sb.append("}")
        return sb.toString()
    }

    private fun jsonValue(value: Any): String = when (value) {
        is String -> jsonString(value)
        is Boolean -> if (value) "true" else "false"
        is Long, is Int, is Short, is Byte -> value.toString()
        is Double, is Float -> {
            val d = (value as Number).toDouble()
            if (d.isFinite() && d == kotlin.math.floor(d) &&
                d >= Long.MIN_VALUE.toDouble() && d <= Long.MAX_VALUE.toDouble()
            ) {
                d.toLong().toString()
            } else {
                BigDecimal.valueOf(d).toPlainString()
            }
        }
        is Number -> value.toString()
        else -> throw IllegalArgumentException("unsupported sync field value: ${value::class}")
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * محلل JSON صغير لكائن مسطّح (سلاسل/أرقام/بوليان فقط — هذا كل ما في
     * حقول المزامنة). يعيد null عند أي نص غير صالح.
     */
    fun parseFlatJson(text: String): Map<String, Any>? {
        val t = text.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) return null
        val out = LinkedHashMap<String, Any>()
        var i = 1
        val n = t.length
        fun skipWs() {
            while (i < n && t[i].isWhitespace()) i++
        }
        fun parseString(): String? {
            if (i >= n || t[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < n) {
                val c = t[i++]
                if (c == '"') return sb.toString()
                if (c == '\\' && i < n) {
                    when (val e = t[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > n) return null
                            val code = t.substring(i, i + 4).toIntOrNull(16) ?: return null
                            i += 4
                            sb.append(code.toChar())
                        }
                        else -> return null
                    }
                } else {
                    sb.append(c)
                }
            }
            return null
        }
        fun parseValue(): Any? {
            skipWs()
            if (i >= n) return null
            return when (t[i]) {
                '"' -> parseString()
                't' -> if (t.startsWith("true", i)) {
                    i += 4
                    true
                } else null
                'f' -> if (t.startsWith("false", i)) {
                    i += 5
                    false
                } else null
                else -> {
                    val start = i
                    while (i < n && (t[i].isDigit() || t[i] in "-+.eE")) i++
                    if (start == i) return null
                    val num = t.substring(start, i)
                    num.toLongOrNull() ?: num.toDoubleOrNull() ?: return null
                }
            }
        }
        skipWs()
        if (i < n && t[i] == '}') return out
        while (true) {
            skipWs()
            val key = parseString() ?: return null
            skipWs()
            if (i >= n || t[i] != ':') return null
            i++
            val value = parseValue() ?: return null
            out[key] = value
            skipWs()
            if (i >= n) return null
            when (t[i]) {
                ',' -> {
                    i++
                    continue
                }
                '}' -> return out
                else -> return null
            }
        }
    }

    // ---- scrypt (RFC 7914) ----

    /**
     * scrypt(password, salt, N, r, p, dkLen) — N قوة 2، r=8، p=1 في
     * الاستخدام. PBKDF2 منفَّذ يدويًا عبر HmacSHA256 لضمان بايتات كلمة
     * المرور UTF-8 مطابقةً لـ Node (لا غموض PBEKeySpec).
     */
    fun scrypt(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, dkLen: Int): ByteArray {
        require(n > 1 && (n and (n - 1)) == 0) { "N must be a power of 2 greater than 1" }
        require(r >= 1 && p >= 1) { "r and p must be positive" }
        val b = pbkdf2(password, salt, 1, p * 128 * r)
        val blockLen = 128 * r
        for (i in 0 until p) {
            val block = b.copyOfRange(i * blockLen, (i + 1) * blockLen)
            romix(block, n, r)
            block.copyInto(b, i * blockLen)
        }
        return pbkdf2(password, b, 1, dkLen)
    }

    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // HMAC يبطّن مفتاحًا أقصر من كتلة التجزئة (64 بايت) بالأصفار؛ فكلمة
        // مرور فارغة تعادل مفتاحًا من 64 صفرًا، بينما SecretKeySpec يرفض
        // المفتاح الفارغ (متجه RFC 7914 الأول كلمة مروره فارغة).
        val keyBytes = if (password.isEmpty()) ByteArray(64) else password
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val out = ByteArray(dkLen)
        var done = 0
        var blockIndex = 1
        while (done < dkLen) {
            mac.reset()
            mac.update(salt)
            val counter = byteArrayOf(
                (blockIndex ushr 24).toByte(),
                (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(),
                blockIndex.toByte()
            )
            var u = mac.doFinal(counter)
            val t = u.copyOf()
            for (c in 2..iterations) {
                mac.reset()
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val take = min(32, dkLen - done)
            t.copyInto(out, done, 0, take)
            done += take
            blockIndex++
        }
        return out
    }

    private fun romix(block: ByteArray, n: Int, r: Int) {
        val blockLen = 128 * r
        val x = block.copyOf()
        val v = Array(n) { ByteArray(blockLen) }
        for (i in 0 until n) {
            v[i] = x.copyOf()
            blockMix(x, r)
        }
        for (i in 0 until n) {
            // N قوة 2 دائمًا في scrypt، فـ mod N = AND (N-1) (غير سالب أبدًا).
            val j = (integerify(x, r) and (n - 1).toLong()).toInt()
            for (k in 0 until blockLen) x[k] = (x[k].toInt() xor v[j][k].toInt()).toByte()
            blockMix(x, r)
        }
        x.copyInto(block)
    }

    private fun integerify(b: ByteArray, r: Int): Long {
        val off = (2 * r - 1) * 64
        var v = 0L
        for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun blockMix(b: ByteArray, r: Int) {
        val blockLen = 128 * r
        val y = ByteArray(blockLen)
        var x = ByteArray(64)
        b.copyInto(x, 0, (2 * r - 1) * 64, (2 * r - 1) * 64 + 64)
        for (i in 0 until 2 * r) {
            val block = b.copyOfRange(i * 64, (i + 1) * 64)
            for (j in 0 until 64) x[j] = (x[j].toInt() xor block[j].toInt()).toByte()
            salsa208(x)
            x.copyInto(y, i * 64)
        }
        // الناتج: الكتل الزوجية ثم الفردية.
        for (i in 0 until r) {
            y.copyInto(b, i * 64, (2 * i) * 64, (2 * i) * 64 + 64)
            y.copyInto(b, (r + i) * 64, (2 * i + 1) * 64, (2 * i + 1) * 64 + 64)
        }
    }

    private fun salsa208(b: ByteArray) {
        val x = IntArray(16)
        for (i in 0 until 16) {
            x[i] = (b[i * 4].toInt() and 0xFF) or
                ((b[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((b[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((b[i * 4 + 3].toInt() and 0xFF) shl 24)
        }
        val z = x.copyOf()
        fun quarterRound(a: Int, bIdx: Int, c: Int, d: Int) {
            z[bIdx] = z[bIdx] xor rotateLeft(z[a] + z[d], 7)
            z[c] = z[c] xor rotateLeft(z[bIdx] + z[a], 9)
            z[d] = z[d] xor rotateLeft(z[c] + z[bIdx], 13)
            z[a] = z[a] xor rotateLeft(z[d] + z[c], 18)
        }
        repeat(4) {
            // جولة الأعمدة.
            quarterRound(0, 4, 8, 12)
            quarterRound(5, 9, 13, 1)
            quarterRound(10, 14, 2, 6)
            quarterRound(15, 3, 7, 11)
            // جولة الصفوف.
            quarterRound(0, 1, 2, 3)
            quarterRound(5, 6, 7, 4)
            quarterRound(10, 11, 8, 9)
            quarterRound(15, 12, 13, 14)
        }
        for (i in 0 until 16) {
            val v = z[i] + x[i]
            b[i * 4] = v.toByte()
            b[i * 4 + 1] = (v ushr 8).toByte()
            b[i * 4 + 2] = (v ushr 16).toByte()
            b[i * 4 + 3] = (v ushr 24).toByte()
        }
    }

    private fun rotateLeft(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))
}
