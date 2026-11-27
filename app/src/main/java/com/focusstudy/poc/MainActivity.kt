package com.focusstudy.poc

import android.Manifest

import android.app.Activity

import android.app.AlarmManager

import android.content.Context

import android.content.Intent

import android.content.pm.PackageManager

import android.net.Uri

import android.net.VpnService

import android.os.Build

import android.os.Bundle

import android.os.PowerManager

import android.provider.Settings

import android.widget.Toast

import androidx.activity.ComponentActivity

import androidx.activity.compose.setContent

import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade

import androidx.compose.foundation.isSystemInDarkTheme

import androidx.compose.animation.core.LinearEasing

import androidx.compose.animation.core.RepeatMode

import androidx.compose.animation.core.animateFloat

import androidx.compose.animation.core.animateFloatAsState

import androidx.compose.animation.core.infiniteRepeatable

import androidx.compose.animation.core.rememberInfiniteTransition

import androidx.compose.animation.core.tween

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image

import androidx.compose.foundation.background

import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues

import androidx.compose.foundation.layout.Box

import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.lazy.LazyColumn

import androidx.compose.foundation.lazy.items

import androidx.compose.foundation.shape.CircleShape

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.AlertDialog

import androidx.compose.material3.Button

import androidx.compose.material3.ButtonDefaults

import androidx.compose.material3.Card

import androidx.compose.material3.CardDefaults

import androidx.compose.material3.Checkbox

import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor

import androidx.compose.material3.OutlinedButton

import androidx.compose.material3.OutlinedTextField

import androidx.compose.material3.Slider

import androidx.compose.material3.SliderDefaults

import androidx.compose.material3.Text

import androidx.compose.material3.TextButton
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.material3.ProvideTextStyle

import androidx.compose.runtime.Composable

import androidx.compose.runtime.CompositionLocalProvider

import androidx.compose.runtime.DisposableEffect

import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.collectAsState

import androidx.compose.runtime.getValue

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.runtime.remember

import androidx.compose.runtime.rememberUpdatedState

import androidx.compose.runtime.saveable.listSaver

import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip

import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.geometry.Size

import androidx.compose.ui.graphics.Brush

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

import androidx.compose.ui.graphics.drawscope.Stroke

import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.platform.LocalLayoutDirection

import androidx.compose.ui.text.font.FontWeight

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.input.KeyboardType

import androidx.compose.ui.unit.dp

import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

import androidx.core.content.ContextCompat

import androidx.lifecycle.Lifecycle

import androidx.lifecycle.LifecycleEventObserver

import androidx.lifecycle.compose.LocalLifecycleOwner

import androidx.lifecycle.viewmodel.compose.viewModel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader

import java.util.Locale

/** Longest back chain kept between root screens; older steps drop off the far end. */
private const val MAX_BACK_STACK_ENTRIES = 20

class MainActivity : ComponentActivity() {

    private data class PendingStart(

        val taskId: Long,

        val title: String,

        val minutes: Int,

        val packages: Set<String>

    )

    private var pendingStart: PendingStart? = null

    private var resumeVpnRequested = false

    private val vpnPermission = registerForActivityResult(

        ActivityResultContracts.StartActivityForResult()

    ) { result ->

        val granted = result.resultCode == Activity.RESULT_OK

        if (resumeVpnRequested) {

            resumeVpnRequested = false

            if (granted) {

                SessionEngine.restartVpn(this)

            } else {

                toast("لم يُمنح إذن VPN، لذلك لن تُحجب الشبكة.")

            }

            return@registerForActivityResult

        }

        val pending = pendingStart

        pendingStart = null

        if (pending == null) return@registerForActivityResult

        if (!granted) {

            toast("لن تبدأ الجلسة بدون إذن VPN.")

            return@registerForActivityResult

        }

        SessionEngine.start(this, pending.taskId, pending.title, pending.minutes, pending.packages)

    }

    /**
     * حالة نافذة بوابة إذن الإشعارات (المرحلة 7أ). يملكها النشاط لأن الطلب
     * يُطلق من أماكن غير مركّبة أيضًا (إنهاء الإعداد الأولي)، فتُعرض النافذة
     * من `setContent` فوق أي شاشة كانت ظاهرة.
     */
    private var notificationGateDialog by mutableStateOf<GateDialogState?>(null)

    /**
     * طلب إذن الإشعارات وحده (المرحلة 7أ: البوابة المركزية). الموقع التقريبي
     * لم يعد يُطلب هنا: يُطلب عند أول استخدام لزر «استخدم موقعي التقريبي» في
     * شاشة مواقيت الصلاة عبر البوابة نفسها. الوصولات الخاصة (التنبيهات الدقيقة،
     * البطارية) لا يمكن دمجها من النظام، فتُسلسل بعدها مباشرة متخطيةً الممنوح.
     */
    private val runtimePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            results[Manifest.permission.POST_NOTIFICATIONS] == true
        if (notificationsGranted) {
            NotificationMaintenance.rescheduleAll(applicationContext)
        } else {
            NotificationMaintenance.log(this, NotificationMaintenance.PERMISSION_DENIED)
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { ConsentStore.record(applicationContext) }
        }
        requestSpecialAccessInOrder(0)
    }

    private val specialAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        requestSpecialAccessInOrder(specialAccessStep + 1)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { ConsentStore.record(applicationContext) }
        }
    }

    private var specialAccessStep = 0

    /** Only screens that still need a grant are returned; already-granted ones are skipped. */
    private fun specialAccessIntent(index: Int): Intent? = when (index) {
        0 -> {
            val alarms = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarms?.canScheduleExactAlarms() == false) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:$packageName"))
            } else null
        }
        1 -> {
            val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (power?.isIgnoringBatteryOptimizations(packageName) == false) {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            } else null
        }
        else -> null
    }

    private fun requestSpecialAccessInOrder(from: Int) {
        for (index in from until 2) {
            val intent = runCatching { specialAccessIntent(index) }.getOrNull() ?: continue
            specialAccessStep = index
            if (runCatching { specialAccess.launch(intent) }.isSuccess) return
        }
        specialAccessStep = 2
        // Every grant is in place now: rebuild the window so alarms and channels use it at once.
        NotificationMaintenance.rescheduleAll(applicationContext)
    }


    override fun onNewIntent(intent: Intent) {

        super.onNewIntent(intent)

        setIntent(intent)

        if (intent.getBooleanExtra(StudyNotificationScheduler.EXTRA_OPEN_TASKS, false)) {

            NotificationNavigation.requestTasks()

        }

        if (intent.getBooleanExtra(AppUpdateManager.EXTRA_OPEN_UPDATES, false)) {

            NotificationNavigation.requestUpdates()

        }

        if (intent.getBooleanExtra(WirdReminderScheduler.EXTRA_OPEN_QURAN, false)) {

            NotificationNavigation.requestQuran()

        }

        if (intent.getBooleanExtra(SkillReminderScheduler.EXTRA_OPEN_SKILLS, false)) {

            NotificationNavigation.requestSkills()

        }

        if (intent.getBooleanExtra(ExerciseReminderScheduler.EXTRA_OPEN_LIFE, false)) {

            NotificationNavigation.requestLife()

        }

        val taskId = intent.getLongExtra(ReminderEngine.EXTRA_TASK_ID, 0L)

        if (taskId > 0L) NotificationNavigation.requestTask(taskId)

    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        if (intent?.getBooleanExtra(StudyNotificationScheduler.EXTRA_OPEN_TASKS, false) == true) {

            NotificationNavigation.requestTasks()

        }

        if (intent?.getBooleanExtra(WirdReminderScheduler.EXTRA_OPEN_QURAN, false) == true) {

            NotificationNavigation.requestQuran()

        }

        if (intent?.getBooleanExtra(SkillReminderScheduler.EXTRA_OPEN_SKILLS, false) == true) {

            NotificationNavigation.requestSkills()

        }

        if (intent?.getBooleanExtra(ExerciseReminderScheduler.EXTRA_OPEN_LIFE, false) == true) {

            NotificationNavigation.requestLife()

        }

        SessionEngine.init(this)
        AppUpdateManager.schedule(this)
        NetworkRetryJob.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            AppUpdateManager.check(applicationContext, manual = false)
        }

        setContent {

            val preferences = remember { mutableStateOf(FocusPreferences.load(this@MainActivity)) }
            val dark = when (preferences.value.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            FocusTheme(
                accent = FocusColors.accents[preferences.value.accentIndex],
                textScale = preferences.value.textScale,
                dark = dark
            ) {

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {

                    AppRoot(

                        preferences = preferences.value,
                        onPreferencesChanged = { updated ->
                            FocusPreferences.save(this@MainActivity, updated)
                            val profileChanged = updated.profile != preferences.value.profile
                            val timezoneChanged = updated.timezoneId != preferences.value.timezoneId
                            preferences.value = updated
                            if (profileChanged) {
                                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                                    runCatching { ProfilePresets.seed(applicationContext, updated.profile) }
                                }
                            }
                            // A different IANA zone changes every future wall-clock alarm: rebuild
                            // all local schedules once so no prayer, dhikr or task reminder is lost.
                            if (timezoneChanged) NotificationMaintenance.rescheduleAll(applicationContext)
                        },

                        onStartSession = ::startFocusSession,

                        onResumeVpn = ::resumeVpnBlocking,

                        onRequestNotifications = ::requestNotificationPermissionIfNeeded,
                        onGrantVpn = ::grantVpnConsent,
                        openUpdatesInitially = intent?.getBooleanExtra(AppUpdateManager.EXTRA_OPEN_UPDATES, false) == true,
                        openTasksInitially = intent?.getBooleanExtra(StudyNotificationScheduler.EXTRA_OPEN_TASKS, false) == true,
                        autoStartTaskId = intent?.getLongExtra(ReminderEngine.EXTRA_TASK_ID, 0L)?.takeIf { it > 0 }

                    )

                    // نافذة بوابة إذن الإشعارات (شرح قبل أول طلب، وتلميح يدوي بعد الرفض).
                    notificationGateDialog?.let { state ->
                        PermissionGateDialog(
                            state = state,
                            onRequestSystem = ::onNotificationGateRequestSystem,
                            onOpenSettings = ::onNotificationGateOpenSettings,
                            onDismiss = { notificationGateDialog = null }
                        )
                    }
                }

            }

        }

    }

    /**
     * القمع الوحيد لطلب إذن الإشعارات: إنهاء الإعداد الأولي، وزر «منح» في
     * شاشة الإشعارات، وصف الإذن في إعداد الجلسة. لا يُطلق طلب نظام مباشرةً:
     * البوابة المركزية تعرض شرحًا داخل التطبيق أولًا، وتطلب من النظام مرة
     * واحدة فقط في عمر التثبيت، ثم تكتفي بالتلميح اليدوي بعد أي رفض.
     *
     * الموقع التقريبي لم يعد يُطلب هنا مع الإشعارات: يُطلب عند أول استخدام
     * لزر «استخدم موقعي التقريبي» في شاشة مواقيت الصلاة عبر البوابة نفسها.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (PermissionGate.isGranted(this, GatedPermission.NOTIFICATIONS)) {
            // لا شيء يطلبه النظام: نمر مباشرة بمسار الوصولات الخاصة.
            requestSpecialAccessInOrder(0)
            return
        }
        notificationGateDialog = if (PermissionGate.wasAsked(this, GatedPermission.NOTIFICATIONS)) {
            GateDialogState(GatedPermission.NOTIFICATIONS, GateDialogKind.DENIED_HINT)
        } else {
            GateDialogState(GatedPermission.NOTIFICATIONS, GateDialogKind.EXPLAIN)
        }
    }

    /** ضغطة «فهمت، اطلب الإذن» في نافذة البوابة: أول إطلاق فعلي لطلب النظام. */
    private fun onNotificationGateRequestSystem() {
        notificationGateDialog = null
        PermissionGate.markAsked(this, GatedPermission.NOTIFICATIONS)
        runtimePermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    /** ضغطة «فتح إعدادات التطبيق» في نافذة التلميح بعد الرفض. */
    private fun onNotificationGateOpenSettings() {
        notificationGateDialog = null
        PermissionGate.openAppSettings(this)
    }

    /** Only reachable from the setup screen, after duration, apps and permissions are in place. */

    private fun startFocusSession(taskId: Long, title: String, minutes: Int, packages: Set<String>) {

        SessionEngine.updateTaskBlocked(taskId, packages)

        if (packages.isEmpty()) {

            toast("اختر تطبيقًا واحدًا على الأقل قبل بدء الجلسة.")

            return

        }

        val consent = VpnService.prepare(this)

        if (consent != null) {

            pendingStart = PendingStart(taskId, title, minutes, packages)

            vpnPermission.launch(consent)

        } else {

            SessionEngine.start(this, taskId, title, minutes, packages)

        }

    }

    /** Re-creates network filtering during a running session; the session itself stays locked. */

    /** Asks for VPN consent from any screen; the ready state is re-read on resume. */

    private fun grantVpnConsent() {

        val consent = VpnService.prepare(this)

        if (consent != null) {

            vpnPermission.launch(consent)

        }

    }

    private fun resumeVpnBlocking() {

        val consent = VpnService.prepare(this)

        if (consent != null) {

            resumeVpnRequested = true

            vpnPermission.launch(consent)

        } else {

            SessionEngine.restartVpn(this)

        }

    }

    private fun toast(message: String) {

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    }

}

@Composable

private fun AppRoot(

    preferences: FocusPreferences,
    onPreferencesChanged: (FocusPreferences) -> Unit,

    onStartSession: (Long, String, Int, Set<String>) -> Unit,

    onResumeVpn: () -> Unit,

    onRequestNotifications: () -> Unit,
    onGrantVpn: () -> Unit,
    openUpdatesInitially: Boolean,
    openTasksInitially: Boolean = false,
    autoStartTaskId: Long? = null

) {

    val context = LocalContext.current
    // الرفيق ثلاثي الأبعاد «منظم» (0.34.0): متحكم واحد مرفوع هنا فيعيش
    // مع الجذر كله؛ الرئيسية تعرضه، وإكمال مهمة أو فتح تذكير يحرّكه.
    val companionController = remember { CompanionController() }
    LaunchedEffect(preferences.companion3dEnabled) {
        companionController.setEnabled(preferences.companion3dEnabled)
    }
    // تحية مرة واحدة عند فتح التطبيق (لا تتكرر مع إعادة التركيب).
    LaunchedEffect(Unit) {
        if (preferences.companion3dEnabled) companionController.greet()
    }
    // الرفيق ثلاثي الأبعاد: المحرك + المحمِّل + النموذج مرفوعون إلى
    // Companion3DHost على مستوى الجذر (0.35.0) — يعيشون مع النشاط كله ولا
    // يُعاد إنشاؤهم عند إعادة تركيب الرئيسية أو تبديل التبويبات. التحميل
    // المسبق يبدأ هنا فور فتح التطبيق (أثناء شاشة الترحيب) فلا يحجب
    // واجهة «اليوم»؛ وتفعيل الرفيق لاحقًا من الإعدادات يبدأ التحميل حينها.
    val companionEngine = rememberEngine()
    val companionModelLoader = rememberModelLoader(companionEngine)
    val companion3DHost = remember(companionEngine, companionModelLoader) {
        Companion3DHost(companionEngine, companionModelLoader)
    }
    LaunchedEffect(companion3DHost.loadRetry, preferences.companion3dEnabled) {
        if (preferences.companion3dEnabled) companion3DHost.loadModel()
    }
    // حارس المهلة للتحميل المعلّق فقط: إن بقي النموذج null بعد المهلة
    // (ولم يُسجَّل فشل صريح)، يُعرض البديل الثابت.
    LaunchedEffect(companion3DHost.modelInstance, companion3DHost.loadRetry, preferences.companion3dEnabled) {
        if (!preferences.companion3dEnabled) return@LaunchedEffect
        if (companion3DHost.modelInstance != null || companion3DHost.loadFailed) return@LaunchedEffect
        delay(MODEL_LOAD_TIMEOUT_MS)
        companion3DHost.markTimedOut()
    }
    // إصدار مالك الرفيق عند تدمير الجذر فقط (0.35.0): إصدار المتحكم هنا
    // أُزيل — كان DisposableEffect يعطّل المتحكم (DISABLED) عند أي مغادرة
    // للتركيب الجذري فيخفي الرفيق بصمت دون أي سجل.
    DisposableEffect(companion3DHost) {
        onDispose { companion3DHost.release() }
    }
    LaunchedEffect(
        preferences.morningMessageEnabled,
        preferences.eveningMessageEnabled,
        preferences.morningMessageHour,
        preferences.eveningMessageHour
    ) {
        StudyNotificationScheduler.update(context, preferences)
    }
    LaunchedEffect(Unit) {
        PrayerReminderScheduler.schedule(context)
        DhikrReminderScheduler.schedule(context)
        WirdReminderScheduler.schedule(context)
        SkillReminderScheduler.schedule(context)
        // One cached GPS fix is enough until the day changes or the user moves a meaningful
        // distance. The check is offline, permission-gated and never blocks the UI.
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val here = withContext(Dispatchers.IO) { currentApproximateLocation(context) }
            if (here != null && PrayerPrefs.needsLocationRefresh(context, here.latitude, here.longitude)) {
                PrayerPrefs.saveLocation(context, here.latitude, here.longitude, "موقعي")
                PrayerReminderScheduler.schedule(context)
            }
        }
    }

    // Keep the local consent ledger current even if the user never opens the session setup.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { ConsentStore.record(context) } } }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { ReminderRescheduleJob.schedule(context) }
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { TeamMessages.checkForNew(context) }
            // A first launch without a connection must try again on the next open.
            runCatching { UserRegistry.register(context) }
        }
    }

    // First launch and every later name/profile change introduce the user to the admin dashboard.
    // `force` skips the retry throttle so a corrected name reaches the panel immediately.
    // The request is fire-and-forget: offline users keep using the app normally.
    LaunchedEffect(preferences.studentName, preferences.profile) {
        withContext(Dispatchers.IO) { UserRegistry.register(context, force = true) }
    }

    LaunchedEffect(preferences.smartRemindersEnabled, preferences.leadMinutes, preferences.notificationDensity) {

        ReminderEngine.channels(context)

        if (preferences.smartRemindersEnabled) ReminderEngine.refresh(context)
        else StudyDb.get(context).pendingReminders(200).forEach { StudyDb.get(context).markReminderDone(it.id, "disabled") }

    }

    val session by SessionEngine.active.collectAsState()

    val remaining by SessionEngine.remainingSeconds.collectAsState()

    val vpnLost by SessionEngine.vpnLost.collectAsState()

    var setupTaskId by remember { mutableStateOf<Long?>(null) }

    var autoStartConsumed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(autoStartTaskId) {

        if (!autoStartConsumed && autoStartTaskId != null && autoStartTaskId > 0L) {

            setupTaskId = autoStartTaskId

            autoStartConsumed = true

        }

    }

    var pickerTaskId by remember { mutableStateOf<Long?>(null) }

    // جذر البدء البارد: عند فتح التطبيق من إشعار يكون الجذر وجهة الإشعار لا «اليوم».
    val initialRoot = if (openUpdatesInitially) "updates" else if (openTasksInitially) "tasks" else "today"
    var rootTab by rememberSaveable {
        mutableStateOf(initialRoot)
    }
    // The assistant's display name, renameable from its own screen; the drawer row
    // and the conversation title read this state, not a hard-coded «منظم».
    var assistantName by remember { mutableStateOf(AssistantStore.loadSettings(context).name) }
    // Real back history (phase 2): the old single `previousRootTab` slot kept only the
    // last root, so back from a chain like «المزيد ← التخصيص ← التطبيقات الدائمة»
    // jumped straight to the start. The stack below is capped so it cannot grow
    // without bound, and it is cleared on every root jump (bottom bar / drawer).
    // بدء بارد من إشعار: «اليوم» قاعدة في المكدس، فيعود زر الرجوع إلى الرئيسية
    // بدل الخروج من التطبيق. البدء البارد من المصحف/المهارات يأتي عبر عدّاد
    // الإشعار، فيدفع openSecondary «اليوم» إلى المكدس عند استقباله.
    var backStack by rememberSaveable { mutableStateOf(if (initialRoot == "today") emptyList() else listOf("today")) }
    fun openSecondary(tab: String) {
        if (tab == rootTab) return
        backStack = (backStack + rootTab).takeLast(MAX_BACK_STACK_ENTRIES)
        rootTab = tab
    }
    /** A root jump (bottom bar / drawer): the old chain no longer applies. */
    fun openRoot(tab: String) {
        backStack = emptyList()
        rootTab = tab
    }
    fun returnToPreviousTab() {
        val previous = backStack.lastOrNull()
        backStack = backStack.dropLast(1)
        rootTab = previous ?: "today"
    }

    // A reminder tapped while the app is already running: onNewIntent bumps this counter.
    // Every destination below goes through openSecondary, not a bare assignment, so
    // the root the person was on is pushed onto the back stack and back returns to it.
    val tasksRequest = NotificationNavigation.openTasksRequest
    LaunchedEffect(tasksRequest) {
        if (tasksRequest > 0) {
            // فتح تذكير: منظم يشجّع — يظهر متحركًا على الرئيسية عند
            // الوصول إليها خلال دقيقة (نافذة الـ 60 ثانية في
            // CompanionController)، وصورة ثابتة في الإشعار نفسه (§13).
            companionController.encourage()
            openSecondary("tasks")
        }
    }
    val updatesRequest = NotificationNavigation.openUpdatesRequest
    LaunchedEffect(updatesRequest) {
        if (updatesRequest > 0) openSecondary("updates")
    }
    val quranRequest = NotificationNavigation.openQuranRequest
    LaunchedEffect(quranRequest) {
        if (quranRequest > 0) openSecondary("quran")
    }
    val skillsRequest = NotificationNavigation.openSkillsRequest
    LaunchedEffect(skillsRequest) {
        if (skillsRequest > 0) openSecondary("skills")
    }
    val prayerTimesRequest = NotificationNavigation.openPrayerTimesRequest
    LaunchedEffect(prayerTimesRequest) {
        if (prayerTimesRequest > 0) openSecondary("prayer_times")
    }
    val lifeRequest = NotificationNavigation.openLifeRequest
    LaunchedEffect(lifeRequest) {
        if (lifeRequest > 0) openSecondary("life")
    }
    val requestedTaskId = NotificationNavigation.requestedTaskId
    LaunchedEffect(requestedTaskId) {
        if (requestedTaskId != null && requestedTaskId > 0L) {
            setupTaskId = requestedTaskId
            NotificationNavigation.clearTaskRequest()
        }
    }
    var sectionsOpen by remember { mutableStateOf(false) }
    // معرّف التمرين المفتوح في شاشة شرحه (0.34.0)؛ null تعني لا شاشة مفتوحة.
    var exerciseDetailId by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    /**
     * ينفّذ طلب المساعد ببدء جلسة تركيز عبر مسار بدء الجلسة القائم
     * ([onStartSession]). المهمة المذكورة تُستخدم بتطبيقاتها المحظورة مضافًا إليها
     * التطبيقات الدائمة، والجلسة العامة تستخدم التطبيقات الدائمة وحدها. بلا
     * تطبيقات محظورة تُفتح شاشة إعداد الجلسة ليختارها المستخدم، بدل رفض
     * صامت أو تنبيه عابر. معرّف 0 يعني جلسة عامة بلا مهمة.
     */
    suspend fun assistantStartFocus(action: AssistantAction.StartFocus): AssistantFocusOutcome {
        if (SessionEngine.active.value != null) return AssistantFocusOutcome.ALREADY_RUNNING
        val minutes = action.minutes.coerceIn(5, 120)
        val taskId: Long
        val title: String
        val packages: Set<String>
        if (action.taskId != null) {
            val task = runCatching { TaskRepository.get(context).task(action.taskId) }.getOrNull()
            packages = task?.blockedPackages.orEmpty() + preferences.permanentBlockedPackages
            taskId = action.taskId
            title = task?.title ?: action.taskTitle ?: "جلسة تركيز"
        } else {
            packages = preferences.permanentBlockedPackages
            taskId = 0L
            title = "جلسة تركيز"
        }
        if (packages.isEmpty()) {
            setupTaskId = taskId
            return AssistantFocusOutcome.SETUP_OPENED
        }
        return runCatching {
            onStartSession(taskId, title, minutes, packages)
            AssistantFocusOutcome.STARTED
        }.getOrDefault(AssistantFocusOutcome.FAILED)
    }

    val screen = when {

        session != null -> "session"

        setupTaskId != null -> "setup"

        pickerTaskId != null -> "picker"

        !preferences.onboardingCompleted -> "onboarding"

        else -> rootTab

    }

    // System back must return to the previous screen instead of leaving the app.
    val bottomTabIds = remember(preferences.bottomTabs) { BottomTab.normalize(preferences.bottomTabs) }
    /** Drawer picks a bottom-bar root when it is one, otherwise a secondary screen. */
    fun openDrawerDestination(tab: String) {
        if (tab in bottomTabIds) openRoot(tab) else openSecondary(tab)
    }
    var quickAddTask by remember { mutableStateOf(0) }
    // System back is also caught on a bottom-tab screen when the back stack is
    // non-empty (e.g. the app was cold-started from a notification): back then pops
    // to the previous root instead of leaving the app. On a bottom-tab screen with
    // an empty stack, back keeps its usual meaning: exit the app.
    androidx.activity.compose.BackHandler(
        enabled = screen != "session" && screen != "onboarding" &&
            (screen !in bottomTabIds || backStack.isNotEmpty())
    ) {
        when {
            pickerTaskId != null -> pickerTaskId = null
            setupTaskId != null -> setupTaskId = null
            else -> returnToPreviousTab()
        }
    }

    // شاشة ترحيبية بسيطة (0.35.0): ترحيب قصير قبل الدخول يخفف حدة الإقلاع،
    // ينتقل تلقائيًا بعد ~1.2 ثانية أو بالنقر، ولا يوقف الدخول أبدًا.
    // تُعرض بعد الإعداد الأولي فقط، ولا تُعرض أثناء جلسة تركيز جارية.
    // rememberSaveable حتى لا تعود مع تدوير الشاشة.
    var showWelcome by rememberSaveable { mutableStateOf(true) }
    if (showWelcome && preferences.onboardingCompleted && session == null) {
        WelcomeScreen(
            userName = preferences.studentName,
            onDone = { showWelcome = false }
        )
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Edge swipe opens the drawer only on main screens, never over a session
        // or onboarding; the header menu button is the other way in.
        // The drawer sits on the start side: in this RTL app that is the RIGHT edge.
        // ModalNavigationDrawer follows LocalLayoutDirection, which MainActivity sets
        // to Rtl above AppRoot — do not force Ltr here or the drawer flips to the left.
        gesturesEnabled = screen in bottomTabIds,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = FocusColors.Surface,
                drawerContentColor = FocusColors.TextPrimary
            ) {
                AppDrawerContent(
                    current = rootTab,
                    userName = preferences.studentName,
                    profileLabel = preferences.profile.label,
                    assistantName = assistantName,
                    avatarPath = preferences.avatarPath,
                    companionStyle = preferences.companionStyle,
                    collapsedGroups = preferences.drawerCollapsedGroups,
                    onToggleDrawerGroup = { title ->
                        onPreferencesChanged(
                            preferences.copy(
                                drawerCollapsedGroups = toggleDrawerGroup(preferences.drawerCollapsedGroups, title)
                            )
                        )
                    },
                    onSelect = { tab ->
                        drawerScope.launch { drawerState.close() }
                        openDrawerDestination(tab)
                    }
                )
            }
        }
    ) {
    BrandBackground(animate = !preferences.reduceMotion && session == null) {

        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Crossfade(
            targetState = screen,
            animationSpec = tween(if (preferences.reduceMotion) MotionSpec.SHORT_MS else MotionSpec.LONG_MS),
            label = "screen",
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { current ->

            when (current) {

                "onboarding" -> FirstLaunchSetupScreen(
                    current = preferences,
                    onComplete = onPreferencesChanged,
                    onRequestNotifications = onRequestNotifications
                )

                "session" -> session?.let { active ->

                    SessionScreen(

                        session = active,

                        remainingSeconds = remaining,

                        vpnLost = vpnLost,

                        onResumeVpn = onResumeVpn

                    )

                }

                "setup" -> setupTaskId?.let { id ->

                    SessionSetupScreen(

                        taskId = id,
                        defaultMinutes = preferences.defaultSessionMinutes,

                        onBack = { setupTaskId = null },

                        onRequestNotifications = onRequestNotifications,
                        onGrantVpn = onGrantVpn,

                        onStart = { taskId, title, minutes, packages ->

                            setupTaskId = null

                            onStartSession(taskId, title, minutes, packages)

                        }

                    )

                }

                "picker" -> pickerTaskId?.let { id ->

                    BlockedAppsScreen(taskId = id, onDone = { pickerTaskId = null })

                }

                "subjects" -> SubjectsScreen(onBack = { returnToPreviousTab() })
                "task_types" -> TaskTypesScreen(onBack = { returnToPreviousTab() })
                "skills" -> SkillsScreen(onBack = { returnToPreviousTab() })
                "notes" -> NotesScreen(onBack = { returnToPreviousTab() })
                "bottom_bar" -> BottomBarSettingsScreen(
                    selected = preferences.bottomTabs,
                    onBack = { returnToPreviousTab() },
                    onSave = { tabs ->
                        onPreferencesChanged(preferences.copy(bottomTabs = tabs))
                        returnToPreviousTab()
                    }
                )
                "messages" -> TeamMessagesScreen(onBack = { returnToPreviousTab() })
                "notifications" -> NotificationCenterScreen(
                    preferences = preferences,
                    onBack = { returnToPreviousTab() },
                    onSave = onPreferencesChanged,
                    onRequestNotifications = onRequestNotifications,
                    onOpenPrayerSettings = { openSecondary("prayer_times") },
                    onOpenPersonalization = { openSecondary("settings") }
                )
                "goals" -> GoalsScreen(onBack = { returnToPreviousTab() })
                "life" -> LifeTrackerScreen(
                    onBack = { returnToPreviousTab() },
                    onOpenTasks = { rootTab = "tasks" },
                    onOpenExercise = { exerciseDetailId = it.id; openSecondary("exercise_detail") }
                )
                "exercise_detail" -> ExerciseDetailRoute(
                    exerciseId = exerciseDetailId,
                    onBack = { returnToPreviousTab() }
                )
                "assistant" -> AssistantScreen(
                    onBack = { returnToPreviousTab() },
                    userName = preferences.studentName,
                    avatarPath = preferences.avatarPath,
                    companionStyle = preferences.companionStyle,
                    assistantName = assistantName,
                    onAssistantNameChanged = { assistantName = it },
                    onOpenTasks = { openRoot("tasks") },
                    onAssistantAddTask = { title, dueAt, priorityLevel, recurrence ->
                        runCatching {
                            TaskRepository.get(context).add(
                                title = title,
                                plannedMinutes = 30,
                                blockedPackages = emptySet(),
                                priority = TaskPriority.from(priorityLevel ?: TaskPriority.MEDIUM.level),
                                dueAt = dueAt,
                                recurrence = recurrence
                            )
                            SessionEngine.notifyTasksChanged()
                            true
                        }.getOrDefault(false)
                    },
                    onAssistantLogWird = {
                        runCatching {
                            val life = LifeRepository.get(context)
                            val settings = life.wirdSettings()
                            val todayKey = LocalDate.now(AppTimeZone.zone(context)).toString()
                            life.logWird(todayKey, settings.pagesPerDay, settings.surah)
                        }.getOrDefault(false)
                    },
                    onAssistantStartFocus = { action -> assistantStartFocus(action) },
                    onAssistantCompleteTask = { taskId ->
                        runCatching {
                            TaskRepository.get(context).setStatus(taskId, TaskStatus.DONE)
                            SessionEngine.notifyTasksChanged()
                            companionController.celebrateAchievement()
                            true
                        }.getOrDefault(false)
                    },
                    onAssistantOpenQuran = { openSecondary("quran") },
                    onAssistantOpenToday = { openRoot("today") },
                    companionController = companionController
                )
                "quran" -> QuranRouteScreen(onBack = { returnToPreviousTab() })
                "adhkar" -> AdhkarRouteScreen(onBack = { returnToPreviousTab() })
                "calendar" -> StudyCalendarScreen(
                    onBack = { returnToPreviousTab() },
                    onStartTask = { taskId -> setupTaskId = taskId }
                )
                "more" -> MoreSectionsScreen(onOpen = { openSecondary(it) })
                "prayer_times" -> PrayerTimesScreen(
                    onBack = { returnToPreviousTab() },
                    timezoneId = preferences.timezoneId,
                    onTimeZoneChanged = { id -> onPreferencesChanged(preferences.copy(timezoneId = id)) },
                    onOpenNotifications = { openSecondary("notifications") }
                )
                "updates" -> AppUpdatesScreen(onBack = { returnToPreviousTab() })
                "settings" -> PersonalizationScreen(
                    current = preferences,
                    onBack = { returnToPreviousTab() },
                    onSave = onPreferencesChanged,
                    onManagePermanentApps = { openSecondary("permanent_apps") },
                    onOpenNotifications = { openSecondary("notifications") }
                )
                "permanent_apps" -> PermanentAppsScreen(
                    selectedPackages = preferences.permanentBlockedPackages,
                    onBack = { rootTab = "settings" },
                    onSave = { packages -> onPreferencesChanged(preferences.copy(permanentBlockedPackages = packages)) }
                )
                "support" -> SupportScreen(onBack = { returnToPreviousTab() })
                "sync" -> SyncAccountScreen(onBack = { returnToPreviousTab() })
                "privacy" -> PrivacyScreen(onBack = { returnToPreviousTab() })
                "planner" -> StudyPlannerScreen(
                    dailyGoal = preferences.dailyGoalMinutes,
                    onBack = { returnToPreviousTab() },
                    onStartTask = { taskId -> setupTaskId = taskId },
                    profile = preferences.profile
                )
                "analytics" -> StudyAnalyticsScreen(onBack = { returnToPreviousTab() })

                "today", "tasks" -> TasksScreen(

                    onOpenSetup = { setupTaskId = it },

                    onEditBlocked = { pickerTaskId = it },

                    dashboard = current == "today",
                    onOpenAllTasks = { openRoot("tasks") },
                    onOpenMessages = { openSecondary("messages") },
                    onOpenPrayerSettings = { openSecondary("prayer_times") },
                    preferences = preferences,
                    addRequest = quickAddTask,
                    onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                    onOpenAssistant = { openSecondary("assistant") },
                    assistantName = assistantName,
                    onOpenSkills = { openSecondary("skills") },
                    onOpenGoals = { openSecondary("goals") },
                    companionController = companionController,
                    companion3DHost = companion3DHost,
                    onTaskCompleted = { companionController.celebrateAchievement() },
                    showBack = backStack.isNotEmpty() && current == "tasks",
                    onBack = { returnToPreviousTab() },
                    // زر الثيم يبدّل فاتح ↔ داكن فقط (إصلاح 0.34.0): وضع
                    // «حسب النظام» خرج من دورة الزر — كان يدخل فيها فيجعل
                    // الضغطة الثالثة تتبع إعداد الهاتف فجأة. الوضع «نظام»
                    // يبقى متاحًا من «التخصيص ← المظهر» لمن يريده صراحةً.
                    onToggleTheme = {
                        onPreferencesChanged(
                            preferences.copy(
                                themeMode = when (preferences.themeMode) {
                                    ThemeMode.DARK -> ThemeMode.LIGHT
                                    else -> ThemeMode.DARK
                                }
                            )
                        )
                    }

                )

            }

        }

        if (screen in bottomTabIds) {
            FocusDock(
                tabs = bottomTabIds,
                current = rootTab,
                onSelect = { tab ->
                    // Tapping the open section goes back to the head of that section,
                    // and a root jump clears the back stack built by secondary screens.
                    if (rootTab != tab) openRoot(tab)
                },
                onOpenSections = { sectionsOpen = true },
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
            )
        }
        }

        // One persistent quick-add button for every main (bottom-tab) screen: the two
        // "مهمة +" header buttons (dashboard + today plan) were removed, so this FAB is
        // now the single place to add a task, skill or goal from anywhere.
        if (screen in bottomTabIds) {
            // Floats over the corner the bar leaves empty; the list opens upwards from it.
            // Anchored to the dock's own coordinates (0.34.0): the dock lives in a
            // safeDrawing-padded column, so on gesture-navigation phones it sits
            // higher by the system-bar inset — while this button used to measure
            // from the physical screen bottom and cover the dock's top edge,
            // exactly where «الأقسام» sits. Adding the same bottom inset keeps
            // the 16dp gap on every device and every navigation mode.
            QuickAddFab(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 18.dp,
                        bottom = LayoutSpec.FAB_BOTTOM_DP.dp +
                            WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
                    ),
                onAddTask = {
                    rootTab = "tasks"
                    quickAddTask++
                },
                onAddSkill = { openSecondary("skills") },
                onAddGoal = { openSecondary("goals") }
            )
        }

        if (sectionsOpen) {
            SectionsSheet(
                pinned = preferences.bottomTabs,
                onDismiss = { sectionsOpen = false },
                onSelect = { tab ->
                    sectionsOpen = false
                    openSecondary(tab)
                },
                onTogglePin = { tab ->
                    val current = BottomTab.normalize(preferences.bottomTabs)
                    val next = when {
                        tab in current -> current - tab
                        BottomTab.canAdd(current) -> current + tab
                        else -> current
                    }
                    if (next != current) onPreferencesChanged(preferences.copy(bottomTabs = BottomTab.normalize(next)))
                },
                // السحب والإفلات في الورقة (0.34.0): يُحفظ فورًا كالتثبيت،
                // فيتحدث الشريط لحظيًا ويستمر الترتيب بعد إعادة التشغيل.
                onReorder = { next ->
                    onPreferencesChanged(preferences.copy(bottomTabs = BottomTab.normalize(next)))
                }
            )
        }

    }
    }

}
/**
 * تجميع زمني لقائمة «مهامي» بعد الفلترة والترتيب والبحث: الفلاتر والترتيب تعمل
 * أولًا على القائمة الكاملة، ثم يُجمَّع الناتج المفلتر فقط — فلا تتغير نتيجة
 * أي فلتر أو ترتيب، ويبقى ترتيب المهام داخل كل مجموعة كما رتّبها المستخدم.
 * ترتيب المجموعات ثابت: متأخرة، اليوم، غدًا، لاحقًا (يومًا بيوم، تصاعديًا)،
 * مكتملة، ثم بلا موعد أخيرة.
 */
private enum class TaskTimeGroup(val label: String) {
    OVERDUE("متأخرة"),
    TODAY("اليوم"),
    TOMORROW("غدًا"),
    LATER("لاحقًا"),
    DONE("مكتملة"),
    UNDATED("بلا موعد")
}

private data class TaskTimeSection(val group: TaskTimeGroup, val date: LocalDate?, val tasks: List<StudyTask>)

private fun groupTasksByTime(tasks: List<StudyTask>, today: LocalDate, zone: ZoneId): List<TaskTimeSection> {
    val buckets = linkedMapOf<TaskTimeGroup, MutableList<StudyTask>>()
    val laterByDate = linkedMapOf<LocalDate, MutableList<StudyTask>>()
    for (task in tasks) {
        // المكتملة مجموعة مستقلة مهما كان موعدها: وضعها مع «متأخرة» حسب تاريخها
        // القديم كان سيوسمها خطأً بأنها متأخرة. والمؤجلة/الملغاة التي مضى موعدها
        // تبقى في «متأخرة» لأن موعدها فعلًا مضى، وبطاقتها نفسها توضح حالتها.
        val bucket = when {
            TaskState.completed(task) -> TaskTimeGroup.DONE
            task.dueAt == null -> TaskTimeGroup.UNDATED
            else -> {
                val due = TaskState.dueDate(task, zone)
                when {
                    due == null -> TaskTimeGroup.UNDATED
                    due.isBefore(today) -> TaskTimeGroup.OVERDUE
                    due == today -> TaskTimeGroup.TODAY
                    due == today.plusDays(1) -> TaskTimeGroup.TOMORROW
                    else -> null
                }
            }
        }
        if (bucket != null) {
            buckets.getOrPut(bucket) { mutableListOf() } += task
        } else {
            val due = TaskState.dueDate(task, zone) ?: continue
            laterByDate.getOrPut(due) { mutableListOf() } += task
        }
    }
    val sections = mutableListOf<TaskTimeSection>()
    listOf(TaskTimeGroup.OVERDUE, TaskTimeGroup.TODAY, TaskTimeGroup.TOMORROW).forEach { group ->
        buckets[group]?.takeIf { it.isNotEmpty() }?.let { sections += TaskTimeSection(group, null, it) }
    }
    laterByDate.keys.sorted().forEach { date ->
        sections += TaskTimeSection(TaskTimeGroup.LATER, date, laterByDate.getValue(date))
    }
    listOf(TaskTimeGroup.DONE, TaskTimeGroup.UNDATED).forEach { group ->
        buckets[group]?.takeIf { it.isNotEmpty() }?.let { sections += TaskTimeSection(group, null, it) }
    }
    return sections
}

private fun taskTimeSectionLabel(section: TaskTimeSection): String = when (section.group) {
    TaskTimeGroup.LATER -> "لاحقًا · " + (section.date?.let { ArabicDate.format(it) }.orEmpty())
    else -> section.group.label
}

private fun taskTimeSectionColor(group: TaskTimeGroup): Color = when (group) {
    TaskTimeGroup.OVERDUE -> FocusColors.onTint(FocusColors.Rose)
    TaskTimeGroup.TODAY -> FocusColors.onTint(FocusColors.Blue)
    TaskTimeGroup.TOMORROW -> FocusColors.onTint(FocusColors.Teal)
    TaskTimeGroup.DONE -> FocusColors.onTint(FocusColors.Green)
    TaskTimeGroup.LATER -> FocusColors.TextPrimary
    TaskTimeGroup.UNDATED -> FocusColors.TextMuted
}


@Composable

private fun TasksScreen(

    onOpenSetup: (Long) -> Unit,

    onEditBlocked: (Long) -> Unit,

    dashboard: Boolean,
    onOpenAllTasks: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenPrayerSettings: () -> Unit = {},
    preferences: FocusPreferences,
    addRequest: Int = 0,
    onOpenDrawer: () -> Unit = {},
    onOpenAssistant: () -> Unit = {},
    assistantName: String = "منظم",
    onToggleTheme: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenGoals: () -> Unit = {},
    // يظهر زر رجوع في الترويسة بدل زر القايمة عند فتح «مهامي» من إشعار (أو من
    // أي مسار دفع الجذر الحالي إلى المكدس): بدونه لا مخرج مرئي من الشاشة، لأنها
    // جذر في الشريط السفلي بلا زر رجوع في ترويستها أصلًا.
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    // الرفيق ثلاثي الأبعاد (0.34.0): null تعني بلا رفيق في هذه الشاشة.
    companionController: CompanionController? = null,
    // مالك محرك الرفيق ونموذجه على مستوى الجذر (0.35.0) — null مع null المتحكم.
    companion3DHost: Companion3DHost? = null,
    // يُستدعى عند انتقال مهمة إلى «منجزة» فقط، لا عند إلغاء الإنجاز.
    onTaskCompleted: () -> Unit = {}

) {

    val context = LocalContext.current
    val today = rememberCurrentDate()

    val version by SessionEngine.tasksVersion.collectAsState()

    val viewModel: TaskListViewModel = viewModel()

    val ui by viewModel.state.collectAsState()
    val subjectViewModel: SubjectListViewModel = viewModel()
    val subjectState by subjectViewModel.state.collectAsState()
    val subjectNames = remember(subjectState.subjects) { subjectState.subjects.associate { it.id to it.name } }

    val typeViewModel: TaskTypesViewModel = viewModel()
    val typeState by typeViewModel.state.collectAsState()
    val typeNames = remember(typeState.types) {
        typeState.types.filter { !it.archived }.associate { it.id to it.name }
    }
    var typeFilter by rememberSaveable { mutableStateOf<String?>(null) }

    val tasks = ui.tasks

    val totalMinutes = ui.totalMinutes

    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(addRequest) { if (addRequest > 0) showAddDialog = true }

    var showEditDialog by remember { mutableStateOf(false) }

    var deleteTarget by remember { mutableStateOf<StudyTask?>(null) }
    var subtasksTarget by remember { mutableStateOf<StudyTask?>(null) }

    var editTarget by remember { mutableStateOf<StudyTask?>(null) }
    var taskQuery by rememberSaveable { mutableStateOf("") }
    var taskFilter by rememberSaveable { mutableStateOf(TaskListFilter.ALL) }
    var taskSort by rememberSaveable { mutableStateOf(TaskListSort.SMART) }
    var prayerTick by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { prayerTick = PrayerWindows.nextMoment(context)?.atMillis }

    val examBySubject = remember(subjectState.subjects) {
        subjectState.subjects.associate { it.id to it.nextExamAt }
    }

    val nextStep = remember(tasks, subjectNames, examBySubject, prayerTick) {
        NextStepPlanner.pick(
            tasks = tasks,
            now = System.currentTimeMillis(),
            subjectNames = subjectNames,
            examBySubject = examBySubject,
            prayerAtMillis = prayerTick,
            zone = AppTimeZone.zone(context)
        )
    }

    val visibleTasks = remember(tasks, taskQuery, taskFilter, taskSort, typeFilter, typeNames, today) {
        val matchingTasks = tasks.filter { task ->
            val done = TaskState.completed(task)
            val matchesFilter = when (taskFilter) {
                TaskListFilter.ALL -> true
                TaskListFilter.TODAY -> task.dueAt.isOnLocalDate(today) && !done
                TaskListFilter.UPCOMING -> task.dueAt.isAfterLocalDate(today) && !done
                TaskListFilter.OVERDUE -> TaskState.overdueDays(task, today) > 0
                TaskListFilter.OPEN -> TaskState.active(task)
                TaskListFilter.COMPLETED -> done
                TaskListFilter.URGENT -> TaskState.active(task) && task.priority == TaskPriority.URGENT
            }
            val matchesType = typeFilter == null || task.typeId == typeFilter
            val query = taskQuery.trim()
            val matchesQuery = query.isEmpty() || task.title.contains(query, ignoreCase = true) ||
                task.description.contains(query, ignoreCase = true) ||
                task.notes.contains(query, ignoreCase = true) ||
                task.category.label.contains(query, ignoreCase = true) ||
                task.typeId?.let { typeNames[it]?.contains(query, ignoreCase = true) } == true ||
                task.subjectId?.let { subjectNames[it]?.contains(query, ignoreCase = true) } == true
            matchesFilter && matchesType && matchesQuery
        }
        when (taskSort) {
            TaskListSort.DEADLINE -> matchingTasks.sortedWith(
                compareBy<StudyTask> { it.dueAt ?: Long.MAX_VALUE }
                    .thenByDescending { it.priority.level }
            )
            TaskListSort.PRIORITY -> matchingTasks.sortedWith(
                compareByDescending<StudyTask> { it.priority.level }
                    .thenBy { it.dueAt ?: Long.MAX_VALUE }
            )
            TaskListSort.SMART -> SmartOrder.sort(matchingTasks, System.currentTimeMillis(), examBySubject, AppTimeZone.zone(context))
            TaskListSort.NEWEST -> matchingTasks.sortedByDescending { it.createdAt }
            TaskListSort.TITLE -> matchingTasks.sortedBy { it.title.lowercase(Locale.getDefault()) }
        }
    }

    // تجميع زمني للناتج المفلتر والمُرتَّب فقط — انظر groupTasksByTime.
    val groupedTasks = remember(visibleTasks, today) {
        groupTasksByTime(visibleTasks, today, AppTimeZone.zone(context))
    }

    var accessibilityOn by remember {

        mutableStateOf(AppBlockAccessibilityService.isEnabled(context))

    }

    LaunchedEffect(version) {

        viewModel.refresh()

        if (preferences.smartRemindersEnabled) ReminderEngine.refresh(context)

    }

    RefreshOnResume {

        accessibilityOn = AppBlockAccessibilityService.isEnabled(context)

    }

    Column(

        modifier = Modifier

            .fillMaxSize()

            .padding(horizontal = 18.dp)

            .padding(top = 22.dp, bottom = 10.dp)

    ) {

        Row(verticalAlignment = Alignment.CenterVertically) {

            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "رجوع",
                        tint = FocusColors.TextSecondary
                    )
                }
            } else {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "القايمة",
                        tint = FocusColors.TextSecondary
                    )
                }
            }

            FocusLogo(size = 46.dp)

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {

                Text(

                    text = if (!dashboard) "مهامي" else if (preferences.studentName.isBlank()) "مُنظِّم" else "أهلًا، ${preferences.studentName}",

                    style = MaterialTheme.typography.titleMedium,

                    fontWeight = FontWeight.Bold,
                    color = FocusColors.TextPrimary

                )

                Text(

                    text = ArabicDate.withSuffix(
                        today,
                        if (dashboard) "تركيز اليوم: خطوتك التالية وخطة اليوم فقط" else "القائمة الكاملة — ابحث وصفِّ ورتّب"
                    ),

                    style = MaterialTheme.typography.bodySmall,

                    color = FocusColors.TextSecondary

                )

            }

            TextButton(
                onClick = onToggleTheme,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
            ) {
                Text(
                    // رمز الوضع الناتج عن الضغطة: ☾ تعني ستصبح داكنًا،
                    // ☀ تعني ستصبح فاتحًا. الدورة وضعان فقط (فاتح ↔ داكن).
                    if (preferences.themeMode == ThemeMode.DARK) "☀" else "☾",
                    style = MaterialTheme.typography.titleMedium,
                    color = FocusColors.TextSecondary
                )
            }

        }

        if (typeNames.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth()
                    .background(FocusColors.Surface)
                    .padding(top = 10.dp, bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Spacer(Modifier.width(4.dp))
                    TaskCategoryChip(selected = typeFilter == null, onSelect = { typeFilter = null }) {
                        Text("كل الأنواع", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    typeNames.forEach { (id, name) ->
                        TaskCategoryChip(
                            selected = typeFilter == id,
                            onSelect = { typeFilter = if (typeFilter == id) null else id }
                        ) {
                            Text(name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                HorizontalDivider(color = FocusColors.Outline.copy(alpha = 0.35f))
            }
        }

        if (dashboard) {
            // تسلسل بصري واحد يجيب عن «ماذا أفعل الآن؟»: بطل واحد (التقدّم + الخطوة
            // التالية) أولًا، ثم خطة اليوم. باقي بطاقات اليوم مطوية في «تفاصيل يومك»
            // أسفل الصفحة: موجودة وقابلة للوصول، لكنها لا تنافس البطل.
            // Bottom padding keeps the day cards clear of the floating + button.
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = LayoutSpec.CONTENT_BOTTOM_DP.dp)) {
            Spacer(Modifier.height(12.dp))
            val todayTasksForWelcome = tasks.filter { it.dueAt.isOnLocalDate(java.time.LocalDate.now()) }
            WelcomeGreeting(
                name = preferences.studentName,
                openToday = todayTasksForWelcome.count { !it.isDone && it.status != TaskStatus.DONE },
                doneToday = todayTasksForWelcome.count { it.isDone || it.status == TaskStatus.DONE }
            )
            Spacer(Modifier.height(12.dp))
            AssistantHomeCard(assistantName = assistantName, onOpenAssistant = onOpenAssistant, preferences = preferences)
            Spacer(Modifier.height(12.dp))
            // الرفيق ثلاثي الأبعاد (0.34.0) في مساحته المخصصة (§11.1):
            // بطاقة في عمود التمرير نفسه، لا overlay ولا تغطية لأي عنصر.
            // عند التعطيل لا تُركَّب أصلًا فيستعيد التخطيط مساحتها.
            if (companionController != null && companion3DHost != null) {
                CompanionHomeSlot(
                    controller = companionController,
                    host = companion3DHost,
                    assistantName = assistantName,
                    onOpenAssistant = onOpenAssistant,
                    enabled = preferences.companion3dEnabled,
                    animationsEnabled = preferences.companion3dAnimationsEnabled && !preferences.reduceMotion
                )
                Spacer(Modifier.height(12.dp))
            }
            HeroCard(
                totalMinutes = totalMinutes,
                tasks = tasks,
                preferences = preferences,
                nextStep = nextStep,
                onStartNextStep = { step -> onOpenSetup(step.task.id) },
                onSnoozeNextStep = { step ->
                    val base = maxOf(step.task.dueAt ?: System.currentTimeMillis(), System.currentTimeMillis())
                    viewModel.reschedule(step.task.id, base + 60 * 60 * 1000L)
                },
                onAddTask = { showAddDialog = true }
            )
            Spacer(Modifier.height(14.dp))
            TodayPlanSection(
                tasks = tasks,
                onToggleDone = { task ->
                    // الاحتفال بعد تأكيد نجاح الحفظ لا قبله (M5).
                    val completing = !task.isDone && task.status != TaskStatus.DONE
                    viewModel.setStatus(task.id, if (task.isDone) TaskStatus.TODO else TaskStatus.DONE) { ok ->
                        if (ok && completing) onTaskCompleted()
                    }
                },
                onStartTask = { taskId -> onOpenSetup(taskId) },
                onScheduleToday = { task ->
                    val endOfToday = java.time.LocalDate.now()
                        .atTime(23, 59)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    viewModel.reschedule(task.id, endOfToday)
                },
                onAddTask = { showAddDialog = true }
            )
            Spacer(Modifier.height(12.dp))
            // الشخصية (المرحلة 5): أثر عملي في الرئيسية حسب «التخصيص».
            if (preferences.profile == UserProfile.STUDENT) {
                LectureScheduleCard()
                Spacer(Modifier.height(12.dp))
                SubjectProgressCard(subjects = subjectState.subjects, tasks = tasks)
                Spacer(Modifier.height(12.dp))
            }
            if (preferences.profile == UserProfile.GRADUATE) {
                GraduateSkillsCard(onOpenSkills = onOpenSkills, onOpenGoals = onOpenGoals)
                Spacer(Modifier.height(12.dp))
            }
            DayExtrasSection(
                preferences = preferences,
                tasks = tasks,
                onOpenPrayerSettings = onOpenPrayerSettings,
                onOpenAssistant = onOpenAssistant
            )
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = FocusColors.Surface), shape = RoundedCornerShape(FocusSpacing.radiusCard)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("قائمة مهامك", style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("${tasks.count { !it.isDone && it.status != TaskStatus.CANCELLED }} مهمة قيد الإنجاز", style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary)
                    }
                    TextButton(onClick = onOpenAllTasks) { Text("إدارة المهام") }
                }
            }
            if (!accessibilityOn) {
                Spacer(Modifier.height(10.dp))
                AccessibilityWarningCard(onEnable = { openAccessibilitySettings(context) })
            }
            Spacer(Modifier.height(12.dp))
            // الموظف (المرحلة 5): بانر الفريق مخفي في وضع العمل المختصر.
            if (preferences.profile != UserProfile.PROFESSIONAL) {
                TeamMessageBanner(onOpenAll = onOpenMessages)
            }
            Spacer(Modifier.height(12.dp))
            }
        } else {
        Spacer(Modifier.height(16.dp))
        if (!accessibilityOn) {
            AccessibilityWarningCard(onEnable = { openAccessibilitySettings(context) })
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {

            Text(

                text = "مهامي",

                style = MaterialTheme.typography.titleSmall,

                fontWeight = FontWeight.SemiBold,
                color = FocusColors.TextPrimary

            )

            Spacer(Modifier.weight(1f))

            Text(

                text = "${tasks.count { !it.isDone && it.status != TaskStatus.CANCELLED }} قيد الإنجاز",

                style = MaterialTheme.typography.labelMedium,

                color = FocusColors.TextSecondary

            )

        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "القائمة الكاملة بكل مهامك — ابحث، صفِّ، ورتّب كما يناسبك.",
            style = MaterialTheme.typography.bodySmall,
            color = FocusColors.TextSecondary
        )
        Spacer(Modifier.height(10.dp))
        // الشخصية (المرحلة 5): الطالب يرى تقدّم مواده فوق قائمته.
        if (preferences.profile == UserProfile.STUDENT) {
            SubjectProgressCard(subjects = subjectState.subjects, tasks = tasks)
            Spacer(Modifier.height(10.dp))
        }
        TaskListControls(
            query = taskQuery,
            onQueryChange = { taskQuery = it },
            filter = taskFilter,
            onFilterChange = { taskFilter = it },
            sort = taskSort,
            onSortChange = { taskSort = it }
        )

        Spacer(Modifier.height(8.dp))

        LazyColumn(

            modifier = Modifier.fillMaxWidth().weight(1f),

            verticalArrangement = Arrangement.spacedBy(10.dp),
            // Leaves room for the floating + button so the last card is never hidden under it.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = LayoutSpec.CONTENT_BOTTOM_DP.dp)

        ) {

            groupedTasks.forEach { section ->
                item(key = "header-${section.group.name}-${section.date}") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = taskTimeSectionLabel(section),
                            style = MaterialTheme.typography.labelMedium,
                            color = taskTimeSectionColor(section.group),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(FocusColors.SurfaceHigh)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "${section.tasks.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = FocusColors.TextSecondary
                            )
                        }
                    }
                }
                items(section.tasks, key = { task -> task.id }) { task ->
                    UnifiedTaskCard(
                        task = task,
                        today = today,
                        density = TaskCardDensity.FULL,
                        typeLabel = task.typeId?.let { typeNames[it] }.orEmpty(),
                        subjectName = task.subjectId?.let { subjectNames[it] },
                        onStart = { onOpenSetup(task.id) },
                        onEditBlocked = { onEditBlocked(task.id) },
                        onDelete = { deleteTarget = task },
                        onEdit = { editTarget = task; showEditDialog = true },
                        onDuplicate = { viewModel.duplicate(task.id) },
                        onReschedule = { target -> viewModel.reschedule(task.id, target) },
                        onSubtasks = { subtasksTarget = task },
                        onToggleDone = {
                            // الاحتفال بعد تأكيد نجاح الحفظ لا قبله (M5).
                            val completing = !task.isDone && task.status != TaskStatus.DONE
                            viewModel.setStatus(
                                task.id,
                                if (task.isDone) TaskStatus.TODO else TaskStatus.DONE
                            ) { ok ->
                                if (ok && completing) onTaskCompleted()
                            }
                        }
                    )
                }
            }

            if (visibleTasks.isEmpty()) {

                item {
                    if (tasks.isEmpty()) EmptyTasks(onAddTask = { showAddDialog = true })
                    else Text(
                        text = if (taskQuery.isBlank()) "لا توجد مهام ضمن هذا التصفية." else "لا توجد مهام تطابق بحثك.",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        color = FocusColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

            }

            item { Spacer(Modifier.height(8.dp)) }

        }
        }

    }

    if (showAddDialog) {

        AddTaskDialog(

            onDismiss = { showAddDialog = false },

            subjects = subjectState.subjects,
            types = typeState.types,
            onAdd = { draft ->

                viewModel.add(draft.title, draft.minutes, draft.category, draft.priority, parseDueDateMillis(draft.dueAt), draft.subjectId, draft.notes.orEmpty(), draft.recurrence, draft.typeId) { showAddDialog = false }



            },

        )

    }

    subtasksTarget?.let { target ->
        SubtasksDialog(task = target, viewModel = viewModel, onDismiss = { subtasksTarget = null })
    }

    val target = deleteTarget

    if (target != null) {

        AlertDialog(

            onDismissRequest = { deleteTarget = null },

            shape = FocusShapes.large,

            containerColor = FocusColors.SurfaceHigh,

            title = { Text("حذف المهمة؟") },

            text = {

                Text(

                    "«${target.title}» ستُحذف مع ${target.doneMinutes} دقيقة تركيز مسجّلة. لا يمكن التراجع."

                )

            },

            confirmButton = {

                TextButton(

                    onClick = {

                        deleteTarget = null

                        viewModel.delete(target.id)

                    }

                ) { Text("حذف", color = FocusColors.onTint(FocusColors.Rose)) }

            },

            dismissButton = {

                TextButton(onClick = { deleteTarget = null }) { Text("إلغاء") }

            }

        )

    }

    // Edit task floating action

    val editingTask = editTarget
    if (showEditDialog && editingTask != null) {

        EditTaskDialog(

            onDismiss = { showEditDialog = false },

            subjects = subjectState.subjects,
            onEdit = { title, minutes, category, priority, dueAtString, recurrence, notes, subjectId ->

                viewModel.update(StudyTask(

                    id = editingTask.id,

                    title = title,

                    plannedMinutes = minutes,

                    doneMinutes = editingTask.doneMinutes,

                    isDone = editingTask.isDone,

                    blockedPackages = editingTask.blockedPackages,

                    createdAt = editingTask.createdAt,

                    description = notes.orEmpty(),

                    category = category,

                    priority = priority,

                    status = editingTask.status,

                    startAt = editingTask.startAt,

                    dueAt = parseDueDateMillis(dueAtString),

                    notes = notes.orEmpty(),

                    recurrence = recurrence,

                    sortOrder = editingTask.sortOrder,
                    subjectId = subjectId,
                    completedAt = editingTask.completedAt,
                    originalDueAt = editingTask.originalDueAt

                ))

                showEditDialog = false

            },

            targetTask = editingTask

        )

    }

}

@Composable

private fun MoreSectionsScreen(onOpen: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = LayoutSpec.CONTENT_BOTTOM_DP.dp)
    ) {
        Text("المزيد", style = MaterialTheme.typography.headlineSmall, color = FocusColors.TextPrimary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("الإعدادات والأدوات الثانوية فقط — باقي الأقسام في القايمة الجانبية.", style = MaterialTheme.typography.bodyMedium, color = FocusColors.TextSecondary)
        // The rows come from the same catalog as the dock sheet, narrowed to the settings
        // shelves; the daily and growth sections now live in the side drawer instead.
        SectionsDirectory(onlyShelves = NavigationCatalog.moreShelves, onOpen = onOpen)
    }
}

@Composable

/**
 * بطل اليوم الوحيد: حلقة تقدّم مهام اليوم + الخطوة التالية داخل البطاقة نفسها،
 * فتقود العين إلى «ماذا أفعل الآن؟» قبل أي شيء آخر. عند غياب خطوة تالية يعرض
 * دعوة لإضافة أول مهمة بدل بطاقة منفصلة.
 */
private fun HeroCard(
    totalMinutes: Int,
    tasks: List<StudyTask>,
    preferences: FocusPreferences,
    nextStep: NextStep?,
    onStartNextStep: (NextStep) -> Unit,
    onSnoozeNextStep: (NextStep) -> Unit,
    onAddTask: () -> Unit
) {

    val today = rememberCurrentDate()
    val todayTasks = tasks.filter { it.dueAt.isOnLocalDate(today) }
    val done = todayTasks.count { it.isDone || it.status == TaskStatus.DONE }
    val percent = if (todayTasks.isEmpty()) 0 else done * 100 / todayTasks.size

    Box(

        modifier = Modifier

            .fillMaxWidth()

            .clip(RoundedCornerShape(28.dp))

            .background(FocusColors.hero)

            .padding(20.dp)

    ) {

        Column {

            Row(verticalAlignment = Alignment.CenterVertically) {

                Column(modifier = Modifier.weight(1f)) {

                    Text(

                        text = "مهام اليوم",

                        style = MaterialTheme.typography.labelLarge,

                        color = Color.White.copy(alpha = 0.95f)

                    )

                    Spacer(Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.Bottom) {

                        Text(

                            // LRM marks keep "1 / 3" reading left-to-right inside the RTL layout.
                            text = "‎$done / ${todayTasks.size}‎",

                            fontSize = 40.sp,

                            fontWeight = FontWeight.Bold,

                            color = Color.White

                        )

                        Text(

                            text = " مكتملة",

                            style = MaterialTheme.typography.bodyMedium,

                            color = Color.White.copy(alpha = 0.95f),

                            modifier = Modifier.padding(bottom = 8.dp)

                        )

                    }

                    Spacer(Modifier.height(6.dp))

                    Text(

                        text = if (preferences.dailyGoalMinutes == 0)
                            "بلا هدف يومي · التركيز الكلي: $totalMinutes دقيقة"
                        else
                            "هدف اليوم: ${preferences.dailyGoalMinutes} دقيقة · التركيز الكلي: $totalMinutes دقيقة",

                        style = MaterialTheme.typography.bodySmall,

                        color = Color.White.copy(alpha = 0.95f)

                    )

                }

                RingBadge(progress = percent / 100f, label = "$percent%")

            }

            HorizontalDivider(
                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
                color = Color.White.copy(alpha = 0.25f)
            )

            val step = nextStep
            if (step != null) {
                val task = step.task
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("خطوتك التالية", color = Color.White.copy(alpha = 0.95f), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            task.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "لماذا الآن؟ ${step.reason}",
                            color = Color.White.copy(alpha = 0.95f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${step.suggestedMinutes} دقيقة مقترحة · ${task.remainingMinutes} متبقية · ${task.priority.label}",
                            color = Color.White.copy(alpha = 0.95f),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onSnoozeNextStep(step) },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                        ) { Text("أجّل") }
                        TextButton(
                            onClick = { onStartNextStep(step) },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                        ) { Text("ابدأ", fontWeight = FontWeight.SemiBold) }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("جاهز لخطوتك الأولى؟", style = MaterialTheme.typography.titleSmall, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("أضف مهمة وسنساعدك على تنظيم وقتها.", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.95f))
                    }
                    TextButton(
                        onClick = onAddTask,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                    ) { Text("أضف مهمة", fontWeight = FontWeight.SemiBold) }
                }
            }
        }

    }

}

/**
 * «تفاصيل يومك»: النية والصلاة والرفيق في صفوف مدمجة قابلة للطي تحت البطل وخطة اليوم.
 * كل صف يعرض ملخصًا من سطر واحد حتى وهو مطوي، فيبقى المحتوى موجودًا وقابلًا للوصول
 * دون أن ينافس بطل اليوم. البطاقات الكاملة نفسها تظهر عند فتح الصف.
 */
@Composable
private fun DayExtrasSection(
    preferences: FocusPreferences,
    tasks: List<StudyTask>,
    onOpenPrayerSettings: () -> Unit,
    onOpenAssistant: () -> Unit = {}
) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val todayTasks = tasks.filter { it.dueAt.isOnLocalDate(today) }
    val openToday = todayTasks.count { !it.isDone && it.status != TaskStatus.DONE }
    val doneToday = todayTasks.count { it.isDone || it.status == TaskStatus.DONE }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "تفاصيل يومك",
            style = MaterialTheme.typography.labelMedium,
            color = FocusColors.TextMuted,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        DayExtraRow(
            label = "نية اليوم",
            summary = DailyIntentions.of(context, today).ifBlank { "لم تكتب نيتك بعد — أضف سطرًا واحدًا" },
            expanded = expanded == "intention",
            onToggle = { expanded = if (expanded == "intention") null else "intention" }
        ) {
            DailyIntentionCard(openToday = openToday, doneToday = doneToday)
        }
        // الموظف (المرحلة 5): وضع عمل مختصر بلا صلاة وبلا رفيق في شاشة اليوم.
        // النية تبقى، والإخفاء عرض فقط — الإعدادات والتنبيهات كما هي.
        if (preferences.profile != UserProfile.PROFESSIONAL) {
            DayExtraRow(
                label = "الصلاة القادمة",
                summary = prayerSummary(),
                expanded = expanded == "prayer",
                onToggle = { expanded = if (expanded == "prayer") null else "prayer" }
            ) {
                PrayerCountdownCard(onOpenPrayerSettings = onOpenPrayerSettings)
            }
            DayExtraRow(
                label = "رفيقك",
                summary = companionSummary(preferences, tasks),
                expanded = expanded == "companion",
                onToggle = { expanded = if (expanded == "companion") null else "companion" }
            ) {
                StudyCompanionCard(preferences, tasks, onOpenAssistant = onOpenAssistant)
            }
        }
    }
}

@Composable
private fun DayExtraRow(
    label: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(FocusSpacing.radiusCard))
                .background(FocusColors.Surface)
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = FocusColors.onTint(FocusColors.Blue))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "طي" else "عرض",
                tint = FocusColors.TextSecondary
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) { content() }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** ملخص الصلاة القادمة لصف «تفاصيل يومك» — يُحدَّث كل 30 ثانية مثل البطاقة. */
@Composable
private fun prayerSummary(): String {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            tick += 1
        }
    }
    val next = remember(tick) { runCatching { PrayerCalculator.next(context) }.getOrNull() }
    if (next == null) return "حدّد موقعك لمعرفة مواقيت الصلاة"
    val minutes = ((next.atMillis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
    return "${next.name} · ${PrayerCountdownFormat.label(minutes)}"
}

/** ملخص رسالة الرفيق لصف «تفاصيل يومك» — نفس منطق البطاقة، بلا ازدواج. */
@Composable
private fun companionSummary(preferences: FocusPreferences, tasks: List<StudyTask>): String {
    val session by SessionEngine.active.collectAsState()
    val today = LocalDate.now()
    val zone = AppTimeZone.zone(LocalContext.current)
    val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val tomorrowStart = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val completedToday = tasks.count { it.completedAt?.let { at -> at in todayStart until tomorrowStart } == true }
    val pending = tasks.filter { !it.isDone && it.status != TaskStatus.DONE && it.status != TaskStatus.CANCELLED }
    val urgent = pending.firstOrNull { it.priority == TaskPriority.URGENT }
    return companionMessage(
        sessionActive = session != null,
        completedToday = completedToday,
        urgentTitle = urgent?.title,
        hasPending = pending.isNotEmpty()
    )
}

@Composable

private fun RingBadge(progress: Float, label: String) {

    val animated by animateFloatAsState(

        targetValue = progress.coerceIn(0f, 1f),

        animationSpec = tween(900),

        label = "badge"

    )

    Box(modifier = Modifier.size(78.dp), contentAlignment = Alignment.Center) {

        Canvas(Modifier.fillMaxSize()) {

            val stroke = 8.dp.toPx()

            val arcSize = Size(size.width - stroke, size.height - stroke)

            val topLeft = Offset(stroke / 2, stroke / 2)

            drawArc(

                color = Color.White.copy(alpha = 0.32f),

                startAngle = -90f,

                sweepAngle = 360f,

                useCenter = false,

                topLeft = topLeft,

                size = arcSize,

                style = Stroke(stroke, cap = StrokeCap.Round)

            )

            drawArc(

                color = Color.White,

                startAngle = -90f,

                sweepAngle = 360f * animated,

                useCenter = false,

                topLeft = topLeft,

                size = arcSize,

                style = Stroke(stroke, cap = StrokeCap.Round)

            )

        }

        Text(

            text = label,

            style = MaterialTheme.typography.labelLarge,

            fontWeight = FontWeight.Bold,

            color = Color.White

        )

    }

}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun SubtasksDialog(
    task: StudyTask,
    viewModel: TaskListViewModel,
    onDismiss: () -> Unit
) {
    var subtasks by remember(task.id) { mutableStateOf(emptyList<Subtask>()) }
    var title by rememberSaveable(task.id) { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(task.id, refreshKey) { subtasks = viewModel.subtasks(task.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = FocusShapes.large,
        containerColor = FocusColors.SurfaceHigh,
        title = { Text("خطوات: ${task.title}") },
        text = {
            Column {
                Text("${subtasks.count { it.isDone }} من ${subtasks.size} مكتملة", color = FocusColors.TextSecondary)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().height(180.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(subtasks, key = { it.id }) { subtask ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = subtask.isDone,
                                onCheckedChange = { done ->
                                    scope.launch { viewModel.updateSubtask(subtask.id, done); refreshKey++ }
                                }
                            )
                            Text(subtask.title, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch { viewModel.removeSubtask(subtask.id); refreshKey++ }
                            }) { Text("حذف", color = FocusColors.onTint(FocusColors.Rose)) }
                        }
                    }
                    if (subtasks.isEmpty()) item { Text("قسّم المهمة إلى خطوات صغيرة تساعدك على البدء.", color = FocusColors.TextSecondary, modifier = Modifier.padding(vertical = 10.dp)) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(80) },
                    label = { Text("خطوة جديدة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                scope.launch { viewModel.createSubtask(task.id, title); title = ""; refreshKey++ }
            }) { Text("إضافة خطوة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

@Composable

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun EditTaskDialog(

    onDismiss: () -> Unit,

    onEdit: (String, Int, TaskCategory, TaskPriority, String?, Recurrence, String?, Long?) -> Unit,

    targetTask: StudyTask?,
    subjects: List<StudySubject>

) {

    var title by rememberSaveable { mutableStateOf(targetTask?.title.orEmpty()) }

    var minutesInput by rememberSaveable { mutableStateOf((targetTask?.plannedMinutes ?: 30).toString()) }
    var subjectId by rememberSaveable { mutableStateOf(targetTask?.subjectId) }

    var category by rememberSaveable { mutableStateOf(targetTask?.category ?: TaskCategory.STUDY) }

    var priority by rememberSaveable { mutableStateOf(targetTask?.priority ?: TaskPriority.MEDIUM) }

    var dueAtString by rememberSaveable { mutableStateOf(formatDueDate(targetTask?.dueAt)) }

    var notes by rememberSaveable { mutableStateOf(targetTask?.notes ?: "") }

    var recurrence by rememberSaveable { mutableStateOf(targetTask?.recurrence ?: Recurrence.NONE) }

    AlertDialog(

        onDismissRequest = onDismiss,

        shape = FocusShapes.large,

        containerColor = FocusColors.SurfaceHigh,

        title = { Text("تعديل المهمة") },

        text = {

            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {

                OutlinedTextField(

                    value = title,

                    onValueChange = { title = it },

                    singleLine = true,

                    label = { Text("اسم المهمة") },

                    shape = RoundedCornerShape(16.dp),

                    modifier = Modifier.fillMaxWidth()

                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = minutesInput,
                    onValueChange = { value -> if (value.length <= 3 && value.all(Char::isDigit)) minutesInput = value },
                    singleLine = true,
                    label = { Text("المدة المتوقعة بالدقائق (1–720)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = minutesInput.isNotEmpty() && minutesInput.toIntOrNull() !in TaskDraft.MIN_MINUTES..TaskDraft.MAX_MINUTES,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                SubjectChooser(subjects = subjects, selectedId = subjectId, onSelect = { subjectId = it })

                Text("التصنيف", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    TaskComposer.CATEGORIES.forEach { option ->
                        TaskCategoryChip(
                            selected = category == option,
                            onSelect = { category = option }
                        ) { Text(option.label) }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text("أولوية", style = MaterialTheme.typography.labelLarge)

                Spacer(Modifier.height(8.dp))

                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {

                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                        TaskPriorityChip(

                            selected = priority == TaskPriority.LOW,

                            onSelect = { priority = TaskPriority.LOW }

                        ) { Text("منخفضة") }

                        TaskPriorityChip(

                            selected = priority == TaskPriority.MEDIUM,

                            onSelect = { priority = TaskPriority.MEDIUM }

                        ) { Text("متوسطة") }

                        TaskPriorityChip(

                            selected = priority == TaskPriority.HIGH,

                            onSelect = { priority = TaskPriority.HIGH }

                        ) { Text("عالية") }

                        TaskPriorityChip(

                            selected = priority == TaskPriority.URGENT,

                            onSelect = { priority = TaskPriority.URGENT }

                        ) { Text("عاجلة") }

                    }

                }

                Spacer(Modifier.height(16.dp))

                Text("تاريخ الاستحقاق (اختياري)", style = MaterialTheme.typography.labelLarge)

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(

                    value = dueAtString,

                    onValueChange = { dueAtString = it },

                    singleLine = true,

                    label = { Text("تاريخ (yyyy-mm-dd)") },

                    shape = RoundedCornerShape(16.dp),

                    modifier = Modifier.fillMaxWidth()

                )

                Spacer(Modifier.height(16.dp))

                Text("ملاحظات (اختياري)", style = MaterialTheme.typography.labelLarge)

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(

                    value = notes,

                    onValueChange = { notes = it },

                    singleLine = true,

                    label = { Text("ملاحظات") },

                    shape = RoundedCornerShape(16.dp),

                    modifier = Modifier.fillMaxWidth()

                )

                Spacer(Modifier.height(16.dp))

                Text("التكرار", style = MaterialTheme.typography.labelLarge)

                Spacer(Modifier.height(8.dp))

                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {

                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                        RecurrenceChip(

                            selected = recurrence == Recurrence.NONE,

                            onSelect = { recurrence = Recurrence.NONE }

                        ) { Text("بدون تكرار") }

                        RecurrenceChip(

                            selected = recurrence == Recurrence.DAILY,

                            onSelect = { recurrence = Recurrence.DAILY }

                        ) { Text("يوميًا") }

                        RecurrenceChip(

                            selected = recurrence == Recurrence.WEEKLY,

                            onSelect = { recurrence = Recurrence.WEEKLY }

                        ) { Text("أسبوعيًا") }

                        RecurrenceChip(

                            selected = recurrence == Recurrence.MONTHLY,

                            onSelect = { recurrence = Recurrence.MONTHLY }

                        ) { Text("شهريًا") }

                    }

                }

            }

        },

        confirmButton = {

            TextButton(

                onClick = { if (title.isNotBlank()) onEdit(title.trim(), minutesInput.toIntOrNull() ?: 30, category, priority, dueAtString, recurrence, notes, subjectId) },

                enabled = title.isNotBlank() && minutesInput.toIntOrNull() in 1..720

            ) { Text("حفظ") }

        },

        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }

    )

}

@Composable

private fun EmptyTasks(onAddTask: () -> Unit) {

    Column(

        modifier = Modifier.fillMaxWidth().padding(top = 28.dp),

        horizontalAlignment = Alignment.CenterHorizontally

    ) {

        FocusLogo(size = 64.dp, showTile = false)

        Spacer(Modifier.height(12.dp))

        Text(

            text = "ابدأ بمهمة واحدة",

            style = MaterialTheme.typography.titleSmall,
            color = FocusColors.TextPrimary

        )

        Spacer(Modifier.height(6.dp))

        Text(

            text = "أضف مهمة، حدّد التطبيقات المشتّتة، ثم ابدأ جلسة تركيز مقفلة.",

            style = MaterialTheme.typography.bodySmall,

            color = FocusColors.TextSecondary,

            textAlign = TextAlign.Center

        )

        Spacer(Modifier.height(16.dp))

        FilledTonalButton(
            onClick = onAddTask,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = FocusColors.Blue.copy(alpha = 0.18f),
                contentColor = FocusColors.onTint(FocusColors.Blue)
            )
        ) { Text("أضف أول مهمة") }

    }

}

@Composable

private fun AccessibilityWarningCard(onEnable: () -> Unit) {

    Card(

        modifier = Modifier.fillMaxWidth(),

        shape = RoundedCornerShape(FocusSpacing.radiusCard),

        colors = CardDefaults.cardColors(containerColor = FocusColors.Amber.copy(alpha = 0.16f))

    ) {

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "فعّل حجب فتح التطبيقات",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = FocusColors.onTint(FocusColors.Amber)
                )
                Text(
                    text = "إذن الوصول يقرأ اسم التطبيق المفتوح فقط، ولا يقرأ محتواه.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary,
                    maxLines = 2
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onEnable) { Text("تفعيل") }
        }

    }

}

/**

 * Pre-session screen: the learner sets both the duration and the apps that will be locked, grants

 * the required permissions, and only then can the locked session start.

 */

@Composable

private fun SessionSetupScreen(

    taskId: Long,
    defaultMinutes: Int,

    onBack: () -> Unit,

    onRequestNotifications: () -> Unit,
    onGrantVpn: () -> Unit,

    onStart: (Long, String, Int, Set<String>) -> Unit

) {

    val context = LocalContext.current

    var title by rememberSaveable { mutableStateOf("") }

    var apps by remember { mutableStateOf(emptyList<InstalledApp>()) }

    var selected by rememberSaveable(

        stateSaver = listSaver<Set<String>, String>(

            save = { it.toList() },

            restore = { it.toSet() }

        )

    ) { mutableStateOf(emptySet<String>()) }

    var usePermanentApps by rememberSaveable { mutableStateOf(true) }
    var blockAllApps by rememberSaveable { mutableStateOf(false) }

    var query by rememberSaveable { mutableStateOf("") }

    var minutes by rememberSaveable { mutableStateOf(defaultMinutes.toFloat()) }

    var confirm by remember { mutableStateOf(false) }

    var restored by rememberSaveable { mutableStateOf(false) }

    var accessibilityOn by remember {

        mutableStateOf(AppBlockAccessibilityService.isEnabled(context))

    }

    var vpnReady by remember { mutableStateOf(isVpnConsentGranted(context)) }

    var notificationsOn by remember { mutableStateOf(hasNotificationPermission(context)) }

    var batteryOk by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    var consentChanges by remember { mutableStateOf<Map<ConsentKey, ConsentPolicy.Change>>(emptyMap()) }
    val consentScope = rememberCoroutineScope()

    LaunchedEffect(taskId) {

        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }

    }

    LaunchedEffect(taskId) {

        if (restored) return@LaunchedEffect

        val task = withContext(Dispatchers.IO) {

            StudyDb.get(context).tasks().firstOrNull { it.id == taskId }

        }

        title = task?.title.orEmpty()

        selected = task?.blockedPackages ?: emptySet()

        minutes = ((task?.remainingMinutes ?: defaultMinutes).coerceIn(5, 120)).toFloat()

        restored = true

    }

    RefreshOnResume {

        accessibilityOn = AppBlockAccessibilityService.isEnabled(context)

        vpnReady = isVpnConsentGranted(context)

        notificationsOn = hasNotificationPermission(context)

        batteryOk = isIgnoringBatteryOptimizations(context)

        consentScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { ConsentStore.record(context) }.getOrDefault(emptyMap())
            }
            consentChanges = fresh
        }

    }

    val filtered = remember(apps, query) {

        if (query.isBlank()) {

            apps

        } else {

            apps.filter {

                it.label.contains(query, ignoreCase = true) ||

                    it.packageName.contains(query, ignoreCase = true)

            }

        }

    }

    val ready = accessibilityOn && vpnReady && notificationsOn
    val permanent = FocusPreferences.load(context).permanentBlockedPackages
    val homePackage = remember(context) {
        runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        }.getOrNull()
    }
    val protectedPackages = remember(context, homePackage) {
        setOfNotNull(
            context.packageName, homePackage, "com.android.systemui", "com.android.settings",
            "com.android.phone", "com.android.dialer", "com.google.android.dialer",
            "com.android.emergency", "com.android.packageinstaller"
        )
    }
    val effectiveSelected = when {
        blockAllApps -> apps.map { it.packageName }.filterNot { it in protectedPackages }.toSet()
        usePermanentApps -> selected + permanent
        else -> selected
    }
    val canStart = ready && effectiveSelected.isNotEmpty()

    val duration = minutes.toInt()

    Column(

        modifier = Modifier

            .fillMaxSize()

            .padding(horizontal = 18.dp)

            .padding(top = 18.dp, bottom = 10.dp)

    ) {

        Row(verticalAlignment = Alignment.CenterVertically) {

            Column(modifier = Modifier.weight(1f)) {

                Text(

                    text = "إعداد الجلسة",

                    style = MaterialTheme.typography.titleLarge,

                    fontWeight = FontWeight.Bold,
                    color = FocusColors.TextPrimary

                )

                Text(

                    text = title.ifBlank { "مهمة" },

                    style = MaterialTheme.typography.bodySmall,

                    color = FocusColors.TextSecondary

                )

            }

            TextButton(onClick = onBack) { Text("رجوع", color = FocusColors.TextSecondary) }

        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(

            modifier = Modifier.fillMaxWidth().weight(1f),

            verticalArrangement = Arrangement.spacedBy(14.dp)

        ) {

            item {

                Card(

                    shape = RoundedCornerShape(FocusSpacing.radiusCard),

                    colors = CardDefaults.cardColors(containerColor = FocusColors.Surface)

                ) {

                    Column(modifier = Modifier.padding(16.dp)) {

                        Row(verticalAlignment = Alignment.CenterVertically) {

                            Text(

                                text = "المدة",

                                style = MaterialTheme.typography.titleSmall,
                                color = FocusColors.TextPrimary

                            )

                            Spacer(Modifier.weight(1f))

                            Text(

                                text = "$duration دقيقة",

                                style = MaterialTheme.typography.titleSmall,

                                color = FocusColors.onTint(FocusColors.Blue)

                            )

                        }

                        Spacer(Modifier.height(10.dp))

                        DurationChips(selected = duration, onSelect = { minutes = it.toFloat() })

                        Slider(

                            value = minutes,

                            onValueChange = { minutes = it },

                            valueRange = 5f..120f,

                            steps = 22,

                            colors = SliderDefaults.colors(

                                thumbColor = FocusColors.Blue,

                                activeTrackColor = FocusColors.Blue,

                                inactiveTrackColor = FocusColors.Outline

                            )

                        )

                        Text(

                            text = "من 5 إلى 120 دقيقة. المؤقّت يبدأ بعد التأكيد فقط.",

                            style = MaterialTheme.typography.bodySmall,

                            color = FocusColors.TextSecondary

                        )

                    }

                }

            }

            item {
                Card(shape = RoundedCornerShape(FocusSpacing.radiusCard), colors = CardDefaults.cardColors(containerColor = FocusColors.Surface)) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("نطاق الحجب", style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { usePermanentApps = !usePermanentApps }) {
                            Checkbox(checked = usePermanentApps, onCheckedChange = { usePermanentApps = it })
                            Column {
                                Text("التطبيقات الثابتة + تطبيقات المهمة", color = FocusColors.TextPrimary)
                                Text("${permanent.size} ثابتة ستُضاف تلقائيًا", style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { blockAllApps = !blockAllApps }) {
                            Checkbox(checked = blockAllApps, onCheckedChange = { blockAllApps = it })
                            Column {
                                Text("حجب كل التطبيقات القابلة للفتح", color = FocusColors.TextPrimary)
                                Text("تظل الشاشة الرئيسية والاتصال والطوارئ متاحة. التطبيق لا يطفئ الهاتف.", style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary)
                            }
                        }
                    }
                }
            }

            if (!ready) {
                item {
                    Card(
                        shape = RoundedCornerShape(FocusSpacing.radiusCard),
                        colors = CardDefaults.cardColors(containerColor = FocusColors.Amber.copy(alpha = 0.12f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("أذونات ناقصة", style = MaterialTheme.typography.titleSmall, color = FocusColors.TextPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "الجلسة لن تبدأ قبل منحها. اضغط «منح» بجانب كل إذن ناقص.",
                                style = MaterialTheme.typography.bodySmall,
                                color = FocusColors.TextSecondary
                            )
                            Spacer(Modifier.height(8.dp))
                            if (!accessibilityOn) {
                                PermissionRow(
                                    title = "إذن الوصول (Accessibility)",
                                    description = consentDescription(
                                        "يخفي التطبيق المقفل ويعيدك للرئيسية.",
                                        consentChanges[ConsentKey.ACCESSIBILITY]
                                    ),
                                    granted = false,
                                    required = true,
                                    actionLabel = consentActionLabel(consentChanges[ConsentKey.ACCESSIBILITY]),
                                    onAction = { openAccessibilitySettings(context) }
                                )
                            }
                            if (!vpnReady) {
                                PermissionRow(
                                    title = "إذن VPN",
                                    description = consentDescription(
                                        "يقطع إنترنت التطبيقات المختارة.",
                                        consentChanges[ConsentKey.VPN]
                                    ),
                                    granted = false,
                                    required = true,
                                    actionLabel = consentActionLabel(consentChanges[ConsentKey.VPN]),
                                    onAction = onGrantVpn
                                )
                            }
                            if (!notificationsOn) {
                                PermissionRow(
                                    title = "إذن الإشعارات",
                                    description = consentDescription(
                                        "يعرض الجلسة الجارية والمؤقّت.",
                                        consentChanges[ConsentKey.NOTIFICATIONS]
                                    ),
                                    granted = false,
                                    required = true,
                                    actionLabel = consentActionLabel(consentChanges[ConsentKey.NOTIFICATIONS]),
                                    onAction = onRequestNotifications
                                )
                            }
                        }
                    }
                }
            }
            item {

                Row(verticalAlignment = Alignment.CenterVertically) {

                    Text(

                        text = "التطبيقات التي ستُقفل",

                        style = MaterialTheme.typography.titleSmall,
                        color = FocusColors.TextPrimary

                    )

                    Spacer(Modifier.weight(1f))

                    Text(

                        text = "${effectiveSelected.size} سيُحجب",

                        style = MaterialTheme.typography.labelMedium,

                        color = FocusColors.onTint(FocusColors.Blue)

                    )

                }

            }

            item {

                OutlinedTextField(

                    value = query,

                    onValueChange = { query = it },

                    singleLine = true,

                    label = { Text("بحث عن تطبيق") },

                    shape = RoundedCornerShape(16.dp),

                    modifier = Modifier.fillMaxWidth()

                )

            }

            item {

                Row(verticalAlignment = Alignment.CenterVertically) {

                    TextButton(onClick = { selected = filtered.map { it.packageName }.toSet() }) {

                        Text("اختيار الكل")

                    }

                    TextButton(onClick = { selected = emptySet() }) {

                        Text("مسح", color = FocusColors.TextSecondary)

                    }

                }

            }

            if (selected.isNotEmpty()) item {
                SelectedAppsStrip(
                    selectedApps = apps.filter { it.packageName in selected },
                    onRemove = { packageName -> selected = selected - packageName }
                )
            }

            items(filtered, key = { it.packageName }) { app ->

                val checked = app.packageName in selected || (usePermanentApps && app.packageName in permanent) || blockAllApps

                Row(

                    modifier = Modifier

                        .fillMaxWidth()

                        .clip(RoundedCornerShape(16.dp))

                        .background(if (checked) FocusColors.SurfaceHigh else FocusColors.Surface)

                        .clickable {

                            if (blockAllApps || (usePermanentApps && app.packageName in permanent)) return@clickable

                            selected = if (checked) {

                                selected - app.packageName

                            } else {

                                selected + app.packageName

                            }

                        }

                        .padding(horizontal = 10.dp, vertical = 8.dp),

                    verticalAlignment = Alignment.CenterVertically

                ) {

                    Checkbox(

                        checked = checked,

                        onCheckedChange = { value ->

                            if (blockAllApps || (usePermanentApps && app.packageName in permanent)) return@Checkbox

                            selected = if (value) {

                                selected + app.packageName

                            } else {

                                selected - app.packageName

                            }

                        },

                        colors = CheckboxDefaults.colors(checkedColor = FocusColors.Blue)

                    )

                    Spacer(Modifier.width(8.dp))
                    InstalledAppIcon(app, Modifier.size(40.dp))
                    Spacer(Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {

                        Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, color = FocusColors.TextPrimary)

                        Text(

                            text = app.packageName,

                            style = MaterialTheme.typography.labelSmall,

                            color = FocusColors.TextMuted,

                            maxLines = 1

                        )

                    }

                }

            }

            if (filtered.isEmpty()) {

                item {

                    Text(

                        text = "لا تطبيقات مطابقة للبحث.",

                        style = MaterialTheme.typography.bodySmall,

                        color = FocusColors.TextSecondary

                    )

                }

            }

            item {

                Card(

                    shape = RoundedCornerShape(FocusSpacing.radiusCard),

                    colors = CardDefaults.cardColors(containerColor = FocusColors.Surface)

                ) {

                    Column(modifier = Modifier.padding(16.dp)) {

                        Text(

                            text = "الأذونات المطلوبة",

                            style = MaterialTheme.typography.titleSmall

                        )

                        Spacer(Modifier.height(4.dp))

                        Text(

                            text = "لن تبدأ الجلسة قبل منحها، لأنها الوسيلة الوحيدة لمنع فتح التطبيقات فعليًا.",

                            style = MaterialTheme.typography.bodySmall,

                            color = FocusColors.TextSecondary

                        )

                        Spacer(Modifier.height(12.dp))

                        PermissionRow(

                            title = "إذن الوصول (Accessibility)",

                            description = "يخفي التطبيق المقفل ويعيدك للرئيسية.",

                            granted = accessibilityOn,

                            required = true,

                            actionLabel = "منح",

                            onAction = { openAccessibilitySettings(context) }

                        )

                        PermissionRow(

                            title = "إذن VPN",

                            description = "يقطع إنترنت التطبيقات المختارة.",

                            granted = vpnReady,

                            required = true,

                            actionLabel = "منح",

                            onAction = {

                                val consent = VpnService.prepare(context)

                                if (consent != null) {

                                    runCatching { context.startActivity(consent) }

                                } else {

                                    vpnReady = true

                                }

                            }

                        )

                        PermissionRow(

                            title = "إذن الإشعارات",

                            description = "يعرض الجلسة الجارية والمؤقّت.",

                            granted = notificationsOn,

                            required = true,

                            actionLabel = "منح",

                            onAction = onRequestNotifications

                        )

                        PermissionRow(

                            title = "تعطيل تحسين البطارية",

                            description = "يمنع النظام من إيقاف الجلسة في الخلفية.",

                            granted = batteryOk,

                            required = false,

                            actionLabel = "فتح",

                            onAction = { openBatterySettings(context) }

                        )

                    }

                }

            }

            item {

                Row(

                    modifier = Modifier

                        .fillMaxWidth()

                        .clip(RoundedCornerShape(18.dp))

                        .background(FocusColors.Rose.copy(alpha = 0.14f))

                        .padding(14.dp),

                    verticalAlignment = Alignment.CenterVertically

                ) {

                    Text(

                        text = "بعد البدء لا يمكن إلغاء الجلسة أو تقصيرها؛ تُفتح التطبيقات تلقائيًا عند انتهاء المدة.",

                        style = MaterialTheme.typography.bodySmall,

                        color = FocusColors.onTint(FocusColors.Rose)

                    )

                }

            }

        }

        Spacer(Modifier.height(10.dp))

        Text(

            text = when {

                !ready -> "أكمل الأذونات المطلوبة أولًا."

                effectiveSelected.isEmpty() -> "اختر تطبيقًا واحدًا على الأقل."

                else -> "$duration دقيقة • ${effectiveSelected.size} تطبيقًا سيُقفل"

            },

            style = MaterialTheme.typography.bodySmall,

            color = if (canStart) FocusColors.TextSecondary

            else FocusColors.onTint(FocusColors.Amber)

        )

        Spacer(Modifier.height(6.dp))

        Button(

            onClick = { confirm = true },

            enabled = canStart,

            modifier = Modifier.fillMaxWidth().height(54.dp),

            shape = RoundedCornerShape(18.dp)

        ) { Text("ابدأ الجلسة", fontWeight = FontWeight.Bold) }

    }

    if (confirm) {

        AlertDialog(

            onDismissRequest = { confirm = false },

            shape = FocusShapes.large,

            containerColor = FocusColors.SurfaceHigh,

            title = { Text("جلسة مقفلة") },

            text = {

                Text(

                    "مدة $duration دقيقة، و${effectiveSelected.size} تطبيقًا سيُمنع فتحه وتُقطع شبكته.\n\n" +

                        "لن تستطيع إلغاء الجلسة أو فتح هذه التطبيقات قبل انتهاء الوقت."

                )

            },            confirmButton = {

                Button(

                    onClick = {

                        confirm = false

                        onStart(taskId, title, duration, effectiveSelected)

                    },

                    shape = RoundedCornerShape(14.dp)

                ) { Text("ابدأ الآن") }

            },

            dismissButton = {

                TextButton(onClick = { confirm = false }) { Text("رجوع") }

            }

        )

    }

}




@Composable

private fun PermissionRow(

    title: String,

    description: String,

    granted: Boolean,

    required: Boolean,

    actionLabel: String,

    onAction: () -> Unit

) {

    Row(

        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),

        verticalAlignment = Alignment.CenterVertically

    ) {

        Box(

            modifier = Modifier

                .size(10.dp)

                .clip(CircleShape)

                .background(if (granted) FocusColors.Green else FocusColors.Amber)

        )

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {

            Row(verticalAlignment = Alignment.CenterVertically) {

                Text(title, style = MaterialTheme.typography.bodyLarge)

                if (!required) {

                    Spacer(Modifier.width(6.dp))

                    Text(

                        text = "اختياري",

                        style = MaterialTheme.typography.labelSmall,

                        color = FocusColors.TextMuted

                    )

                }

            }

            Text(

                text = description,

                style = MaterialTheme.typography.labelMedium,

                color = FocusColors.TextSecondary

            )

        }

        if (!granted) {

            OutlinedButton(onClick = onAction, shape = RoundedCornerShape(12.dp)) {

                Text(actionLabel)

            }

        } else {

            Text(

                text = "مفعّل",

                style = MaterialTheme.typography.labelMedium,

                color = FocusColors.onTint(FocusColors.Green)

            )

        }

    }

}

@Composable

private fun DurationChips(selected: Int, onSelect: (Int) -> Unit) {

    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {

        listOf(15, 25, 45, 60, 90).forEach { option ->

            val active = option == selected

            Box(

                modifier = Modifier

                    .clip(RoundedCornerShape(12.dp))

                    .background(if (active) FocusColors.Blue else FocusColors.SurfaceHigh)

                    .clickable { onSelect(option) }

                    .padding(horizontal = 12.dp, vertical = 9.dp)

            ) {

                Text(

                    text = "$option",

                    style = MaterialTheme.typography.labelLarge,

                    color = if (active) Color.White else FocusColors.TextSecondary

                )

            }

        }

    }

}

@Composable

private fun SessionScreen(

    session: SessionEngine.Active,

    remainingSeconds: Long,

    vpnLost: Boolean,

    onResumeVpn: () -> Unit

) {

    val context = LocalContext.current

    val ownerActive = remember { DeviceOwnerController.isDeviceOwner(context) }

    val progress = (remainingSeconds.toFloat() / session.totalSeconds.toFloat()).coerceIn(0f, 1f)

    val animatedProgress by animateFloatAsState(

        targetValue = progress,

        animationSpec = tween(600),

        label = "ring"

    )

    val pulse = rememberInfiniteTransition(label = "pulse")

    val glow by pulse.animateFloat(

        initialValue = 0.35f,

        targetValue = 0.9f,

        animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Reverse),

        label = "glow"

    )

    Column(

        modifier = Modifier.fillMaxSize().padding(24.dp),

        horizontalAlignment = Alignment.CenterHorizontally,

        verticalArrangement = Arrangement.Center

    ) {

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(FocusSpacing.radiusChip))
                .background(FocusColors.Green.copy(alpha = 0.16f))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(FocusColors.Green.copy(alpha = glow)))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "جلسة مقفلة — لا يمكن إلغاؤها",
                    style = MaterialTheme.typography.labelLarge,
                    color = FocusColors.onTint(FocusColors.Green)
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(

            text = session.taskTitle,

            style = MaterialTheme.typography.titleLarge,

            fontWeight = FontWeight.Bold,

            textAlign = TextAlign.Center

        )

        Spacer(Modifier.height(24.dp))

        Box(modifier = Modifier.size(258.dp), contentAlignment = Alignment.Center) {

            Canvas(Modifier.fillMaxSize()) {

                val stroke = 16.dp.toPx()

                val arcSize = Size(size.width - stroke, size.height - stroke)

                val topLeft = Offset(stroke / 2, stroke / 2)

                drawCircle(

                    color = FocusColors.Blue.copy(alpha = 0.08f * glow),

                    radius = size.minDimension * 0.48f

                )

                drawArc(

                    color = FocusColors.SurfaceHigh,

                    startAngle = -90f,

                    sweepAngle = 360f,

                    useCenter = false,

                    topLeft = topLeft,

                    size = arcSize,

                    style = Stroke(stroke, cap = StrokeCap.Round)

                )

                drawArc(

                    brush = Brush.sweepGradient(

                        listOf(FocusColors.Blue, FocusColors.Violet, FocusColors.Teal, FocusColors.Blue)

                    ),

                    startAngle = -90f,

                    sweepAngle = 360f * animatedProgress,

                    useCenter = false,

                    topLeft = topLeft,

                    size = arcSize,

                    style = Stroke(stroke, cap = StrokeCap.Round)

                )

            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {

                Text(

                    text = formatClock(remainingSeconds),

                    fontSize = 52.sp,

                    fontWeight = FontWeight.Bold,

                    color = FocusColors.TextPrimary

                )

                Text(

                    text = "متبقي حتى تُفتح التطبيقات",

                    style = MaterialTheme.typography.labelMedium,

                    color = FocusColors.TextSecondary

                )

                Spacer(Modifier.height(4.dp))

                Text(

                    text = "مدة الجلسة ${minuteCount((session.totalSeconds / 60).toInt())}",

                    style = MaterialTheme.typography.labelSmall,

                    color = FocusColors.TextMuted

                )

            }

        }

        Spacer(Modifier.height(24.dp))

        Box(

            modifier = Modifier

                .clip(RoundedCornerShape(14.dp))

                .background(FocusColors.SurfaceHigh)

                .padding(horizontal = 14.dp, vertical = 8.dp)

        ) {

            Text(

                text = "${session.blockedPackages.size} تطبيقًا ممنوع من الفتح والإنترنت",

                style = MaterialTheme.typography.bodySmall,

                color = FocusColors.TextPrimary

            )

        }

        if (ownerActive) {

            Spacer(Modifier.height(8.dp))

            Box(

                modifier = Modifier

                    .clip(RoundedCornerShape(14.dp))

                    .background(FocusColors.Green.copy(alpha = 0.16f))

                    .padding(horizontal = 14.dp, vertical = 8.dp)

            ) {

                Text(

                    text = "التعليق النظامي مفعّل — أيقوناتها معطّلة",

                    style = MaterialTheme.typography.labelMedium,

                    color = FocusColors.onTint(FocusColors.Green)

                )

            }

        }

        if (vpnLost) {

            Spacer(Modifier.height(16.dp))

            Card(

                shape = RoundedCornerShape(FocusSpacing.radiusCard),

                colors = CardDefaults.cardColors(containerColor = FocusColors.Amber.copy(alpha = 0.16f))

            ) {

                Column(modifier = Modifier.padding(14.dp)) {

                    Text(

                        text = "حجب الشبكة توقّف",

                        style = MaterialTheme.typography.titleSmall,

                        color = FocusColors.onTint(FocusColors.Amber)

                    )

                    Spacer(Modifier.height(4.dp))

                    Text(

                        text = "منع فتح التطبيقات ما زال يعمل. أعد تشغيل حجب الشبكة ليكتمل الحجب.",

                        style = MaterialTheme.typography.bodySmall,

                        color = FocusColors.TextSecondary

                    )

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(onClick = onResumeVpn, shape = RoundedCornerShape(12.dp)) {

                        Text("إعادة تشغيل الحجب")

                    }

                }

            }

        }

    }

}

@Composable
private fun QuickCaptureHint(title: String, onApply: (ParsedCapture) -> Unit) {
    val trimmed = title.trim()
    if (trimmed.length < 4 || !trimmed.contains(' ')) return
    val today = remember(trimmed) { LocalDate.now() }
    val parsed = remember(trimmed, today) { QuickCaptureParser.parse(trimmed, today) }
    val hasDetails = parsed.date != null || parsed.hour != null ||
        parsed.durationMinutes != null || parsed.priorityLevel != null ||
        parsed.recurrence != Recurrence.NONE
    if (!hasDetails) return
    val parts = mutableListOf<String>()
    parsed.date?.let { d ->
        parts += when (d) {
            today -> "اليوم"
            today.plusDays(1) -> "غدًا"
            today.plusDays(2) -> "بعد بكرة"
            else -> d.toString()
        }
    }
    parsed.hour?.let { h -> parts += h.toString().padStart(2, '0') + ":" + parsed.minute.toString().padStart(2, '0') }
    parsed.durationMinutes?.let { parts += "$it دقيقة" }
    parsed.priorityLevel?.let { parts += if (it >= 3) "عاجل" else "مهم" }
    if (parsed.recurrence != Recurrence.NONE) parts += RecurrenceEngine.label(parsed.recurrence)
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            "فهمت: " + parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onApply(parsed) }) { Text("تطبيق") }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun AddTaskDialog(
    onDismiss: () -> Unit,
    subjects: List<StudySubject>,
    types: List<TaskType>,
    onAdd: (TaskDraft) -> Unit
) {

    var title by rememberSaveable { mutableStateOf("") }

    var minutesInput by rememberSaveable { mutableStateOf("30") }
    var customMinutes by rememberSaveable { mutableStateOf(false) }
    var subjectId by rememberSaveable { mutableStateOf<Long?>(null) }

    var category by rememberSaveable { mutableStateOf(TaskCategory.STUDY) }

    var priority by rememberSaveable { mutableStateOf(TaskPriority.MEDIUM) }

    var dueAtString by rememberSaveable { mutableStateOf("") }

    var notes by rememberSaveable { mutableStateOf("") }

    var recurrence by rememberSaveable { mutableStateOf(Recurrence.NONE) }
    var typeId by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }



    AlertDialog(



        onDismissRequest = onDismiss,

        shape = FocusShapes.large,

        containerColor = FocusColors.SurfaceHigh,

        title = { Text("مهمة جديدة") },

        text = {

            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {

                OutlinedTextField(

                    value = title,

                    onValueChange = { title = it },

                    singleLine = true,

                    label = { Text("اسم المهمة") },

                    shape = RoundedCornerShape(16.dp),

                    modifier = Modifier.fillMaxWidth()

                )

                if (!expanded && title.isNotBlank()) {
                    Text(
                        "اكتب الاسم واضغط إضافة — الخيارات الباقية اختيارية.",
                        style = MaterialTheme.typography.labelSmall,
                        color = FocusColors.TextMuted
                    )
                    Spacer(Modifier.height(6.dp))
                }

                Spacer(Modifier.height(6.dp))
                QuickCaptureHint(
                    title = title,
                    onApply = { capture ->
                        var day = capture.date
                        if (day == null && capture.hour != null) day = LocalDate.now()
                        day?.let { d ->
                            val time = capture.hour
                            dueAtString = if (time == null) d.toString() else
                                d.toString() + " " + time.toString().padStart(2, '0') + ":" + capture.minute.toString().padStart(2, '0')
                        }
                        capture.durationMinutes?.let { if (it in 1..720) minutesInput = it.toString() }
                        priority = when (capture.priorityLevel) {
                            3 -> TaskPriority.URGENT
                            2 -> TaskPriority.HIGH
                            else -> priority
                        }
                        if (capture.recurrence != Recurrence.NONE) recurrence = capture.recurrence
                        if (capture.title.isNotBlank()) title = capture.title
                        expanded = true
                    }
                )
                Spacer(Modifier.height(10.dp))
                Text("المدة", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    TaskComposer.QUICK_MINUTES.forEach { value ->
                        TaskCategoryChip(
                            selected = !customMinutes && minutesInput.toIntOrNull() == value,
                            onSelect = { minutesInput = value.toString(); customMinutes = false }
                        ) { Text("$value د") }
                    }
                    TaskCategoryChip(selected = customMinutes, onSelect = { customMinutes = true }) { Text("مخصص") }
                }
                if (customMinutes) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = minutesInput,
                        onValueChange = { value -> if (value.length <= 3 && value.all(Char::isDigit)) minutesInput = value },
                        singleLine = true,
                        label = { Text("المدة بالدقائق (1–720)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = minutesInput.isNotEmpty() && minutesInput.toIntOrNull() !in TaskDraft.MIN_MINUTES..TaskDraft.MAX_MINUTES,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("الأولوية", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    TaskPriorityChip(selected = priority == TaskPriority.LOW, onSelect = { priority = TaskPriority.LOW }) { Text("منخفضة") }
                    TaskPriorityChip(selected = priority == TaskPriority.MEDIUM, onSelect = { priority = TaskPriority.MEDIUM }) { Text("متوسطة") }
                    TaskPriorityChip(selected = priority == TaskPriority.HIGH, onSelect = { priority = TaskPriority.HIGH }) { Text("عالية") }
                    TaskPriorityChip(selected = priority == TaskPriority.URGENT, onSelect = { priority = TaskPriority.URGENT }) { Text("عاجلة") }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(if (expanded) "إخفاء الخيارات" else "المزيد من الخيارات")
                }
                if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text("التصنيف", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    TaskComposer.CATEGORIES.forEach { option ->
                        TaskCategoryChip(
                            selected = typeId == null && category == option,
                            onSelect = { category = option; typeId = null }
                        ) { Text(option.label) }
                    }
                    types.filter { !it.archived }.forEach { type ->
                        TaskCategoryChip(
                            selected = typeId == type.id,
                            onSelect = { typeId = type.id }
                        ) { Text(type.name) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SubjectChooser(subjects = subjects, selectedId = subjectId, onSelect = { subjectId = it })
                Spacer(Modifier.height(12.dp))

                Spacer(Modifier.height(16.dp))

                Text("تاريخ الاستحقاق (اختياري)", style = MaterialTheme.typography.labelLarge)

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(

                    value = dueAtString,

                    onValueChange = { dueAtString = it },

                    singleLine = true,

                    label = { Text("تاريخ (yyyy-mm-dd)") },

                    shape = RoundedCornerShape(16.dp),

                    modifier = Modifier.fillMaxWidth()

                )

                Spacer(Modifier.height(16.dp))

                Text("ملاحظات (اختياري)", style = MaterialTheme.typography.labelLarge)

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(

                    value = notes,

                    onValueChange = { notes = it },

                    singleLine = true,

                    label = { Text("ملاحظات") },

                    shape = RoundedCornerShape(16.dp),

                    modifier = Modifier.fillMaxWidth()

                )

                Spacer(Modifier.height(16.dp))

                Text("التكرار", style = MaterialTheme.typography.labelLarge)

                Spacer(Modifier.height(8.dp))

                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {

                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                        RecurrenceChip(

                            selected = recurrence == Recurrence.NONE,

                            onSelect = { recurrence = Recurrence.NONE }

                        ) { Text("بدون تكرار") }

                        RecurrenceChip(

                            selected = recurrence == Recurrence.DAILY,

                            onSelect = { recurrence = Recurrence.DAILY }

                        ) { Text("يوميًا") }

                        RecurrenceChip(

                            selected = recurrence == Recurrence.WEEKLY,

                            onSelect = { recurrence = Recurrence.WEEKLY }

                        ) { Text("أسبوعيًا") }

                        RecurrenceChip(

                            selected = recurrence == Recurrence.MONTHLY,

                            onSelect = { recurrence = Recurrence.MONTHLY }

                        ) { Text("شهريًا") }

                    }

                }
                }

            }

        },

        confirmButton = {

            TextButton(

                onClick = {
                    val draft = TaskDraft(
                        title = title.trim(),
                        minutes = TaskDraft.minutesFrom(minutesInput),
                        category = category,
                        priority = priority,
                        dueAt = dueAtString,
                        recurrence = recurrence,
                        notes = notes,
                        subjectId = subjectId,
                        typeId = typeId
                    )
                    if (draft.isValid()) onAdd(draft)
                },

                enabled = TaskDraft.canAdd(title, minutesInput)

            ) { Text("إضافة") }

        },

        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }

    )

}

@Composable

private fun SubjectChooser(subjects: List<StudySubject>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = subjects.firstOrNull { it.id == selectedId }?.name ?: "بدون مادة"
    Box(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                "المادة المرتبطة: $selectedName",
                color = FocusColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            DropdownMenuItem(
                text = { Text("بدون مادة", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = { onSelect(null); expanded = false }
            )
            subjects.forEach { subject ->
                DropdownMenuItem(
                    text = { Text(subject.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onSelect(subject.id); expanded = false }
                )
            }
            if (subjects.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("أضف مادة من قسم موادي أولًا", color = FocusColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false }
                )
            }
        }
    }
}

@Composable
private fun TaskCategoryChip(selected: Boolean, onSelect: () -> Unit, content: @Composable () -> Unit) {

    Box(

        modifier = Modifier

            .clip(RoundedCornerShape(12.dp))

            .background(if (selected) FocusColors.Blue else FocusColors.SurfaceHigh)

            .clickable { onSelect() }

            .padding(horizontal = 8.dp, vertical = 4.dp)

    ) {

        CompositionLocalProvider(LocalContentColor provides if (selected) Color.White else FocusColors.TextSecondary) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge, content)
        }

    }

}

@Composable

private fun TaskPriorityChip(selected: Boolean, onSelect: () -> Unit, content: @Composable () -> Unit) {

    Box(

        modifier = Modifier

            .clip(RoundedCornerShape(12.dp))

            .background(if (selected) FocusColors.Blue else FocusColors.SurfaceHigh)

            .clickable { onSelect() }

            .padding(horizontal = 8.dp, vertical = 4.dp)

    ) {

        CompositionLocalProvider(LocalContentColor provides if (selected) Color.White else FocusColors.TextSecondary) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge, content)
        }

    }

}

@Composable

private fun RecurrenceChip(selected: Boolean, onSelect: () -> Unit, content: @Composable () -> Unit) {

    Box(

        modifier = Modifier

            .clip(RoundedCornerShape(12.dp))

            .background(if (selected) FocusColors.Blue else FocusColors.SurfaceHigh)

            .clickable { onSelect() }

            .padding(horizontal = 8.dp, vertical = 4.dp)

    ) {

        CompositionLocalProvider(LocalContentColor provides if (selected) Color.White else FocusColors.TextSecondary) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge, content)
        }

    }

}

@Composable
private fun BlockedAppsScreen(taskId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf(emptyList<InstalledApp>()) }
    var selected by remember(taskId) { mutableStateOf(emptySet<String>()) }
    var query by rememberSaveable(taskId) { mutableStateOf("") }
    var taskTitle by remember { mutableStateOf("") }

    LaunchedEffect(taskId) {
        val (loadedApps, task) = withContext(Dispatchers.IO) {
            val currentTask = StudyDb.get(context).task(taskId)
            loadLaunchableApps(context) to currentTask
        }
        apps = loadedApps
        selected = task?.blockedPackages.orEmpty()
        taskTitle = task?.title.orEmpty()
    }

    val filteredApps = remember(apps, query) {
        val term = query.trim()
        if (term.isEmpty()) apps else apps.filter { app ->
            app.label.contains(term, ignoreCase = true) || app.packageName.contains(term, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("رجوع") }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("التطبيقات المشتتة", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = FocusColors.TextPrimary)
                if (taskTitle.isNotBlank()) {
                    Text(taskTitle, style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("ابحث عن تطبيق") },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("المحدد ${selected.size}", style = MaterialTheme.typography.labelMedium, color = FocusColors.TextSecondary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { selected = selected + filteredApps.map { it.packageName } }) {
                Text("اختيار النتائج")
            }
            TextButton(onClick = { selected = emptySet() }) {
                Text("مسح", color = FocusColors.TextSecondary)
            }
        }
        if (selected.isNotEmpty()) {
            SelectedAppsStrip(
                selectedApps = apps.filter { it.packageName in selected },
                onRemove = { packageName -> selected = selected - packageName }
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(filteredApps, key = { it.packageName }) { app ->
                val checked = app.packageName in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (checked) FocusColors.SurfaceHigh else FocusColors.Surface)
                        .clickable {
                            selected = if (checked) selected - app.packageName else selected + app.packageName
                        }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { value ->
                            selected = if (value) selected + app.packageName else selected - app.packageName
                        },
                        colors = CheckboxDefaults.colors(checkedColor = FocusColors.Blue)
                    )
                    Spacer(Modifier.width(8.dp))
                    InstalledAppIcon(app, Modifier.size(40.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, color = FocusColors.TextPrimary)
                        Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = FocusColors.TextSecondary, maxLines = 1)
                    }
                }
            }
            if (filteredApps.isEmpty()) {
                item {
                    Text(
                        if (apps.isEmpty()) "جارٍ تحميل التطبيقات أو لا توجد تطبيقات قابلة للفتح." else "لا توجد تطبيقات تطابق البحث.",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        color = FocusColors.TextSecondary
                    )
                }
            }
        }
        Button(
            onClick = {
                SessionEngine.updateTaskBlocked(taskId, selected)
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("حفظ التطبيقات (${selected.size})")
        }
    }
}

@Composable
private fun InstalledAppIcon(app: InstalledApp, modifier: Modifier = Modifier.size(40.dp)) {
    val bitmap = remember(app.packageName, app.icon) {
        runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
    }
    Box(
        modifier = modifier.clip(CircleShape).background(FocusColors.SurfaceHigh),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) Image(bitmap, contentDescription = "أيقونة ${app.label}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(5.dp))
        else Text(app.label.firstOrNull()?.uppercase().orEmpty(), color = FocusColors.TextPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SelectedAppsStrip(selectedApps: List<InstalledApp>, onRemove: (String) -> Unit) {
    if (selectedApps.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        selectedApps.forEach { app ->
            Card(colors = CardDefaults.cardColors(containerColor = FocusColors.SurfaceHigh), shape = RoundedCornerShape(FocusSpacing.radiusCard)) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InstalledAppIcon(app, Modifier.size(30.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(app.label, maxLines = 1, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(max = 110.dp), color = FocusColors.TextPrimary)
                    Box(
                        modifier = Modifier.size(30.dp).clip(CircleShape).clickable { onRemove(app.packageName) },
                        contentAlignment = Alignment.Center
                    ) { Text("×", color = FocusColors.TextSecondary, fontSize = 20.sp) }
                }
            }
        }
    }
}

@Composable
private fun RefreshOnResume(block: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val current = rememberUpdatedState(block)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) current.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

private fun isVpnConsentGranted(context: Context): Boolean =
    runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openBatterySettings(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
    val opened = runCatching { context.startActivity(direct) }.isSuccess
    if (!opened) {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
}

private fun formatClock(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return String.format(Locale.US, "%02d:%02d", safe / 60, safe % 60)
}


