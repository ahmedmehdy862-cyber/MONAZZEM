package com.focusstudy.poc.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * مؤقت تركيز — عدّ تنازلي + تذكير عند الانتهاء فقط.
 *
 * لا حجب تطبيقات على ويندوز إطلاقًا: الحجب في أندرويد يعتمد على
 * AccessibilityService وVpnService، ولا مقابل لهما على سطح المكتب.
 * الجلسة المكتملة تُسجَّل محليًا في جدول sessions (للسجل فقط).
 */
class FocusTimer(private val scope: CoroutineScope) {
    var totalSeconds by mutableStateOf(25 * 60)
        private set
    var remainingSeconds by mutableStateOf(25 * 60)
        private set
    var running by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set

    private var job: Job? = null
    private var startedAt: Long = 0L
    var onFinished: ((startedAt: Long, endsAt: Long) -> Unit)? = null

    val progress: Float get() = if (totalSeconds <= 0) 0f else remainingSeconds.toFloat() / totalSeconds

    fun setMinutes(minutes: Int) {
        if (running) return
        totalSeconds = minutes.coerceIn(1, 240) * 60
        remainingSeconds = totalSeconds
        finished = false
    }

    fun start() {
        if (running) return
        if (remainingSeconds <= 0) remainingSeconds = totalSeconds
        running = true
        finished = false
        startedAt = System.currentTimeMillis() - (totalSeconds - remainingSeconds) * 1000L
        job = scope.launch {
            while (remainingSeconds > 0 && running) {
                delay(1000)
                if (!running) break
                remainingSeconds--
            }
            if (remainingSeconds <= 0 && running) {
                running = false
                finished = true
                onFinished?.invoke(startedAt, System.currentTimeMillis())
            }
        }
    }

    fun pause() {
        running = false
        job?.cancel()
        job = null
    }

    fun reset() {
        pause()
        remainingSeconds = totalSeconds
        finished = false
    }

    fun dismissFinished() {
        finished = false
    }

    fun display(): String {
        val m = remainingSeconds / 60
        val s = remainingSeconds % 60
        return "%02d:%02d".format(m, s)
    }
}
