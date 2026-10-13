package com.ruskcoder.messages.data.model

import kotlinx.serialization.Serializable

/**
 * Who has received and read one outgoing message, keyed by Apple handle.
 *
 * A group needed this. Apple sends a receipt per participant, but the message carried only a single
 * [DeliveryStatus] — so the first person in a five-person group to open a message flipped it to
 * "Read" for everyone, and there was no way to say who had actually seen it. Keeping the handles
 * makes the aggregate a derived answer ([summarize]) rather than a race between receipts, and lets
 * the thread show a real "Read by 3 of 5".
 *
 * Timestamps are epoch millis and are what the message-info list is ordered by.
 */
@Serializable
data class MessageReceipts(
    val delivered: Map<String, Long> = emptyMap(),
    val read: Map<String, Long> = emptyMap(),
    /** Who has listened to it — audio messages only. Everyone here is in [read] as well. */
    val played: Map<String, Long> = emptyMap(),
) {
    val isEmpty: Boolean get() = delivered.isEmpty() && read.isEmpty() && played.isEmpty()

    /** Reading implies delivery, so a handle in either map counts as reached. */
    fun deliveredCount(): Int = (delivered.keys + read.keys).size

    fun readCount(): Int = read.size

    fun withDelivered(handle: String, at: Long): MessageReceipts =
        // Never demote: a delivery notice can arrive after the read one it precedes.
        if (handle in read) this else copy(delivered = delivered + (handle to at))

    fun withRead(handle: String, at: Long): MessageReceipts =
        copy(delivered = delivered - handle, read = read + (handle to at))

    /**
     * Playing implies reading, so the handle lands in both maps. An earlier read keeps its own
     * time: the message was seen then, and listened to now.
     */
    fun withPlayed(handle: String, at: Long): MessageReceipts =
        copy(
            delivered = delivered - handle,
            read = read + (handle to (read[handle] ?: at)),
            played = played + (handle to at),
        )

    /**
     * The single status to draw on the bubble.
     *
     * [recipientCount] is how many people the message went to — 1 for a one-to-one conversation,
     * where this reduces to exactly the old behaviour. A group only goes READ once everyone has
     * read it, which is the rule WhatsApp's blue ticks follow and the one that makes the tick mean
     * something in a group at all.
     */
    fun summarize(recipientCount: Int): DeliveryStatus = when {
        recipientCount > 0 && played.size >= recipientCount -> DeliveryStatus.PLAYED
        recipientCount > 0 && readCount() >= recipientCount -> DeliveryStatus.READ
        recipientCount > 0 && deliveredCount() >= recipientCount -> DeliveryStatus.DELIVERED
        readCount() > 0 || deliveredCount() > 0 -> DeliveryStatus.DELIVERED
        else -> DeliveryStatus.SENT
    }
}
