package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.ruskcoder.messages.ui.theme.LocalChatTheme
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.DisposableEffect
import com.ruskcoder.messages.data.model.preview
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.BubbleStyle
import com.ruskcoder.messages.ui.components.bubbles.BubbleRowInset
import com.ruskcoder.messages.ui.components.bubbles.OutlinedBubble
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.canReact
import com.ruskcoder.messages.data.model.edited
import com.ruskcoder.messages.data.model.error
import com.ruskcoder.messages.data.model.pollAttachment
import com.ruskcoder.messages.data.model.ReactionSummary
import com.ruskcoder.messages.data.model.isMine
import com.ruskcoder.messages.data.model.reactions
import com.ruskcoder.messages.data.model.effect
import com.ruskcoder.messages.ui.components.effects.BubbleEffectLayer
import com.ruskcoder.messages.ui.components.effects.LocalBubbleInk
import com.ruskcoder.messages.ui.components.effects.BubbleEffectState
import com.ruskcoder.messages.ui.components.effects.rememberBubbleEffect
import com.ruskcoder.messages.data.model.replyTo
import com.ruskcoder.messages.data.model.textRuns
import com.ruskcoder.messages.data.model.status
import com.ruskcoder.messages.data.model.verificationFailed
import com.ruskcoder.messages.data.model.textScale
import com.ruskcoder.messages.ui.components.bubbles.AppleBubble
import com.ruskcoder.messages.transcribe.TranscriptionState
import com.ruskcoder.messages.ui.components.bubbles.AudioClip
import com.ruskcoder.messages.ui.components.bubbles.BubbleColors
import com.ruskcoder.messages.ui.components.bubbles.GoogleBubble
import com.ruskcoder.messages.ui.components.bubbles.WhatsAppBubble
import com.ruskcoder.messages.ui.components.bubbles.WhatsAppCallBubble
import com.ruskcoder.messages.ui.components.bubbles.useAppleBubbleColors
import com.ruskcoder.messages.ui.components.bubbles.useGoogleBubbleColors
import com.ruskcoder.messages.ui.components.bubbles.useWhatsAppBubbleColors
import com.ruskcoder.messages.ui.theme.LocalSettings

/** How far below the bubble's own bottom edge the WhatsApp/Google reaction pill hangs. */
private val ReactionHang = 10.dp

/** The tapback's own diameter (a multi-emoji reaction stretches wider but keeps this height). */
val TapbackSize = 32.dp

/** How far the tapback sinks down over the bubble's top edge. */
val TapbackOverlap = 12.dp

/** Horizontal distance from the bubble's outer edge to the tapback's outer edge. */
val TapbackInset = 10.dp

/** How far in from the bubble's trailing edge the WhatsApp/Google pill sits. */
private val PillInset = 12.dp

/** How far the pill rides up over the bubble's bottom edge, so the two read as attached. */
private val PillOverlap = 8.dp
private val TapbackGap = TapbackSize - TapbackOverlap + 4.dp

/**
 * Eased at both ends: the badge gathers speed out of nothing and slows into its full size.
 *
 * Shared with the row's own height — see `settleHeight` — so the room a reaction needs opens on
 * the same clock the badge grows on, and the two read as one movement.
 */
internal val ReactionSettleEasing = CubicBezierEasing(0.45f, 0f, 0.15f, 1f)
internal const val ReactionSettleMs = 320

/** How much of the badge's zoom its fade takes up, at either end. */
private const val SettleInFade = 0.3f

/** How small a reaction badge is on the first frame of its zoom in. */
private const val SettleInStartScale = 0.2f

/** How the line an unsend leaves behind arrives, and how long it waits for the row to close first. */
private const val TombstoneFadeMs = 200
private const val TombstoneDelayMs = 120

/** The gap a tombstone keeps above and below itself, matching an ordinary bubble's. */
private val TombstoneGap = 3.dp

/**
 * The tombstone's own corner, per style.
 *
 * Its own rather than [OutlinedBubble]'s: this is a notice with an icon in it rather than words
 * somebody wrote, so it takes the style's corner radius without the tail or the run-merging that
 * belong to a real message. Apple never reaches it — that style leaves a centred line instead of a
 * bubble — and is listed only so the `when` is exhaustive.
 */
private fun tombstoneShape(bubbleType: BubbleStyle) = when (bubbleType) {
    BubbleStyle.GOOGLE -> RoundedCornerShape(19.dp)
    BubbleStyle.WHATSAPP -> RoundedCornerShape(10.dp)
    BubbleStyle.APPLE -> RoundedCornerShape(18.dp)
}

/** The space between one earlier version of a message and the next. */
private val EditHistoryGap = 4.dp

/** How long the stack takes to come out from behind the message, on Material's own decelerate. */
private const val EditHistoryMs = 280
private val EditHistoryEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * The versions a message had before, oldest first — drawn above the bubble, never inside it.
 *
 * The same dotted outlines an unsent message leaves behind: these are messages that are no longer
 * the message, which is the one thing they and a tombstone have in common.
 *
 * It lives *beside* the bubble in the row rather than in [MessageBubble]'s own column, and that
 * placement is the whole animation. The thread is a reversed list, so a row grows upward from its
 * own bottom edge: uncovered from behind the message, the stack claims its height above the bubble
 * at exactly the rate the row's top edge rises to hold it, and the message you are reading never
 * moves a pixel. Inside the bubble it was also inside the row's height-settling layer, which eases
 * *toward* whatever it last measured — so the two ran on separate clocks, the bubble dropped by the
 * stack's full height on the first frame and crawled back up as the settle caught up.
 */
@Composable
fun EditHistoryStack(
    versions: List<String>,
    mine: Boolean,
    time: String,
    visible: Boolean,
    /** Leading inset for the stack in a group, so it lines up with the bubble and not the screen. */
    startInset: Dp = 0.dp,
) {
    AnimatedVisibility(
        visible = visible && versions.isNotEmpty(),
        enter = expandVertically(
            expandFrom = Alignment.Bottom,
            animationSpec = tween(EditHistoryMs, easing = EditHistoryEasing),
        ) + fadeIn(tween(EditHistoryMs, delayMillis = EditHistoryMs / 3)),
        exit = shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = tween(EditHistoryMs, easing = EditHistoryEasing),
        ) + fadeOut(tween(EditHistoryMs / 2)),
    ) {
        // Oldest at the top, so the stack reads downward into the message it became. Every one of
        // them is `isLast = false`: only the message in the conversation gets a tail.
        Column {
            versions.forEach { version ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = EditHistoryGap, start = startInset),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                ) {
                    OutlinedBubble(text = version, mine = mine, time = time, isFirst = false, isLast = false)
                }
            }
        }
    }
}

/**
 * One message, drawn in whichever bubble style is active, with its reactions attached. Ported
 * from `message-bubble.tsx`.
 */
@Composable
fun MessageBubble(
    message: Message,
    isFirstInRun: Boolean,
    isLastInRun: Boolean,
    /**
     * Whether the message this one quotes is the one directly above it in the conversation.
     *
     * Only the thread can answer that, so it is passed in rather than worked out here. Apple is the
     * only style that changes its mind about it — see [AppleQuotePreview] — and every other surface
     * that draws a bubble outside the conversation leaves it false, since a message lifted into an
     * overlay or peeked from the chat list has nothing above it to be adjacent to.
     */
    quoteAdjacent: Boolean = false,
    /**
     * Leaves the quote off entirely — the hollow bubble above the message and the connector beside
     * it both.
     *
     * For the one surface where a quote says nothing: a whole thread lifted out of the conversation
     * and stacked. Everything a reply could point at is already on screen, in order, directly above
     * it — so the reference is a picture of the bubble sitting one place up, and the connector is a
     * line drawn between two messages that are already touching.
     *
     * Deliberately not used by the long-press lift, which is showing you one message and does have
     * to say what it answers.
     */
    hideReplyQuote: Boolean = false,
    /** Whether a connector already lands on the quoted bubble, or leaves this one — see [AppleBubble]. */
    splitTop: Boolean = false,
    splitBottom: Boolean = false,
    /** True for the message that ends a thread, which keeps a gap under itself. */
    threadEnd: Boolean = false,
    /**
     * Hides the message while an overlay draws it elsewhere, keeping its space in the row.
     *
     * Apple hides only the bubble, because its quote is a row element that the overlay is not
     * drawing and that should stay where it is. Every other style keeps its quote *inside* the
     * bubble, so there is nothing to leave behind and the whole thing goes.
     */
    bubbleHidden: Boolean = false,
    /**
     * Whether this message's send effect should play now.
     *
     * True for exactly one frame's worth of rows: the ones the thread has just seen arrive. A
     * bubble effect is part of how a message is drawn and stays on it forever, but *playing* it is
     * an event, and an event that fired again every time the message scrolled back into view would
     * be a conversation that will not sit still.
     */
    playEffect: Boolean = false,
    /**
     * The message's own effect state, where the caller needs to reach it.
     *
     * The conversation does: the "Replay" line under a bubble is a row element, drawn outside this,
     * and pressing it has to reach the animation *inside* it. Left to make its own, the bubble owned
     * a state nothing else could touch. Null anywhere the effect only ever plays itself.
     */
    effectState: BubbleEffectState? = null,
    sender: GroupMember? = null,
    /**
     * Who wrote the message this one quotes, in a group.
     *
     * The outline standing in for a received message is drawn the way the message itself was — past
     * the avatar gutter, with that person's face beside it — so a reference says whose message it
     * refers to at a glance, and the connector has a face to leave from. Null in a one-to-one and
     * for a quote of one of your own.
     */
    quotedSender: GroupMember? = null,
    /** Draws this message's face even mid-run, because a connector leaves from it — see [ThreadRow]. */
    forcedAvatar: Boolean = false,
    /** Draws this message's tail even mid-run, because a reply below points at it — see [ThreadRow]. */
    forcedTail: Boolean = false,
    /** Bumped when any row becomes measurable, so a connector waiting on one redraws. */
    anchorRevision: (() -> Int)? = null,
    /** Whether this reply's line ends on the outline of the row it names, not on its bubble. */
    targetIsOutline: Boolean = false,
    /** Reports this row's own outline, so a reply below it can aim at that instead. */
    onOutlineLayout: ((LayoutCoordinates?) -> Unit)? = null,
    /**
     * Where the message this one answers is sitting, when it is a whole row away.
     *
     * The adjacent case draws no outline, so there is nothing local to aim at — and the climb used
     * to be a flat constant that knew nothing about the height of the bubble overhead, the text
     * size it was set in, or the reply count printed under it. Null anywhere the conversation
     * cannot be seen from, which falls back to that constant.
     */
    quotedAnchor: (() -> ThreadAnchor?)? = null,
    /** Whether the conversation reserves an avatar gutter — see [AppleBubble]. */
    inGroup: Boolean = false,
    /**
     * Whether this reply's connector is drawn at all.
     *
     * False where the link is one the reader can already see — a reply sitting directly under a
     * message from the same person, in the same column. Decided by the thread, which is the only
     * thing that can see both messages. See [ThreadRow.connectorTarget].
     */
    drawConnector: Boolean = true,
    /** Who the conversation is with, named in the line an unsent message leaves behind. */
    peerName: String = "They",
    onBubbleLayout: ((LayoutCoordinates) -> Unit)? = null,
    /** Where the sender's face landed beside this bubble, or null when there is no face. */
    onAvatarLayout: ((LayoutCoordinates?) -> Unit)? = null,
    revealX: (() -> Float)? = null,
    selectable: Boolean = false,
    /** Our own handles, so a reaction we placed can be marked as ours. */
    myHandles: Set<String> = emptySet(),
    /** Names an `@` mention in this conversation could point at. */
    mentionNames: List<String> = emptyList(),
    /** Opens the list of who reacted. Null leaves the badge inert, as it was. */
    onReactionsClick: (() -> Unit)? = null,
    /**
     * Opens the reason an undelivered message gives, and what can be done about it.
     *
     * Null leaves the badge drawn but inert, which is what a message lifted into an overlay or
     * peeked from the chat list wants: the failure is still true there and should still be visible,
     * but those surfaces have nowhere to put a dialog.
     */
    onFailureClick: (() -> Unit)? = null,
    /**
     * Whether a retracted message's words are being shown, and the link that toggles them.
     *
     * Held by the row rather than by the tombstone, because what the link reveals is drawn by the
     * row too — see [UnsentReveal]. A tombstone with no [onToggleUnsent] offers no link at all,
     * which is what a preview or an overlay wants.
     */
    unsentShown: Boolean = false,
    onToggleUnsent: (() -> Unit)? = null,
    /** Where this message's transcription has got to, for a voice message. */
    transcription: TranscriptionState = TranscriptionState.OFF,
    /**
     * Runs the transcription of a voice message. Null offers none — which is what a bubble drawn
     * outside the conversation wants, and what a device with no speech model gets.
     */
    onTranscribe: (() -> Unit)? = null,
    /** A voice message sent to us started playing — see [AudioClip.onPlay]. */
    onPlayed: (() -> Unit)? = null,
) {
    val settings = LocalSettings.current
    val bubbleType = settings.bubbleType
    val isApple = bubbleType == BubbleStyle.APPLE
    val scale = settings.textScale()

    if (message is Message.System) {
        SystemNotice(message.text, plain = isApple)
        return
    }
    if (message is Message.Call) {
        WhatsAppCallBubble(mine = message.mine, title = message.title, detail = message.detail, time = message.time, missed = message.missed)
        return
    }
    // Whether this row was already a tombstone the first time it was drawn. An unsend that happens
    // under your eyes is worth a fade; one that happened last week is just what the message is, and
    // fading it in every time it scrolls past would animate the history of the conversation at you.
    val bornDeleted = remember { message is Message.Deleted }
    if (message is Message.Deleted) {
        if (isApple) {
            // iMessage doesn't leave a bubble behind — it leaves a line in the conversation saying
            // the message was taken back, centered like a date separator.
            UnsentNotice(
                who = if (message.mine) "You" else sender?.name ?: peerName,
                text = message.text,
                attachment = message.attachment,
                animateIn = !bornDeleted,
                shown = unsentShown,
                onToggle = onToggleUnsent,
            )
        } else {
            DeletedBubble(
                mine = message.mine,
                text = message.text,
                attachment = message.attachment,
                bubbleType = bubbleType,
                animateIn = !bornDeleted,
                onBubbleLayout = onBubbleLayout,
                shown = unsentShown,
                onToggle = onToggleUnsent,
            )
        }
        return
    }

    val reactions = message.reactions() ?: emptyList()
    // Apple sets a tapback down on the bubble's own outer corner, whatever else the message is
    // carrying — a reply's quote above it doesn't change where the reaction belongs, since the
    // bubble reports the box the badge is hung off.
    val canTapback = isApple
    val gapTarget = if (reactions.isNotEmpty()) (if (canTapback) TapbackGap else ReactionHang * scale) else 0.dp
    val density = LocalDensity.current

    val audio = (message as? Message.Audio)?.let {
        AudioClip(
            uri = it.uri,
            duration = it.duration,
            waveform = it.waveform,
            transcript = it.transcript,
            transcribing = transcription == TranscriptionState.RUNNING,
            transcriptFailed = transcription == TranscriptionState.FAILED,
            transcriptNeedsModel = transcription == TranscriptionState.NO_MODEL,
            transcriptOffered = transcription != TranscriptionState.OFF,
            // A clip whose bytes have not arrived has nothing to transcribe yet.
            onTranscribe = onTranscribe?.takeIf { _ -> it.uri.isNotBlank() },
            // Only someone else's clip has a sender waiting to hear it was played.
            onPlay = onPlayed?.takeIf { _ -> !it.mine },
        )
    }
    val attachment = (message as? Message.AttachmentMessage)?.attachment
    // The plain text, with nothing appended. An edit used to be marked by "(edited)" tacked onto
    // the end of the words, which put a fact about the message inside the message — indistinguishable
    // from something the sender had typed, and impossible to style. Each bubble style now says it in
    // its own meta line instead.
    val text = (message as? Message.Text)?.text.orEmpty()
    // Empty for everything nobody formatted, which is the path every bubble in the app took until
    // now and still takes.
    val runs = message.textRuns()
    // Left as the message's own answer for the run gaps and the flush-media test below, which
    // follow the message rather than the drawing — a bubble that keeps its space in the thread and
    // one that is lifted out of it must be the same size, or the lift starts with a jump.
    val replyTo = if (hideReplyQuote && isApple) null else message.replyTo()
    /**
     * Where the bubble sits inside this row — what a tapback, a reaction pill and a sticker are all
     * placed against.
     *
     * Measured *within the row*, from the row's and the bubble's own coordinates, rather than as two
     * screen positions subtracted from each other. That used to be the arrangement, and it had two
     * ways to lose the badge. The two positions were separate states filled in by separate
     * callbacks, so a lift that carried the whole message across the screen — the long-press
     * overlay — could place the badge from one frame's row and another frame's bubble, a screen
     * away from either. And screen positions change on every frame of every scroll, so they were
     * only tracked for a message already wearing a reaction: one reacted to from the menu had no
     * box at all, and where adding the badge moved nothing — a group's name holds the gap, and the
     * other styles hang theirs below — nothing ever measured it, and the reaction never appeared.
     *
     * Inside the row none of that applies. The number does not move when the conversation scrolls
     * or when the overlay flies the row about, so it is cheap to keep for every message, and a badge
     * that turns up later finds it already there.
     */
    val bubbleAnchor = remember { BubbleInRow() }
    var bubbleInRow by remember { mutableStateOf<Rect?>(null) }
    fun remeasureBubble() {
        val next = bubbleAnchor.rect() ?: return
        if (next != bubbleInRow) bubbleInRow = next
    }
    val hasReactions = reactions.isNotEmpty()
    val stickers = LocalStickers.current[message.id].orEmpty()
    // Every bubble in the conversation is somewhere a sticker can be dropped.
    val stickerTargets = LocalStickerTargets.current
    val stickerOwner = remember { Any() }
    if (stickerTargets != null) {
        DisposableEffect(stickerTargets, message.id) {
            onDispose { stickerTargets.remove(message.id, stickerOwner) }
        }
    }
    /**
     * Whether the badge should play its drop, decided here rather than inside the badge itself.
     *
     * What counts is the reactions this *row* was composed with — not the ones the badge was, which
     * is a different and useless answer: the badge is only composed once the bubble has reported
     * where it is, so a message reacted to for the first time creates its badge already wearing the
     * new emoji and would think it had always been there. Recorded against the row, an existing
     * reaction is history and a new one is an event.
     */
    val settleKey = reactions.settleKey()
    // Forgotten once the message has been left bare: a reaction put back after that is an event
    // again, even if it is the same one the row started with.
    val bornWith = remember { arrayOf(settleKey) }
    if (!hasReactions) bornWith[0] = ""
    val animateSettle = settleKey != bornWith[0]
    /**
     * The badge outlives its reactions by one zoom out.
     *
     * Taken away on the frame the reaction was, it blinked off while the row was still closing the
     * room it had stood in. So the last reactions it wore are kept to draw it with on the way out,
     * and it is only dropped once it reports that it has gone.
     */
    val wornReactions = remember { arrayOf(reactions) }
    if (hasReactions) wornReactions[0] = reactions
    val badgeReactions = wornReactions[0]
    var badgeGone by remember { mutableStateOf(!hasReactions) }
    LaunchedEffect(hasReactions) { if (hasReactions) badgeGone = false }
    val showBadge = hasReactions || !badgeGone
    val captureLayout: (LayoutCoordinates) -> Unit = { coordinates ->
        if (stickerTargets != null && coordinates.isAttached) {
            stickerTargets.place(
                message.id,
                message.preview(),
                Rect(coordinates.positionInRoot(), coordinates.size.toSize()),
                stickerOwner,
            )
        }
        bubbleAnchor.bubble = coordinates
        remeasureBubble()
        onBubbleLayout?.invoke(coordinates)
    }

    // Armed here rather than inside the style: all three draw the same message, and which of them
    // is in use has nothing to do with how a slam behaves.
    val effects = effectState ?: rememberBubbleEffect(message.effect(), autoPlay = playEffect)
    // A named group bubble that has been reacted to makes its own room for the tapback, between the
    // name and the bubble — see [AppleBubble]. The row-level gap above would only double it.
    val nameHoldsTapback = isApple && sender != null && isFirstInRun && hasReactions
    /**
     * Whether what this draws *around* the style's bubble goes with the bubble when it is lifted.
     *
     * An overlay lifting a message draws all of it — the tapback, a poll, the failure badge — so
     * the row has to stop drawing them too. On Apple [bubbleHidden] used to reach only the bubble
     * inside the style, which left the tapback standing in the thread while an identical one flew
     * off with the message: two of them on screen, one of which snapped away when the lift ended.
     * Faded rather than removed, so nothing the row has measured moves.
     */
    /**
     * Held a couple of frames behind [bubbleHidden] on the way *out*, and released with it on the
     * way back.
     *
     * The overlay's copy of the tapback cannot be drawn on the frame it is composed: it is placed
     * against the bubble's measured box, so it arrives on the layout pass after. Blanking ours on
     * the frame the press landed therefore left a gap with no reaction on screen at all — the badge
     * blinking off and back on just before the message lifted. Held, the two copies stand in the
     * same place for those frames instead, which is nothing anyone can see.
     */
    var extrasHidden by remember { mutableStateOf(bubbleHidden) }
    LaunchedEffect(bubbleHidden) {
        if (!bubbleHidden) {
            extrasHidden = false
        } else {
            withFrameNanos { }
            withFrameNanos { }
            extrasHidden = true
        }
    }
    val extrasAlpha = if (extrasHidden) 0f else 1f
    // Stickers ease away into their own centres as the message is picked up, rather than blinking
    // out with the rest of what it wears, and grow back once it has been set down again.
    val stickersShown by animateFloatAsState(
        targetValue = if (bubbleHidden) 0f else 1f,
        animationSpec = tween(170, easing = FastOutSlowInEasing),
        label = "stickersShown",
    )
    // Room above and below for a sticker hung off the bubble's edge, so it sits in its own space
    // rather than on the message next to it — and a word to the thread that the gap is there, so
    // the bubble before it can wear a tail. See [StickerSpacing].
    val stickerHeightPx = if (stickers.isEmpty()) 0f else bubbleInRow?.height ?: 0f
    val (overTopPx, overBottomPx) = remember(stickers, stickerHeightPx) {
        if (stickerHeightPx <= 0f) 0f to 0f
        else stickerOverhang(stickers, stickerHeightPx, with(density) { StickerBaseSize.toPx() })
    }
    val tapbackRoom = if (canTapback && !nameHoldsTapback) gapTarget else 0.dp
    val stickerTop by animateDpAsState(
        targetValue = (with(density) { overTopPx.toDp() } - tapbackRoom).coerceAtLeast(0.dp),
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "stickerTop",
    )
    val stickerBottom by animateDpAsState(
        targetValue = with(density) { overBottomPx.toDp() },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "stickerBottom",
    )
    val stickerSpacing = LocalStickerSpacing.current
    val gapAbove = overTopPx > with(density) { tapbackRoom.toPx() } + 0.5f
    val gapBelow = overBottomPx > 0.5f
    if (stickerSpacing != null) {
        LaunchedEffect(stickerSpacing, message.id, gapAbove, gapBelow) {
            if (gapAbove || gapBelow || stickerSpacing.known(message.id)) {
                stickerSpacing.report(message.id, gapAbove, gapBelow)
            }
        }
    }
    Column(modifier = if (bubbleHidden && !isApple) Modifier.graphicsLayer { alpha = 0f } else Modifier) {
        if (canTapback && !nameHoldsTapback) Spacer(modifier = Modifier.height(gapTarget))
        if (stickerTop > 0.dp) Spacer(modifier = Modifier.height(stickerTop))
        // Reactions are positioned against the *bubble*, not the row: aligned to the row they came
        // out at the screen's edge, since every style insets its bubble by the row padding, the
        // tail's width, and — in a group — an avatar gutter. The bubble reports where it actually
        // landed and the pill is placed from that.
        var rowWidthPx by remember { mutableIntStateOf(0) }
        // A failed message gives up the width of the badge rather than having it laid over the
        // bubble: the badge sits *beside* the message, and the bubble is what has to move to make
        // room for it. Weighting the bubble's box is what does that — everything positioned off the
        // bubble's measured edge, the tapback included, follows it across for free.
        val failure = message.error()
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .onGloballyPositioned {
                        bubbleAnchor.row = it
                        remeasureBubble()
                        // Unconditional for the same reason as the box: it is the row's width, which
                        // a scroll never changes, and a badge that arrives later needs it too.
                        if (it.size.width != rowWidthPx) rowWidthPx = it.size.width
                    },
            ) {
                // Provided around the whole message so the words, wherever a style puts them, can
                // read it — see [LocalBubbleInk]. The layer beside it handles the other three.
                // The link card, for whichever style draws the words — see [LocalLinkPreview].
                CompositionLocalProvider(
                    LocalBubbleInk provides effects,
                    LocalLinkPreview provides (message as? Message.Text)?.link,
                ) {
                BubbleEffectLayer(state = effects, mine = message.mine) {
                // A poll is a balloon message, not a bubble: no card and no speech shape, just the
                // choices. Recognised here rather than in each style, because a control that
                // changed shape with the conversation's bubble setting would be three controls to
                // learn. Everything around it still applies — a reaction hangs off the pills the
                // same way it hangs off a bubble, since the poll reports its box the same way.
                //
                // While the poll is open in the composer for a choice to be added, the copy in the
                // thread stands down to a line: the live one is the one under your thumb.
                val pollHost = LocalPollHost.current
                val poll = message.pollAttachment()
                val pollState = if (poll == null) null else pollHost.state(message.id)
                if (poll != null && pollHost.editing == message.id) {
                    Box(modifier = Modifier.graphicsLayer { alpha = extrasAlpha }) {
                        PollPlaceholder(mine = message.mine, senderName = sender?.name)
                    }
                } else if (poll != null && pollState != null) {
                    Box(modifier = Modifier.graphicsLayer { alpha = extrasAlpha }) {
                        PollCard(message = message, state = pollState, onBubbleLayout = captureLayout)
                    }
                } else when (bubbleType) {
                    BubbleStyle.WHATSAPP -> WhatsAppBubble(
                        text = text, mine = message.mine, time = message.time, status = message.status() ?: DeliveryStatus.SENT,
                        showTail = isFirstInRun, isLastInRun = isLastInRun, replyTo = replyTo, audio = audio, attachment = attachment, sender = sender, mentionNames = mentionNames, runs = runs,
                        onBubbleLayout = captureLayout, selectable = selectable, edited = message.edited(),
                    )
                    BubbleStyle.APPLE -> AppleBubble(
                        isFromMe = message.mine, showTail = isLastInRun, isFirstInRun = isFirstInRun, text = text, replyTo = replyTo,
                        audio = audio, attachment = attachment, sender = sender, mentionNames = mentionNames, runs = runs, onBubbleLayout = captureLayout, revealX = revealX,
                        // The message's own quote, not the one being drawn: hiding it must not move the
                        // bubble, or a message lifted into an overlay sits differently from its row.
                        hasQuote = message.replyTo() != null, quoteAdjacent = quoteAdjacent, splitTop = splitTop, splitBottom = splitBottom, threadEnd = threadEnd, bubbleHidden = bubbleHidden, hasReactions = hasReactions, selectable = selectable,
                        quotedSender = quotedSender, quotedAnchor = quotedAnchor, onAvatarLayout = onAvatarLayout, inGroup = inGroup, drawConnector = drawConnector, forcedAvatar = forcedAvatar, forcedTail = forcedTail,
                        targetIsOutline = targetIsOutline, onOutlineLayout = onOutlineLayout, anchorRevision = anchorRevision,
                    )
                    BubbleStyle.GOOGLE -> GoogleBubble(
                        text = text, mine = message.mine, isFirst = isFirstInRun, isLast = isLastInRun, replyTo = replyTo,
                        audio = audio, attachment = attachment, sender = sender, mentionNames = mentionNames, runs = runs, onBubbleLayout = captureLayout, selectable = selectable,
                    )
                }
                }
                }

                // Read only for a message wearing something: the state is kept for every bubble,
                // but a row with nothing hung off it has no reason to recompose when it changes.
                val bubble = if (showBadge) bubbleInRow else null
                if (showBadge && bubble != null) {
                    // The bubble's own box, in this row's coordinates.
                    val left = with(density) { bubble.left.toDp() }
                    val top = with(density) { bubble.top.toDp() }
                    val right = left + with(density) { bubble.width.toDp() }
                    val bottom = top + with(density) { bubble.height.toDp() }
                    val fromRight = with(density) { rowWidthPx.toDp() } - right
                    if (canTapback) {
                        // On the bubble's outer top corner — the one away from the tail — sunk over its
                        // edge, the way iMessage sets a tapback down on a message.
                        AppleTapback(
                            reactions = badgeReactions,
                            settleKey = settleKey,
                            animateSettle = animateSettle,
                            visible = hasReactions,
                            onGone = { badgeGone = true },
                            myHandles = myHandles,
                            mine = message.mine,
                            onClick = onReactionsClick,
                            modifier = Modifier
                                .align(if (message.mine) Alignment.TopStart else Alignment.TopEnd)
                                .graphicsLayer { alpha = extrasAlpha }
                                .offsetBy(
                                    x = if (message.mine) left - TapbackInset else -(fromRight - TapbackInset),
                                    y = top - (TapbackSize - TapbackOverlap),
                                ),
                        )
                    } else {
                        ReactionsPill(
                            reactions = badgeReactions,
                            settleKey = settleKey,
                            animateSettle = animateSettle,
                            visible = hasReactions,
                            onGone = { badgeGone = true },
                            myHandles = myHandles,
                            onClick = onReactionsClick,
                            modifier = Modifier
                                .align(if (message.mine) Alignment.TopEnd else Alignment.TopStart)
                                .graphicsLayer { alpha = extrasAlpha }
                                .offsetBy(
                                    x = if (message.mine) -(fromRight + PillInset) else left + PillInset,
                                    y = bottom - PillOverlap,
                                ),
                        )
                    }
                }
                val stuckOn = if (stickers.isNotEmpty()) bubbleInRow else null
                if (stickers.isNotEmpty() && stuckOn != null) {
                    // Over the bubble, in its own box, and past the tapback: a sticker is slapped on
                    // top of everything the message is wearing.
                    StickersOnBubble(
                        stickers = stickers,
                        width = with(density) { stuckOn.width.toDp() },
                        height = with(density) { stuckOn.height.toDp() },
                        shown = { stickersShown },
                        modifier = Modifier
                            .offsetBy(
                                x = with(density) { stuckOn.left.toDp() },
                                y = with(density) { stuckOn.top.toDp() },
                            ),
                    )
                }
            }
            // Outgoing only — an incoming message was never ours to deliver, and `error` is never
            // set on one. Guarding on it anyway keeps the badge on the side of the row the bubble
            // is actually on.
            if (failure != null && message.mine) {
                FailedBadge(
                    onClick = onFailureClick,
                    modifier = Modifier.graphicsLayer { alpha = extrasAlpha }.padding(end = FailedBadgeInset),
                )
            }
        }
        if (stickerBottom > 0.dp) Spacer(modifier = Modifier.height(stickerBottom))
        if (!canTapback) Spacer(modifier = Modifier.height(gapTarget))
        if (message.verificationFailed()) {
            Text(
                "Sender verification failed",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(if (message.mine) Alignment.End else Alignment.Start)
                    .graphicsLayer { alpha = extrasAlpha }
                    .padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * How far the failure badge sits in from the row's trailing edge.
 *
 * The same inset every bubble style keeps, so the badge lines up with where the bubbles themselves
 * stop rather than sitting closer to the screen edge than any message ever does.
 */
private val FailedBadgeInset = BubbleRowInset

private fun Modifier.offsetBy(x: Dp, y: Dp): Modifier = this.offset(x = x, y = y)

/**
 * The pill starts small, centered on where it will end up, and grows into place, easing off as it
 * arrives — a reaction should read as the emoji you just picked coming up out of the message.
 *
 * Only when the reaction *changes*, though — [animateSettle], which the row works out, since only
 * the row knows what it started with. Played unconditionally it meant every reacted message in the
 * history threw an emoji at you: on opening the conversation, and again each time one scrolled back
 * into view.
 *
 * A badge that is going to animate is small from its very first frame, and one that is not is at
 * rest from its first. It used to start at rest either way and snap small once the effect below
 * got to run, which is a frame later: the reaction stood there full size, vanished, and only then
 * zoomed in.
 *
 * Leaving is the same run backwards — [visible] going false shrinks the badge back into the bubble
 * and [onGone] says when there is nothing left to draw.
 *
 * There is deliberately no `key()` around this any more. Remounting on every change is what used to
 * restart the animation, and it would also throw away the record of what the badge began as.
 */
@Composable
private fun SettleIn(
    settleKey: String,
    animateSettle: Boolean,
    visible: Boolean,
    onGone: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shown = remember { Animatable(if (animateSettle) 0f else 1f) }
    // The key the badge last started a run for. A change it has not caught up with yet is drawn
    // small straight away, for the frame before the effect snaps it there.
    var settledKey by remember { mutableStateOf(if (animateSettle) null else settleKey) }
    val spec = tween<Float>(ReactionSettleMs, easing = ReactionSettleEasing)
    LaunchedEffect(settleKey, visible) {
        if (!visible) {
            shown.animateTo(0f, spec)
            onGone()
            return@LaunchedEffect
        }
        // Only from rest: a badge caught on its way out grows back from where it had got to.
        if (animateSettle && settledKey != settleKey && shown.value == 1f) shown.snapTo(0f)
        settledKey = settleKey
        shown.animateTo(1f, spec)
    }
    // The caller's modifier belongs on *this* box, not on the content inside it: the alignment
    // and offset that place the pill against its bubble are the row's to read, and landing them
    // on a child of this wrapper left every pill pinned to the row's top-left corner instead.
    Box(
        modifier = modifier.graphicsLayer {
            val pending = visible && animateSettle && settledKey != settleKey && shown.value == 1f
            val progress = if (pending) 0f else shown.value
            val scale = SettleInStartScale + (1f - SettleInStartScale) * progress
            scaleX = scale
            scaleY = scale
            alpha = (progress / SettleInFade).coerceIn(0f, 1f)
        },
    ) {
        content()
    }
}

/**
 * The key that decides when a badge replays its settle-in animation.
 *
 * Counts are in it as well as glyphs, so a second person joining an existing reaction is a change
 * the badge acknowledges — otherwise "❤️ 1" becoming "❤️ 2" happens with no motion at all.
 */
private fun List<ReactionSummary>.settleKey(): String = joinToString { "${it.emoji}${it.count}" }

/** The same key for a whole message, for the row that has to make room on the badge's clock. */
internal fun Message.reactionSettleKey(): String = reactions().orEmpty().settleKey()

@Composable
private fun ReactionsPill(
    reactions: List<ReactionSummary>,
    settleKey: String,
    animateSettle: Boolean,
    visible: Boolean,
    onGone: () -> Unit,
    myHandles: Set<String>,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val fill = MaterialTheme.colorScheme.surfaceVariant
    // A reaction of your own is tinted, so you can tell at a glance whether the heart on a message
    // is yours without opening the list.
    val minePill = reactions.any { it.isMine(myHandles) }
    SettleIn(settleKey = settleKey, animateSettle = animateSettle, visible = visible, onGone = onGone, modifier = modifier, content = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (minePill) MaterialTheme.colorScheme.primaryContainer else fill)
                .border(2.dp, MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            reactions.forEach { reaction ->
                Text(reaction.emoji, fontSize = 12.sp)
                // A lone reactor is the common case and needs no number; a count only earns its
                // space once more than one person has picked the same glyph.
                if (reaction.count > 1) {
                    Text(
                        reaction.count.toString(),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, end = 2.dp),
                    )
                }
            }
        }
    })
}

@Composable
private fun AppleTapback(
    reactions: List<ReactionSummary>,
    settleKey: String,
    animateSettle: Boolean,
    visible: Boolean,
    onGone: () -> Unit,
    myHandles: Set<String>,
    mine: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // A tapback is coloured by *who put it there*, not by whose message it landed on: one you
    // placed is the blue of your own bubbles wherever it sits, and one of theirs is the colour
    // their messages come in. Read off the same palette the bubbles use, so a recoloured
    // conversation recolours its reactions with it.
    val ours = reactions.any { it.isMine(myHandles) }
    val tapbackColors = useAppleBubbleColors(mine = ours)
    val tapbackBorder = MaterialTheme.colorScheme.background
    SettleIn(settleKey = settleKey, animateSettle = animateSettle, visible = visible, onGone = onGone, modifier = modifier, content = {
        // No trailing bead. iMessage draws two shrinking circles below its tapback; at this size
        // they read as a speck of dirt on the bubble rather than as part of the badge.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // A lone glyph is narrower than the badge's minimum width; without this it settles
            // against the left edge instead of sitting in the middle of the circle.
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .height(TapbackSize)
                .widthIn(min = TapbackSize)
                .clip(RoundedCornerShape(TapbackSize / 2))
                .background(tapbackColors.background)
                .border(2.dp, tapbackBorder, RoundedCornerShape(TapbackSize / 2))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 7.dp),
        ) {
            reactions.forEach { reaction ->
                Text(reaction.emoji, fontSize = 12.sp)
                if (reaction.count > 1) {
                    Text(
                        reaction.count.toString(),
                        fontSize = 11.sp,
                        color = tapbackColors.text,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
    })
}

/**
 * A conversation event, centred in its own soft pill — a rename, someone joining or leaving.
 *
 * The pill rather than a bare line of text (which is what an unsent notice gets): a system event is
 * a thing that happened rather than a gap where a message was, and over a patterned wallpaper plain
 * centred text is hard to read.
 *
 * [plain] drops the pill for a bare centred line, the way iMessage draws every inline notice —
 * matching the unsent tombstone so the two read as one kind of thing.
 */
@Composable
private fun SystemNotice(text: String, plain: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = if (plain) {
                Modifier
            } else {
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            },
        )
    }
}

/**
 * iMessage's tombstone: a centered grey line rather than a bubble of its own.
 *
 * It fades up rather than appearing outright. The bubble it replaces is gone the moment the unsend
 * lands — there is no crossfading the two, since only one of them exists at a time — but the row's
 * height is easing down around it, and a line that snaps in at full strength while the space under
 * it is still closing reads as two unrelated events instead of one.
 */
@Composable
private fun UnsentNotice(
    who: String,
    text: String,
    attachment: Attachment?,
    animateIn: Boolean,
    shown: Boolean,
    onToggle: (() -> Unit)?,
) {
    val arrived = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) { if (animateIn) arrived.animateTo(1f, tween(TombstoneFadeMs, delayMillis = TombstoneDelayMs)) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = arrived.value },
    ) {
        // The notice keeps its own generous inset, the way a date separator does.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        ) {
            Text(
                "$who unsent a message",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            ShowUnsentLink(text = text, attachment = attachment, shown = shown, onToggle = onToggle)
        }
        // What it reveals is not drawn here at all: it is a bubble, and it belongs to the row for
        // the reasons set out on [UnsentReveal].
    }
}

/**
 * The link that reveals what a retracted message said.
 *
 * Drawn only where there is something to reveal: a message unsent before the words were kept has an
 * empty tombstone, and offering to show nothing is worse than not offering.
 */
@Composable
private fun ShowUnsentLink(text: String, attachment: Attachment?, shown: Boolean, onToggle: (() -> Unit)?) {
    // Something to reveal is either words or a payload; a message unsent before either was kept has
    // neither, and offering to show nothing is worse than not offering.
    if (onToggle == null || !canRevealUnsent(text, attachment)) return
    // The conversation's own colour where it has one, so the link reads as part of this thread.
    val accent = LocalChatTheme.current?.mine ?: MaterialTheme.colorScheme.secondary
    Text(
        if (shown) "Hide" else "Show",
        color = accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** Whether a retracted message kept anything worth offering to show. */
fun canRevealUnsent(text: String, attachment: Attachment?): Boolean = text.isNotBlank() || attachment != null

/**
 * What it used to say, once asked for.
 *
 * Dotted rather than filled: this is not a message any more, and a solid bubble would put it back
 * in the conversation as though it had never been taken out of it.
 *
 * Drawn by the *row*, beside the tombstone rather than inside it, and for the same reason
 * [EditHistoryStack] is: the row eases its own height through a change, and a reveal growing inside
 * that easing is two animations on two clocks for one movement. The bubble arrived at its full
 * height on the first frame, laid over the message above it, while the row was still catching up —
 * and then the whole conversation lurched as it caught up. Out here the expansion is the only thing
 * claiming height, so on a reversed list the row opens upward at exactly the rate the bubble is
 * uncovered, which is the message coming out from behind the conversation rather than being
 * inserted into it.
 */
@Composable
fun UnsentReveal(text: String, attachment: Attachment?, shown: Boolean, mine: Boolean, time: String) {
    AnimatedVisibility(
        visible = shown && canRevealUnsent(text, attachment),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        // The conversation's own bubble, outlined. It was a message, and the only thing that
        // stopped being true about it is that it is still there — so it is drawn by the same
        // composable, which is what keeps its shape, padding and text size exactly a message's.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        ) {
            OutlinedBubble(text = text, attachment = attachment, mine = mine, time = time)
        }
    }
}

@Composable
private fun DeletedBubble(
    mine: Boolean,
    text: String,
    attachment: Attachment?,
    bubbleType: BubbleStyle,
    animateIn: Boolean,
    onBubbleLayout: ((LayoutCoordinates) -> Unit)?,
    shown: Boolean,
    onToggle: (() -> Unit)?,
) {
    // Same reasoning as the Apple tombstone above: the row is still resizing around it.
    val arrived = remember { Animatable(if (animateIn) 0f else 1f) }
    LaunchedEffect(Unit) { if (animateIn) arrived.animateTo(1f, tween(TombstoneFadeMs, delayMillis = TombstoneDelayMs)) }
    // All three are called unconditionally — they're composables — and the active style picks the winner.
    val whatsapp = useWhatsAppBubbleColors(mine)
    val google = useGoogleBubbleColors(mine)
    val apple = useAppleBubbleColors(mine)
    val colors: BubbleColors = when (bubbleType) {
        BubbleStyle.WHATSAPP -> whatsapp
        BubbleStyle.GOOGLE -> google
        BubbleStyle.APPLE -> apple
    }

    Column(
        // The same rhythm an ordinary bubble keeps. Without the vertical half of it two tombstones
        // in a row shared an edge, which read as one tall empty bubble rather than two messages.
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = arrived.value }
            .padding(horizontal = 8.dp, vertical = TombstoneGap),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = 280.dp)
                // Outlined in its own style's bubble shape rather than filled: a filled one reads
                // as a message that is still there, which is the one thing this is not.
                .border(1.5.dp, colors.background, tombstoneShape(bubbleType))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .onGloballyPositioned { onBubbleLayout?.invoke(it) },
        ) {
            Icon(Icons.Default.Cancel, contentDescription = null, tint = colors.meta, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                if (mine) "You deleted this message" else "This message was deleted",
                color = colors.meta,
                fontStyle = FontStyle.Italic,
                fontSize = 14.sp,
            )
            // The same offer the Apple tombstone makes. Whether the words can be shown is a
            // question about what was stored, not about which bubble style is switched on.
            ShowUnsentLink(text = text, attachment = attachment, shown = shown, onToggle = onToggle)
        }
    }
}

/**
 * The two ends a bubble's place in its row is measured between — see `bubbleInRow` in
 * [MessageBubble]. Plain fields, not state: they are handles to read from, and the rect worked out
 * from them is what anything draws off.
 */
private class BubbleInRow {
    var row: LayoutCoordinates? = null
    var bubble: LayoutCoordinates? = null

    /** The bubble's box in the row's own space, or null until both have been laid out. */
    fun rect(): Rect? {
        val row = row?.takeIf { it.isAttached } ?: return null
        val bubble = bubble?.takeIf { it.isAttached } ?: return null
        return Rect(row.localPositionOf(bubble, Offset.Zero), bubble.size.toSize())
    }
}
