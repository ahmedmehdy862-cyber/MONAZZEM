package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recovery codes (design §3.3): 32 unambiguous characters, shown grouped, never stored. */
class SyncRecoveryCodeTest {

    @Test fun generatedCodeHasThirtyTwoUnambiguousCharacters() {
        val code = SyncRecoveryCode.generate()
        assertEquals(39, code.length) // 32 characters + 7 grouping dashes
        assertEquals(8, code.split("-").size)
        assertTrue(code.split("-").all { it.length == 4 })
        assertTrue(SyncRecoveryCode.isValid(code))
        assertEquals(code, SyncRecoveryCode.display(code))
    }

    @Test fun generatedCodesDiffer() {
        assertTrue(SyncRecoveryCode.generate() != SyncRecoveryCode.generate())
    }

    @Test fun normalizeAcceptsGroupedLowercaseAndSpacedCodes() {
        val code = SyncRecoveryCode.generate()
        val normalized = SyncRecoveryCode.normalize(code)
        assertEquals(32, normalized.length)
        assertEquals(normalized, SyncRecoveryCode.normalize(normalized.lowercase()))
        assertEquals(normalized, SyncRecoveryCode.normalize(code.replace("-", " ")))
        assertEquals(code, SyncRecoveryCode.display(normalized))
    }

    @Test fun ambiguousAndShortCodesAreRejected() {
        assertFalse(SyncRecoveryCode.isValid("short"))
        assertFalse(SyncRecoveryCode.isValid(""))
        // L, I, O, 0 and 1 never appear in a generated code.
        assertFalse(SyncRecoveryCode.isValid("ABCD-EFGH-JKLM-NPQR-STUV-WXYZ-2345-6789"))
        assertFalse(SyncRecoveryCode.isValid("ABCD-EFGH-JKMN-PQRS-TUVW-XYZ2-3456-7890"))
        assertFalse(SyncRecoveryCode.isValid("ABCD-EFGH-JKMN-PQRS-TUVW-XYZ2-3456-789I"))
        assertEquals("", SyncRecoveryCode.normalize("short"))
    }
}
