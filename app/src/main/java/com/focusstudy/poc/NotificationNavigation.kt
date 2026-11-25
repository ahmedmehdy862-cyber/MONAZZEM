package com.focusstudy.poc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Carries notification taps into the live composition.
 *
 * A notification tapped while the app is already alive arrives through onNewIntent, and Compose
 * never re-reads the activity intent on its own, so the tab or session setup would stay unchanged.
 * AppRoot watches these values and reacts whenever they move.
 */
internal object NotificationNavigation {
    var openTasksRequest by mutableStateOf(0)
        private set

    var openUpdatesRequest by mutableStateOf(0)
        private set

    /** The wird reminder opens the reader on today's wird page. */
    var openQuranRequest by mutableStateOf(0)
        private set

    /** The skills/goals reminder opens the skills screen. */
    var openSkillsRequest by mutableStateOf(0)
        private set

    /** The redesigned prayer alert opens the prayer-times screen. */
    var openPrayerTimesRequest by mutableStateOf(0)
        private set

    var requestedTaskId by mutableStateOf<Long?>(null)
        private set

    fun requestTasks() {
        openTasksRequest += 1
    }

    fun requestUpdates() {
        openUpdatesRequest += 1
    }

    fun requestQuran() {
        openQuranRequest += 1
    }

    fun requestSkills() {
        openSkillsRequest += 1
    }

    fun requestPrayerTimes() {
        openPrayerTimesRequest += 1
    }

    fun requestTask(taskId: Long) {
        requestedTaskId = taskId
    }

    fun clearTaskRequest() {
        requestedTaskId = null
    }
}
