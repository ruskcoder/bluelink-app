package com.ruskcoder.messages.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.StickerPlacement
import com.ruskcoder.messages.data.model.canHaveStatus
import com.ruskcoder.messages.data.model.receipts
import com.ruskcoder.messages.data.model.replyTo
import com.ruskcoder.messages.data.model.senderId
import com.ruskcoder.messages.data.model.status
import com.ruskcoder.messages.data.model.threadEndIds
import com.ruskcoder.messages.data.model.threadRoots
import com.ruskcoder.messages.data.model.withoutPollUpdates
import com.ruskcoder.messages.util.isSameDay
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Everything a conversation lays out, worked out from its messages: which bubbles group into runs,
 * where tails and faces go, which replies draw connectors to what, where the date and unread rules
 * fall, and the order the reversed list shows it all in.
 *
 * Moved out of `MessageThread` and made a pure function of the messages, for two reasons. It was
 * ten passes over the whole conversation keyed on the message list, so a single receipt landing on
 * one message re-ran every one of them inside a frame — the stutter on every incoming "Delivered".
 * Pure, it can run off the main thread (see [rememberThreadModel]), and it can be unit tested
 * without a UI in sight, which for rules this intricate is the only way to change them safely.
 */

/**
 * The silence that ends a section. Short enough to break up an evening that came in bursts, long
 * enough that a conversation being had in real time stays one unbroken run.
 */
private const val SectionGapMs = 60L * 60 * 1000

internal data class ThreadRow(
    val message: Message,
    val isFirstInRun: Boolean,
    val isLastInRun: Boolean,
    val sender: GroupMember?,
    /**
     * Who wrote the message this one quotes, where that is someone else in a group.
     *
     * The outline standing in for a received message is drawn exactly as the message itself would
     * be — indented past the avatar gutter, with that person's face beside it — so a reference to
     * someone's message says whose it was without having to be read. Resolved here because only the
     * thread can see both the quoted message and the roster.
     */
    val quotedSender: GroupMember? = null,
    /**
     * The message this reply's connector should actually reach for, which is not always the message
     * it quotes.
     *
     * Two rules meet here. A reply sitting directly under a message from the *same person* needs no
     * line at all — they are already one stack of bubbles in one column, and a line drawn between
     * two touching bubbles from the same sender is scaffolding around something nobody could
     * misread. But a chain of those still has to say what it is answering: three messages deep, the
     * link that matters is the one out of the chain, not the two inside it.
     *
     * So the link is followed upward past every suppressed pair until it reaches one worth drawing,
     * and null means the whole chain was suppressed and this reply draws nothing. In the worked
     * case — your message, their reply A, their reply B — B's line skips A entirely and runs up to
     * your message, passing beside A on the way, which is exactly the shape of the conversation.
     */
    val connectorTarget: String? = null,
    /**
     * Whether [connectorTarget] names a row whose *outline* is the endpoint, rather than its bubble.
     *
     * The chain case: the reference is drawn once, on the message that first needed it, and every
     * reply under it answers that picture rather than the message a screen away that it stands for.
     */
    val targetIsOutline: Boolean = false,
    /**
     * Whether this message draws its sender's face even though it is not the one wearing the tail.
     *
     * Normally only the last message of a run carries a face and the rest are indented past an
     * empty gutter. But a connector leaves from the face, and a received message that is one end of
     * a reply link may easily be mid-run — the next message along steals the tail, and with it the
     * only thing the line had to come out of. So a message the conversation draws a line to or from
     * keeps its own face, wherever it sits in its run.
     */
    val forcedAvatar: Boolean = false,
    /**
     * Whether this message wears a tail because a reply below it points at its bubble.
     *
     * Same reasoning as [forcedAvatar], for the side of the conversation that has no faces. A
     * message mid-run is a square-cornered bubble packed three dip from the next one, which is
     * nothing for a line to leave from; carrying a connector makes it the head of a thread rather
     * than one bubble in a stack, and it is drawn as one. See [AppleBubble]'s `forcedTail`.
     */
    val forcedTail: Boolean = false,
    /**
     * Whether the message this one quotes is the one immediately above it, with nothing between.
     *
     * Worked out here because this is the only place that can see both messages *and* whether a
     * separator was dropped between them: a reply to the message above, on the far side of a day
     * rule, is not a pair you can take in at a glance and still wants its quote drawn out.
     */
    val quoteAdjacent: Boolean = false,
    /** How many messages in the conversation answer this one. */
    val replyCount: Int = 0,
    /** True for the last message of a thread, which closes it off with a tail and a gap. */
    val threadEnd: Boolean = false,
    /** The message right after this one in its run, or null at the run's end — see [StickerSpacing]. */
    val nextInRun: String? = null,
    /**
     * Whether a second connector already meets the bubble at either end of this one's.
     *
     * Worked out here because it is a question about neighbours: whether the message above is
     * itself an answer, and whether the message below answers this one from right underneath. The
     * bubble knows neither. See [ConnectorSplit].
     */
    val splitTop: Boolean = false,
    val splitBottom: Boolean = false,
)

/**
 * What the conversation lays out, in display (newest-first) order.
 *
 * The thread used to be a flat list of bubbles with one hardcoded "TODAY" pinned above the whole
 * history, which was a lie for every conversation older than today. Separators are real items now,
 * so they scroll with the messages they head.
 */
internal sealed interface ThreadItem {
    val key: String

    data class Bubble(val row: ThreadRow) : ThreadItem {
        override val key: String get() = row.message.id
    }

    /**
     * The head of a section: when the messages under it were sent. Carries the instant rather than
     * a finished label, so the wording is the style's business at draw time and not the list's.
     */
    data class SectionBreak(val at: Long, val heads: String) : ThreadItem {
        /**
         * Keyed on the message it heads, not on the instant it prints.
         *
         * A send lands optimistically with the clock's own reading and is corrected a moment later
         * to the time the service stamped it. Keyed on that instant, the separator was a *different
         * item* either side of the correction: the first one faded out and a second faded in, which
         * is the flicker between the message appearing and the header settling. The message it
         * heads does not change its id, so this doesn't change either.
         */
        override val key: String get() = "section-$heads"
    }

    /** Where you had read up to when the conversation was opened. */
    data object UnreadBreak : ThreadItem {
        override val key: String get() = "unread-break"
    }
}

/**
 * The result of [buildThreadModel]: the thread as the list lays it out.
 *
 * [inputs] is kept so a caller can tell whether a model already answers the messages it has now.
 */
@Stable
internal class ThreadModel(
    val inputs: ThreadInputs,
    /** The messages after games, media runs and poll updates are folded — what is actually drawn. */
    val messages: List<Message>,
    /** Rows and separators, newest first — the reversed list's own order. */
    val items: List<ThreadItem>,
    /** The newest outgoing message that can carry a receipt, which is the one that shows it. */
    val lastMineId: String?,
    /**
     * An older outgoing message that keeps its "Read" standing beside the newest one's receipt —
     * see [heldReadReceipt]. Null when the newest message's own receipt is the only one.
     */
    val heldReadId: String? = null,
    val forcedTails: Set<String>,
    val forcedAvatars: Set<String>,
    /** The stickers laid on each message, by the id of the bubble they are drawn on, oldest first. */
    val stickers: Map<String, List<PlacedSticker>> = emptyMap(),
)

/** A sticker as a bubble draws it: its picture, and where on the bubble it goes. */
@Immutable
data class PlacedSticker(
    val id: String,
    val uri: String,
    val placement: StickerPlacement?,
    val mine: Boolean,
    /** The emoji one of ours was made from — what it is sent as again when it is moved. */
    val emoji: String? = null,
)

/** A sticker row, as the image it carries — or null for anything that is not one. */
private fun Message.stickerImage(): Attachment.Image? =
    ((this as? Message.AttachmentMessage)?.attachment as? Attachment.Image)?.takeIf { it.sticker }

/**
 * Takes the stickers out of the conversation and hands them to the bubbles they were put on.
 *
 * A sticker is stored as a row answering its message, but it is not something said *after* that
 * message: it is drawn on it. One whose message is not in the list is dropped with it.
 */
private fun splitStickers(messages: List<Message>): Pair<List<Message>, List<Message>> =
    messages.partition { it.stickerImage() == null }

/**
 * What a [ThreadModel] is built from.
 *
 * Compared by the *identity* of the message list rather than its contents: every write produces a
 * new list, and deep-comparing a few hundred messages on every recomposition to discover that is
 * work spent answering a question the flow has already answered.
 */
@Stable
internal class ThreadInputs(
    val messages: List<Message>,
    val members: List<GroupMember>,
    val isGroup: Boolean,
    val firstUnreadId: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is ThreadInputs && other.messages === messages && other.members == members &&
            other.isGroup == isGroup && other.firstUnreadId == firstUnreadId

    override fun hashCode(): Int =
        ((System.identityHashCode(messages) * 31 + members.hashCode()) * 31 + isGroup.hashCode()) * 31 +
            (firstUnreadId?.hashCode() ?: 0)
}

/** A plain cell for the model, so building one inline is not a state write during composition. */
private class ThreadModelCell {
    @Volatile var value: ThreadModel? = null
}

/**
 * Up to this many messages the model is simply built inline, in composition.
 *
 * A short conversation costs well under a millisecond to lay out, and building it inline means a
 * send appears on the very frame it lands rather than one frame later. Past it the work moves to a
 * background thread and the thread keeps drawing the previous model until the new one is ready.
 */
private const val InlineBuildLimit = 80

/**
 * The model for these messages, built off the main thread once the conversation is long enough to
 * make that worth a frame of latency.
 *
 * The first model with any messages in it is always built inline: a conversation must not open
 * blank for a frame while its first layout is computed somewhere else.
 */
@Composable
internal fun rememberThreadModel(
    messages: List<Message>,
    members: List<GroupMember>,
    isGroup: Boolean,
    firstUnreadId: String?,
): ThreadModel {
    val cell = remember { ThreadModelCell() }
    // Bumped when a background build lands, which is what brings composition back round to read it.
    val landed = remember { mutableIntStateOf(0) }
    landed.intValue
    val inputs = ThreadInputs(messages, members, isGroup, firstUnreadId)

    var model = cell.value
    val stale = model == null || model.inputs != inputs
    val mustBuildInline = model == null ||
        (model.inputs.messages.isEmpty() && messages.isNotEmpty()) ||
        messages.size <= InlineBuildLimit
    if (stale && mustBuildInline) {
        model = buildThreadModel(inputs)
        cell.value = model
    }

    LaunchedEffect(inputs) {
        if (cell.value?.inputs == inputs) return@LaunchedEffect
        val built = withContext(Dispatchers.Default) { buildThreadModel(inputs) }
        cell.value = built
        landed.intValue++
    }
    return model!!
}

/** Lays out a conversation. Pure: the same inputs always give the same model. */
internal fun buildThreadModel(inputs: ThreadInputs): ThreadModel {
    val members = inputs.members
    val isGroup = inputs.isGroup
    val firstUnreadId = inputs.firstUnreadId

    // A game is a run of bubbles, one per move; only its newest turn is drawn as a board. Folded in
    // before anything else looks at the list so runs, quotes, separators and scroll indices all
    // agree with what is actually on screen. Poll updates go the same way and for the same reason:
    // the poll they answer is already showing every one of them.
    val (said, stickerRows) = splitStickers(inputs.messages)
    val messages = withoutPollUpdates(collapseMediaGroups(collapseGameTurns(said)))
    val stickers: Map<String, List<PlacedSticker>> = run {
        if (stickerRows.isEmpty()) return@run emptyMap()
        // A photo folded into a batch is drawn as part of the batch's bubble.
        val drawnAs = buildMap {
            messages.forEach { message ->
                val group = (message as? Message.AttachmentMessage)?.attachment as? Attachment.MediaGroup
                group?.messageIds?.forEach { put(it, message.id) }
            }
        }
        stickerRows
            .sortedBy { it.createdAt }
            .mapNotNull { row ->
                val target = row.replyTo()?.id ?: return@mapNotNull null
                val image = row.stickerImage() ?: return@mapNotNull null
                (drawnAs[target] ?: target) to PlacedSticker(row.id, image.uri, image.placement, row.mine, image.caption?.takeIf { row.mine })
            }
            .groupBy({ it.first }, { it.second })
    }

    // How many answers each message has drawn. Counted once over the whole conversation rather than
    // searched for per row. A reply sitting directly under what it answers is deliberately not
    // counted: the connector already spans that pair, so a "1 Reply" label over two bubbles you can
    // see at once is answering a question nobody has.
    val replyCounts = buildMap {
        messages.forEachIndexed { index, message ->
            val quoted = message.replyTo()?.id ?: return@forEachIndexed
            if (!quoteAdjacentFor(messages, index, firstUnreadId)) put(quoted, (get(quoted) ?: 0) + 1)
        }
    }

    // How many messages each thread holds altogether, keyed by every message in it — what the reply
    // label counts, as against [replyCounts], which only decides whether there is a label at all.
    val threadSizes = run {
        val roots = threadRoots(messages)
        val sizes = messages.groupingBy { roots[it.id] ?: it.id }.eachCount()
        messages.associate { it.id to (sizes[roots[it.id] ?: it.id] ?: 1) }
    }
    val threadEnds = threadEndIds(messages)
    val rows = run {
        // A face beside every message is how a group says who is talking. In a one-to-one there is
        // only ever one other person and the answer never changes, so the gutter was a column of
        // the same picture repeated down the side of the conversation, buying nothing and taking a
        // bubble's worth of width off every incoming message to do it.
        fun memberOf(message: Message): GroupMember? =
            if (!message.mine && isGroup) members.find { it.id == message.senderId() } else null
        val byId = messages.associateBy { it.id }
        val index = messages.withIndex().associate { (i, m) -> m.id to i }

        // The two bubbles touching, in the same column, from the same person.
        fun touching(reply: Message, quoted: Message): Boolean =
            quoteAdjacentFor(messages, index[reply.id] ?: -1, firstUnreadId) &&
                reply.mine == quoted.mine && reply.senderId() == quoted.senderId()

        /**
         * Whether a run of one person's touching answers leads back, in the end, to somebody else.
         *
         * Bounded by the conversation's length for the same reason [targetFor] is.
         */
        fun leadsToSomeoneElse(message: Message): Boolean {
            var current = message
            repeat(messages.size) {
                val quoted = current.replyTo()?.id?.let(byId::get) ?: return false
                if (!touching(current, quoted)) {
                    return quoted.mine != current.mine || quoted.senderId() != current.senderId()
                }
                current = quoted
            }
            return false
        }

        // Whether the link between a reply and what it quotes is one the reader can already see, and
        // so is folded into the line above it rather than drawn for itself.
        //
        // Theirs always: their side is a column of faces, and the stack already says who is
        // answering whom. Yours only when the run you are adding to is answering someone else — then
        // there is already an L coming down out of their message into your first answer, and a
        // second answer under it is the same L made taller rather than a C of its own stacked under
        // the first. A run of your own answering your own keeps its C, since there is no other line
        // for it to join.
        fun suppressed(reply: Message, quoted: Message): Boolean =
            touching(reply, quoted) && (!reply.mine || leadsToSomeoneElse(quoted))

        // In a one-to-one the other side has no avatar column for a line to run down, so a run of
        // their touching answers is not folded into one tall L: that line would be drawn straight
        // through every bubble under the first. The first answer keeps its own line to what it
        // answers, and the ones touching it below draw nothing at all.
        fun silent(reply: Message, quoted: Message): Boolean =
            !isGroup && !reply.mine && touching(reply, quoted)

        /** Whether a message shows the outline of what it answers, rather than pointing at a row. */
        fun showsOutline(message: Message): Boolean =
            message.replyTo() != null && !quoteAdjacentFor(messages, index[message.id] ?: -1, firstUnreadId)

        /**
         * Walks up the chain to whatever this reply's line should actually reach for.
         *
         * Two ways to stop. A link the reader cannot already see is drawn as it stands. A link they
         * *can* — the same person, one bubble up — is stepped over, but only as far as the message
         * that is showing the reference: the picture of what the chain is answering is already on
         * screen there, and the newest message answers *that* rather than the real bubble it stands
         * for, which may be a screen away.
         *
         * Bounded by the conversation's own length rather than trusted to terminate: `replyTo`
         * comes off the wire, and a cycle in it would be an infinite loop inside a layout pass.
         */
        fun targetFor(message: Message): Pair<String, Boolean>? {
            var current = message
            repeat(messages.size) {
                val quotedId = current.replyTo()?.id ?: return null
                val quoted = byId[quotedId] ?: return quotedId to false
                if (silent(current, quoted)) return null
                if (!suppressed(current, quoted)) return quotedId to false
                // Stepping over a link the reader can see. If the message being stepped over is the
                // one holding the reference, that outline is the end of the line.
                if (showsOutline(quoted)) return quoted.id to true
                current = quoted
            }
            return null
        }

        /**
         * The messages whose connector a later one has taken over.
         *
         * A run of same-person replies is one thread with one thing to say, and it should say it
         * once. Left to draw for itself, every message in the chain ran its own line to the same
         * reference — and each of those needed a face to leave from, so a run that should have worn
         * a single avatar at its foot sprouted one per message. Only the newest draws.
         *
         * Built in one pass rather than asked per message: "has anything absorbed me" is a question
         * about the whole conversation, and answering it by searching is a scan per row — which on
         * a long thread is the conversation walked once for every message in it, on every change.
         */
        val absorbedIds = buildSet {
            messages.forEach { later ->
                val parentId = later.replyTo()?.id ?: return@forEach
                val parent = byId[parentId] ?: return@forEach
                if (suppressed(later, parent) && !silent(later, parent)) add(parentId)
            }
        }

        // Each message's endpoint, worked out once. Every row asks for it three times below.
        val targets = messages.associate { message ->
            message.id to (if (message.id in absorbedIds) null else targetFor(message))
        }
        // Every message that is one end of a line the conversation actually draws. Collected up
        // front because a message cannot answer "does anything point at me" on its own — and only
        // over the lines that survive [absorbed], so a chain contributes one face and not one each.
        // The messages a line actually lands on from below — real bubbles, not the outlines that
        // stand in for them. These are the ones that stop being mid-run: a connector leaves from a
        // tail, and needs room under it to do so.
        val pointedAt = buildSet {
            messages.forEach { message ->
                val (target, viaOutline) = targets[message.id] ?: return@forEach
                if (!viaOutline) add(target)
            }
        }
        // Both ends of every line that survives [absorbedIds]: the replies that draw one, and —
        // from [pointedAt] — the bubbles they land on. An outline is drawn by the row it belongs to
        // and needs no face of its own, which is why it is not in either set.
        val linked = pointedAt + messages.filter { targets[it.id] != null }.map { it.id }
        messages.mapIndexed { index, message ->
            val (first, last) = runFlagsFor(messages, index, firstUnreadId, threadEnds)
            val sender = memberOf(message)
            ThreadRow(
                message,
                isFirstInRun = first,
                isLastInRun = last,
                sender = sender,
                quotedSender = message.replyTo()?.id?.let { byId[it] }?.let(::memberOf),
                connectorTarget = targets[message.id]?.first,
                targetIsOutline = targets[message.id]?.second == true,
                // A face for either end of a drawn link, tail or no tail — the line has to leave
                // from somewhere, and mid-run there is otherwise nothing there.
                forcedAvatar = sender != null && message.id in linked,
                // And a tail on the end a line comes *out* of, where the run did not already give
                // it one. Costs the gap under it too, which is the point.
                forcedTail = !last && message.id in pointedAt,
                quoteAdjacent = quoteAdjacentFor(messages, index, firstUnreadId),
                // Shown only where an answer of this message's is out of sight, and counting the
                // whole thread when it is.
                replyCount = if ((replyCounts[message.id] ?: 0) > 0) threadSizes[message.id] ?: 0 else 0,
                threadEnd = message.id in threadEnds,
                nextInRun = if (last) null else messages.getOrNull(index + 1)?.id,
                // Two lines meeting one bubble split apart around its middle, one just above it and
                // one just below, rather than laid over each other. Worked out from the lines that
                // are actually drawn: guessed from which messages sat next to each other, a reply
                // with its reference drawn as an outline never split, and a bubble answered from
                // further down had its second line land wherever the first one was.
                //
                // The top of this row's line splits where the bubble it leaves has a line of its own
                // arriving; the bottom splits where a later message's line leaves this bubble.
                splitTop = targets[message.id]
                    ?.takeIf { (_, viaOutline) -> !viaOutline }
                    ?.let { (target, _) -> targets[target] != null } == true,
                splitBottom = targets[message.id] != null && message.id in pointedAt,
            )
        }
    }
    return ThreadModel(
        inputs = inputs,
        messages = messages,
        items = withBreaks(rows.asReversed(), firstUnreadId),
        lastMineId = messages.lastOrNull { it.mine && it.canHaveStatus() }?.id,
        heldReadId = heldReadReceipt(messages),
        forcedTails = rows.filter { it.forcedTail }.mapTo(HashSet()) { it.message.id },
        forcedAvatars = rows.filter { it.forcedAvatar }.mapTo(HashSet()) { it.message.id },
        stickers = stickers,
    )
}

/** How long after a message was read the next send has to come for the "Read" to stay up. */
private const val HeldReadGapMs = 5 * 60_000L

/**
 * The newest read outgoing message, when it is not the newest outgoing message and the one sent
 * after it went out at least [HeldReadGapMs] after it was read.
 *
 * A message sent well after the last one was read starts a new exchange, and the newest receipt
 * only says how far *that* got — "Delivered" under it no longer tells you the earlier message was
 * seen. So the old "Read" stays standing, tail and all, until a newer message is read and takes
 * the word over.
 */
private fun heldReadReceipt(messages: List<Message>): String? {
    val mine = messages.filter { it.mine && it.canHaveStatus() }
    val index = mine.indexOfLast { it.status() == DeliveryStatus.READ || it.status() == DeliveryStatus.PLAYED }
    // Nothing read, or the newest message is itself the read one and says so on its own.
    if (index < 0 || index == mine.lastIndex) return null
    val read = mine[index]
    val readAt = read.receipts()?.read?.values?.maxOrNull() ?: read.createdAt
    val next = mine[index + 1].createdAt
    if (readAt <= 0L || next <= 0L) return null
    return read.id.takeIf { next - readAt >= HeldReadGapMs }
}

/**
 * Where a message sits in its run of same-sender neighbours: `isFirstInRun to isLastInRun`.
 *
 * The single place this is decided. Anything that lifts a bubble out of the conversation — the
 * long-press overlay, a focused reply — has to arrive at the same answer the thread did, or the
 * message changes shape under the finger that picked it up. A second copy of this rule living in
 * the chat screen was doing exactly that: a message that ended a section wore a tail in the thread
 * and lost it the moment it was pressed.
 */
fun runFlagsFor(
    messages: List<Message>,
    index: Int,
    firstUnreadId: String? = null,
    /**
     * The messages that close a thread — see [threadEndIds], which is where the rule lives.
     *
     * Passed in rather than derived here because this is called once per row and deriving it is a
     * pass over the whole conversation. Callers asking about a single message can let it default.
     */
    threadEnds: Set<String> = threadEndIds(messages),
): Pair<Boolean, Boolean> {
    val message = messages.getOrNull(index) ?: return true to true
    val previous = messages.getOrNull(index - 1)
    val next = messages.getOrNull(index + 1)
    val first = !joinsRun(previous, message, threadEnds) || startsSection(previous, message, firstUnreadId)
    val last = !joinsRun(message, next, threadEnds) || startsSection(message, next, firstUnreadId)
    return first to last
}

/**
 * Whether the message at [index] quotes the one directly above it, with nothing in between.
 *
 * Public and called rather than mirrored, for the same reason [runFlagsFor] is: an overlay that
 * lifts a reply out of the conversation has to draw it exactly as the conversation did, and this is
 * what decides whether it is drawn with a hollow bubble above it or with a connector reaching up.
 * Two copies of the rule means a message that changes shape the moment you press it.
 */
fun quoteAdjacentFor(messages: List<Message>, index: Int, firstUnreadId: String? = null): Boolean {
    val message = messages.getOrNull(index) ?: return false
    val quoted = message.replyTo()?.id ?: return false
    val previous = messages.getOrNull(index - 1) ?: return false
    // A separator between the two is a pair you cannot take in at a glance, whatever the order says.
    return previous.id == quoted && !startsSection(previous, message, firstUnreadId)
}

/**
 * Whether two neighbours are drawn as one run.
 *
 * A notice, a tombstone and a call log each break the conversation in two rather than continuing
 * it: none of them is a bubble that can carry a tail, so a run running through one left the message
 * above it trailing off into something that isn't a message.
 */
private fun joinsRun(older: Message?, newer: Message?, threadEnds: Set<String>): Boolean {
    if (older == null || newer == null) return false
    if (!older.joinsRuns() || !newer.joinsRuns()) return false
    // The one thing a thread changes about grouping: the message that ends one closes its run, so
    // it wears a tail and whatever comes next starts fresh underneath it. Carrying a quote is not
    // itself a reason to break a run — a reply in the middle of a back-and-forth groups with its
    // neighbours exactly like any other message.
    if (older.id in threadEnds) return false
    return older.mine == newer.mine && older.senderId() == newer.senderId()
}

private fun Message.joinsRuns(): Boolean =
    this !is Message.System && this !is Message.Deleted && this !is Message.Call

/**
 * Whether a separator is drawn immediately above [message] — a section break, or the line marking
 * where the unread messages start.
 *
 * Deliberately the same test [withBreaks] uses to decide where those separators go, down to the
 * silence it takes to start a section and to how an undated message is treated. A run that spans a
 * separator is not a group: the message above the rule was left tail-less, so a whole section could
 * go by without a tail anywhere in it. The two must agree, so they are written to the one rule.
 */
private fun startsSection(previous: Message?, message: Message?, firstUnreadId: String?): Boolean {
    if (message == null) return true
    if (message.id == firstUnreadId) return true
    val at = message.createdAt
    // No header above a message with no date on it — the settings preview's samples.
    if (at <= 0L) return false
    val older = previous?.createdAt?.takeIf { it > 0L } ?: return true
    return !isSameDay(older, at) || at - older >= SectionGapMs
}

/**
 * Drops a separator above the first message of each section — a run of messages with no long
 * silence in it. A new day starts one however short the gap, since a message sent at one minute
 * past midnight belongs under the new date and not the old one.
 *
 * [displayRows] is newest-first, so the message that *starts* a section is the last one of it in
 * this order — which is why the separator is appended after a row whose older neighbour is on the
 * far side of the break, and after the final row unconditionally. On a reversed list, "after" is
 * "above".
 *
 * A message with no timestamp (the settings preview's samples) is left unheaded rather than dated
 * to the epoch.
 */
/**
 * How many turns of a game the conversation keeps: the live one, plus the two behind it as
 * one-line summaries.
 *
 * Apple's own clients keep a short tail rather than the whole game, and for the same reason — a
 * long match would otherwise bury everything said around it under a column of identical boards.
 */
private const val GameTurnsKept = 3

/**
 * Collapse each game down to its most recent turns.
 *
 * A game is not one bubble that rewrites itself; it is a run of them, one per move, and only the
 * newest is worth drawing as a board. The two behind it stay as a line saying which game they
 * belong to, so the exchange is still legible as having happened, and everything older leaves the
 * conversation entirely.
 *
 * Done here, over the whole message list, because "newest" is a question about the conversation
 * rather than about any one message — and done *before* runs, quotes and separators are worked
 * out, so those all see the same list the reader does.
 */
internal fun collapseGameTurns(messages: List<Message>): List<Message> {
    fun invite(message: Message): Attachment.GameInvite? =
        (message as? Message.AttachmentMessage)?.attachment as? Attachment.GameInvite

    // Grouped by the message that opened the game. A turn that never learned its root stands alone,
    // under its own id, and is left as it is.
    val turnsByGame = messages
        .mapNotNull { message -> invite(message)?.rootGuid?.let { it to message.id } }
        .groupBy({ it.first }, { it.second })
    if (turnsByGame.isEmpty()) return messages

    val live = turnsByGame.mapValues { (_, ids) -> ids.last() }
    val kept = turnsByGame.values.flatMap { ids -> ids.takeLast(GameTurnsKept) }.toSet()

    return messages.mapNotNull { message ->
        val invite = invite(message) ?: return@mapNotNull message
        val root = invite.rootGuid ?: return@mapNotNull message
        when {
            message.id !in kept -> null
            live[root] == message.id -> message
            else -> (message as Message.AttachmentMessage)
                .copy(attachment = invite.copy(superseded = true))
        }
    }
}

/** How far apart two pictures can land and still be one batch. */
private const val MediaGroupWindowMs = 60_000L

/**
 * Folds a run of photos and videos sent back-to-back into one message carrying a
 * [Attachment.MediaGroup].
 *
 * A picker sends a batch as one message per file, and drawn as they arrive twelve photos are a
 * screenful each. A run is broken by a different sender, a caption, a quote, a failed send, or a
 * minute's gap. The folded message is the run's newest, so the receipt, the reactions and the row
 * key stay with the message the conversation already treats as the latest.
 */
internal fun collapseMediaGroups(messages: List<Message>): List<Message> {
    fun groupable(message: Message): Message.AttachmentMessage? = (message as? Message.AttachmentMessage)?.takeIf {
        val attachment = it.attachment
        // A Genmoji is never part of a batch: it is a glyph somebody typed, and folding one into a
        // grid of photos puts a character into a photo album.
        val media = (attachment is Attachment.Image && attachment.genmoji == null) ||
            attachment is Attachment.Video
        media && mediaCaption(attachment).isNullOrBlank() && it.error == null
    }

    val out = ArrayList<Message>(messages.size)
    val run = mutableListOf<Message.AttachmentMessage>()
    fun flush() {
        when (run.size) {
            0 -> Unit
            1 -> out.add(run[0])
            else -> out.add(
                run.last().copy(attachment = Attachment.MediaGroup(run.map { it.attachment }, run.map { it.id }), replyTo = run.first().replyTo),
            )
        }
        run.clear()
    }
    messages.forEach { message ->
        val media = groupable(message)
        val previous = run.lastOrNull()
        val joins = media != null && previous != null && media.replyTo == null &&
            media.mine == previous.mine && media.senderId == previous.senderId &&
            abs(media.createdAt - previous.createdAt) <= MediaGroupWindowMs
        if (joins) {
            run.add(media!!)
        } else {
            flush()
            if (media != null) run.add(media) else out.add(message)
        }
    }
    flush()
    return out
}

internal fun withBreaks(displayRows: List<ThreadRow>, firstUnreadId: String?): List<ThreadItem> = buildList {
    displayRows.forEachIndexed { index, row ->
        add(ThreadItem.Bubble(row))
        // Above the first unread message, which on this order means straight after it. The day
        // separator goes on after, so a conversation resumed on a later day reads
        // "Tuesday / unread from here / the messages".
        if (row.message.id == firstUnreadId) add(ThreadItem.UnreadBreak)
        val at = row.message.createdAt
        if (at <= 0L) return@forEachIndexed
        val older = displayRows.getOrNull(index + 1)?.message?.createdAt?.takeIf { it > 0L }
        if (older == null || !isSameDay(older, at) || at - older >= SectionGapMs) {
            add(ThreadItem.SectionBreak(at, row.message.id))
        }
    }
}
