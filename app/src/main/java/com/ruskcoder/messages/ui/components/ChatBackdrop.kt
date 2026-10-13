package com.ruskcoder.messages.ui.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A live copy of whatever is drawn behind the send bar, so the Apple composer can blur it — the
 * `BlurView` backdrop the RN source puts under its pill.
 *
 * Compose has no "blur what is behind me" modifier: a blur can only be applied to content a layer
 * draws itself. So the conversation records itself into a [GraphicsLayer] as it paints
 * ([chatBackdropSource]), and the bar replays that same recording, offset so the right region lands
 * under it, inside a layer carrying the blur ([chatBackdropBlur]). The recording is drawn twice but
 * the conversation is only *rendered* once.
 *
 * `RenderEffect` — and so any real blur — is API 31 and up. Below that [rememberChatBackdrop]
 * returns `null` and every consumer falls back to its flat translucent look, rather than showing a
 * sharp copy of the conversation through the bar.
 */
@Stable
class ChatBackdrop internal constructor(internal val layer: GraphicsLayer) {
    /** Where the recorded content sits on screen, so a consumer can work out its own offset into it. */
    internal var originInRoot by mutableStateOf(Offset.Zero)
}

val LocalChatBackdrop = compositionLocalOf<ChatBackdrop?> { null }

/** A backdrop for this screen, or `null` where the platform can't blur. */
@Composable
fun rememberChatBackdrop(enabled: Boolean = true): ChatBackdrop? {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { ChatBackdrop(layer) }
}

/** Records everything this composable draws, so a bar above it can blur it. */
fun Modifier.chatBackdropSource(backdrop: ChatBackdrop?): Modifier {
    if (backdrop == null) return this
    return this
        .onGloballyPositioned { backdrop.originInRoot = it.positionInRoot() }
        .drawWithCache {
            onDrawWithContent {
                // The outer receiver explicitly: `record` swaps in the layer's canvas, so this is
                // the node's own content being drawn *into* the recording.
                backdrop.layer.record { this@onDrawWithContent.drawContent() }
                drawLayer(backdrop.layer)
            }
        }
}

/**
 * Paints the recorded backdrop behind this composable, blurred and clipped to [shape]. A no-op when
 * [backdrop] is null, which leaves whatever background the caller painted itself.
 */
@Composable
fun Modifier.chatBackdropBlur(backdrop: ChatBackdrop?, shape: Shape, radius: Dp = 24.dp): Modifier {
    if (backdrop == null) return this
    var originInRoot by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { originInRoot = it.positionInRoot() }
        .graphicsLayer {
            clip = true
            this.shape = shape
            val radiusPx = radius.toPx()
            renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
        }
        .drawBehind {
            // The recording is in the source's coordinates; sliding it by the gap between the two
            // origins puts the region that is actually behind this bar under this bar.
            translate(
                left = backdrop.originInRoot.x - originInRoot.x,
                top = backdrop.originInRoot.y - originInRoot.y,
            ) {
                drawLayer(backdrop.layer)
            }
        }
}

/**
 * The same blurred backdrop as [chatBackdropBlur], painted strictly *behind* this composable rather
 * than in a layer that also contains it.
 *
 * [chatBackdropBlur] puts its `renderEffect` on the node itself, so everything to the right of it in
 * the chain — the tint, and the node's own children — is drawn inside the blur. That is exactly
 * right for the empty `matchParentSize` box the bars stack behind their content, and exactly wrong
 * for a modifier put on a container: a button's glyph, a sheet's tiles and a search field's text
 * were all being blurred along with the wallpaper behind them, which is why they read as missing.
 *
 * So the blur gets a layer of its own. The recording is replayed into it, offset the same way, and
 * that layer — and only that layer — carries the effect; the caller's content is drawn over the
 * result untouched. Clipped to its own bounds so a frosted pill samples a pill's worth of screen
 * rather than the whole of it.
 */
@Composable
fun Modifier.chatBackdropBlurBehind(backdrop: ChatBackdrop?, radius: Dp = 24.dp): Modifier {
    if (backdrop == null) return this
    val blurred = rememberGraphicsLayer()
    var originInRoot by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { originInRoot = it.positionInRoot() }
        .drawWithCache {
            val radiusPx = radius.toPx()
            onDrawBehind {
                blurred.clip = true
                blurred.renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
                blurred.record {
                    translate(
                        left = backdrop.originInRoot.x - originInRoot.x,
                        top = backdrop.originInRoot.y - originInRoot.y,
                    ) {
                        drawLayer(backdrop.layer)
                    }
                }
                drawLayer(blurred)
            }
        }
}
