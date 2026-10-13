package com.ruskcoder.messages.data.model

/**
 * A message as the bubbles render it. `time` is the formatted label derived from the stored
 * `createdAt` — the persisted shape lives in `data.db.MessageEntity`.
 */
sealed class Message {
    abstract val id: String
    abstract val mine: Boolean
    abstract val time: String

    /**
     * When the message was sent, in epoch millis — the same value `time` was formatted from.
     *
     * Carried alongside the formatted label rather than instead of it because the thread needs both
     * for different jobs: the label goes on the bubble, and the raw instant is what decides where a
     * day separator falls. Zero means "not from storage" (the settings preview's sample bubbles),
     * and the separator logic skips those rather than dating them to 1970.
     */
    abstract val createdAt: Long

    data class Text(
        override val id: String,
        val text: String,
        /**
         * How [text] is dressed, where any of it is — see [TextRun].
         *
         * Empty for the overwhelming majority of messages, and empty is not the same as "one plain
         * run": the bubble takes the empty case as "draw this as it always was" and never goes near
         * the formatted path. Concatenated, these spell [text] exactly; the string stays the single
         * source of what the message *says*.
         */
        val runs: List<TextRun> = emptyList(),
        /**
         * The send effect this message was sent with, where it was sent with one.
         *
         * Kept on the message rather than as a one-off event because that is what Apple does and
         * what the conversation needs: a bubble effect is part of how the message is drawn every
         * time you see it, and even a screen effect leaves a message that can be replayed.
         */
        val effect: MessageEffect? = null,
        override val mine: Boolean,
        /** Which group member sent it; null on a one-to-one chat and on your own messages. */
        val senderId: String? = null,
        override val time: String,
        override val createdAt: Long = 0L,
        val status: DeliveryStatus? = null,
        /**
         * Delivered while the other end had a Focus on, so it landed without making a sound.
         *
         * Not a delivery state of its own — the message is delivered either way, and this only
         * changes the word under it. Cleared by "Notify Anyway", which is what turns a quiet
         * delivery into an ordinary one after the fact.
         */
        val deliveredQuietly: Boolean = false,
        /** Per-participant receipts; only meaningful on your own messages in a group. */
        val receipts: MessageReceipts? = null,
        val replyTo: ReplyPreview? = null,
        val reactions: List<ReactionSummary>? = null,
        /** Set once the text has been changed after sending. */
        val edited: Boolean = false,
        /**
         * What it said before, oldest first — the log behind the "Edited" label.
         *
         * Empty on a message edited before the app kept one, which is why the label is drawn from
         * [edited] rather than from this: the message *was* rewritten either way, and only the
         * ability to read the versions back depends on when.
         */
        val history: List<String> = emptyList(),
        val verificationFailed: Boolean = false,
        /**
         * Why the send did not happen, when it did not.
         *
         * A message is drawn from the moment it is composed rather than when Apple accepts it, so
         * a bubble on screen is not proof it went anywhere — this is what says so, in place of the
         * receipt it did not earn.
         */
        val error: String? = null,
        /** The card drawn under the words for the link in them, where the sender fetched one. */
        val link: LinkPreview? = null,
    ) : Message()

    data class Call(
        override val id: String,
        override val mine: Boolean,
        override val time: String,
        override val createdAt: Long = 0L,
        val title: String,
        val detail: String,
        val missed: Boolean = false,
    ) : Message()

    /**
     * A conversation event rather than something anybody wrote: a rename, someone joining or
     * leaving, the group photo changing.
     *
     * Kept in the message log rather than applied silently to the chat row because that is what it
     * is — something that happened in the conversation, at a point in its history. Without it a
     * group could rename itself or lose a member with nothing on screen to say so.
     *
     * `mine` records whether you were the one who did it, so the text can say "You" and the row can
     * skip the notification.
     */
    data class System(
        override val id: String,
        override val mine: Boolean,
        override val time: String,
        override val createdAt: Long = 0L,
        val text: String,
    ) : Message()

    /**
     * What "delete for everyone" leaves behind, in place of the message it replaced.
     *
     * [text] and [attachment] are what it used to be, kept rather than blanked so the tombstone can
     * offer to show it. It was delivered to this device before it was taken back; forgetting it is
     * a thing the app would be choosing to do, not something the retraction accomplishes. Nothing
     * displays it without being asked, and a message unsent before this was stored has neither.
     */
    data class Deleted(
        override val id: String,
        override val mine: Boolean,
        override val time: String,
        override val createdAt: Long = 0L,
        val text: String = "",
        /** The picture, clip or file it was, for the kinds that are not words. */
        val attachment: Attachment? = null,
    ) : Message()

    data class Audio(
        override val id: String,
        override val mine: Boolean,
        val senderId: String? = null,
        override val time: String,
        override val createdAt: Long = 0L,
        /** Local file uri of the recorded clip. */
        val uri: String,
        /** Clip length in seconds. */
        val duration: Float,
        /** Normalized (0..1) bar heights capturing the clip's shape, sampled while recording. */
        val waveform: List<Float>,
        /**
         * What the clip says, once somebody has pressed Transcribe on it.
         *
         * Null means nobody has asked. An empty string means they did and there were no words —
         * a different answer, and one the bubble has to be able to tell apart from the first.
         */
        val transcript: String? = null,
        val status: DeliveryStatus? = null,
        /**
         * Delivered while the other end had a Focus on, so it landed without making a sound.
         *
         * Not a delivery state of its own — the message is delivered either way, and this only
         * changes the word under it. Cleared by "Notify Anyway", which is what turns a quiet
         * delivery into an ordinary one after the fact.
         */
        val deliveredQuietly: Boolean = false,
        val receipts: MessageReceipts? = null,
        val replyTo: ReplyPreview? = null,
        val reactions: List<ReactionSummary>? = null,
        val verificationFailed: Boolean = false,
        /**
         * Why the send did not happen, when it did not.
         *
         * A message is drawn from the moment it is composed rather than when Apple accepts it, so
         * a bubble on screen is not proof it went anywhere — this is what says so, in place of the
         * receipt it did not earn.
         */
        val error: String? = null,
    ) : Message()

    /** Anything sent through the composer's "+" panel — photo, file, pin, contact card. */
    data class AttachmentMessage(
        override val id: String,
        override val mine: Boolean,
        val senderId: String? = null,
        override val time: String,
        override val createdAt: Long = 0L,
        val attachment: Attachment,
        val status: DeliveryStatus? = null,
        /**
         * Delivered while the other end had a Focus on, so it landed without making a sound.
         *
         * Not a delivery state of its own — the message is delivered either way, and this only
         * changes the word under it. Cleared by "Notify Anyway", which is what turns a quiet
         * delivery into an ordinary one after the fact.
         */
        val deliveredQuietly: Boolean = false,
        val receipts: MessageReceipts? = null,
        val replyTo: ReplyPreview? = null,
        val reactions: List<ReactionSummary>? = null,
        val verificationFailed: Boolean = false,
        /**
         * Why the send did not happen, when it did not.
         *
         * A message is drawn from the moment it is composed rather than when Apple accepts it, so
         * a bubble on screen is not proof it went anywhere — this is what says so, in place of the
         * receipt it did not earn.
         */
        val error: String? = null,
    ) : Message()
}

/**
 * How long after sending a message can still be rewritten.
 *
 * iMessage's own window. It is the other end that enforces it — an edit sent late is one the
 * recipient's phone declines — so offering the option past this point is offering something that
 * quietly does not happen.
 */
const val EDIT_WINDOW_MS = 15L * 60 * 1000

/**
 * How long after sending a message can still be taken back.
 *
 * iMessage's own window, and a much shorter one than an edit's. As with an edit it is the other end
 * that enforces it — a recall sent late is one the recipient's phone ignores, so the message stays
 * on their screen while this one shows it as unsent. Offering it past this point would be offering
 * something that quietly does not happen, and worse, says it did.
 */
const val UNSEND_WINDOW_MS = 2L * 60 * 1000

/**
 * The file this message carries, as a uri and the name to save it under.
 *
 * Null for everything with nothing behind it to save — words, a tombstone, a call, a location or a
 * contact card, whose "attachment" is a few fields rather than a file — and for one whose bytes
 * have not arrived yet. A picture and a clip suggest no name, because the gallery names those from
 * the file itself; a voice message is named after what it is rather than after the internal id its
 * stored copy carries.
 */
fun Message.savableAttachment(): Pair<String, String?>? = when (this) {
    is Message.Audio -> uri.takeIf { it.isNotBlank() }
        ?.let { it to "Audio Message.${it.substringAfterLast('.', "m4a")}" }
    is Message.AttachmentMessage -> when (val carried = attachment) {
        is Attachment.Image -> carried.uri.takeIf { it.isNotBlank() }?.let { it to null }
        is Attachment.Video -> carried.uri.takeIf { it.isNotBlank() }?.let { it to null }
        is Attachment.File -> carried.uri.takeIf { it.isNotBlank() }?.let { it to carried.name }
        else -> null
    }
    // What an unsent message used to be, where this phone kept it — see [Message.Deleted].
    is Message.Deleted -> when (val carried = attachment) {
        is Attachment.Image -> carried.uri.takeIf { it.isNotBlank() }?.let { it to null }
        is Attachment.Video -> carried.uri.takeIf { it.isNotBlank() }?.let { it to null }
        is Attachment.File -> carried.uri.takeIf { it.isNotBlank() }?.let { it to carried.name }
        else -> null
    }
    else -> null
}

/**
 * Every file this message carries: [savableAttachment], widened so a photo stack gives up all of its
 * pictures rather than none of them.
 */
fun Message.savableAttachments(): List<Pair<String, String?>> {
    val group = (this as? Message.AttachmentMessage)?.attachment as? Attachment.MediaGroup
    if (group == null) return listOfNotNull(savableAttachment())
    return group.items.mapNotNull { item ->
        when (item) {
            is Attachment.Image -> item.uri.takeIf { it.isNotBlank() }?.let { it to null }
            is Attachment.Video -> item.uri.takeIf { it.isNotBlank() }?.let { it to null }
            else -> null
        }
    }
}

/**
 * Whether this message is still yours to rewrite.
 *
 * Messages with no stored time — the settings screen's sample bubbles — are dated to the epoch and
 * would otherwise read as long past editing.
 */
fun Message.editable(now: Long = System.currentTimeMillis()): Boolean =
    mine && this is Message.Text && createdAt > 0 && now - createdAt <= EDIT_WINDOW_MS

/**
 * Whether this message can still be recalled from the other end — see [UNSEND_WINDOW_MS].
 *
 * Only our own, only while it still exists, and only inside the window. A message still going out
 * is a different question — it has not reached anyone, so calling it off is always possible — and
 * the sender answers that one; see `IMessageSender.canUnsend`.
 */
fun Message.unsendable(now: Long = System.currentTimeMillis()): Boolean =
    mine && this !is Message.Deleted && createdAt > 0 && now - createdAt <= UNSEND_WINDOW_MS

/** Kinds that can carry emoji reactions — everything except call logs and tombstones. */
fun Message.canReact(): Boolean =
    this is Message.Text || this is Message.Audio || this is Message.AttachmentMessage

/** Every kind an outgoing message can carry delivery ticks on. */
fun Message.canHaveStatus(): Boolean =
    this !is Message.Call && this !is Message.Deleted && this !is Message.System

fun Message.reactions(): List<ReactionSummary>? = when (this) {
    is Message.Text -> reactions
    is Message.Audio -> reactions
    is Message.AttachmentMessage -> reactions
    else -> null
}

fun Message.status(): DeliveryStatus? = when (this) {
    is Message.Text -> status
    is Message.Audio -> status
    is Message.AttachmentMessage -> status
    else -> null
}

/**
 * Whether this one arrived without making a sound, because the other end had a Focus on.
 *
 * Read alongside [status] rather than folded into it: it is not a fourth state a message can be in
 * — the message is delivered — it is a qualification of that state, and only ever a true one where
 * the status is already `DELIVERED`.
 */
fun Message.deliveredQuietly(): Boolean = when (this) {
    is Message.Text -> deliveredQuietly
    is Message.Audio -> deliveredQuietly
    is Message.AttachmentMessage -> deliveredQuietly
    else -> false
}

/**
 * Whether a failed send is one that can simply be attempted again.
 *
 * Only text. An attachment or a voice message was sent from a staged copy of the file, which is
 * pruned once the attempt finishes — there is nothing left to upload a second time, so offering a
 * retry there would be offering something that cannot work. Deleting it and sending it again is
 * what that case actually needs.
 *
 * Stated as a property of the stored `kind` as well as of the [Message], because the two callers
 * ask from opposite ends: the dialog asks about the message it is describing, and the send path
 * asks about the row it is about to re-send.
 */
fun canRetrySend(kind: String): Boolean = kind == TEXT_KIND || kind == AUDIO_KIND || kind == ATTACHMENT_KIND

fun Message.canRetrySend(): Boolean =
    this is Message.Text || this is Message.Audio || this is Message.AttachmentMessage

/** The stored `kind` of each thing that can be sent, and so of each thing that can fail to be. */
const val TEXT_KIND = "text"
const val AUDIO_KIND = "audio"
const val ATTACHMENT_KIND = "attachment"

/** Why an outgoing message never left, for the kinds that can fail to. */
fun Message.error(): String? = when (this) {
    is Message.Text -> error
    is Message.Audio -> error
    is Message.AttachmentMessage -> error
    else -> null
}

fun Message.receipts(): MessageReceipts? = when (this) {
    is Message.Text -> receipts
    is Message.Audio -> receipts
    is Message.AttachmentMessage -> receipts
    else -> null
}

/** The storage kind of a message that has been taken back. */
const val DELETED_KIND = "deleted"

/** What a reply says in place of a quote whose message has since been unsent. */
const val UNSENT_QUOTE = "Unsent message"

/**
 * The same reply, quoting [quoted] rather than whatever the stored row said it quoted.
 *
 * Every correction a quote needs is made through here, because a quote is the one part of a message
 * that is not a fact about the message itself: it is a *reference*, and it goes stale. Which side of
 * the conversation the quoted message is on isn't stored against the reply at all; the quoted words
 * are stored, and go on reproducing a message that has since been retracted — the one copy of it an
 * unsend could not reach; and which message in a thread is being answered depends on what has been
 * said in that thread since. See `ChatRepository.threadFlow`, which resolves all three together.
 */
fun Message.withQuoted(quoted: ReplyPreview): Message = when (this) {
    is Message.Text -> if (replyTo == null) this else copy(replyTo = quoted)
    is Message.Audio -> if (replyTo == null) this else copy(replyTo = quoted)
    is Message.AttachmentMessage -> if (replyTo == null) this else copy(replyTo = quoted)
    else -> this
}

/** Whether the text has been changed since it was sent — only text can be. */
fun Message.edited(): Boolean = this is Message.Text && edited

fun Message.verificationFailed(): Boolean = when (this) {
    is Message.Text -> verificationFailed
    is Message.Audio -> verificationFailed
    is Message.AttachmentMessage -> verificationFailed
    else -> false
}

/** The send effect a message arrived with, or null for the overwhelming majority of them. */
fun Message.effect(): MessageEffect? = (this as? Message.Text)?.effect

/** How a message's text is formatted, or empty where it is not. */
fun Message.textRuns(): List<TextRun> = (this as? Message.Text)?.runs.orEmpty()

fun Message.replyTo(): ReplyPreview? = when (this) {
    is Message.Text -> replyTo
    is Message.Audio -> replyTo
    is Message.AttachmentMessage -> replyTo
    else -> null
}

fun Message.senderId(): String? = when (this) {
    is Message.Text -> senderId
    is Message.Audio -> senderId
    is Message.AttachmentMessage -> senderId
    else -> null
}

/**
 * The one-line summary stored on the chat row and shown in the chat list. Both stores derive it
 * from the same function so the history reads identically everywhere it's summarized.
 */
fun Message.preview(): String = when (this) {
    is Message.Text -> text
    is Message.Call -> title
    is Message.Deleted -> "This message was deleted"
    is Message.Audio -> "Voice message"
    is Message.AttachmentMessage -> attachment.preview()
    is Message.System -> text
}

data class StoredMessage(val message: Message, val createdAt: Long)

/** A message-body search hit, joined back to the conversation it belongs to. */
data class MessageHit(
    val messageId: String,
    val chatId: String,
    val text: String,
    val createdAt: Long,
    /** Whether we sent it. */
    val mine: Boolean = false,
)

/**
 * Every message belonging to the same reply thread as [id], in conversation order.
 *
 * A thread is the message that started it plus everything hanging off it, however deep. Which is
 * more than "the messages quoting this one": iMessage points a reply at whichever message was
 * being answered, so a reply to a reply is a third link in a chain rather than a second child of
 * the first, and a thread read one level deep loses the end of its own conversation. So the walk
 * goes up to the root first — following each quote to what it quoted until nothing does — and then
 * takes everything whose own walk arrives at that same root.
 *
 * Apple's own wire format uses a single `thread_originator_guid` on every reply, which makes the
 * chain one level deep and the closure and the naive answer identical. Reading it as a tree costs
 * nothing there and is the difference between a whole thread and half of one anywhere the chain
 * runs deeper — a reply we sent while quoting a reply, for one.
 *
 * A quote pointing at a message this conversation doesn't hold — one from before the history we
 * synced — ends the walk where it runs out, so the thread is whatever part of it we can actually
 * show. Returns an empty list for an unknown [id], and a single-message list for one that neither
 * answers anything nor has been answered.
 */
fun threadOf(messages: List<Message>, id: String): List<Message> {
    val byId = messages.associateBy { it.id }
    if (id !in byId) return emptyList()

    /** The oldest message this one's quotes lead back to. Guarded against a cycle in bad data. */
    fun rootOf(start: String): String {
        var current = start
        val seen = mutableSetOf(current)
        while (true) {
            val parent = byId[current]?.replyTo()?.id ?: return current
            if (parent !in byId || !seen.add(parent)) return current
            current = parent
        }
    }

    val root = rootOf(id)
    return messages.filter { it.id == root || rootOf(it.id) == root }
}

/** Whether this message is part of a reply thread — it answers something, or something answers it. */
fun Message.inThread(messages: List<Message>): Boolean = threadOf(messages, id).size > 1

/**
 * The thread each message belongs to, as the id of the message that started it.
 *
 * One pass, not a walk per message: a reply always arrives after what it answers, so by the time
 * one is reached its parent's own root is already known and the chain collapses to a lookup. A
 * quote pointing outside this conversation — a message older than the history we synced — starts a
 * thread of its own rather than being dropped, which is the only honest answer when the message it
 * refers to is not here to be grouped with.
 */
fun threadRoots(messages: List<Message>): Map<String, String> {
    val roots = HashMap<String, String>(messages.size)
    for (message in messages) {
        val parent = message.replyTo()?.id
        roots[message.id] = if (parent != null) roots[parent] ?: message.id else message.id
    }
    return roots
}

/**
 * The last message of every thread that actually is one.
 *
 * What the conversation uses to close a thread off: the message at the end of one wears a tail and
 * keeps a gap under it, so a stretch of back-and-forth reads as a finished exchange rather than
 * running straight into whatever was said next. Nothing else about a reply changes the spacing —
 * a reply mid-thread is grouped by the ordinary run rules like any other message.
 *
 * A thread of one is not a thread: a message nobody answered has nothing to close.
 */
fun threadEndIds(messages: List<Message>): Set<String> {
    val roots = threadRoots(messages)
    val sizes = HashMap<String, Int>()
    val last = HashMap<String, String>()
    for (message in messages) {
        val root = roots[message.id] ?: continue
        sizes[root] = (sizes[root] ?: 0) + 1
        last[root] = message.id
    }
    return last.entries.mapNotNullTo(mutableSetOf()) { (root, id) -> id.takeIf { (sizes[root] ?: 0) > 1 } }
}
