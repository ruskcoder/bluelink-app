package com.ruskcoder.messages.imessage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * How far along one attachment's send is.
 *
 * [total] is the file's size on disk and is known before anything starts, so the bubble can say how
 * big the thing it is sending is from the first frame. [sent] only means something while
 * [determinate] is true.
 */
data class TransferProgress(
    /**
     * Bytes that have actually reached Apple.
     *
     * Deliberately not "bytes we have handled". A send is two stages — the file is first copied out
     * of the picker's `content://` URI into the app's outbox, and then uploaded from there — and
     * only the second is the thing anybody is waiting on. Counting the copy as progress would run
     * the ring to full in a second and then leave it sitting there for the whole upload, which is
     * the opposite of what a progress ring is for. The copy shows as zero, because zero is how much
     * has been sent.
     */
    val sent: Long,
    val total: Long,
    /** False only for something with no size to count against — a contact card written as a vCard. */
    val determinate: Boolean,
)

/**
 * Every attachment currently on its way out, keyed by the message row it belongs to.
 *
 * A message's bubble is drawn from the store the instant it is recorded — long before its bytes
 * have gone anywhere — so something has to say which of the bubbles on screen are still in flight.
 * That cannot be a column on the row: it changes many times a second, it is worthless the moment
 * the process dies, and a row that says "40% uploaded" after a restart is a row that will say it
 * forever. It lives here, in memory, for exactly as long as the send does.
 */
class AttachmentTransfers {
    private val state = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val active: StateFlow<Map<String, TransferProgress>> = state

    /** Announces a send, before a single byte has moved, so the bubble comes up already busy. */
    fun begin(messageId: String, totalBytes: Long) {
        state.update { it + (messageId to TransferProgress(0L, totalBytes, determinate = totalBytes > 0)) }
    }

    /**
     * A progress report from the core's uploader.
     *
     * [totalBytes] is trusted over the size read off the picker when it is non-zero: what the
     * uploader is counting against is the staged file it is actually reading, and a provider that
     * lied about the size would otherwise leave the ring stuck short of full or pinned at it.
     */
    fun advance(messageId: String, sentBytes: Long, totalBytes: Long) {
        state.update { current ->
            val existing = current[messageId] ?: return@update current
            val total = if (totalBytes > 0L) totalBytes else existing.total
            current + (messageId to TransferProgress(
                sent = sentBytes.coerceIn(0L, total),
                total = total,
                determinate = total > 0L,
            ))
        }
    }

    /** Sent, or failed — either way the bubble stops being busy. */
    fun finish(messageId: String) {
        state.update { it - messageId }
    }
}
