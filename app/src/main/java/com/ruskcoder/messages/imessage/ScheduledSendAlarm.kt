package com.ruskcoder.messages.imessage

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ruskcoder.messages.MessagesApp
import com.ruskcoder.messages.data.repository.ScheduledSend
import com.ruskcoder.messages.data.repository.ScheduledSends
import com.ruskcoder.messages.util.makeId
import kotlinx.coroutines.launch

private const val TAG = "ScheduledSend"
private const val ACTION_FIRE = "com.ruskcoder.messages.SCHEDULED_SEND"

/** One alarm for the whole queue, re-pointed at whatever is next. */
private const val REQUEST_CODE = 0x5C4E

/**
 * The clock behind [ScheduledSends].
 *
 * A single alarm is armed for the *soonest* pending message rather than one per message: the queue
 * is re-read whenever it fires, so everything already due goes out together and the alarm is then
 * re-pointed at whatever is left. One alarm is also one thing to restore after a reboot.
 *
 * Deliberately an inexact alarm. Exact ones need `SCHEDULE_EXACT_ALARM`, which Android 13 treats as
 * a special permission for alarm clocks and calendar reminders — a message going out a minute or
 * two either side of its time is not that, and asking for the permission to buy that minute would
 * be the wrong trade.
 */
object ScheduledSendAlarm {

    /** Queues [text] for [at], and makes sure the alarm covers it. */
    fun schedule(context: Context, chatId: String, text: String, at: Long) {
        val app = context.applicationContext
        ScheduledSends.add(app, ScheduledSend(id = makeId(), chatId = chatId, text = text, at = at))
        rearm(app)
    }

    /** Takes one back out of the queue before it goes. */
    fun cancel(context: Context, id: String) {
        val app = context.applicationContext
        ScheduledSends.remove(app, id)
        rearm(app)
    }

    /**
     * Moves one to a different time.
     *
     * Re-arming afterwards is the whole point: the single alarm is set for whatever is soonest, and
     * moving an entry can change which one that is in either direction.
     */
    fun reschedule(context: Context, id: String, at: Long) {
        val app = context.applicationContext
        ScheduledSends.reschedule(app, id, at)
        rearm(app)
    }

    /** Points the alarm at the next pending message, or cancels it when there is none. */
    fun rearm(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val next = ScheduledSends.load(app).minByOrNull { it.at }
        val intent = pendingIntent(app, mutable = false)

        if (next == null) {
            alarms.cancel(intent)
            return
        }
        // `allowWhileIdle` so a phone that has been sitting in a pocket all night still sends the
        // message someone queued for the morning. Doze batches these; it does not drop them.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, intent)
    }

    private fun pendingIntent(context: Context, mutable: Boolean): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, ScheduledSendReceiver::class.java).setAction(ACTION_FIRE),
            flags,
        )
    }
}

/**
 * Sends whatever the queue says is due.
 *
 * Takes everything at or before now rather than only the one the alarm was set for: an alarm that
 * slipped in Doze can arrive after several have come due, and sending them in one pass keeps their
 * order rather than firing them a batch apart.
 */
class ScheduledSendReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val app = context.applicationContext
        val container = (app as MessagesApp).container
        val (due, _) = ScheduledSends.takeDue(app, System.currentTimeMillis())
        if (due.isEmpty()) {
            ScheduledSendAlarm.rearm(app)
            return
        }

        val pending = goAsync()
        container.applicationScope.launch {
            try {
                due.forEach { entry ->
                    val identity = container.chatRepository.identityOf(entry.chatId)
                    if (identity == null) {
                        // The conversation was deleted between queueing and sending. Nothing to
                        // send it to, and re-creating it from a chat id we no longer have a name
                        // for would be worse than dropping it.
                        Log.w(TAG, "dropping ${entry.id}: chat ${entry.chatId} is gone")
                        return@forEach
                    }
                    container.imessageSender.sendText(entry.chatId, identity, entry.text)
                }
            } catch (e: Exception) {
                Log.w(TAG, "scheduled send failed", e)
            } finally {
                ScheduledSendAlarm.rearm(app)
                pending.finish()
            }
        }
    }
}
