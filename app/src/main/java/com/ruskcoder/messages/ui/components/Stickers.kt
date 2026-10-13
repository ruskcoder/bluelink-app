package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.Image
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ruskcoder.messages.data.model.ALL_EMOJI
import com.ruskcoder.messages.data.model.EMOJI_GROUPS
import com.ruskcoder.messages.data.model.StickerPlacement
import com.ruskcoder.messages.data.model.matches
import com.ruskcoder.messages.data.repository.RecentEmoji
import com.ruskcoder.messages.imessage.StickerImages
import kotlin.math.PI
import kotlin.math.atan2

/**
 * How large a sticker is at a scale of 1, on the bubble and in the hand alike.
 *
 * Apple's scale is against the sticker picture's own size in points: a 320-pixel emoji sticker at
 * @3x, about 107. Matching it is what makes a sticker the same size on an iPhone as it is here.
 */
val StickerBaseSize = 107.dp

/** How big a sticker comes up out of the picker — about where an iPhone sets one down. */
private const val LiftedStickerScale = 0.5f

/** How far a sticker may be pinched down or up while it is being carried. */
private const val MinStickerScale = 0.2f
private const val MaxStickerScale = 1.6f

/**
 * Where a sticker with no placement of its own is drawn: the bubble's outer top corner, smaller,
 * the spot an older sticker row that only knew which message it was on can sit without covering it.
 */
private val DefaultPlacement = StickerPlacement(x = 0.9f, y = 0.1f, scale = 0.45f)

/** The stickers laid on each message, by the id of its bubble. Provided by the thread. */
val LocalStickers = compositionLocalOf<Map<String, List<PlacedSticker>>> { emptyMap() }

/**
 * The bubbles a carried sticker can be dropped on. Provided around the conversation only, so a
 * bubble drawn anywhere else — a lifted copy, a preview — is never a place to stick one.
 */
val LocalStickerTargets = compositionLocalOf<StickerDropTargets?> { null }

/**
 * Where every bubble a sticker could land on is on screen, in root coordinates.
 *
 * A plain map, not state: the bubbles rewrite it on every frame of a scroll, and nothing draws off
 * it — it is only read at the moment something is dropped, or to light up what is under the hand.
 */
class StickerDropTargets {
    class Target(val messageId: String, val text: String, val rect: Rect, internal val owner: Any)

    private val targets = HashMap<String, Target>()

    fun place(messageId: String, text: String, rect: Rect, owner: Any) {
        targets[messageId] = Target(messageId, text, rect, owner)
    }

    /** Only the bubble that placed it can take it back, so a copy going away leaves the original. */
    fun remove(messageId: String, owner: Any) {
        if (targets[messageId]?.owner === owner) targets.remove(messageId)
    }

    /** The bubble under [point], the smallest of them if more than one claims it. */
    fun at(point: Offset): Target? =
        targets.values.filter { it.rect.contains(point) }.minByOrNull { it.rect.width * it.rect.height }
}

/** What the carried sticker is doing: in the hand, or on its way out of it. */
enum class StickerDragPhase { IDLE, CARRYING, PLACING, DISCARDING, RETURNING }

/**
 * The sticker in the hand, from the moment it is lifted — out of the picker, or off a bubble it was
 * already on — until it has finished settling wherever it was let go.
 *
 * [position] is the sticker's centre in root coordinates; [rotation] is in degrees, clockwise.
 */
@Stable
class StickerDragState {
    /** The emoji being carried. For a placed sticker being moved, the one it was made from. */
    var emoji by mutableStateOf<String?>(null)
        private set
    /** The picture of a placed sticker being moved, which is what is drawn in the hand for one. */
    var uri by mutableStateOf<String?>(null)
        private set
    /** The row of the placed sticker being moved, hidden on its bubble while it is in the hand. */
    var movingId by mutableStateOf<String?>(null)
        private set
    var phase by mutableStateOf(StickerDragPhase.IDLE)
        internal set
    var position by mutableStateOf(Offset.Zero)
    var scale by mutableFloatStateOf(1f)
    var rotation by mutableFloatStateOf(0f)
    internal var pointer: PointerId? = null
    /** From the finger to the sticker's centre, kept for the whole carry so it never jumps. */
    internal var grab: Offset = Offset.Zero
    /** Bumped on every lift, so the layer plays its pick-up from the start each time. */
    internal var session by mutableIntStateOf(0)
    /** Where it came up from, against [position], and how large it was there — eased into the hand. */
    internal var liftFrom: Offset = Offset.Zero
    internal var liftScale: Float = 1f
    /** A moved sticker's own spot, which it goes back to if it is let go over nothing. */
    internal var home: Offset = Offset.Zero
    internal var homeScale: Float = 1f
    internal var homeRotation: Float = 0f

    /** Whether a finger is carrying it — the drag host takes every finger while this is true. */
    val active: Boolean get() = phase == StickerDragPhase.CARRYING
    /** Whether anything is drawn in the hand at all, settling included. */
    val visible: Boolean get() = phase != StickerDragPhase.IDLE

    /** Lifted out of the picker: [from] is the cell's centre and [fromScale] the glyph's size there. */
    fun begin(emoji: String, pointer: PointerId, finger: Offset, from: Offset, fromScale: Float) {
        this.pointer = pointer
        // Centred on the finger: the sticker is exactly where it will land when the finger lifts.
        grab = Offset.Zero
        position = finger
        liftFrom = from - finger
        liftScale = fromScale / LiftedStickerScale
        scale = LiftedStickerScale
        rotation = 0f
        uri = null
        movingId = null
        this.emoji = emoji
        session++
        phase = StickerDragPhase.CARRYING
    }

    /** Peeled off a bubble it was already on, keeping its size and turn and where the finger held it. */
    fun beginMove(
        sticker: PlacedSticker,
        pointer: PointerId,
        finger: Offset,
        center: Offset,
        scale: Float,
        rotation: Float,
    ) {
        this.pointer = pointer
        grab = center - finger
        position = center
        liftFrom = Offset.Zero
        liftScale = 1f
        home = center
        homeScale = scale
        homeRotation = rotation
        this.scale = scale
        this.rotation = rotation
        emoji = sticker.emoji
        uri = sticker.uri
        movingId = sticker.id
        session++
        phase = StickerDragPhase.CARRYING
    }

    internal fun release(placed: Boolean) {
        pointer = null
        phase = when {
            placed -> StickerDragPhase.PLACING
            movingId != null -> StickerDragPhase.RETURNING
            else -> StickerDragPhase.DISCARDING
        }
    }

    internal fun clear() {
        phase = StickerDragPhase.IDLE
        emoji = null
        uri = null
        movingId = null
        pointer = null
    }
}

/** Everything the screen needs to stick a carried sticker where it was let go. */
class StickerDrop(
    val emoji: String?,
    val uri: String?,
    /** The placed sticker's row, when this was a move rather than a new one out of the picker. */
    val movingId: String?,
    val center: Offset,
    val scale: Float,
    val rotation: Float,
)

/** The screen's carried sticker, for the bubbles — which is where a placed one is picked back up. */
val LocalStickerDrag = compositionLocalOf<StickerDragState?> { null }

/**
 * Takes over every finger on the screen while a sticker is being carried.
 *
 * Watched from the top of the screen, ahead of everything under it, so the finger that lifted the
 * sticker goes on moving it wherever it travels — out of the picker, over the thread — and a
 * second finger put down anywhere pinches and turns it. Nothing underneath sees any of it: the
 * picker does not scroll and the thread does not swipe while a sticker is in the hand.
 *
 * [onDrop] is asked when the carrying finger lifts, and answers whether the sticker stuck: if it did
 * it stamps down where it is, and if not it shrinks away — or goes back to the bubble it came off.
 */
fun Modifier.stickerDragHost(
    state: StickerDragState,
    onDrop: (StickerDrop) -> Boolean,
): Modifier = composed {
    val drop by rememberUpdatedState(onDrop)
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    fun letGo() {
        val placed = drop(StickerDrop(state.emoji, state.uri, state.movingId, state.position, state.scale, state.rotation))
        state.release(placed)
    }
    this
        .onGloballyPositioned { coordinates[0] = it }
        .pointerInput(state) {
            awaitPointerEventScope {
                // The second finger, and where the pair stood on the last event.
                var partner: PointerId? = null
                var lastPair: Pair<Offset, Offset>? = null
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val carried = state.pointer
                    if (!state.active || carried == null) {
                        partner = null
                        lastPair = null
                        continue
                    }
                    val toRoot = { p: Offset -> coordinates[0]?.takeIf { it.isAttached }?.localToRoot(p) ?: p }
                    val primary = event.changes.firstOrNull { it.id == carried }
                    event.changes.forEach { it.consume() }
                    if (primary == null) {
                        // Every finger gone without the carrying one being seen to lift: let it go.
                        if (event.changes.none { it.pressed }) letGo()
                        continue
                    }
                    if (!primary.pressed) {
                        partner = null
                        lastPair = null
                        letGo()
                        continue
                    }
                    state.position = toRoot(primary.position) + state.grab

                    val second = event.changes.firstOrNull { it.id == partner && it.pressed }
                        ?: event.changes.firstOrNull { it.id != carried && it.pressed }
                    if (second == null) {
                        partner = null
                        lastPair = null
                        continue
                    }
                    if (second.id != partner) {
                        partner = second.id
                        lastPair = null
                    }
                    val pair = primary.position to second.position
                    val before = lastPair
                    lastPair = pair
                    if (before != null) {
                        val was = before.second - before.first
                        val now = pair.second - pair.first
                        val wasLength = was.getDistance()
                        if (wasLength > 1f) {
                            state.scale = (state.scale * now.getDistance() / wasLength)
                                .coerceIn(MinStickerScale, MaxStickerScale)
                        }
                        var turn = atan2(now.y, now.x) - atan2(was.y, was.x)
                        if (turn > PI) turn -= (2 * PI).toFloat()
                        if (turn < -PI) turn += (2 * PI).toFloat()
                        state.rotation = normalizeDegrees(state.rotation + Math.toDegrees(turn.toDouble()).toFloat())
                    }
                }
            }
        }
}

private fun normalizeDegrees(degrees: Float): Float {
    var d = degrees % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

/** Degrees, as the radians Apple sends a sticker's rotation in. */
fun stickerRadians(degrees: Float): Float = (normalizeDegrees(degrees) * PI / 180.0).toFloat()

/**
 * Where a sticker dropped at [center] sits on [target], in Apple's terms — the centre as a fraction
 * of the bubble, so it lands on the same spot however wide the bubble is drawn on the other side.
 */
fun stickerPlacementOn(target: Rect, center: Offset, scale: Float, rotationDegrees: Float) = StickerPlacement(
    x = ((center.x - target.left) / target.width).coerceIn(0f, 1f),
    y = ((center.y - target.top) / target.height).coerceIn(0f, 1f),
    scale = scale,
    rotation = stickerRadians(rotationDegrees),
)

/** How much bigger a sticker is while it is held up off the glass than once it is stuck down. */
private const val CarriedBoost = 1.12f

/**
 * The carried sticker, drawn over everything. Brighter over a bubble it could be dropped on, so
 * the hand knows before it lets go whether the sticker will stay.
 *
 * It animates the whole trip: it grows out of the cell it was lifted from into the hand, rides a
 * little large while it is held, and stamps down when it sticks — or shrinks away over nothing, or
 * slides back to its bubble if it was a placed one being moved.
 */
@Composable
fun StickerDragLayer(state: StickerDragState, targets: StickerDropTargets, modifier: Modifier = Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current
    val lift = remember { Animatable(0f) }
    val away = remember { Animatable(0f) }
    val boost by animateFloatAsState(
        targetValue = if (state.phase == StickerDragPhase.CARRYING) CarriedBoost else 1f,
        // Under-damped, so letting go reads as the sticker being pressed down onto the bubble.
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
        label = "stickerBoost",
    )
    LaunchedEffect(state.session) {
        away.snapTo(0f)
        lift.snapTo(0f)
        lift.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow))
    }
    LaunchedEffect(state.phase, state.session) {
        when (state.phase) {
            // Held a moment past the stamp: the sticker's own row is being written in under it, and
            // this is what stands in its place until the bubble draws it.
            StickerDragPhase.PLACING -> {
                kotlinx.coroutines.delay(420)
                state.clear()
            }
            StickerDragPhase.DISCARDING -> {
                away.animateTo(1f, tween(200))
                state.clear()
            }
            StickerDragPhase.RETURNING -> {
                away.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                state.clear()
            }
            else -> Unit
        }
    }
    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        if (!state.visible) return@Box
        val emoji = state.emoji
        val uri = state.uri
        val over = if (state.phase == StickerDragPhase.CARRYING) targets.at(state.position) else null
        val overId = over?.messageId
        LaunchedEffect(overId) { if (overId != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
        val look = Modifier
            .size(StickerBaseSize)
            .graphicsLayer {
                val base = StickerBaseSize.toPx()
                val p = lift.value
                val a = away.value
                val returning = state.phase == StickerDragPhase.RETURNING
                val carriedAt = state.position + state.liftFrom * (1f - p)
                val at = if (returning) lerp(carriedAt, state.home, a) else carriedAt
                val grown = state.scale * (state.liftScale + (1f - state.liftScale) * p) * boost
                val size = when {
                    returning -> grown + (state.homeScale - grown) * a
                    state.phase == StickerDragPhase.DISCARDING -> grown * (1f - a)
                    else -> grown
                }
                translationX = at.x - origin.x - base / 2f
                translationY = at.y - origin.y - base / 2f
                scaleX = size
                scaleY = size
                rotationZ = if (returning) state.rotation + (state.homeRotation - state.rotation) * a else state.rotation
                alpha = when (state.phase) {
                    StickerDragPhase.DISCARDING -> 1f - a
                    StickerDragPhase.CARRYING -> if (over != null) 1f else 0.8f
                    else -> 1f
                }
                shadowElevation = if (state.phase == StickerDragPhase.CARRYING) base * size * 0.06f else 0f
            }
        if (uri != null) {
            AsyncImage(model = uri, contentDescription = null, modifier = look)
        } else if (emoji != null) {
            val bitmap = remember(emoji) { StickerImages.drawEmoji(emoji, 240).asImageBitmap() }
            Image(bitmap = bitmap, contentDescription = null, modifier = look)
        }
    }
}

/**
 * How far the stickers on a bubble reach past its top and bottom edges, in pixels — the room the
 * thread makes for them, so a sticker hung off an edge does not land on the next message.
 */
fun stickerOverhang(stickers: List<PlacedSticker>, heightPx: Float, basePx: Float): Pair<Float, Float> {
    var top = 0f
    var bottom = 0f
    stickers.forEach { sticker ->
        val placement = sticker.placement ?: DefaultPlacement
        val half = basePx * placement.scale.coerceIn(0.1f, 4f) / 2f
        val cy = heightPx * placement.y
        top = maxOf(top, half - cy)
        bottom = maxOf(bottom, cy + half - heightPx)
    }
    return top to bottom
}

/**
 * Which bubbles have had room made above or below them for a sticker, by message id — so the one
 * before such a gap can wear a tail, the way a bubble standing on its own does.
 */
@Stable
class StickerSpacing {
    private val above = mutableStateMapOf<String, Boolean>()
    private val below = mutableStateMapOf<String, Boolean>()

    fun report(messageId: String, top: Boolean, bottom: Boolean) {
        if (above[messageId] != top) above[messageId] = top
        if (below[messageId] != bottom) below[messageId] = bottom
    }

    /** Whether this bubble has ever reported, so one whose stickers are gone can say so. */
    fun known(messageId: String): Boolean = messageId in above

    /** Whether there is a sticker's gap between [messageId] and [nextId], the message after it. */
    fun gapAfter(messageId: String, nextId: String?): Boolean =
        below[messageId] == true || (nextId != null && above[nextId] == true)
}

val LocalStickerSpacing = compositionLocalOf<StickerSpacing?> { null }

/**
 * The stickers on one bubble, laid out in a box the bubble's size.
 *
 * Drawn over the bubble and let spill past its edges, which is where a sticker slapped on a
 * corner is supposed to hang. [shown] shrinks them into their own centres — a lifted message puts
 * its stickers away that way rather than having them blink out. One of our own can be held and
 * peeled back off to be moved.
 */
@Composable
fun StickersOnBubble(
    stickers: List<PlacedSticker>,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    shown: () -> Float = { 1f },
) {
    val drag = LocalStickerDrag.current
    val haptics = LocalHapticFeedback.current
    Box(modifier = modifier.size(width, height)) {
        stickers.forEach { sticker ->
            key(sticker.id) {
                val placement = sticker.placement ?: DefaultPlacement
                val stickerScale = placement.scale.coerceIn(0.1f, 4f)
                val size = StickerBaseSize * stickerScale
                val degrees = Math.toDegrees(placement.rotation.toDouble()).toFloat()
                val uri = sticker.uri
                val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
                // Settles onto the bubble as it arrives rather than just appearing: the second half
                // of the stamp the hand plays as it lets go.
                val arrive = remember { Animatable(0.9f) }
                LaunchedEffect(Unit) {
                    arrive.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
                }
                val movable = drag != null && sticker.mine && sticker.emoji != null
                Box(
                    modifier = Modifier
                        .offset(x = width * placement.x - size / 2, y = height * placement.y - size / 2)
                        .size(size)
                        .onGloballyPositioned { coordinates[0] = it }
                        .graphicsLayer {
                            val s = shown()
                            scaleX = s * arrive.value
                            scaleY = s * arrive.value
                            alpha = if (drag?.movingId == sticker.id) 0f else s.coerceIn(0f, 1f)
                            rotationZ = degrees
                        }
                        .then(
                            if (!movable) Modifier else Modifier.pointerInput(sticker.id) {
                                awaitEachGesture {
                                    // Taken, so the bubble under it does not start a hold of its own:
                                    // held, it is the sticker that is picked up, not the message.
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                                    held.consume()
                                    val box = coordinates[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                                    val carrier = drag ?: return@awaitEachGesture
                                    if (carrier.visible) return@awaitEachGesture
                                    val half = Offset(box.size.width / 2f, box.size.height / 2f)
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    carrier.beginMove(
                                        sticker = sticker,
                                        pointer = held.id,
                                        finger = box.localToRoot(held.position),
                                        center = box.localToRoot(half),
                                        scale = stickerScale,
                                        rotation = degrees,
                                    )
                                }
                            },
                        ),
                ) {
                    if (uri.isNotBlank()) {
                        AsyncImage(model = uri, contentDescription = "Sticker", modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/**
 * One emoji that can be lifted out as a sticker: held, it grows out of its cell into the hand and
 * the screen's [StickerDragState] carries it from there. A tap only says how it is done.
 *
 * Lifting it does not make it a recent — only sticking it on something does. See the screen's drop.
 */
@Composable
private fun StickerCell(glyph: String, drag: StickerDragState, onTap: () -> Unit) {
    val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val tapped by rememberUpdatedState(onTap)
    Box(
        modifier = Modifier
            .size(StickerCellSize)
            .onGloballyPositioned { coordinates[0] = it }
            .pointerInput(glyph) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val held = awaitLongPressOrCancellation(down.id)
                    if (held == null) {
                        // Let go before the press became a hold, and not taken by a scroll: a tap.
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && !up.pressed && !up.isConsumed) tapped()
                        return@awaitEachGesture
                    }
                    if (drag.visible) return@awaitEachGesture
                    val box = coordinates[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    val at = box.localToRoot(held.position)
                    val centre = box.localToRoot(Offset(box.size.width / 2f, box.size.height / 2f))
                    // The glyph's size in the cell against a sticker's, so it grows from exactly
                    // what was under the finger.
                    val fromScale = with(density) { StickerGlyphSize.toPx() / (StickerBaseSize.toPx() * 0.8f) }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    drag.begin(glyph, held.id, at, from = centre, fromScale = fromScale)
                }
            }
            .graphicsLayer { alpha = if (drag.active && drag.movingId == null && drag.emoji == glyph) 0.3f else 1f },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = StickerGlyphSize)
    }
}

private val StickerCellSize = 48.dp
private val StickerGlyphSize = 30.sp
private val SearchPillHeight = 38.dp
/** The band the search pill stands in, which the grid scrolls up under. */
private val SearchBandHeight = SearchPillHeight + 8.dp

/**
 * The "+" card's sticker face: every emoji, to hold and drag onto a message.
 *
 * The search pill at the top does not take typing itself. The card is the keyboard's slot, and a
 * keyboard coming up for it would cover it — so tapping it hands over to [StickerSearchStrip],
 * which sits above the send bar with the keys, and the card comes back filtered by what was typed.
 *
 * Grows the way the full gallery does: scrolled up, it takes height towards the top of the screen
 * before the grid moves at all, and scrolled back down it gives the height back once the grid is at
 * its top. The grid runs up under the search pill, which frosts what passes beneath it.
 */
@Composable
fun StickerPanel(
    query: String,
    onSearch: () -> Unit,
    onClearQuery: () -> Unit,
    drag: StickerDragState,
    surface: Color,
    height: Dp,
    modifier: Modifier = Modifier,
    expansion: Dp = 0.dp,
    maxExpansion: Dp = 0.dp,
    /** Asked to grow (or, negative, shrink) by a height; answers with how much it actually moved. */
    onExpand: (Dp) -> Dp = { 0.dp },
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var recents by remember { mutableStateOf(RecentEmoji.load(context)) }
    // Read again once a sticker has been let go, which is when one that stuck became a recent.
    LaunchedEffect(drag.visible) { if (!drag.visible) recents = RecentEmoji.load(context) }
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(hint) {
        if (hint) {
            kotlinx.coroutines.delay(2200)
            hint = false
        }
    }
    val results = remember(query) { if (query.isBlank()) emptyList() else ALL_EMOJI.filter { it.matches(query) } }

    val latestExpand by rememberUpdatedState(onExpand)
    val room by rememberUpdatedState(maxExpansion - expansion)
    val taken by rememberUpdatedState(expansion)
    fun stretchBy(dy: Float): Float {
        val wanted = with(density) { dy.toDp() }
        val limited = if (wanted < 0.dp) maxOf(wanted, -room) else minOf(wanted, taken.coerceAtLeast(0.dp))
        if (limited == 0.dp) return 0f
        return with(density) { -latestExpand(-limited).toPx() }
    }
    // Height first on the way up, the grid's own scroll first on the way down — the gallery's rule.
    val grow = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0f) Offset(0f, stretchBy(available.y)) else Offset.Zero

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y > 0f) Offset(0f, stretchBy(available.y)) else Offset.Zero
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height + expansion.coerceAtLeast(0.dp))
            .padding(8.dp)
            .frostedFill(RoundedCornerShape(26.dp), tint = surface.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        HeaderedPanel(modifier = Modifier.fillMaxSize(), header = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SearchPillHeight)
                    .clip(RoundedCornerShape(SearchPillHeight / 2))
                    .background(ControlTint)
                    .clickable(onClick = onSearch)
                    .padding(start = 12.dp, end = 4.dp),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    when {
                        hint -> "Hold an emoji, then drag it onto a message"
                        query.isNotBlank() -> query
                        else -> "Search stickers"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (query.isNotBlank() && !hint) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                if (query.isNotBlank()) {
                    IconButton(onClick = onClearQuery, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(StickerCellSize),
                // Held still while a sticker is in the hand; the host is taking every finger anyway.
                userScrollEnabled = !drag.active,
                contentPadding = PaddingValues(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxSize().nestedScroll(grow),
            ) {
                if (query.isNotBlank()) {
                    if (results.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { StickerSectionLabel("Nothing matches \"${query.trim()}\"") }
                    }
                    items(results, key = { "search-${it.glyph}" }) { emoji ->
                        StickerCell(emoji.glyph, drag) { hint = true }
                    }
                } else {
                    if (recents.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { StickerSectionLabel("Recents") }
                        items(recents, key = { "recent-$it" }) { glyph -> StickerCell(glyph, drag) { hint = true } }
                    }
                    EMOJI_GROUPS.forEach { group ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "head-${group.label}") {
                            StickerSectionLabel(group.label)
                        }
                        items(group.emoji, key = { "${group.label}-${it.glyph}" }) { emoji ->
                            StickerCell(emoji.glyph, drag) { hint = true }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A [header] with the scrolling [content] beneath it, a [gap] apart.
 *
 * The content used to run up *under* the header, which frosted whatever passed beneath it: the grid
 * was recorded as it drew and the band replayed it blurred. That is a full-panel capture and a blur
 * every single frame the grid moves, for a strip a few dip tall that nobody is looking at while they
 * scroll. The two now simply sit one above the other and nothing is drawn twice.
 */
@Composable
fun HeaderedPanel(
    modifier: Modifier = Modifier,
    gap: Dp = 8.dp,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        header()
        Spacer(modifier = Modifier.height(gap))
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

/**
 * Sticker search with the keyboard up: the field, and a row of what it finds, sitting above the
 * send bar. Anything in the row can be held and dragged onto a message from right there.
 */
@Composable
fun StickerSearchStrip(
    query: String,
    onQueryChange: (String) -> Unit,
    onDone: () -> Unit,
    drag: StickerDragState,
    surface: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val results = remember(query) {
        if (query.isBlank()) RecentEmoji.load(context).ifEmpty { ALL_EMOJI.take(24).map { it.glyph } }
        else ALL_EMOJI.filter { it.matches(query) }.map { it.glyph }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .frostedFill(RoundedCornerShape(22.dp), tint = surface.copy(alpha = 0.55f))
            .padding(8.dp),
    ) {
        LazyRow(
            userScrollEnabled = !drag.active,
            modifier = Modifier.fillMaxWidth().height(StickerCellSize),
        ) {
            items(results, key = { it }) { glyph -> StickerCell(glyph, drag) {} }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(SearchPillHeight)
                .frostedFill(RoundedCornerShape(SearchPillHeight / 2), blur = ControlBlur, tint = ControlTint)
                .padding(start = 12.dp, end = 4.dp),
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Box(modifier = Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        "Search stickers",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            IconButton(onClick = onDone, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Done searching", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun StickerSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 4.dp),
    )
}
