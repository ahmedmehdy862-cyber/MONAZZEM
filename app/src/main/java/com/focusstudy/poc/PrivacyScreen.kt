package com.focusstudy.poc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

/**
 * شاشة «الخصوصية وبياناتي» (P-02 — تصميم التدقيق §11 / MA-H3).
 *
 * تعلن بصراحة ماذا يغادر الجهاز وماذا يبقى عليه وكم نحتفظ بالبيانات،
 * وتعطي المستخدم ثلاثة أزرار حذف حقيقية: نسخة المزامنة من الخادم،
 * الحساب كاملًا من الخادم، وكل البيانات من هذا الجهاز.
 */
@Composable
internal fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Plain `remember`: كلمة المرور سرّ لا يدخل الحالة المحفوظة.
    var passphrase by remember { mutableStateOf("") }
    var syncStatus by remember { mutableStateOf("") }
    var accountStatus by remember { mutableStateOf("") }
    var localStatus by remember { mutableStateOf("") }
    var confirmLocalDelete by remember { mutableStateOf(false) }
    var syncBusy by remember { mutableStateOf(false) }
    var accountBusy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("الخصوصية وبياناتي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        PrivacyCard("ماذا يغادر جهازك؟") {
            PrivacyLine("رسائل الفريق: معرّف تثبيت مجهول + اسمك + ملفك (طالب/خريج…) + إصدار التطبيق. بلا بريد إلكتروني ولا رقم هاتف.")
            PrivacyLine("المزامنة (إن فعّلتها): نسخة مشفرة من مهامك وعاداتك ووردك وصحتك ومالك ومزاجك — لا يستطيع الخادم قراءتها. كلمة المرور لا تُحفظ على الجهاز.")
            PrivacyLine("الدعم: نص رسالتك واسمك فقط، إن راسلتنا.")
        }
        PrivacyCard("ماذا يبقى على جهازك فقط؟") {
            PrivacyLine("ملاحظاتك (النوتة وملاحظات المهام والمعاملات المالية)، ومعرّفات جهازك، ورموز الإشعارات — لا تغادر الجهاز أبدًا.")
        }
        PrivacyCard("كم نحتفظ بالبيانات؟") {
            PrivacyLine("نسخة المزامنة وسجل المستخدم يبقيان على الخادم حتى تحذفهما أنت من هذه الشاشة. سجل التسجيل (للتدقيق الأمني) يُحفظ آخر 500 حدث فقط. رسائل الدعم تُحفظ حتى تحذف حسابك. لا نبيع بياناتك ولا نشاركها مع أحد.")
        }

        PrivacyCard("حذف بيانات المزامنة من الخادم") {
            PrivacyLine("يحذف النسخة المزامَنة وحساب المزامنة من الخادم نهائيًا. بيانات جهازك لا تُمسّ، ويمكنك تفعيل المزامنة من جديد لاحقًا.")
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it.take(SyncPassphrase.MAX_LENGTH) },
                label = { Text("كلمة مرور المزامنة") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
            Button(
                onClick = {
                    if (syncBusy) return@Button
                    syncBusy = true
                    syncStatus = ""
                    scope.launch {
                        val outcome = SyncAccount.deleteAccount(context, passphrase)
                        syncBusy = false
                        syncStatus = when (outcome) {
                            SyncAccount.Result.SUCCESS -> "حُذفت نسخة المزامنة وحسابها من الخادم."
                            SyncAccount.Result.INVALID_PASSPHRASE -> "كلمة المرور لا تحقق الشرط."
                            SyncAccount.Result.PROFILE_NOT_READY -> "لا يوجد حساب مزامنة مرتبط بهذا الجهاز."
                            SyncAccount.Result.NETWORK_ERROR -> "تعذّر الاتصال؛ حاول مرة أخرى."
                            SyncAccount.Result.REJECTED -> "كلمة المرور غير صحيحة، أو لا يوجد حساب مزامنة."
                        }
                        if (outcome == SyncAccount.Result.SUCCESS) passphrase = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text(if (syncBusy) "جارٍ الحذف…" else "حذف نسخة المزامنة من الخادم") }
            if (syncStatus.isNotBlank()) Text(syncStatus, style = MaterialTheme.typography.bodySmall)
        }

        PrivacyCard("حذف حسابي من الخادم نهائيًا") {
            PrivacyLine("يحذف سجلّك ورسائل دعمك من الخادم، ويحذف أيضًا نسخة المزامنة إن أدخلت كلمة مرورها أعلاه. لا يمكن التراجع عن هذا الحذف.")
            Button(
                onClick = {
                    if (accountBusy) return@Button
                    accountBusy = true
                    accountStatus = ""
                    scope.launch {
                        val messagesDeleted = UserRegistry.deleteAccount(context)
                        val syncDeleted = if (passphrase.isNotBlank()) {
                            SyncAccount.deleteAccount(context, passphrase) == SyncAccount.Result.SUCCESS
                        } else {
                            false
                        }
                        accountBusy = false
                        accountStatus = when {
                            !messagesDeleted -> "تعذّر حذف الحساب؛ تحقق من الاتصال وحاول مرة أخرى."
                            syncDeleted -> "حُذف حسابك ونسخة المزامنة من الخادم نهائيًا."
                            passphrase.isBlank() -> "حُذف حسابك ورسائل دعمك. نسخة المزامنة لم تُحذف — أدخل كلمة مرورها أعلاه واحذفها إن أردت."
                            else -> "حُذف حسابك ورسائل دعمك. نسخة المزامنة لم تُحذف (إما لأنها غير موجودة أو لأن كلمة المرور غير صحيحة)."
                        }
                        if (messagesDeleted) {
                            UserIdentity.clearProfileId(context)
                            passphrase = ""
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = FocusColors.Error)
            ) { Text(if (accountBusy) "جارٍ الحذف…" else "حذف حسابي من الخادم نهائيًا") }
            if (accountStatus.isNotBlank()) Text(accountStatus, style = MaterialTheme.typography.bodySmall)
        }

        PrivacyCard("حذف كل بياناتي من هذا الجهاز") {
            PrivacyLine("سيُحذف كل شيء من هذا الجهاز: المهام، الورد، العادات، الصحة، المال، المزاج، الملاحظات، والإعدادات — ثم يعيد التطبيق التشغيل على حالة جديدة. إن كانت المزامنة مفعّلة، تبقى نسخة مشفرة على الخادم ويمكن استعادتها بإعادة الربط؛ وإن أردت حذف نسخة الخادم أيضًا، احذفها من الأعلى أولًا.")
            if (!confirmLocalDelete) {
                Button(
                    onClick = { confirmLocalDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = FocusColors.Error)
                ) { Text("حذف كل بياناتي من هذا الجهاز") }
            } else {
                Text(
                    "متأكد؟ هذا الحذف نهائي ولا يمكن التراجع عنه.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { LocalDataDeletion.deleteAll(context) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = FocusColors.Error)
                    ) { Text("نعم، احذف كل شيء") }
                    Button(
                        onClick = { confirmLocalDelete = false },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("تراجع") }
                }
            }
            if (localStatus.isNotBlank()) Text(localStatus, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PrivacyCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun PrivacyLine(text: String) {
    Text(text, color = FocusColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
}

/**
 * الحذف المحلي الشامل (P-02): قاعدة البيانات + كل ملفات التفضيلات +
 * كل الملفات الخاصة (كصور المستخدم) — ثم إعادة تشغيل التطبيق على حالة
 * جديدة تمامًا. لا يمس الخادم.
 */
internal object LocalDataDeletion {
    fun deleteAll(context: Context) {
        val appContext = context.applicationContext
        StudyDb.deleteDatabaseFile(appContext)
        File(appContext.filesDir.parent, "shared_prefs").listFiles()?.forEach { it.delete() }
        appContext.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        appContext.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        val intent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            } ?: return
        val pending = PendingIntent.getActivity(
            appContext, 0, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.RTC, System.currentTimeMillis() + 300, pending)
        Process.killProcess(Process.myPid())
    }
}
