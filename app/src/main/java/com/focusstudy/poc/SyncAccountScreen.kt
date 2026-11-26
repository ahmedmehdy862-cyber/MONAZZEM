package com.focusstudy.poc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Account pairing screen. It shows the server-issued key. The typed secret is never stored by the manual flow; only the explicit «مزامنة تلقائية» toggle stores it, encrypted (see SyncCredentialStore). */
@Composable
internal fun SyncAccountScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profileId = remember { UserIdentity.profileId(context) }
    // Plain `remember`, not `rememberSaveable`: the secret must not enter a saved instance state.
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    // A separate, non-persisted field: the pairing secret is cleared and never kept on the device.
    var pushPassphrase by remember { mutableStateOf("") }
    var pushBusy by remember { mutableStateOf(false) }
    var pushStatus by remember { mutableStateOf("") }
    var pullBusy by remember { mutableStateOf(false) }
    var pullStatus by remember { mutableStateOf("") }
    // Auto-sync toggle state, read once from durable prefs; the switch below is the only
    // writer (see AutoSync.setEnabled).
    var autoSyncEnabled by remember { mutableStateOf(AutoSync.isEnabled(context)) }
    var autoSyncSessionOnly by remember { mutableStateOf(AutoSync.isSessionOnly(context)) }
    var autoSyncBusy by remember { mutableStateOf(false) }
    var autoSyncStatus by remember { mutableStateOf("") }

    // Maps a manual sync outcome to the line shown under its button. REJECTED means the
    // server refused the secret (wrong passphrase), not a lost connection — see
    // SyncAccount.resultFor — so the copy points at the passphrase.
    fun syncMessage(outcome: SyncAccount.Result, isPush: Boolean): String = when (outcome) {
        SyncAccount.Result.SUCCESS -> if (isPush) "تم رفع البيانات بنجاح." else "تم سحب البيانات وتطبيقها."
        SyncAccount.Result.INVALID_PASSPHRASE -> "كلمة المرور لا تحقق الشرط."
        SyncAccount.Result.PROFILE_NOT_READY -> "معرّف الحساب غير جاهز بعد."
        SyncAccount.Result.NETWORK_ERROR -> if (isPush) "تعذّر الرفع؛ تحقق من الاتصال وكلمة المرور ثم حاول مرة أخرى."
            else "تعذّر السحب؛ تحقق من الاتصال وكلمة المرور ثم حاول مرة أخرى."
        SyncAccount.Result.REJECTED -> if (isPush) "تعذّر رفع البيانات. تأكد من كلمة المرور ثم حاول مرة أخرى."
            else "تعذّر سحب البيانات. تأكد من كلمة المرور ثم حاول مرة أخرى."
    }

    // Runs one manual push/pull with an already-valid passphrase, serialized with the
    // automatic triggers through AutoSync.manualSync (one sync at a time per account).
    // A typed secret re-arms the in-memory session, so auto-sync keeps working after a
    // restart in session-only mode (where nothing is stored on disk).
    fun runManualSync(effective: String, isPush: Boolean) {
        if (isPush) {
            pushBusy = true
            pushStatus = ""
        } else {
            pullBusy = true
            pullStatus = ""
        }
        scope.launch {
            val outcome = AutoSync.manualSync(context, isPush, effective)
            if (isPush) {
                pushBusy = false
                pushStatus = syncMessage(outcome, true)
            } else {
                pullBusy = false
                pullStatus = syncMessage(outcome, false)
            }
            if (outcome == SyncAccount.Result.SUCCESS) {
                if (SyncPassphrase.isValid(pushPassphrase) && autoSyncEnabled) {
                    SyncSession.set(pushPassphrase)
                }
                pushPassphrase = ""
            }
        }
    }

    // Falls back to the stored/session passphrase when the field is blank; null when the
    // user must type it (auto-sync off, or session-only mode after a restart).
    fun runManualSyncWithStoredFallback(isPush: Boolean) {
        if (SyncPassphrase.isValid(pushPassphrase)) {
            runManualSync(pushPassphrase, isPush)
            return
        }
        if (isPush) {
            pushBusy = true
            pushStatus = ""
        } else {
            pullBusy = true
            pullStatus = ""
        }
        scope.launch {
            // Opening encrypted storage touches the Keystore: never on the main thread.
            val stored = withContext(Dispatchers.IO) { AutoSync.resolvePassphrase(context) }
            if (stored == null) {
                if (isPush) {
                    pushBusy = false
                    pushStatus = "أدخل كلمة مرور المزامنة."
                } else {
                    pullBusy = false
                    pullStatus = "أدخل كلمة مرور المزامنة."
                }
            } else {
                if (isPush) pushBusy = false else pullBusy = false
                if (isPush) pushStatus = "" else pullStatus = ""
                runManualSync(stored, isPush)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Text("مزامنة الحساب", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("معرّف حسابك", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (profileId.isBlank()) {
                    Text(
                        "المعرّف غير جاهز بعد. افتح التطبيق مرة أخرى عندما تتوفر شبكة، ثم عُد إلى هنا.",
                        color = FocusColors.TextSecondary
                    )
                } else {
                    Text(profileId, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("monazem-profile", profileId))
                        status = "نُسخ معرّف الحساب."
                    }) { Text("نسخ المعرّف") }
                }
                Text(
                    "يمكنك إدخال هذا المعرّف وكلمة المرور نفسها في تطبيق الويب. لا تستخدم اسمك أو معرّف جهازك كمعرّف.",
                    color = FocusColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("كلمة مرور المزامنة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it.take(SyncPassphrase.MAX_LENGTH) },
                    label = { Text("١٠ أحرف على الأقل") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it.take(SyncPassphrase.MAX_LENGTH) },
                    label = { Text("تأكيد كلمة المرور") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = {
                        when {
                            !SyncPassphrase.isValid(passphrase) ->
                                status = "اختر كلمة مرور من ١٠ أحرف على الأقل."
                            passphrase != confirmation ->
                                status = "الكلمتان غير متطابقتين."
                            busy -> Unit
                            else -> {
                                busy = true
                                status = ""
                                scope.launch {
                                    val outcome = SyncAccount.register(context, passphrase)
                                    busy = false
                                    status = when (outcome) {
                                        SyncAccount.Result.SUCCESS -> "تم تفعيل المزامنة."
                                        SyncAccount.Result.INVALID_PASSPHRASE -> "كلمة المرور لا تحقق الشرط."
                                        SyncAccount.Result.PROFILE_NOT_READY -> "معرّف الحساب غير جاهز بعد."
                                        SyncAccount.Result.NETWORK_ERROR -> "تعذّر الاتصال؛ حاول مرة أخرى."
                                        SyncAccount.Result.REJECTED -> "تعذّر تفعيل الحساب. إن كان الحساب مفعّلًا من قبل، أدخل كلمة المرور الحالية نفسها."
                                    }
                                    if (outcome == SyncAccount.Result.SUCCESS) {
                                        passphrase = ""
                                        confirmation = ""
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text(if (busy) "جارٍ التفعيل…" else "تفعيل المزامنة") }
                if (status.isNotBlank()) {
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "لا تُحفظ كلمة المرور على الجهاز إلا إذا فعّلت «المزامنة التلقائية» أدناه؛ حينها تُحفظ مشفّرة وتُحذف بإيقاف التبديل. ولا تُزامَن النوتة أو ملاحظات المهام أو معرّفات الجهاز.",
                    color = FocusColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "مزامنة تلقائية",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = autoSyncEnabled,
                        enabled = !autoSyncBusy,
                        onCheckedChange = { enable ->
                            if (autoSyncBusy) return@Switch
                            // Enabling needs the secret once: the pairing field above, else the
                            // push/pull field below, else one already stored or in this session
                            // (setEnabled reports NEEDS_PASSPHRASE when there is none).
                            val typed = when {
                                SyncPassphrase.isValid(passphrase) -> passphrase
                                SyncPassphrase.isValid(pushPassphrase) -> pushPassphrase
                                else -> null
                            }
                            autoSyncBusy = true
                            scope.launch {
                                // Encrypted storage touches the Keystore: never on the main thread.
                                val outcome = withContext(Dispatchers.IO) {
                                    AutoSync.setEnabled(context, enable, typed)
                                }
                                autoSyncEnabled = outcome != AutoSync.EnableOutcome.NEEDS_PASSPHRASE &&
                                    outcome != AutoSync.EnableOutcome.DISABLED
                                autoSyncSessionOnly = outcome == AutoSync.EnableOutcome.ENABLED_SESSION_ONLY
                                autoSyncStatus = when (outcome) {
                                    AutoSync.EnableOutcome.ENABLED_ENCRYPTED ->
                                        "مفعّلة — كلمة المرور محفوظة مشفّرة على هذا الجهاز."
                                    AutoSync.EnableOutcome.ENABLED_SESSION_ONLY ->
                                        "مفعّلة لهذه الجلسة فقط — التخزين المشفّر غير متاح على هذا الجهاز؛ أعد إدخال كلمة المرور بعد إعادة فتح التطبيق."
                                    AutoSync.EnableOutcome.NEEDS_PASSPHRASE ->
                                        "أدخل كلمة مرور المزامنة أولًا (في حقل التفعيل أو الرفع) ثم فعّل التبديل."
                                    AutoSync.EnableOutcome.DISABLED ->
                                        "أُوقفت المزامنة التلقائية وحُذفت كلمة المرور المحفوظة."
                                }
                                autoSyncBusy = false
                            }
                        }
                    )
                }
                Text(
                    "عند التفعيل يسحب التطبيق أحدث نسخة عند فتحه وعودته للواجهة وعودة الشبكة، ويرفع تغييراتك بعد هدوء قصير — بلا زر يدوي. كلمة المرور تُحفظ مشفّرة على هذا الجهاز فقط، وتُحذف بإيقاف التبديل.",
                    color = FocusColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                if (autoSyncEnabled) {
                    Text(
                        if (autoSyncSessionOnly) "الحالة: مفعّلة لهذه الجلسة فقط."
                        else "الحالة: مفعّلة، وكلمة المرور محفوظة مشفّرة.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (autoSyncStatus.isNotBlank()) {
                    Text(autoSyncStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("رفع البيانات إلى الويب", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "أدخل كلمة مرور المزامنة نفسها لرفع نسخة من مهامك وموادك وأنواع المهام وبيانات «حياتي» (العادات والورد والصحة والمال والمزاج والسعرات).",
                    color = FocusColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = pushPassphrase,
                    onValueChange = { pushPassphrase = it.take(SyncPassphrase.MAX_LENGTH) },
                    label = { Text("كلمة مرور المزامنة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    // With auto-sync on, the stored/session passphrase is used when the field
                    // is blank, so the manual buttons stay usable without retyping.
                    onClick = { if (!pushBusy) runManualSyncWithStoredFallback(isPush = true) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text(if (pushBusy) "جارٍ الرفع…" else "رفع البيانات") }
                if (pushStatus.isNotBlank()) {
                    Text(pushStatus, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    // Same stored/session fallback as the push button above.
                    onClick = { if (!pullBusy) runManualSyncWithStoredFallback(isPush = false) },
                    enabled = !pullBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text(if (pullBusy) "جارٍ السحب…" else "سحب البيانات من الويب") }
                if (pullStatus.isNotBlank()) {
                    Text(pullStatus, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "السحب يجلب أحدث نسخة من الويب: السجل الأحدث يفوز، والمحذوف هناك يُحذف هنا، وبياناتك المحلية التي لم تُرفع بعد لا تُستبدل.",
                    color = FocusColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "لا تُرفع النوتة ولا ملاحظات المهام أو المعاملات المالية ولا معرّفات الجهاز أو الإشعارات.",
                    color = FocusColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
