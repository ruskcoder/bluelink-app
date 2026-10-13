package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntOffset
import com.ruskcoder.messages.ui.theme.LocalSettings

/** How much the emoji under the finger grows, and how long it takes to get there and back. */
private const val HOVER_SCALE = 1.7f
private const val HOVER_MS = 200

/**
 * A plain ease-out — CSS's own curve. Off the mark at once and easing all the way into the stop,
 * with no overshoot at the end of it, so the emoji arrives at its size rather than snapping to it.
 * Both directions use it: growing and shrinking are the same motion run either way.
 */
private val HoverEasing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * A row or grid of emoji you can pick from without lifting your finger.
 *
 * The gesture that opens these is a long press, so the finger is already down when they appear —
 * making a separate tap to choose one an odd second act. Sliding onto an emoji and letting go is
 * the whole interaction, and the one under the finger grows so you can see what you are about to
 * pick while your own thumb is covering it.
 *
 * The growth is anchored to the bottom of the slot rather than its middle: the emoji rises out from
 * under the finger into the space above, which is the only space that isn't covered.
 *
 * A plain tap still works — a press and a release inside the same slot is just a very short slide.
 */
@Stable
class EmojiSlide {
    /** Where the finger is, in the container's coordinates; null when nothing is touching. */
    var position by mutableStateOf<Offset?>(null)
        internal set

    /** The container the slots report themselves against. */
    internal var container: LayoutCoordinates? = null

    /**
     * What to do with the emoji the finger comes off on.
     *
     * Held on the state rather than passed to whichever modifier happens to be running, because a
     * pick can be committed from two places now: the row's own pointer input, and the relay that
     * carries a finger which went down on the message *before* this bar existed.
     */
    internal var onPick: ((String) -> Unit)? = null

    /**
     * How far each emoji has grown, keyed by glyph; absent means "at rest".
     *
     * Every slot animates its own, so crossing the row is the one you left shrinking while the one
     * you reached grows — two movements at once, each in its own place. A single shared animation
     * cannot express that: it has one value and one position, so it either teleports between slots
     * or slides along the row, and both were wrong.
     *
     * Written from each slot's animation and read by the overlay, which is why it is snapshot
     * state rather than the plain map the rects live in.
     */
    internal val scales = mutableStateMapOf<String, Float>()

    // A plain map rather than snapshot state: written from layout, and only ever read while
    // resolving a pointer position that is itself state.
    private val slots = mutableMapOf<String, Rect>()

    internal fun place(key: String, rect: Rect) {
        slots[key] = rect
    }

    /** Where the finger is, given in the window's coordinates rather than the container's. */
    internal fun pressFromWindow(at: Offset) {
        val coordinates = container?.takeIf { it.isAttached } ?: return
        position = coordinates.windowToLocal(at)
    }

    /** Picks whatever is under the finger, if anything, and lets go. */
    internal fun commit() {
        val picked = hovered()
        position = null
        if (picked != null) onPick?.invoke(picked)
    }

    /** Where a slot is, for the overlay that redraws it grown. */
    internal fun rectOf(key: String): Rect? = slots[key]

    /** Which emoji the finger is over, or null when it is between them or gone. */
    fun hovered(): String? = hoveredSlot()?.first

    /** That emoji and the box it sits in, in the container's coordinates. */
    fun hoveredSlot(): Pair<String, Rect>? {
        val at = position ?: return null
        return slots.entries.firstOrNull { it.value.contains(at) }?.toPair()
    }
}

/**
 * A slide state, wired to the finger that opened whatever this bar is part of.
 *
 * A press belongs to the pointer-input nodes that were under it when it went down, and a reaction
 * bar that appears *because* of a long press was not there to be hit — so sliding straight from the
 * bubble onto the emoji did nothing until you lifted and pressed again. The menus already solved
 * this: the node that took the long press keeps reporting the finger to [MenuDragRelay] in window
 * coordinates, and anything open reads it as its own. This is the emoji bar reading the same relay,
 * so one gesture reaches the reactions above the message and the menu below it alike.
 */
@Composable
fun rememberEmojiSlide(): EmojiSlide {
    val state = remember { EmojiSlide() }
    val relay = LocalMenuDragRelay.current

    // Where the relay stood when this bar appeared. A bar only answers to a finger handed over
    // *before* it existed, because that is the only finger that opened it.
    val baseline = remember { relay?.state?.seq ?: 0 }
    LaunchedEffect(state, relay) {
        if (relay == null) return@LaunchedEffect
        var touched = false
        snapshotFlow { relay.state }.collect { drag ->
            if (drag.armSeq > baseline) return@collect
            if (!drag.engaged) {
                // Still resting where it long-pressed. Nothing is grown, so letting go here leaves
                // the bar standing rather than reacting with whatever it opened over.
                state.position = null
                if (!drag.down) touched = false
                return@collect
            }
            // Applied either way: on a lift this is where the finger ended, which is the emoji
            // being chosen when the move and the lift arrive in the same frame.
            state.pressFromWindow(drag.at)
            if (drag.down) {
                touched = true
            } else if (touched) {
                touched = false
                state.commit()
            }
        }
    }
    return state
}

/**
 * Tracks the finger across the emoji under it, and picks whatever it is over when it lifts.
 *
 * Put on the container. Pointer events are consumed so a slide across the row is never also read as
 * a scroll by whatever the row is sitting in.
 */
@Composable
fun Modifier.emojiSlide(state: EmojiSlide, onPick: (String) -> Unit): Modifier {
    // Published to the state so the relay above can commit a pick too, without a second copy of
    // this callback threaded through it.
    SideEffect { state.onPick = onPick }
    return this
        .onGloballyPositioned { state.container = it }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                state.position = down.position
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    state.position = change.position
                    if (!change.pressed) break
                    change.consume()
                }
                state.commit()
            }
        }
}

/**
 * Registers one slot's box with the container, and grows it while the finger is over it.
 *
 * [key] is the emoji itself, which is what the pick reports back — the slots are keyed by what they
 * mean rather than by where they happen to sit, so a grid that reflows does not need re-indexing.
 */
@Composable
fun Modifier.emojiSlot(
    state: EmojiSlide,
    key: String,
    /**
     * Whether this slot answers the finger by growing out of the row.
     *
     * True for an emoji, whose whole job under a thumb is to be seen. False for a control that is
     * already a control — the "+" at the end of the bar is a button, and a button that swells to
     * one and three-quarter size under a finger reads as a bug rather than as feedback. It takes
     * the same gesture and reports the same hover; it simply answers with a press instead. See
     * [EmojiSlide.hovered].
     */
    grows: Boolean = true,
): Modifier {
    val hovered = state.hovered() == key

    // One tick as the finger crosses onto each emoji, the way a slider ticks through its stops.
    // Sliding is the whole gesture and your thumb is over the thing it is choosing, so the feedback
    // that it moved at all cannot be visual alone.
    val haptics = LocalHapticFeedback.current
    val enabled = LocalSettings.current.haptics
    LaunchedEffect(hovered) {
        if (hovered && enabled) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    // This slot's own growth, animated in place. Published to the state on every frame so the
    // overlay can redraw it out where nothing is clipping — the bar has to clip its row to its own
    // animating width for the unroll, and a slot that grew inside that clip would be cut off by it.
    val scale = remember { Animatable(1f) }
    LaunchedEffect(hovered, grows) {
        if (!grows) return@LaunchedEffect
        scale.animateTo(if (hovered) HOVER_SCALE else 1f, tween(HOVER_MS, easing = HoverEasing)) {
            state.scales[key] = value
        }
        // The animation's own last frame is not guaranteed to be the target, and an emoji left
        // a thousandth short of 1 would stay hidden in its slot forever.
        if (hovered) state.scales[key] = HOVER_SCALE else state.scales.remove(key)
    }
    // A slot that never grows never hides either — there is no overlay copy of it to hide behind.
    DisposableEffect(grows) { if (!grows) state.scales.remove(key); onDispose { } }
    DisposableEffect(key) { onDispose { state.scales.remove(key) } }

    return this
        .onGloballyPositioned { coordinates ->
            val container = state.container?.takeIf { it.isAttached } ?: return@onGloballyPositioned
            if (coordinates.isAttached) state.place(key, container.localBoundingBoxOf(coordinates))
        }
        // Hidden here for exactly as long as the overlay is drawing it, so it is never drawn twice.
        // Read inside the lambda so the state is observed at draw time: a slot that recomposed on
        // every frame of its neighbour's animation would re-measure the whole row.
        .graphicsLayer { alpha = if ((state.scales[key] ?: 1f) > 1.001f) 0f else 1f }
}

/**
 * The grown emoji, drawn over the bar rather than inside it.
 *
 * Placed by hand at each slot's own measured box, as a sibling of whatever is doing the clipping
 * rather than a child of it, so they can be as large as they like and cross the bar's edges. Each
 * anchors to the bottom of its slot, so it rises out of the row into the space above — the only
 * space a finger holding the bar down is not covering.
 *
 * Plural, deliberately. Crossing from one emoji to the next has two of them in flight at once, the
 * one being left shrinking while the one being reached grows, and each stays exactly where its own
 * slot is: nothing here moves sideways.
 */
@Composable
fun EmojiSlideOverlay(
    state: EmojiSlide,
    fontSize: TextUnit,
    /**
     * How to draw one slot at full size, given its key.
     *
     * A parameter rather than always a glyph, because not every slot in a row *is* one: the "+" at
     * the end of the reaction bar is a disc with an icon in it, and its key is a sentinel that
     * would have been drawn here as the literal text `::more::`. That is the whole reason it used
     * to be excluded from the growth — not any judgement that it should not grow, which left one
     * button in a row of seven behaving unlike its neighbours under the same finger.
     */
    slotContent: @Composable (String) -> Unit = { Text(it, fontSize = fontSize) },
) {
    val density = LocalDensity.current
    // Copied out rather than iterated live: the map is written from the animations this is
    // reading, and a snapshot of it is one frame's worth of truth.
    state.scales.entries.toList().forEach { (emoji, scale) ->
        if (scale <= 1.001f) return@forEach
        val box = state.rectOf(emoji) ?: return@forEach
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
                .size(with(density) { box.width.toDp() }, with(density) { box.height.toDp() })
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
        ) {
            slotContent(emoji)
        }
    }
}

