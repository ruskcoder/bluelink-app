package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** How out of focus the conversation goes while one of its messages is being rewritten. */
private val EditBlur = 18.dp
private const val EditDim = 0.42f
private const val EDIT_MS = 200

/**
 * The conversation, out of focus and out of reach, while one of its messages is being rewritten.
 *
 * Two bands rather than one sheet with a hole in it: the row being edited lives *inside* the
 * scrolling list, so it cannot be lifted above a scrim that covers the list. Covering everything
 * above it and everything below it leaves it standing in the gap — sharp, and the only thing on
 * screen still answering a touch — which is the same result reached from the other side.
 *
 * Both bands swallow taps. Rewriting a message is a modal act: the two buttons on the field are the
 * way out of it, and a stray press on a bubble you cannot see should not open a menu over it.
 *
 * [rowTopInRoot] is where the edited row sits in the window, which is how the thread reports it;
 * this measures its own place in the same coordinates and works out the difference, rather than
 * assuming it starts at the top of the window. Before the row has been measured both are zero and
 * the upper band covers the screen alone — the right answer for the frame between an edit beginning
 * and its field being laid out.
 */
@Composable
fun BoxScope.EditScrim(
    editing: Boolean,
    rowTopInRoot: Float,
    rowHeight: Float,
    backdrop: ChatBackdrop?,
) {
    val opacity by animateFloatAsState(if (editing) 1f else 0f, tween(EDIT_MS), label = "editScrim")
    if (opacity <= 0.01f) return

    var originInRoot by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .matchParentSize()
            .onGloballyPositioned { originInRoot = it.positionInRoot().y },
    ) {
        val rowTop = rowTopInRoot - originInRoot
        Band(opacity = opacity, backdrop = backdrop, topPx = 0f, heightPx = rowTop.coerceAtLeast(0f))
        Band(opacity = opacity, backdrop = backdrop, topPx = rowTop + rowHeight, heightPx = Float.MAX_VALUE)
    }
}

/** One horizontal slice of frosted dark, placed by hand at [topPx]. */
@Composable
private fun BoxScope.Band(opacity: Float, backdrop: ChatBackdrop?, topPx: Float, heightPx: Float) {
    if (heightPx <= 0f) return
    Box(
        modifier = Modifier
            .matchParentSize()
            // Laid out by hand: a band is a slice of the screen at a position that moves with the
            // list, which no arrangement of padding expresses. The node keeps the full box so the
            // placement offset means what it says.
            .layout { measurable, constraints ->
                val top = topPx.roundToInt().coerceIn(0, constraints.maxHeight)
                val height = heightPx
                    .coerceAtMost((constraints.maxHeight - top).toFloat())
                    .coerceAtLeast(0f)
                    .roundToInt()
                val placeable = measurable.measure(
                    constraints.copy(minHeight = height, maxHeight = height),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(IntOffset(0, top))
                }
            }
            .graphicsLayer { alpha = opacity }
            .chatBackdropBlur(backdrop, RectangleShape, radius = EditBlur)
            .background(Color.Black.copy(alpha = EditDim))
            // Consumed rather than merely ignored: the conversation underneath is still a
            // conversation, and a bubble does not need to be visible to answer a long press.
            .pointerInput(Unit) { detectTapGestures { } },
    )
}
