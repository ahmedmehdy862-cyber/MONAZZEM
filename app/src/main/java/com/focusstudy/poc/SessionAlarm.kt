package com.focusstudy.poc

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes the app at the session end time so a locked session still releases its apps even if the
 * process was killed in the meantime. Exact alarms are used when the platform allows them, with an
 * inexact fallback otherwise.
 */
object SessionAlarm {

    private const val REQUEST_CODE = 73

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, SessionEndReceiver::class.java).setAction(SessionEndReceiver.ACTION_END),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(context: Context, atMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        NotificationMaintenance.schedule(
            context = context,
            manager = manager,
            atMillis = atMillis,
            pending = pending(context),
            kind = "session_end"
        )
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        manager.cancel(pending(context))
    }
}

class SessionEndReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_END) return
        SessionEngine.refresh(context)
    }

    companion object {
        const val ACTION_END = "com.focusstudy.poc.action.SESSION_END"
    }
}
