package com.focusstudy.poc.desktop

import java.security.SecureRandom

/**
 * Single-use recovery codes (design §3.3) — the desktop mirror of Android's
 * SyncRecoveryCode: 32 characters from an unambiguous alphabet (no 0/O, 1/I/L),
 * shown grouped in eights of four. Only the scrypt hash ever reaches the server;
 * the code itself is shown once at activation and never stored on the device.
 */
object SyncRecoveryCode {
    const val CODE_LENGTH = 32
    const val GROUP_SIZE = 4
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    private val VALID_PATTERN = Regex("^[A-HJ-KM-NP-Z2-9]{$CODE_LENGTH}$")

    /** A fresh code, already grouped for display ("XXXX-XXXX-…"). */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val chars = CharArray(CODE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return display(String(chars))
    }

    /** Strips grouping dashes/spaces and upper-cases; "" when the code cannot be valid. */
    fun normalize(value: String): String {
        val text = value.replace(Regex("[-\\s]"), "").uppercase()
        return if (VALID_PATTERN.matches(text)) text else ""
    }

    fun isValid(value: String): Boolean = normalize(value).isNotEmpty()

    /** Groups a normalized code in fours for display; returns the input unchanged when invalid. */
    fun display(normalized: String): String {
        val text = normalize(normalized)
        if (text.isEmpty()) return normalized
        return text.chunked(GROUP_SIZE).joinToString("-")
    }
}
