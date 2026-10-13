package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import com.ruskcoder.messages.ui.components.bubbles.useGoogleBubbleColors
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.ruskcoder.messages.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.ruskcoder.messages.data.model.BubbleStyle
import com.ruskcoder.messages.data.model.textScale
import com.ruskcoder.messages.ui.components.bubbles.useWhatsAppBubbleColors
import com.ruskcoder.messages.ui.theme.LocalSettings
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** Layout positions the gestures read imperatively, kept out of the snapshot system on purpose. */
/**
 * How far the finger has to travel after a long press before the menu it opened will answer to it.
 * Well past touch slop: this is not "is it a drag", it is "did you mean to reach for a row".
 */
private val MenuDragSlop = 16.dp

private class RowPlacement {
    /** The row's top-left in the window, which is the space the menu reads its finger in. */
    var originInWindow = Offset.Zero

    var origin: Offset = Offset.Zero
    var bubble: Rect? = null
}

private val SwipeTrigger = 40.dp

/**
 * How much further the bubble can be pushed once the reply has armed. The swipe doesn't stop dead
 * at the trigger — it gets steadily heavier and eases toward this much overshoot, so a hard pull
 * still moves something instead of hitting a wall.
 */
private val SwipeOvershoot = 30.dp

/**
 * The distance a reply swipe has to cover before it arms — see [SwipeTrigger], which is that
 * distance and which every part of the gesture is expressed against.
 *
 * Two thirds of what it was. The reply is one of the two things a horizontal drag on a message can
 * mean, and it is by far the more common: at the old distance a deliberate swipe routinely fell
 * short and the bubble simply sprang back, which reads as the gesture not existing rather than as
 * not having gone far enough. The resistance past the trigger is unchanged, so a longer pull still
 * has somewhere to go.
 */

/** How far off the bubble's edge the reply glyph sits. */
private val ReplyIconGap = 10.dp

/**
 * How far past the bubble's inner edge a reply swipe can be started.
 *
 * A bubble is only as wide as its words, so a two-word message is a tiny target for a gesture that
 * is about the *message* rather than about the shape it happens to be drawn in — and the empty half
 * of the row beside it looks like part of the message even though it is not. This gives the swipe
 * the room the eye already assumes it has, reaching in toward the middle of the screen without the
 * bubble changing size at all.
 *
 * Inward only. Reaching *outward*, past the bubble's own edge, would take nothing back from the
 * time column but would let a swipe start off the screen's edge, and there is nothing out there to
 * aim at. And it is only the drag's target: a long press still belongs to the whole row, which is
 * why that test is on the row and this one is on the bubble.
 */
private val ReplyReach = 120.dp

/**
 * The same reach on your own messages, which is a fifth of it.
 *
 * The reasoning above is about *their* side of the conversation, where the empty half of the row is
 * dead space and the only thing a swipe out there could mean is this message's reply. Your own side
 * is not like that: the row beside your bubbles is where the time column is dragged open from, and a
 * 120dp target for the reply swallowed most of it — reaching for the times on your own messages
 * armed a reply instead. Kept just wide enough that a one-word bubble is still an honest target.
 */
private val MineReplyReach = ReplyReach / 5

/** Apple's is a larger, rounded glyph that sharpens rather than grows as the swipe runs. */
private val AppleReplyIconSize = 30.dp

/** How far out of focus the Apple glyph starts, at the very beginning of the swipe. */
private val AppleReplyBlur = 7.dp

/** How small the Apple glyph starts. It grows in place rather than travelling to its spot. */
private const val APPLE_REPLY_START_SCALE = 0.45f

/**
 * How far in from the bubble's resting outer edge the Apple glyph is pinned.
 *
 * Measured from the edge rather than from the swipe's trigger distance, so the arrow sits close to
 * where the bubble started and the swipe uncovers it — the glyph is drawn *behind* the bubble, so
 * the corridor the bubble vacates is the only place it can be seen at all.
 *
 * Barely inside that edge now. Set deeper the arrow only cleared the message a good way into the
 * swipe, which made the gesture feel as though it had to run most of the width of the screen before
 * anything happened — the arrow appearing *is* the feedback that the swipe has been understood, and
 * it should arrive early.
 */
private val AppleReplyEdgeInset = 4.dp

/** WhatsApp's sits inside a disc, and it doesn't change size as it arrives. */
private val DiscSize = 32.dp
private val DiscGlyph = 17.dp

/**
 * Google's disc is drawn to the height of an ordinary one-line bubble beside it — the bubble's own
 * 10dp of padding either side of a 20pt line — so the two read as the same size of thing. It tracks
 * the text scale for the same reason the bubble does.
 */
private const val GOOGLE_DISC_LINE = 20f
private val GoogleDiscPadding = 10.dp

/**
 * The disc behind Google's arrow, as a vivid tone of whatever colour the conversation is set to.
 *
 * It used to be one hard-coded pale blue, which was a reasonable guess in a blue conversation and
 * plainly wrong in every other — a lilac thread uncovered a blue disc from behind its own bubble.
 * The hue is taken from the outgoing bubble and the rest is fixed: lifted well clear of the bubble
 * in both saturation and lightness, because this is a mark that appears for half a second under a
 * moving thumb and has to register in that time. A tone *near* the bubble's would read as a smudge
 * of the bubble rather than as something uncovered from behind it.
 */
private fun googleDiscFill(bubble: Color): Color = Color.hsl(bubble.hue(), 0.72f, 0.55f)

/** The arrow on it: the same hue taken right down, so the pair reads as one object. */
private fun googleDiscGlyph(bubble: Color): Color = Color.hsl(bubble.hue(), 0.85f, 0.14f)

/**
 * A colour's hue in degrees.
 *
 * Through the platform's own conversion rather than a hand-rolled one: it is the same arithmetic,
 * and getting it subtly wrong shows up as a colour that is *nearly* right, which is the hardest
 * kind of wrong to notice.
 */
private fun Color.hue(): Float {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red * 255f).roundToInt().coerceIn(0, 255),
        (green * 255f).roundToInt().coerceIn(0, 255),
        (blue * 255f).roundToInt().coerceIn(0, 255),
        hsv,
    )
    return hsv[0]
}

/**
 * Wraps one message bubble with its long-press/double-tap/swipe-to-reply gestures. Ported from
 * `message-interactions.tsx`. Uses two independent `pointerInput` blocks (drag, taps) rather than
 * Reanimated's `Gesture.Simultaneous`/`Exclusive` composition — Compose has no direct equivalent
 * of that precise arbitration, so this is closer than an exact one-to-one port but can occasionally
 * let a fast tap register while a drag is also in flight.
 */
@Composable
fun MessageInteractions(
    bubbleType: BubbleStyle,
    /** Your own messages swipe toward their own edge — left — rather than against it. */
    mine: Boolean,
    selecting: Boolean,
    selected: Boolean,
    /** Given how far the bubble had been dragged, so a focused reply can fly on from there. */
    onReply: (fromX: Float) -> Unit,
    onLongPress: () -> Unit,
    /**
     * Whether this row answers a touch at all.
     *
     * False for a tombstone and for the row being rewritten. Neutering the callbacks was not
     * enough: the drag still ran, so an unsent message could be swiped across the screen with the
     * reply arrow arming behind it and nothing happening at the end — which reads as a reply
     * gesture that is broken rather than one that is not offered.
     */
    enabled: Boolean = true,
    onDoubleTap: () -> Unit,
    onToggleSelect: () -> Unit,
    /**
     * A plain tap on the row, outside a selection, told where it landed: true for a tap on the
     * bubble itself, false for one out in the row around it — the quote above a reply, the
     * connector beside it, the empty half of the row.
     *
     * The distinction is what keeps a picture from opening when the thing pressed was the quote
     * hanging above it: the media answers a tap on itself, and everything else in the row belongs
     * to the message, which answers with its thread or its receipt.
     */
    onTap: (onBubble: Boolean) -> Unit = {},
    /**
     * True for a bubble that moves inside its own row rather than moving the row — an Apple reply,
     * whose quote above the bubble has to stay where it is. The translation is handed to [content]
     * to apply where it belongs instead.
     */
    splitReveal: Boolean = false,
    /**
     * The thread's time column. A horizontal drag that isn't this message's reply — the wrong
     * direction, or started off the bubble entirely — is handed to it here rather than left for an
     * ancestor to pick up: this detector claims the touch slop, and a claimed slop never reaches
     * the list underneath.
     */
    reveal: TimestampReveal? = null,
    /** Whether the conversation is scrolling, which is when a reply swipe must not start. */
    listScrolling: () -> Boolean = { false },
    /**
     * A row that answers a long press and nothing else, even while [enabled] is false: an unsent
     * message. There is no reply to make to it and nothing to react to, but what it said can still be
     * copied, saved or deleted from this phone.
     */
    longPressOnly: Boolean = false,
    /**
     * Where the bubble's middle sits inside this row, in row coordinates. A row can be taller than
     * its bubble — a quote above it, a receipt below, a sender's name in a group — so anything that
     * wants to line up with the *message* rather than with the strip it lives in has to be told
     * where the message actually is.
     */
    onBubbleCenterY: ((Float) -> Unit)? = null,
    /**
     * The row, handed how far the reply swipe has moved it — as a reading to take while drawing,
     * not as a number. Handed a number, every frame of a swipe recomposed the whole message just to
     * give it the next one: a message's worth of work sixty times a second, under a finger trying
     * to drag it smoothly.
     */
    content: @Composable (translateX: () -> Float, onBubblePlaced: (LayoutCoordinates) -> Unit) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // The Chats setting is what decides whether the arming buzz happens at all.
    val hapticsEnabled = LocalSettings.current.haptics
    // Where the finger goes after the long press has fired, for the menu that appears under it.
    val menuDrag = LocalMenuDragRelay.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val triggerPx = with(density) { SwipeTrigger.toPx() }
    val overshootPx = with(density) { SwipeOvershoot.toPx() }
    val reachPx = with(density) { (if (mine) MineReplyReach else ReplyReach).toPx() }
    val menuDragSlopPx = with(density) { MenuDragSlop.toPx() }
    val translateX = remember { Animatable(0f) }
    var armed by remember { mutableFloatStateOf(0f) } // 0 or 1, avoids a separate boolean recomposition source

    // Where the bubble sits inside this row. A row is full width — most of it is empty space beside
    // the bubble — and a drag started out there belongs to the thread's time column, not to this
    // message.
    //
    // Deliberately *not* snapshot state. These are written from layout, and a position in root
    // coordinates changes on every frame of every scroll — as state that recomposed every visible
    // row sixty times a second for the whole length of a fling, which is the sort of background
    // work that turns a send animation choppy. Nothing reads them during composition; the gesture
    // reads them imperatively, once, when a drag begins.
    val placement = remember { RowPlacement() }
    /**
     * The bubble's box within the row, frozen at the moment the drag started. The glyph is placed
     * off this plus the live translation, so it travels at exactly the bubble's speed by
     * construction — rather than chasing the bubble's reported position, always a frame behind it.
     */
    var swipeAnchor by remember { mutableStateOf<Rect?>(null) }
    val onBubblePlaced: (LayoutCoordinates) -> Unit = { coordinates ->
        val rect = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
        placement.bubble = rect
        // Root coordinates on both sides, so the difference is the same on every frame of a
        // scroll — which is what keeps this from writing to state sixty times a second.
        onBubbleCenterY?.invoke(rect.center.y - placement.origin.y)
    }

    Box(
        modifier = Modifier
            .onGloballyPositioned {
                placement.origin = it.positionInRoot()
                placement.originInWindow = it.positionInWindow()
            }
            // Watches the same press the detectors below it work on, without ever consuming any of
            // it, so that a finger which long-pressed this bubble can go on to work the menu that
            // appeared — a press belongs to the nodes that were under it when it went down, and the
            // menu was not one of them. Runs on the Initial pass, ahead of the long-press detector
            // that consumes everything once it has fired.
            .pointerInput(menuDrag) {
                if (menuDrag == null) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        menuDrag.move(placement.originInWindow + change.position)
                    }
                    menuDrag.end()
                }
            }
            .pointerInput(mine, reveal, enabled) {
                if (!enabled) return@pointerInput
                // A drag is either this bubble's reply gesture or the thread's timestamp reveal,
                // decided by its direction on the first movement — and by whether it started on the
                // bubble at all, since the empty half of the row was never this message's.
                var ours: Boolean? = null
                // The finger's own travel, kept apart from the bubble's: past the trigger the two
                // stop being the same number, which is the whole point of the resistance.
                var travelled = 0f
                // Which way this swipe went, fixed on its first movement: +1 right, -1 left. The
                // reply used to have one direction per side of the conversation and could hard-code
                // it; now that a message can be swiped either way, everything downstream — the
                // resistance, the bubble's offset, where the glyph sits — is expressed along
                // whichever way this one is actually going.
                var direction = 0f
                // Whether the conversation was still moving when the finger landed. That press is
                // catching the scroll and is never a reply — but it can still open the time column.
                var caughtScroll = false
                // Whether this drag went to the time column, so it is put back when the finger lifts.
                var revealing = false
                // Gives way to a vertical drag rather than claiming anything that moves sideways
                // at all. Rows sit inside the list, so they are offered the gesture first;
                // measuring slop on x alone meant a scroll that ran even slightly off true was
                // taken here and the conversation would not move.
                detectReplyDrag(
                    canStart = { caughtScroll = listScrolling(); true },
                    onStart = { start ->
                        val local = placement.bubble?.translate(-placement.origin)
                        // Reaching toward the middle of the screen — see [ReplyReach]. The glyph is
                        // still placed off the bubble's real edge below, so widening the target does
                        // not move anything you can see.
                        val target = local?.let {
                            if (mine) it.copy(left = it.left - reachPx) else it.copy(right = it.right + reachPx)
                        }
                        ours = if (caughtScroll || target?.contains(start) == false) false else null
                        swipeAnchor = local
                        travelled = 0f
                        direction = 0f
                    },
                    onEnd = {
                        // Read off the finger's own travel rather than the animated value, which is
                        // written from a coroutine and may not have caught up with the last frame.
                        val restingX = resistPast(travelled, triggerPx, overshootPx) * direction
                        if (armed > 0f) onReply(restingX)
                        armed = 0f
                        reveal?.releaseReply()
                        if (revealing) reveal?.settle()
                        revealing = false
                        ours = null
                        scope.launch { translateX.animateTo(0f, tween(180)) }
                    },
                    onDrag = { change, delta ->
                        val dragAmount = delta.x
                        if (ours == null && dragAmount != 0f) {
                            direction = if (dragAmount > 0f) 1f else -1f
                            // Rightwards always replies, on either side of the conversation — it is
                            // the gesture every messaging app has trained into the thumb, and it was
                            // doing nothing at all on your own messages.
                            //
                            // Leftwards is the one that has to be shared. On your own messages it
                            // replies too, since the time column can still be opened from the empty
                            // half of the row beside them; on theirs it stays the time column's,
                            // because that is the side of the screen the times slide in from and a
                            // reply already has the other direction.
                            ours = if (mine) true else direction > 0f
                        }
                        if (ours != true) {
                            // Not a reply, so it is the time column's. The drag started sideways and
                            // this detector already owns it, so the list's own scroll never sees it:
                            // the column follows x and carries the conversation's scroll on y.
                            if (reveal != null && !reveal.lockedByReply) {
                                revealing = true
                                change.consume()
                                reveal.drag(dragAmount)
                                reveal.scrollBy(delta.y)
                            }
                            return@detectReplyDrag
                        }
                        // A reply is exclusive, so it says so — the observer stands off while this
                        // is in flight rather than opening the column under the swipe.
                        reveal?.lockForReply()
                        change.consume()
                        // Measured along the reply's own direction, so both sides can be reasoned
                        // about as one positive distance; dragging back past the start is nothing.
                        travelled = (travelled + dragAmount * direction).coerceAtLeast(0f)
                        val moved = resistPast(travelled, triggerPx, overshootPx)
                        scope.launch { translateX.snapTo(moved * direction) }
                        if (armed == 0f && travelled >= triggerPx) {
                            armed = 1f
                            if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (armed > 0f && travelled < triggerPx) {
                            armed = 0f
                        }
                    },
                )
            }
            .pointerInput(enabled, longPressOnly) {
                if (!enabled && !longPressOnly) return@pointerInput
                detectTapGestures(
                    onLongPress = { at ->
                        // The bubble has been taken hold of, and until the menu has drawn itself
                        // there is nothing on screen saying so. Every other hold in the app answers
                        // in the hand; this one, the one people use most, did not.
                        if (hapticsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // From here the finger is the menu's: it can slide straight onto a row and
                        // lift there, without ever coming off the glass.
                        menuDrag?.arm(placement.originInWindow + at, menuDragSlopPx)
                        onLongPress()
                    },
                    onDoubleTap = { if (!longPressOnly) onDoubleTap() },
                    onTap = { at ->
                        if (selecting) {
                            onToggleSelect()
                        } else if (!longPressOnly) {
                            // Root coordinates on the bubble, row-local ones on the finger, so the
                            // row's own origin is what carries between them.
                            val bubble = placement.bubble?.translate(-placement.origin)
                            onTap(bubble?.contains(at) == true)
                        }
                    },
                )
            },
    ) {
        val swipeNow = remember(translateX) { { translateX.value } }
        val progress = (abs(translateX.value) / triggerPx).coerceIn(0f, 1f)
        // All three sit behind the bubble. Apple's is the one that doesn't move: pinned just inside
        // the bubble's resting edge, it starts small and out of focus underneath the message and is
        // uncovered by the swipe as it grows — the bubble sliding off it rather than it sliding in.
        if (progress > 0f) {
            ReplyGlyph(
                bubbleType = bubbleType,
                // Which side the glyph is uncovered on follows the *swipe*, not the sender: the
                // bubble vacates a corridor on the side it is travelling away from, and that
                // corridor is the only place the glyph can be seen at all.
                towardRight = translateX.value > 0f,
                progress = progress,
                bubble = swipeAnchor,
                translateX = { translateX.value },
            )
        }
        // Neither a tint nor a lift for a picked message: the checkbox down the side is the whole of
        // the selection's appearance, and scaling a bubble up nudged its neighbours around.
        Box(
            modifier = Modifier
                // A split reveal leaves the offset to the content, which applies it to the bubble
                // alone and leaves the quote above it where it is.
                .then(if (splitReveal) Modifier else Modifier.offset { IntOffset(translateX.value.roundToInt(), 0) }),
        ) {
            content(swipeNow, onBubblePlaced)
        }
    }
}

/**
 * How far the bubble follows the finger once the reply has armed. Up to the trigger the two move
 * together; past it the extra travel decays toward [overshoot], so the swipe grows heavier and
 * heavier without ever going dead under the finger.
 */
private fun resistPast(travelled: Float, trigger: Float, overshoot: Float): Float =
    if (travelled <= trigger) travelled else trigger + overshoot * (1f - exp(-(travelled - trigger) / overshoot))

/**
 * The mark a swipe-to-reply leaves beside the bubble, in each style's own idiom: Apple's rounded
 * arrow resolves out of a blur, WhatsApp's is a line arrow on a half-lit disc, and Google's is a
 * disc in the bubble's own colour with the arrow struck through it in a darker shade of the same.
 *
 * The two Material glyphs don't move under their own steam: each is placed off the bubble's
 * measured edge and nothing else, so it travels at exactly the bubble's speed rather than racing
 * it — which is what a separate slide-in or a scale about the centre used to do.
 *
 * Apple's doesn't travel at all. It is pinned just inside the bubble's own resting edge and grows
 * there from small and out of focus, behind the message — so the arrow reads as resolving in place
 * while the bubble slides off it, rather than being dragged in from beyond the screen's edge.
 */
@Composable
private fun BoxScope.ReplyGlyph(
    bubbleType: BubbleStyle,
    /** Whether the bubble is being dragged to the right, which puts the glyph on its left. */
    towardRight: Boolean,
    progress: Float,
    /** The bubble's resting box in this row, before the swipe moved it. */
    bubble: Rect?,
    /** How far the swipe has moved the bubble since, read at placement time. */
    translateX: () -> Float,
) {
    val textScale = LocalSettings.current.textScale()
    val googleDisc = GoogleDiscPadding * 2 + (GOOGLE_DISC_LINE * textScale).dp
    val size: Dp = when (bubbleType) {
        BubbleStyle.APPLE -> AppleReplyIconSize
        BubbleStyle.GOOGLE -> googleDisc
        BubbleStyle.WHATSAPP -> DiscSize
    }
    // Beside the bubble's own edge rather than the row's: on a short message the row's edge is half
    // a screen away from the message being replied to.
    val pinned = bubbleType == BubbleStyle.APPLE
    val placement = if (bubble != null) {
        Modifier
            .align(Alignment.TopStart)
            .offset {
                val icon = size.toPx()
                val gap = ReplyIconGap.toPx()
                val inset = AppleReplyEdgeInset.toPx()
                // The bubble's live edge is its resting edge plus however far the swipe has carried
                // it, which is the same number the bubble itself is drawn with — so the two cannot
                // drift apart. Apple's ignores the swipe entirely and takes a fixed spot tucked
                // just inside where the bubble was.
                val x = when {
                    pinned && towardRight -> bubble.left + inset
                    pinned -> bubble.right - inset - icon
                    towardRight -> bubble.left + translateX() - gap - icon
                    else -> bubble.right + translateX() + gap
                }
                IntOffset(x.roundToInt(), (bubble.center.y - icon / 2f).roundToInt())
            }
    } else {
        Modifier.align(if (towardRight) Alignment.CenterStart else Alignment.CenterEnd)
    }
    // Both take their colour from the outgoing bubble, so a recoloured conversation recolours the
    // mark its own swipe uncovers. Read off `mine` regardless of who sent the message being replied
    // to: this glyph is *your* reply about to happen, not a property of the message under it.
    val whatsAppFill = useWhatsAppBubbleColors(mine = true).background
    val googleBubble = useGoogleBubbleColors(mine = true).background

    when (bubbleType) {
        // Small and out of focus at the first pixel of the swipe, growing and sharpening as it
        // runs: the glyph resolves in one place, exactly as the reply arms. The blur is the outer
        // of the two, so its radius stays a fixed number of screen pixels rather than shrinking
        // along with the arrow it is meant to be softening.
        BubbleStyle.APPLE -> Icon(
            Icons.AutoMirrored.Rounded.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = placement
                .blur(AppleReplyBlur * (1f - progress), BlurredEdgeTreatment.Unbounded)
                .graphicsLayer {
                    val grown = APPLE_REPLY_START_SCALE + (1f - APPLE_REPLY_START_SCALE) * progress
                    scaleX = grown
                    scaleY = grown
                    alpha = 0.35f + 0.65f * progress
                }
                .size(size),
        )
        BubbleStyle.WHATSAPP -> ReplyDisc(
            modifier = placement.size(size),
            alpha = progress,
            fill = whatsAppFill.copy(alpha = 0.5f),
            glyph = MaterialTheme.colorScheme.onSurface,
        )
        // Fades into its colour rather than growing into it, and is fully there the moment the
        // reply arms.
        BubbleStyle.GOOGLE -> ReplyDisc(
            modifier = placement.size(size),
            alpha = progress,
            fill = googleDiscFill(googleBubble),
            glyph = googleDiscGlyph(googleBubble),
            glyphSize = size * 0.45f,
            // Material Symbols' own `reply`, out of `res/drawable`. The icons library's `Reply` —
            // filled or outlined, they are the same glyph — is the 2014 Material Icons arrow, which
            // is a different drawing from the one every other mark in a Google Messages bubble is
            // made to. See the drawable's own note.
            icon = painterResource(R.drawable.reply_24),
        )
    }
}

/** A line arrow struck through a solid disc — the shared shape behind both Material styles. */
@Composable
private fun ReplyDisc(
    modifier: Modifier,
    alpha: Float,
    fill: Color,
    glyph: Color,
    glyphSize: Dp = DiscGlyph,
    icon: Painter = rememberVectorPainter(Icons.AutoMirrored.Outlined.Reply),
) {
    Box(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = glyph,
            modifier = Modifier.size(glyphSize),
        )
    }
}

/** Toward black, keeping the alpha — a shade of the bubble's own colour rather than a grey. */
private fun Color.darken(amount: Float): Color =
    Color(red * (1f - amount), green * (1f - amount), blue * (1f - amount), alpha)
