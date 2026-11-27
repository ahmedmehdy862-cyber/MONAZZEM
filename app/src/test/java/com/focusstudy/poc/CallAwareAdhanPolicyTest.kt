package com.focusstudy.poc

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * مراعاة المكالمة الجارية (0.35.0): قرار الأذان أثناء مكالمة — منطق خالص
 * بلا أندرويد. الكشف نفسه (`CallAware.isInCall` عبر `AudioManager.mode`)
 * يحتاج مكالمة فعلية على جهاز حقيقي، فلا يغطيه اختبار وحدة.
 */
class CallAwareAdhanPolicyTest {

    @Test fun noCallAlwaysPlays() {
        assertEquals(AdhanCallDecision.PLAY, CallAwareAdhanPolicy.decide(false, 0))
        assertEquals(AdhanCallDecision.PLAY, CallAwareAdhanPolicy.decide(false, 3))
        assertEquals(AdhanCallDecision.PLAY, CallAwareAdhanPolicy.decide(false, 99))
    }

    @Test fun callDefersWhileAttemptsRemain() {
        assertEquals(AdhanCallDecision.DEFER, CallAwareAdhanPolicy.decide(true, 0))
        assertEquals(AdhanCallDecision.DEFER, CallAwareAdhanPolicy.decide(true, 1))
        assertEquals(AdhanCallDecision.DEFER, CallAwareAdhanPolicy.decide(true, 2))
    }

    @Test fun callMissesAfterMaxDefers() {
        assertEquals(AdhanCallDecision.MISSED, CallAwareAdhanPolicy.decide(true, 3))
        assertEquals(AdhanCallDecision.MISSED, CallAwareAdhanPolicy.decide(true, 4))
        assertEquals(AdhanCallDecision.MISSED, CallAwareAdhanPolicy.decide(true, 99))
    }

    @Test fun retryIsFifteenMinutesLater() {
        assertEquals(1_000L + 15 * 60 * 1000L, CallAwareAdhanPolicy.nextRetryAt(1_000L))
        assertEquals(15 * 60 * 1000L, CallAwareAdhanPolicy.DEFER_INTERVAL_MILLIS)
        assertEquals(3, CallAwareAdhanPolicy.MAX_DEFERS)
    }
}
