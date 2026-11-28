package com.focusstudy.poc

import android.content.Context
import java.security.SecureRandom

/**
 * Single-use recovery codes (design §3.3): the primary forgot-password path, with no
 * phone, no third party and no new secret.
 *
 * A code is 32 characters from an unambiguous alphabet (no 0/O, 1/I/L), shown grouped
 * in eights of four. Only its scrypt hash ever reaches the server (`setRecoveryCode`);
 * the code itself is shown once at activation and never stored on the device. Using it
 * deletes the account and its snapshot server-side, so the code cannot be reused and a
 * new one must be set after re-registering.
 *
 * [SyncRecoveryState] is only a boolean per profile id in plain prefs — whether this
 * device already showed a code — never the code itself.
 */
internal object SyncRecoveryCode {
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

/** Plain-prefs metadata only: whether a recovery code was already shown for this profile. */
internal object SyncRecoveryState {
    private const val FILE_NAME = "focus_sync_recovery"
    private const val KEY_PREFIX = "code_shown_"

    fun isCodeShown(context: Context, profileId: String): Boolean =
        context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREFIX + profileId, false)

    fun markCodeShown(context: Context, profileId: String) {
        context.applicationContext
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PREFIX + profileId, true).apply()
    }
}
