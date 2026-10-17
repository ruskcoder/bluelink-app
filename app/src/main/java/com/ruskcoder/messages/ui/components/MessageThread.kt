package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.BubbleStyle
import com.ruskcoder.messages.ui.components.bubbles.AppleGroupGutter
import com.ruskcoder.messages.ui.components.bubbles.AppleTailReserve
import com.ruskcoder.messages.ui.components.bubbles.BubbleRowInset
import com.ruskcoder.messages.ui.components.bubbles.GoogleGroupGutter
import com.ruskcoder.messages.ui.components.bubbles.WhatsAppGroupGutter
import com.ruskcoder.messages.imessage.TransferProgress
import com.ruskcoder.messages.data.model.edited
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.data.model.deliveredQuietly
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.withoutPollUpdates
import com.ruskcoder.messages.transcribe.TranscriptionState
import com.ruskcoder.messages.data.model.canHaveStatus
import com.ruskcoder.messages.data.model.error
import com.ruskcoder.messages.data.model.receipts
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import com.ruskcoder.messages.data.model.EffectKind
import com.ruskcoder.messages.data.model.MessageEffect
import com.ruskcoder.messages.data.model.effect
import com.ruskcoder.messages.ui.components.effects.rememberBubbleEffect
import com.ruskcoder.messages.data.model.replyTo
import com.ruskcoder.messages.data.model.threadEndIds
import com.ruskcoder.messages.data.model.threadRoots
import com.ruskcoder.messages.data.model.senderId
import com.ruskcoder.messages.data.model.status
import com.ruskcoder.messages.ui.theme.LocalChatTheme
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.util.formatAppleSection
import com.ruskcoder.messages.util.formatGoogleSection
import com.ruskcoder.messages.util.formatWhatsAppSection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// The longest time it has to print — "12:45 AM" — plus the margin it keeps off the edge, and very
// little else: a wider column only puts air between a bubble and its time, which is what makes the
// two read as unrelated. The text never wraps (see the column below), so a size that runs a little
// over at the largest setting spills evenly either side rather than breaking onto a second line.
private val RevealWidth = 68.dp

/**
 * The extra room under a message that closes a thread or has a connector leaving it, on top of the
 * ordinary run gap.
 *
 * Kept under the row's caption rather than on the bubble. On the bubble it sat between the message and
 * its own "2 Replies" or "Delivered", which then read as belonging to nothing in particular.
 */
private val AppleThreadGap = 7.dp

/**
 * How much of the finger's travel the time column actually takes, at rest and at full open.
 *
 * The column used to track the finger exactly, which made it feel like a panel being dragged rather
 * than one being pulled out against something. Under half the movement, stiffening as it opens, it
 * resists — and the resistance is what tells you the column has a width without having to reach it.
 */
private const val DragGainAtRest = 0.6f
private const val DragGainAtFull = 0.35f

/**
 * How far each part of a row has slid while the time column is open.
 *
 * Two rates, not one. Everything anchored to the *right* of the screen travels with the column, at
 * [full]: your own bubbles and the outlines above them, and — in a group — the faces beside their
 * messages, which slide out past the left edge. Their bubbles travel at [received] instead, which
 * is only as far as the avatar gutter is wide: they start indented past the face and finish flush
 * against the same margin your own messages keep, so the conversation squares up as the column
 * opens rather than being shunted sideways. In a one-to-one there is no gutter to give up and their
 * side of the conversation simply holds still.
 *
 * Read as lambdas, in a `graphicsLayer` rather than an `offset`: a translation that moves layout
 * would re-measure and re-report every visible row on every frame of the drag, and the connectors
 * are drawn off those reported positions. Sliding the drawing instead leaves every measurement
 * exactly where it was taken, and the connector adds the shift back itself — see [AppleBubble].
 */
@Stable
class RevealShift(val full: () -> Float, val received: () -> Float) {
    /** The travel a message on the given side makes. */
    fun of(mine: Boolean): Float = if (mine) full() else received()
}

private val NoRevealShift = RevealShift({ 0f }, { 0f })

/** Nothing has been swiped anywhere outside a conversation, so everything off it reads zero. */
val LocalRevealShift = androidx.compose.runtime.staticCompositionLocalOf { NoRevealShift }

/**
 * The conversation's time column, for a bubble with a sideways gesture of its own — a card stack —
 * to hold shut while a finger is on it. Null off a conversation and with the swipe turned off.
 */
val LocalTimestampReveal = androidx.compose.runtime.staticCompositionLocalOf<TimestampReveal?> { null }

/** The typing row's list key. It is always in the list, so this is always index 0. */
private const val TYPING_KEY = "typing"

/** The "has notifications silenced" line's key, which is in the list only while that is true. */
private const val FOCUS_NOTICE_KEY = "focus-silenced"

/** The column multi-select opens down the left edge, wide enough for a Material checkbox. */
private val CheckboxColumn = 48.dp

/** Material's emphasized-decelerate: the column arrives at its width rather than easing out of it. */
private val ColumnEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * How a just-arrived message grows in: from this much of its size, on the same decelerating curve.
 *
 * Internal rather than private because the focused-reply overlay sends a message too, and a reply
 * sent there has to arrive exactly the way one sent here does — see `FocusedReply`'s `FocusedBubble`.
 */
internal const val ArrivalScale = 0.15f
internal const val ArrivalMs = 360

/**
 * Decelerating, but not so hard that the beginning is over before it can be seen.
 *
 * The curve this shared with the rest of the file is nearly vertical off the mark — a fifth of the
 * way through the duration it is already three-quarters of the way through the movement. A bubble
 * growing on it looked like it started at three-quarters of its size and animated the last quarter,
 * whatever it actually started at. This one spends its first frames where the eye can follow them.
 */
internal val ArrivalEasing = CubicBezierEasing(0.22f, 0.68f, 0.32f, 1f)

/**
 * How long the receipt waits before following a message that has just been sent. Long enough for
 * the conversation to have finished sliding and the new bubble to have finished growing, so the
 * two movements are read one after the other rather than on top of each other.
 */
private const val ReceiptHandoverBeatMs = 100
private const val ReceiptHandoverDelayMs = (ArrivalMs + ReceiptHandoverBeatMs).toLong()

/** Matches `ReceiptGrowMs` in `DeliveryStatusViews`; the pair have to come off the screen together. */
private const val ReceiptGrowMs = 420

/** How long the typing row takes to open or shut, on the same decelerating curve as an arrival. */
private const val TypingGrowMs = 260

/** How small the dots start, growing out of the corner the message they precede will appear in. */
private const val TypingStartScale = 0.7f

/** How long a bubble takes to settle into a new height — an unsend, an edit, a reaction landing. */
private const val BubbleResizeMs = 260



/**
 * The time column's shared position, and the two calls that move it.
 *
 * The reveal used to live only on the list itself, which meant a drag that started on a row never
 * reached it: the row's own reply gesture takes the touch slop first, and a consumed slop is the
 * end of the story for every ancestor. So the rows hold this instead and push whatever isn't a
 * reply into it — which is what makes the gesture work anywhere on the conversation rather than
 * only in the strip of empty list beyond the last bubble.
 */
@Stable
class TimestampReveal(
    private val offset: Animatable<Float, *>,
    private val widthPx: Float,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val listState: androidx.compose.foundation.lazy.LazyListState,
    private val canScroll: () -> Boolean,
) {
    /**
     * The conversation's scroll, driven by hand while a reveal owns the finger. A reveal claims the
     * gesture outright — so a drag that began sideways never scrolls at the start, and a drag that
     * began as a scroll never opens the column — and then passes the vertical travel on from here.
     * Reversed layout, so the finger's own direction is already the list's forward one.
     */
    fun scrollBy(dy: Float) {
        if (dy != 0f && canScroll()) listState.dispatchRawDelta(dy)
    }

    /** How far the column is open, 0..1, for the row that draws the times. */
    val progress: Float get() = (-offset.value / widthPx).coerceIn(0f, 1f)

    /** How far the bubbles have slid, in pixels — negative, since they move toward the left edge. */
    val x: Float get() = offset.value

    /**
     * Takes a share of the finger's movement rather than all of it, and a smaller share the further
     * the column is already open. See [DragGainAtRest].
     */
    fun drag(dx: Float) {
        val gain = DragGainAtRest + (DragGainAtFull - DragGainAtRest) * progress
        val next = (offset.value + dx * gain).coerceIn(-widthPx, 0f)
        scope.launch { offset.snapTo(next) }
    }

    fun settle() {
        scope.launch { offset.animateTo(0f, tween(220)) }
    }

    /**
     * Held by a reply swipe for as long as it owns the finger.
     *
     * The column is opened by an observer that claims nothing — see [observeRevealDrag] — which is
     * what lets it run alongside the conversation's own scroll. The price is that it can also see a
     * gesture that belongs to something else, and a reply swipe is the one gesture that is properly
     * exclusive. So the swipe says so out loud rather than being inferred.
     */
    var lockedByReply: Boolean = false
        private set

    fun lockForReply() {
        lockedByReply = true
    }

    fun releaseReply() {
        lockedByReply = false
    }
}

/**
 * Where a row sits on screen, in root coordinates, and where the bubble sits inside it.
 *
 * The two are not the same place, and on a row carrying an Apple reply's quote they are a quote's
 * height apart. Anything aiming at the *message* — an overlay lifting it out, the bar that hangs off
 * its top edge — wants [bubbleTop]; only the strip itself wants [top].
 */
data class ThreadRowRect(val top: Float, val height: Float, val bubbleTop: Float = top)

/**
 * Where a message's connector attaches, in root coordinates.
 *
 * Published by every laid-out row so that a reply can aim at the message it answers even when that
 * message is a whole row away and drawn by something this composable cannot see into. The adjacent
 * case is the one that needs it: there is no outline to measure against, and the climb used to be a
 * flat 24dp guess that knew nothing about how tall the bubble overhead was, what text size it was
 * set in, or whether it was carrying a name and a reply count of its own.
 */
data class ThreadAnchor(
    val bubble: Rect,
    val avatar: Rect?,
    val mine: Boolean,
    /**
     * The outline this row is drawing above its own bubble, where it draws one.
     *
     * Published because it is a legitimate thing for *another* row's connector to point at. A chain
     * of replies shows the reference once, on the message that first needed it; everything below
     * answers the same reference, and the picture of it is already on screen. So the newest message
     * in the chain aims at that outline rather than at the real bubble it stands for, which may be
     * a screen away.
     */
    val outline: Rect? = null,
    /** Whose message the outline stands for, which decides the side it hangs on. */
    val outlineMine: Boolean = false,
)

/**
 * A handle on the conversation: its scroll position, and where each laid-out row currently sits.
 * The focused-reply overlay lifts a bubble out of the thread and has to fly it from exactly where
 * it was — and back to wherever the thread has scrolled to by the time it closes — which is what
 * [rowRect] answers. Ported from `message-thread.tsx`'s `MessageThreadHandle`.
 */
@Stable
class MessageThreadState(val listState: LazyListState) {
    // A plain map, not snapshot state: it's written from layout on every scroll frame and only ever
    // read imperatively, at the moment a bubble is lifted or sent home.
    private val rows = mutableMapOf<String, ThreadRowRect>()

    // The row and the bubble inside it report themselves separately, and a layout pass may deliver
    // them in either order, so the bubble's own reading is kept apart and merged into whichever
    // rect is current. Held rather than folded in on arrival: the row rewrites its entry on every
    // frame of a scroll, and would otherwise wipe the bubble's position out from under itself.
    private val bubbleTops = mutableMapOf<String, Float>()

    // Where each message's bubble and face landed, for a *different* row's connector to aim at.
    // Kept apart from `bubbleTops` because the two are read at different moments: one at the
    // instant a bubble is lifted, the other on every layout pass of every reply on screen.
    private val bubbleRects = mutableMapOf<String, Rect>()
    private val avatarRects = mutableMapOf<String, Rect>()
    private val mineFlags = mutableMapOf<String, Boolean>()
    private val outlineRects = mutableMapOf<String, Rect>()
    private val outlineMine = mutableMapOf<String, Boolean>()

    /**
     * The rows wearing a tail or a face their run alone would not give them, because a connector
     * leaves from them — see [ThreadRow.forcedTail] and [ThreadRow.forcedAvatar].
     *
     * Published so that an overlay lifting one of them out draws it the way the row does. Only the
     * thread can work these out, since they depend on the whole conversation; a lifted copy that
     * asked its run alone came up without the tail and the face — and without the gap under them —
     * on the frame it was picked up, and grew all three back on the frame it landed.
     */
    internal var forcedTails: Set<String> = emptySet()
    internal var forcedAvatars: Set<String> = emptySet()

    /** Whether this message's row wears a tail only because a reply below it points at it. */
    fun wearsForcedTail(messageId: String): Boolean = messageId in forcedTails

    /** Whether this message's row wears a face only because a connector leaves from it. */
    fun wearsForcedAvatar(messageId: String): Boolean = messageId in forcedAvatars

    /**
     * Bumped whenever a message starts or stops being measurable — never when one merely moves.
     *
     * A reply's connector aims at a row it does not own and cannot see, and the answer to "where is
     * it" is null right up until that row has been through layout once. Resolved once and cached,
     * a reply laid out before its target simply never got an answer: nothing about its own position
     * changed afterwards, so nothing asked again, and the line was missing for the life of the
     * screen. Snapshot state, so a draw that reads it is invalidated the moment the answer exists.
     *
     * Deliberately *not* bumped on position. These rects are rewritten on every frame of every
     * scroll, and a revision that tracked them would redraw every connector on screen sixty times a
     * second to discover that nothing had moved relative to anything else.
     */
    var placedRevision by mutableIntStateOf(0)
        private set

    internal fun place(messageId: String, rect: ThreadRowRect) {
        rows[messageId] = rect.copy(bubbleTop = bubbleTops[messageId] ?: rect.top)
    }

    /** Where the bubble itself landed, which is not where its row starts. */
    internal fun placeBubble(messageId: String, box: Rect, mine: Boolean) {
        if (bubbleRects.put(messageId, box) == null) placedRevision++
        bubbleTops[messageId] = box.top
        mineFlags[messageId] = mine
        rows[messageId]?.let { rows[messageId] = it.copy(bubbleTop = box.top) }
    }

    /** Where the sender's face landed beside a received group message; absent everywhere else. */
    internal fun placeAvatar(messageId: String, box: Rect?) {
        val had = avatarRects.containsKey(messageId)
        if (box == null) avatarRects.remove(messageId) else avatarRects[messageId] = box
        if (had != (box != null)) placedRevision++
    }

    /** Where this row's outline reference landed, for a reply below it to aim at. */
    internal fun placeOutline(messageId: String, box: Rect?, mine: Boolean) {
        val had = outlineRects.containsKey(messageId)
        if (box == null) {
            outlineRects.remove(messageId)
            outlineMine.remove(messageId)
        } else {
            outlineRects[messageId] = box
            outlineMine[messageId] = mine
        }
        if (had != (box != null)) placedRevision++
    }

    /**
     * What a reply's connector should aim at, or null while that message isn't laid out.
     *
     * Null is the ordinary answer for a message scrolled off the top, and the connector simply
     * falls back to reaching a fixed distance above its own bubble — the old behaviour, kept for
     * the one case where there is genuinely nothing to measure.
     */
    fun anchorRect(messageId: String): ThreadAnchor? {
        val bubble = bubbleRects[messageId] ?: return null
        return ThreadAnchor(
            bubble = bubble,
            avatar = avatarRects[messageId],
            mine = mineFlags[messageId] ?: false,
            outline = outlineRects[messageId],
            outlineMine = outlineMine[messageId] ?: false,
        )
    }

    internal fun forget(messageId: String) {
        rows.remove(messageId)
        bubbleTops.remove(messageId)
        if (bubbleRects.remove(messageId) != null) placedRevision++
        avatarRects.remove(messageId)
        mineFlags.remove(messageId)
        outlineRects.remove(messageId)
        outlineMine.remove(messageId)
    }

    /** Where a row is right now, or `null` if it isn't laid out — scrolled away, or deleted. */
    fun rowRect(messageId: String): ThreadRowRect? = rows[messageId]

    /**
     * Where a message's bubble should be flown from, or back to — including one that isn't on
     * screen at all.
     *
     * Lifting a whole thread out of the conversation is the case this exists for: a thread can span
     * a screenful of messages, so some of it is reliably scrolled away, and a bubble with no
     * measured row used to have nowhere to come from. It came from nowhere, literally — it faded in
     * where it was going instead of travelling, which is exactly the message the animation is
     * there to avoid giving.
     *
     * So an unmeasured row is placed just past the edge it went out of. The list is reversed, so a
     * *higher* display index is older and further up: past the last visible item means off the top,
     * and before the first means off the bottom. The bubble then makes the same trip every other
     * one does, only a longer one — which, over a fixed duration, is what makes it travel faster.
     */
    fun originFor(messageId: String, viewportHeight: Float): Float {
        rows[messageId]?.let { return it.bubbleTop }
        val index = displayOrder.indexOf(messageId)
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index
        val last = visible.lastOrNull()?.index
        // Nothing laid out at all, or an id the list has never heard of: the foot of the screen is
        // the nearer edge on a conversation that rests against the send bar.
        if (index < 0 || first == null || last == null) return viewportHeight + OffscreenMargin
        return if (index > last) -OffscreenMargin else viewportHeight + OffscreenMargin
    }

    /**
     * Where the row being rewritten is, or null when nothing is.
     *
     * Snapshot state, unlike [rows]: this one is *read* while drawing, by the scrim that dims the
     * rest of the conversation around it, so it has to invalidate what it feeds.
     */
    var editingRow by mutableStateOf<ThreadRowRect?>(null)
        internal set

    /**
     * The order rows are laid out in, newest first — the reversed list's own indices, which is what
     * a scroll has to be expressed in. Published by the thread on every composition.
     */
    internal var displayOrder: List<String> = emptyList()

    /**
     * Whether this message has a row in the list as it is currently laid out.
     *
     * The thread holds a window of the conversation, so a jump to a message further back has to
     * load it first — and then wait for it to arrive here before there is an index to scroll to.
     */
    fun holds(messageId: String): Boolean = messageId in displayOrder

    /** Brings one message into view, wherever it is in the conversation. */
    suspend fun scrollToMessage(messageId: String) {
        val index = displayOrder.indexOf(messageId)
        if (index < 0) return
        // Landed a third of the way up the viewport rather than hard against its edge, so the
        // messages around a hit are part of the answer.
        listState.animateScrollToItem(index, scrollOffset = -listState.layoutInfo.viewportSize.height / 3)
    }

    /**
     * Puts one message's bubble at an exact place on the screen, without animating the trip.
     *
     * What this is for is closing the focused stack onto a message you picked out of it: the
     * conversation is moved *behind* the overlay so that the bubble you tapped is already sitting
     * under the lifted one, and the return leg then has nothing to travel — that message stays
     * perfectly still while every other bubble in the stack flies home around it. Animating the
     * scroll instead would have the thread sliding under the stack while the stack came down onto
     * it, which is two movements answering one tap.
     *
     * Corrected against a second reading rather than computed in one go: a row's height is only
     * known once it is laid out, so a message that was scrolled away is first brought roughly into
     * view and then trued up. Where the list has run out of scroll — a message near either end of
     * the conversation — it lands as close as it can and the bubble travels the remainder.
     */
    suspend fun alignBubble(messageId: String, targetTop: Float) {
        val index = displayOrder.indexOf(messageId)
        if (index < 0) return
        if (rows[messageId] == null) {
            // Scrolled away, so there is no measurement to move relative to. Park it mid-viewport
            // and give the list two frames to report where the row actually landed: one for the
            // layout, one for the position to be published back here.
            listState.scrollToItem(index, scrollOffset = -listState.layoutInfo.viewportSize.height / 2)
            withFrameNanos { }
            withFrameNanos { }
        }
        val from = rows[messageId]?.bubbleTop ?: return
        // One move, off a reading taken before anything has shifted — the conversation is held
        // still the whole time the overlay is up, so this is exact and nothing needs correcting
        // afterwards. The list is reversed, which is what puts the sign this way round: scrolling
        // forward walks back through the conversation and carries what is on screen downward.
        listState.scrollBy(targetTop - from)
    }

    /**
     * Brings one message clear of whatever is covering the foot of the conversation — the keyboard,
     * and the send bar riding on top of it — and does nothing at all if it is already clear.
     *
     * The case this exists for is editing a message low in the thread: the keyboard opens over the
     * row you are typing into, and a field you cannot see is not a field. Conditional because the
     * common case is a message already well above the fold, and scrolling the conversation for it
     * would move the thing you were looking at for no reason.
     *
     * [insetPx] is how much of the bottom is spoken for. On a reversed list an item's `offset` is
     * its distance from the bottom edge, so "covered" is simply an offset inside that.
     */
    suspend fun revealAbove(messageId: String, insetPx: Int, force: Boolean = false) {
        val index = displayOrder.indexOf(messageId)
        if (index < 0) return
        val laidOut = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        // Not laid out at all means scrolled off the end entirely, which also needs bringing up.
        if (!force && laidOut != null && laidOut.offset >= insetPx) return
        listState.animateScrollToItem(index, scrollOffset = -insetPx)
    }

    /** The tail is index 0 on a reversed list, so following it is a scroll to the origin. */
    suspend fun scrollToEnd(animated: Boolean = true) {
        if (listState.layoutInfo.totalItemsCount == 0) return
        if (animated) listState.animateScrollToItem(0) else listState.scrollToItem(0)
    }
}

/**
 * How far past the edge an unmeasured row is treated as sitting.
 *
 * Enough that a bubble starting there is genuinely off screen — starting it *on* the edge has it
 * appear to be sitting in the header or the send bar for the first frame of its flight.
 */
private const val OffscreenMargin = 160f

@Composable
fun rememberMessageThreadState(listState: LazyListState = rememberLazyListState()): MessageThreadState =
    remember(listState) { MessageThreadState(listState) }

/**
 * The scrollable conversation. Ported from `message-thread.tsx`: a reversed `LazyColumn` (newest
 * at the bottom, matching an inverted `FlatList`), run-grouping (consecutive same-sender messages
 * share one tail), and swipe-left-to-reveal-timestamps on the sender's own messages.
 *
 * The RN version drives the reveal gesture and the list's own scroll simultaneously via
 * `Gesture.Pan` + `Gesture.Native()` composition. Compose has no matching primitive for "let a
 * horizontal component through to me and a vertical one through to the scrolling list at the same
 * time," so this gates on drag angle instead: a drag only starts revealing once it reads as mostly
 * horizontal, which lets an ordinary vertical scroll fling straight through untouched.
 */
@Composable
fun MessageThread(
    messages: List<Message>,
    members: List<GroupMember>,
    swipeTimestampsEnabled: Boolean,
    selecting: Boolean,
    selectedIds: Set<String>,
    /** The message, and how far its bubble had been swiped when the reply fired. */
    onReply: (Message, Float) -> Unit,
    onLongPress: (Message) -> Unit,
    onDoubleTap: (Message) -> Unit,
    onToggleSelect: (String) -> Unit,
    /**
     * A tap on a row outside a selection, told whether it landed on the bubble itself. Returns true
     * if the tap was spent — a photo or a video opening full screen. Anything it turns down falls
     * through to this thread, which answers a tap by opening that message's own time-and-status
     * caption underneath it.
     */
    onTap: (Message, onBubble: Boolean) -> Boolean = { _, _ -> false },
    /** Our own handles, so a reaction we placed reads as ours. */
    myHandles: Set<String> = emptySet(),
    /** Names an `@` mention in this conversation could point at — the roster, plus your own. */
    mentionNames: List<String> = emptyList(),
    /**
     * The oldest message that was still unread when this conversation was opened.
     *
     * Drawn as a line across the thread, so a conversation you come back to says where you left off
     * rather than making you work it out from the timestamps.
     */
    firstUnreadId: String? = null,
    /**
     * The handles of everyone writing right now, which puts three dots at the tail.
     *
     * A list because a group can have several people writing at once, and the indicator shows all
     * of their faces — holding one handle meant the second person to start silently replaced the
     * first, so the dots stayed put while the face under them flickered between people.
     */
    typingSenders: List<String> = emptyList(),
    /** True for a group, so the typing bubble keeps the avatar gutter its messages do. */
    isGroup: Boolean = false,
    /**
     * A press on the "N Replies" line under a message, which opens that whole thread.
     *
     * The label is the only part of the caption line that leads anywhere, so it is the only part
     * that answers a press. A tap on the bubble itself reaches the same place through [onTap],
     * which the screen answers for since only the screen knows what a thread is.
     */
    onOpenThread: (Message) -> Unit = {},
    /**
     * A message with a full-screen effect has just landed, and where its bubble is.
     *
     * Reported rather than played here: a screen effect covers the whole screen, which is a place
     * the conversation does not own — and three of the eight aim at the bubble that set them off,
     * which only the thread knows the position of. So the thread says what and where, and the
     * screen draws it.
     */
    onScreenEffect: (MessageEffect, Rect) -> Unit = { _, _ -> },
    /** A tap on a message's reaction badge, which opens the list of who put it there. */
    onReactionsClick: (Message) -> Unit = {},
    /**
     * A tap on the badge beside a message that never sent, which opens the reason and what can be
     * done about it. Left unset the badge is still drawn — the failure is true either way — and is
     * simply inert.
     */
    onFailureClick: (Message) -> Unit = {},
    /**
     * Voice messages currently being transcribed, and the ones whose last attempt failed.
     *
     * Sets rather than a flag on the message because transcription is not part of what a message
     * *is* — it is work in flight, owned by something that outlives this screen, and a row that
     * scrolls away and comes back has to find it still running.
     */
    transcribing: Set<String> = emptySet(),
    transcriptionFailed: Set<String> = emptySet(),
    /**
     * Whether the chosen speech model is actually downloaded.
     *
     * Decided by the screen from what is installed rather than discovered by pressing: a button
     * that runs for a second and then reports there was never anything to run with is a worse
     * answer than one that says so before it is pressed.
     */
    transcriptionReady: Boolean = true,
    /** Starts transcribing a voice message. Left unset, no bubble offers the action. */
    onTranscribe: ((Message) -> Unit)? = null,
    /** A voice message sent to us started playing, so its sender can be told it was listened to. */
    onVoicePlayed: ((Message) -> Unit)? = null,
    state: MessageThreadState = rememberMessageThreadState(),
    /**
     * Rows to render invisible — messages currently lifted into the focused-reply overlay. They
     * keep their space, so the conversation doesn't reflow underneath one that has been lifted out.
     */
    hiddenIds: Set<String> = emptySet(),
    /**
     * Rows whose *bubble* is being drawn elsewhere, but whose quote and connector are not.
     *
     * A thread lift takes the messages and leaves their references behind — see
     * [MessageBubble.bubbleHidden]. Distinct from [hiddenIds], which takes the whole row because
     * the overlay there is drawing the whole row.
     */
    bubbleHiddenIds: Set<String> = emptySet(),
    /**
     * The attachments still going out, keyed by the row they belong to.
     *
     * Passed down rather than read from a composition local at the top: the thread is the only
     * thing that knows which row is which, and a bubble drawn anywhere else — a preview, a lifted
     * overlay — is looking at a message rather than watching a send.
     */
    transfers: Map<String, TransferProgress> = emptyMap(),
    /** The search hit currently being looked at, lit up so it can be found on screen. */
    highlightedId: String? = null,
    /** Who you are talking to, for the line an unsent message leaves behind. */
    peerName: String = "They",
    /**
     * Scroll-content inset for an overlaid composer; the viewport itself still extends underneath,
     * matching the RN source where the send bar floats over the thread.
     */
    bottomInset: Dp = 0.dp,
    /**
     * [bottomInset] read at layout time rather than passed as a value, which takes precedence.
     *
     * The composer's inset moves on every frame of the keyboard's animation. As a value it had to be
     * read in composition to be passed at all, which recomposed the conversation screen once a frame
     * for as long as the keyboard moved; read here by the list's own measure pass it only re-lays-out.
     */
    bottomInsetProvider: (() -> Dp)? = null,
    /** Same, for the overlaid header. */
    topInset: Dp = 0.dp,
    /**
     * The reader has scrolled close to the oldest message loaded, so there may be more to fetch.
     *
     * Fired once each time the list comes within reach of its far end; a caller with nothing more to
     * load simply does nothing, and it is not asked again until the list has grown and been reached.
     */
    onNearOldest: (() -> Unit)? = null,
    /**
     * The message being rewritten, whose row turns into an editor in place.
     *
     * In the thread rather than in the composer or an overlay: an edit is a correction to a message
     * you can already see, and it should be made where you can still see it.
     */
    editingId: String? = null,
    /**
     * The name to say has notifications silenced, or null when nobody has.
     *
     * Set only for a one-to-one iMessage conversation whose other party has shared a Focus with us
     * and currently has one on. Puts a line under the newest message saying so — it is a fact about
     * the conversation as it stands right now, not about any particular message in it, which is why
     * it lives at the tail rather than on a bubble.
     */
    focusSilencedName: String? = null,
    /**
     * Offers to break through that Focus for the last thing we said, where there is anything to
     * break through for.
     *
     * Null while there is not: nothing sent since the Focus came on, or the last thing sent has
     * been read anyway, or it has already been notified. The line above still stands in all three
     * cases — they have a Focus on either way — it simply has nothing to offer about it.
     */
    onNotifyAnyway: (() -> Unit)? = null,
    /** The editor's measured height, for the caller that has to keep it clear of the send bar. */
    onEditorHeight: (Int) -> Unit = {},
    onEditCommit: (String) -> Unit = {},
    onEditCancel: () -> Unit = {},
) {
    val settings = LocalSettings.current

    // Everything the conversation lays out is worked out from the messages in one pass — see
    // [ThreadModel] — and off the main thread once the conversation is long, so a receipt landing on
    // one message no longer re-walks the whole thread inside a frame.
    val model = rememberThreadModel(messages, members, isGroup, firstUnreadId)
    @Suppress("NAME_SHADOWING")
    val messages = model.messages
    // Only the newest outgoing message that can *carry* a receipt gets one — a call log or a
    // tombstone has no delivery state, so the receipt belongs to the message before it.
    val lastMineId = model.lastMineId
    state.forcedTails = model.forcedTails
    state.forcedAvatars = model.forcedAvatars
    // Separators are interleaved in the model rather than in the `items` block so `displayOrder` and
    // the separator indices agree: a scroll-to-message has to speak in the indices the list is
    // actually laid out with.
    val items = model.items
    // The typing row is always index 0 of the list, and the Focus line index 1 whenever it is
    // there at all, so everything else sits one or two further along than its place in this
    // collection — and an index is the only language a scroll speaks.
    state.displayOrder = remember(items, focusSilencedName) {
        buildList {
            add(TYPING_KEY)
            if (focusSilencedName != null) add(FOCUS_NOTICE_KEY)
            items.forEach { add(it.key) }
        }
    }

    /**
     * The messages currently showing their caption.
     *
     * Several at once: comparing when two messages were sent, or which of them was read, means
     * having both captions up together. Tapping a message toggles its own caption and leaves the
     * others alone.
     */
    var openStatusIds by remember { mutableStateOf(emptySet<String>()) }

    /**
     * Who the dots belong to, held past the moment they stop typing.
     *
     * The row shuts over a quarter of a second, and for that quarter of a second it still has an
     * avatar to draw — read live it would lose the member on the first frame of the exit and the
     * gutter would collapse out from under the bubble on its way down.
     */
    val typingMembersNow = if (isGroup) typingSenders.mapNotNull { memberFor(it, members) } else emptyList()
    var typingMembers by remember { mutableStateOf(typingMembersNow) }
    LaunchedEffect(typingMembersNow) { if (typingSenders.isNotEmpty()) typingMembers = typingMembersNow }

    // The scroll content's inset, as one object whose bottom edge is looked up by the list's own
    // measure pass — see [bottomInsetProvider] and [ThreadContentPadding].
    val latestBottomProvider by rememberUpdatedState(bottomInsetProvider)
    val latestBottomInset by rememberUpdatedState(bottomInset)
    val threadPadding = remember(topInset) {
        ThreadContentPadding(top = 12.dp + topInset) {
            12.dp + (latestBottomProvider?.invoke() ?: latestBottomInset)
        }
    }

    // Reaching back for older messages as the reader nears the far end of what is loaded. On a
    // reversed list the oldest rows are the highest indices, so "near the end" is near the top.
    val latestNearOldest by rememberUpdatedState(onNearOldest)
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LoadOlderThreshold
        }
            .distinctUntilChanged()
            .collect { near -> if (near) latestNearOldest?.invoke() }
    }

    val revealX = remember { Animatable(0f) }
    val density = LocalDensity.current
    val revealWidthPx = with(density) { RevealWidth.toPx() }
    val scope = rememberCoroutineScope()
    val currentEditingId by androidx.compose.runtime.rememberUpdatedState(editingId)
    val reveal = remember(revealX, revealWidthPx, scope, state) {
        TimestampReveal(revealX, revealWidthPx, scope, state.listState, canScroll = { currentEditingId == null })
    }
    // Their side gives up exactly the avatar gutter, so a group's incoming messages finish the
    // drag flush with the margin your own already keep. Nothing to give up in a one-to-one, where
    // there is no gutter and their bubbles are against that margin to begin with.
    // Each style reserves its own gutter, so "give up the gutter" is a different number in each.
    // Taken from the style in use rather than from Apple's, or a Google conversation finished the
    // drag two dip short of the margin it was supposed to square up against.
    val gutter = when (settings.bubbleType) {
        BubbleStyle.APPLE -> AppleGroupGutter
        BubbleStyle.GOOGLE -> GoogleGroupGutter
        BubbleStyle.WHATSAPP -> WhatsAppGroupGutter
    }
    val receivedTravelPx = with(density) { if (isGroup) gutter.toPx() else 0f }
    val revealShift = remember(revealX, revealWidthPx, receivedTravelPx) {
        RevealShift(
            full = { revealX.value },
            received = { revealX.value / revealWidthPx * receivedTravelPx },
        )
    }
    /** The same travel for the row's own time column, handed down as a lambda for the same reason. */
    val revealXNow: () -> Float = remember(revealX) { { revealX.value } }
    /** Whether the conversation is moving under the finger — a reply swipe waits for it to stop. */
    val listScrolling: () -> Boolean = remember(state) { { state.listState.isScrollInProgress } }
    /**
     * The row the receipt is on, and the row it is on its way off — the handover, spread over two
     * rows so it can be a move instead of a removal followed by an insertion.
     *
     * It waits for the message that caused it. A send has its own animation to play out — the
     * conversation sliding up, the new bubble growing into the gap — and starting the receipt's
     * move in the middle of that put three separate movements on screen at once.
     */
    // A handover in flight, and nothing at all the rest of the time.
    //
    // Derived rather than stored, so that "where the receipt lives" has a correct answer on the
    // very first frame a conversation is composed: with the newest outgoing message. Held in state
    // and seeded by an effect, it was null on the frame the thread first drew — the thread's own
    // messages arrive a frame after the composable does — so the newest row was built with its slot
    // shut and then opened it. Which is why opening a chat played a delivery receipt at you for a
    // message that had been delivered hours ago.
    var handover by remember { mutableStateOf<Handover?>(null) }

    // What the receipt was on last time round. Deliberately not snapshot state: by the time the
    // effect below runs, `lastMineId` is already the new message, so the one it is moving *from*
    // has to have been written down before it changed.
    val previousMine = remember { arrayOfNulls<String>(1) }
    // The frame a send lands, before the effect below has had a chance to start the handover. Read
    // as "no handover" it put the receipt on the new message for that one frame — the old row's line
    // blinked out and straight back in. Until the effect catches up, it stays where it was.
    val pendingFrom = previousMine[0]?.takeIf { lastMineId != null && it != lastMineId }
    val receiptOn = handover?.on ?: pendingFrom ?: lastMineId
    val receiptLeaving = handover?.leaving
    LaunchedEffect(lastMineId) {
        val from = previousMine[0]
        previousMine[0] = lastMineId
        // A conversation loading is not a handover — nor is the first message in one, which takes
        // the receipt from nobody.
        if (from == null || lastMineId == null || from == lastMineId) return@LaunchedEffect
        // The receipt stays where it is while the new message plays its arrival: a send has its own
        // animation, and starting the move in the middle of it put three things on screen at once.
        handover = Handover(on = from, leaving = null)
        delay(ReceiptHandoverDelayMs)
        // Both ends of the move, running against each other for the length of it.
        handover = Handover(on = lastMineId, leaving = from)
        delay(ReceiptGrowMs.toLong())
        handover = null
    }

    /**
     * The bubble still wearing the tail while a send's receipt is on its way down to the message
     * below it.
     *
     * The tail and the receipt are one handover seen twice: both belong to the newest outgoing
     * message. But `rows` takes the tail off the old bubble the instant the new one lands, which is
     * a beat before the receipt sets off — and for that beat the old message has neither, while the
     * new one is still at two-fifths of its size and growing. There was no tail anywhere in the run
     * for the length of the arrival, so the tail read as something that appeared at the end rather
     * than something that moved down.
     *
     * The arriving message keeps its own tail throughout — it grows in wearing it, which is the
     * whole point — so for that one beat the run has two. That is what makes the handover legible:
     * the tail is visibly on both bubbles before it is on only the lower one, and it comes off the
     * old message on the same frame its status line slides away.
     *
     * Apple only. The other two styles hang other things off `isLastInRun` — WhatsApp puts the
     * sender's face in the gutter with it — and neither draws a tail that follows a receipt.
     */
    val tailHeldBy =
        if (settings.bubbleType == BubbleStyle.APPLE && receiptOn != lastMineId) receiptOn else null

    /**
     * An older message's "Read", standing beside the newest message's receipt — see
     * [ThreadModel.heldReadId] — and the ones that have just stopped standing, shutting their slot.
     *
     * Letting go is a shrink, not a removal: the row keeps its slot for the length of the slot's own
     * animation with the slot shut, exactly as the old row of a handover does. Run on the thread's
     * scope rather than the effect's, so a second change landing mid-shrink starts its own instead
     * of cancelling this one and leaving the row holding a slot forever.
     */
    val heldReadId = model.heldReadId
    var heldLeaving by remember { mutableStateOf(emptySet<String>()) }
    // What was held last time round, written down before it changed — the same reason as
    // [previousMine]. Read straight away it covers the frame before the effect runs, when the row
    // would otherwise lose its receipt outright and swap to its tapped caption for a frame.
    val previousHeld = remember { arrayOfNulls<String>(1) }
    val heldDropping = previousHeld[0]?.takeIf { it != heldReadId }
    LaunchedEffect(heldReadId) {
        val from = previousHeld[0]
        previousHeld[0] = heldReadId
        if (from == null || from == heldReadId) return@LaunchedEffect
        heldLeaving = heldLeaving + from
        scope.launch {
            delay(ReceiptGrowMs.toLong())
            heldLeaving = heldLeaving - from
        }
    }
    /** Whether this row wears a held "Read", or is letting one go. */
    fun holdsRead(id: String) = id == heldReadId || id == heldDropping || id in heldLeaving
    // Messages that turned up while the conversation was already on screen — the ones that get an
    // entrance. Everything present when the thread first loads is simply there, so opening a chat
    // doesn't play a send animation for the whole history.
    val seenIds = remember { mutableSetOf<String>() }
    val loaded = remember { booleanArrayOf(false) }
    val arrivals = remember(messages) {
        val ids = messages.map { it.id }
        if (!loaded[0]) {
            loaded[0] = ids.isNotEmpty()
            seenIds.addAll(ids)
            emptySet()
        } else {
            val fresh = ids.filterNot { it in seenIds }.toSet()
            seenIds.addAll(ids)
            fresh
        }
    }

    // Read by every part of every row that has to slide when the time column opens — the bubbles,
    // the outlines above them, the faces beside them, and the connectors drawn between the three.
    // Provided once around the whole list rather than passed down: the two numbers are the same for
    // every row, and threading them through would have put a reveal parameter on every bubble in
    // the app to answer a question only this conversation asks.
    val stickerSpacing = remember { StickerSpacing() }
    CompositionLocalProvider(
        LocalRevealShift provides revealShift,
        LocalTimestampReveal provides if (swipeTimestampsEnabled) reveal else null,
        LocalStickers provides model.stickers,
        LocalStickerSpacing provides stickerSpacing,
    ) {
    LazyColumn(
        state = state.listState,
        reverseLayout = true,
        // Editing holds the conversation still. The field has grown out of one row and the rest of
        // the thread is dimmed behind it: scrolling it away would leave the edit floating over a
        // conversation it no longer belongs to.
        userScrollEnabled = editingId == null,
        // 12dp of breathing room either end, plus whatever the overlaid header/composer cover —
        // `chatContent` in the RN source.
        contentPadding = threadPadding,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (swipeTimestampsEnabled) {
                    // For a drag that lands on none of the rows at all — the padding at either end
                    // of the conversation, or the empty screen above a short one. Rows claim their
                    // own drags first (they are children, offered the Main pass ahead of this), and
                    // a claimed drag cancels this one, so the column is never driven twice.
                    Modifier.pointerInput(reveal) {
                        detectReplyDrag(
                            onEnd = { reveal.settle() },
                            onDrag = { _, delta ->
                                reveal.drag(delta.x)
                                reveal.scrollBy(delta.y)
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        // First on a reversed list is last on screen: the dots sit below the newest message, where
        // the one being written is about to appear.
        //
        // The row is in the list whether anyone is typing or not, shut to nothing when nobody is.
        // Added and removed outright it took a bubble's height out of the conversation in a single
        // frame, which jolted the thread every time someone started or stopped writing. Kept, it
        // opens and shuts instead — and being index 0 on a reversed list, it grows upward from the
        // bottom edge, so the conversation eases up to make room and stays pinned to its tail.
        item(key = TYPING_KEY, contentType = "typing") {
            TypingSlot(senders = typingMembers, visible = typingSenders.isNotEmpty())
        }

        // Above the dots and below the newest message, which is where the fact belongs: it is about
        // the conversation as it stands, not about anything anyone has said in it.
        if (focusSilencedName != null) {
            item(key = FOCUS_NOTICE_KEY, contentType = "focus") {
                FocusSilencedNotice(
                    name = focusSilencedName,
                    onNotifyAnyway = onNotifyAnyway,
                    modifier = Modifier.animateItem(placementSpec = null),
                )
            }
        }

        // The date header sits above the oldest message, which on a reversed list is the *end* of
        // the content — so it goes in last.
        items(items, key = { it.key }, contentType = { it::class }) { item ->
            // Fade only, never placement. A separator that animated where it sits would slide
            // against the messages either side of it every time one was added at the tail — the
            // whole list shifts by an index on a send, and the bubbles deliberately don't animate
            // that. Appearing and disappearing is the part worth softening.
            if (item is ThreadItem.SectionBreak) {
                SectionBreakLabel(
                    at = item.at,
                    bubbleType = settings.bubbleType,
                    // A header that arrives with the message under it opens on that message's own
                    // curve — see [SectionBreakLabel]. Everything else is simply there.
                    arriving = item.heads in arrivals,
                    // The list's own fade is left to the way out only: on the way in the header
                    // now has an entrance of its own, and two of them ran against each other.
                    modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = null),
                )
                return@items
            }
            if (item is ThreadItem.UnreadBreak) {
                UnreadBreakLabel(modifier = Modifier.animateItem(placementSpec = null))
                return@items
            }
            val row = (item as ThreadItem.Bubble).row
            // A conversation event is not a message anyone can act on: no reply swipe, no
            // selection checkbox, no long-press menu, no receipt. It is drawn and nothing else.
            if (row.message is Message.System) {
                MessageBubble(message = row.message, isFirstInRun = true, isLastInRun = true)
                return@items
            }
            val id = row.message.id
            // The hole in the scrim closes with the edit, wherever the row happens to be.
            if (id == editingId) DisposableEffect(id) { onDispose { state.editingRow = null } }
            // Deliberately *not* `animateItem`: the conversation already slides by exactly one
            // bubble's height when the list follows a new message to the tail, and a placement
            // animation on top of that scroll is two movements fighting over the same frames —
            // which is what made a send drop, lift and drop again. The scroll carries the older
            // bubbles; the new one has its own entrance below.
            val itemMotion = Modifier
            // A row that scrolls out of the window is disposed, and with it any idea of where it
            // is — which is exactly what "not measurable" means to the overlay.
            DisposableEffect(id) { onDispose { state.forget(id) } }
            // The old bubble's tail waits for the receipt rather than coming off the moment the new
            // message lands. The new message is left alone: it arrives with its own tail and grows
            // in wearing it, so the run carries two until the handover. See [tailHeldBy].
            // A held "Read" keeps its tail too, for as long as it stands: it comes off on the frame
            // the line starts to shrink, the same moment a handover takes the old row's.
            val holdsTail = settings.bubbleType == BubbleStyle.APPLE && id == heldReadId
            val tailed = if (id == tailHeldBy || holdsTail) row.copy(isLastInRun = true) else row
            MessageThreadRow(
                modifier = itemMotion,
                row = tailed,
                // A lambda, not the value. Read in composition it recomposed — and so re-placed —
                // every visible row on every frame of the drag, and a row re-placed mid-drag
                // re-reports its boxes with the reveal's own layer translation already folded into
                // them. The connectors are drawn off those boxes and then slid by the same travel
                // again, which is what had a line leaving a face at twice the face's own speed.
                // See [RevealShift].
                revealX = revealXNow,
                revealWidthPx = revealWidthPx,
                reveal = if (swipeTimestampsEnabled) reveal else null,
                listScrolling = listScrolling,
                selecting = selecting,
                selected = id in selectedIds,
                // Both ends of a handover keep a slot for the length of it, one opening as the
                // other shuts.
                showReceipt = id == receiptOn || id == receiptLeaving || holdsRead(id),
                receiptVisible = id == receiptOn || id == heldReadId,
                receiptHandedOver = handover?.leaving != null && id == handover?.on,
                arriving = id in arrivals,
                bubbleType = settings.bubbleType,
                hidden = id in hiddenIds,
                bubbleHidden = id in bubbleHiddenIds,
                // The same reading the entrance animation runs off: a message that is arriving is
                // exactly a message whose effect has not been seen yet.
                playEffect = id in arrivals,
                transfer = transfers[id],
                highlighted = id == highlightedId,
                statusOpen = id in openStatusIds,
                editing = id == editingId,
                onEditCommit = onEditCommit,
                onEditCancel = onEditCancel,
                peerName = peerName,
                recipientCount = members.size.coerceAtLeast(1),
                onPositioned = { rect ->
                    state.place(id, rect)
                    if (id == editingId) {
                        state.editingRow = rect
                        onEditorHeight(rect.height.toInt())
                    }
                },
                onBubblePositioned = { box -> state.placeBubble(id, box, row.message.mine) },
                onAvatarPositioned = { box -> state.placeAvatar(id, box) },
                onOutlinePositioned = { box -> state.placeOutline(id, box, row.message.replyTo()?.mine == true) },
                // What this reply's connector aims at when there is no outline to measure — the
                // bubble a row up, which only the thread can hand it. Read at layout time rather
                // than captured, so a message that scrolls into place while its answer is already
                // on screen is picked up on the next pass instead of being missed for good.
                quotedAnchor = { row.connectorTarget?.let(state::anchorRect) },
                // Read inside the connector's own draw, so it is invalidated the moment the row it
                // aims at becomes measurable — see [MessageThreadState.placedRevision].
                anchorRevision = { state.placedRevision },
                // Null means every link in this reply's chain was one the reader can already see —
                // see [ThreadRow.connectorTarget]. No target, no line.
                drawConnector = row.connectorTarget != null,
                isGroup = isGroup,
                onReply = { fromX -> onReply(row.message, fromX) },
                onLongPress = { onLongPress(row.message) },
                onDoubleTap = { onDoubleTap(row.message) },
                // A grouped bubble stands for several messages, and selecting it selects all of
                // them: forwarding, saving or deleting "these photos" has to mean every photo in
                // the group, not only the newest one the row happens to be keyed by.
                onToggleSelect = { groupedIds(row.message).forEach(onToggleSelect) },
                onTap = { onBubble ->
                    // Whatever the message had to open full screen wins; the caption is what a tap
                    // means on everything else. A call log or a tombstone has no time worth
                    // printing, so those keep doing nothing.
                    if (!onTap(row.message, onBubble) && row.message.canHaveStatus()) {
                        openStatusIds = if (id in openStatusIds) openStatusIds - id else openStatusIds + id
                    }
                },
                myHandles = myHandles,
                mentionNames = mentionNames,
                onOpenThread = { onOpenThread(row.message) },
                onScreenEffect = onScreenEffect,
                onReactionsClick = { onReactionsClick(row.message) },
                onFailureClick = { onFailureClick(row.message) },
                transcription = when {
                    onTranscribe == null -> TranscriptionState.OFF
                    row.message.id in transcribing -> TranscriptionState.RUNNING
                    !transcriptionReady -> TranscriptionState.NO_MODEL
                    row.message.id in transcriptionFailed -> TranscriptionState.FAILED
                    else -> TranscriptionState.IDLE
                },
                onTranscribe = onTranscribe?.let { start -> { start(row.message) } },
                onPlayed = onVoicePlayed?.let { played -> { played(row.message) } },
            )
        }

    }
    }
}

/**
 * The conversation's scroll-content inset, with a bottom edge that is looked up rather than fixed.
 *
 * `PaddingValues` is an interface the lazy list only asks during measure, so a bottom edge computed
 * on demand is read in the measure pass — which is where a keyboard-driven inset belongs. A plain
 * `PaddingValues(bottom = …)` had to be built in composition from a value read in composition.
 */
@Stable
private class ThreadContentPadding(private val top: Dp, private val bottom: () -> Dp) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateTopPadding(): Dp = top
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateBottomPadding(): Dp = bottom()
}

/** Every message a row stands for: the whole group for a grouped bubble, otherwise itself. */
private fun groupedIds(message: Message): List<String> =
    ((message as? Message.AttachmentMessage)?.attachment as? Attachment.MediaGroup)
        ?.messageIds
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(message.id)

/** How close, in rows, the reader gets to the oldest loaded message before more are asked for. */
private const val LoadOlderThreshold = 20

/**
 * A receipt on its way from one message to the next: where it is now, and what it is leaving.
 *
 * `leaving` is null for the beat before the move starts, during which the receipt is simply still
 * on the older message.
 */
private data class Handover(val on: String, val leaving: String?)


/**
 * The line marking where the unread messages start.
 *
 * A rule either side of the words rather than a bare centred label, so it reads as a boundary
 * across the conversation instead of as another date separator.
 */
@Composable
private fun UnreadBreakLabel(modifier: Modifier = Modifier) {
    // The conversation's own colour where it has one, so the rule reads as part of this thread
    // rather than as the app's accent laid across it. `mine` is the scheme's strongest colour and
    // the one already on screen either side of the break, in the bubbles.
    val accent = LocalChatTheme.current?.mine ?: MaterialTheme.colorScheme.secondary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = accent.copy(alpha = 0.5f))
        Text(
            "UNREAD MESSAGES",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = accent.copy(alpha = 0.5f))
    }
}

/**
 * When the messages under this go back to, said the way the style in use says it: a pill on
 * WhatsApp, a bare centred line on the other two.
 *
 * Public because the focused overlays head their lifted stacks with the same line. A thread pulled
 * out of the conversation has lost every other cue about when it happened — the messages around it
 * are behind a blur — so it brings its date with it, drawn by this rather than by a second copy
 * that would drift from it.
 */
@Composable
fun SectionBreakLabel(
    at: Long,
    bubbleType: BubbleStyle,
    /**
     * Whether the message under this header is arriving right now, in which case the two open
     * together.
     *
     * A message that starts a new day is one event and should look like one. The header used to
     * claim its full height the frame it was added — the conversation stepped up to make room for
     * a date that was not drawn yet — and then faded in on the list's own spring while the bubble
     * below it was still scaling out of the corner: two movements, on two clocks, for one send.
     * Run off the same curve and the same duration as the bubble's entrance, the room the header
     * needs is opened by the same push that opens the room for the message.
     */
    arriving: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Read once, at first composition: scrolling the same header off and back on is not a second
    // arrival, and a header already on screen was never one.
    val entrance = remember { Animatable(if (arriving) 0f else 1f) }
    val bornArriving = remember { arriving }
    LaunchedEffect(Unit) { if (entrance.value < 1f) entrance.animateTo(1f, tween(ArrivalMs, easing = ArrivalEasing)) }
    Box(
        // The gap separates it from the oldest message *below* it.
        modifier = modifier
            .fillMaxWidth()
            // Anchored to its bottom edge, the side the message it heads is on: the header is
            // uncovered downward out of the top of the conversation rather than unrolling in place.
            .then(if (bornArriving) Modifier.arrivalHeight { entrance.value } else Modifier)
            .then(if (bornArriving) Modifier.graphicsLayer { alpha = entrance.value } else Modifier)
            .padding(top = 8.dp, bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (bubbleType) {
            // A panel behind the words, the way WhatsApp sets its date apart from the wallpaper
            // rather than trusting bare text to stay legible over one.
            BubbleStyle.WHATSAPP -> Text(
                formatWhatsAppSection(at),
                fontSize = 12.sp,
                color = muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            BubbleStyle.GOOGLE -> Text(
                formatGoogleSection(at),
                fontSize = 12.sp,
                color = muted,
                textAlign = TextAlign.Center,
            )
            // The day carries the weight and the clock hanging off it doesn't — the same split the
            // receipt under a bubble is set in.
            BubbleStyle.APPLE -> {
                val header = formatAppleSection(at)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(header.day) }
                        append(" ")
                        append(header.time)
                    },
                    fontSize = 11.sp,
                    color = muted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Everything a [settleHeight] needs to remember between layout passes, kept out of the snapshot
 * system: it is written from layout, and nothing reads it during composition. */
private class HeightSettle {
    var started = false
    var last = 0f
    var reactionKey = ""
}

/**
 * Eases the wrapped content through a change of height instead of applying it between two frames.
 *
 * The case this is here for is an unsend: a message becomes a one-line tombstone, and dropping most
 * of a bubble's height in a single frame yanked the whole conversation down with it. It catches the
 * smaller ones too — a reaction landing, an edit that costs the text a line, an image that resolves
 * its size once it has loaded.
 *
 * `animateContentSize` is this in one line, and is the wrong tool: it clips to its own bounds, and
 * a reaction is set down on a bubble at four times its final size before shrinking into place —
 * which a clip cuts the corner off. This reports an animated height and lets the drawing spill.
 *
 * The first measurement is taken as-is. A row composed at its own size was never a change, and
 * animating up from nothing would play an entrance for every bubble that scrolled into view.
 *
 * [fromBottom] says which edge the content holds on to while the height is in flight. The content
 * is its new size at once and only the reported height eases, so pinned to the top — in a list
 * that grows upward from each row's bottom edge — room opening *above* a bubble dropped the bubble
 * by the whole of it on the first frame and then carried it back up. Pinned to the bottom the
 * bubble never moves, and the height easing is the rows above being pushed away. That is where
 * Apple's tapback makes its room; the other styles hang their pill below, and keep the top.
 *
 * A change that comes with a change of [reactionKey] runs on the badge's own clock, so the room
 * opens exactly as fast as the reaction grows into it.
 */
@Composable
private fun Modifier.settleHeight(reactionKey: String, fromBottom: Boolean): Modifier {
    val height = remember { Animatable(0f) }
    val settle = remember { HeightSettle() }
    val scope = rememberCoroutineScope()
    return this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val measured = placeable.height.toFloat()
        val first = !settle.started
        val reacted = settle.reactionKey != reactionKey
        settle.reactionKey = reactionKey
        if (first) {
            settle.started = true
            settle.last = measured
            scope.launch { height.snapTo(measured) }
        } else if (settle.last != measured) {
            settle.last = measured
            val spec = if (reacted) tween<Float>(ReactionSettleMs, easing = ReactionSettleEasing)
            else tween(BubbleResizeMs, easing = ArrivalEasing)
            scope.launch { height.animateTo(measured, spec) }
        }
        // Read here rather than in composition, so a height in flight re-lays out the row without
        // recomposing it — and on the very first pass, before the snap has run, the measurement
        // itself stands in for an animated value that is still zero.
        val current = (if (first) measured else height.value).roundToInt().coerceAtLeast(0)
        layout(placeable.width, current) {
            placeable.place(0, if (fromBottom) current - placeable.height else 0)
        }
    }
}

/**
 * Where the bubble's box sits inside its row.
 *
 * The field that replaces a bubble during an edit has to stand exactly where the bubble was, and
 * "the middle of the row" is not that: a bubble with a tail keeps an overhang below itself for the
 * tail to be drawn into, so its own middle is above the row's. Measured rather than derived,
 * because the overhang is a function of the text size and the style in use.
 */
private class EditorFrame {
    var box: LayoutCoordinates? = null

    /** The bubble's top edge, in the row's coordinates. */
    var top by mutableFloatStateOf(0f)
        private set

    fun measure(bubble: LayoutCoordinates) {
        val box = box?.takeIf { it.isAttached } ?: return
        if (!bubble.isAttached) return
        top = box.localPositionOf(bubble, Offset.Zero).y
    }
}

/**
 * Opens an arriving row's height in step with the bubble growing into it.
 *
 * The row used to claim its full height on the frame it was composed, so the conversation jumped
 * upward in one go and the bubble was left to grow afterwards into a gap that was already sitting
 * there — two movements, one after the other, for what is one event. Height and scale now run off
 * the same number, so the push and the growth are the same movement.
 *
 * The content is placed against the bottom edge, which is the corner the bubble is scaling out of:
 * the message holds still at the foot of the conversation and the space opens above it.
 */
private fun Modifier.arrivalHeight(progress: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = (placeable.height * progress()).roundToInt().coerceAtLeast(0)
    layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
}

/**
 * The typing row's own space, and the only thing that animates it open or shut.
 *
 * Height, opacity and scale run off one value, and the dots are anchored to the *bottom* of
 * whatever height the slot currently claims and clipped to it — so the bubble rises out of the
 * bottom edge of the conversation rather than unrolling in place, which is the direction the
 * message it stands in for will arrive from.
 *
 * Composed only while there is something to draw: the dots run an endless animation of their own,
 * and leaving that ticking under every conversation where nobody is typing is work for nothing.
 */
@Composable
private fun TypingSlot(senders: List<GroupMember>, visible: Boolean) {
    // Starts settled, so a conversation opened while someone is already writing doesn't play an
    // entrance for a state that was true before you got there.
    val shown = remember { Animatable(if (visible) 1f else 0f) }
    var present by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        if (visible) {
            present = true
            shown.animateTo(1f, tween(TypingGrowMs, easing = ArrivalEasing))
        } else {
            shown.animateTo(0f, tween(TypingGrowMs, easing = ArrivalEasing))
            present = false
        }
    }
    if (!present) return
    Box(
        modifier = Modifier
            .graphicsLayer {
                alpha = shown.value
                scaleX = TypingStartScale + (1f - TypingStartScale) * shown.value
                scaleY = TypingStartScale + (1f - TypingStartScale) * shown.value
                transformOrigin = TransformOrigin(0f, 1f)
            }
            // Outside the layout below, so it clips to the height the slot has actually claimed
            // rather than to the dots' own full height.
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val height = (placeable.height * shown.value).roundToInt()
                layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
            },
    ) {
        TypingBubble(senders = senders)
    }
}

/**
 * Apple's own colour for a Focus, and OpenBubbles' for this line — Material's deep purple.
 *
 * A named constant rather than a theme colour on purpose: a Focus is not one of this app's own
 * states, it is the other person's phone reporting itself, and it reads as that in every
 * conversation regardless of what colour the bubbles in it happen to be.
 */
private val FocusPurple = Color(0xFF673AB7)

/**
 * The line under the newest message saying that the other end has a Focus on, and the one thing
 * that can be done about it.
 *
 * Two states, and the difference between them is what the words are *for*. With nothing to offer,
 * the sentence is the whole message and carries the Focus's own colour. With a break-through
 * available, the button becomes the thing being said and takes the colour, and the sentence steps
 * back to a caption explaining it — which is exactly OpenBubbles' arrangement, and the reason its
 * text turns grey the moment "Notify Anyway" appears underneath it.
 */
@Composable
private fun FocusSilencedNotice(
    name: String,
    onNotifyAnyway: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val offering = onNotifyAnyway != null
    val lineColor = if (offering) MaterialTheme.colorScheme.outline else FocusPurple
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // Generous above and tight below, so the line reads as hanging off the last message rather
        // than floating loose between it and the send bar. Tighter still with a button under it,
        // which brings padding of its own.
        modifier = modifier.fillMaxWidth().padding(top = 20.dp, bottom = if (offering) 0.dp else 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Bedtime,
                contentDescription = null,
                tint = lineColor,
                modifier = Modifier.size(FocusMoonSize),
            )
            Text(
                " $name has notifications silenced",
                style = MaterialTheme.typography.bodyLarge,
                color = lineColor,
            )
        }
        if (onNotifyAnyway != null) {
            // No pill and no fill: the words are the button. The touch target is still Material's
            // minimum, which is a good deal easier to hit than the 30dp OpenBubbles shrink-wraps
            // this to, and costs the line nothing anyone can see.
            TextButton(onClick = onNotifyAnyway, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(
                    "Notify Anyway",
                    style = MaterialTheme.typography.labelLarge,
                    color = FocusPurple,
                )
            }
        }
    }
}

/** Sized to the sentence beside it, so the moon reads as part of the line rather than an icon on it. */
private val FocusMoonSize = 17.dp

/** How long the bubble takes to stretch into a field, and to fold back into a bubble afterwards. */
private const val EDIT_OPEN_MS = 340
private const val EDIT_CLOSE_MS = 260
private val EditEasing = CubicBezierEasing(0.32f, 0.72f, 0.15f, 1f)

@Composable
private fun MessageThreadRow(
    modifier: Modifier,
    row: ThreadRow,
    /** How far the time column has slid — read at the draw, never in composition. */
    revealX: () -> Float,
    revealWidthPx: Float,
    /** The shared time column, for whatever part of a drag on this row isn't a reply. */
    reveal: TimestampReveal?,
    listScrolling: () -> Boolean,
    selecting: Boolean,
    selected: Boolean,
    showReceipt: Boolean,
    /** False for the row the receipt is leaving, which shuts its slot as the other opens. */
    receiptVisible: Boolean,
    /**
     * True for the row a handover is moving the receipt onto. Its line opens on the handover's own
     * frame, the same frame the old row's starts shutting, rather than waiting for [grown]: the
     * handover already waits out the arrival, and a row whose arrival started late would otherwise
     * open its line a beat after the other had begun to close.
     */
    receiptHandedOver: Boolean = false,
    /** True for a message that landed while the conversation was on screen — it gets an entrance. */
    arriving: Boolean,
    bubbleType: BubbleStyle,
    hidden: Boolean,
    /** The same, for a lift that is taking only the bubble — see [MessageThread]. */
    bubbleHidden: Boolean,
    /** True on the one composition where this message's send effect should fire. */
    playEffect: Boolean,
    /** Non-null while this row's attachment is still being sent. */
    transfer: TransferProgress? = null,
    highlighted: Boolean,
    /** True while this message is the one showing its tapped time-and-status caption. */
    statusOpen: Boolean,
    /** True while this row is an editor rather than a bubble. */
    editing: Boolean,
    onEditCommit: (String) -> Unit,
    onEditCancel: () -> Unit,
    peerName: String,
    /** How many people the conversation's messages go to; 1 in a one-to-one. */
    recipientCount: Int,
    onPositioned: (ThreadRowRect) -> Unit,
    /** Where the bubble itself landed in root coordinates, reported apart from its row's box. */
    onBubblePositioned: (Rect) -> Unit,
    /** The same for the sender's face, where a group message has one. */
    onAvatarPositioned: (Rect?) -> Unit,
    /** And for the outline above it, which a reply further down may aim at instead. */
    onOutlinePositioned: (Rect?) -> Unit,
    /** What this reply's connector should reach for, when the message it answers is a row away. */
    quotedAnchor: () -> ThreadAnchor?,
    /** False for a reply whose whole chain is adjacent and same-sender — nothing to draw. */
    drawConnector: Boolean,
    /** Bumped when any row becomes measurable, so a waiting connector redraws. */
    anchorRevision: () -> Int,
    /** Whether the conversation reserves an avatar gutter beside received messages. */
    isGroup: Boolean,
    onReply: (fromX: Float) -> Unit,
    onLongPress: () -> Unit,
    onDoubleTap: () -> Unit,
    onToggleSelect: () -> Unit,
    onTap: (onBubble: Boolean) -> Unit,
    myHandles: Set<String>,
    mentionNames: List<String>,
    onOpenThread: () -> Unit,
    onScreenEffect: (MessageEffect, Rect) -> Unit,
    onReactionsClick: () -> Unit,
    onFailureClick: () -> Unit,
    transcription: TranscriptionState = TranscriptionState.OFF,
    onTranscribe: (() -> Unit)? = null,
    onPlayed: (() -> Unit)? = null,
) {
    // A sticker hung off the edge between this message and the next pushes them apart, and the
    // bubble left standing on its own above that gap wears a tail, as one ending a run does.
    val stickerTail = !row.isLastInRun && LocalStickerSpacing.current?.gapAfter(row.message.id, row.nextInRun) == true
    // An Apple reply draws its quote above the bubble, spanning the row. Sliding the row would carry
    // the quote off the screen with it, so these bubbles move inside their row instead: the quote
    // holds still and only the bubble travels, for the timestamp reveal and the reply swipe alike.
    // Every Apple row moves its bubble from the inside, not just the ones carrying a quote.
    //
    // Offsetting the row instead carried the whole attributed block — the sender's face and their
    // name travelled with the bubble, so swiping a group message dragged the person who wrote it
    // across the screen, and the reply arrow was uncovered beside the block rather than beside the
    // message it belongs to. See [AppleBubble]'s `placed`.
    val splitReveal = bubbleType == BubbleStyle.APPLE
    // What the caption under this bubble follows, so it stays under it — see [RevealShift]. Zero
    // on the styles that still move the whole row, which carries the caption with everything else.
    val rowShift = LocalRevealShift.current

    // Where the bubble's middle is inside this row, so the time column can line up with the message
    // rather than with the whole strip — which on a row carrying a quote, a receipt or a sender's
    // name is a visibly different place.
    var bubbleCenterY by remember { mutableFloatStateOf(0f) }

    // The edit's own progress: 0 with the bubble as it is, 1 with the field at full width. Driven
    // here rather than inside the editor so the bubble underneath can fade against the same number,
    // and so cancelling folds the field back down instead of dropping it.
    val editMorph = remember { Animatable(0f) }
    LaunchedEffect(editing) {
        editMorph.animateTo(if (editing) 1f else 0f, tween(if (editing) EDIT_OPEN_MS else EDIT_CLOSE_MS, easing = EditEasing))
    }
    // The swap is done well before the stretch is: by the time the field is visibly wider than the
    // bubble, it is the only one of the two still being drawn.
    val editInk = (editMorph.value * 2.5f).coerceIn(0f, 1f)
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    // Seeded from the message each time an edit starts, not held across them: reopening an edit you
    // discarded should offer the message as it stands, not the words you decided against.
    LaunchedEffect(editing) { if (editing) draft = editorDraft((row.message as? Message.Text)?.text.orEmpty()) }
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var bubbleWidthPx by remember { mutableIntStateOf(0) }

    /** Whether this message's earlier versions are showing, opened from its own "Edited" link. */
    var historyOpen by remember { mutableStateOf(false) }

    // Where this row's bubble is on screen, for the screen effects that aim at it. A plain holder,
    // not snapshot state: it is written from layout on every frame of a scroll and read once, at
    // the moment a message lands.
    val effectBox = remember { arrayOfNulls<Rect>(1) }
    LaunchedEffect(playEffect) {
        if (!playEffect) return@LaunchedEffect
        val effect = row.message.effect() ?: return@LaunchedEffect
        if (effect.kind == EffectKind.SCREEN) onScreenEffect(effect, effectBox[0] ?: Rect.Zero)
    }

    // Held by the row rather than by the bubble, because the thing that replays it — the line under
    // the message — is drawn by the row. See [MessageBubble.effectState].
    val messageEffect = row.message.effect()
    val bubbleEffect = rememberBubbleEffect(messageEffect, autoPlay = playEffect)
    /** Plays it again, wherever it belongs: on the bubble, or over the whole screen. */
    val replayEffect: (() -> Unit)? = messageEffect?.let { effect ->
        {
            when (effect.kind) {
                EffectKind.BUBBLE -> bubbleEffect.play()
                EffectKind.SCREEN -> onScreenEffect(effect, effectBox[0] ?: Rect.Zero)
            }
        }
    }

    /** Whether a retracted message's words are showing, opened from the tombstone's "Show" link. */
    var unsentShown by remember { mutableStateOf(false) }

    /** What it said before. Read here because both the bubble and the line under it need it. */
    val editHistory = (row.message as? Message.Text)?.history.orEmpty()
    /** Where the bubble's own box sits inside the row, which is where the field has to sit too. */
    val editorFrame = remember { EditorFrame() }
    val rowDensity = LocalDensity.current

    /** Nothing on this row answers a gesture — see the note at [MessageInteractions] below. */
    val inert = editing || row.message is Message.Deleted

    // Everything drawn beside an incoming group bubble rather than inside it — the edit history above,
    // the status/edited/reply line below — carries the same avatar-gutter inset the bubble does, so
    // it lines up with the message and not with the screen edge.
    val metaGroupGutter =
        if (bubbleType == BubbleStyle.APPLE && row.sender != null && !row.message.mine) AppleGroupGutter else 0.dp

    // A message that has just landed grows out of the corner it was sent from while the rest of the
    // conversation slides up past it. Read once, at the row's first composition: scrolling the same
    // message off and back on is not a second arrival.
    val entrance = remember { Animatable(if (arriving) 0f else 1f) }
    val bornArriving = remember { arriving }
    /**
     * Whether this row has finished growing — which is when it is allowed to show a status line.
     *
     * A send is one movement: the message comes out of the corner it was sent from and settles. The
     * receipt used to be *inside* that movement, because "which message wears the receipt" follows
     * the newest outgoing message and that is already the new one on the frame it lands, a beat
     * before the handover effect can say otherwise. So the bubble grew with a "Delivered" already
     * printed under it, the effect ran, and the line shrank away again halfway up — a second thing
     * moving inside the first, at whatever scale the first had reached.
     *
     * Held here rather than read off [entrance] directly: the animation's value is only ever
     * sampled inside a `graphicsLayer`, and reading it in composition would recompose the row on
     * every frame of its own arrival to answer a question that changes once.
     */
    var grown by remember { mutableStateOf(!arriving) }
    /** The room under this row's Apple tail, kept by the caption row rather than the bubble. */
    val tailReserve = remember { AppleTailReserve() }
    /** How far the line under this row is open, for the Apple caption row around it. */
    val slotProgress = remember { SlotProgress() }
    LaunchedEffect(Unit) {
        if (entrance.value < 1f) entrance.animateTo(1f, tween(ArrivalMs, easing = ArrivalEasing))
        grown = true
    }

    val content: @Composable () -> Unit = {
        Column {
            // Outside the settling layer below, and outside the bubble itself: the row grows upward
            // from its own bottom edge on a reversed list, so a stack that claims its height here
            // is uncovered from behind a message that never moves. See [EditHistoryStack].
            EditHistoryStack(
                versions = editHistory,
                mine = row.message.mine,
                time = row.message.time,
                visible = historyOpen,
                startInset = metaGroupGutter,
            )
            // A lifted message is drawn by the overlay instead, so the bubble here goes invisible
            // while it is away — but only the bubble. The receipt under it belongs to the row and
            // stays where it is: what the overlay moves is the message, and a "Delivered" that
            // blinks out and back every time you press a bubble reads as the status being lost.
            // Only this row's own bubble is told about the send; a quote of it somewhere else, or
            // the same picture in another conversation, is not the thing being uploaded.
            CompositionLocalProvider(LocalAttachmentTransfer provides transfer) {
            Box(modifier = Modifier.graphicsLayer { alpha = if (hidden) 0f else 1f }) {
              MessageInteractions(
                  bubbleType = bubbleType,
                  mine = row.message.mine,
                  // A tombstone has no checkbox either: there is nothing to forward, copy or delete.
                  selecting = selecting && !inert,
                  selected = selected && !inert,
                  // Neither a tombstone nor a row being rewritten is something you can swipe,
                  // long-press or tap. On the tombstone there is no message left to act on — the line
                  // is a note saying one used to be here, and offering to reply to it or open a
                  // receipt on it was offering to act on the absence. On the editor every one of
                  // those would act on the message the field in front of you is replacing.
                  enabled = !inert,
                  // An unsent message still offers what it said — see [MessageInteractions.longPressOnly].
                  longPressOnly = row.message is Message.Deleted && !editing,
                  onReply = onReply,
                  onLongPress = onLongPress,
                  onDoubleTap = onDoubleTap,
                  onToggleSelect = onToggleSelect,
                  onTap = onTap,
                  splitReveal = splitReveal,
                  reveal = reveal,
                  listScrolling = listScrolling,
                  onBubbleCenterY = { centre -> if (centre != bubbleCenterY) bubbleCenterY = centre },
              ) { swipeX, onBubblePlaced ->
                // Whatever changes the bubble's height changes it over a quarter of a second rather
                // than between two frames. The case this is here for is an unsend: a message becomes
                // a one-line tombstone, and dropping most of a bubble's height in a single frame
                // yanked the whole conversation down. It catches the smaller ones too — a reaction
                // arriving, an edit that costs the text a line.
                Box(
                  modifier = Modifier
                      .settleHeight(
                          reactionKey = row.message.reactionSettleKey(),
                          fromBottom = bubbleType == BubbleStyle.APPLE,
                      )
                      .onSizeChanged { rowWidthPx = it.width }
                      .onGloballyPositioned { editorFrame.box = it },
                ) {
                  Box(modifier = Modifier.graphicsLayer { alpha = 1f - editInk }) {
                    MessageBubble(
                      message = row.message,
                      isFirstInRun = row.isFirstInRun,
                      isLastInRun = row.isLastInRun,
                      quoteAdjacent = row.quoteAdjacent,
                      splitTop = row.splitTop,
                      splitBottom = row.splitBottom,
                      threadEnd = row.threadEnd,
                      bubbleHidden = bubbleHidden,
                      playEffect = playEffect,
                      effectState = bubbleEffect,
                      sender = row.sender,
                      peerName = peerName,
                      // Only the reply swipe. The time column's own travel reaches the bubble
                      // through [LocalRevealShift] instead, because its three pieces — the face,
                      // the bubble and the outline above it — do not all move at the same rate.
                      revealX = if (splitReveal) swipeX else null,
                      // Tells the gestures where the bubble actually is, so a drag out in the empty
                      // half of the row isn't mistaken for a reply.
                      quotedSender = row.quotedSender,
                      forcedAvatar = row.forcedAvatar,
                      forcedTail = row.forcedTail || stickerTail,
                      // The caption row keeps the room under an Apple tail — see [appleCaptionLine].
                      // Not under a tombstone, which has no caption row to keep it.
                      tailReserve = if (bubbleType == BubbleStyle.APPLE && row.message !is Message.Deleted) tailReserve else null,
                      quotedAnchor = quotedAnchor,
                      anchorRevision = anchorRevision,
                      targetIsOutline = row.targetIsOutline,
                      onOutlineLayout = { coordinates ->
                          onOutlinePositioned(
                              coordinates?.takeIf { it.isAttached }
                                  ?.let { Rect(it.positionInRoot(), it.size.toSize()) }
                                  ?.translate(-rowShift.of(row.message.replyTo()?.mine == true), 0f),
                          )
                      },
                      drawConnector = drawConnector,
                      inGroup = isGroup,
                      // A face travels with the time column, so a reading taken while the column
                      // is open comes back already moved — see [AppleBubble]'s `locate`. Everything
                      // a connector is built from is the geometry at rest; the drawing slides
                      // itself afterwards.
                      onAvatarLayout = { coordinates ->
                          onAvatarPositioned(
                              coordinates?.takeIf { it.isAttached }
                                  ?.let { Rect(it.positionInRoot(), it.size.toSize()) }
                                  ?.translate(-rowShift.full(), 0f),
                          )
                      },
                      onBubbleLayout = { coordinates ->
                          onBubblePlaced(coordinates)
                          effectBox[0] = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
                          // What a connector a row below aims at — at rest, with the column's
                          // travel taken back out. The lift above keeps the live box: an overlay
                          // flies from where the bubble actually is, not from where it started.
                          onBubblePositioned(
                              Rect(coordinates.positionInRoot(), coordinates.size.toSize())
                                  .translate(-(rowShift.of(row.message.mine) + swipeX()), 0f),
                          )
                          // And where the field should start its stretch from, and stand while it
                          // does it. Measured only while the field is not in front of it — the
                          // editor never changes the bubble's box, but the bubble is what the
                          // editor's box is derived from, so the reading is taken at rest.
                          if (coordinates.size.width != bubbleWidthPx) bubbleWidthPx = coordinates.size.width
                          if (editMorph.value == 0f) editorFrame.measure(coordinates)
                      },
                      myHandles = myHandles,
                      mentionNames = mentionNames,
                      onReactionsClick = onReactionsClick,
                      onFailureClick = onFailureClick,
                      unsentShown = unsentShown,
                      onToggleUnsent = { unsentShown = !unsentShown },
                      transcription = transcription,
                      onTranscribe = onTranscribe,
                      onPlayed = onPlayed,
                    )
                  }
                  // Stacked over the bubble rather than replacing it: the bubble is what sets the
                  // row's height, and it is what the field folds back into if the edit is discarded.
                  if (editMorph.value > 0f) {
                      InlineMessageEditor(
                          value = draft,
                          onValueChange = { draft = it },
                          colors = activeBubbleColors(row.message.mine),
                          progress = editMorph.value,
                          ink = editInk,
                          startFraction = if (rowWidthPx > 0 && bubbleWidthPx > 0) {
                              (bubbleWidthPx.toFloat() / rowWidthPx).coerceIn(0.2f, 1f)
                          } else {
                              0.7f
                          },
                          mine = row.message.mine,
                          onCancel = onEditCancel,
                          // An emptied field is a discard, not an edit that blanks the message.
                          onConfirm = { if (draft.text.isBlank()) onEditCancel() else onEditCommit(draft.text) },
                          // Sat on the bubble's own box rather than centred in the row. A bubble
                          // with a tail reserves an overhang below itself for it, so the row's
                          // middle is a few dip lower than the bubble's — the field came out
                          // sitting under the message it was replacing rather than over it.
                          // Padding rather than an offset: an offset displaces the field at
                          // placement and tells the row nothing, so a field grown to several lines
                          // hung off the bottom of a row still measured for a one-line bubble and
                          // over the message underneath. As padding the displacement is part of what
                          // the row measures, and the row grows to hold it.
                          modifier = Modifier
                              .align(Alignment.TopCenter)
                              .padding(top = with(rowDensity) { editorFrame.top.toDp() }),
                      )
                  }
                }
              }
            }
            }
            // Outside the settling layer above, exactly as the edit log is — see [UnsentReveal] for
            // why the words a tombstone offers cannot be drawn by the tombstone itself.
            (row.message as? Message.Deleted)?.let { unsent ->
                UnsentReveal(
                    text = unsent.text,
                    attachment = unsent.attachment,
                    shown = unsentShown,
                    mine = unsent.mine,
                    time = unsent.time,
                )
            }
            // The receipt belongs to the bubble above it, so it hangs off that bubble's own
            // trailing edge rather than starting at the row's leading one — `alignSelf: 'flex-end'`
            // on both receipts in the RN source.
            // A failure takes the slot in every style, WhatsApp included: its bubble ticks can only
            // say how far a message got, and this one got nowhere.
            val failure = row.message.error()
            val status = row.message.status() ?: DeliveryStatus.SENT
            // In a group the plain word is not the answer — "Delivered" says nothing about how
            // many of the five people it reached. Null in a one-to-one, where it is.
            val groupLabel = groupReceiptLabel(row.message.receipts(), recipientCount)
            // When the other end actually read it, not when it was sent — the latest read, which in
            // a one-to-one is the only one.
            // For a voice message that has been listened to, the moment it was played instead: that
            // is the later and more telling of the two, and it is what the line then says.
            val readAt = row.message.receipts()?.let { receipts ->
                (if (status == DeliveryStatus.PLAYED) receipts.played.values.maxOrNull() else null)
                    ?: receipts.read.values.maxOrNull()
            }
            // The caption the newest outgoing message wears without being asked.
            val standingReceipt = showReceipt && (bubbleType != BubbleStyle.WHATSAPP || failure != null)
            // One slot per row, whichever of the two lines is in it — never two slots taking turns.
            //
            // Split across an if/else, the composition *replaced* the slot the moment a send handed
            // the receipt over: the caption's slot was disposed and the standing receipt's was born
            // in its place. A slot is born settled, deliberately, so a receipt already on screen
            // when a row scrolls into view doesn't play an arrival — which meant the new message's
            // "Delivered" appeared between two frames while the old message's was still easing out.
            // The two halves of a handover are supposed to run *against* each other. Kept as one
            // slot, `visible` goes false → true and they do.
            //
            // Wrapped around the line itself rather than the full-width row: the slot scales about
            // its own centre, and a full-width one would send the words drifting toward the middle
            // of the screen on the way in.
            // Never under a tombstone. "Delivered" is a fact about a message, and an unsent one is
            // the absence of one — the line said the message had arrived somewhere it had just been
            // taken back from.
            if (row.message !is Message.Deleted) {
            // What the line under this message says whether or not anyone asks it: that the message
            // was rewritten, and how many answers it has drawn. Both are facts about the message
            // rather than about its delivery, so both stand there permanently — a reply count you
            // have to press a message to discover is a reply count nobody finds.
            //
            // WhatsApp is left out of the edit half: its bubble already says so inside itself, and
            // saying it twice under the same message reads as two different edits.
            // Apple's alone. Google says "Edited" inside its own receipt and WhatsApp says it on
            // the bubble, and neither conversation has the room under a message for a second line
            // of chrome — a reply count under a Google bubble simply crowded the ticks.
            val standing = bubbleType == BubbleStyle.APPLE
            val editedLabel = standing && row.message.edited()
            val replyLabel = if (standing) row.replyCount else 0
            // Offered on every style, unlike the other two: an effect is not something a bubble
            // draws differently, it is something that happened, and a message that spent one while
            // you were away has no other way of saying so.
            val replayLabel = if (messageEffect != null) replayEffect else null
            val standingLabels = editedLabel || replyLabel > 0 || replayLabel != null
            // A voice message that has been listened to says so without being asked — "Played" and
            // when — the way it does on an iPhone. A tap on it then puts the line away rather than
            // opening it.
            val playedByDefault = row.message is Message.Audio && row.message.mine && status == DeliveryStatus.PLAYED
            val statusShown = if (standingReceipt) receiptVisible else statusOpen != playedByDefault
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = metaGroupGutter)
                    // Below the caption, so the caption itself stays against its bubble — see
                    // [AppleThreadGap]. Present whether or not the caption is, since what it holds
                    // apart is this message and the next.
                    .padding(
                        bottom = if (bubbleType == BubbleStyle.APPLE && (row.threadEnd || row.forcedTail || stickerTail)) {
                            AppleThreadGap
                        } else {
                            0.dp
                        },
                    )
                    // Inside the gap above, so the gap stays under whichever is taller, the tail or the line.
                    .then(if (bubbleType == BubbleStyle.APPLE) Modifier.appleCaptionLine(tailReserve, slotProgress) else Modifier)
                    // Hung off the bubble above it, so it goes wherever that bubble goes while the
                    // time column is open — a "Delivered" left standing where the message used to
                    // be reads as a caption that has come loose from what it captions.
                    .graphicsLayer { translationX = rowShift.of(row.message.mine) },
                horizontalArrangement = if (row.message.mine) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Nothing in the slot until the bubble above it has stopped moving — see [grown].
                // The slot stays open for as long as there is a standing label in it, so opening a
                // status on a message that already carries one costs the conversation no height:
                // the words arrive beside what is already there rather than under it.
                val ready = grown || receiptHandedOver
                ReceiptSlot(visible = ready && (standingLabels || statusShown), progress = slotProgress) {
                    // Past the tail under a tailed bubble, and for the standing receipt wherever it
                    // is, including the row it is leaving, which has just lost its tail. A line
                    // tapped open under an untailed bubble sits against the edge.
                    StatusLine(
                        mine = row.message.mine,
                        bubbleType = bubbleType,
                        clearTail = standingReceipt || row.isLastInRun || row.forcedTail || stickerTail,
                    ) {
                        // Ahead of the status, not after it: it is the one thing on this line you
                        // can press, and it should not be found by reading past a caption first.
                        replayLabel?.let {
                            MessageReplayLabel(bubbleType = bubbleType, mine = row.message.mine, onClick = it)
                        }
                        // The status takes its room from the *side*, not from above: it opens into
                        // the line the labels are already on and pushes them across, which is the
                        // one movement in the thread that doesn't move the messages around it.
                        // With no standing label beside it the status is all the outer slot holds,
                        // so it stays in for as long as that slot is still shrinking — shut here
                        // too, the leaving row's line vanished on the first frame and the slot
                        // closed around nothing. The outer slot drops it once it is fully shut.
                        // Nothing at all where the style draws its status on the bubble, so the line's
                        // spacing is never spent on an empty slot.
                        val statusHasContent = failure != null || !standingReceipt || bubbleType != BubbleStyle.WHATSAPP
                        StatusSlot(visible = ready && statusHasContent && (statusShown || !standingLabels)) {
                            when {
                                failure != null -> FailedReceipt()
                                !standingReceipt -> TappedStatusLine(
                                    // Every other message keeps the same slot, shut. A tap opens it.
                                    mine = row.message.mine,
                                    bubbleType = bubbleType,
                                    status = status,
                                    time = row.message.time,
                                    groupLabel = groupLabel,
                                    edited = row.message.edited(),
                                    quietly = row.message.deliveredQuietly(),
                                    readAt = readAt,
                                )
                                bubbleType == BubbleStyle.APPLE -> AppleReceipt(
                                    status = status,
                                    time = row.message.time,
                                    groupLabel = groupLabel,
                                    quietly = row.message.deliveredQuietly(),
                                    readAt = readAt,
                                )
                                bubbleType == BubbleStyle.GOOGLE ->
                                    GoogleReceipt(status, row.message.time, groupLabel = groupLabel, edited = row.message.edited())
                                // WhatsApp draws its ticks on the bubble itself.
                                else -> Unit
                            }
                        }
                        MessageMetaLabels(
                            mine = row.message.mine,
                            bubbleType = bubbleType,
                            edited = editedLabel,
                            replyCount = replyLabel,
                            // The word is the way in to the versions behind it, where there are any
                            // kept. Nothing to read back leaves it a plain label.
                            onShowEdits = if (editHistory.isEmpty()) null else ({ historyOpen = !historyOpen }),
                            onOpenThread = onOpenThread,
                        )
                    }
                }
            }
            }
        }
    }

    // The column slides the conversation aside rather than appearing on top of it, so opening a
    // selection reads as one movement instead of the checkboxes landing over the bubbles.
    val checkboxWidth by animateDpAsState(
        targetValue = if (selecting) CheckboxColumn else 0.dp,
        animationSpec = tween(260, easing = ColumnEasing),
        label = "checkboxColumn",
    )

    // The row a search has landed on glows behind the bubble and fades back down, so the eye is
    // told where to look without the message itself changing colour.
    val highlight by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = tween(if (highlighted) 180 else 420),
        label = "searchHighlight",
    )
    val highlightColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxWidth()
            // A slam grows to five times the bubble's size and a loud one shakes past its own row.
            // Rows are drawn in list order, so without this the message playing is drawn *under*
            // every older bubble it grows across — the effect ends up sliced by the conversation
            // it is meant to be happening on top of. Lifted for exactly as long as it plays.
            .zIndex(if (bubbleEffect.playing) 1f else 0f)
            // Only for a row that is actually arriving; every other row claims its height outright,
            // which is what it has always done and what scrolling depends on.
            .then(if (bornArriving) Modifier.arrivalHeight { entrance.value } else Modifier)
            .then(
                if (highlight > 0f) {
                    Modifier.background(highlightColor.copy(alpha = 0.18f * highlight))
                } else {
                    Modifier
                },
            )
            // While a selection is open the whole row is the target — the checkbox, the bubble, and
            // the empty half of the row beside it all tick the same box.
            .then(
                if (selecting) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleSelect,
                    )
                } else {
                    Modifier
                },
            )
            // A lifted message keeps its space in the thread — the overlay is drawing it elsewhere,
            // and the conversation must not reflow underneath it.
            .graphicsLayer {
                val growth = entrance.value
                alpha = growth
                scaleX = ArrivalScale + (1f - ArrivalScale) * growth
                scaleY = ArrivalScale + (1f - ArrivalScale) * growth
                // Out of the corner the message came from: the send button's own corner for your
                // messages, the mirrored one for theirs.
                transformOrigin = TransformOrigin(if (row.message.mine) 1f else 0f, 1f)
            }
            .onGloballyPositioned { coordinates ->
                onPositioned(ThreadRowRect(coordinates.positionInRoot().y, coordinates.size.height.toFloat()))
            },
    ) {
        // One column down the left edge, the same width for every row: a selection is read by
        // running an eye down the ticks, which only works if they line up.
        if (checkboxWidth > 0.dp) {
            // The tick doesn't just turn up with the column: it comes in from the edge and settles,
            // one movement with the conversation stepping aside for it.
            val entry = (checkboxWidth / CheckboxColumn).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(checkboxWidth)
                    // The bubbles are still sliding right while the column opens, so its contents
                    // are clipped to whatever width it has reached rather than spilling over them.
                    .clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                // Laid out at its full size against the column's final width and clipped to
                // whatever has opened so far, rather than squashed into the width of the moment.
                //
                // Without Material's 48dp minimum touch target: the column is a child of the row,
                // so that minimum became the row's minimum too, and opening a selection stretched
                // every short bubble's row — changing the spacing of the whole conversation the
                // moment you picked one message. The row itself is the tap target here anyway.
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier
                            .width(CheckboxColumn)
                            .wrapContentWidth(unbounded = true)
                            .graphicsLayer {
                                alpha = entry
                                scaleX = 0.6f + 0.4f * entry
                                scaleY = 0.6f + 0.4f * entry
                                translationX = -(1f - entry) * CheckboxColumn.toPx() * 0.5f
                            },
                    )
                }
            }
        }

        // Every style now slides its own pieces, each at its own rate: your bubbles and their faces
        // travel with the column, their bubbles only as far as the avatar gutter is wide, so a
        // group squares up against one margin by the end of the drag rather than being shunted
        // sideways whole. See [RevealShift].
        //
        // Nothing is offset at the row level any more. Moving the row carried the face and the
        // sender's name along with the bubble, which is what kept an incoming message standing
        // still — the row only ever moved for your own.
        // Neither side gives up the checkbox's width. Padded, a bubble took its maximum width from a
        // row made narrower by the checkbox column and came out visibly shorter than the same message
        // outside selection. Theirs are moved over by the column at full width; yours stay exactly
        // where they are, since they sit against the far edge and their maximum width already leaves
        // more room than the column needs.
        val slid = if (row.message.mine) {
            Modifier
        } else {
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(checkboxWidth.roundToPx(), 0) }
            }
        }
        Box(modifier = slid) { content() }
        // Parked just off the right edge — the bubbles keep the full viewport width, and this
        // column's own offset is what slides it into view (`marginRight: -REVEAL_WIDTH` in the RN
        // source). Every row has one, not only the ones that move.
        var timeHeightPx by remember { mutableIntStateOf(0) }
        Box(
            modifier = Modifier
                // Hung off the row's top corner and then dropped onto the bubble's own middle,
                // rather than centred in the row: a row is often taller than the message in it, and
                // a time centred on the row sat visibly off the bubble it belongs to.
                .align(Alignment.TopEnd)
                .width(RevealWidth)
                // The same margin the bubbles keep. Centred in a column that ran to the very edge,
                // a longer time — "12:45 AM" — sat visibly closer to the glass than anything else
                // on the row.
                .padding(end = BubbleRowInset)
                .onSizeChanged { timeHeightPx = it.height }
                // Parked by the layout, slid by the layer. The vertical is a real offset — it
                // follows the bubble's middle, which moves only when the row itself changes — but
                // the horizontal is the drag, and an `offset` there re-places the row on every
                // frame of it. Every descendant then re-reports where it is, with its own reveal
                // translation folded in, and the connectors drawn off those numbers pick the travel
                // up a second time. A layer moves the drawing and leaves every measurement where it
                // was taken, which is the whole arrangement [RevealShift] describes.
                .offset { IntOffset(revealWidthPx.roundToInt(), (bubbleCenterY - timeHeightPx / 2f).roundToInt()) }
                // Slid, and nothing else. The column used to fade up with the drag as well, which
                // made the times arrive as grey ghosts and only reach their own colour at the very
                // end of a swipe most people never finish — so the thing the gesture exists to show
                // was the last thing it showed. Parked a full column's width off the row, there is
                // nothing to hide it behind anyway.
                .graphicsLayer { translationX = revealX() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                row.message.time,
                fontSize = 11.sp,
                // Brighter than the usual secondary ink: this is read at a glance, in the moment,
                // against a conversation sliding past under the thumb.
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
