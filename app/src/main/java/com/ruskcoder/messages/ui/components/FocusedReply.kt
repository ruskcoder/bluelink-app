package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import com.ruskcoder.messages.ui.theme.LocalSettings
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.ruskcoder.messages.data.model.BubbleStyle
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.Message
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * A message lifted into the focused-reply overlay, and where it came from.
 *
 * [originTop] is where the message's *bubble* sat in the thread at the moment it was lifted, in
 * root coordinates — not the top of its row, which on an Apple reply is a quote's height higher up.
 * It is `null` for the reply itself, which had no place in the conversation until it was sent.
 *
 * [originX] is how far the reply swipe had dragged the bubble sideways when it let go. The lift
 * starts from there rather than waiting for the bubble to spring back first, so the swipe and the
 * flight out of the thread are one movement.
 *
 * [isFirstInRun] / [isLastInRun] are the message's place in its run back in the conversation — the
 * shape it is wearing at the moment it is picked up, which it then rounds itself out of on the way.
 */
data class FocusedEntry(
    val message: Message,
    val originTop: Float?,
    val originX: Float = 0f,
    val isFirstInRun: Boolean = true,
    val isLastInRun: Boolean = true,
    /**
     * Whether this message's quote pointed at the bubble directly above it back in the thread.
     *
     * Carried up with the message because it changes how the quote is drawn — a hollow bubble above
     * it, or a connector reaching for a row that is no longer there — and a lifted message that
     * answers this differently from its row is a message that changes shape as you pick it up.
     */
    val quoteAdjacent: Boolean = false,
    /** True for a thread lift, where every quote points at the bubble directly overhead. */
    val hideQuote: Boolean = false,
    /**
     * Who sent it, in a group — the avatar beside the bubble and the name above it.
     *
     * Lifted without one, an incoming message arrived in the stack stripped of both: it lost the
     * gutter it was indented by and so didn't even start from where it had been sitting. The whole
     * attributed row travels, which is what keeps the avatar on the bubble's own line while the
     * bubble does its sideways flight.
     */
    val sender: GroupMember? = null,
    /** And who wrote the message it quotes, so the outline above it keeps its face too. */
    val quotedSender: GroupMember? = null,
    /**
     * The tail and the face its row wears because a connector leaves from it — see
     * [MessageThreadState.wearsForcedTail]. Carried by a message lifted on its own, which is drawn
     * exactly as its row is. A thread stack reads its own shape and leaves these off: nothing in the
     * stack is joined by a line.
     */
    val forcedTail: Boolean = false,
    val forcedAvatar: Boolean = false,
)

/**
 * The shape a message is drawn with back in the conversation: its place in its run, and the tail
 * and the face a connector can add to that. What a lifted bubble changes back into on its way home.
 */
data class HomeShape(
    val isFirstInRun: Boolean,
    val isLastInRun: Boolean,
    val forcedTail: Boolean = false,
    val forcedAvatar: Boolean = false,
)

/** Gap between the lifted stack and the top of the send bar. */
private val StackGap = 14.dp

/** Gap between the quoted message and the reply underneath it. */
private val EntryGap = 6.dp

/**
 * How long a lift takes, out and back.
 *
 * Public because the conversation behind the lift goes out of focus on exactly this clock, and it
 * is blurred by the screen rather than by this overlay — see the note on the scrim below. Two
 * copies of these numbers would be two things that had to be kept in step by hand.
 */
const val FOCUS_OPEN_MS = 620
const val FOCUS_CLOSE_MS = 420

private const val OPEN_MS = FOCUS_OPEN_MS
private const val CLOSE_MS = FOCUS_CLOSE_MS

/** How long the stack takes to re-settle when a reply joins it and pushes the quote up. */
private const val RESTACK_MS = 380

/**
 * Off the mark quickly and a very long, soft landing — the bubble is most of the way there within
 * the first third of the animation and spends the rest of it slowing down. The near-1 second
 * control point is what flattens the tail: by the last quarter the bubble is barely moving, so the
 * stack reads as settling into place rather than arriving at it.
 */
private val OpenEasing = CubicBezierEasing(0.1f, 0.94f, 0.06f, 1f)
private val CloseEasing = CubicBezierEasing(0.05f, 0.75f, 0.15f, 1f)

/**
 * The two axes are driven off the same progress at different rates, which is what bends the flight
 * into a curve instead of running it along a straight line: sideways resolves early (an exponent
 * below one), the drop builds late (one above it). Traced out, the bubble leaves along the swipe
 * and then dives for the send bar — the downward exponential the path is meant to read as.
 */
private const val HORIZONTAL_BIAS = 0.42f
private const val VERTICAL_BIAS = 1.75f


/**
 * The focus-thread reply overlay: the message being replied to lifts out of the conversation and
 * settles just above the send bar while everything behind it blurs away, and the reply lands
 * directly beneath it — the two of them isolated from the thread they came from. Ported from
 * `focused-reply.tsx`.
 *
 * Each bubble animates independently, which is what lets them return correctly on close: two
 * messages that sat next to each other travel together, while ones with a stretch of conversation
 * between them separate on the way back, one rising and one falling to wherever the thread has been
 * scrolled to.
 *
 * The header above this overlay and the send bar below it are both left live — a focused reply
 * isolates the *conversation*, not the screen.
 */
@Composable
fun FocusedReplyOverlay(
    entries: List<FocusedEntry>,
    /**
     * Distance from the bottom of the screen to the top of the send bar, in pixels. The bar rides
     * on the keyboard, and the keyboard is animating at exactly the moment this overlay opens, so
     * this is re-read as it moves: the lifted bubbles fly straight to where the bar is going to land
     * rather than aiming at the wrong place and correcting once the keyboard settles.
     */
    composerInset: Float,

    /**
     * Where an entry's bubble should fly back to, resolved at the moment the overlay closes so it
     * reflects where the thread has actually scrolled to. `null` means "not measurable".
     */
    measureReturn: (String) -> Float?,
    /**
     * The shape each message has in the conversation right now, asked for as the stack closes.
     *
     * A thread stack redraws its messages against each other, and left in that shape they landed
     * wearing a name or a tail their rows do not — which the row then took away on the frame the
     * overlay was removed. So each changes back as it sets off, while it is moving. Asked at that
     * moment rather than when the stack was built: a message sent into the stack had no row to ask
     * about until it had been sent. `null` keeps the shape the bubble already has.
     */
    homeShape: (Message) -> HomeShape? = { null },
    /**
     * How far the stack has moved from resting on the send bar (0) to the middle of the screen (1).
     *
     * A focused reply is about to be typed into, so it sits where the typing will be. A thread
     * lifted for *reading* — with no keyboard up — has no such anchor, and parked against a send
     * bar resting on the navigation bar it hung off the bottom of the screen with the whole of it
     * empty above. Centred, the thread is simply the thing on screen.
     *
     * A share rather than a switch, so the screen can hand over the keyboard's own progress while
     * the keys are leaving — see [following].
     */
    centering: Float = 0f,
    /**
     * Whether [composerInset] and [centering] are tracking something that is moving on its own
     * frames — the keyboard on its way down — and should be followed exactly rather than eased to.
     *
     * Eased on the stack's own clock, the move off the bar ran on a different curve and a different
     * duration from the keys it was answering, and finished after them. Followed, the stack and the
     * keyboard are one movement.
     */
    following: Boolean = false,
    /**
     * How much of the top of the screen the header covers, in pixels.
     *
     * The floor a scrolled stack stops against. A thread longer than the screen has to be able to
     * travel, and it has to stop somewhere — this is where: the oldest message comes to rest just
     * clear of the app bar rather than running on up behind it and out of reach.
     */
    topInset: Float = 0f,
    /** Raised by the screen to start the closing animation. */
    closing: Boolean,
    /** Asks the screen to start closing (tapping the dimmed conversation behind the stack). */
    onRequestClose: () -> Unit,
    /** Runs once every bubble has landed back in the thread. */
    onClosed: () -> Unit,
    /**
     * A tap on one of the stacked bubbles: the overlay closes onto that message.
     *
     * The second argument is where that bubble's top edge rests in the stack. The conversation
     * behind is scrolled so the tapped message lands exactly there, and the bubble you pressed
     * then has nowhere to travel on the way home — it stays perfectly still while the rest of the
     * stack flies back around it, and the thread reassembles under a message that never moved.
     */
    onMessageClick: (Message, bubbleTop: Float) -> Unit = { _, _ -> },
    /** Our own handles, so a reaction we placed reads as ours — the thread's rule, unchanged. */
    myHandles: Set<String> = emptySet(),
) {
    val density = LocalDensity.current
    /** Measured heights per entry, needed before the stack's resting positions can be computed. */
    var heights by remember { mutableStateOf(mapOf<String, Float>()) }
    /** Each bubble's own box inside its wrapper — what a tap on the stack is tested against. */
    var boxes by remember { mutableStateOf(mapOf<String, Rect>()) }
    /**
     * Raised once the conversation behind has had its chance to settle for the close. Every bubble
     * then sets off home, and asks where its row is on every frame of the way.
     */
    var returning by remember { mutableStateOf(false) }
    val scrim = remember { Animatable(0f) }

    /**
     * Each entry's resting position *relative to the send bar*, stacked upward from it. Kept
     * relative on purpose: the bar itself moves with the keyboard, and only this part — which entry
     * sits how far above it — is settled here. Recomputed whenever an entry is added, which is what
     * makes the quoted message rise to make room for the reply.
     */
    val offsets = remember(entries, heights, density) {
        val stackGapPx = with(density) { StackGap.toPx() }
        val entryGapPx = with(density) { EntryGap.toPx() }
        val positions = mutableMapOf<String, Float>()
        var bottom = -stackGapPx
        for (index in entries.indices.reversed()) {
            val id = entries[index].message.id
            // Nothing is placed until it has been measured; until then it stays where it started.
            val height = heights[id] ?: continue
            positions[id] = bottom - height
            bottom -= height + entryGapPx
        }
        positions
    }

    LaunchedEffect(closing) {
        scrim.animateTo(if (closing) 0f else 1f, tween(if (closing) CLOSE_MS else OPEN_MS))
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenHeightPx = constraints.maxHeight.toFloat()

        /**
         * The line the stack is built up from: the send bar's top edge, or the point that leaves
         * the stack centred on the screen.
         *
         * [offsets] are relative to this and stay so, which is what lets the anchor move — with the
         * keyboard, or between the two modes — without any of the stacking having to be redone.
         * The stack runs from `anchor + the topmost offset` down to `anchor - StackGap`, so
         * centring it is a matter of solving for the anchor that puts that span's middle on the
         * screen's.
         */
        val measured = entries.isNotEmpty() && offsets.size == entries.size

        /**
         * How tall the stack is, and how much room there is between the header and the send bar to
         * put it in.
         *
         * A lifted thread is as long as the conversation made it, and there is no reason it should
         * fit. Once it doesn't, two things change: the stack stops trying to centre itself — there
         * is no centre to find when both ends are off the screen — and it starts scrolling instead.
         */
        val stackSpan = if (measured) -offsets.values.min() + with(density) { StackGap.toPx() } else 0f
        val room = (screenHeightPx - composerInset) - topInset
        val overflowing = measured && stackSpan > room

        val barAnchor = screenHeightPx - composerInset
        val centreAnchor = if (measured && !overflowing) {
            screenHeightPx / 2f - (offsets.values.min() - with(density) { StackGap.toPx() }) / 2f
        } else {
            barAnchor
        }
        val anchor = barAnchor + (centreAnchor - barAnchor) * centering.coerceIn(0f, 1f)

        /**
         * How far the stack has been dragged, and how far it is allowed to go.
         *
         * Zero rests the newest message against the send bar, which is where a thread you have just
         * opened wants to be: it is the end of the conversation, and the end is what you are about
         * to add to. Dragging down walks back through it, and the travel is capped at exactly the
         * amount that brings the oldest message level with the header — so the far end of a thread
         * can always be reached, and neither end can be pulled past its own stop.
         */
        val maxScroll = if (overflowing) stackSpan - room else 0f
        var scroll by remember { mutableFloatStateOf(0f) }
        // Re-clamped rather than only clamped on drag: the keyboard opening under an open thread
        // changes how much room there is, and a scroll that was legal a moment ago may no longer be.
        val settledScroll = scroll.coerceIn(0f, maxScroll)
        // A reply joining the stack pushes it up; the thing you just sent is the thing you want to
        // see, so the view goes back to the end of the thread to meet it.
        LaunchedEffect(entries.size) { scroll = 0f }

        /**
         * The anchor as the bubbles actually see it: snapped until the stack has been measured,
         * glided every time after.
         *
         * Both halves matter. The lift is aimed at wherever this stands on the frame it starts, so
         * the first reading — which arrives late, with the heights — has to be in place before
         * anything moves, or every bubble sets off toward a line it is about to leave. And every
         * reading after it is a correction to a stack already on screen: a keyboard opening under a
         * thread, a reply joining it, a send bar that turned out taller than the remembered one.
         * Those are movements, and cutting to them reads as the stack flinching.
         */
        val stackAnchor = remember { Animatable(anchor) }
        var primed by remember { mutableStateOf(false) }
        LaunchedEffect(anchor, measured, following) {
            when {
                // Kept level with the line it is following, so that when the following stops there
                // is nothing left for the ease to do.
                following -> stackAnchor.snapTo(anchor)
                primed -> stackAnchor.animateTo(anchor, tween(RESTACK_MS, easing = OpenEasing))
                else -> {
                    stackAnchor.snapTo(anchor)
                    primed = measured
                }
            }
        }
        /**
         * The anchor as drawn on this frame. While following, the line itself rather than the
         * animatable chasing it: the effect above runs a frame after the composition that moved the
         * line, and a stack a frame behind the keyboard is a stack visibly trailing it.
         */
        val anchorNow = if (following) anchor else stackAnchor.value

        // On the way out each bubble is asked separately where it belongs now; whichever direction
        // that turns out to be is the direction it travels. A row that can't be measured — deleted,
        // or outside the laid-out thread — leaves past the bottom edge instead of snapping, since
        // the stack rests just above the send bar and that is always the nearer way out.
        LaunchedEffect(closing) {
            if (!closing) return@LaunchedEffect
            // The thread is scrolled back to its tail as the overlay closes; two frames here — one
            // for the scroll to apply, one for the rows to be laid out at their new positions — let
            // that land before the rows are asked where they are.
            withFrameNanos { }
            withFrameNanos { }
            returning = true
        }

        /**
         * Which stacked bubble a point on the screen belongs to, and where that bubble's top edge
         * is — or `null` for the empty space around them.
         *
         * Resolved here rather than by the bubbles themselves, and deliberately so: every bubble is
         * a full-width wrapper with the message somewhere inside it, and giving those wrappers a
         * gesture of their own would put a pointer target over the whole screen — the topmost one
         * wins a hit outright, so a tap in the empty half of a row would land on the wrapper and
         * die there instead of falling through to the scrim that closes the overlay. The stack's
         * resting geometry is all here anyway: the anchor, each entry's offset against it, and the
         * box each bubble reported inside its own wrapper.
         */
        val bubbleAt: (Offset) -> Pair<Message, Float>? = { at ->
            val top = anchorNow + settledScroll
            entries.asReversed().firstNotNullOfOrNull { entry ->
                val offset = offsets[entry.message.id]
                val box = boxes[entry.message.id]
                if (offset == null || box == null) {
                    null
                } else {
                    val rect = box.translate(0f, top + offset)
                    if (rect.contains(at)) entry.message to rect.top else null
                }
            }
        }
        val probe by rememberUpdatedState(bubbleAt)

        // Nothing behind the overlay answers a touch.
        //
        // The scrim above this takes the gestures it has a use for, but only those: with a stack
        // short enough not to need scrolling its drag handler is switched off, and an unconsumed
        // drag fell straight through to the conversation underneath — which scrolled, out of
        // focus, behind the thread that had just been lifted out of it. Anything the scrim leaves
        // ends here, so the only live things on the screen are the ones drawn over the blur: the
        // stack, the header above it and the send bar below.
        //
        // Under the scrim rather than over it, which is what makes it a floor instead of a lid:
        // events reach the topmost node first, so the taps and drags the overlay wants are still
        // answered up there and only what is left over arrives down here to be swallowed.
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
                },
        )

        // Tapping the dimmed conversation puts the message back — the header above this overlay
        // stays live, so a floating close button would only be in its way.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = scrim.value }
                // The drag goes on the scrim rather than on the bubbles because the bubbles answer
                // no gestures at all — they are drawn over this and a press on one falls straight
                // through to here. Which is also what lets a tap anywhere close the overlay while a
                // drag anywhere scrolls it: the two are the same target, told apart by movement.
                .draggable(
                    state = rememberDraggableState { delta ->
                        scroll = (scroll + delta).coerceIn(0f, maxScroll)
                    },
                    orientation = Orientation.Vertical,
                    enabled = overflowing,
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close reply",
                    onClick = onRequestClose,
                )
                // A tap that lands on one of the lifted bubbles means that message rather than the
                // overlay: it closes onto it instead of simply putting everything back.
                //
                // Innermost of the three, so it is offered the touch before the close above it and
                // can take it away. Only the lift-off is claimed, never the press: leaving the down
                // alone is what lets a drag that began on a bubble go on scrolling the stack, and
                // what lets a tap on the empty space around one reach the close underneath.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val hit = probe(down.position) ?: return@awaitEachGesture
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        up.consume()
                        onMessageClick(hit.first, hit.second)
                    }
                },
        ) {
            // A dim, and only a dim.
            //
            // The blur belongs to the conversation itself now, applied by the screen to the thread
            // — see `ChatScreen`'s focus blur. It used to be here, as a blurred replay of the
            // recording the frosted bars sample, and that recording is taken *around* this overlay:
            // replaying it here meant a lifted bubble was drawn outside the very recording the
            // header reads, so every message that flew out of the conversation vanished from under
            // the app bar the instant it left, and the bar went on showing a sharp, undimmed
            // conversation while everything below it was blurred. With this overlay inside the
            // recording and the blur on the thread, the bar shows what it should: the conversation
            // going out of focus, with the bubbles travelling across it.
            Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)))
        }

        // Above the topmost bubble in the stack, which is where the oldest message rests. Only
        // once everything has been measured — until then there is no stack to head.
        //
        // The stack's top is remembered rather than read straight off [measured]: a reply joining
        // the stack leaves it unmeasured for a frame, and a header gated on that left composition
        // and came back in from off the top of the screen. It stays put instead, and rides the
        // restack up with the bubbles.
        val oldest = entries.firstOrNull()?.message
        var headerOffset by remember { mutableStateOf<Float?>(null) }
        LaunchedEffect(measured, offsets) {
            if (measured) headerOffset = offsets.values.min()
        }
        val topOffset = headerOffset
        if (topOffset != null && oldest != null && oldest.createdAt > 0L) {
            FocusedHeader(
                at = oldest.createdAt,
                stackBase = anchorNow + settledScroll,
                topOffset = topOffset,
                closing = closing,
            )
        }

        val lastId = entries.lastOrNull()?.message?.id
        entries.forEach { entry ->
            key(entry.message.id) {
                FocusedBubble(
                    entry = entry,
                    // The scroll rides on the anchor rather than on each bubble: every one of them
                    // is placed against this line, so moving the line moves the stack as one piece
                    // and nothing inside it can drift out of order.
                    composerTop = anchorNow + settledScroll,
                    offset = offsets[entry.message.id],
                    returning = returning,
                    landingNow = { measureReturn(entry.message.id) },
                    homeShape = if (returning) homeShape(entry.message) else null,
                    screenHeightPx = screenHeightPx,
                    onHeight = { height ->
                        heights = if (heights[entry.message.id] == height) heights else heights + (entry.message.id to height)
                    },
                    onBubbleBox = { box ->
                        boxes = if (boxes[entry.message.id] == box) boxes else boxes + (entry.message.id to box)
                    },
                    // One callback for the whole overlay, fired by whichever bubble is last in the stack.
                    onReturned = if (entry.message.id == lastId) onClosed else null,
                    myHandles = myHandles,
                )
            }
        }
    }
}


/**
 * The date the lifted stack happened on, dropped in over the top of it.
 *
 * A conversation carries its own sense of when: the separators running through it say which day
 * you are looking at, and every message near the one you pressed is another cue. A lift takes all
 * of that away at once — everything but the stack is behind a blur — and what is left is a handful
 * of bubbles with no more date on them than the clock in the status bar.
 *
 * So the separator comes too, in whichever of the three forms the style in use draws it: the same
 * [SectionBreakLabel] the thread itself heads its sections with, not a second copy of it.
 *
 * It enters from off the top of the screen rather than fading in where it belongs, because the
 * bubbles under it are travelling and a header that simply appeared over moving messages read as a
 * caption stamped on top of them rather than as part of the same movement. Same duration, same
 * easing, opposite edge: everything arrives together.
 */
@Composable
private fun FocusedHeader(
    /** When the oldest message in the stack was sent. */
    at: Long,
    /** The line the stack is placed against, in screen coordinates. */
    stackBase: Float,
    /** The stack's top edge relative to [stackBase] — the topmost bubble's resting offset. */
    topOffset: Float,
    /** Raised while the whole overlay is on its way out, which sends this back the way it came. */
    closing: Boolean,
) {
    val bubbleType = LocalSettings.current.bubbleType
    var height by remember { mutableFloatStateOf(0f) }
    /** The offset as drawn — eased on the bubbles' own restack clock, so it rises with them. */
    val rest = remember { Animatable(topOffset) }
    LaunchedEffect(topOffset) {
        rest.animateTo(topOffset, tween(RESTACK_MS, easing = OpenEasing))
    }
    /** 0 with the header off the top of the screen, 1 with it resting above the stack. */
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(closing) {
        if (closing) {
            arrival.animateTo(0f, tween(CLOSE_MS, easing = CloseEasing))
        } else {
            arrival.animateTo(1f, tween(OPEN_MS, easing = OpenEasing))
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { height = it.height.toFloat() }
            .graphicsLayer {
                // Its own height above the screen's edge is far enough to be genuinely gone —
                // starting it at zero has the first frame drawn across the header behind it.
                val from = -height
                val stackTop = stackBase + rest.value
                translationY = from + (stackTop - height - from) * arrival.value
                // Fades over the first part of the trip only, so most of what you see is travel.
                alpha = (arrival.value * 2.2f).coerceAtMost(1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        SectionBreakLabel(at = at, bubbleType = bubbleType)
    }
}

/** One lifted bubble, owning the animated position it travels along. */
@Composable
private fun FocusedBubble(
    entry: FocusedEntry,
    /** The line the stack rests on: the send bar's top edge, or the screen's own middle. */
    composerTop: Float,
    /** Resting position relative to the send bar, or `null` until the bubble is measured. */
    offset: Float?,
    /** Raised once, when the overlay closes: every bubble sets off home on it. */
    returning: Boolean,
    /**
     * Where this bubble's row is *now*, or `null` for one the thread cannot place. Read on every
     * frame of the trip home rather than once as it starts — see the layer below.
     */
    landingNow: () -> Float?,
    /** The shape to change back into on the way home, or `null` to keep this one. */
    homeShape: HomeShape?,
    screenHeightPx: Float,
    onHeight: (Float) -> Unit,
    /** The bubble's own box within this wrapper, which is the part of it a tap belongs to. */
    onBubbleBox: (Rect) -> Unit,
    onReturned: (() -> Unit)?,
    myHandles: Set<String>,
) {
    /**
     * How far down this wrapper the bubble itself begins.
     *
     * What travels is the wrapper, but what has to land on the row is the bubble inside it — and the
     * two are not the same place. The quote an Apple reply hangs above its bubble is left behind
     * here and is still there in the thread, so a wrapper flown to the bubble's own position put the
     * bubble a quote's height below where it belonged; aimed at the row's top instead it sat a
     * quote's height above it. Measured on both sides, whatever either is carrying cancels out.
     */
    val placement = remember { BubbleInset() }
    LaunchedEffect(placement.bubble) { onBubbleBox(placement.bubble) }

    // A lifted message starts exactly where it sat in the thread — including however far the reply
    // swipe had dragged it sideways. The reply, which was never there, has no flight at all: it
    // appears in its place in the stack and grows out of its corner, the way every message sent from
    // the conversation itself arrives. See [arrival].
    /**
     * Where this bubble sets off from, read at draw time rather than at composition.
     *
     * [BubbleInset.inset] is measured, so on the very first composition it is still zero — and a
     * start position computed then put the bubble a quote's height out of place for exactly one
     * frame before the real reading arrived and moved it. That single wrong frame, at the head of
     * the flight, is the flinch you see when a message is picked up. Read from inside the graphics
     * layer it is never stale: the layer is evaluated after layout, which is when the measurement
     * lands.
     */
    fun startY(resting: Float): Float = entry.originTop?.let { it - placement.inset } ?: resting
    val startX = if (entry.originTop != null) entry.originX else 0f
    /** Whether this is the reply just sent from the overlay, rather than a message lifted into it. */
    val isNewReply = entry.originTop == null
    /**
     * Whether the reply swipe this lift came out of had moved the bubble alone.
     *
     * Apple splits the gesture: the message slides and the face and the name beside it stand still
     * — see [AppleBubble]'s `placed`. The other two styles offset the whole row, so there the
     * wrapper is the right thing to carry it.
     */
    val splitSwipe = LocalSettings.current.bubbleType == BubbleStyle.APPLE
    /** How far along the trip out of the thread this bubble is: 0 at [startY], 1 in the stack. */
    val lift = remember { Animatable(0f) }
    /** The resting offset itself, animated so a reply joining the stack pushes the quote up. */
    val rest = remember { Animatable(0f) }
    /** 0 while the overlay is open, 1 once the bubble has landed back in the thread. */
    val drop = remember { Animatable(0f) }
    val opacity = remember { Animatable(1f) }
    /**
     * How far a just-sent reply has grown in: 0 a speck in the send button's corner, 1 full size.
     *
     * The same entrance, clock and curve as a message sent from the conversation — [ArrivalScale],
     * [ArrivalMs], [ArrivalEasing] — so sending feels like one thing wherever it is done from. It
     * used to rise from under the send bar on the lift's long open curve and fade in as it went,
     * which is how a *lifted* message moves, and read as the reply being fetched rather than sent.
     * Already 1 for a lifted message, which is full size from its first frame.
     */
    val arrival = remember { Animatable(if (isNewReply) 0f else 1f) }
    var placed by remember { mutableStateOf(false) }

    /**
     * Whether the bubble has set off home.
     *
     * Raised by the close only once [flight] has recorded where the bubble is on screen, and the
     * shape it is drawn in switches to its row's on this same flag — so that recording is always
     * taken in the shape the bubble was actually shown in. See the layer below for why it matters.
     */
    var homing by remember { mutableStateOf(false) }
    val flight = remember { Flight() }

    /**
     * What is left of the swipe, which decays to nothing over the first part of the flight.
     *
     * Given to the bubble rather than to the wrapper on Apple. Carried by the wrapper, the face and
     * the name — which had been standing still in the thread while the message slid out from
     * between them — jumped sideways onto the bubble on the lift's first frame and then rode it all
     * the way to the stack. They have nowhere to go: they were already where they end up, and the
     * only thing with a distance to cover is the message.
     */
    fun swipeX(): Float = startX * (1f - lift.value.pow(HORIZONTAL_BIAS)) * (1f - drop.value)

    // Out of the thread — once. Keyed on having a place at all, not on the place itself.
    //
    // All three legs used to share one effect keyed on the offset, and the stack re-measures while
    // bubbles are still in the air: the entry under this one reports a frame later, a reply's text
    // wraps once it is laid out. Each of those restarted the effect, which cancelled the flight
    // part of the way up — leaving the bubble stalled wherever it had got to, and a reply that was
    // fading in stuck half transparent. The flight now runs to the end whatever the stack does.
    LaunchedEffect(offset != null) {
        val target = offset ?: return@LaunchedEffect
        if (placed) return@LaunchedEffect
        rest.snapTo(target)
        placed = true
        if (isNewReply) {
            // Nowhere to travel from, so no lift to run: straight to the end of it, and grow.
            lift.snapTo(1f)
            arrival.animateTo(1f, tween(ArrivalMs, easing = ArrivalEasing))
        } else {
            lift.animateTo(1f, tween(OPEN_MS, easing = OpenEasing))
        }
    }

    // Every later change of place: the stack making room for a reply that joined it. On its own
    // clock, so a restack never touches the flight above or the trip home below.
    LaunchedEffect(offset) {
        val target = offset ?: return@LaunchedEffect
        if (!placed || homing) return@LaunchedEffect
        rest.animateTo(target, tween(RESTACK_MS, easing = OpenEasing))
    }

    // Home. Keyed on the close alone, for the same reason as the flight out: a height changing on
    // the way down restarted the tween from wherever it had reached and ran the full duration again,
    // which read as the bubble stalling just short of its row — and reported it landed twice.
    LaunchedEffect(returning) {
        if (!returning) return@LaunchedEffect
        flight.from = flight.bubbleTop.takeUnless { it.isNaN() } ?: (landingNow() ?: screenHeightPx)
        homing = true
        if (landingNow() == null) {
            // Only a bubble with genuinely nowhere to land fades.
            launch { opacity.animateTo(0f, tween(CLOSE_MS)) }
        } else {
            // Everything else arrives fully drawn. A reply sent into the stack used to fade out
            // here as if it had no row — while that row sat invisible in the thread waiting for the
            // overlay to go: the message went out, and then came back. And one closed while it was
            // still fading in finishes doing so on the way, rather than landing translucent and
            // popping to full strength when its row takes over.
            launch { opacity.animateTo(1f, tween(CLOSE_MS)) }
        }
        // A reply the overlay is closed on while it is still growing finishes growing on the way
        // down, rather than landing on its row at a fraction of its size.
        if (arrival.value < 1f) launch { arrival.animateTo(1f, tween(CLOSE_MS, easing = ArrivalEasing)) }
        drop.animateTo(1f, tween(CLOSE_MS, easing = CloseEasing))
        onReturned?.invoke()
    }

    /**
     * The shape the bubble is drawn in: the stack's while it is in the stack, its row's on the way
     * home.
     *
     * A thread stack reads each message against its neighbours in the stack, and that is not the
     * conversation it came out of — a message mid-run back there is first of its own run up here,
     * with the name and the tail that go with it. Kept in that shape all the way down, it landed on
     * its row wearing both and lost them on the frame the overlay was removed: the blink at the end
     * of the close. Changed as it sets off, the change happens while the bubble is moving, and the
     * trip is worked out on the bubble so the change doesn't move it.
     */
    val shape = homeShape?.takeIf { homing }
        ?: HomeShape(entry.isFirstInRun, entry.isLastInRun, entry.forcedTail, entry.forcedAvatar)

    Box(
        modifier = Modifier
            // Full width so the bubble keeps the left/right alignment it has in the thread; the
            // swipe's leftover offset and the drop out of the row are laid over that.
            .fillMaxWidth()
            .onSizeChanged { onHeight(it.height.toFloat()) }
            .graphicsLayer {
                val progress = lift.value
                // Sideways first, downward after: the same progress read at two different rates,
                // which is what curves the path rather than ruling a line along it.
                val sideways = progress.pow(HORIZONTAL_BIAS)
                if (!homing) {
                    val downward = progress.pow(VERTICAL_BIAS)
                    val resting = composerTop + rest.value
                    val from = startY(resting)
                    val open = from + (resting - from) * downward
                    translationY = open
                    // Kept for the trip home, which sets off from exactly what is on screen now.
                    flight.bubbleTop = open + placement.inset
                } else {
                    // Worked out for the bubble, not the wrapper around it: the wrapper's contents
                    // change shape as the trip begins, and a position held on the wrapper moved the
                    // message by whatever that change added or took away above it.
                    //
                    // And aimed at where its row is on *this* frame, not where it was when the close
                    // began. The conversation is often still moving underneath — scrolled back to
                    // its tail for a reply, settling after the keyboard — and a landing read once was
                    // stale by the time the bubble reached it, so the row appeared somewhere else
                    // the moment the overlay was removed.
                    val target = landingNow() ?: screenHeightPx
                    val bubbleTop = flight.from + (target - flight.from) * drop.value
                    translationY = bubbleTop - placement.inset
                }
                // Read for the curve either way; only spent here on the styles that swipe the
                // whole row. See [swipeX].
                translationX = if (splitSwipe) 0f else startX * (1f - sideways) * (1f - drop.value)
                // Out of the corner it was sent from, exactly as the thread grows a sent row: the
                // wrapper is full width with the bubble against its edge, so the wrapper's bottom
                // corner on that side is the bubble's own.
                val grown = ArrivalScale + (1f - ArrivalScale) * arrival.value
                scaleX = grown
                scaleY = grown
                transformOrigin = TransformOrigin(if (entry.message.mine) 1f else 0f, 1f)
                alpha = opacity.value * arrival.value
            }
            // Inside this node's own layer, so what it reports is where the bubble sits within the
            // wrapper rather than where the flight has carried the wrapper to.
            .onGloballyPositioned { placement.box = it },
    ) {
        // Drawn exactly as it is in the conversation — same run, same tail, same corners, same
        // gaps. Lifting a message out of the thread moves it; it is not an excuse to reshape it,
        // and every attempt to round it off or drop its tail on the way read as the bubble
        // flinching as you picked it up. The only thing left behind is the quote above it, which
        // is the bubble overhead in the stack rather than part of this one.
        MessageBubble(
            message = entry.message,
            isFirstInRun = shape.isFirstInRun,
            isLastInRun = shape.isLastInRun,
            quoteAdjacent = entry.quoteAdjacent,
            hideReplyQuote = entry.hideQuote,
            // Everything the message is wearing comes up with it, the quote above it included: a
            // lifted message that redraws itself as a different message is no longer the message
            // you picked. In a thread lift the quotes are the point — they are what says which of
            // the stacked bubbles answers which.
            sender = entry.sender,
            quotedSender = entry.quotedSender,
            // Nothing in the stack is tied to anything else by a line. The stack *is* the thread,
            // in order, and a connector between two bubbles that are already touching in the right
            // order is scaffolding around something the arrangement has already said.
            drawConnector = false,
            forcedTail = shape.forcedTail,
            forcedAvatar = shape.forcedAvatar,
            revealX = if (splitSwipe) ({ swipeX() }) else null,
            myHandles = myHandles,
            onBubbleLayout = { placement.measure(it, if (splitSwipe) swipeX() else 0f) },
        )
    }
}

/**
 * Where a lifted bubble's top edge is on screen, kept by its own graphics layer, and where the trip
 * home set off from.
 *
 * Plain fields, not state: written while drawing and read once, by the effect that starts the
 * return, so there is nothing for a change to them to invalidate.
 */
private class Flight {
    var bubbleTop = Float.NaN
    var from = 0f
}

/**
 * How far down a lifted wrapper the bubble itself begins — a run gap, a sender's name in a group,
 * the space a tapback is set down in.
 *
 * Snapshot state, not a plain field: it is written from layout and read from a graphics layer, and
 * as an ordinary `var` a reading that landed after the layer had last run would never be picked up.
 */
private class BubbleInset {
    var box: LayoutCoordinates? = null

    var inset by mutableFloatStateOf(0f)
        private set

    /**
     * The bubble's whole box within the wrapper, which is what a tap is tested against.
     *
     * Empty until it has been measured, so a press that arrives before the first layout — there
     * isn't one, but the type allows it — falls through to the scrim rather than being swallowed.
     */
    var bubble by mutableStateOf(Rect.Zero)
        private set

    /**
     * [shiftedBy] is whatever of the swipe the bubble's own layer is still carrying — a coordinate
     * transform folds it in, and what a tap has to be tested against is where the bubble comes to
     * rest, not where it happens to be mid-flight.
     */
    fun measure(bubble: LayoutCoordinates, shiftedBy: Float) {
        val box = box?.takeIf { it.isAttached } ?: return
        if (!bubble.isAttached) return
        val at = box.localPositionOf(bubble, Offset.Zero)
        val origin = Offset(at.x - shiftedBy, at.y)
        inset = origin.y
        this.bubble = Rect(origin, bubble.size.toSize())
    }
}
