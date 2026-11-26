package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 7b: pure-logic checks for the local support-message model. The database
 * itself (migration v16 → v17, round-trips) is covered by the Robolectric test
 * [DbMigrationV16ToV17Test], not here.
 */
class Phase7bSupportTest {

    @Test fun supportKindRoundTripsByKey() {
        assertEquals(SupportMessageKind.SUGGESTION, SupportMessageKind.from("suggestion"))
        assertEquals(SupportMessageKind.COMPLAINT, SupportMessageKind.from("complaint"))
        assertEquals(SupportMessageKind.COMPLAINT, SupportMessageKind.from("unknown"))
        assertEquals(SupportMessageKind.COMPLAINT, SupportMessageKind.from(null))
    }

    @Test fun supportStatusRoundTripsByKey() {
        assertEquals(SupportMessageStatus.SENT, SupportMessageStatus.from("sent"))
        assertEquals(SupportMessageStatus.FAILED, SupportMessageStatus.from("failed"))
        assertEquals(SupportMessageStatus.SENDING, SupportMessageStatus.from("sending"))
        assertEquals(SupportMessageStatus.FAILED, SupportMessageStatus.from("unknown"))
        assertEquals(SupportMessageStatus.FAILED, SupportMessageStatus.from(null))
    }

    @Test fun supportLabelsAreArabic() {
        assertEquals("شكوى", SupportMessageKind.COMPLAINT.label)
        assertEquals("اقتراح", SupportMessageKind.SUGGESTION.label)
        assertEquals("مرسلة", SupportMessageStatus.SENT.label)
        assertEquals("فاشلة", SupportMessageStatus.FAILED.label)
        assertEquals("قيد الإرسال", SupportMessageStatus.SENDING.label)
    }

    @Test fun imagePathsJoinAndSplitRoundTrip() {
        val paths = listOf(
            "/data/user/0/com.focusstudy.poc/files/support_images/support_a.jpg",
            "/data/user/0/com.focusstudy.poc/files/support_images/support_b.png"
        )
        assertEquals(paths, splitSupportImagePaths(joinSupportImagePaths(paths)))
        assertEquals(emptyList<String>(), splitSupportImagePaths(""))
        assertEquals(emptyList<String>(), splitSupportImagePaths(joinSupportImagePaths(emptyList())))
    }
}
