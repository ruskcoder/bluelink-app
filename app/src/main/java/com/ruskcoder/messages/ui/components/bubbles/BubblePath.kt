package com.ruskcoder.messages.ui.components.bubbles

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * iMessage-style speech bubble outline, ported from the RN app's `src/lib/bubble-path.ts` (itself
 * ported from a Flutter `BubbleBorder`).
 *
 * The source art is a 504x240 rounded body with a little curling tail hanging off the bottom-right
 * corner (drawn down to y=282). Its corners are a squircle of radius 120 — i.e. a full stadium at
 * that height. Rather than uniformly scaling that art (which would fatten the tail and distort the
 * caps), this reproduces the exact bezier data as four corner quarters and stretches the straight
 * edges between them — a nine-slice in both axes.
 *
 * - Corner radius = min(maxRadius, height/2). While the bubble is short enough (one line),
 *   height/2 wins and the top & bottom curves meet with no straight side — a perfect stadium. Once
 *   it's taller, the radius caps at maxRadius and straight vertical sides open up between separate
 *   top and bottom curves.
 * - The corner art (and the tail) scale with that radius, so the tail stays proportional and
 *   overhangs below the box by ~0.35 * radius, just like the art.
 *
 * `isFromMe = false` mirrors it — tail on the bottom-left for received bubbles.
 */

/** How far below the box the tail hangs, as a fraction of the corner radius — from the source art. */
const val TAIL_OVERFLOW_RATIO = 0.35f

fun buildBubblePath(width: Float, height: Float, isFromMe: Boolean, showTail: Boolean, maxRadius: Float): Path {
    val path = Path()
    // Corner radius: full stadium (h/2) until it exceeds maxRadius, then capped.
    val r = minOf(maxRadius, height / 2f)
    // The art's corners are radius 120; scale everything corner-related by this.
    val cs = if (r == 0f) 0f else r / 120f

    // x helpers: left corners hug x=0, right corners hug x=w. y helpers: the art's top half
    // (y 0->120) maps to [0, r]; the bottom half (y 120->282, incl. the tail) maps to
    // [h-r, ...], leaving a straight side of length (h-2r) between the two.
    fun lx(x: Float) = x * cs
    fun rx(x: Float) = width - (504f - x) * cs
    fun ty(y: Float) = y * cs
    fun by(y: Float) = height - r + (y - 120f) * cs

    // Mirror across the vertical centre -> tail on the bottom-left.
    fun mirror(x: Float, y: Float): Offset = if (isFromMe) Offset(x, y) else Offset(width - x, y)
    fun moveTo(x: Float, y: Float) = mirror(x, y).let { path.moveTo(it.x, it.y) }
    fun lineTo(x: Float, y: Float) = mirror(x, y).let { path.lineTo(it.x, it.y) }
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val p1 = mirror(x1, y1)
        val p2 = mirror(x2, y2)
        val p3 = mirror(x3, y3)
        path.cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
    }

    // start at the top of the bottom-left corner (bottom of the left side)
    moveTo(lx(0f), by(120f))
    // bottom-left corner: (0,120) -> (118,240)
    cubicTo(lx(0f), by(186.274f), lx(52.8304f), by(240f), lx(118f), by(240f))

    if (showTail) {
        lineTo(rx(373.819f), by(240f))
        // -- the tail --
        cubicTo(rx(375.888f), by(240f), rx(377.904f), by(240.64f), rx(379.601f), by(241.825f))
        cubicTo(rx(391.166f), by(249.898f), rx(437.795f), by(282f), rx(448f), by(282f))
        cubicTo(rx(450.5f), by(282f), rx(452.288f), by(280.862f), rx(453f), by(280f))
        cubicTo(rx(454.267f), by(278.466f), rx(455.268f), by(274.5f), rx(452.6f), by(272f))
        cubicTo(rx(449.932f), by(269.5f), rx(441f), by(254.637f), rx(441f), by(244f))
        cubicTo(rx(441f), by(236.683f), rx(442.408f), by(227.87f), rx(448.396f), by(222.712f))
        // bottom-right corner: tail rejoin -> rightmost point (w, h-r)
        cubicTo(rx(480.389f), by(195.155f), rx(504f), by(164.251f), rx(504f), by(120f))
    } else {
        // bottom edge (stretches) -> straight into a plain rounded corner that mirrors the
        // bottom-left one, no tail.
        lineTo(rx(386f), by(240f))
        cubicTo(rx(451.17f), by(240f), rx(504f), by(186.274f), rx(504f), by(120f))
    }

    // right side (stretches)
    lineTo(rx(504f), ty(120f))
    // top-right corner: (504,120) -> (386,0)
    cubicTo(rx(504f), ty(53.7258f), rx(451.17f), ty(0f), rx(386f), ty(0f))
    // top edge (stretches)
    lineTo(lx(118f), ty(0f))
    // top-left corner: (118,0) -> (0,120)
    cubicTo(lx(52.8304f), ty(0f), lx(0f), ty(53.7258f), lx(0f), ty(120f))
    // close draws the left side (stretches) back to the start
    path.close()

    return path
}

/**
 * The same outline, solved so the tail hangs *inside* the box instead of below it.
 *
 * [AppleBubbleShape] reproduces the source art, whose tail overhangs the body — right for a fill,
 * since the bubble reserves that overhang as padding underneath itself. It is wrong for a mask: a
 * photo clipped to it has nothing to put in the tail, because the content stops at the box's bottom
 * edge and the tail is drawn past it, so the tail comes out empty.
 *
 * This works backwards instead: given the box, what body height puts the tip of the tail exactly on
 * its bottom edge? The radius is `min(maxRadius, body / 2)`, so there are two cases — a short
 * bubble whose corners are a full stadium, and a tall one whose radius has capped — and the stadium
 * one is tried first.
 */
class AppleFlushBubbleShape(
    private val isFromMe: Boolean,
    private val showTail: Boolean,
    private val maxRadius: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val maxRadiusPx = with(density) { maxRadius.toPx() }
        val overflow = if (showTail) TAIL_OVERFLOW_RATIO else 0f
        val stadium = size.height / (1f + overflow / 2f)
        val body = if (stadium / 2f <= maxRadiusPx) stadium else size.height - overflow * maxRadiusPx
        return Outline.Generic(
            buildBubblePath(size.width, body.coerceAtLeast(1f), isFromMe, showTail, maxRadiusPx),
        )
    }
}

/** A [Shape] wrapper around [buildBubblePath], for `Modifier.background`/`Modifier.clip`. */
class AppleBubbleShape(
    private val isFromMe: Boolean,
    private val showTail: Boolean,
    private val maxRadius: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val maxRadiusPx = with(density) { maxRadius.toPx() }
        return Outline.Generic(buildBubblePath(size.width, size.height, isFromMe, showTail, maxRadiusPx))
    }
}
