package com.focusstudy.poc

/** Local validation only; the server repeats these limits before hashing.
 * The passphrase is not stored by the manual flow. The only exception is the explicit
 * opt-in «مزامنة تلقائية» toggle, which stores it encrypted via [SyncCredentialStore]
 * and deletes it when switched off. */
internal object SyncPassphrase {
    const val MIN_LENGTH = 10
    const val MAX_LENGTH = 200

    fun isValid(value: String): Boolean =
        value.trim().length >= MIN_LENGTH && value.length <= MAX_LENGTH
}
