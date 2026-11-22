package com.focusstudy.poc

/** Local validation only; the server repeats these limits before hashing. No secret is stored. */
internal object SyncPassphrase {
    const val MIN_LENGTH = 10
    const val MAX_LENGTH = 200

    fun isValid(value: String): Boolean =
        value.trim().length >= MIN_LENGTH && value.length <= MAX_LENGTH
}
