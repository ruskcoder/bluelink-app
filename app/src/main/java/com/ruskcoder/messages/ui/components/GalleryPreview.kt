package com.ruskcoder.messages.ui.components

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.compose.rememberAsyncImagePainter
import com.ruskcoder.messages.R
import com.ruskcoder.messages.util.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * One thing in the "+" card's galleries: a picture, or a clip and how long it runs.
 *
 * The duration comes from the media store's own row, read in the same query as the id — it is what
 * the badge on the tile shows, and asking the file for it would be a decode per tile.
 */
internal data class GalleryItem(
    val uri: Uri,
    /** Set for a clip, null for a picture. */
    val durationMs: Long? = null,
    /** When the media store took it in, which is the order the gallery runs in and what it is grouped by. */
    val addedMs: Long = 0L,
) {
    val isVideo: Boolean get() = durationMs != null
}

/**
 * Whether a hold on the photos widget began on one of its pictures.
 *
 * The widget's own carry and the picture's preview both time a long press from the same touch, and
 * neither timer can see the other, so the picture raises this on its down and the carry stands aside
 * for any hold that finds it up. A plain field: it is only ever read from inside a gesture.
 */
internal class PhotoHold {
    var claimed = false
}

/**
 * The widget's pick-up, handed to a picture whose preview is dragged off.
 *
 * Holding a picture shows it; dragging away from it (upwards or downwards for a clip, whose sideways
 * drag scrubs it) puts the preview away and picks the widget up instead, the rest of the same drag
 * carrying it — so the widget can still be moved and resized from anywhere on it.
 */
internal class GalleryCarry(
    val hold: PhotoHold,
    val start: () -> Unit,
    val drag: (PointerInputChange, Offset) -> Unit,
    val end: () -> Unit,
)

/** The preview's picture, decoded once at a size the screen can use rather than at full resolution. */
private fun previewRequest(context: android.content.Context, uri: Uri): ImageRequest =
    ImageRequest.Builder(context).data(uri).size(PreviewDecodePx).build()

private const val PreviewDecodePx = 1600

/** How far a held picture's preview can be pinched in. */
private const val PreviewMaxZoom = 5f

/** How far a held picture's preview has to be dragged before it gives the hold to the carry. */
private val HandOffDistance = 56.dp

/** A clip being scrubbed: how far the finger has gone sideways since it started, in pixels. */
internal class Scrub {
    var active by mutableStateOf(false)
    var travel by mutableFloatStateOf(0f)
}

/** Whether a tile is being held open, and the scrub across it if it is a clip. */
internal class GalleryHoldState {
    var previewing by mutableStateOf(false)

    /**
     * A clip's preview left open after the finger came up: it stays until a tap closes it, and the
     * touches that follow are the preview's own rather than the grid's.
     */
    var released by mutableStateOf(false)
    internal val scrub = Scrub()

    /** A second finger pinches the held picture: its zoom, and how far it has been slid about. */
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)

    /**
     * Where the holding finger is, in window coordinates, and where its tile sits in the window.
     *
     * The preview is a window of its own, so a second finger put down on it arrives there as a
     * lone pointer, never as the second of two. The pinch is measured from this finger to that one.
     */
    var finger1: Offset? = null
    var tileOrigin = Offset.Zero

    /** The pinch as last seen by the preview window: finger-to-finger distance and midpoint. */
    var lastSpread = 0f
    var lastMid = Offset.Zero

    /** The preview's own size in pixels, which is what bounds the pan. */
    var frameW = 0f
    var frameH = 0f

    /** The window the preview fills, which the zoomed frame is kept over. */
    var screenW = 0f
    var screenH = 0f

    fun reset() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    /** Applies a pinch's [zoomBy] and [pan], keeping the picture covering its frame. */
    fun transform(zoomBy: Float, pan: Offset) {
        zoom = (zoom * zoomBy).coerceIn(1f, PreviewMaxZoom)
        // Free to travel as far as the frame overhangs the screen, or — while it is still smaller
        // than the screen — as far as it has room to spare, so it can never be lost off an edge.
        val maxX = abs(frameW * zoom - screenW) / 2f
        val maxY = abs(frameH * zoom - screenH) / 2f
        panX = (panX + pan.x).coerceIn(-maxX, maxX)
        panY = (panY + pan.y).coerceIn(-maxY, maxY)
    }

    fun close() {
        previewing = false
        released = false
        scrub.active = false
    }
}

/** The held tile's preview, while there is one — composed beside the tile it belongs to. */
@Composable
internal fun GalleryHoldPreview(item: GalleryItem, state: GalleryHoldState) {
    // Kept composed through its exit so it can animate out rather than vanish.
    val appear = remember { Animatable(0f) }
    var alive by remember { mutableStateOf(false) }
    LaunchedEffect(state.previewing) {
        if (state.previewing) {
            alive = true
            appear.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
        } else if (alive) {
            appear.animateTo(0f, tween(180))
            alive = false
        }
    }
    if (alive) GalleryPreviewPopup(item, state, appear)
}

/**
 * Tap to choose, hold to preview.
 *
 * A picture's preview lasts as long as the hold — released, it goes, all in the one touch. A clip's
 * stays open once the finger comes up, playing with its sound, until a tap closes it.
 *
 * While a clip is held its preview can be scrubbed by dragging sideways. Nothing under the hold
 * scrolls: once the preview is up every move is the preview's.
 */
@Composable
internal fun Modifier.galleryHold(
    item: GalleryItem,
    state: GalleryHoldState,
    enabled: Boolean,
    onClick: () -> Unit,
    carry: GalleryCarry?,
    /** Told when a preview opens or closes, so the grid can leave a pinch to it. */
    onHoldChange: (Boolean) -> Unit = {},
): Modifier {
    val scrub = state.scrub
    val haptics = LocalHapticFeedback.current
    val currentClick by rememberUpdatedState(onClick)
    val currentCarry by rememberUpdatedState(carry)
    val context = LocalContext.current
    LaunchedEffect(state.previewing) { onHoldChange(state.previewing) }
    return this.onGloballyPositioned { state.tileOrigin = it.positionInWindow() }.pointerInput(item.uri, enabled) {
        if (!enabled) return@pointerInput
        val handOffPx = HandOffDistance.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Started on the touch, not the hold: the decode runs while the finger is still working
            // out whether it is a hold, so the preview opens onto a picture that is already there.
            SingletonImageLoader.get(context).enqueue(previewRequest(context, item.uri))
            val handOff = currentCarry
            handOff?.hold?.claimed = true
            var handedOff = false
            // Lifted without leaving the preview: a clip's then stays open.
            var keepOpen = false
            var pinched = false
            try {
                val held = awaitLongPressOrCancellation(down.id)
                if (held == null) {
                    // Up before the hold: a tap, unless something under it (a scroll) took the touch.
                    val up = currentEvent.changes.firstOrNull { it.id == down.id }
                    if (up != null && up.changedToUp()) {
                        up.consume()
                        currentClick()
                    }
                    return@awaitEachGesture
                }
                held.consume()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                scrub.active = false
                scrub.travel = 0f
                state.released = false
                state.reset()
                state.previewing = true
                val origin = held.position
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    state.finger1 = if (change.pressed) change.position + state.tileOrigin else null
                    // A second finger pinches the picture instead of anything the first was doing.
                    if (!handedOff && change.pressed && event.changes.count { it.pressed } >= 2) {
                        pinched = true
                        state.transform(event.calculateZoom(), event.calculatePan())
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    if (!change.pressed) {
                        change.consume()
                        keepOpen = !handedOff
                        break
                    }
                    if (handedOff) {
                        handOff?.drag?.invoke(change, change.positionChange())
                        change.consume()
                        continue
                    }
                    change.consume()
                    if (pinched && state.zoom > 1.01f) {
                        // One finger left on a zoomed picture: it slides it about.
                        state.transform(1f, change.positionChange())
                        continue
                    }
                    if (pinched) continue
                    val travel = change.position - origin
                    val leaving = when {
                        handOff == null -> false
                        // Sideways is the scrub; only a mostly-vertical pull hands over.
                        item.isVideo -> abs(travel.y) > handOffPx && abs(travel.y) > abs(travel.x)
                        else -> travel.getDistance() > handOffPx
                    }
                    if (leaving && handOff != null) {
                        state.previewing = false
                        handedOff = true
                        handOff.start()
                        // The whole pull so far, so the widget comes up under the finger.
                        handOff.drag(change, travel)
                        continue
                    }
                    if (item.isVideo) {
                        if (!scrub.active && abs(travel.x) > slop) scrub.active = true
                        if (scrub.active) scrub.travel = travel.x
                    }
                }
            } finally {
                state.finger1 = null
                if (handedOff) handOff?.end?.invoke()
                if (keepOpen) {
                    scrub.active = false
                    state.released = true
                } else {
                    state.close()
                }
                handOff?.hold?.claimed = false
            }
        }
    }
}

/** Anchors the preview to the window's own corner, whatever tile it was held from. */
private object FullWindow : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset.Zero
}

/**
 * The held item, large, over a dimmed screen.
 *
 * Not focusable while held: the touch that opened it is still down on the grid, and the rest of that
 * touch has to keep going there — the release is what closes a picture's. A clip's, left open after
 * the release, becomes focusable so back closes it, and takes the touches that follow itself: a tap
 * closes it, a sideways drag scrubs it.
 */
@Composable
private fun GalleryPreviewPopup(item: GalleryItem, state: GalleryHoldState, appear: Animatable<Float, *>) {
    val scrub = state.scrub
    val released = state.released
    Popup(
        popupPositionProvider = FullWindow,
        onDismissRequest = { state.close() },
        properties = PopupProperties(focusable = released, clippingEnabled = false),
    ) {
        val thumb = rememberAsyncImagePainter(previewRequest(LocalContext.current, item.uri))
        val thumbState by thumb.state.collectAsState()
        val intrinsic = (thumbState as? AsyncImagePainter.State.Success)?.painter?.intrinsicSize
        val ratio = intrinsic
            ?.takeIf { it.width > 0f && it.height > 0f }
            ?.let { it.width / it.height }
            ?: 1f
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f * appear.value.coerceIn(0f, 1f)))
                .onSizeChanged {
                    state.screenW = it.width.toFloat()
                    state.screenH = it.height.toFloat()
                }
                .then(if (released) Modifier.releasedPreviewTouch(state, item.isVideo) else Modifier.secondFingerPinch(state)),
        ) {
            BoxWithConstraints(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .padding(vertical = 96.dp),
            ) {
                Box(
                    modifier = Modifier
                        .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < maxWidth / maxHeight)
                        .onSizeChanged {
                            state.frameW = it.width.toFloat()
                            state.frameH = it.height.toFloat()
                        }
                        // The pinch grows the whole frame — over the dimmed screen, not within the
                        // rounded box — so it is applied here, ahead of the clip.
                        .graphicsLayer {
                            alpha = if (intrinsic != null) appear.value.coerceIn(0f, 1f) else 0f
                            val grow = (0.88f + 0.12f * appear.value) * state.zoom
                            scaleX = grow
                            scaleY = grow
                            translationX = state.panX
                            translationY = state.panY
                        }
                        .clip(RoundedCornerShape(20.dp))
                        // Nothing at all until the picture is in: the empty square it used to show
                        // first was just the backing waiting for a decode.
                        .background(if (intrinsic != null) Color.White.copy(alpha = 0.08f) else Color.Transparent),
                ) {
                    androidx.compose.foundation.Image(
                        painter = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (item.isVideo) PreviewVideo(item, scrub)
                }
            }
        }
    }
}

/**
 * A second finger landing on the preview while the first is still held on the grid beneath it.
 *
 * The two fingers live in different windows, so neither window sees a pinch; this one measures the
 * spread between the held finger (reported by the grid) and its own, and zooms by how that changes.
 */
private fun Modifier.secondFingerPinch(state: GalleryHoldState): Modifier = pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        state.lastSpread = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.pressed } ?: break
            val other = state.finger1 ?: break
            val spread = (change.position - other).getDistance()
            val mid = (change.position + other) / 2f
            if (state.lastSpread > 0f && spread > 0f) {
                state.transform(spread / state.lastSpread, mid - state.lastMid)
            }
            state.lastSpread = spread
            state.lastMid = mid
            change.consume()
        }
    }
}

/**
 * A preview left open: a tap closes it, two fingers zoom it, and one finger either pans it (once
 * zoomed) or scrubs it (a clip, sideways).
 */
private fun Modifier.releasedPreviewTouch(state: GalleryHoldState, scrubbable: Boolean): Modifier =
    pointerInput(state, scrubbable) {
        val slop = viewConfiguration.touchSlop
        val scrub = state.scrub
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            var moved = false
            var pinched = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed == 0) {
                        event.changes.forEach { it.consume() }
                        if (!moved && !pinched) state.close()
                        break
                    }
                    if (pressed >= 2) {
                        pinched = true
                        moved = true
                        scrub.active = false
                        state.transform(event.calculateZoom(), event.calculatePan())
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    val change = event.changes.first { it.pressed }
                    change.consume()
                    if (pinched || state.zoom > 1.01f) {
                        // Zoomed in, the finger moves the picture rather than scrubbing it.
                        if (state.zoom > 1.01f) state.transform(1f, change.positionChange())
                        moved = true
                        continue
                    }
                    val travel = change.position - down.position
                    if (!moved && travel.getDistance() > slop) moved = true
                    if (!scrubbable) continue
                    if (!scrub.active && abs(travel.x) > slop) {
                        scrub.travel = 0f
                        scrub.active = true
                    }
                    if (scrub.active) scrub.travel = travel.x
                }
            } finally {
                scrub.active = false
            }
        }
    }

/**
 * The clip itself, with its sound, looping, and its timeline along the bottom.
 *
 * The thumbnail underneath stays until the first frame is drawn, so the preview never flashes
 * black. A sideways drag across the whole screen's width is the whole clip.
 */
@Composable
private fun PreviewVideo(item: GalleryItem, scrub: Scrub) {
    val context = LocalContext.current
    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            // Takes audio focus, so whatever else is playing ducks or pauses under the clip.
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            setMediaItem(MediaItem.fromUri(item.uri))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    var shown by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                shown = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    val duration = item.durationMs?.takeIf { it > 0 } ?: 1L
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            withFrameNanos { }
            position = player.currentPosition
        }
    }

    // Held still at the start of the scrub, so the drag is measured from where the clip was.
    var scrubFrom by remember { mutableLongStateOf(0L) }
    LaunchedEffect(scrub.active) {
        if (scrub.active) {
            scrubFrom = player.currentPosition
        } else {
            // A scrub let go of before its rest still plays on from where it stopped.
            player.setScrubbingModeEnabled(false)
            player.play()
        }
    }
    val screenWidth = context.resources.displayMetrics.widthPixels.toFloat()
    LaunchedEffect(scrub.travel, scrub.active) {
        if (!scrub.active) return@LaunchedEffect
        // Scrubbing mode trades exactness for keeping up with the finger; it is only for the seeks.
        player.setScrubbingModeEnabled(true)
        player.pause()
        val target = (scrubFrom + (scrub.travel / screenWidth) * duration).toLong().coerceIn(0L, duration)
        player.seekTo(target)
        position = target
        // A finger resting mid-scrub lets the clip play on from there.
        delay(ScrubRest)
        player.setScrubbingModeEnabled(false)
        player.play()
    }

    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(R.layout.video_player_texture, null) as PlayerView).apply {
                this.player = player
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (shown) 1f else 0f },
    )
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(formatDuration(position / 1000f), fontSize = 12.sp, color = Color.White)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White.copy(alpha = 0.3f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Color.White),
                    )
                }
                Text(formatDuration(duration / 1000f), fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

/** How long a scrubbing finger has to rest before the clip plays on from there. */
private const val ScrubRest = 350L

/**
 * A clip's play mark and running time, in the tile's bottom-left corner.
 *
 * Over a short fade rather than a pill, so it reads on a bright frame without boxing the picture in.
 */
@Composable
internal fun VideoBadge(durationMs: Long, modifier: Modifier = Modifier, compact: Boolean = false) {
    val shadow = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(0f, 1f), blurRadius = 3f)
    Box(
        contentAlignment = Alignment.BottomStart,
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = if (compact) 3.dp else 4.dp, end = if (compact) 3.dp else 6.dp, top = 10.dp, bottom = if (compact) 2.dp else 4.dp),
        ) {
            // Compact is the tightly packed grid: no room for the mark, and the time alone wraps.
            if (!compact) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                formatDuration(durationMs / 1000f),
                maxLines = 1,
                softWrap = false,
                style = TextStyle(fontSize = if (compact) 9.sp else 11.sp, color = Color.White, shadow = shadow),
            )
        }
    }
}
