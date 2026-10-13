package com.ruskcoder.messages.data.repository

import android.content.Context
import com.ruskcoder.messages.util.appJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A message written now and sent later. */
@Serializable
data class ScheduledSend(
    val id: String,
    val chatId: String,
    val text: String,
    /** When to send it, in epoch millis. */
    val at: Long,
)

private const val PREFS_NAME = "scheduled_sends"
private const val KEY = "scheduledSends.v1"

/**
 * The queue of messages waiting for their moment.
 *
 * `SharedPreferences` rather than a table of its own: this is a handful of short rows read whole
 * every time and never queried, which is the shape settings storage is for — and a message that has
 * not been sent is not part of a conversation's history yet, so it has no business in the messages
 * table where every reader would then have to learn to exclude it.
 *
 * Nothing here sends anything. [com.ruskcoder.messages.imessage.ScheduledSendAlarm] owns the clock,
 * and this owns the list.
 */
object ScheduledSends {
    private val serializer = ListSerializer(ScheduledSend.serializer())

    /** Everything queued, soonest first. */
    fun load(context: Context): List<ScheduledSend> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching { appJson.decodeFromString(serializer, raw) }
            .getOrDefault(emptyList())
            .sortedBy { it.at }
    }

    fun add(context: Context, entry: ScheduledSend): List<ScheduledSend> =
        save(context, load(context) + entry)

    /** Everything queued for one conversation, soonest first. */
    fun forChat(context: Context, chatId: String): List<ScheduledSend> =
        load(context).filter { it.chatId == chatId }

    fun remove(context: Context, id: String): List<ScheduledSend> =
        save(context, load(context).filterNot { it.id == id })

    /** Moves one entry to a new time, leaving its text and its conversation alone. */
    fun reschedule(context: Context, id: String, at: Long): List<ScheduledSend> =
        save(context, load(context).map { if (it.id == id) it.copy(at = at) else it })

    /** Everything whose moment has come, and the queue with those taken out of it. */
    fun takeDue(context: Context, now: Long): Pair<List<ScheduledSend>, List<ScheduledSend>> {
        val all = load(context)
        val (due, waiting) = all.partition { it.at <= now }
        if (due.isNotEmpty()) save(context, waiting)
        return due to waiting
    }

    private fun save(context: Context, entries: List<ScheduledSend>): List<ScheduledSend> {
        val sorted = entries.sortedBy { it.at }
        prefs(context).edit().putString(KEY, appJson.encodeToString(serializer, sorted)).apply()
        return sorted
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
