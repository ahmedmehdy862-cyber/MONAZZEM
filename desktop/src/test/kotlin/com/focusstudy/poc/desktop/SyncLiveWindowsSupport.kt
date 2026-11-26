package com.focusstudy.poc.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume
import java.io.File
import java.nio.file.Files

/**
 * دعم مشترك للاختبار الحي لويندوز (SyncLiveWindows*Test).
 *
 * يعمل فقط من workflow sync-live-test.yml على windows-latest: يقرأ بيانات
 * اعتماد ملف الاختبار المعزول (التي صدّرها اختبار أندرويد كمصنّف) من
 * مسار في متغير البيئة SYNC_LIVE_CREDENTIALS_FILE (أو الخاصية
 * sync.live.credentials). إن غاب الملف يُتخطى الاختبار (Assume) بدل
 * الفشل الكاذب — فاختبارات :desktop العادية في CI لا تملك ملفًا حيًا
 * ولا شبكة حية.
 */
internal object SyncLiveWindowsSupport {

    data class LiveCredentials(
        val profileId: String,
        val passphrase: String,
        val day: String,
    )

    const val ANDROID_TASK_TITLE = "مهمة أندرويد — اختبار حي"
    const val WINDOWS_TASK_TITLE = "مهمة ويندوز — اختبار حي"
    const val HABIT_NAME = "عادة اختبار حي"

    fun readCredentials(): LiveCredentials {
        val path = System.getenv("SYNC_LIVE_CREDENTIALS_FILE")
            ?: System.getProperty("sync.live.credentials")
        Assume.assumeTrue(
            "live credentials file missing — run via the sync-live-test workflow",
            !path.isNullOrBlank() && File(path).exists(),
        )
        val json = Json { ignoreUnknownKeys = true }
        val obj = json.parseToJsonElement(File(path!!).readText()).jsonObject
        return LiveCredentials(
            profileId = obj.getValue("profileId").jsonPrimitive.content,
            passphrase = obj.getValue("passphrase").jsonPrimitive.content,
            day = obj.getValue("day").jsonPrimitive.content,
        )
    }

    /** قاعدة ويندوز حقيقية في مجلد مؤقت + جلسة بخزنة مزيفة (بلا Credential Manager). */
    fun openRepoAndSession(credentials: LiveCredentials): Triple<DesktopRepository, SyncSession, SyncService> {
        val dir = Files.createTempDirectory("sync-live-win").toFile()
        val repo = DesktopRepository(DesktopDatabase.open(dir))
        val session = SyncSession(WindowsCredentialStore(CredentialStoreTest.FakeCredApi()))
        session.link(credentials.profileId, credentials.passphrase)
        return Triple(repo, session, SyncService(repo))
    }
}
