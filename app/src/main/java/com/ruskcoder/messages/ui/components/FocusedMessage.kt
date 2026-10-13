package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.transcribe.TranscriptionState
import kotlin.math.roundToInt

/** The reaction bar's own height — one 44dp slot plus its 6dp padding either side. */
private val ReactionBarHeight = 56.dp

/** Gap between the bubble and the two things hanging off it. */
private val Gap = 10.dp

/** Breathing room the cluster keeps from the header above and the send bar below. */
private val EdgeMargin = 12.dp

/**
 * How far down the screen the lifted bubble is allowed to rest. A message picked from the bottom of
 * the conversation rises into the upper part of the screen so the menu below it has somewhere to go
 * — and so your thumb isn't covering the message you're acting on.
 */
private const val RESTING_FRACTION = 0.42f

private const val OPEN_MS = 460
private const val CLOSE_MS = 340

/** Eased at both ends and unhurried — the lift is meant to be followed, not just completed. */
private val OpenEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/**
 * Off the mark at once and slowing all the way into the stop, rather than easing in at both ends.
 *
 * A symmetric curve over a short distance is indistinguishable from a straight line: the message
 * going home read as sliding at a constant speed and then stopping dead. Decelerating gives the
 * movement somewhere to arrive.
 */
private val CloseEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * How far the conversation behind a picked-out message is pushed back.
 *
 * Enough that the lifted message is plainly the thing in focus, and no further: the conversation is
 * the context you are acting on the message *in*, and blurring it to porridge takes that away.
 * Heavier without a recording to blur, where the film is doing the whole job on its own.
 */
private val BackdropBlur = 7.dp
private const val BackdropDim = 0.26f
private const val BackdropDimAlone = 0.42f

/** How long the menu and the reaction bar take to get out of the way of a selection. */
private const val FOLD_MS = 240
private val FoldEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * A long-pressed message, picked out of the conversation: everything behind it blurs away, the
 * bubble itself lifts off the thread and travels up to a resting place in the upper half of the
 * screen, and the reaction bar and the action menu arrive attached to it, above and below.
 *
 * The three move as one cluster — the bar and the menu are laid out around the bubble and the whole
 * column is what animates — so the menu can never end up somewhere the bubble isn't. Where the
 * cluster rests is worked out from its measured height: it prefers to leave the bubble exactly
 * where it already was, moves up only as far as it must to fit the menu on screen, and never rises
 * above the header or sinks under the send bar.
 *
 * Select text happens *here* rather than somewhere else in the app: it is about this one message,
 * and the lift is what makes the words big enough and still enough to drag a handle through. The
 * menu and the reaction bar fold out of the way for it, and the bubble stays exactly where it was
 * standing. Editing, by contrast, deliberately does not happen here — see [InlineMessageEditor].
 */
@Composable
fun FocusedMessageOverlay(
    message: Message,
    /**
     * Where the message's *bubble* sits in the thread right now, in root coordinates — not where
     * its row starts. A row is often taller than the bubble in it: an Apple reply hangs a quote
     * above it, a group adds the sender's name, a tapback opens a gap over the top corner. Aimed at
     * the row instead, a lifted message carrying a quote flew home to a point a quote's height
     * above its own bubble, and snapped down the moment the overlay was removed.
     */
    originTop: Float,
    /**
     * Where the message's bubble is in the thread *now*, read on every frame of the way home.
     *
     * [originTop] is where it was when it was pressed, and the press itself is often what moves
     * it: a long press puts the keyboard away, and the conversation drops by the keyboard's height
     * under the blur while the message is lifted. Flown back to [originTop], the bubble landed where
     * the row used to be and the real row appeared a keyboard's height below it — or, closed quickly
     * enough, cut straight there with no animation at all.
     */
    returnTop: () -> Float? = { null },
    /**
     * The message's place in its run back in the conversation. Picking a message out is not an
     * excuse to redraw it: forced to `true`/`true` the bubble grew a tail it never had, which read
     * as the message changing shape under the finger that pressed it.
     */
    isFirstInRun: Boolean,
    isLastInRun: Boolean,
    /** And whether its quote pointed at the bubble above it — the same rule, for the same reason. */
    quoteAdjacent: Boolean = false,
    /** Who sent it, in a group: the message keeps its avatar and its name where it is lifted to. */
    sender: GroupMember? = null,
    /**
     * Who wrote the message it quotes, so the outline above it keeps its face too.
     *
     * The same reason every other part of a lifted message is carried up unchanged: a bubble that
     * redraws its own reference the moment you press it is a bubble that changed shape under the
     * finger, and the outline is a gutter's width off from where it was sitting a frame earlier.
     */
    quotedSender: GroupMember? = null,
    /** The tail and the face its row wears because a connector leaves it — carried up unchanged. */
    forcedTail: Boolean = false,
    forcedAvatar: Boolean = false,
    /** Our own handles, so a reaction we placed reads as ours. */
    myHandles: Set<String> = emptySet(),
    /** The header's height: the cluster stays clear of it. */
    topInset: Dp,
    /** Everything the send bar covers, for the same reason. */
    bottomInset: Dp,
    /** Backdrop source for the blur; without one it falls back to a plain scrim. */
    backdrop: ChatBackdrop?,
    /** Emoji already on the message, or `null` if it can't carry reactions. */
    activeReactions: List<String>?,
    onPickReaction: (String) -> Unit,
    editable: Boolean,
    onReply: (() -> Unit)?,
    /** Puts the row itself into an editor; the overlay's only part in it is getting out of the way. */
    onEdit: () -> Unit,
    onCopy: (() -> Unit)?,
    /**
     * The selection the app bar's Copy button reads, or null when the conversation is not in
     * text-selection mode. Non-null is what puts the bubble's words into a field with handles.
     */
    selection: BubbleSelection? = null,
    /** Null on a message with no words in it, where highlighting part of the text means nothing. */
    onSelectText: (() -> Unit)? = null,
    /** Swaps the whole conversation into the checkbox column, this message already ticked. */
    onSelectMessages: (() -> Unit)?,
    /** Null when the message cannot be unsent, which hides the row rather than disabling it. */
    onUnsend: (() -> Unit)? = null,
    /** Null unless this is one of your own messages in a group, where "who has seen it" has an answer. */
    onInfo: (() -> Unit)? = null,
    /** Saves the message's attachment to the phone. Null on a message carrying nothing to save. */
    onDownload: (() -> Unit)? = null,
    downloadLabel: String = "Download",
    onDownloadCurrent: (() -> Unit)? = null,
    copyLabel: String = "Copy",
    /** Removes the message from this phone only — see [BubbleMenu]. */
    onDeleteForMe: (() -> Unit)? = null,
    /**
     * Where the message's transcription has got to, so a lifted voice message says what the one in
     * the thread says. A press picks a message out of the conversation; it does not get to redraw it.
     */
    transcription: TranscriptionState = TranscriptionState.OFF,
    /** Raised by the screen to start the closing animation. */
    closing: Boolean,
    /** Asks the screen to start closing — a tap anywhere off the cluster. */
    onRequestClose: () -> Unit,
    /** Runs once the bubble has landed back in the thread. */
    onClosed: () -> Unit,
) {
    val density = LocalDensity.current
    val showReactions = activeReactions != null

    /** 0 at the message's place in the thread, 1 at the cluster's resting position. */
    val lift = remember { Animatable(0f) }
    var clusterHeightPx by remember { mutableFloatStateOf(0f) }
    /**
     * Whether the cluster has been measured at all — a boolean, not the height itself.
     *
     * Keying the open animation on the raw height restarted it every time the cluster re-measured,
     * and the cluster re-measures more than once on its way in: the menu below the bubble reports
     * nothing on its first frame and its full height on the next. The restart cancelled the lift
     * mid-flight and began again from wherever it had reached, which is why a long press sometimes
     * appeared not to animate at all.
     */
    val measured = clusterHeightPx > 0f

    /** Whether the menu and the reaction bar have been sent away to make room for something. */
    val aside = selection != null
    val folded = remember { Animatable(0f) }
    LaunchedEffect(aside) { folded.animateTo(if (aside) 1f else 0f, tween(FOLD_MS, easing = FoldEasing)) }

    /**
     * The height the cluster had while the menu was still standing.
     *
     * The resting place is worked out from the cluster's height, so folding the menu away would
     * otherwise let the whole thing slide back down toward the send bar. Frozen at the moment the
     * fold starts, the bubble stays exactly where you left it.
     */
    var foldedHeightPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(aside) { if (aside) foldedHeightPx = clusterHeightPx }

    /**
     * The reaction bar's height *and the gap under it*, as measured rather than as declared.
     *
     * This is what the bubble would rise by when the bar above it folds away, and therefore exactly
     * what has to be given back to keep the message where it was standing. The gap is part of the
     * reading because it is part of the fold: the cluster used to space its three parts with
     * `spacedBy`, which keeps a gap whether or not the thing on either side of it still has any
     * height — so the gap survived the whole fold and then vanished in one frame when the folded
     * bar was finally dropped from the composition. That single frame is the jerk: the message
     * hopped up ten dip as it arrived in the middle of the screen, and back down again the instant
     * the fold began to reverse. Folding the gap along with the bar that owns it removes the step.
     */
    var barHeightPx by remember { mutableFloatStateOf(0f) }

    /** Where the bubble sits inside its row: how far to slide it to centre it, and how far down it starts. */
    val placement = remember { BubblePlacement() }

    /**
     * The close, and where it set off from — see `bubbleTop` below.
     *
     * Kept apart from [closing] so the bubble's position is recorded *before* the geometry switches
     * over to the trip home. Switched on the flag itself, a close that began part of the way up
     * jumped the bubble onto the line it was about to travel along.
     */
    var homing by remember { mutableStateOf(false) }
    var homeFrom by remember { mutableFloatStateOf(0f) }
    var homeLift by remember { mutableFloatStateOf(1f) }
    /** The bubble's top edge on the last frame drawn, kept by the layer for the close to start from. */
    val shown = remember { floatArrayOf(Float.NaN) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeightPx = constraints.maxHeight.toFloat()
        // The bar's own measured height plus the gap under it — the distance from the top of the
        // cluster down to the bubble. Measured rather than assumed, and the *same* number the fold
        // gives back below, so the two can never disagree by a few dip and leave the message
        // landing beside its own row. The constant stands in only for the frame before the bar has
        // been measured, when nothing has moved yet anyway.
        val marginPx = with(density) { EdgeMargin.toPx() }
        val topInsetPx = with(density) { topInset.toPx() }
        val bottomInsetPx = with(density) { bottomInset.toPx() }
        /** The highest the cluster may sit before it disappears under the header. */
        val ceilingPx = topInsetPx + marginPx
        val fallbackBarPx = with(density) { (ReactionBarHeight + Gap).toPx() }

        // Everything below is read while drawing, never while composing.
        //
        // The two measurements the geometry hangs on — how far down its slot the bubble begins, and
        // how tall the bar above it is — both arrive during the first layout. Worked out in
        // composition, before either had landed, the first frame was drawn with neither: the bubble
        // sat a name's height or a run gap's below its own row, and the bar's guessed height was a
        // few dip off its real one, and on the next frame both were corrected. That frame is the
        // short snap down and back before the lift begins. A graphics layer runs after layout, so it
        // sees both on the frame they arrive.

        /**
         * The reaction bar's height and the gap under it: measured, and the constant only for a
         * frame before there is a measurement. See [barHeightPx].
         */
        fun barPx(): Float = when {
            !showReactions -> 0f
            barHeightPx > 0f -> barHeightPx
            else -> fallbackBarPx
        }

        /**
         * Where the bubble rests, as its own top edge: no lower than the resting line, and the menu
         * always on screen.
         *
         * The two bounds used to be a plain `coerceIn(ceiling, floor)`, and for anything taller than
         * the viewport — a full-height photo — the floor comes out *above* the ceiling, so the clamp
         * resolved to the ceiling and the menu hung off the bottom of the screen with no way to
         * reach it. When the two disagree the floor wins: the menu is the entire reason the message
         * was picked up, and the top of a picture running off the top of the screen costs nothing
         * beside that. The reaction bar is kept on screen separately — see [barShiftFor].
         */
        fun restingBubbleTop(): Float {
            // Frozen for the length of the fold in *both* directions — see [foldedHeightPx].
            val height = if (folded.value > 0f && foldedHeightPx > 0f) foldedHeightPx else clusterHeightPx
            val above = barPx() + placement.inset
            val wanted = minOf(originTop, screenHeightPx * RESTING_FRACTION) - above
            val floor = screenHeightPx - bottomInsetPx - marginPx - height
            return minOf(wanted, floor).coerceAtLeast(minOf(ceilingPx, floor)) + above
        }

        /**
         * Where the bubble is on this frame.
         *
         * Out: from its place in the thread to its resting place, on the lift. Home: from wherever
         * it was when the close began to wherever its row is *now*, on the same lift running back
         * to zero — so a close that begins part of the way up sets off from where the bubble
         * actually is, and one that ends while the conversation is still settling underneath meets
         * the row where it has got to rather than cutting to it once the overlay is gone.
         */
        fun bubbleTop(): Float {
            val progress = lift.value
            if (!homing) return originTop + (restingBubbleTop() - originTop) * progress
            val home = returnTop() ?: originTop
            val share = if (homeLift > 0f) progress / homeLift else 0f
            return home + (homeFrom - home) * share
        }

        /**
         * The cluster column's translation: the bubble's top, less everything above the bubble in
         * the column. The bar there is only as tall as its fold leaves it, which is exactly the
         * height the fold gives back — so the message stands still while the bar folds away.
         */
        fun columnY(): Float = bubbleTop() - placement.inset - barPx() * (1f - folded.value)

        /**
         * How far the reaction bar has to slide back down to stay on screen.
         *
         * Zero for every message that fits, which is nearly all of them — the bar is then exactly
         * where the column put it, above the bubble with its gap. For a tall one the column has been
         * pushed up past the header to get the menu on screen, taking the bar with it, and this puts
         * it back at the ceiling. It then lies over the picture, which is the right trade: the bar is
         * a row of six taps, and the part of the photo behind it is part that has gone off the top
         * of the screen anyway.
         */
        fun barShiftFor(clusterTop: Float): Float = maxOf(0f, ceilingPx - clusterTop)

        LaunchedEffect(closing, measured) {
            if (closing) {
                if (!homing) {
                    homeFrom = shown[0].takeUnless { it.isNaN() } ?: originTop
                    homeLift = lift.value
                    homing = true
                }
                lift.animateTo(0f, tween(CLOSE_MS, easing = CloseEasing))
                onClosed()
            } else if (measured) {
                // Nothing moves until the cluster has been measured — until then it is sitting
                // exactly over its own row anyway, so there is nothing to see.
                lift.animateTo(1f, tween(OPEN_MS, easing = OpenEasing))
            }
        }

        // Tapping anywhere off the cluster puts the message back. Mid-edit it discards first, so a
        // stray tap doesn't fly a full-width field back down into the thread.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = lift.value }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Dismiss message menu",
                    onClick = onRequestClose,
                ),
        ) {
            if (backdrop != null) {
                Box(modifier = Modifier.matchParentSize().chatBackdropBlur(backdrop, RectangleShape, radius = BackdropBlur))
            }
            Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = if (backdrop != null) BackdropDim else BackdropDimAlone)))
        }

        Column(
            horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
            // No `spacedBy`: each satellite carries its own gap *inside* its fold — see
            // [barHeightPx] for why a gap the arrangement owns cannot fold.
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { clusterHeightPx = it.height.toFloat() }
                .graphicsLayer {
                    // The reaction bar folding away above the bubble would otherwise drag the
                    // bubble up into the space it vacated; giving that height back to the cluster
                    // as an offset leaves the message standing exactly where it was.
                    val top = bubbleTop()
                    shown[0] = top
                    translationY = top - placement.inset - barPx() * (1f - folded.value)
                },
        ) {
            // Both of these grow out of the bubble between them rather than fading in on the spot,
            // and both fold back into it once the bubble has something better to be.
            //
            // Dropped from the composition entirely once folded, not merely faded out. An invisible
            // menu is still a menu: a `graphicsLayer` alpha of zero changes nothing about hit
            // testing, and the collapsed height only shrinks what the *parent* reports — so the
            // rows carried on answering taps from behind the selection they had made way for.
            if (showReactions && folded.value < 1f) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        // Over the message rather than under it. Once the bar is sliding back down
                        // onto a picture that has been pushed up past it, whichever of the two is
                        // painted second is both the one you can see and the one that answers a
                        // tap — and a reaction bar you cannot press is not a reaction bar.
                        .zIndex(1f)
                        .graphicsLayer {
                            // Against the column's *actual* translation, fold included, so the two
                            // can never drift apart by the height the fold gives back.
                            translationY = barShiftFor(columnY())
                        }
                        .inert(aside)
                        .attachedTo(lift.value, TransformOrigin(if (message.mine) 1f else 0f, 1f))
                        .foldedAway(folded.value)
                        // Inside the fold, so what it reports is the bar's full height rather than
                        // whatever is left of it this frame — and outside the gap, so the reading
                        // is the whole distance the fold takes back.
                        .onSizeChanged { barHeightPx = it.height.toFloat() }
                        .padding(bottom = Gap),
                ) {
                    ReactionBar(visible = true, active = activeReactions, onPick = onPickReaction)
                }
            }

            // Selecting text slides the bubble to the middle of the screen and leaves it at the
            // height it was already standing at. Picking words out of a message is reading work,
            // not conversation, and a bubble pinned to its own edge puts the text under the thumb
            // that has to drag through it — on the wrong side, for half of every conversation.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { placement.row = it }
                    // Unwound by the lift as well as the fold, so going home is one diagonal
                    // movement on the close's own curve. Held at `folded` alone, the bubble stayed
                    // out in the middle of the screen for the whole descent and then jumped back to
                    // its own edge on the frame the overlay was removed.
                    .graphicsLayer { translationX = placement.shift * folded.value * lift.value },
            ) {
                FocusedBubble(
                    message = message,
                    isFirstInRun = isFirstInRun,
                    isLastInRun = isLastInRun,
                    quoteAdjacent = quoteAdjacent,
                    sender = sender,
                    quotedSender = quotedSender,
                    forcedTail = forcedTail,
                    forcedAvatar = forcedAvatar,
                    myHandles = myHandles,
                    selection = selection,
                    transcription = transcription,
                    // The sideways reading is taken only while no slide is applied — which is
                    // exactly when the product below is zero, at either end of the movement.
                    // Reading it back mid-slide would measure the shift this is being moved by and
                    // chase itself. The vertical one is safe throughout: this row's own layer moves
                    // the bubble sideways only, and the cluster's sits above both ends of the
                    // measurement, so it cancels.
                    onBubbleLayout = { coordinates ->
                        placement.measure(coordinates, slid = folded.value * lift.value != 0f)
                    },
                )
            }

            if (folded.value < 1f) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        // Inert for the whole fold, not just at the end of it: the rows are still
                        // drawn and still hit-testable while they shrink, and a menu on its way out
                        // should not be able to answer for a tap aimed past it.
                        .inert(aside)
                        .attachedTo(lift.value, TransformOrigin(if (message.mine) 1f else 0f, 0f))
                        .foldedAway(folded.value)
                        .padding(top = Gap),
                ) {
                    BubbleMenu(
                        visible = true,
                        editable = editable,
                        mine = message.mine,
                        // Apple's menu is frosted glass over the conversation rather than a Material
                        // card, so it needs the same recording the rest of the overlay blurs.
                        backdrop = backdrop,
                        onReply = onReply,
                        onEdit = onEdit,
                        onCopy = onCopy,
                        onSelectText = onSelectText,
                        onSelectMessages = onSelectMessages,
                        onUnsend = onUnsend,
                        onInfo = onInfo,
                        onDownload = onDownload,
                        downloadLabel = downloadLabel,
                        onDownloadCurrent = onDownloadCurrent,
                        copyLabel = copyLabel,
                        onDeleteForMe = onDeleteForMe,
                    )
                }
            }
        }
    }
}

/**
 * The lifted bubble: exactly the one that is in the conversation, plus whatever selection mode has
 * turned its words into.
 */
@Composable
private fun FocusedBubble(
    message: Message,
    isFirstInRun: Boolean,
    isLastInRun: Boolean,
    quoteAdjacent: Boolean,
    sender: GroupMember?,
    quotedSender: GroupMember?,
    forcedTail: Boolean,
    forcedAvatar: Boolean,
    myHandles: Set<String>,
    selection: BubbleSelection?,
    onBubbleLayout: ((LayoutCoordinates) -> Unit)? = null,
    transcription: TranscriptionState = TranscriptionState.OFF,
) {
    CompositionLocalProvider(
        LocalBubbleSelection provides selection,
        LocalTextToolbar provides NoTextToolbar,
    ) {
        // Same run, same tail. A long press picks a message out of the conversation; it does not
        // get to redraw it.
        //
        // The quote stays behind, though, and so does the connector: both belong to the row, and
        // the row now stays standing with only its bubble lifted out of it. The press used to take
        // the whole row, and a line the row drew to the message above went with it — blinking out
        // under the finger, which is the one thing a press must never do. Lifting the outline as
        // well would now draw it twice. Only Apple draws its quote outside the bubble; the other
        // styles keep theirs inside it, where it has to travel, and ignore this.
        MessageBubble(
            message = message,
            isFirstInRun = isFirstInRun,
            isLastInRun = isLastInRun,
            quoteAdjacent = quoteAdjacent,
            hideReplyQuote = true,
            sender = sender,
            quotedSender = quotedSender,
            forcedTail = forcedTail,
            forcedAvatar = forcedAvatar,
            myHandles = myHandles,
            selectable = selection != null,
            onBubbleLayout = onBubbleLayout,
            transcription = transcription,
        )
    }
}

/**
 * Where the bubble sits inside its row: how far it would have to move to be centred, and how far
 * down the row it begins.
 *
 * Both are measured rather than assumed. Every style insets its bubble by a different amount and a
 * group adds an avatar gutter, so the sideways figure cannot be derived; and what stands above the
 * bubble varies from message to message — a run gap, a sender's name, the gap a tapback is set down
 * in — so neither can the vertical one.
 */
private class BubblePlacement {
    var row: LayoutCoordinates? = null

    /**
     * Snapshot state, not plain fields: they are written from layout and read from a graphics
     * layer, and as ordinary `var`s a value that landed after the layer had last run was simply
     * never picked up — leaving the slide at zero and the bubble sitting where it started.
     */
    var shift by mutableFloatStateOf(0f)
        private set

    /** The bubble's own top, measured down from the row's. */
    var inset by mutableFloatStateOf(0f)
        private set

    fun measure(bubble: LayoutCoordinates, slid: Boolean) {
        val row = row?.takeIf { it.isAttached } ?: return
        if (!bubble.isAttached) return
        val origin = row.localPositionOf(bubble, Offset.Zero)
        inset = origin.y
        if (!slid) shift = row.size.width / 2f - (origin.x + bubble.size.width / 2f)
    }
}

/**
 * Swallows every touch aimed at a subtree, before the subtree gets a look at it.
 *
 * Consumed on the initial pass, so the children never see the event at all — the only way to make
 * something that is still composed genuinely unclickable.
 */
private fun Modifier.inert(inert: Boolean): Modifier = if (!inert) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/** Scales and fades a satellite in from the bubble's corner as the cluster settles. */
private fun Modifier.attachedTo(progress: Float, origin: TransformOrigin): Modifier = graphicsLayer {
    alpha = progress
    scaleX = 0.85f + 0.15f * progress
    scaleY = 0.85f + 0.15f * progress
    transformOrigin = origin
}

/**
 * Folds a satellite out of the cluster: its ink, its size and the height it claims all go together.
 *
 * The height is what matters — a satellite that only faded would leave its gap behind, and the
 * cluster's height is what the resting position is worked out from.
 */
private fun Modifier.foldedAway(progress: Float): Modifier = this
    .graphicsLayer {
        alpha = 1f - progress
        scaleX = 1f - 0.12f * progress
        scaleY = 1f - 0.12f * progress
    }
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height * (1f - progress)).roundToInt()
        layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
    }

