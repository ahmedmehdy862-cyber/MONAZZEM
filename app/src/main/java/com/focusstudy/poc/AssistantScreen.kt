package com.focusstudy.poc

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.SmartToy
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
    assistantName: String,
    onAssistantNameChanged: (String) -> Unit,
    onOpenTasks: () -> Unit = {},
    /** تنفّذها MainActivity: إضافة مهمة عبر TaskRepository وإشعار واجهة المهام. */
    onAssistantAddTask: suspend (String, Long?, Int?, Recurrence) -> Boolean = { _, _, _, _ -> false },
    /** تنفّذها MainActivity: تسجيل ورد اليوم عبر LifeRepository بإعدادات الورد. */
    onAssistantLogWird: suspend () -> Boolean = { false },
    /** تنفّذها MainActivity: بدء جلسة عبر مسار بدء الجلسة القائم أو فتح إعدادها. */
    onAssistantStartFocus: suspend (AssistantAction.StartFocus) -> AssistantFocusOutcome = { AssistantFocusOutcome.FAILED },
    /** تنفّذها MainActivity: تعليم مهمة كمنجزة عبر TaskRepository. */
    onAssistantCompleteTask: suspend (Long) -> Boolean = { false },
    /** تنفّذها MainActivity: انتقال إلى مسار المصحف المباشر. */
    onAssistantOpenQuran: () -> Unit = {},
    /** تنفّذها MainActivity: انتقال إلى خطة اليوم. */
    onAssistantOpenToday: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AssistantStore.loadSettings(context)) }
    var facts by remember { mutableStateOf(AssistantStore.loadFacts(context)) }
    var profile by remember { mutableStateOf(AssistantStore.loadProfile(context)) }
    var messages by remember { mutableStateOf(AssistantStore.loadHistory(context)) }
    var snapshot by remember { mutableStateOf(AssistantSnapshot(userName = userName)) }
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

    fun send(rawText: String) {
        val clean = rawText.trim()
        if (clean.isBlank()) return
        input = ""
        voiceNote = null
        avatarState = AssistantAvatarState.TALKING
        addMessage("user", clean)
        scope.launch {
            val fresh = AssistantKnowledge.load(context, userName)
            snapshot = fresh
            val reply = AssistantEngine.reply(clean, fresh, settings, facts, profile, today = today, zone = zone)
            reply.learnedFact?.let { learned ->
                facts = facts + learned
                AssistantStore.saveFacts(context, facts)
            }
            // الفعل يُنفَّذ أولًا، ثم يُعرض تأكيده: نص المحرك عند النجاح، أو
            // رسالة بديلة دقيقة عند الفشل أو عند فتح إعداد الجلسة بدل بدئها.
            val finalText = reply.action?.let { executeAssistantAction(it, reply.text) } ?: reply.text
            addMessage("assistant", finalText)
            if (settings.speakReplies) voice.speak(finalText)
            delay(finalText.length.coerceIn(30, 130) * 45L)
            avatarState = AssistantAvatarState.IDLE
        }
    }

    /**
     * ينفّذ فعل المساعد عبر دوال رد النداء من MainActivity، ويعيد نص التأكيد
     * النهائي للعرض في المحادثة.
     */
    suspend fun executeAssistantAction(action: AssistantAction, confirmation: String): String = when (action) {
        is AssistantAction.AddTask ->
            if (onAssistantAddTask(action.title, action.dueAt, action.priorityLevel, action.recurrence)) confirmation
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

    val recognizer = remember {
        AssistantSpeechRecognizer(
            context = context,
            onResult = { heard -> send(heard) },
            onPartial = { heard -> input = heard },
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

    fun importProfileText(text: String, sourceLabel: String) {
        val clean = text.trim()
        if (clean.isBlank()) {
            importStatus = "الملف فاضي أو ما فيهوش نص مقروء. جرّب تنسخ النص وتلصقه في الحقل فوق."
            return
        }
        profile = clean.take(20_000)
        AssistantStore.saveProfile(context, profile)
        saveSettings(settings.copy(profileImported = true))
        importStatus = "تمام، حفظت ملفك التعريفي من $sourceLabel (${profile.length} حرف). من النهاردة هستخدمه عشان أفهمك أكتر."
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

    LaunchedEffect(Unit) {
        snapshot = AssistantKnowledge.load(context, userName)
        if (messages.isEmpty()) {
            addMessage("assistant", AssistantEngine.greeting(settings, userName))
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
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
            AssistantAvatar(state = avatarState, size = 72.dp)
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
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message -> AssistantMessageBubble(message) }
            }
            Spacer(Modifier.height(8.dp))
            // صف الأوامر التنفيذية أولًا: الضغط على أي منها ينفّذ الفعل فعلًا
            // (إضافة مهمة، تسجيل ورد، بدء جلسة، فتح المصحف أو خطة اليوم).
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FocusSpacing.chip)
            ) {
                ASSISTANT_ACTION_PROMPTS.forEach { prompt ->
                    AssistChip(onClick = { send(prompt) }, label = { Text(prompt) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(FocusSpacing.chip)
            ) {
                (ASSISTANT_KNOWLEDGE_PROMPTS + ASSISTANT_TEACH_ME_PROMPTS.take(3)).forEach { prompt ->
                    AssistChip(onClick = { send(prompt) }, label = { Text(prompt) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب رسالتك لـ ${settings.name}…") },
                    singleLine = false,
                    maxLines = 3
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = {
                    if (!AssistantVoice.isRecognitionAvailable(context)) {
                        voiceNote = "التعرف الصوتي مش متاح على الجهاز ده — اكتب رسالتك."
                        return@IconButton
                    }
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        voiceNote = "بتكلم؟ اسمعك…"
                        recognizer.start()
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "تحدث",
                        tint = if (avatarState == AssistantAvatarState.LISTENING) FocusColors.Blue else FocusColors.TextSecondary
                    )
                }
                IconButton(onClick = { send(input) }, enabled = input.isNotBlank()) {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = "إرسال",
                        tint = FocusColors.Blue
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
                        addMessage("assistant", AssistantEngine.greeting(settings, userName))
                        importStatus = "اتمسح سجل المحادثة."
                    },
                    onOpenTasks = onOpenTasks
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
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
        Surface(
            color = if (isUser) FocusColors.Blue else FocusColors.SurfaceHigh,
            shape = RoundedCornerShape(FocusSpacing.radiusCard),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isUser) FocusColors.onAccentFill(FocusColors.Blue) else FocusColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

/**
 * شخصية المساعد المرئية: رفيق مرسوم على الجهاز بحالات تعبيرية متحركة بسيطة
 * (ساكن / يتكلم / يستمع) عبر Compose animation، مبني على نهج [CartoonAvatar]:
 * لوحة الألوان مأخوذة من [CartoonAvatar.paletteFrom] بلا صورة وبلا رفع وبلا
 * نموذج وبلا أصول خارجية.
 *
 * ملاحظة صريحة: النموذج الثلاثي الأبعاد الحقيقي غير وارد دون أصل نموذج خارجي
 * (ملف glTF أو مشهد Filament/SceneView)؛ ما نُفّذ هنا رسم ثنائي الأبعاد متحرك
 * يفي بالغرض كرفيق مرئي، ويمكن استبداله لاحقًا بنموذج ثلاثي الأبعاد إن توفر
 * أصله دون تغيير واجهة الشاشة.
 */
@Composable
internal fun AssistantAvatar(
    state: AssistantAvatarState,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp
) {
    val palette = remember { CartoonAvatar.paletteFrom(intArrayOf(), 0) }
    val skin = Color(palette.skin)
    val hair = Color(palette.hair)
    val accent = Color(palette.accent)
    val background = Color(palette.background)
    val bob = rememberInfiniteTransition(label = "assistant-bob")
        .animateFloat(
            initialValue = 0f, targetValue = 6f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bob"
        )
    val mouthOpen = rememberInfiniteTransition(label = "assistant-mouth")
        .animateFloat(
            initialValue = 0.35f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(320),
                repeatMode = RepeatMode.Reverse
            ),
            label = "mouth"
        )
    val pulse = rememberInfiniteTransition(label = "assistant-pulse")
        .animateFloat(
            initialValue = 0.45f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (state == AssistantAvatarState.LISTENING) {
            Box(
                Modifier.fillMaxSize()
                    .border(2.dp, FocusColors.Blue.copy(alpha = pulse.value), CircleShape)
            )
        }
        Canvas(Modifier.fillMaxSize().offset(y = (-bob.value).dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            drawCircle(color = background, radius = radius, center = center)
            drawCircle(color = skin, radius = radius * 0.72f, center = center)
            // الشعر: قوس علوي بنفس لوحة الكرتون.
            drawArc(
                color = hair,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(center.x - radius * 0.72f, center.y - radius * 0.72f),
                size = Size(radius * 1.44f, radius * 1.1f)
            )
            val eyeY = center.y - radius * 0.08f
            val eyeR = radius * 0.065f
            drawCircle(color = hair, radius = eyeR, center = Offset(center.x - radius * 0.28f, eyeY))
            drawCircle(color = hair, radius = eyeR, center = Offset(center.x + radius * 0.28f, eyeY))
            // الخدود تضيف دفئًا كما في الكرتون.
            drawCircle(color = accent.copy(alpha = 0.45f), radius = radius * 0.09f, center = Offset(center.x - radius * 0.48f, center.y + radius * 0.22f))
            drawCircle(color = accent.copy(alpha = 0.45f), radius = radius * 0.09f, center = Offset(center.x + radius * 0.48f, center.y + radius * 0.22f))
            if (state == AssistantAvatarState.TALKING) {
                val mouthH = radius * 0.24f * mouthOpen.value
                drawOval(
                    color = hair,
                    topLeft = Offset(center.x - radius * 0.14f, center.y + radius * 0.22f),
                    size = Size(radius * 0.28f, mouthH)
                )
            } else {
                drawArc(
                    color = hair,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius * 0.22f, center.y + radius * 0.18f),
                    size = Size(radius * 0.44f, radius * 0.3f),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
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
    onOpenTasks: () -> Unit
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
                                color = FocusColors.Blue
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

/** بطاقة صغيرة في شاشة اليوم تفتح المساعد — إضافة موضعية فقط. */
@Composable
internal fun AssistantHomeCard(assistantName: String, onOpenAssistant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = FocusColors.Surface),
        shape = RoundedCornerShape(FocusSpacing.radiusCard)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(FocusColors.SurfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = FocusColors.Blue,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "مساعدك $assistantName",
                    style = MaterialTheme.typography.titleSmall,
                    color = FocusColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "اسأله عن وردك ومهامك والصلاة الجاية — وعرّفه عليك عشان يفهمك أكتر.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FocusColors.TextSecondary
                )
            }
            TextButton(onClick = onOpenAssistant) { Text("ابدأ المحادثة") }
        }
    }
}
