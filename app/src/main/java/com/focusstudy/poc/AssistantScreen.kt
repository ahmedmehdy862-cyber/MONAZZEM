package com.focusstudy.poc

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * شاشة المساعد الشخصي «منظم».
 *
 * تبويبان: «المحادثة» (ردود قواعد محلية من معرفة التطبيق، إدخال كتابة أو صوت،
 * ونطق الردود) و«عرّفني عليك» (البرومبت الثابت، لصق نص أو استيراد PDF/TXT،
 * الحقائق المتعلمة، وتغيير الاسم والنبرة). كل بيانات المساعد محلية على الجهاز
 * فقط — لا رفع ولا مشاركة بين المستخدمين إطلاقًا — ومذكور ذلك في الواجهة.
 *
 * الألوان من [FocusColors] فقط، فتقرأ الشاشة صح في الفاتح والداكن.
 */
enum class AssistantAvatarState { IDLE, TALKING, LISTENING }

@Composable
internal fun AssistantScreen(
    onBack: () -> Unit,
    userName: String,
    /** مسار صورة المستخدم المرفوعة — تُعرض في رأس المحادثة بدل الوجه الكرتوني الثابت (0.35.0). */
    avatarPath: String = "",
    companionStyle: Int = 0,
    assistantName: String,
    onAssistantNameChanged: (String) -> Unit,
    onOpenTasks: () -> Unit = {},
    /** تنفّذها MainActivity: إضافة مهمة عبر TaskRepository وإشعار واجهة المهام.
     * [plannedMinutes] مدة المهمة كما ذكرها المستخدم (null للافتراضي 30). */
    onAssistantAddTask: suspend (String, Long?, Int?, Recurrence, Int?) -> Boolean = { _, _, _, _, _ -> false },
    /** تنفّذها MainActivity: تسجيل ورد اليوم عبر LifeRepository بإعدادات الورد. */
    onAssistantLogWird: suspend () -> Boolean = { false },
    /** تنفّذها MainActivity: بدء جلسة عبر مسار بدء الجلسة القائم أو فتح إعدادها. */
    onAssistantStartFocus: suspend (AssistantAction.StartFocus) -> AssistantFocusOutcome = { AssistantFocusOutcome.FAILED },
    /** تنفّذها MainActivity: تعليم مهمة كمنجزة عبر TaskRepository. */
    onAssistantCompleteTask: suspend (Long) -> Boolean = { false },
    /** تنفّذها MainActivity: انتقال إلى مسار المصحف المباشر. */
    onAssistantOpenQuran: () -> Unit = {},
    /** تنفّذها MainActivity: انتقال إلى خطة اليوم. */
    onAssistantOpenToday: () -> Unit = {},
    /** متحكم الرفيق ثلاثي الأبعاد (0.34.0، M3): THINKING أثناء توليد الرد، ثم IDLE بعده. */
    companionController: CompanionController? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AssistantStore.loadSettings(context)) }
    var facts by remember { mutableStateOf(AssistantStore.loadFacts(context)) }
    var profile by remember { mutableStateOf(AssistantStore.loadProfile(context)) }
    var messages by remember { mutableStateOf(AssistantStore.loadHistory(context)) }
    var snapshot by remember { mutableStateOf(AssistantSnapshot(userName = userName)) }
    var pendingProposal by remember { mutableStateOf<ProposedTask?>(null) }
    var pendingChips by remember { mutableStateOf<List<String>>(emptyList()) }
    var memories by remember { mutableStateOf<List<AssistantMemoryRow>>(emptyList()) }
    // اقتراحات متكيفة من بيانات المستخدم (لا أسئلة ثابتة).
    val suggestions = remember(snapshot) {
        runCatching { AssistantSuggestions.suggest(snapshot) }.getOrDefault(emptyList())
    }
    // تاريخ اليوم ومنطقته الزمنية (من إعدادات التطبيق) لتمريرهما إلى المحرك:
    // استخراج موعد مهمة أو مدة جلسة يحتاجان تاريخ اليوم والمنطقة الصحيحين.
    val today = rememberCurrentDate()
    val zone = remember(context) { AppTimeZone.zone(context) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var avatarState by remember { mutableStateOf(AssistantAvatarState.IDLE) }
    var voiceNote by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var nameDraft by rememberSaveable { mutableStateOf(settings.name) }
    var pasteDraft by rememberSaveable { mutableStateOf("") }
    var promptExpanded by remember { mutableStateOf(!settings.promptShown) }
    val listState = rememberLazyListState()

    val voice = remember { AssistantVoice(context) }

    fun saveSettings(next: AssistantSettings) {
        settings = next
        AssistantStore.saveSettings(context, next)
    }

    fun addMessage(role: String, text: String) {
        messages = messages + AssistantChatMessage(role, text, System.currentTimeMillis())
        AssistantStore.saveHistory(context, messages)
    }

    /**
     * ينفّذ فعل المساعد عبر دوال رد النداء من MainActivity، ويعيد نص التأكيد
     * النهائي للعرض في المحادثة.
     */
    suspend fun executeAssistantAction(action: AssistantAction, confirmation: String): String = when (action) {
        is AssistantAction.AddTask ->
            if (onAssistantAddTask(action.title, action.dueAt, action.priorityLevel, action.recurrence, action.plannedMinutes)) confirmation
            else "حاولت أضيف المهمة بس حصلت مشكلة. جرّب تاني من زر الإضافة السريع."
        AssistantAction.LogWird ->
            if (onAssistantLogWird()) confirmation
            else "ورد النهاردة متسجّل أصلًا — أو حصلت مشكلة في التسجيل. افتح المصحف للتأكيد."
        is AssistantAction.StartFocus -> when (onAssistantStartFocus(action)) {
            AssistantFocusOutcome.STARTED -> confirmation
            AssistantFocusOutcome.SETUP_OPENED ->
                if (action.taskTitle != null) {
                    "فتحت لك إعداد جلسة مهمة «${action.taskTitle}» — اختر التطبيقات المحظورة واضغط «ابدأ الجلسة»."
                } else {
                    "فتحت لك إعداد جلسة تركيز عامة — اختر التطبيقات المحظورة واضغط «ابدأ الجلسة»."
                }
            AssistantFocusOutcome.ALREADY_RUNNING ->
                "في جلسة تركيز شغالة أصلًا دلوقتي. خلصها الأول وبعدين نبدأ جلسة جديدة."
            AssistantFocusOutcome.FAILED ->
                "ما قدرتش أبدأ الجلسة دلوقتي. جرّب من «مهامي» — افتح المهمة واضغط بدء الجلسة."
        }
        is AssistantAction.CompleteTask ->
            if (onAssistantCompleteTask(action.taskId)) confirmation
            else "ما قدرتش أعلّم المهمة كمنجزة. جرّب من «مهامي»."
        AssistantAction.OpenQuran -> {
            onAssistantOpenQuran()
            confirmation
        }
        AssistantAction.OpenToday -> {
            onAssistantOpenToday()
            confirmation
        }
    }

    /** حارس الإرسال المكرر (A-16): نقرة مزدوجة على زر أو رقاقة لا تُرسل
     * الرسالة مرتين ولا تنفّذ الفعل مرتين. */
    var sending by remember { mutableStateOf(false) }

    fun send(rawText: String) {
        val clean = rawText.trim()
        if (clean.isBlank() || sending) return
        sending = true
        input = ""
        voiceNote = null
        avatarState = AssistantAvatarState.TALKING
        addMessage("user", clean)
        // الرفيق يفكر أثناء توليد الرد (M3 — كانت حالة THINKING بلا متصل).
        companionController?.think()
        scope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) { AssistantKnowledge.load(context, userName) }
                snapshot = fresh
                val reply = AssistantEngine.reply(clean, fresh, settings, facts, profile, today = today, zone = zone)
                reply.learnedFact?.let { learned ->
                    facts = facts + learned
                    AssistantStore.saveFacts(context, facts)
                    // A2 (A-15): مصدر واحد للحقائق — تُحفظ في التفضيلات فقط؛
                    // استرجاعها يتم من الحقائق نفسها (memoryAnswer) لا من نسخة
                    // ثانية في قاعدة البيانات، فلا تظهر مرتين في تبويب الملف.
                }
                reply.usedMemoryId?.let { id ->
                    withContext(Dispatchers.IO) { AssistantMemoryStore.touchMemory(context, id) }
                }
                pendingProposal = reply.proposedTask
                pendingChips = reply.chips
                // الفعل يُنفَّذ أولًا، ثم يُعرض تأكيده: نص المحرك عند النجاح، أو
                // رسالة بديلة دقيقة عند الفشل أو عند فتح إعداد الجلسة بدل بدئها.
                val finalText = reply.action?.let { executeAssistantAction(it, reply.text) } ?: reply.text
                addMessage("assistant", finalText)
                // الحارس يُرفع فور ظهور الرد — لا بعد تأخير حالة TALKING
                // الاصطناعي، فيستطيع المستخدم إرسال رسالة جديدة فورًا.
                sending = false
                if (settings.speakReplies) voice.speak(finalText)
                delay(finalText.length.coerceIn(30, 130) * 45L)
            } finally {
                sending = false
            }
            avatarState = AssistantAvatarState.IDLE
            companionController?.playIdle()
        }
    }

    val recognizer = remember {
        AssistantSpeechRecognizer(
            context = context,
            onResult = { heard -> send(heard) },
            // A4 (A-21): النتائج الجزئية لا تستبدل نصًا كتبه المستخدم —
            // تُملأ فقط إن كان الحقل فارغًا (إملاء صوتي من الصفر).
            onPartial = { heard -> if (input.isBlank()) input = heard },
            onError = { message ->
                voiceNote = message
                avatarState = AssistantAvatarState.IDLE
            },
            onListeningChanged = { listening ->
                avatarState = if (listening) AssistantAvatarState.LISTENING else AssistantAvatarState.IDLE
            }
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            voice.release()
            recognizer.destroy()
        }
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceNote = "بتكلم؟ اسمعك…"
            recognizer.start()
        } else {
            voiceNote = "الميكروفون محتاج إذن. فعّله من إعدادات التطبيق، أو اكتب رسالتك."
        }
    }

    // البوابة المركزية (المرحلة 7أ): شرح داخل التطبيق قبل أول طلب نظام، وطلب
    // النظام مرة واحدة فقط في عمر التثبيت، ثم تلميح يدوي بعد أي رفض.
    val micGate = rememberPermissionGate(
        permission = GatedPermission.MICROPHONE,
        launchSystemRequest = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
        onGranted = {
            voiceNote = "بتكلم؟ اسمعك…"
            recognizer.start()
        }
    )

    fun importProfileText(text: String, sourceLabel: String) {
        val clean = text.trim()
        if (clean.isBlank()) {
            importStatus = "الملف فاضي أو ما فيهوش نص مقروء. جرّب تنسخ النص وتلصقه في الحقل فوق."
            return
        }
        profile = clean.take(20_000)
        AssistantStore.saveProfile(context, profile)
        saveSettings(settings.copy(profileImported = true))
        // A2: تحليل السطور الموسومة إلى حقائق مصنّفة (اسم/هدف/روتين/صحة…)
        // تُستخدم في التحية والخطة؛ سطر بلا وسم يبقى اقتباسًا حرفيًا فقط.
        val parsed = AssistantEngine.parseProfileFacts(profile)
        val fresh = parsed.filter { item -> facts.none { it.text == item.text } }
        if (fresh.isNotEmpty()) {
            facts = facts + fresh
            AssistantStore.saveFacts(context, facts)
        }
        importStatus = "تمام، حفظت ملفك التعريفي من $sourceLabel (${profile.length} حرف)." +
            if (parsed.isNotEmpty()) " حلّلت منه ${parsed.size} حقائق مصنفة (اسم/هدف/روتين…) وهستخدمها عشان أفهمك أكتر." else " من النهاردة هستخدمه عشان أفهمك أكتر."
        addMessage("assistant", "حفظت ملفك التعريفي من $sourceLabel، وهستخدمه عشان أفهمك وأساعدك بشكل أفضل.")
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importBusy = true
        importStatus = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { AssistantPdfImport.extractText(context, uri) }
            importBusy = false
            result
                .onSuccess { text -> importProfileText(text, "ملف PDF") }
                .onFailure {
                    importStatus = "ما قدرتش أقرا النص من ملف الـ PDF ده (ممكن يكون صور ممسوحة ضوئيًا من غير نص). انسخ النص من الملف والصقه في الحقل فوق، أو استورد ملف نصي (.txt)."
                }
        }
    }
    val txtPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importBusy = true
        importStatus = null
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    }.orEmpty()
                }.getOrDefault("")
            }
            importBusy = false
            importProfileText(text, "ملف نصي")
        }
    }

    // الترحيب فوري — قبل أي قراءة قاعدة بيانات: التحية البسيطة لا تحتاج
    // اللقطة، فتظهر في أول إطار بعد فتح الشاشة. (عيب أول فتح للشات: انتظار
    // AssistantKnowledge.load() — عشرات قراءات قاعدة البيانات — على الـ Main
    // كان يؤخر الترحيب إلى ما بعد مهلة اختبار البوابة.)
    LaunchedEffect(Unit) {
        if (messages.isEmpty()) {
            addMessage("assistant", AssistantEngine.greeting(settings, userName))
        }
        val loaded = withContext(Dispatchers.IO) { AssistantKnowledge.load(context, userName) }
        snapshot = loaded
        memories = loaded.context.memories
    }
    // تُسجَّل الاقتراحات المعروضة (قبول/عرض) مرة لكل لقطة، ليتعلم المساعد
    // أي نوع اقتراح ينفع فعلًا وأي نوع يتجاهله المستخدم.
    var suggestionsRecordedFor by remember { mutableStateOf<AssistantSnapshot?>(null) }
    LaunchedEffect(snapshot) {
        memories = snapshot.context.memories
        if (suggestions.isNotEmpty() && suggestionsRecordedFor != snapshot) {
            suggestionsRecordedFor = snapshot
            withContext(Dispatchers.IO) {
                suggestions.forEach { suggestion ->
                    AssistantMemoryStore.recordInteraction(context, suggestion.kind, suggestion.text, accepted = false)
                }
            }
        }
    }
    // التمرير التلقائي لآخر رسالة يبدأ بعد التحميل الأولي فقط: عند التحميل
    // الأولي (سجل سابق أو رسالة الترحيب) يبقى أول عنصر ظاهرًا كاملًا ولا
    // يُقصّ نصفه تحت التبويبات (لقطة أحمد 19:03 — فقاعة الترحيب مقصوصة).
    var autoScrollReady by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (!autoScrollReady) {
            autoScrollReady = true
            return@LaunchedEffect
        }
        listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = FocusSpacing.screen)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                val next = settings.copy(speakReplies = !settings.speakReplies)
                saveSettings(next)
                if (!next.speakReplies) voice.stop()
            }) {
                Icon(
                    imageVector = if (settings.speakReplies) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = if (settings.speakReplies) "كتم صوت المساعد" else "تشغيل صوت المساعد",
                    tint = FocusColors.TextSecondary
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // صورة المستخدم المرفوعة في رأس المحادثة (0.35.0) — بدل الوجه
            // الكرتوني الثابت الذي كان يُرسم من بكسلات فارغة ولا يقرأ الصورة.
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center
            ) {
                AvatarPortrait(
                    avatarPath = avatarPath,
                    name = userName,
                    styleIndex = companionStyle,
                    size = 72.dp
                )
                if (avatarState == AssistantAvatarState.LISTENING) {
                    Box(
                        Modifier.fillMaxSize()
                            .border(2.dp, FocusColors.Blue, CircleShape)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = settings.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = FocusColors.TextPrimary
                )
                Text(
                    text = "مساعدك الشخصي — على جهازك فقط",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
                Text(
                    text = "لا رفع ولا مشاركة بين المستخدمين إطلاقًا.",
                    style = MaterialTheme.typography.labelSmall,
                    color = FocusColors.TextMuted
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(FocusSpacing.chip)) {
            AssistantTone.entries.forEach { tone ->
                FilterChip(
                    selected = settings.tone == tone,
                    onClick = { saveSettings(settings.copy(tone = tone)) },
                    label = { Text(tone.label) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistantTabButton(selected = tab == 0, label = "المحادثة") { tab = 0 }
            AssistantTabButton(selected = tab == 1, label = "عرّفني عليك") { tab = 1 }
        }
        Spacer(Modifier.height(12.dp))

        if (tab == 0) {
            // جسم الشات مثبّت (إصلاح عيب أول فتح للشات): قائمة الرسائل تملأ
            // المساحة، وشريط الرقاقات والإدخال مثبّت في أسفل الشاشة بلا
            // اعتماد على weight — فحتى لو انهار توزيع الـ weight في السلف،
            // يبقى حقل الإدخال ظاهرًا والرقاقات غير مقصوصة. ارتفاع الشريط
            // السفلي يُقاس وقت التشغيل فيُحجز مثله أسفل القائمة حتى لا
            // تُحجب آخر رسالة خلف الشريط.
            val density = LocalDensity.current
            var bottomBarHeight by remember { mutableStateOf(0.dp) }
            // زر قفز للأعلى (A4): يظهر بعد التمرير لأسفل، فيعود المستخدم
            // لفقاعة الترحيب بلا تمرير يدوي طويل.
            val showScrollTop by remember {
                derivedStateOf {
                    listState.firstVisibleItemIndex > 1 || listState.firstVisibleItemScrollOffset > 0
                }
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 16.dp, bottom = bottomBarHeight + 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { message -> AssistantMessageBubble(message) }
                }
                if (showScrollTop) {
                    TextButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        Text("↑ العودة لأول المحادثة")
                    }
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(FocusColors.Surface)
                        .onSizeChanged { size -> bottomBarHeight = with(density) { size.height.toDp() } }
                ) {
                    Spacer(Modifier.height(8.dp))
            // صف الأوامر التنفيذية أولًا: الضغط على أي منها ينفّذ الفعل فعلًا
            // (إضافة مهمة، تسجيل ورد، بدء جلسة، فتح المصحف أو خطة اليوم).
            WrappedChipRow {
                ASSISTANT_ACTION_PROMPTS.forEach { prompt ->
                    AssistChip(onClick = { send(prompt) }, label = { Text(prompt) })
                }
            }
            Spacer(Modifier.height(8.dp))
            // رقاقات متكيفة من بيانات المستخدم (لا أسئلة ثابتة): كل رقاقة
            // تُرسل نصها وتُسجَّل قبولًا ليتعلم المساعد ما ينفع. بلا بيانات
            // كافية تُعرض الأسئلة المعروفة كبداية فقط.
            WrappedChipRow {
                if (suggestions.isNotEmpty()) {
                    suggestions.forEach { suggestion ->
                        AssistChip(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        AssistantMemoryStore.recordInteraction(
                                            context, suggestion.kind, suggestion.text, accepted = true
                                        )
                                    }
                                }
                                send(suggestion.sendText)
                            },
                            label = { Text(suggestion.text) }
                        )
                    }
                } else {
                    ASSISTANT_KNOWLEDGE_PROMPTS.forEach { prompt ->
                        AssistChip(onClick = { send(prompt) }, label = { Text(prompt) })
                    }
                }
            }
            // مهمة مقترحة من كلام عادي: زر تأكيد يضيفها فعلًا، أو رفض يلغيها.
            pendingProposal?.let { proposed ->
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = FocusColors.SurfaceHigh),
                    shape = RoundedCornerShape(FocusSpacing.radiusCard)
                ) {
                    Column(Modifier.fillMaxWidth().padding(FocusSpacing.card)) {
                        Text(
                            text = "أضيف مهمة «${proposed.title}»؟",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = FocusColors.TextPrimary
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val toAdd = proposed
                                pendingProposal = null
                                scope.launch {
                                    val added = onAssistantAddTask(
                                        toAdd.title, toAdd.dueAt, toAdd.priorityLevel, toAdd.recurrence,
                                        toAdd.plannedMinutes
                                    )
                                    addMessage(
                                        "assistant",
                                        if (added) "تم إضافة المهمة «${toAdd.title}»، وهتلاقيها في «مهامي»."
                                        else "ما قدرتش أضيف المهمة. جرّب من زر الإضافة السريع."
                                    )
                                }
                            }) { Text("أيوه ضيفها") }
                            OutlinedButton(onClick = { pendingProposal = null }) { Text("لا مش مهم") }
                        }
                    }
                }
            }
            // رقاقات توضيحية: خيارات سؤال المساعد التوضيحي (مثلًا «خلصت إيه؟»).
            if (pendingChips.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                WrappedChipRow {
                    pendingChips.forEach { chip ->
                        AssistChip(
                            onClick = {
                                pendingChips = emptyList()
                                send(chip)
                            },
                            label = { Text(chip) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.imePadding()
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب رسالتك لـ ${settings.name}…") },
                    singleLine = false,
                    maxLines = 3,
                    // A4: زر الإدخال في لوحة المفاتيح يرسل بدل سطر جديد.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input) })
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    // A4 (A-20): النقرة الثانية على الميك تلغي الاستماع بدل
                    // إعادة تشغيله — المستخدم لا ينتظر المهلة.
                    if (avatarState == AssistantAvatarState.LISTENING) {
                        recognizer.stop()
                        voiceNote = null
                        avatarState = AssistantAvatarState.IDLE
                        return@IconButton
                    }
                    if (!AssistantVoice.isRecognitionAvailable(context)) {
                        voiceNote = "التعرف الصوتي مش متاح على الجهاز ده — اكتب رسالتك."
                        return@IconButton
                    }
                    micGate.attempt()
                }) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = if (avatarState == AssistantAvatarState.LISTENING) "إيقاف الاستماع" else "تحدث",
                        tint = if (avatarState == AssistantAvatarState.LISTENING) FocusColors.onTint(FocusColors.Blue) else FocusColors.TextSecondary
                    )
                }
                IconButton(onClick = { send(input) }, enabled = input.isNotBlank() && !sending) {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = "إرسال",
                        tint = FocusColors.onTint(FocusColors.Blue)
                    )
                }
            }
            voiceNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = FocusColors.TextMuted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
            } ?: Spacer(Modifier.height(8.dp))
                } // نهاية شريط الرقاقات والإدخال المثبّت في الأسفل
            } // نهاية Box جسم الشات
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AssistantProfileTab(
                    profile = profile,
                    facts = facts,
                    nameDraft = nameDraft,
                    onNameDraftChange = { nameDraft = it },
                    onSaveName = {
                        val clean = nameDraft.trim()
                        if (clean.isBlank()) {
                            importStatus = "اكتب اسم المساعد الأول."
                        } else {
                            saveSettings(settings.copy(name = clean))
                            onAssistantNameChanged(clean)
                            importStatus = "تمام، من النهاردة هنادي المساعد «$clean»."
                        }
                    },
                    pasteDraft = pasteDraft,
                    onPasteDraftChange = { pasteDraft = it },
                    onSavePaste = {
                        importProfileText(pasteDraft, "النص الملصوق")
                        pasteDraft = ""
                    },
                    promptExpanded = promptExpanded,
                    onTogglePrompt = { promptExpanded = !promptExpanded },
                    onCopyPrompt = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("برومبت منظم", ASSISTANT_ONBOARDING_PROMPT)
                        )
                        importStatus = "اتنسخ البرومبت. الصقه في ChatGPT أو Gemini، وبعدين أعد الناتج هنا كنص أو PDF."
                    },
                    onPromptUnderstood = {
                        saveSettings(settings.copy(promptShown = true))
                        promptExpanded = false
                    },
                    onPickPdf = { pdfPicker.launch("application/pdf") },
                    onPickTxt = { txtPicker.launch("text/plain") },
                    importBusy = importBusy,
                    importStatus = importStatus,
                    onDeleteProfile = {
                        profile = ""
                        AssistantStore.saveProfile(context, "")
                        saveSettings(settings.copy(profileImported = false))
                        importStatus = "اتحذف الملف التعريفي."
                    },
                    onDeleteFact = { fact ->
                        facts = facts.filter { it.id != fact.id }
                        AssistantStore.saveFacts(context, facts)
                    },
                    onClearHistory = {
                        messages = emptyList()
                        AssistantStore.clearHistory(context)
                        addMessage("assistant", AssistantEngine.greeting(settings, snapshot))
                        importStatus = "اتمسح سجل المحادثة."
                    },
                    onOpenTasks = onOpenTasks,
                    learnedEntries = snapshot.context.profileEntries,
                    // A2 (A-15): ذكريات قاعدة البيانات التي تطابق حقيقة في
                    // التفضيلات (من نسخ مزدوجة قديمة) لا تُعرض مرتين.
                    memories = memories.filter { memory ->
                        facts.none { it.text == memory.text }
                    },
                    onDeleteMemory = { memory ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AssistantMemoryStore.deleteMemory(context, memory.id)
                            }
                            memories = memories.filter { it.id != memory.id }
                        }
                    }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    // نافذة بوابة الميكروفون (شرح قبل أول طلب، وتلميح يدوي بعد الرفض).
    micGate.Dialog()
}

@Composable
private fun AssistantTabButton(selected: Boolean, label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) FocusColors.Blue else FocusColors.SurfaceHigh,
            contentColor = if (selected) FocusColors.onAccentFill(FocusColors.Blue) else FocusColors.TextPrimary
        )
    ) { Text(label) }
}

@Composable
private fun AssistantMessageBubble(message: AssistantChatMessage) {
    val isUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        // A4 (A-09): الفقاعة بعرض محتواها (حد أقصى 85%) — رسالة من كلمتين
        // لا تُرسم كلوح أزرق بعرض الشاشة.
        if (isUser) Spacer(Modifier.weight(0.15f))
        Surface(
            color = if (isUser) FocusColors.Blue else FocusColors.SurfaceHigh,
            shape = RoundedCornerShape(FocusSpacing.radiusCard),
            modifier = Modifier.weight(0.85f, fill = false)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) FocusColors.onAccentFill(FocusColors.Blue) else FocusColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
        if (!isUser) Spacer(Modifier.weight(0.15f))
    }
}

/**
 * صف رقاقات ملتفّ (A4/A-08، إصلاح عيب أول فتح للشات): الرقاقات تلتفّ على
 * أسطر بدل التمرير الأفقي، فلا تُقصّ أي رقاقة عند حافة الشاشة — التمرير
 * الأفقي كان يخفي الرقاقة الطرفية اليسرى («سجّل ورد اليوم»).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WrappedChipRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FocusSpacing.chip),
        verticalArrangement = Arrangement.spacedBy(FocusSpacing.chip)
    ) {
        content()
    }
}

/** تبويب «عرّفني عليك»: البرومبت والاستيراد والملف والحقائق والاسم. */
@Composable
private fun AssistantProfileTab(
    profile: String,
    facts: List<LearnedFact>,
    nameDraft: String,
    onNameDraftChange: (String) -> Unit,
    onSaveName: () -> Unit,
    pasteDraft: String,
    onPasteDraftChange: (String) -> Unit,
    onSavePaste: () -> Unit,
    promptExpanded: Boolean,
    onTogglePrompt: () -> Unit,
    onCopyPrompt: () -> Unit,
    onPromptUnderstood: () -> Unit,
    onPickPdf: () -> Unit,
    onPickTxt: () -> Unit,
    importBusy: Boolean,
    importStatus: String?,
    onDeleteProfile: () -> Unit,
    onDeleteFact: (LearnedFact) -> Unit,
    onClearHistory: () -> Unit,
    onOpenTasks: () -> Unit,
    learnedEntries: List<AssistantProfileEntry> = emptyList(),
    memories: List<AssistantMemoryRow> = emptyList(),
    onDeleteMemory: (AssistantMemoryRow) -> Unit = {}
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.SurfaceHigh),
        shape = RoundedCornerShape(FocusSpacing.radiusCard)
    ) {
        Column(Modifier.fillMaxWidth().padding(FocusSpacing.card)) {
            Text(
                text = "عرّفني عليك",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = FocusColors.TextPrimary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "أول مرة: انسخ البرومبت اللي تحت والصقه في ChatGPT أو Gemini (التطبيق اللي بتستخدمه بكثرة وعنده معلومات كافية عنك)، وبعدين أعد الناتج هنا كنص ملصوق أو كملف PDF. كل البيانات دي على جهازك بس.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            Spacer(Modifier.height(10.dp))
            if (promptExpanded) {
                Surface(
                    color = FocusColors.Surface,
                    shape = RoundedCornerShape(FocusSpacing.radiusCard)
                ) {
                    Text(
                        text = ASSISTANT_ONBOARDING_PROMPT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FocusColors.TextPrimary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCopyPrompt) {
                        Icon(imageVector = Icons.Filled.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("نسخ البرومبت")
                    }
                    Button(onClick = onPromptUnderstood) { Text("فهمت، هبدأ") }
                }
            } else {
                OutlinedButton(onClick = onTogglePrompt) { Text("عرض برومبت التعريف") }
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard)
    ) {
        Column(Modifier.fillMaxWidth().padding(FocusSpacing.card)) {
            Text(
                text = "أعد الناتج إلى المساعد",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = FocusColors.TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pasteDraft,
                onValueChange = onPasteDraftChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("الصق نص الملف التعريفي هنا…") },
                minLines = 4,
                maxLines = 8
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onSavePaste, enabled = pasteDraft.isNotBlank()) {
                Text("حفظ النص الملصوق")
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickPdf, enabled = !importBusy) { Text("استيراد ملف PDF") }
                OutlinedButton(onClick = onPickTxt, enabled = !importBusy) { Text("استيراد ملف نصي (.txt)") }
                if (importBusy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            Text(
                text = "استخراج نص الـ PDF يتم على جهازك بمكتبة PDFBox-Android. إن فشل الاستخراج (مثلًا ملف صور ممسوحة ضوئيًا من غير نص) هتظهر رسالة واضحة، وتقدر تنسخ النص وتلصقه فوق.",
                style = MaterialTheme.typography.labelSmall,
                color = FocusColors.TextMuted
            )
            importStatus?.let { status ->
                Spacer(Modifier.height(6.dp))
                Text(text = status, style = MaterialTheme.typography.bodySmall, color = FocusColors.TextSecondary)
            }
            if (profile.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "ملفك التعريفي المحفوظ:",
                    style = MaterialTheme.typography.labelMedium,
                    color = FocusColors.TextSecondary
                )
                Text(
                    text = profile.take(300) + if (profile.length > 300) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextPrimary
                )
                TextButton(onClick = onDeleteProfile) {
                    Text("حذف الملف التعريفي", color = FocusColors.Error)
                }
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard)
    ) {
        Column(Modifier.fillMaxWidth().padding(FocusSpacing.card)) {
            Text(
                text = "اسم المساعد",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = FocusColors.TextPrimary
            )
            Text(
                text = "الاسم اللي بتنادي بيه المساعد في المحادثة والقايمة الجانبية.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = onNameDraftChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("منظم") }
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSaveName) { Text("حفظ الاسم") }
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard)
    ) {
        Column(Modifier.fillMaxWidth().padding(FocusSpacing.card)) {
            Text(
                text = "الحقائق اللي اتعلمتها منك (${facts.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = FocusColors.TextPrimary
            )
            Text(
                text = "كل ما تقولي عن نفسك في المحادثة — هدف جديد أو مواعيد نومك مثلًا — بحفظه هنا وبستخدمه في ردودي، وبيتطور مع الاستخدام يوم بعد يوم.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            if (facts.isEmpty()) {
                Text(
                    text = "لسه ما اتعلمتش حاجة عنك. جرّب من المحادثة: «عرفني على هدفك الجديد» أو «قولي عن روتينك».",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextMuted
                )
            } else {
                facts.forEach { fact ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = fact.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = FocusColors.onTint(FocusColors.Blue)
                            )
                            Text(
                                text = fact.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = FocusColors.TextPrimary
                            )
                        }
                        IconButton(onClick = { onDeleteFact(fact) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "حذف الحقيقة",
                                tint = FocusColors.TextMuted
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenTasks) { Text("فتح مهامي") }
                TextButton(onClick = onClearHistory) {
                    Text("مسح سجل المحادثة", color = FocusColors.Error)
                }
            }
        }
    }

    // ما تعلّمه المساعد من سلوكك (لا من كلامك فقط): أنماط محفوظة على جهازك.
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard)
    ) {
        Column(Modifier.fillMaxWidth().padding(FocusSpacing.card)) {
            Text(
                text = "اللي اتعلمته عنك من سلوكك",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = FocusColors.TextPrimary
            )
            Text(
                text = "احذف أي ذكرى لا تريدها.",
                style = MaterialTheme.typography.bodySmall,
                color = FocusColors.TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            if (learnedEntries.isEmpty() && memories.isEmpty()) {
                Text(
                    text = "لسه بتعلم عنك — استخدم التطبيق أيامًا قليلة وستظهر هنا أنماطك (أفضل وقت تركيز، مدة الجلسة المفضلة، الفئات التي تتأخر).",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextMuted
                )
            } else {
                learnedEntries.forEach { entry ->
                    Text(
                        text = "• ${entry.textValue}",
                        style = MaterialTheme.typography.bodySmall,
                        color = FocusColors.TextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                }
                memories.forEach { memory ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = memory.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = FocusColors.onTint(FocusColors.Blue)
                            )
                            Text(
                                text = memory.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = FocusColors.TextPrimary
                            )
                        }
                        IconButton(onClick = { onDeleteMemory(memory) }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = "حذف الذكرى",
                                tint = FocusColors.TextMuted
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/**
 * استخراج نص PDF على الجهاز عبر PDFBox-Android (com.tom-roush:pdfbox-android).
 * يتطلب تهيئة PDFBoxResourceLoader مرة واحدة في العملية قبل أول استخدام.
 * أي فشل يُعاد كـ Result.failure برسالة لطيفة في الواجهة، لا كراش.
 */
internal object AssistantPdfImport {
    @Volatile
    private var loaderReady = false

    fun extractText(context: Context, uri: Uri): Result<String> = runCatching {
        if (!loaderReady) {
            PDFBoxResourceLoader.init(context)
            loaderReady = true
        }
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("تعذر فتح الملف")
        stream.use { input ->
            PDDocument.load(input).use { document ->
                PDFTextStripper().getText(document).trim()
            }
        }
    }
}

/**
 * بطاقة المساعد الوحيدة في أعلى شاشة اليوم (A3 — دُمج فيها CompanionHomeSlot
 * فلا بطاقتا «مساعدك منظّم» مكررتان): صورة الرفيق أو نموذجه ثلاثي الأبعاد +
 * اسمه + دعوة واضحة لفتح المحادثة. البطاقة كلها قابلة للضغط، والزر يفتح
 * المحادثة مباشرة. عند تعطيل الرفيق ثلاثي الأبعاد تُعرض الصورة المرفوعة.
 */
@Composable
internal fun AssistantHomeCard(
    assistantName: String,
    onOpenAssistant: () -> Unit,
    preferences: FocusPreferences,
    companionController: CompanionController? = null,
    companion3DHost: Companion3DHost? = null,
    companionEnabled: Boolean = false,
    companionAnimationsEnabled: Boolean = true
) {
    val show3D = companionController != null && companion3DHost != null && companionEnabled
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAssistant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (show3D) {
                MONAZZEMCompanionView(
                    controller = companionController!!,
                    host = companion3DHost!!,
                    modifier = Modifier.size(96.dp),
                    enabled = true,
                    animationsEnabled = companionAnimationsEnabled
                )
            } else {
                AvatarPortrait(
                    avatarPath = preferences.avatarPath,
                    name = preferences.studentName,
                    styleIndex = preferences.companionStyle,
                    size = 52.dp
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "مساعدك $assistantName",
                    style = MaterialTheme.typography.titleSmall,
                    color = FocusColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "اسأله عن وردك ومهامك والصلاة الجاية — وعرّفه عليك عشان يفهمك أكتر.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
            }
            Button(onClick = onOpenAssistant, shape = RoundedCornerShape(16.dp)) { Text("اسألني") }
        }
    }
}
