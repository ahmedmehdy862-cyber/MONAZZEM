package com.focusstudy.poc

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Retries the optional network side-effects when a connection returns. Uses the platform
 * JobScheduler so no new dependency is needed (the local SQLite database is never touched here):
 * registration, cached team messages and the update feed each keep their own durable state, so a
 * failure simply tries again on the next connected job.
 */
internal class NetworkRetryJob : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            val results = listOf(
                runCatching { UserRegistry.register(applicationContext) }.isSuccess,
                runCatching { TeamMessages.fetch(applicationContext) }.isSuccess,
                runCatching {
                    if (AppUpdateManager.configured()) {
                        AppUpdateManager.check(applicationContext, manual = false)
                    }
                }.isSuccess,
            )
            jobFinished(params, results.any { !it })
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 8207

        /** Schedules a connected-only retry. Safe to call repeatedly. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val builder = JobInfo.Builder(JOB_ID, ComponentName(context.packageName, NetworkRetryJob::class.java.name))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setMinimumLatency(1_000L)
                .setOverrideDeadline(60_000L)
            runCatching { scheduler.schedule(builder.build()) }
        }
    }
}
