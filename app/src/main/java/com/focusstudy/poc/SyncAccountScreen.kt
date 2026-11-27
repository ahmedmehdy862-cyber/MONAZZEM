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

/** Account pairing screen. It shows the server-issued key and never stores the typed secret. */
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
                                        SyncAccount.Result.REJECTED -> "تعذّر تفعيل الحساب."
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
                    "لا تُحفظ كلمة المرور على الجهاز، ولا تُزامَن النوتة أو ملاحظات المهام أو معرّفات الجهاز.",
                    color = FocusColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "تنبيه: بياناتك المزامَنة مشفرة بكلمة المرور هذه ولا يستطيع أحد قراءتها — بمن فيهم الخادم. إن فقدتَ كلمة المرور فلن تستطيع استعادة النسخة المزامَنة أبدًا (بيانات جهازك نفسه لا تتأثر). احفظها في مكان آمن.",
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
                    onClick = {
                        when {
                            !SyncPassphrase.isValid(pushPassphrase) ->
                                pushStatus = "أدخل كلمة مرور المزامنة."
                            pushBusy -> Unit
                            else -> {
                                pushBusy = true
                                pushStatus = ""
                                scope.launch {
                                    val outcome = SyncAccount.push(context, pushPassphrase)
                                    pushBusy = false
                                    pushStatus = when (outcome) {
                                        SyncAccount.Result.SUCCESS -> "تم رفع البيانات بنجاح."
                                        SyncAccount.Result.INVALID_PASSPHRASE -> "كلمة المرور لا تحقق الشرط."
                                        SyncAccount.Result.PROFILE_NOT_READY -> "معرّف الحساب غير جاهز بعد."
                                        SyncAccount.Result.NETWORK_ERROR ->
                                            "تعذّر الرفع؛ تحقق من الاتصال وكلمة المرور ثم حاول مرة أخرى."
                                        SyncAccount.Result.REJECTED -> "تعذّر رفع البيانات."
                                    }
                                    if (outcome == SyncAccount.Result.SUCCESS) pushPassphrase = ""
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text(if (pushBusy) "جارٍ الرفع…" else "رفع البيانات") }
                if (pushStatus.isNotBlank()) {
                    Text(pushStatus, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        when {
                            !SyncPassphrase.isValid(pushPassphrase) ->
                                pullStatus = "أدخل كلمة مرور المزامنة."
                            pullBusy -> Unit
                            else -> {
                                pullBusy = true
                                pullStatus = ""
                                scope.launch {
                                    val outcome = SyncAccount.pull(context, pushPassphrase)
                                    pullBusy = false
                                    pullStatus = when (outcome) {
                                        SyncAccount.Result.SUCCESS -> "تم سحب البيانات وتطبيقها."
                                        SyncAccount.Result.INVALID_PASSPHRASE -> "كلمة المرور لا تحقق الشرط."
                                        SyncAccount.Result.PROFILE_NOT_READY -> "معرّف الحساب غير جاهز بعد."
                                        SyncAccount.Result.NETWORK_ERROR ->
                                            "تعذّر السحب؛ تحقق من الاتصال وكلمة المرور ثم حاول مرة أخرى."
                                        SyncAccount.Result.REJECTED -> "تعذّر سحب البيانات."
                                    }
                                    if (outcome == SyncAccount.Result.SUCCESS) pushPassphrase = ""
                                }
                            }
                        }
                    },
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
