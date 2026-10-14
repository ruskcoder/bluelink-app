package com.ruskcoder.messages.web

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.ruskcoder.messages.MessagesApp
import kotlinx.coroutines.launch

private const val TAG = "WebKeepAlive"
private const val ACTION_TICK = "com.ruskcoder.messages.WEB_KEEPALIVE"
private const val REQUEST_CODE = 0x5EB1

/**
 * The clock that keeps the relay sockets alive while the phone sleeps.
 *
 * A wakeup alarm is the one timer that still fires with the processor suspended. Every few minutes
 * it runs [WebBridge.tick], which pings each relay socket so the carrier's NAT keeps the mapping,
 * notices one that has quietly died, and reconnects it — rather than finding out only when the
 * browser asks for something and nothing answers.
 *
 * Inexact, like [com.ruskcoder.messages.imessage.ScheduledSendAlarm] and for the same reason:
 * exact alarms are a special permission for alarm clocks. Doze stretches these to a round every
 * nine minutes or so, which is still well inside a typical NAT timeout.
 */
object WebKeepAlive {

    private const val INTERVAL_MS = 3L * 60 * 1000

    fun arm(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                pendingIntent(app),
            )
        }.onFailure { Log.w(TAG, "could not arm the keepalive", it) }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(app))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WebKeepAliveReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class WebKeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TICK) return
        val container = (context.applicationContext as MessagesApp).container
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                container.webBridge.tick()
            } catch (e: Exception) {
                Log.w(TAG, "keepalive round failed", e)
                WebKeepAlive.arm(context)
            } finally {
                pending.finish()
            }
        }
    }
}
