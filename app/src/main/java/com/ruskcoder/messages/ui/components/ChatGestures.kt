package com.ruskcoder.messages.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Below this much change the pinch is still just two fingers resting on the conversation. */
private const val ZOOM_SLOP = 0.04f

/**
 * A two-finger pinch on the conversation, reported as the running multiplier between frames.
 *
 * Handled on the initial pass and consumed once it is clearly a pinch, so the list underneath never
 * sees it: a two-finger drag would otherwise scroll the thread out from under the gesture. A single
 * finger is left entirely alone, which is what keeps ordinary scrolling and every bubble's own
 * gesture working as before.
 *
 * [onZoomEnd] runs when the fingers come off, for a caller that wants to keep the live value in
 * memory and only write the settled one down.
 */
fun Modifier.pinchZoom(onZoom: (Float) -> Unit, onZoomEnd: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        // Waited for on the initial pass so the gesture is seen before the list claims it.
        awaitPointerEvent(PointerEventPass.Initial)
        var accumulated = 1f
        var pinching = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.count { it.pressed }
            if (down == 0) break
            if (down < 2) continue
            val zoom = event.calculateZoom()
            if (zoom == 0f) continue
            accumulated *= zoom
            if (!pinching && abs(accumulated - 1f) > ZOOM_SLOP) pinching = true
            if (pinching) {
                onZoom(zoom)
                event.changes.forEach { it.consume() }
            }
        }
        if (pinching) onZoomEnd()
    }
}

/** How far a finger has to travel up or down the draft before it is reaching for the keyboard. */
private val KeyboardSwipeSlop = 20.dp

/**
 * A vertical flick on the send bar's draft: up brings the keyboard, down puts it away.
 *
 * The same shortcut the thread has for dismissing the keyboard, on the one control that is always
 * under the thumb — reaching for a keyboard that is already there, or getting it out of the way to
 * read what you are answering, without having to aim at the field or at the back gesture.
 *
 * Handled and consumed on the initial pass, before the text field sees it. A field that had already
 * taken the drag would have moved its caret or scrolled its own contents, and the same finger
 * cannot do both — which is why the caller turns this off once the draft is long enough to scroll:
 * past that point the drag belongs to the words, not to the keyboard.
 *
 * Nothing is consumed until the travel is clearly vertical and clearly past [KeyboardSwipeSlop], so
 * a tap still puts the caret where it was tapped; once it does fire, the rest of the gesture is
 * swallowed so the field cannot treat the finger coming up as a tap on top of it.
 */
@Composable
fun Modifier.keyboardSwipe(
    enabled: Boolean = true,
    onOpen: () -> Unit,
    onClose: () -> Unit,
): Modifier {
    // Held rather than keyed on: the send bar recomposes on every character typed, and a detector
    // keyed to the callbacks would be torn down and rebuilt under a finger that is mid-swipe.
    val open by rememberUpdatedState(onOpen)
    val close by rememberUpdatedState(onClose)
    if (!enabled) return this
    return this.pointerInput(Unit) {
        val slop = KeyboardSwipeSlop.toPx()
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var travelX = 0f
            var travelY = 0f
            var fired = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // A second finger is a pinch on the conversation, not a flick on the bar.
                if (event.changes.count { it.pressed } > 1) break
                val change = event.changes.firstOrNull { it.id == first.id } ?: break
                if (!change.pressed) break
                if (fired) {
                    change.consume()
                    continue
                }
                val delta = change.positionChange()
                travelX += delta.x
                travelY += delta.y
                if (abs(travelY) < slop || abs(travelY) < abs(travelX)) continue
                fired = true
                change.consume()
                if (travelY < 0) open() else close()
            }
        }
    }
}
