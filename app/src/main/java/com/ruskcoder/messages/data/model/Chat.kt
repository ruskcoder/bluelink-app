package com.ruskcoder.messages.data.model

/**
 * A conversation as it is stored. `preview` and `updatedAt` are denormalized copies of the last
 * message so the chat list can render without touching the messages table.
 */
data class ChatRecord(
    val id: String,
    val name: String,
    val color: String,
    val initials: String,
    val group: Boolean,
    val muted: Boolean,
    val unread: Int,
    val preview: String,
    val updatedAt: Long,
    /** Per-conversation color scheme; `DEFAULT` means "follow the app's bubble style". */
    val theme: ChatThemeKey,
    /** The hue the scheme is derived from, meaningful only when [theme] is `CUSTOM`. */
    val themeHue: Float = ChatThemeKey.DEFAULT_HUE,
    /** Only meaningful for a group; a one-to-one chat carries an empty list. */
    val members: List<GroupMember>,
    val pinned: Boolean = false,
    /** Address-book photo for the person on the other end; null for a group or an unknown number. */
    val photoUri: String? = null,
    /** The shape of the newest message, for the row's preview line and its tick. */
    val last: LastMessage? = null,
    /** When a snooze runs out, in epoch millis; 0 for "not snoozed". */
    val snoozedUntil: Long = 0,
    /** A picture behind this conversation only, in place of the app's own wallpaper. */
    val wallpaperUri: String? = null,
    /**
     * This conversation's own say on the two things you tell the other end about yourself.
     *
     * Null means nobody has touched it here, so the global setting applies — and follows it when it
     * changes. A value means it was set by hand on this conversation and the global switch no longer
     * reaches it, until it is reset back to null. Read through [readReceiptsOn]/[typingIndicatorsOn].
     */
    val readReceipts: Boolean? = null,
    val typingIndicators: Boolean? = null,
    val blocked: Boolean = false,
    /**
     * Whether the other end has a Focus on that we are not an exception to.
     *
     * Always false in a group and on anything that is not iMessage: Apple shares a Focus with one
     * person at a time, and a conversation with five people in it has no single answer.
     */
    val notifsSilenced: Boolean = false,
    /** When we last broke through that Focus, in epoch millis; 0 for never. */
    val dateNotifiedAnyways: Long = 0,
    /** Put away in Archived; see `ChatEntity.archived`. */
    val archived: Boolean = false,
)

/**
 * What the chat list needs to know about a conversation's newest message, beyond its text.
 *
 * `preview` on its own could not say who wrote it, so a group's last line was unattributable; nor
 * what kind it was, so a photo read as the word "Photo"; nor how far it had got, so your own last
 * message carried no tick.
 */
data class LastMessage(
    val mine: Boolean,
    /** Storage kind — `text`, `attachment`, `audio`, `call`, `deleted`, `system`. */
    val kind: String,
    val status: DeliveryStatus?,
    /** The group member who sent it, resolved against the roster; null in a one-to-one. */
    val senderName: String?,
)

/**
 * The identity half of a chat: what we know before any message exists. `theme` is deliberately
 * absent — it belongs to the conversation's own history and survives re-opening it.
 */
data class ChatIdentity(
    val id: String,
    val name: String,
    val color: String,
    val initials: String,
    val group: Boolean,
    val members: List<GroupMember>? = null,
    val photoUri: String? = null,
)

/**
 * A conversation as the chat list renders it. `time` is a formatted label derived from the
 * stored `updatedAt`, computed on read rather than persisted.
 */
data class Chat(
    val id: String,
    val name: String,
    val preview: String,
    val time: String,
    val color: String,
    val initials: String,
    val unread: Int,
    val group: Boolean,
    val muted: Boolean = false,
    val theme: ChatThemeKey,
    val themeHue: Float = ChatThemeKey.DEFAULT_HUE,
    val members: List<GroupMember>,
    val pinned: Boolean = false,
    val photoUri: String? = null,
    val last: LastMessage? = null,
    val snoozedUntil: Long = 0,
    val wallpaperUri: String? = null,
    /** Null follows the global setting — see [ChatRecord.readReceipts]. */
    val readReceipts: Boolean? = null,
    val typingIndicators: Boolean? = null,
    val blocked: Boolean = false,
    /**
     * Whether the other end has a Focus on that we are not an exception to.
     *
     * Always false in a group and on anything that is not iMessage: Apple shares a Focus with one
     * person at a time, and a conversation with five people in it has no single answer.
     */
    val notifsSilenced: Boolean = false,
    /** When we last broke through that Focus, in epoch millis; 0 for never. */
    val dateNotifiedAnyways: Long = 0,
    /** Put away in Archived, out of the main list until someone writes in it. */
    val archived: Boolean = false,
)

/**
 * Whether this conversation is silent right now — muted outright, or inside a snooze that has not
 * yet run out.
 *
 * A snooze is stored as the instant it ends rather than as a flag, so "is it snoozed" is a question
 * about the clock and has to be asked at the moment it matters, not cached.
 */
fun Chat.silenced(now: Long = System.currentTimeMillis()): Boolean = muted || snoozedUntil > now

/** Whether read receipts go out for this conversation: its own say if it has one, else the app's. */
fun Chat.readReceiptsOn(settings: Settings): Boolean = readReceipts ?: settings.readReceipts

/** Whether typing indicators go out for this conversation: its own say if it has one, else the app's. */
fun Chat.typingIndicatorsOn(settings: Settings): Boolean = typingIndicators ?: settings.typingIndicators
