package com.ruskcoder.messages.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A picture of the screen, taken as you leave it, for the page that replaces it to frost.
 *
 * A details page is a real destination, so by the time it is drawn the conversation it came from
 * has been torn down — there is nothing left behind the window to blur, which is why the platform's
 * cross-window blur only ever worked while these pages were dialogs, and why a dialog is what broke
 * their menus. So the screen is copied out just before the navigation, and the page frosts the copy
 * instead of frosting a live window.
 *
 * The copy is deliberately tiny: a fifth of the screen in each direction is a sixteenth of a
 * frame's worth of pixels, and blowing it back up to fill the page is itself most of the blur.
 * [FrostedPage] softens what is left of the pixel grid on top of that.
 */
object ScreenSnapshot {
    /** The last screen captured, or null if nothing has been captured or the copy failed. */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    /** How much of the screen's resolution the copy keeps. */
    private const val SCALE = 0.2f

    /** How many frosted pages are composed right now — see [retain]. */
    private var readers = 0

    /** Whether somebody has asked for the copy to go, and it is only being held for its readers. */
    private var stale = false

    /**
     * Drops the copy, or arranges for it to be dropped as soon as nobody is drawing it.
     *
     * The deferral is the whole of it. This is called when a destination that isn't frosted
     * arrives, and "arrives" is a slipperier moment than it sounds: a page that is sliding out
     * still wants its glass, and a back gesture that gets cancelled leaves a page that had already
     * been counted as gone very much still on screen. Cleared eagerly, both of those lost their
     * blur — the second one permanently, since nothing would ever take the picture again.
     */
    fun clear() {
        stale = true
        if (readers == 0) drop()
    }

    /**
     * Registers a page that is drawing the copy, for as long as it is composed.
     *
     * Paired with [release]. Reference-counted rather than a single flag because a frosted page can
     * open another one — the shared-media card leads to search — and the two overlap for as long as
     * the transition between them runs.
     */
    fun retain() {
        readers++
    }

    fun release() {
        readers = (readers - 1).coerceAtLeast(0)
        if (readers == 0 && stale) drop()
    }

    private fun drop() {
        image = null
        stale = false
    }

    /**
     * Copies the window [view] lives in, and hands it to the next page. Suspends for the copy —
     * a frame or two — so callers navigate *after* it lands, otherwise the copy would catch the
     * outgoing transition rather than the screen it is meant to freeze.
     *
     * Leaves the previous snapshot in place if anything goes wrong (no activity window, a surface
     * that is not ready, a secure window), which is no worse than the flat page it falls back to.
     */
    suspend fun capture(view: View) {
        image = copyWindow(view) ?: return
        // A copy just taken is by definition not the stale one somebody asked to be rid of.
        stale = false
    }

    /**
     * The same copy, handed back rather than published.
     *
     * For a surface that wants its own picture of the screen and must not disturb anybody else's —
     * a bottom sheet, which can be opened *from* a page that is already frosting [image] and would
     * otherwise pull the ground out from under it.
     */
    suspend fun copyOf(view: View): ImageBitmap? = copyWindow(view)

    private suspend fun copyWindow(view: View): ImageBitmap? {
        val window = view.activity()?.window ?: return null
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(
            (width * SCALE).roundToInt().coerceAtLeast(1),
            (height * SCALE).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        // PixelCopy reads the window's surface, so the region has to be named in window
        // coordinates rather than the view's own; it scales whatever it reads into the
        // destination bitmap for us.
        val origin = IntArray(2).also(view::getLocationInWindow)
        val source = Rect(origin[0], origin[1], origin[0] + width, origin[1] + height)

        return suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(
                    window,
                    source,
                    bitmap,
                    { result -> continuation.resume(if (result == PixelCopy.SUCCESS) bitmap.asImageBitmap() else null) },
                    Handler(Looper.getMainLooper()),
                )
            } catch (_: IllegalArgumentException) {
                // Thrown when the window has no surface to read yet.
                continuation.resume(null)
            }
        }
    }
}

/** The activity behind a view's context, which may be wrapped several times over by themes. */
private fun View.activity(): Activity? {
    var context: Context? = context
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * Draws [image] behind this composable, aligned to where it was on screen and blurred.
 *
 * The material a window of its own is made of — a bottom sheet, a dialog — where the recorded-layer
 * backdrop the rest of the app uses cannot reach, because nothing behind the window is in this
 * composition to record. A photograph stands in for it.
 *
 * The picture is drawn at window size and slid back by this node's own position, so the region
 * under the panel is the region the panel is standing on; anything else and the glass would show a
 * piece of screen from somewhere else entirely. The blur lives in a layer of its own rather than on
 * this node, so it takes the photograph and nothing else — put on the node, it would take the
 * panel's contents with it.
 *
 * A no-op with no picture to draw, leaving whatever fill the caller painted.
 */
@Composable
fun Modifier.snapshotBlur(image: ImageBitmap?, radius: Dp = 34.dp): Modifier {
    if (image == null) return this
    val layer = rememberGraphicsLayer()
    val window = LocalWindowInfo.current.containerSize
    var originInRoot by remember { mutableStateOf(Offset.Zero) }

    return this
        .onGloballyPositioned { originInRoot = it.positionInRoot() }
        .drawWithCache {
            val radiusPx = radius.toPx()
            onDrawBehind {
                layer.clip = true
                layer.renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
                layer.record {
                    translate(left = -originInRoot.x, top = -originInRoot.y) {
                        drawImage(image = image, dstSize = IntSize(window.width, window.height))
                    }
                }
                drawLayer(layer)
            }
        }
}
