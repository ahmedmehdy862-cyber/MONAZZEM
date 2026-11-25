package com.focusstudy.poc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * البوابة المركزية الوحيدة لطلب أذونات التشغيل (runtime permissions):
 * الميكروفون والإشعارات والموقع التقريبي.
 *
 * القاعدة (المرحلة 7أ من إعادة التصميم): كل إذن يُطلب مرة واحدة فقط في عمر
 * التثبيت، وعند أول استخدام فعلي للميزة التي تحتاجه — لا عند بدء التطبيق ولا
 * عند فتح شاشة. وقبل طلب النظام يُعرض شرح داخل التطبيق يوضح السبب، ولا يُطلق
 * طلب النظام إلا بعد موافقة المستخدم على الشرح.
 *
 * بعد الرفض (بما فيه «عدم السؤال مجددًا») لا تُعاد المطالبة تلقائيًا أبدًا:
 * عند محاولة استخدام الميزة مجددًا يُعرض فقط تلميح يدوي يوجّه إلى إعدادات
 * التطبيق. عمدًا لا نستخدم `shouldShowRequestPermissionRationale` لإعادة
 * الطلب: حتى الرفض غير الدائم يُعامل كرفض نهائي من جهة الطلب التلقائي.
 *
 * الحالات الحدّية:
 * - رفض ثم تفعيل يدوي من الإعدادات ثم عودة: الفحص (`isGranted`) يقرأ الحالة
 *   الحقيقية من النظام في كل مرة، فتعمل الميزة دون أي طلب جديد.
 * - قبول: الإذن ممنوح، فلا يظهر الشرح ولا الطلب مجددًا.
 * - إعادة التثبيت: `SharedPreferences` تُمسح مع التطبيق، فتصفّر العلامات
 *   تلقائيًا (سلوك طبيعي ومتوقع).
 *
 * منتقيات الصور والملفات (`GetContent` و`PickMultipleVisualMedia`) لا تحتاج
 * إذنًا أصلًا، فهي خارج هذه البوابة. والوصولات الخاصة (التنبيهات الدقيقة،
 * تجاهل تحسين البطارية، VPN، الوصول) ليست أذونات تشغيل، فتبقى على مسارها
 * الحالي دون تغيير.
 */
enum class GatedPermission(
    /** إذن التشغيل كما في `AndroidManifest.xml`. */
    val runtimePermission: String,
    val title: String,
    /** يُعرض قبل أول طلب نظام، ويوضح لماذا نحتاج الإذن وبماذا نستغني عنه. */
    val explanation: String,
    /** يُعرض بعد رفض سابق بدلًا من أي طلب جديد، ويوجّه للإعدادات يدويًا. */
    val deniedHint: String
) {
    MICROPHONE(
        Manifest.permission.RECORD_AUDIO,
        "إذن الميكروفون",
        "يستخدم المساعد الميكروفون لسماع رسالتك الصوتية وتحويلها إلى نص على جهازك فقط، ولا يُرسل صوتك إلى أي خادم. بدون هذا الإذن يمكنك مراسلة المساعد كتابةً كالمعتاد.",
        "الميكروفون مرفوض. يمكنك تفعيله يدويًا من إعدادات التطبيق (الأذونات ← الميكروفون)، أو اكتب رسالتك للمساعد بدلًا من التحدث."
    ),
    NOTIFICATIONS(
        Manifest.permission.POST_NOTIFICATIONS,
        "إذن الإشعارات",
        "نستخدم الإشعارات لتذكيرك بمواقيت الصلاة والورد والمهام، ولإظهار الجلسة الجارية والمؤقّت. بدون هذا الإذن لن تصلك أي تذكيرات، وتبقى المواقيت والمهام نفسها متاحة داخل التطبيق.",
        "الإشعارات مرفوضة. يمكنك تفعيلها يدويًا من إعدادات التطبيق (الإشعارات)، أو من شاشة الإشعارات داخل التطبيق."
    ),
    LOCATION(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        "إذن الموقع التقريبي",
        "نستخدم موقعك التقريبي مرة واحدة لحساب مواقيت الصلاة لمدينتك. الإحداثيات تُحفظ على جهازك فقط ولا تُرسل إلى أي خادم، ويمكنك بدلًا من ذلك إدخال الإحداثيات يدويًا أو اختيار مدينة من القائمة.",
        "الموقع مرفوض. يمكنك تفعيله يدويًا من إعدادات التطبيق (الأذونات ← الموقع)، أو أدخل الإحداثيات يدويًا أو اختر مدينة من القائمة."
    )
}

/** ما تعرضه نافذة البوابة: شرح قبل أول طلب نظام، أو تلميح يدوي بعد رفض سابق. */
enum class GateDialogKind { EXPLAIN, DENIED_HINT }

/** حالة نافذة بوابة واحدة: أي إذن، وبأي صيغة. */
data class GateDialogState(val permission: GatedPermission, val kind: GateDialogKind)

object PermissionGate {

    private const val PREFS = "permission_gate"

    private fun askedKey(permission: GatedPermission) = "asked_${permission.name}"

    /** الفحص الحقيقي من النظام؛ يبقى كما هو في كل موضع ولا يمر عبر البوابة. */
    fun isGranted(context: Context, permission: GatedPermission): Boolean {
        // قبل Android 13 لا يوجد إذن تشغيل للإشعارات أصلًا.
        if (permission == GatedPermission.NOTIFICATIONS &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) return true
        return ContextCompat.checkSelfPermission(
            context, permission.runtimePermission
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** true إذا أُطلق طلب النظام لهذا الإذن من قبل — ولو مرة واحدة — في عمر التثبيت الحالي. */
    fun wasAsked(context: Context, permission: GatedPermission): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(askedKey(permission), false)

    /**
     * تُستدعى لحظة إطلاق طلب النظام فعليًا فقط — لا عند عرض الشرح، ولا عند
     * ضغطة «ليس الآن» (فالمستخدم لم يُسأل من النظام بعد، ويجوز عرض الشرح له
     * مجددًا عند استخدامه الميزة مرة أخرى).
     */
    fun markAsked(context: Context, permission: GatedPermission) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(askedKey(permission), true).apply()
    }

    /** يفتح صفحة التطبيق في إعدادات النظام ليُفعّل المستخدم الإذن يدويًا. */
    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}

/**
 * نافذة البوابة الموحّدة. في صيغة الشرح: «فهمت، اطلب الإذن» تُطلق طلب النظام
 * (عبر [onRequestSystem]) و«ليس الآن» تغلق فقط. في صيغة الرفض السابق: زر واحد
 * يفتح إعدادات التطبيق، ولا يوجد أي مسار هنا يطلق طلب نظام.
 */
@Composable
fun PermissionGateDialog(
    state: GateDialogState,
    onRequestSystem: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = FocusShapes.large,
        containerColor = FocusColors.SurfaceHigh,
        title = { Text(state.permission.title, color = FocusColors.TextPrimary) },
        text = {
            Text(
                if (state.kind == GateDialogKind.EXPLAIN) state.permission.explanation
                else state.permission.deniedHint,
                color = FocusColors.TextSecondary
            )
        },
        confirmButton = {
            if (state.kind == GateDialogKind.EXPLAIN) {
                TextButton(onClick = onRequestSystem) { Text("فهمت، اطلب الإذن") }
            } else {
                TextButton(onClick = onOpenSettings) { Text("فتح إعدادات التطبيق") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (state.kind == GateDialogKind.EXPLAIN) "ليس الآن" else "إغلاق")
            }
        }
    )
}

/**
 * المتحكم الذي تملكه الشاشة التي عندها `launcher` لطلب النظام.
 * [attempt] هي نقطة الدخول الوحيدة من ضغطة المستخدم على الميزة:
 * - الإذن ممنوح → [onGranted] فورًا دون أي نافذة.
 * - سبق طلبه ورُفض → نافذة التلميح اليدوي فقط، ولا طلب نظام مجددًا.
 * - لم يُطلب قط → نافذة الشرح؛ ولا يُطلق طلب النظام إلا بعد «فهمت، اطلب الإذن».
 */
class PermissionGateController(
    private val context: Context,
    private val permission: GatedPermission,
    private val launchSystemRequest: () -> Unit,
    private val onGranted: () -> Unit
) {
    var dialog by mutableStateOf<GateDialogState?>(null)
        private set

    /** تُستدعى عند ضغطة المستخدم على الميزة التي تحتاج الإذن. */
    fun attempt() {
        when {
            PermissionGate.isGranted(context, permission) -> onGranted()
            PermissionGate.wasAsked(context, permission) ->
                dialog = GateDialogState(permission, GateDialogKind.DENIED_HINT)
            else -> dialog = GateDialogState(permission, GateDialogKind.EXPLAIN)
        }
    }

    /** ضغطة «فهمت، اطلب الإذن»: أول إطلاق فعلي لطلب النظام — هنا فقط تُسجَّل العلامة. */
    fun onDialogRequestSystem() {
        dialog = null
        PermissionGate.markAsked(context, permission)
        launchSystemRequest()
    }

    fun onDialogOpenSettings() {
        dialog = null
        PermissionGate.openAppSettings(context)
    }

    fun dismissDialog() {
        dialog = null
    }

    /** تُركَّب داخل الشاشة المالكة؛ لا تعرض شيئًا ما لم تكن هناك نافذة معلّقة. */
    @Composable
    fun Dialog() {
        dialog?.let { state ->
            PermissionGateDialog(
                state = state,
                onRequestSystem = ::onDialogRequestSystem,
                onOpenSettings = ::onDialogOpenSettings,
                onDismiss = ::dismissDialog
            )
        }
    }
}

/**
 * ينشئ [PermissionGateController] لشاشة مركّبة. `launchSystemRequest` هو
 * استدعاء `launcher` الخاص بالشاشة، و`onGranted` هو ما تفعله الميزة
 * عندما يكون الإذن ممنوحًا أصلًا (أو يُمنح بعد طلب النظام عبر `launcher` نفسه).
 */
@Composable
fun rememberPermissionGate(
    permission: GatedPermission,
    launchSystemRequest: () -> Unit,
    onGranted: () -> Unit = {}
): PermissionGateController {
    val context = LocalContext.current
    val currentLaunch = rememberUpdatedState(launchSystemRequest)
    val currentGranted = rememberUpdatedState(onGranted)
    return remember(permission) {
        PermissionGateController(
            context,
            permission,
            launchSystemRequest = { currentLaunch.value() },
            onGranted = { currentGranted.value() }
        )
    }
}
