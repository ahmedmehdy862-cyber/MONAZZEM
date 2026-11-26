package com.focusstudy.poc.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * المزامنة التلقائية — طول ما فيه نت، البيانات تتحدث بلا زر يدوي
 * (العقد ~/workspace/windows-sync-contract.md §5: مزامنة متسلسلة لكل
 * حساب؛ دورة syncNow نفسها في SyncService لا تتغير).
 *
 * المحفزات الأربعة:
 *  1. عند فتح التطبيق — سحب فوري إن كان الحساب مربوطًا.
 *  2. عند عودة الشبكة — سحب فوري عند انتقال دون اتصال ← متصل.
 *  3. عند تغيير محلي — دفع بعد مهلة قصيرة (debounce) تهدأ فيها التغييرات.
 *  4. دوريًا أثناء الفتح — كل 15 دقيقة منذ آخر مزامنة ناجحة.
 *
 * زر «مزامنة الآن» يبقى كاحتياط يدوي؛ والمزامنة نفسها متسلسلة دائمًا:
 * طلب جديد أثناء مزامنة جارية يُصفّ خلفها ولا يعمل بالتوازي أبدًا.
 */

data class AutoSyncConfig(
    /** مهلة هدوء التغييرات المحلية قبل الدفع. */
    val debounceMs: Long = 5_000,
    /** الفاصل الدوري بين مزامنتين أثناء الفتح. */
    val periodicMs: Long = 15 * 60_000,
    /** الفاصل بين فحوصات الشبكة الخفيفة. */
    val networkPollMs: Long = 30_000,
)

/**
 * سياسة المحفزات — منطق خالص بلا كوروتينات ولا شبكة، يُختبر بساعة مزيفة.
 * كل الأوقات مللي ثانية epoch؛ `nowMs` يحقنها المتصل.
 */
class AutoSyncTriggers(private val config: AutoSyncConfig = AutoSyncConfig()) {
    private var lastSyncFinishedAtMs: Long? = null
    private var lastChangeAtMs: Long? = null
    private var lastOnline: Boolean? = null

    /** عند الفتح: مزامنة فورية إن كان الحساب مربوطًا فقط. */
    fun onStartup(linked: Boolean): Boolean = linked

    /** سجّل تغييرًا محليًا (إضافة/تعديل/حذف) في هذا الوقت. */
    fun onLocalChange(nowMs: Long) {
        lastChangeAtMs = nowMs
    }

    /** هل هدأت التغييرات منذ [config.debounceMs]؟ */
    fun isDebounceDue(nowMs: Long): Boolean {
        val changedAt = lastChangeAtMs ?: return false
        return nowMs - changedAt >= config.debounceMs
    }

    /**
     * انتقال الشبكة: يعيد true عند دون اتصال ← متصل فقط؛
     * أول فحص (بلا حالة سابقة) لا يُطلق مزامنة — الفتح يغطيه.
     */
    fun onNetworkChange(online: Boolean): Boolean {
        val previous = lastOnline
        lastOnline = online
        return previous == false && online
    }

    /** سجّل انتهاء مزامنة (ناجحة أو فاشلة — كلتاهما تعيد ضبط الدوري). */
    fun onSyncFinished(nowMs: Long) {
        lastSyncFinishedAtMs = nowMs
        lastChangeAtMs = null
    }

    /** هل مرّ [config.periodicMs] منذ آخر مزامنة منتهية؟ */
    fun isPeriodicDue(nowMs: Long): Boolean {
        val last = lastSyncFinishedAtMs ?: return false
        return nowMs - last >= config.periodicMs
    }
}

/**
 * يربط [AutoSyncTriggers] بكوروتينات التطبيق و`syncNow` الفعلية.
 *
 * @param requestSync تبدأ مزامنة وتعيد true إن بدأت فعلًا (false إن كانت
 *   مزامنة جارية أو الحساب غير مربوط)؛ تُستدعى `onDone` عند انتهائها
 *   مهما كانت النتيجة، فيُصرَّف أي طلب كان منتظرًا خلفها.
 * @param probeOnline فحص اتصال خفيف (افتراضيًا: اتصال TCP بمضيف المزامنة
 *   على 443 بمهلة 3 ثوانٍ)؛ يُحقن في الاختبارات.
 */
class AutoSyncController(
    private val scope: CoroutineScope,
    private val config: AutoSyncConfig = AutoSyncConfig(),
    private val isLinked: () -> Boolean,
    private val requestSync: (onDone: () -> Unit) -> Boolean,
    private val probeOnline: () -> Boolean = ::defaultProbeOnline,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val triggers = AutoSyncTriggers(config)
    private var started = false
    private var syncRunning = false
    private var pendingAfterSync = false
    private var debounceJob: Job? = null
    private var monitorJob: Job? = null

    /** يبدأ المحفزات: مزامنة فورية عند الفتح إن كان الحساب مربوطًا. */
    fun start() {
        if (started) return
        started = true
        if (triggers.onStartup(isLinked())) requestSyncInternal()
        monitorJob = scope.launch {
            while (isActive) {
                delay(config.networkPollMs)
                checkNetworkAndPeriodic()
            }
        }
    }

    fun stop() {
        started = false
        monitorJob?.cancel()
        debounceJob?.cancel()
        monitorJob = null
        debounceJob = null
        pendingAfterSync = false
    }

    /** أُبلغ عن تغيير محلي: ادفع بعد [AutoSyncConfig.debounceMs] هدوء. */
    fun notifyLocalChange() {
        if (!started || !isLinked()) return
        triggers.onLocalChange(clock())
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(config.debounceMs)
            if (isLinked() && triggers.isDebounceDue(clock())) requestSyncInternal()
        }
    }

    /** فحص شبكة واحد (يستدعيه المراقب الدوري؛ مكشوف للاختبار). */
    fun checkNetworkAndPeriodic() {
        if (!started || !isLinked()) return
        val online = runCatching { probeOnline() }.getOrDefault(false)
        if (triggers.onNetworkChange(online)) {
            requestSyncInternal()
            return
        }
        if (online && triggers.isPeriodicDue(clock())) requestSyncInternal()
        // طلب منتظر من مزامنة يدوية جارية (بلا onDone يصرّفه): أعد المحاولة هنا.
        if (online && pendingAfterSync && !syncRunning) {
            pendingAfterSync = false
            requestSyncInternal()
        }
    }

    private fun requestSyncInternal() {
        if (!isLinked()) return
        if (syncRunning) {
            pendingAfterSync = true
            return
        }
        syncRunning = true
        val startedSync = requestSync {
            syncRunning = false
            triggers.onSyncFinished(clock())
            if (pendingAfterSync) {
                pendingAfterSync = false
                requestSyncInternal()
            }
        }
        // لم تبدأ (مزامنة يدوية جارية مثلًا): أبقِها منتظرة للفحص الدوري.
        if (!startedSync) {
            syncRunning = false
            pendingAfterSync = true
        }
    }
}

/** فحص اتصال خفيف: مقبس TCP إلى مضيف المزامنة (443) بمهلة 3 ثوانٍ. */
fun defaultProbeOnline(): Boolean {
    val host = runCatching { URI.create(SyncProtocol.BASE_URL).host }.getOrNull()
        ?: return false
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, 443), 3_000)
            socket.isConnected
        }
    } catch (_: Exception) {
        false
    }
}
