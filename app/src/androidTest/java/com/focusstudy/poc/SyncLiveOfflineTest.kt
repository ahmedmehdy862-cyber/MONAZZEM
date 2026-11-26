package com.focusstudy.poc

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * الاختبار الحي — قطع الشبكة وإعادتها (أندرويد).
 *
 * يعمل في مهمة android-seed بعد اختبار الزرع، والـ workflow يقطع الشبكة
 * على المحاكي قبله (`svc wifi disable` + `svc data disable`) ويعيدها
 * بعده. يثبت أن محاولة المزامنة بلا شبكة تنتهي بـ NETWORK_ERROR صريح
 * (لا تعطل، لا نتيجة نجاح كاذبة، ولا «تعذّر الاتصال» يخفي خطأ كلمة مرور).
 * إن لم يكن القطع قد أخذ مفعوله فعلًا، يفشل هذا الاختبار بصوت عالٍ —
 * وهذا مقصود: لا يُدَّعى اختبار انقطاع لم يحدث.
 */
@RunWith(AndroidJUnit4::class)
class SyncLiveOfflineTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pullAndPushFailAsNetworkErrorWhileOffline() {
        val credentials = SyncLiveTestSupport.readCredentials(context)
        val profileId = credentials.getString("profileId")
        val passphrase = credentials.getString("passphrase")
        UserIdentity.storeProfileId(context, profileId)

        SyncLiveTestSupport.runLive {
            assertEquals(
                "pull while offline must report NETWORK_ERROR, not success",
                SyncAccount.Result.NETWORK_ERROR, SyncAccount.pull(context, passphrase)
            )
            assertEquals(
                "push while offline must report NETWORK_ERROR, not success",
                SyncAccount.Result.NETWORK_ERROR, SyncAccount.push(context, passphrase)
            )
        }
    }
}
