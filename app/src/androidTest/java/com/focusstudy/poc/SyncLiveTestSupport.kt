package com.focusstudy.poc

import android.content.Context
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * دعم مشترك للاختبار الحي للمزامنة (SyncLive*Test).
 *
 * الاختبار الحي يعمل ضد نقطة النهاية الحية /api/sync على محاكي في CI،
 * بملف اختبار معزول جديد في كل تشغيل — لا يمس حساب أحمد الحقيقي إطلاقًا:
 * معرّف مستخدم عشوائي (uid) يُسجَّل عبر /api/messages فيُصدر الخادم
 * profileId جديدًا، ثم تُفعَّل المزامنة بكلمة مرور عشوائية. بيانات
 * الاعتماد تُكتب في ملف filesDir لتنتقل كمصنّف (artifact) بين مهمات
 * الـ workflow (أندرويد ← ويندوز ← أندرويد).
 *
 * ملاحظة: لا يُحذف ملف الاختبار من الخادم بعد التشغيل — لا توجد نقطة
 * حذف للحسابات؛ يبقى معزولًا ويحمل بيانات اصطناعية فقط (انظر
 * docs/sync-live-test.md).
 */
internal object SyncLiveTestSupport {

    const val CREDENTIALS_FILE = "sync-live-credentials.json"

    // قيم زرع مميزة يبحث عنها الاختبار في الطرف الآخر — لا تتداخل مع بيانات حقيقية.
    const val SEED_TASK_TITLE = "مهمة أندرويد — اختبار حي"
    const val SEED_TASK2_TITLE = "مهمة ثانية — اختبار حي"
    const val SEED_SUBJECT_NAME = "فيزياء — اختبار حي"
    const val SEED_TYPE_NAME = "مراجعة — اختبار حي"
    const val SEED_HABIT_NAME = "عادة اختبار حي"
    const val SEED_CALORIE_NAME = "أرز — اختبار حي"
    const val WINDOWS_TASK_TITLE = "مهمة ويندوز — اختبار حي"

    fun randomPassphrase(): String {
        val alphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..20).map { alphabet.random() }.joinToString("")
    }

    private fun randomUid(): String =
        "livetest-" + UUID.randomUUID().toString().replace("-", "").take(16)

    /**
     * يسجّل مستخدم اختبار جديد عبر /api/messages (نفس مصافحة التطبيق عند
     * أول تشغيل) ويعيد profileId الصادر من الخادم.
     */
    fun registerTestProfile(): String {
        val payload = JSONObject()
            .put("action", "register")
            .put("uid", randomUid())
            .put("name", "اختبار مزامنة حي")
            .put("profile", "general")
            .put("versionName", "0.34.0")
            .put("versionCode", 43)
        val response = TeamMessages.request("${TeamMessages.BASE}/api/messages", "POST", payload.toString())
        val profileId = JSONObject(response).optString("profileId")
        check(profileId.isNotBlank()) { "messages register did not issue a profileId: $response" }
        check(UserIdentity.isValidProfileId(profileId)) { "issued profileId is not a valid UUID: $profileId" }
        return profileId
    }

    /** سحب خام من الخادم (مستقل عن منطق التطبيق) — للتحقق أن البيانات وصلت فعلًا. */
    fun rawPull(profileId: String, passphrase: String): JSONObject {
        val payload = JSONObject()
            .put("action", "pull")
            .put("profileId", profileId)
            .put("passphrase", passphrase)
        val response = TeamMessages.request("${TeamMessages.BASE}/api/sync", "POST", payload.toString())
        return JSONObject(response)
    }

    fun writeCredentials(context: Context, credentials: JSONObject): File {
        val file = File(context.filesDir, CREDENTIALS_FILE)
        file.writeText(credentials.toString())
        return file
    }

    fun readCredentials(context: Context): JSONObject {
        val internal = File(context.filesDir, CREDENTIALS_FILE)
        val external = File(context.getExternalFilesDir(null), CREDENTIALS_FILE)
        val file = when {
            internal.exists() -> internal
            external.exists() -> external
            else -> error("sync-live-credentials.json not found (neither filesDir nor external files)")
        }
        return JSONObject(file.readText())
    }

    /** شبكة حقيقية + مهلة صريحة: runTest بساعة افتراضية لا يصلح لطلبات HTTP حية. */
    fun <T> runLive(block: suspend () -> T): T = runBlocking {
        withTimeout(180_000) { block() }
    }
}
