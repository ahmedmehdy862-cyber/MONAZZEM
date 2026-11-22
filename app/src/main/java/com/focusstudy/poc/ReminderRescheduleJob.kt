package com.focusstudy.poc

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * Safety net for phones that never deliver BOOT_COMPLETED to the app.
 *
 * Some ROMs (MIUI/HyperOS here) hold the boot broadcast as "pending offload" and never
 * start the process, so a persisted JobScheduler job rebuilds the reminder schedule on
 * its own. Persisted jobs survive reboot and do not depend on the app being launched.
 */
class ReminderRescheduleJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext
        executor.execute {
            try {
                NotificationMaintenance.rescheduleAll(app)
                Log.i(TAG, "reminders rescheduled by job ${params?.jobId}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val TAG = "FocusJob"
        private const val JOB_ID = 4741
        private const val INTERVAL_MS = 6 * 60 * 60 * 1000L
        private val executor = Executors.newSingleThreadExecutor()

        /** Idempotent: scheduling the same id twice just updates the existing job. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ReminderRescheduleJob::class.java))
                .setPersisted(true)
                .setPeriodic(INTERVAL_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            runCatching { scheduler.schedule(job) }
                .onFailure { Log.w(TAG, "could not schedule reschedule job", it) }
        }
    }
}
