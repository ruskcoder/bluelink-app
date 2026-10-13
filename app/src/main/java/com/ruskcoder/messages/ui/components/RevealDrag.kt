package com.ruskcoder.messages.ui.components

import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import kotlin.math.abs

/**
 * A horizontal drag that takes the gesture outright — the reply swipe, and the time column.
 *
 * Exclusive on purpose. Dragging a bubble aside to answer
 * it is a whole gesture in itself: there is no sense in which you are half replying and half
 * scrolling, so this claims the finger and the list stops moving under it, which is the behaviour a
 * reply swipe should have.
 *
 * It still decides on both axes before claiming. Slop measured on x alone takes any drag that
 * happens to travel far enough sideways, including a scroll that ran a little off true — and rows
 * sit inside the list, so they are offered the gesture first and that scroll simply died. Declining
 * to consume leaves the gesture unclaimed and still being watched, so the scroll it belonged to
 * carries on as though nothing had looked at it.
 */
suspend fun PointerInputScope.detectReplyDrag(
    /**
     * Asked as the finger comes down. False leaves the whole gesture alone — a press that lands on a
     * conversation still moving is catching the scroll, and is never the start of a reply.
     */
    canStart: () -> Boolean = { true },
    onStart: (Offset) -> Unit = {},
    onEnd: () -> Unit = {},
    onDrag: (change: PointerInputChange, delta: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!canStart()) return@awaitEachGesture
        var overSlop = Offset.Zero
        val claimed = awaitTouchSlopOrCancellation(down.id) { change, over ->
            // A gesture heading mostly down the screen belongs to the conversation, not to this.
            if (abs(over.y) > abs(over.x)) return@awaitTouchSlopOrCancellation
            overSlop = over
            change.consume()
        } ?: return@awaitEachGesture

        onStart(down.position)
        // The slop itself is real travel and is handed over rather than thrown away, or the bubble
        // starts a touch-slop behind the finger and never catches up. Sideways only: the vertical
        // wobble that came with it is not a scroll, so a reveal starts with the screen still.
        if (overSlop.x != 0f) onDrag(claimed, Offset(overSlop.x, 0f))
        // Both axes from here on. A reply reads only x; the time column, once it owns the finger,
        // carries the conversation's scroll on y too — see [TimestampReveal.scrollBy].
        drag(claimed.id) { change ->
            onDrag(change, change.positionChange())
            change.consume()
        }
        // Finished or cancelled alike: a swipe that is let go of mid-gesture has to put the bubble
        // back exactly as a completed one does.
        onEnd()
    }
}
