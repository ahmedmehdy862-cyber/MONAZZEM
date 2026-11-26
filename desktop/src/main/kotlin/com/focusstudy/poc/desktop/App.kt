package com.focusstudy.poc.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * واجهة ويندوز v1 — عربية RTL (CompositionLocalProvider في Main)، فاتح/داكن.
 *
 * الأقسام: مهامي، عاداتي، وردي، صحتي، مالي، مزاجي، تركيز.
 * لا حجب تطبيقات على ويندوز إطلاقًا (انظر FocusTimer): قسم التركيز مؤقت
 * وتذكير فقط، وتوضح الواجهة ذلك صراحةً.
 *
 * الأرقام العربية المشرقية (١٢٣) تُطبَّع إلى لاتينية قبل التحويل — نفس
 * الخلل الذي أُصلح في أندرويد (toIntOrNull يرفضها بصمت) لا يتكرر هنا.
 */
fun digitsToLatin(value: String): String = buildString {
    for (ch in value) {
        when (ch) {
            in '٠'..'٩' -> append('0' + (ch - '٠'))
            in '۰'..'۹' -> append('0' + (ch - '۰'))
            else -> append(ch)
        }
    }
}

enum class Screen(val label: String) {
    Tasks("مهامي"),
    Habits("عاداتي"),
    Wird("وردي"),
    Health("صحتي"),
    Money("مالي"),
    Mood("مزاجي"),
    Focus("تركيز"),
}

class DesktopAppState(val repo: DesktopRepository) {
    val session = SyncSession()
    val syncClient = SyncClient()
    val syncService = SyncService(repo, syncClient)
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val focusTimer = FocusTimer(scope)

    /**
     * المزامنة التلقائية (انظر AutoSync.kt): سحب عند الفتح، وسحب عند
     * عودة الشبكة، ودفع بعد هدوء التغييرات المحلية، ودوريًا كل 15 دقيقة.
     * زر «مزامنة الآن» يبقى احتياطًا يدويًا يستدعي الدورة نفسها.
     */
    val autoSync = AutoSyncController(
        scope = scope,
        isLinked = { session.isLinked },
        requestSync = { onDone -> syncNow(onDone) },
    )

    var screen by mutableStateOf(Screen.Tasks)
    var dark by mutableStateOf(false)
    var offlineMode by mutableStateOf(false)
    var linked by mutableStateOf(false)
    var dataVersion by mutableStateOf(0)
    var syncBusy by mutableStateOf(false)
    var syncMessage by mutableStateOf<String?>(null)
    var loginError by mutableStateOf<String?>(null)
    var loginBusy by mutableStateOf(false)

    /** profileId محفوظ من جلسة سابقة؛ كلمة المرور ما زالت مطلوبة. */
    val restoredProfileId: Boolean = session.restoreProfileId()

    init {
        focusTimer.onFinished = { start, end ->
            repo.logFocusSession(0L, start, end, "completed")
            refresh()
        }
        // استعادة كاملة من خزنة النظام: ربط صامت + مزامنة فورية عند الفتح.
        // إن غابت كلمة المرور من الخزنة تبقى شاشة الدخول كما كانت.
        if (session.restoreFromVault()) {
            linked = true
            autoSync.start()
        }
    }

    /**
     * يُستدعى بعد كل تغيير محلي (إضافة/تعديل/حذف)؛ يرفع نسخة البيانات
     * ويُبلغ المزامنة التلقائية لتدفع بعد مهلة الهدوء.
     * `triggerAutoSync = false` عند التحديثات الناتجة عن مزامنة نفسها
     * (وإلا أعادت المزامنة جدولة نفسها إلى ما لا نهاية).
     */
    fun refresh(triggerAutoSync: Boolean = true) {
        dataVersion++
        if (triggerAutoSync) autoSync.notifyLocalChange()
    }

    fun linkWithPassphrase(profileId: String, passphrase: String, currentPassphrase: String? = null) {
        if (loginBusy) return
        loginBusy = true
        loginError = null
        scope.launch(Dispatchers.IO) {
            val outcome = syncClient.register(profileId.trim(), passphrase, currentPassphrase)
            if (outcome is SyncOutcome.Ok) {
                session.link(profileId.trim(), passphrase)
                linked = true
                syncMessage = "تم ربط الحساب"
                autoSync.start()
                refresh(triggerAutoSync = false)
            } else {
                loginError = (outcome as? SyncOutcome.Error)?.message ?: "تعذّر ربط الحساب"
            }
            loginBusy = false
        }
    }

    fun enterPassphrase(passphrase: String) {
        if (!session.enterPassphrase(passphrase)) {
            loginError = "كلمة المرور بين 10 و 200 حرف"
            return
        }
        linked = true
        loginError = null
        // بدء المزامنة التلقائية يُطلق سحب الفتح فورًا؛ بلا syncNow مكرر.
        autoSync.start()
    }

    /**
     * دورة مزامنة واحدة (pull ← تطبيق ← push)؛ تعيد true إن بدأت فعلًا
     * وfalse إن كانت مزامنة جارية أو الحساب غير مربوط. `onDone` تُستدعى
     * عند انتهائها مهما كانت النتيجة (لتصريف طلب تلقائي منتظر خلفها).
     */
    fun syncNow(onDone: (() -> Unit)? = null): Boolean {
        if (syncBusy || !session.isLinked) return false
        syncBusy = true
        syncMessage = "جارٍ المزامنة…"
        scope.launch(Dispatchers.IO) {
            try {
                val outcome = syncService.syncNow(session)
                syncMessage = when (outcome) {
                    is SyncOutcome.Ok -> "تمت المزامنة ✓"
                    is SyncOutcome.Error -> outcome.message
                }
            } finally {
                syncBusy = false
                refresh(triggerAutoSync = false)
                onDone?.invoke()
            }
        }
        return true
    }

    fun unlink() {
        autoSync.stop()
        session.unlink()
        linked = false
        offlineMode = false
        syncMessage = null
        refresh(triggerAutoSync = false)
    }

    fun lastSyncText(): String? {
        val last = syncService.lastSync() ?: return null
        val time = java.text.SimpleDateFormat("HH:mm", Locale("ar")).format(java.util.Date(last.at))
        return if (last.ok) "آخر مزامنة: $time" else "آخر محاولة $time: ${last.message}"
    }
}

@Composable
fun App(state: DesktopAppState) {
    if (!state.linked && !state.offlineMode) {
        LoginScreen(state)
        return
    }
    Column(Modifier.fillMaxSize()) {
        // الشريط العلوي: العنوان + حالة المزامنة + تبديل المظهر.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("مُنظِّم", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Text(
                state.syncMessage ?: state.lastSyncText().orEmpty(),
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
            )
            if (state.linked) {
                Button(onClick = { state.syncNow() }, enabled = !state.syncBusy) {
                    Text(if (state.syncBusy) "جارٍ…" else "مزامنة الآن")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { state.unlink() }) { Text("تسجيل الخروج") }
                Spacer(Modifier.width(8.dp))
            }
            OutlinedButton(onClick = { state.dark = !state.dark }) {
                Text(if (state.dark) "فاتح" else "داكن")
            }
        }
        if (!state.linked && state.offlineMode) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(
                    "وضع دون اتصال: بياناتك على هذا الجهاز فقط. اربط حساب المزامنة من شاشة الدخول (أعد التشغيل) لتزامنها مع هاتفك.",
                    Modifier.padding(12.dp),
                    fontSize = 13.sp,
                )
            }
        }
        Row(Modifier.fillMaxSize()) {
            // القائمة الجانبية: أول عنصر في RTL يظهر يمينًا.
            Column(
                Modifier.width(168.dp).fillMaxHeight().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (item in Screen.entries) {
                    val selected = state.screen == item
                    if (selected) {
                        Button(
                            onClick = { state.screen = item },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(item.label) }
                    } else {
                        OutlinedButton(
                            onClick = { state.screen = item },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(item.label) }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                key(state.dataVersion) {
                    when (state.screen) {
                        Screen.Tasks -> TasksSection(state)
                        Screen.Habits -> HabitsSection(state)
                        Screen.Wird -> WirdSection(state)
                        Screen.Health -> HealthSection(state)
                        Screen.Money -> MoneySection(state)
                        Screen.Mood -> MoodSection(state)
                        Screen.Focus -> FocusSection(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(state: DesktopAppState) {
    var profileId by remember {
        mutableStateOf(state.session.profileId.orEmpty())
    }
    var passphrase by remember { mutableStateOf("") }
    var currentPassphrase by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("مُنظِّم — ويندوز", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "سجّل دخولك بحساب المزامنة نفسه في هاتفك لتزامن مهامك بين الجهازين. " +
                    "كلمة المرور تُحفظ في مخزن بيانات اعتماد ويندوز على هذا الجهاز فقط " +
                    "(Credential Manager)، فتتم المزامنة تلقائيًا عند كل فتح — ولا تُحفظ في أي ملف.",
                fontSize = 14.sp,
            )
            OutlinedTextField(
                value = profileId,
                onValueChange = { profileId = it.trim() },
                label = { Text("معرّف الحساب (profileId)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("كلمة مرور المزامنة (10 أحرف فأكثر)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = currentPassphrase,
                onValueChange = { currentPassphrase = it },
                label = { Text("كلمة المرور الحالية — عند تغييرها فقط (اتركها فارغة للربط بنفس كلمة مرور الهاتف)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            state.loginError?.let { Text(it, fontSize = 13.sp) }
            Button(
                onClick = {
                    state.linkWithPassphrase(profileId, passphrase, currentPassphrase.ifBlank { null })
                },
                enabled = !state.loginBusy && profileId.isNotBlank() && passphrase.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.loginBusy) "جارٍ الربط…" else "ربط الحساب / تسجيل الدخول") }
            if (state.restoredProfileId && state.session.profileId != null) {
                OutlinedButton(
                    onClick = { state.enterPassphrase(passphrase) },
                    enabled = passphrase.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("دخول بالحساب المحفوظ") }
            }
            TextButton(
                onClick = { state.offlineMode = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("استخدام دون مزامنة (على هذا الجهاز فقط)") }
            Text(
                "معرّف الحساب (profileId) تجده في تطبيق الهاتف: من شاشة المزامنة. " +
                    "إن لم يكن لديك حساب مزامنة بعد، أنشئه من الهاتف أولًا ثم اربطه هنا.",
                fontSize = 12.sp,
            )
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    "todo" -> "لم تبدأ"
    "doing" -> "قيد التنفيذ"
    "done" -> "مكتملة"
    "postponed" -> "مؤجلة"
    "cancelled" -> "ملغاة"
    else -> status
}

@Composable
private fun TasksSection(state: DesktopAppState) {
    val repo = state.repo
    var title by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("25") }
    var error by remember { mutableStateOf<String?>(null) }
    val tasks = remember { repo.tasks() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("مهامي", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("مهمة جديدة") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = minutes,
                onValueChange = { minutes = digitsToLatin(it).filter { ch -> ch.isDigit() }.take(3) },
                label = { Text("دقائق") },
                singleLine = true,
                modifier = Modifier.width(90.dp),
            )
            Button(onClick = {
                runCatching {
                    repo.addTask(title, (digitsToLatin(minutes).toIntOrNull() ?: 25).coerceIn(1, 720))
                    title = ""
                    error = null
                    state.refresh()
                }.onFailure { error = it.message }
            }) { Text("إضافة") }
        }
        error?.let { Text(it, fontSize = 13.sp) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            items(tasks, key = { it.id }) { task ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = task.isDone,
                        onCheckedChange = {
                            repo.setTaskDone(task.id, it)
                            state.refresh()
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(task.title, fontWeight = FontWeight.Medium)
                        Text(
                            "${task.plannedMinutes} دقيقة • ${statusLabel(task.status)}",
                            fontSize = 12.sp,
                        )
                    }
                    TextButton(onClick = {
                        repo.deleteTask(task.id)
                        state.refresh()
                    }) { Text("حذف") }
                }
            }
        }
    }
}

@Composable
private fun HabitsSection(state: DesktopAppState) {
    val repo = state.repo
    val today = todayKey()
    var name by remember { mutableStateOf("") }
    val habits = remember { repo.habits() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("عاداتي", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("عادة جديدة") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                if (name.isNotBlank()) {
                    repo.addHabit(name)
                    name = ""
                    state.refresh()
                }
            }) { Text("إضافة") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            items(habits, key = { it.id }) { habit ->
                val doneToday = remember { today in repo.habitDoneDays(habit.id, today) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = doneToday,
                        onCheckedChange = {
                            repo.setHabitDone(habit.id, today, it)
                            state.refresh()
                        },
                    )
                    Text(habit.name, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        repo.deleteHabit(habit.id)
                        state.refresh()
                    }) { Text("حذف") }
                }
            }
        }
        Text("علامة اليوم فقط؛ السجل الكامل محفوظ في قاعدة البيانات.", fontSize = 12.sp)
    }
}

@Composable
private fun WirdSection(state: DesktopAppState) {
    val repo = state.repo
    val today = todayKey()
    val settings = remember { repo.wirdSettings() }
    val total = remember { repo.wirdTotalPages() }
    val logged = remember { repo.wirdLogged(today) }
    val nextPage = settings.nextPage(total)
    val lastPage = minOf(nextPage + settings.pagesPerDay - 1, 604)
    val completed = settings.isCompleted(total)
    val span = (604 - settings.startPage + 1).coerceAtLeast(1)
    var startPage by remember(settings) { mutableStateOf(settings.startPage.toString()) }
    var pagesPerDay by remember(settings) { mutableStateOf(settings.pagesPerDay.toString()) }
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("وردي", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("السورة: ${settings.surah}")
        if (completed) {
            Text("🎉 ختمت المصحف كاملًا — من صفحة ${settings.startPage} حتى صفحة 604.", fontWeight = FontWeight.Bold)
        } else {
            Text("صفحة اليوم: $nextPage – $lastPage من 604", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            LinearProgressIndicator(
                progress = total.toFloat() / span,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("سجّلت $total صفحة حتى الآن.", fontSize = 13.sp)
        }
        Button(
            onClick = {
                repo.logWird(today, settings.pagesPerDay, settings.surah)
                state.refresh()
            },
            enabled = !logged && !completed,
        ) { Text(if (logged) "سجّلت ورد اليوم ✓" else "سجّلت ورد اليوم (${settings.pagesPerDay} صفحات)") }
        Text("الإعدادات", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = startPage,
                onValueChange = { startPage = digitsToLatin(it).filter { ch -> ch.isDigit() }.take(3) },
                label = { Text("صفحة البداية") },
                singleLine = true,
                modifier = Modifier.width(130.dp),
            )
            OutlinedTextField(
                value = pagesPerDay,
                onValueChange = { pagesPerDay = digitsToLatin(it).filter { ch -> ch.isDigit() }.take(2) },
                label = { Text("صفحات اليوم") },
                singleLine = true,
                modifier = Modifier.width(130.dp),
            )
            Button(onClick = {
                val start = digitsToLatin(startPage).toIntOrNull()?.coerceAtLeast(1) ?: settings.startPage
                val perDay = digitsToLatin(pagesPerDay).toIntOrNull()?.coerceIn(1, 30) ?: settings.pagesPerDay
                repo.saveWirdSettings(settings.copy(startPage = start, pagesPerDay = perDay))
                state.refresh()
            }) { Text("حفظ") }
        }
        Text("تغيير الإعدادات لا يمسح سجل الورد أبدًا.", fontSize = 12.sp)
    }
}

@Composable
private fun HealthSection(state: DesktopAppState) {
    val repo = state.repo
    val today = todayKey()
    val log = remember { repo.healthLog(today) }
    var weight by remember(log) { mutableStateOf(log?.weight?.toString().orEmpty()) }
    var foodName by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    val entries = remember { repo.calorieEntries(today) }
    val totalCalories = entries.sumOf { it.calories }
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("صحتي", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("اليوم: ${displayDay(today)}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = weight,
                onValueChange = { weight = digitsToLatin(it).filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("الوزن (كجم)") },
                singleLine = true,
                modifier = Modifier.width(150.dp),
            )
            Button(onClick = {
                val current = repo.healthLog(today) ?: HealthLog(today)
                repo.saveHealthLog(current.copy(weight = digitsToLatin(weight).toDoubleOrNull()))
                state.refresh()
            }) { Text("حفظ الوزن") }
        }
        Text("سعرات اليوم: $totalCalories سعرة", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = foodName,
                onValueChange = { foodName = it },
                label = { Text("وجبة / طعام") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = calories,
                onValueChange = { calories = digitsToLatin(it).filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("سعرات") },
                singleLine = true,
                modifier = Modifier.width(110.dp),
            )
            Button(onClick = {
                val kcal = digitsToLatin(calories).toIntOrNull() ?: return@Button
                if (foodName.isBlank() || kcal <= 0) return@Button
                repo.addCalorieEntry(foodName, kcal, today)
                foodName = ""
                calories = ""
                state.refresh()
            }) { Text("إضافة") }
        }
        for (entry in entries) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${entry.name} — ${entry.calories} سعرة", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    repo.deleteCalorieEntry(entry.id)
                    state.refresh()
                }) { Text("حذف") }
            }
        }
    }
}

@Composable
private fun MoneySection(state: DesktopAppState) {
    val repo = state.repo
    val month = monthPrefix()
    var kind by remember { mutableStateOf("expense") }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val transactions = remember { repo.transactionsForMonth(month) }
    val income = transactions.filter { it.kind == "income" }.sumOf { it.amount }
    val expense = transactions.filter { it.kind == "expense" }.sumOf { it.amount }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("مالي", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("الشهر الحالي: $month — دخل ${"%.2f".format(Locale.US, income)} • مصروف ${"%.2f".format(Locale.US, expense)} • الصافي ${"%.2f".format(Locale.US, income - expense)}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { kind = "expense" }) { Text(if (kind == "expense") "● مصروف" else "مصروف") }
            OutlinedButton(onClick = { kind = "income" }) { Text(if (kind == "income") "● دخل" else "دخل") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = digitsToLatin(it).filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("المبلغ") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("الفئة") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("ملاحظة (اختياري)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            val value = digitsToLatin(amount).toDoubleOrNull() ?: return@Button
            if (value <= 0) return@Button
            repo.addTransaction(kind, value, category, note, todayKey())
            amount = ""
            category = ""
            note = ""
            state.refresh()
        }) { Text("إضافة معاملة") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            items(transactions, key = { it.id }) { tx ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${if (tx.kind == "income") "+" else "−"} ${"%.2f".format(Locale.US, tx.amount)} — ${tx.category.ifBlank { "بدون فئة" }} • ${tx.day}",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        repo.deleteTransaction(tx.id)
                        state.refresh()
                    }) { Text("حذف") }
                }
            }
        }
    }
}

@Composable
private fun MoodSection(state: DesktopAppState) {
    val repo = state.repo
    val today = todayKey()
    val todayMood = remember { repo.moodFor(today) }
    val since = java.time.LocalDate.now().minusDays(7).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
    val recent = remember { repo.moodLogsSince(since) }
    val labels = listOf("سيئ جدًا", "سيئ", "عادي", "جيد", "ممتاز")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("مزاجي", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            if (todayMood != null) "مزاج اليوم: ${labels[todayMood - 1]} ($todayMood/5)"
            else "لم تسجّل مزاج اليوم بعد.",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (value in 1..5) {
                if (todayMood == value) {
                    Button(onClick = {
                        repo.logMood(today, value)
                        state.refresh()
                    }) { Text("$value") }
                } else {
                    OutlinedButton(onClick = {
                        repo.logMood(today, value)
                        state.refresh()
                    }) { Text("$value") }
                }
            }
        }
        Text("آخر 7 أيام:", fontWeight = FontWeight.Medium)
        for (entry in recent) {
            Text("${displayDay(entry.day)}: ${labels[entry.mood - 1]} (${entry.mood}/5)")
        }
    }
}

@Composable
private fun FocusSection(state: DesktopAppState) {
    val timer = state.focusTimer
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("تركيز", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        Card(Modifier.fillMaxWidth()) {
            Text(
                "لا يوجد حجب تطبيقات على ويندوز: الحجب في الهاتف يعتمد على Accessibility وVPN، " +
                    "ولا مقابل لهما على سطح المكتب. المؤقت هنا للتذكير فقط — أغلق بنفسك ما يشتتك.",
                Modifier.padding(12.dp),
                fontSize = 13.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (minutes in listOf(15, 25, 45, 60)) {
                if (timer.totalSeconds == minutes * 60) {
                    Button(onClick = { timer.setMinutes(minutes) }) { Text("$minutes د") }
                } else {
                    OutlinedButton(onClick = { timer.setMinutes(minutes) }) { Text("$minutes د") }
                }
            }
        }
        Text(timer.display(), fontSize = 64.sp, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(
            progress = timer.progress,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (timer.running) timer.pause() else timer.start() }) {
                Text(if (timer.running) "إيقاف مؤقت" else "بدء التركيز")
            }
            OutlinedButton(onClick = { timer.reset() }) { Text("إعادة") }
        }
        if (timer.finished) {
            AlertDialog(
                onDismissRequest = { timer.dismissFinished() },
                confirmButton = {
                    TextButton(onClick = { timer.dismissFinished() }) { Text("حسنًا") }
                },
                text = { Text("انتهت جلسة التركيز. أحسنت — خذ استراحة قصيرة قبل الجلسة التالية.") },
            )
        }
    }
}
