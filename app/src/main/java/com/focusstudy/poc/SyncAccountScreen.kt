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
    // The stored passphrase started being rejected: it changed on another device.
    var authFailed by remember { mutableStateOf(AutoSyncState.get(context).authFailedAt > 0L) }
    // Change-password card state.
    var changeCurrent by remember { mutableStateOf("") }
    var changeNew by remember { mutableStateOf("") }
    var changeConfirm by remember { mutableStateOf("") }
    var changeBusy by remember { mutableStateOf(false) }
    var changeStatus by remember { mutableStateOf("") }
    // Forgot-password card state. `forgotResetDone` means the old account was deleted
    // and the user must now register a new passphrase on this device.
    var forgotCode by remember { mutableStateOf("") }
    var forgotNew by remember { mutableStateOf("") }
    var forgotConfirm by remember { mutableStateOf("") }
    var forgotBusy by remember { mutableStateOf(false) }
    var forgotStatus by remember { mutableStateOf("") }
    var forgotResetDone by remember { mutableStateOf(false) }
    // One-time recovery code: generated on this device, stored only as a hash on the
    // server, shown once and never persisted locally.
    var shownRecoveryCode by remember { mutableStateOf<String?>(null) }
    var pendingRecoveryCode by remember { mutableStateOf<String?>(null) }
    var recoveryBusy by remember { mutableStateOf(false) }
    var recoveryStatus by remember { mutableStateOf("") }

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
        SyncAccount.Result.STALE_REVISION -> "تعارض مع جهاز آخر؛ أعد المحاولة."
        SyncAccount.Result.RATE_LIMITED -> "محاولات كثيرة — أعد المحاولة بعد قليل."
        SyncAccount.Result.CHANGED_NEEDS_RESYNC -> "تم تغيير كلمة المرور، وستكتمل إعادة الرفع مع المزامنة التالية."
    }

    // Stores a fresh recovery code's hash on the server and, only on success, shows the
    // code once. A failed store never shows a code the server does not know.
    suspend fun storeRecoveryCode(pass: String, code: String) {
        recoveryBusy = true
        recoveryStatus = ""
        val result = withContext(Dispatchers.IO) { SyncAccount.setRecoveryCode(context, pass, code) }
        recoveryBusy = false
        if (result == SyncAccount.Result.SUCCESS) {
            SyncRecoveryState.markCodeShown(context, profileId)
            shownRecoveryCode = SyncRecoveryCode.display(code)
            pendingRecoveryCode = null
        } else {
            pendingRecoveryCode = code
            recoveryStatus = "تعذّر حفظ كود الاسترداد؛ تحقق من الاتصال ثم أعد المحاولة."
        }
    }

    // Runs one manual push/pull with an already-valid passphrase, serialized with the
    // automatic triggers through AutoSync.manualSync (one sync at a time per account).
    // A typed secret re-arms the in-memory session, so auto-sync keeps working after a
    // restart in session-only mode (where nothing is stored on disk).
    fun runManualSync(effective: String, isPush: Boolean, usedStored: Boolean = false) {
        if (isPush) {
            pushBusy = true
            pushStatus = ""
        } else {
            pullBusy = true
            pullStatus = ""
        }
        scope.launch {
            val outcome = AutoSync.manualSync(context, isPush, effective)
            authFailed = AutoSyncState.get(context).authFailedAt > 0L
            // A stored secret that the server now rejects was changed on another device:
            // say so plainly instead of blaming the typed password, and never touch
            // local data. A typed secret that fails is just a wrong password.
            val message = if (outcome == SyncAccount.Result.REJECTED && usedStored) {
                authFailed = true
                "تغيّرت كلمة مرور المزامنة من جهاز آخر — أدخل كلمة المرور الجديدة للمتابعة. بياناتك المحلية محفوظة ولم تُمسح."
            } else {
                syncMessage(outcome, isPush)
            }
            if (isPush) {
                pushBusy = false
                pushStatus = message
            } else {
                pullBusy = false
                pullStatus = message
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
            runManualSync(pushPassphrase, isPush, usedStored = false)
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
                runManualSync(stored, isPush, usedStored = true)
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
                                    status = when (outcome.result) {
                                        SyncAccount.Result.SUCCESS -> "تم تفعيل المزامنة."
                                        SyncAccount.Result.INVALID_PASSPHRASE -> "كلمة المرور لا تحقق الشرط."
                                        SyncAccount.Result.PROFILE_NOT_READY -> "معرّف الحساب غير جاهز بعد."
                                        SyncAccount.Result.NETWORK_ERROR -> "تعذّر الاتصال؛ حاول مرة أخرى."
                                        SyncAccount.Result.REJECTED -> "تعذّر تفعيل الحساب. إن كان الحساب مفعّلًا من قبل، أدخل كلمة المرور الحالية نفسها."
                                        SyncAccount.Result.STALE_REVISION -> "تعارض مع جهاز آخر؛ أعد المحاولة."
                                        SyncAccount.Result.RATE_LIMITED -> "محاولات كثيرة — أعد المحاولة بعد قليل."
                                        SyncAccount.Result.CHANGED_NEEDS_RESYNC -> "تم تفعيل المزامنة."
                                    }
                                    if (outcome.result == SyncAccount.Result.SUCCESS) {
                                        val used = passphrase
                                        passphrase = ""
                                        confirmation = ""
                                        // First activation only: show a one-time recovery code.
                                        // A second device linking must not silently invalidate
                                        // the code the first device already showed.
                                        if (outcome.created && !SyncRecoveryState.isCodeShown(context, profileId)) {
                                            storeRecoveryCode(used, SyncRecoveryCode.generate())
                                        }
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
                Text(
                    "وإن نسيت كلمة المرور بلا كود استرداد وبلا جهاز عليه بياناتك، فلا يمكن استرجاع نسخة الويب — احفظ كود الاسترداد الذي يظهر بعد التفعيل.",
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
        if (authFailed) {
            Card(
                colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("تغيّرت كلمة مرور المزامنة من جهاز آخر", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "كلمة المرور المحفوظة على هذا الجهاز لم تعد تعمل — غيّرها جهاز آخر. أدخل كلمة المرور الجديدة في حقل الرفع أدناه للمتابعة. بياناتك المحلية محفوظة ولم تُمسح.",
                        color = FocusColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { AutoSync.setEnabled(context, false, null) }
                            autoSyncEnabled = false
                            authFailed = false
                        }
                    }) { Text("افصل هذا الجهاز") }
                }
            }
        }
        shownRecoveryCode?.let { code ->
            Card(
                colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("كود الاسترداد — احفظه الآن", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(code, style = MaterialTheme.typography.bodyLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("monazem-recovery", code))
                            recoveryStatus = "نُسخ كود الاسترداد."
                        }) { Text("نسخ الكود") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { shownRecoveryCode = null }) { Text("أخفيتُ الكود — تم الحفظ") }
                    }
                    Text(
                        "يُعرض مرة واحدة فقط ولا يُحفظ على الجهاز. احفظه في مدير كلمات المرور أو على ورق. من يفقد كلمة المرور والكود معًا ولا يملك جهازًا عليه بياناته، يفقد نسخة الويب ولا يمكن استرجاعها.",
                        color = FocusColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (recoveryStatus.isNotBlank()) {
                        Text(recoveryStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (pendingRecoveryCode != null && !recoveryBusy) {
            Card(
                colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "تعذّر حفظ كود الاسترداد على الخادم، فلم يُعرض — الكود بلا حفظ لا ينفع عند النسيان.",
                        color = FocusColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val pass = when {
                                    SyncPassphrase.isValid(pushPassphrase) -> pushPassphrase
                                    else -> withContext(Dispatchers.IO) { AutoSync.resolvePassphrase(context) }
                                }
                                if (pass == null) {
                                    recoveryStatus = "أدخل كلمة مرور المزامنة في حقل الرفع أولًا."
                                } else {
                                    storeRecoveryCode(pass, pendingRecoveryCode!!)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("إعادة محاولة حفظ الكود") }
                    if (recoveryStatus.isNotBlank()) {
                        Text(recoveryStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("تغيير كلمة مرور المزامنة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = changeCurrent,
                    onValueChange = { changeCurrent = it.take(SyncPassphrase.MAX_LENGTH) },
                    label = { Text("كلمة المرور الحالية") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = changeNew,
                    onValueChange = { changeNew = it.take(SyncPassphrase.MAX_LENGTH) },
                    label = { Text("كلمة المرور الجديدة (١٠ أحرف على الأقل)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                OutlinedTextField(
                    value = changeConfirm,
                    onValueChange = { changeConfirm = it.take(SyncPassphrase.MAX_LENGTH) },
                    label = { Text("تأكيد كلمة المرور الجديدة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                )
                Button(
                    onClick = {
                        when {
                            !SyncPassphrase.isValid(changeCurrent) || !SyncPassphrase.isValid(changeNew) ->
                                changeStatus = "كلمة المرور من ١٠ أحرف على الأقل."
                            changeNew != changeConfirm ->
                                changeStatus = "الكلمتان الجديدتان غير متطابقتين."
                            changeNew == changeCurrent ->
                                changeStatus = "كلمة المرور الجديدة مطابقة للحالية."
                            changeBusy -> Unit
                            else -> {
                                changeBusy = true
                                changeStatus = ""
                                scope.launch {
                                    val outcome = AutoSync.changePassphrase(context, changeCurrent, changeNew)
                                    changeBusy = false
                                    changeStatus = when (outcome) {
                                        SyncAccount.Result.SUCCESS -> "تم تغيير كلمة المرور. الأجهزة الأخرى ستحتاج كلمة المرور الجديدة عند مزامنتها التالية."
                                        SyncAccount.Result.INVALID_PASSPHRASE -> "كلمة المرور لا تحقق الشرط."
                                        SyncAccount.Result.PROFILE_NOT_READY -> "معرّف الحساب غير جاهز بعد."
                                        SyncAccount.Result.NETWORK_ERROR -> "تعذّر الاتصال؛ حاول مرة أخرى."
                                        SyncAccount.Result.REJECTED -> "كلمة المرور الحالية غير صحيحة."
                                        SyncAccount.Result.RATE_LIMITED -> "محاولات كثيرة — أعد المحاولة بعد قليل."
                                        SyncAccount.Result.STALE_REVISION -> "تعارض مع جهاز آخر؛ أعد المحاولة."
                                        SyncAccount.Result.CHANGED_NEEDS_RESYNC -> "تم تغيير كلمة المرور، وستكتمل إعادة الرفع مع المزامنة التالية."
                                    }
                                    if (outcome == SyncAccount.Result.SUCCESS || outcome == SyncAccount.Result.CHANGED_NEEDS_RESYNC) {
                                        changeCurrent = ""
                                        changeNew = ""
                                        changeConfirm = ""
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text(if (changeBusy) "جارٍ التغيير…" else "تغيير كلمة المرور") }
                if (changeStatus.isNotBlank()) {
                    Text(changeStatus, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "الأجهزة الأخرى ستحتاج كلمة المرور الجديدة عند مزامنتها التالية — وبياناتك المحلية لا تُمسح أبدًا عند تغييرها من جهاز آخر.",
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
                Text("نسيت كلمة المرور", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "إن نسيت كلمة المرور ولا تملك كود الاسترداد، ولا يوجد جهاز عليه بياناتك، فنسخة الويب فُقدت ولا يمكن استرجاعها — لا توجد طريقة أخرى. أما إن كان لديك جهاز عليه بياناتك، فأدخل كود الاسترداد أدناه: سيُحذف الحساب القديم ونسخته، ثم تُفعّل المزامنة من جديد بكلمة مرور جديدة وتُرفع بيانات هذا الجهاز.",
                    color = FocusColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                if (!forgotResetDone) {
                    OutlinedTextField(
                        value = forgotCode,
                        onValueChange = { forgotCode = it.take(64) },
                        label = { Text("كود الاسترداد") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Button(
                        onClick = {
                            when {
                                !SyncRecoveryCode.isValid(forgotCode) ->
                                    forgotStatus = "كود الاسترداد غير صحيح الصيغة — ٣٢ حرفًا."
                                forgotBusy -> Unit
                                else -> {
                                    forgotBusy = true
                                    forgotStatus = ""
                                    scope.launch {
                                        val outcome = SyncAccount.resetWithRecoveryCode(context, forgotCode)
                                        forgotBusy = false
                                        when (outcome) {
                                            SyncAccount.Result.SUCCESS -> {
                                                withContext(Dispatchers.IO) { AutoSync.setEnabled(context, false, null) }
                                                autoSyncEnabled = false
                                                authFailed = false
                                                forgotResetDone = true
                                                forgotStatus = "تم حذف الحساب القديم ونسخته. أدخل كلمة مرور جديدة لتفعيل المزامنة من جديد."
                                            }
                                            SyncAccount.Result.INVALID_PASSPHRASE -> forgotStatus = "كود الاسترداد غير صحيح الصيغة — ٣٢ حرفًا."
                                            SyncAccount.Result.REJECTED -> forgotStatus = "كود الاسترداد غير صحيح."
                                            SyncAccount.Result.RATE_LIMITED -> forgotStatus = "محاولات كثيرة — أعد المحاولة بعد قليل."
                                            SyncAccount.Result.PROFILE_NOT_READY -> forgotStatus = "معرّف الحساب غير جاهز بعد."
                                            else -> forgotStatus = "تعذّر الاتصال؛ حاول مرة أخرى."
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text(if (forgotBusy) "جارٍ الاسترداد…" else "استرداد الحساب") }
                } else {
                    OutlinedTextField(
                        value = forgotNew,
                        onValueChange = { forgotNew = it.take(SyncPassphrase.MAX_LENGTH) },
                        label = { Text("كلمة المرور الجديدة (١٠ أحرف على الأقل)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    OutlinedTextField(
                        value = forgotConfirm,
                        onValueChange = { forgotConfirm = it.take(SyncPassphrase.MAX_LENGTH) },
                        label = { Text("تأكيد كلمة المرور الجديدة") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )
                    Button(
                        onClick = {
                            when {
                                !SyncPassphrase.isValid(forgotNew) ->
                                    forgotStatus = "اختر كلمة مرور من ١٠ أحرف على الأقل."
                                forgotNew != forgotConfirm ->
                                    forgotStatus = "الكلمتان غير متطابقتين."
                                forgotBusy -> Unit
                                else -> {
                                    forgotBusy = true
                                    forgotStatus = ""
                                    scope.launch {
                                        val outcome = SyncAccount.register(context, forgotNew)
                                        forgotBusy = false
                                        when (outcome.result) {
                                            SyncAccount.Result.SUCCESS -> {
                                                val used = forgotNew
                                                forgotCode = ""
                                                forgotNew = ""
                                                forgotConfirm = ""
                                                forgotResetDone = false
                                                forgotStatus = "تم إنشاء الحساب الجديد. احفظ كود الاسترداد الجديد أدناه."
                                                if (outcome.created) {
                                                    storeRecoveryCode(used, SyncRecoveryCode.generate())
                                                }
                                            }
                                            SyncAccount.Result.INVALID_PASSPHRASE -> forgotStatus = "كلمة المرور لا تحقق الشرط."
                                            SyncAccount.Result.PROFILE_NOT_READY -> forgotStatus = "معرّف الحساب غير جاهز بعد."
                                            SyncAccount.Result.NETWORK_ERROR -> forgotStatus = "تعذّر الاتصال؛ حاول مرة أخرى."
                                            SyncAccount.Result.REJECTED -> forgotStatus = "تعذّر تفعيل الحساب؛ حاول مرة أخرى."
                                            SyncAccount.Result.RATE_LIMITED -> forgotStatus = "محاولات كثيرة — أعد المحاولة بعد قليل."
                                            else -> forgotStatus = "تعذّر تفعيل الحساب؛ حاول مرة أخرى."
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text(if (forgotBusy) "جارٍ التفعيل…" else "تفعيل المزامنة بكلمة المرور الجديدة") }
                }
                if (forgotStatus.isNotBlank()) {
                    Text(forgotStatus, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    scope.launch {
                        val pass = when {
                            SyncPassphrase.isValid(pushPassphrase) -> pushPassphrase
                            else -> withContext(Dispatchers.IO) { AutoSync.resolvePassphrase(context) }
                        }
                        if (pass == null) {
                            recoveryStatus = "أدخل كلمة مرور المزامنة في حقل الرفع أولًا."
                        } else {
                            storeRecoveryCode(pass, SyncRecoveryCode.generate())
                        }
                    }
                }) { Text("إنشاء كود استرداد جديد (يُلغي القديم)") }
                if (recoveryStatus.isNotBlank()) {
                    Text(recoveryStatus, style = MaterialTheme.typography.bodySmall)
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
