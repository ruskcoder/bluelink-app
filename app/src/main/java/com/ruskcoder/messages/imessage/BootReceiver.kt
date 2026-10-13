package com.ruskcoder.messages.imessage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ruskcoder.messages.MessagesApp
import kotlinx.coroutines.launch

/**
 * Reconnects to Apple after the device restarts.
 *
 * iMessage delivery is inbound over a socket this app holds open, so nothing arrives while the
 * process is dead — and without this the app stays silent after every reboot until the user happens
 * to open it, which looks exactly like messages being lost.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val container = (context.applicationContext as MessagesApp).container
        val appContext = context.applicationContext

        // Alarms do not survive a reboot but the queue behind them does, so anything still waiting
        // to be sent needs its alarm put back. Cheap and synchronous — it reads one preference.
        ScheduledSendAlarm.rearm(appContext)

        // Starting the service is cheap, but starting it for an install that never signed in would
        // pin a permanent notification on someone who does not use iMessage. The registration check
        // touches disk, so it cannot run on the main thread here.
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                if (container.imessageService.isRegistered()) {
                    PushService.start(appContext)
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not restart push after boot", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
