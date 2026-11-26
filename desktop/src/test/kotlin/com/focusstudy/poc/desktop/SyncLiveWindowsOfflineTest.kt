package com.focusstudy.poc.desktop

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * الاختبار الحي — قطع الشبكة على ويندوز.
 *
 * يعمل في مهمة windows-verify والـ workflow يحجب java.exe عن الشبكة
 * بقاعدة جدار حماية مؤقتة قبله ويزيلها بعده. يثبت أن syncNow بلا شبكة
 * تنتهي بـ Error صريح (لا تعطل، لا نجاح كاذب). إن لم يكن الحجب قد أخذ
 * مفعوله فعلًا، يفشل هذا الاختبار بصوت عالٍ — وهذا مقصود.
 */
class SyncLiveWindowsOfflineTest {

    @Test
    fun syncFailsGracefullyWhileOffline() {
        val credentials = SyncLiveWindowsSupport.readCredentials()
        val (repo, session, service) = SyncLiveWindowsSupport.openRepoAndSession(credentials)
        val result = service.syncNow(session)
        assertTrue("syncNow while offline must return Error, got $result", result is SyncOutcome.Error)
    }
}
