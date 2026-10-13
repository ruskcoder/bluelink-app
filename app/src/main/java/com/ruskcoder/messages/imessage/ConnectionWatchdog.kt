package com.ruskcoder.messages.imessage

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ruskcoder.messages.MessagesApp
import java.util.concurrent.TimeUnit

/**
 * Checks every quarter of an hour that the push connection is actually alive, and says so when it
 * is not.
 *
 * The foreground service is sticky, but sticky is a request: an aggressive vendor battery manager, a
 * crash, or the system reclaiming memory under pressure can all leave the process gone and stay
 * gone. From the inside that is indistinguishable from nobody writing, which is the worst way an
 * iMessage client can fail. A periodic job is the one thing the system will reliably wake, and it
 * does two things with that:
 *
 *  - no service running — try to start it again, and if the platform refuses (a background start is
 *    not allowed without the battery exemption), post a notification that opening the app fixes it;
 *  - service running but disconnected for a long while despite a network — force a rebuild of the
 *    socket and post the same notification, since whatever is wrong is not fixing itself.
 */
class ConnectionWatchdog(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        // An install that never connected has nothing to watch over, and would otherwise pester
        // someone who does not use iMessage at all.
        if (!ConnectionMonitor.everConnected(context)) return Result.success()
        val container = (context as MessagesApp).container
        if (runCatching { !container.imessageService.isRegistered() }.getOrDefault(true)) return Result.success()

        if (!PushService.isRunning) {
            Log.w(TAG, "push service is not running; restarting it")
            val restarted = runCatching { PushService.start(context) }
                .onFailure { Log.w(TAG, "the system refused to restart the push service", it) }
                .isSuccess
            if (!restarted) {
                ConnectionAlerts.show(
                    context,
                    "Messages may not be arriving. Open the app to reconnect, and allow it to run in " +
                        "the background so this doesn't happen again.",
                )
            }
            return Result.success()
        }

        val health = container.connectionMonitor.health.value
        val downFor = System.currentTimeMillis() - health.since
        if (health.isProblem && health.networkAvailable && downFor > STALE_MS) {
            Log.w(TAG, "disconnected for ${downFor / 1000}s with a network; forcing a reconnect")
            container.imessageSyncer.reconnect()
            ConnectionAlerts.show(
                context,
                health.detail?.let { "Can't reach iMessage: $it" }
                    ?: "Can't reach iMessage. Messages may be delayed until it reconnects.",
            )
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "ConnectionWatchdog"
        private const val WORK_NAME = "connection-watchdog"

        /** Long enough that a network handover or a slow reconnect never raises the alarm. */
        private const val STALE_MS = 10L * 60 * 1000

        /** Idempotent: an existing schedule is kept rather than restarted on every service start. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ConnectionWatchdog>(15, TimeUnit.MINUTES).build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }.onFailure { Log.w(TAG, "could not schedule the connection watchdog", it) }
        }
    }
}
