package com.focusstudy.poc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The passphrase rule is local-only; the server repeats the same limits before hashing. */
class SyncPassphraseTest {

    @Test fun rejectsBlankShortAndOversizedPassphrases() {
        assertFalse(SyncPassphrase.isValid(""))
        assertFalse(SyncPassphrase.isValid("          "))
        assertFalse(SyncPassphrase.isValid("short"))
        assertFalse(SyncPassphrase.isValid("a".repeat(SyncPassphrase.MAX_LENGTH + 1)))
    }

    @Test fun acceptsAReasonablePassphraseIncludingArabic() {
        assertTrue(SyncPassphrase.isValid("كلمة مرور قوية 123"))
        assertTrue(SyncPassphrase.isValid("a".repeat(SyncPassphrase.MIN_LENGTH)))
    }
}
