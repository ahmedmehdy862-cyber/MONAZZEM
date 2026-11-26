package com.focusstudy.poc.desktop

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات المزامنة التلقائية — منطق خالص + ربط الكوروتينات.
 *
 * تعمل على Linux في CI بلا ويندوز وبلا شبكة: الساعة مزيفة في
 * [AutoSyncTriggers]، والشبكة والمزامنة محقونتان في [AutoSyncController].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoSyncTest {

    private val config = AutoSyncConfig(
        debounceMs = 5_000,
        periodicMs = 15 * 60_000,
        networkPollMs = 30_000,
    )

    // ---- AutoSyncTriggers: منطق خالص ----

    @Test
    fun startupSyncsOnlyWhenLinked() {
        val triggers = AutoSyncTriggers(config)
        assertTrue(triggers.onStartup(linked = true))
        assertFalse(triggers.onStartup(linked = false))
    }

    @Test
    fun debounceWaitsForQuietPeriod() {
        val triggers = AutoSyncTriggers(config)
        triggers.onLocalChange(nowMs = 1_000)
        assertFalse(triggers.isDebounceDue(nowMs = 1_000 + 4_999))
        assertTrue(triggers.isDebounceDue(nowMs = 1_000 + 5_000))
    }

    @Test
    fun debounceResetsOnNewChange() {
        val triggers = AutoSyncTriggers(config)
        triggers.onLocalChange(nowMs = 0)
        triggers.onLocalChange(nowMs = 4_000)
        assertFalse(triggers.isDebounceDue(nowMs = 8_999))
        assertTrue(triggers.isDebounceDue(nowMs = 9_000))
    }

    @Test
    fun noDebounceWithoutLocalChange() {
        assertFalse(AutoSyncTriggers(config).isDebounceDue(nowMs = 100_000))
    }

    @Test
    fun networkReturnTriggersOncePerTransition() {
        val triggers = AutoSyncTriggers(config)
        // أول فحص بلا حالة سابقة لا يُطلق (الفتح يغطيه).
        assertFalse(triggers.onNetworkChange(online = false))
        assertFalse(triggers.onNetworkChange(online = false))
        // دون اتصال ← متصل: يُطلق مرة واحدة فقط.
        assertTrue(triggers.onNetworkChange(online = true))
        assertFalse(triggers.onNetworkChange(online = true))
        // انقطاع ثم عودة: يُطلق من جديد.
        assertFalse(triggers.onNetworkChange(online = false))
        assertTrue(triggers.onNetworkChange(online = true))
    }

    @Test
    fun periodicDueOnlyAfterIntervalSinceLastSync() {
        val triggers = AutoSyncTriggers(config)
        // بلا مزامنة سابقة: الدوري لا يُطلق (سحب الفتح يغطي البداية).
        assertFalse(triggers.isPeriodicDue(nowMs = 1_000_000))
        triggers.onSyncFinished(nowMs = 0)
        assertFalse(triggers.isPeriodicDue(nowMs = 15 * 60_000 - 1))
        assertTrue(triggers.isPeriodicDue(nowMs = 15 * 60_000))
    }

    @Test
    fun syncFinishedClearsPendingLocalChange() {
        val triggers = AutoSyncTriggers(config)
        triggers.onLocalChange(nowMs = 0)
        triggers.onSyncFinished(nowMs = 100)
        assertFalse(triggers.isDebounceDue(nowMs = 100_000))
    }

    // ---- AutoSyncController: ربط الكوروتينات ----
    //
    // الساعة مزيفة وتتقدم مع الوقت الافتراضي لـ runTest؛ وإلا فحص الدوري
    // (15 دقيقة) لن يُطلق أبدًا لأن الساعة الحقيقية لا تتقدم.

    private class FakeClock(var nowMs: Long = 1_000_000L) {
        fun advance(ms: Long) {
            nowMs += ms
        }
    }

    private fun TestScope.makeController(
        clock: FakeClock,
        linked: Boolean = true,
        online: () -> Boolean = { true },
        starts: MutableList<Long>,
        holdDone: Boolean = false,
    ): AutoSyncController {
        controllerHeldDone = null
        return AutoSyncController(
            scope = this,
            config = config,
            isLinked = { linked },
            requestSync = { onDone ->
                starts.add(clock.nowMs)
                if (holdDone) {
                    // تُستدعى المحجوزة من الاختبار عبر controllerHeldDone.
                    controllerHeldDone = onDone
                    true
                } else {
                    onDone()
                    true
                }
            },
            probeOnline = online,
            clock = { clock.nowMs },
        )
    }

    // تُحفظ هنا لأن لامدا requestSync لا تستطيع إرجاعها للاختبار مباشرة.
    private var controllerHeldDone: (() -> Unit)? = null

    private suspend fun TestScope.advanceClock(clock: FakeClock, ms: Long) {
        clock.advance(ms)
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun controllerSyncsImmediatelyOnStartWhenLinked() = runTest {
        val starts = mutableListOf<Long>()
        val clock = FakeClock()
        val controller = makeController(clock, starts = starts)
        controller.start()
        assertEquals(listOf(clock.nowMs), starts)
        controller.stop()
    }

    @Test
    fun controllerDoesNotSyncOnStartWhenNotLinked() = runTest {
        val starts = mutableListOf<Long>()
        val clock = FakeClock()
        val controller = makeController(clock, linked = false, starts = starts)
        controller.start()
        advanceClock(clock, 60_000)
        assertEquals(emptyList<Long>(), starts)
        controller.stop()
    }

    @Test
    fun localChangeDebouncesAndCoalesces() = runTest {
        val starts = mutableListOf<Long>()
        val clock = FakeClock()
        val controller = makeController(clock, starts = starts)
        controller.start()
        assertEquals(1, starts.size) // سحب الفتح

        controller.notifyLocalChange()
        advanceClock(clock, 4_000)
        assertEquals(1, starts.size)

        controller.notifyLocalChange() // تغيير جديد يعيد المهلة
        advanceClock(clock, 4_000)
        assertEquals(1, starts.size)

        advanceClock(clock, 1_000)
        assertEquals(2, starts.size) // مزامنة واحدة مدموجة بعد الهدوء
        controller.stop()
    }

    @Test
    fun syncOnNetworkReturnOnly() = runTest {
        var online = false
        val starts = mutableListOf<Long>()
        val clock = FakeClock()
        val controller = makeController(clock, online = { online }, starts = starts)
        controller.start()
        assertEquals(1, starts.size)

        advanceClock(clock, 30_000) // فحص: دون اتصال — بلا مزامنة
        assertEquals(1, starts.size)

        online = true
        advanceClock(clock, 30_000) // فحص: عودة الشبكة — مزامنة
        assertEquals(2, starts.size)

        advanceClock(clock, 30_000) // فحص: ما زال متصلًا — بلا مزامنة جديدة
        assertEquals(2, starts.size)
        controller.stop()
    }

    @Test
    fun periodicSyncWhileOpen() = runTest {
        val starts = mutableListOf<Long>()
        val clock = FakeClock()
        val controller = makeController(clock, starts = starts)
        controller.start()
        assertEquals(1, starts.size)

        advanceClock(clock, 14 * 60_000)
        assertEquals(1, starts.size)

        advanceClock(clock, 60_000) // 15 دقيقة منذ آخر مزامنة
        assertEquals(2, starts.size)
        controller.stop()
    }

    @Test
    fun syncRequestsSerializeInsteadOfOverlapping() = runTest {
        val starts = mutableListOf<Long>()
        val clock = FakeClock()
        val controller = makeController(clock, starts = starts, holdDone = true)
        controller.start() // مزامنة الفتح معلّقة (onDone محجوزة)
        assertEquals(1, starts.size)

        controller.notifyLocalChange()
        advanceClock(clock, 5_000)
        assertEquals(1, starts.size) // طلب منتظر خلف الجارية، لا توازي

        controllerHeldDone?.invoke() // انتهاء الجارية يصرّف المنتظر
        runCurrent()
        assertEquals(2, starts.size)
        controller.stop()
    }
}
