package com.ruskcoder.messages.ui.components

import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.math.abs
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.util.copyMediaToClipboard
import com.ruskcoder.messages.util.saveMediaToGallery
import kotlin.math.min
import kotlinx.coroutines.launch
import android.view.LayoutInflater
import androidx.compose.ui.input.pointer.PointerEventPass
import com.ruskcoder.messages.R

/** What the viewer was opened on. */
sealed class ViewableMedia {
    abstract val uri: String
    abstract val caption: String?

    data class Photo(override val uri: String, override val caption: String?) : ViewableMedia()

    data class Clip(override val uri: String, override val caption: String?) : ViewableMedia()
}

/** The viewer opens on photos and videos only; every other attachment kind has nothing to show. */
fun Attachment.asViewableMedia(): ViewableMedia? = when (this) {
    is Attachment.Image -> uri.takeIf { it.isNotBlank() }?.let { ViewableMedia.Photo(it, caption) }
    is Attachment.Video -> uri.takeIf { it.isNotBlank() }?.let { ViewableMedia.Clip(it, caption) }
    else -> null
}

/** How long the lightbox takes to arrive, and to leave — leaving is the quicker of the two. */
private const val VIEWER_OPEN_MS = 260
private const val VIEWER_CLOSE_MS = 180

/** How much smaller the photo starts, so it reads as coming up out of the conversation. */
private const val VIEWER_START_SCALE = 0.88f

/** Decelerating in, accelerating out. */
private val ViewerOpenEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val ViewerCloseEasing = CubicBezierEasing(0.4f, 0f, 0.9f, 0.4f)

/** How long the controls take to arrive, and how far the picture steps back to make room. */
private const val CHROME_MS = 300

/**
 * How large the picture is with the controls up: a quarter smaller than full screen.
 *
 * The two states are meant to read as two different things — a picture filling the screen, and a
 * picture being *looked at* with tools around it — and the gap between them has to be wide enough
 * to see at a glance.
 */
private const val CHROME_SCALE = 0.75f

/** The viewer's bar: real glass over the picture behind it, rather than a flat black wash. */
private val ChromeBlur = 32.dp
private val ChromeTint = Color.Black.copy(alpha = 0.22f)

/** How out of focus the backdrop copy of the picture is, and how much of it shows through. */
private val BackdropBlur = 44.dp
private const val BACKDROP_ALPHA = 0.5f

/** The most the backdrop is ever decoded at; anything already in memory at least this big is used as is. */
private const val BACKDROP_DECODE_PX = 256

/** How far across the screen a picture has to be dragged before letting go moves the stack along. */
private const val SwipeCommit = 0.22f

/** What is left of the drag once there is no picture that way to go to. */
private const val SwipeResistance = 0.22f

private val SwipeSettle = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
private val SwipeLeave = tween<Float>(170, easing = ViewerCloseEasing)
private val SwipeArrive = tween<Float>(230, easing = ViewerOpenEasing)

/**
 * The sideways move between the pictures of one group.
 *
 * Owned by [MediaViewer] rather than by the picture being dragged, because the move outlives the
 * picture that started it: the old one slides off, is swapped underneath, and its replacement comes
 * in from the far side. Run from the photograph's own scope that second half belonged to a
 * composable which had by then been handed a different picture, and anything that interrupted it
 * left the whole viewer parked off the side of the screen — which is a black screen with a
 * photograph somewhere beside it.
 *
 * A plain value moved by one job at a time, rather than an `Animatable`: the finger writes to it on
 * the frame the event arrives, and a drag serialised through an `Animatable`'s own mutex could land
 * *behind* the release that followed it and cancel the move half way.
 */
@Stable
private class ViewerSwipe(private val scope: CoroutineScope) {
    /** How far the stack has been moved, in pixels — negative is toward the next picture. */
    var offset by mutableFloatStateOf(0f)
        private set

    private var job: Job? = null

    /** Whether a move is under way, so a picture arriving as part of one does not cut it short. */
    val moving: Boolean get() = job?.isActive == true

    /** The finger, which always wins: whatever was animating is abandoned where it stands. */
    fun drag(to: Float) {
        job?.cancel()
        job = null
        offset = to
    }

    /** Let go of without going far enough, so the picture goes back where it was. */
    fun settle() {
        start { glideTo(0f, SwipeSettle) }
    }

    /** Takes the move: the picture leaves, [step] swaps it, and its replacement follows it in. */
    fun commit(direction: Int, width: Float, step: (Int) -> Unit) {
        start {
            glideTo(-direction * width, SwipeLeave)
            step(direction)
            offset = direction * width
            glideTo(0f, SwipeArrive)
        }
    }

    /**
     * Puts the stack back in the middle for a picture that arrived by some other route.
     *
     * The backstop under all of the above: whatever happens to a move, the next picture to reach
     * the viewer settles it, so there is no path that leaves the screen showing nothing.
     */
    fun rest() {
        if (moving) return
        job?.cancel()
        job = null
        offset = 0f
    }

    private fun start(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    private suspend fun glideTo(target: Float, spec: AnimationSpec<Float>) {
        animate(offset, target, animationSpec = spec) { value, _ -> offset = value }
    }
}

/**
 * The sideways drag that moves a stack of media along.
 *
 * [pass] is the whole difference between the two places this is used. Over a photograph the gesture
 * is innermost and the pinch behind it is Compose's own, so the ordinary main pass is early enough.
 * Over a video the transport controls are a platform `View`, which takes a touch and never hands it
 * back — so there the pointer has to be caught on its way *down*, on the initial pass, and stolen
 * only once the finger has proved it is going sideways. Until then nothing is consumed, and a tap
 * still reaches the control it was aimed at.
 *
 * Every gesture it does not claim is handed straight back: a second finger is a pinch and never a
 * swipe, and a drag that set off downward belongs to whatever is underneath.
 */
private fun Modifier.stepSwipe(
    swipe: ViewerSwipe?,
    onStep: ((Int) -> Unit)?,
    canStep: (Int) -> Boolean,
    pass: PointerEventPass = PointerEventPass.Main,
    /** Whether the media is sitting at rest — zoomed in, the finger is moving the picture itself. */
    atRest: () -> Boolean = { true },
): Modifier = this.pointerInput(swipe, onStep, pass) {
    if (swipe == null || onStep == null) return@pointerInput
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
        if (!atRest()) return@awaitEachGesture
        val width = size.width.toFloat()
        var claimed = false
        var travelled = 0f
        while (true) {
            val event = awaitPointerEvent(pass)
            if (event.changes.count { it.pressed } > 1) {
                if (claimed) swipe.settle()
                return@awaitEachGesture
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (!claimed) {
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
                if (abs(dx) <= slop) continue
                claimed = true
            }
            travelled += change.position.x - change.previousPosition.x
            // Past the end of the stack the picture still gives, but only a little: the wall has to
            // be felt rather than hit.
            val towards = if (travelled < 0f) 1 else -1
            swipe.drag(if (canStep(towards)) travelled else travelled * SwipeResistance)
            change.consume()
        }
        if (!claimed) return@awaitEachGesture
        val direction = if (swipe.offset < 0f) 1 else -1
        if (abs(swipe.offset) > width * SwipeCommit && canStep(direction)) {
            swipe.commit(direction, width, onStep)
        } else {
            swipe.settle()
        }
    }
}

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

/** How far out the editor can zoom: as far past fit as MAX_ZOOM goes in. */
private const val EDIT_MIN_ZOOM = 1f / MAX_ZOOM

/** What a double-tap zooms to, and back from. */
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Which tool is in hand while the picture is being edited.
 *
 * Editing is a place, not an action: you go into it once and the pen and the crop are two things
 * you pick up and put down inside it. [None] is the editor with nothing in hand — the picture pans
 * and zooms as it does anywhere else, and the bar is the shelf the two tools sit on.
 *
 * A tool owns the finger while it is held, because a finger can only mean one thing at a time, and
 * every tool leaves the same two ways: a tick that keeps what it did and a cross that puts back
 * what it found. Both land you back on [None], never somewhere new.
 */
private enum class EditTool { None, Pen, Crop }

/**
 * Full-screen photo/video viewer.
 *
 * A `Dialog` rather than a route because it is a lightbox over the conversation, not a place in the
 * history — the back gesture should close it and leave the thread exactly where it was, which is
 * what a dialog's own dismiss already does.
 */
@Composable
fun MediaViewer(
    media: ViewableMedia,
    onDismiss: () -> Unit,
    onShare: (String) -> Unit,
    /**
     * Where a marked-up copy of this picture should go, or null for a picture that isn't yours to
     * draw on.
     *
     * Only a draft has one. A photograph already in the conversation has been sent — there is
     * nothing left to change about it, and a pen offered over it would be offering to edit history.
     * A picture sitting in the send bar has not gone anywhere yet, so the pen belongs there and
     * what it produces replaces the draft it was opened from.
     */
    onEdited: ((Uri) -> Unit)? = null,
    /**
     * Opens every picture of the group this one was sent with, two to a row. Null for a picture
     * sent on its own, which has no grid button.
     */
    onOpenGrid: (() -> Unit)? = null,
    /**
     * Shows the picture [step] places along in the group this one belongs to; called with -1 and 1.
     *
     * Null for a picture with no stack behind it, which is also what turns the sideways swipe off:
     * there is nothing either side of it to swipe to, and a picture that slides under the finger
     * and springs back is promising a neighbour that does not exist.
     */
    onStep: ((Int) -> Unit)? = null,
    /** Whether there is a picture that far along to go to — the ends of the stack are walls. */
    canStep: (Int) -> Boolean = { false },
    /** Opens a picture already stepped back with its controls up, rather than filling the screen. */
    startWithChrome: Boolean = false,
    /**
     * The pictures one step either side of this one, decoded ahead of the swipe that asks for them.
     * Without it a step only started loading its picture once that picture was already on screen.
     */
    neighbours: List<ViewableMedia> = emptyList(),
) {
    val scope = rememberCoroutineScope()
    /** The sideways move between the pictures of a group, if this one is in one. */
    val swipe = remember(scope) { ViewerSwipe(scope) }
    // Whatever became of the last move, the picture now on screen is in the middle of it. Keyed on
    // the media rather than run once, so a picture that arrives by any route at all — a step that
    // the caller declined, a clip that went away underneath the viewer — finds the stack centred.
    LaunchedEffect(media) { swipe.rest() }
    val context = LocalContext.current
    /** The size the picture is decoded for, which a neighbour has to match to be found in memory. */
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(neighbours, viewport) {
        if (viewport.width <= 0 || viewport.height <= 0) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        neighbours.filterIsInstance<ViewableMedia.Photo>().forEach { photo ->
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(photo.uri)
                    .size(viewport.width, viewport.height)
                    .precision(Precision.INEXACT)
                    .build(),
            )
        }
    }
    /** 0 with the lightbox off the screen, 1 with it open. */
    val opened = remember { Animatable(0f) }
    /** Latched so a second back press or a double tap on the close button can't start two exits. */
    var leaving by remember { mutableStateOf(false) }

    /**
     * Whether the controls are up.
     *
     * A picture opens as a picture and nothing else — no bar, no buttons, the whole screen given
     * over to the thing you tapped. Tapping it again is what asks for the controls, and the picture
     * steps back and out of focus to make room for them. Tapping once more puts it back.
     *
     * A video is not that. Its own transport controls are already on top of it and a tap belongs to
     * them, so there is no gesture left to ask with — the bar is simply up from the start, and the
     * clip is never stepped back behind it.
     */
    val isPhoto = media is ViewableMedia.Photo
    var chromeOpen by remember { mutableStateOf(!isPhoto || startWithChrome) }

    /**
     * Whether the picture is being edited, and with what.
     *
     * Its own mode rather than more things floating over the picture: while it is on, the viewer's
     * bar is gone and the editor's is in its place. Everything about the change is animated,
     * because it is the same picture throughout and cutting between two sets of chrome over it
     * reads as the picture having been replaced.
     */
    var editing by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(EditTool.None) }
    val penMode = editing && tool == EditTool.Pen
    val cropping = editing && tool == EditTool.Crop
    val markup = rememberMarkupState()
    /** Raised while the drawing is being burned in, so neither button can be pressed twice. */
    var saving by remember { mutableStateOf(false) }
    /** The decoded size of the photo on screen, to put the pen slider on the side that suits it. */
    var photoSize by remember(media.uri) { mutableStateOf(IntSize.Zero) }

    /**
     * What the tool in hand would be putting back if you pressed its cross: the crop as it was when
     * you picked it up, and how many strokes were already down.
     *
     * Taken on the way in rather than at the start of the edit, so cancelling a tool restores the
     * picture to the state you took the tool out of. A cross that threw away the whole edit would
     * be a different button depending on how you got to it.
     */
    var cropOnEntry by remember { mutableStateOf(FullCrop) }
    var strokesOnEntry by remember { mutableIntStateOf(0) }

    /**
     * How much of the screen the editor's two bars take, measured rather than assumed — both are
     * built out of a system inset plus whatever their contents come to, and neither number is
     * knowable from here.
     *
     * The picture is held between them while editing. Full-bleed it runs under both, and the top
     * and bottom of it — often the part worth circling, and always the part a crop handle has to be
     * grabbed by — cannot be reached by a finger at all: the bar takes the touch first. Kept from
     * the last time each was on screen, so the room closes on the way out as smoothly as it opened.
     */
    var editBarPx by remember { mutableIntStateOf(0) }
    var palettePx by remember { mutableIntStateOf(0) }
    /** 0 with the picture filling the screen, 1 with it held below the editor's top bar. */
    val editRoom by animateFloatAsState(
        targetValue = if (editing) 1f else 0f,
        animationSpec = tween(CHROME_MS, easing = ViewerOpenEasing),
        label = "editRoom",
    )
    /** The same for the colour tray at the foot, which belongs to the pen and to nothing else. */
    val paletteRoom by animateFloatAsState(
        targetValue = if (penMode) 1f else 0f,
        animationSpec = tween(CHROME_MS, easing = ViewerOpenEasing),
        label = "paletteRoom",
    )
    val canEdit = isPhoto && onEdited != null

    val startEditing = {
        // The picture goes back to filling the screen: the editor wants every pixel it can get, and
        // the stepped-back state exists to make room for a bar that is about to leave anyway.
        chromeOpen = false
        tool = EditTool.None
        editing = true
    }
    val stopEditing = {
        markup.clear()
        tool = EditTool.None
        editing = false
    }
    /** Picks a tool up, remembering what it is about to be allowed to change. */
    val openTool: (EditTool) -> Unit = { next ->
        cropOnEntry = markup.crop
        strokesOnEntry = markup.strokes.size
        tool = next
    }
    /** Keeps what the tool did and puts it down. */
    val acceptTool = { tool = EditTool.None }
    /** Puts back what the tool found and puts it down. */
    val cancelTool = {
        when (tool) {
            EditTool.Crop -> markup.crop = cropOnEntry
            EditTool.Pen -> markup.truncateTo(strokesOnEntry)
            EditTool.None -> Unit
        }
        tool = EditTool.None
    }
    // The stepped-back, blurred-behind state — the picture being *looked at* rather than filling
    // the screen. A tap asks for it, in the viewer and in the editor alike: it is the same picture
    // and the same gesture, and it would be a strange thing to lose on the way into the editor.
    // The crop takes it whether it was asked for or not — a rectangle whose handles sit on the
    // picture's own edges needs those edges somewhere a thumb can get at, and the frame around a
    // photo you are cutting down is exactly the thing you need to see to judge the cut.
    val chromeAnim = animateFloatAsState(
        targetValue = if (isPhoto && (chromeOpen || cropping)) 1f else 0f,
        animationSpec = tween(CHROME_MS, easing = ViewerOpenEasing),
        label = "viewerChrome",
    )
    val chrome by chromeAnim
    // A boolean off the animated value rather than the value itself: read directly, the `if` below
    // would recompose the whole viewer on every frame of the transition to answer a question whose
    // answer changes twice.
    //
    // Skipped outright where `Modifier.blur` does nothing, which is everything before Android 12:
    // an unblurred crop of the picture behind the picture is not a softer version of this effect,
    // it is a different and much worse one.
    // Always on, not only with the controls up: a picture that doesn't fill the screen leaves
    // letterbox bands, and the out-of-focus copy is what fills them.
    val backdropShown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    LaunchedEffect(Unit) { opened.animateTo(1f, tween(VIEWER_OPEN_MS, easing = ViewerOpenEasing)) }

    // What the bar blurs: the picture and the out-of-focus copy behind it, recorded as they
    // paint. Null before Android 12, where [viewerGlass] falls back to a flat film.
    val backdrop = rememberChatBackdrop()

    // Every way out goes through here: the dialog is what actually disappears, so it has to be
    // told to wait until the photo has finished shrinking back toward the conversation. Called
    // straight through, the window vanished on the first frame and there was nothing left to
    // animate.
    /** Set by the grid button: the grid opens once the viewer has finished leaving, not over it. */
    var gridAfterClose by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                opened.animateTo(0f, tween(VIEWER_CLOSE_MS, easing = ViewerCloseEasing))
                onDismiss()
                if (gridAfterClose) onOpenGrid?.invoke()
            }
        }
    }

    /**
     * Burns the edit into the picture, hands it back as the new draft, and leaves.
     *
     * The only button that writes anything. Staying here afterwards would leave the picture you
     * started from on screen as if nothing had happened.
     */
    val commitEdits = {
        if (!saving && markup.hasEdits) {
            saving = true
            scope.launch {
                val edited = flattenMarkup(
                    context,
                    media.uri,
                    markup.strokes.toList(),
                    markup.rotationQuarters,
                    markup.crop,
                )
                saving = false
                if (edited == null) {
                    Toast.makeText(context, "Couldn't save the edit", Toast.LENGTH_SHORT).show()
                } else {
                    onEdited?.invoke(edited)
                    tool = EditTool.None
                    editing = false
                    close()
                }
            }
            Unit
        }
    }

    Dialog(
        // Back puts the tool down — keeping what it did, like its tick — before it leaves the
        // editor, and leaves the editor before it leaves the picture: one gesture, one step.
        onDismissRequest = {
            when {
                tool != EditTool.None -> acceptTool()
                editing -> stopEditing()
                else -> close()
            }
        },
        // `decorFitsSystemWindows = false` is what takes the lightbox under the status bar. Without
        // it the dialog window is laid out inside the system insets, so a "full screen" photo
        // stopped short of the top of the screen and left a band of the conversation showing above
        // it — the one place a lightbox must not leak.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The clock and the battery belong to the app, not to the picture — while the picture is
        // the whole screen. With the controls up it is not: the picture has stepped back, there is
        // chrome around it, and the bar wants the status bar above it rather than a strip of
        // nothing. So the two arrive and leave together.
        HideStatusBar(hidden = !chromeOpen && !editing)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewport = it }
                // The black ground fades on its own, ahead of the photo: the conversation should be
                // showing through by the time the picture has finished getting out of the way.
                .graphicsLayer { alpha = opened.value }
                .background(Color.Black),
        ) {
            // Everything the bar's glass samples is inside here — the picture and the copy behind
            // it, but not the bar itself, which is what stops the blur from eating its own buttons.
            Box(modifier = Modifier.fillMaxSize().chatBackdropSource(backdrop)) {
                // The picture again, out of focus and filling the screen behind itself. It is only
                // there once the controls are, which is the only time the frame is bigger than the
                // picture in it and there is any ground to cover.
                if (backdropShown) {
                    AsyncImage(
                        // The conversation's own small copy, asked for small: it is about to be
                        // blurred past recognition, and a second full-screen decode of the same
                        // picture is what made the backdrop arrive after everything else.
                        model = remember(media.uri) {
                            ImageRequest.Builder(context)
                                .data(media.uri)
                                .memoryCacheKey(thumbnailKey(media.uri))
                                .size(BACKDROP_DECODE_PX)
                                .build()
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = BACKDROP_ALPHA }
                            .blur(BackdropBlur),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val grown = VIEWER_START_SCALE + (1f - VIEWER_START_SCALE) * opened.value
                            // Two scales multiplied rather than picked between: opening while the
                            // controls are already up has to do both at once.
                            val stepped = 1f - (1f - CHROME_SCALE) * chrome
                            scaleX = grown * stepped
                            scaleY = grown * stepped
                            // On the whole content rather than on the photograph, so a clip in the
                            // middle of a stack travels with its neighbours even though the swipe
                            // itself is only ever started on a picture.
                            translationX = swipe.offset
                        },
                ) {
                    when (media) {
                        is ViewableMedia.Photo -> ZoomablePhoto(
                            uri = media.uri,
                            penMode = penMode,
                            markup = markup,
                            rotationQuarters = markup.rotationQuarters,
                            crop = markup.crop,
                            cropping = cropping,
                            onCropChange = { markup.crop = it },
                            topInset = editBarPx * editRoom,
                            bottomInset = palettePx * paletteRoom,
                            // A tap steps the picture back and blurs behind it wherever a tap has
                            // nothing better to mean — looking at it, and in the editor with
                            // nothing in hand. With a tool out the finger belongs to the tool, and
                            // the crop steps back on its own account anyway.
                            tapTogglesChrome = tool == EditTool.None,
                            onTap = { chromeOpen = !chromeOpen },
                            onIntrinsic = { photoSize = it },
                            // Not while there is a tool out: a sideways drag over a picture being
                            // drawn on or cut down belongs to the tool, and stepping to the next
                            // picture mid-edit would throw the edit away.
                            swipe = swipe.takeIf { !editing },
                            onStep = onStep,
                            canStep = canStep,
                        )
                        is ViewableMedia.Clip -> VideoPlayer(
                            uri = media.uri,
                            swipe = swipe,
                            onStep = onStep,
                            canStep = canStep,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = chromeOpen && !editing,
                enter = fadeIn(tween(CHROME_MS)) + slideInVertically(tween(CHROME_MS, easing = ViewerOpenEasing)) { -it },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it },
            ) {
                // A bar of the picture behind it, blurred — the same glass the rest of the app is
                // made of, rather than the flat black wash this used to be. The buttons on it are
                // plain buttons: with the bar itself carrying the material, a disc behind each
                // glyph would be a second surface on top of the one that is already there.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .viewerGlass(backdrop)
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    ViewerAction(Icons.Filled.Close, "Close", onClick = close)
                    if (onOpenGrid != null) {
                        ViewerAction(Icons.Filled.GridView, "All media") {
                            gridAfterClose = true
                            close()
                        }
                    }
                    Box(modifier = Modifier.weight(1f))
                    ViewerAction(Icons.Filled.Download, "Save to gallery") {
                        val saved = saveMediaToGallery(context, media.uri)
                        Toast.makeText(
                            context,
                            if (saved) "Saved to your gallery" else "Couldn't save this one",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    ViewerAction(Icons.Filled.ContentCopy, "Copy") {
                        // Toasted either way. A copy leaves nothing on screen to show it worked, so
                        // silence and failure look identical until the paste somewhere else does
                        // nothing either.
                        val copied = copyMediaToClipboard(context, media.uri)
                        Toast.makeText(
                            context,
                            if (copied) "Copied" else "Couldn't copy this one",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    ViewerAction(Icons.Filled.Share, "Share") { onShare(media.uri) }
                }
            }

            media.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(16.dp),
                ) {
                    Text(caption, color = Color.White, fontWeight = FontWeight.Normal)
                }
            }

            // The way in to the editor, on a draft picture only. In the corner a thumb already
            // rests in, and grown into rather than dropped in: it is the one thing on the screen
            // offering to change the picture rather than to do something with it. What it opens is
            // the editor, not a tool — which pen or rectangle you want is a question for once you
            // are in there, not for the one button that has to stand for all of it.
            AnimatedVisibility(
                visible = canEdit && !editing,
                enter = fadeIn(tween(CHROME_MS)) + scaleIn(tween(CHROME_MS, easing = ViewerOpenEasing), initialScale = 0.6f),
                exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(20.dp),
            ) {
                FloatingActionButton(
                    onClick = startEditing,
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit this picture")
                }
            }

            // The editor's own bar, in the place the viewer's bar has just left.
            //
            // One bar with three faces rather than three bars: the cross is always on the left and
            // the tick always on the right, so the two things you reach for without looking are in
            // the same place whatever is in your hand. What changes between them is only the middle
            // — the shelf of tools when nothing is in hand, and that tool's own controls when one
            // is. A tool never offers itself again while it is already out.
            AnimatedVisibility(
                visible = editing,
                enter = fadeIn(tween(CHROME_MS)) + slideInVertically(tween(CHROME_MS, easing = ViewerOpenEasing)) { -it },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { editBarPx = it.height }
                        .viewerGlass(backdrop)
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    when (tool) {
                        // Nothing in hand: the cross leaves the edit, the tick keeps it, and
                        // between them are the things you can pick up.
                        EditTool.None -> {
                            ViewerAction(Icons.Filled.Close, "Cancel editing") { if (!saving) stopEditing() }
                            Box(modifier = Modifier.weight(1f))
                            // Turns the picture a quarter clockwise, carrying the ink and the crop
                            // with it. The glyph faces the way the turn goes. Not a mode — there is
                            // nothing to come back out of, so it acts and stays where it is.
                            ViewerAction(Icons.Filled.RotateRight, "Rotate right") {
                                if (!saving) markup.rotateClockwise()
                            }
                            ViewerAction(Icons.Filled.Draw, "Draw") { if (!saving) openTool(EditTool.Pen) }
                            ViewerAction(Icons.Filled.Crop, "Crop") { if (!saving) openTool(EditTool.Crop) }
                            // Dimmed with nothing to keep: the one button here that writes a file.
                            Box(modifier = Modifier.graphicsLayer { alpha = if (!markup.hasEdits || saving) 0.35f else 1f }) {
                                ViewerAction(Icons.Filled.Check, "Done", onClick = commitEdits)
                            }
                        }
                        // The pen. Its cross takes back the lines drawn since it came out and
                        // nothing else; its tick puts it down with them kept.
                        EditTool.Pen -> {
                            ViewerAction(Icons.Filled.Close, "Discard these lines", onClick = cancelTool)
                            Box(modifier = Modifier.weight(1f))
                            Box(modifier = Modifier.graphicsLayer { alpha = if (markup.isEmpty) 0.35f else 1f }) {
                                ViewerAction(Icons.AutoMirrored.Filled.Undo, "Undo") { markup.undo() }
                            }
                            ViewerAction(Icons.Filled.Check, "Done drawing", onClick = acceptTool)
                        }
                        // The crop. Rotate stays — turning the picture is half of framing it — and
                        // the replay glyph opens the rectangle back out to the whole picture, which
                        // is the one thing dragging handles cannot easily get you back to.
                        EditTool.Crop -> {
                            ViewerAction(Icons.Filled.Close, "Discard this crop", onClick = cancelTool)
                            Box(modifier = Modifier.weight(1f))
                            ViewerAction(Icons.Filled.RotateRight, "Rotate right") { markup.rotateClockwise() }
                            Box(modifier = Modifier.graphicsLayer { alpha = if (markup.crop == FullCrop) 0.35f else 1f }) {
                                ViewerAction(Icons.Filled.Replay, "Reset crop") { markup.crop = FullCrop }
                            }
                            ViewerAction(Icons.Filled.Check, "Done cropping", onClick = acceptTool)
                        }
                    }
                }
            }

            // The pen's size: down the left edge for a tall photo, along the top (under the bar) for
            // a wide one, so it lies along the picture's short side and stays out of the way.
            val wide = if (photoSize.width > 0 && photoSize.height > 0) {
                (photoSize.width > photoSize.height) != (markup.rotationQuarters % 2 != 0)
            } else {
                false
            }
            AnimatedVisibility(
                visible = penMode,
                enter = fadeIn(tween(CHROME_MS)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.align(if (wide) Alignment.TopCenter else Alignment.CenterStart),
            ) {
                MarkupPenSlider(
                    state = markup,
                    horizontal = wide,
                    modifier = if (wide) {
                        Modifier.padding(top = with(LocalDensity.current) { editBarPx.toDp() } + 4.dp)
                    } else {
                        Modifier.padding(start = 2.dp)
                    },
                )
            }

            // The colours, along the foot of the screen where the hand drawing is already working.
            // Up only with the pen in hand: a tray under any other tool would be offering something
            // the finger can't do until you put that tool down.
            AnimatedVisibility(
                visible = penMode,
                enter = fadeIn(tween(CHROME_MS)) + slideInVertically(tween(CHROME_MS, easing = ViewerOpenEasing)) { it },
                exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                MarkupPalette(
                    state = markup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { palettePx = it.height }
                        .viewerGlass(backdrop)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/**
 * One control in the viewer's bar: a glyph, and nothing behind it but the bar's own glass.
 *
 * White by default; a caller passes [tint] for a control that is a toggle and currently on, so it
 * reads as pressed without a second surface drawn behind it.
 */
@Composable
private fun ViewerAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(horizontal = 2.dp)) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}

/**
 * The viewer bar's material: whatever is under it, blurred, beneath a light film.
 *
 * The same recorded-layer glass the rest of the app is made of, sampling the picture rather than
 * the conversation. Before Android 12 there is no blur to be had — [rememberChatBackdrop] returns
 * null there — so the film carries the bar alone and is darkened to compensate; a sharp bar over a
 * bright photo would leave its buttons unreadable.
 */
@Composable
private fun Modifier.viewerGlass(backdrop: ChatBackdrop?): Modifier =
    if (backdrop == null) {
        this.background(Color.Black.copy(alpha = 0.42f))
    } else {
        this.chatBackdropBlurBehind(backdrop, radius = ChromeBlur).background(ChromeTint)
    }

/**
 * Takes the status bar down for as long as this is composed, and puts it back afterwards.
 *
 * Applied to the *dialog's* window rather than the activity's: that is the window the lightbox is
 * in, and the one whose insets the bar above is laid out against.
 */
@Composable
private fun HideStatusBar(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, hidden) {
        val controller = view.dialogWindow()?.let { WindowInsetsControllerCompat(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Both bars, not only the status bar: with the chrome put away the navigation bar was the
        // one thing left standing over the picture.
        if (hidden) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        // Put back on the way out whatever the conversation behind expects to find.
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** A crop-fit that changes nothing: the whole picture, where it already was. */
private val CropFitIdentity = Triple(1f, 0f, 0f)

/**
 * Where the picture sits once a crop has been put down: the kept rectangle blown up to fill the
 * frame, as a scale and a translation to be applied before the pan and the zoom.
 *
 * A crop that stays a rectangle drawn over the whole picture is a crop you have to remember making.
 * Applying it the moment the rectangle is put down makes the editor show the picture that is going
 * to be sent, so the tick at the end holds no surprises — and, just as importantly, makes it
 * obvious that the crop is still there rather than leaving the full picture on screen looking like
 * the crop was thrown away.
 *
 * Identity while the rectangle is still up: you cannot judge what to cut when the part being cut is
 * already off the screen. Identity, too, for a crop that keeps everything.
 */
private fun cropFit(box: Size, intrinsic: IntSize, quarters: Int, crop: Rect): Triple<Float, Float, Float> {
    if (crop == FullCrop || box.width <= 0f || box.height <= 0f) return CropFitIdentity
    val rr = fittedRotatedImageRect(box, intrinsic, quarters)
    if (rr.width <= 0f || rr.height <= 0f) return CropFitIdentity
    val cr = Rect(
        rr.left + crop.left * rr.width, rr.top + crop.top * rr.height,
        rr.left + crop.right * rr.width, rr.top + crop.bottom * rr.height,
    )
    if (cr.width <= 0f || cr.height <= 0f) return CropFitIdentity
    val k = min(box.width / cr.width, box.height / cr.height)
    return Triple(k, (box.width / 2f - cr.center.x) * k, (box.height / 2f - cr.center.y) * k)
}

/**
 * The same transform, [amount] of the way there — 0 being the whole picture and 1 the crop applied.
 *
 * Cut between the two, accepting a crop makes the picture jump; taken as a fraction it grows into
 * the frame over the same beat the crop's chrome takes to leave.
 */
private fun Triple<Float, Float, Float>.partway(amount: Float): Triple<Float, Float, Float> =
    Triple(1f + (first - 1f) * amount, second * amount, third * amount)

/**
 * Pinch to zoom, drag to pan, double-tap to toggle between fit and [DOUBLE_TAP_ZOOM] — and, with a
 * tool in hand, a surface to draw on or to cut down.
 *
 * Pan is clamped to the scaled image's own overflow, so a zoomed photo cannot be dragged off the
 * screen and left as an empty black rectangle. At fit scale there is no overflow, which is what
 * makes the offset return to centre on the way back out.
 *
 * The zoom is the same zoom in every mode and deliberately not reset between them: you zoom in on
 * the thing you mean to draw around, take the pen out, and it is still there in front of you.
 */
@Composable
private fun ZoomablePhoto(
    uri: String,
    /** Whether the pen is the tool in hand — one finger draws, two still zoom. */
    penMode: Boolean = false,
    markup: MarkupState? = null,
    /** Quarter-turns clockwise to show the picture (and its ink) at. */
    rotationQuarters: Int = 0,
    /** The fraction of the rotated picture the crop keeps; [FullCrop] for all of it. */
    crop: Rect = FullCrop,
    /** Whether the crop rectangle and its handles are up. */
    cropping: Boolean = false,
    /** Called with the new crop as a handle is dragged. */
    onCropChange: (Rect) -> Unit = {},
    /**
     * How much of the top and bottom of the screen is spoken for, in pixels.
     *
     * The picture is fitted into what is left rather than under it, so every part of it can be
     * reached by a finger — a stroke aimed at the top of a photograph that runs under the bar lands
     * on the bar. Nothing is inset while the picture is only being looked at: there is nothing to
     * reach for then, and a lightbox that stops short of the edges of the screen is not a lightbox.
     */
    topInset: Float = 0f,
    bottomInset: Float = 0f,
    /** Whether a tap on the picture is a request for the viewer's controls. */
    tapTogglesChrome: Boolean = true,
    /**
     * Where a sideways drag between the pictures of a group is written, or null where there is no
     * group to move through. See [MediaViewer], which owns it and draws it.
     */
    swipe: ViewerSwipe? = null,
    /** Moves the viewer one picture along; see [MediaViewer]. */
    onStep: ((Int) -> Unit)? = null,
    canStep: (Int) -> Boolean = { false },
    onTap: () -> Unit,
    /** Told the picture's decoded size, so the editor can tell a wide photo from a tall one. */
    onIntrinsic: (IntSize) -> Unit = {},
) {
    val density = LocalDensity.current
    var scale by remember { mutableFloatStateOf(MIN_ZOOM) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // Eased while a double tap sets it, and taken raw while a tool is in hand: a stroke has to land
    // under the finger that drew it, and an animated zoom means the picture is still arriving at a
    // scale the ink was already placed against.
    val animated = animateFloatAsState(scale, label = "zoom")
    val settledScale = if (penMode || cropping) scale else animated.value

    /**
     * The picture's own pixel size, which is the one thing about it neither the layout nor the
     * gesture can work out for itself.
     *
     * Everything drawn is stored as a fraction of the picture, and a fraction of the picture means
     * nothing until you know which part of the black screen the picture actually occupies —
     * `ContentScale.Fit` letterboxes it, and by how much depends on its shape.
     */
    var intrinsic by remember(uri) { mutableStateOf(IntSize.Zero) }

    /**
     * The crop, the turn, and the callback as they are *now*, for the gesture handlers to read.
     *
     * A `pointerInput` body runs for as long as its keys hold, so anything it closes over directly
     * is frozen at the value it had when that body started. For the crop that was the whole bug:
     * every touch after the first began from the rectangle the *first* one had started from, so
     * putting a finger down anywhere threw away everything dragged since and snapped the rectangle
     * back. Adding the crop to the keys is not the fix — that restarts the handler in the middle of
     * the very gesture that is changing it.
     */
    val liveCrop = rememberUpdatedState(crop)
    val liveQuarters = rememberUpdatedState(rotationQuarters)
    val liveOnCropChange = rememberUpdatedState(onCropChange)

    /** 0 with the whole picture on screen, 1 with the crop applied. See [cropFit]. */
    val cropFitAnim = animateFloatAsState(
        targetValue = if (cropping) 0f else 1f,
        animationSpec = tween(CHROME_MS, easing = ViewerOpenEasing),
        label = "cropFit",
    )

    // Putting the rectangle down returns the picture to the middle: a pinch made while cropping
    // would otherwise leave the cropped result zoomed and off-centre.
    LaunchedEffect(cropping) {
        if (!cropping) {
            scale = MIN_ZOOM
            offsetX = 0f
            offsetY = 0f
        }
    }

    // The viewer proper has no zoomed-out state, so putting the pen down returns the picture to fit
    // if it was pushed smaller than that.
    LaunchedEffect(penMode, cropping) {
        if (!penMode && !cropping && scale < MIN_ZOOM) {
            scale = MIN_ZOOM
            offsetX = 0f
            offsetY = 0f
        }
    }

    /** The crop-fit a finger has to be mapped back through, wherever the animation has got to. */
    fun fitNow(box: Size): Triple<Float, Float, Float> =
        cropFit(box, intrinsic, liveQuarters.value, liveCrop.value).partway(cropFitAnim.value)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Ahead of every gesture below, so the box they measure and the box the picture is
            // fitted into are the same box: a finger's position, the zoom's clamp and the
            // letterboxing all have to agree about where the picture is.
            .padding(
                top = with(density) { topInset.toDp() },
                bottom = with(density) { bottomInset.toDp() },
            )
            // A cropped picture is drawn larger than the frame it is being shown through, so the
            // part outside the crop is clipped away. Only then, though: clipping always cut a zoomed
            // picture — and the whole crop editor — to the small stepped-back box, when zooming
            // should be free to use the entire screen.
            .then(if (!cropping && crop != FullCrop) Modifier.clipToBounds() else Modifier)
            // Ahead of the pinch below — last in the chain is innermost, and innermost is offered
            // a pointer first — because the two want the same single finger and only this one can
            // tell whether it wanted the next picture.
            .stepSwipe(
                swipe = swipe.takeIf { !penMode && !cropping },
                onStep = onStep,
                canStep = canStep,
                // Zoomed in the finger is panning around a photograph rather than leaving it.
                atRest = { scale <= MIN_ZOOM },
            )
            .pointerInput(penMode, cropping) {
                if (penMode || cropping) return@pointerInput
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    // How far the image may travel at this scale: half the growth beyond the
                    // viewport, in each direction.
                    val maxX = (size.width * (next - 1f) / 2f).coerceAtLeast(0f)
                    val maxY = (size.height * (next - 1f) / 2f).coerceAtLeast(0f)
                    scale = next
                    offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                    offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                }
            }
            .pointerInput(penMode, cropping, tapTogglesChrome) {
                if (penMode || cropping) return@pointerInput
                detectTapGestures(
                    // A tap on a photo at rest asks for the controls, or puts them away again;
                    // while zoomed in the tap belongs to the image instead, and inside the editor
                    // there are no viewer controls for it to be asking about.
                    onTap = { if (scale <= MIN_ZOOM && tapTogglesChrome) onTap() },
                    onDoubleTap = {
                        if (scale > MIN_ZOOM) {
                            scale = MIN_ZOOM
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = DOUBLE_TAP_ZOOM
                        }
                    },
                )
            }
            // One finger draws, two zoom.
            //
            // Written out rather than assembled from the stock detectors because the two have to be
            // told apart *within* one gesture: a stroke starts the moment a finger lands, and a
            // second finger arriving after it means that was never a stroke at all. The line is
            // abandoned rather than kept, so a pinch whose fingers landed slightly out of step
            // leaves no mark behind it.
            .pointerInput(penMode, markup, intrinsic, rotationQuarters) {
                if (!penMode || markup == null) return@pointerInput
                awaitEachGesture {
                    val box = Size(size.width.toFloat(), size.height.toFloat())
                    val rect = fittedRotatedImageRect(box, intrinsic, rotationQuarters)
                    val (k, tx, ty) = fitNow(box)
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    // Screen to picture: undo the pan, the zoom and the crop's own blow-up, then
                    // measure against the box the picture is actually sitting in.
                    fun locate(at: Offset): Offset {
                        if (rect.width <= 0f || rect.height <= 0f) return Offset.Zero
                        val shift = Offset(offsetX + tx * scale, offsetY + ty * scale)
                        val onImage = centre + (at - centre - shift) / (scale * k)
                        return Offset((onImage.x - rect.left) / rect.width, (onImage.y - rect.top) / rect.height)
                    }

                    val first = awaitFirstDown(requireUnconsumed = false)
                    first.consume()
                    // Constant on the glass at any zoom, so drawing zoomed in draws finer — which
                    // is the only reason to zoom in with a pen in your hand. Fixed at the start of
                    // the stroke: a line that changed width halfway through would be one the pinch
                    // that changed it had already abandoned anyway.
                    val penWidth = MarkupPenWidth.toPx() * markup.penScale / (scale * k) / rect.width.coerceAtLeast(1f)
                    markup.begin(locate(first.position), penWidth)
                    var pinching = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.count { it.pressed }
                        if (down == 0) break
                        if (down > 1) {
                            if (!pinching) {
                                pinching = true
                                markup.cancel()
                            }
                            val next = (scale * event.calculateZoom()).coerceIn(EDIT_MIN_ZOOM, MAX_ZOOM)
                            val pan = event.calculatePan()
                            val maxX = (size.width * (next - 1f) / 2f).coerceAtLeast(0f)
                            val maxY = (size.height * (next - 1f) / 2f).coerceAtLeast(0f)
                            scale = next
                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            event.changes.forEach { it.consume() }
                        } else if (!pinching) {
                            // One finger, and one finger all along: this is a line.
                            val change = event.changes.first { it.pressed }
                            markup.extend(locate(change.position))
                            change.consume()
                        }
                    }
                    if (!pinching) markup.commit()
                }
            }
            // The crop rectangle's own gesture. One finger on a handle drags that edge or corner;
            // one finger inside the rectangle slides the whole thing; two fingers fall through to
            // the same pinch-zoom the rest of the viewer uses, so you can crop into a zoomed-in
            // part of the picture. A finger that lands on none of those does nothing at all, which
            // is the only thing a touch on the letterbox could sensibly mean. Everything is kept in
            // the rotated picture's own fractions, which is what [flattenMarkup] cuts against.
            .pointerInput(cropping, rotationQuarters, intrinsic) {
                if (!cropping) return@pointerInput
                awaitEachGesture {
                    val box = Size(size.width.toFloat(), size.height.toFloat())
                    val rr = fittedRotatedImageRect(box, intrinsic, rotationQuarters)
                    val first = awaitFirstDown(requireUnconsumed = false)
                    if (rr.width <= 0f || rr.height <= 0f) return@awaitEachGesture
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    fun toLocal(p: Offset): Offset {
                        val (k, tx, ty) = fitNow(box)
                        val shift = Offset(offsetX + tx * scale, offsetY + ty * scale)
                        return centre + (p - centre - shift) / (scale * k)
                    }
                    fun cropLocal(c: Rect) = Rect(
                        rr.left + c.left * rr.width, rr.top + c.top * rr.height,
                        rr.left + c.right * rr.width, rr.top + c.bottom * rr.height,
                    )

                    // Read now, not when this handler started — see [liveCrop].
                    val cl = cropLocal(liveCrop.value)
                    val start = toLocal(first.position)
                    // Constant on the glass: these coordinates shrink as you zoom in, and a grab
                    // radius that didn't shrink with them would swallow the whole rectangle.
                    val slop = 36.dp.toPx() / (scale * fitNow(box).first)
                    // 0 TL, 1 TR, 2 BR, 3 BL, 4 top, 5 right, 6 bottom, 7 left; -1 none.
                    val handles = listOf(
                        Offset(cl.left, cl.top), Offset(cl.right, cl.top),
                        Offset(cl.right, cl.bottom), Offset(cl.left, cl.bottom),
                        Offset(cl.center.x, cl.top), Offset(cl.right, cl.center.y),
                        Offset(cl.center.x, cl.bottom), Offset(cl.left, cl.center.y),
                    )
                    val handle = handles.indexOfFirst { (it - start).getDistance() <= slop }
                    val insideCrop = handle < 0 && cl.contains(start)
                    // A touch that grabbed nothing is left unconsumed and ignored: consuming it
                    // would be claiming a drag this handler is not going to perform.
                    if (handle < 0 && !insideCrop) return@awaitEachGesture
                    first.consume()

                    val minExt = 0.06f
                    var working = liveCrop.value
                    var pinching = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.count { it.pressed }
                        if (down == 0) break
                        if (down > 1) {
                            pinching = true
                            val next = (scale * event.calculateZoom()).coerceIn(EDIT_MIN_ZOOM, MAX_ZOOM)
                            val pan = event.calculatePan()
                            val maxX = (size.width * (next - 1f) / 2f).coerceAtLeast(0f)
                            val maxY = (size.height * (next - 1f) / 2f).coerceAtLeast(0f)
                            scale = next
                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (pinching) continue
                        val change = event.changes.firstOrNull { it.pressed } ?: continue
                        val now = toLocal(change.position)
                        val fx = ((now.x - rr.left) / rr.width)
                        val fy = ((now.y - rr.top) / rr.height)
                        working = when (handle) {
                            0 -> Rect(fx.coerceIn(0f, working.right - minExt), fy.coerceIn(0f, working.bottom - minExt), working.right, working.bottom)
                            1 -> Rect(working.left, fy.coerceIn(0f, working.bottom - minExt), fx.coerceIn(working.left + minExt, 1f), working.bottom)
                            2 -> Rect(working.left, working.top, fx.coerceIn(working.left + minExt, 1f), fy.coerceIn(working.top + minExt, 1f))
                            3 -> Rect(fx.coerceIn(0f, working.right - minExt), working.top, working.right, fy.coerceIn(working.top + minExt, 1f))
                            4 -> Rect(working.left, fy.coerceIn(0f, working.bottom - minExt), working.right, working.bottom)
                            5 -> Rect(working.left, working.top, fx.coerceIn(working.left + minExt, 1f), working.bottom)
                            6 -> Rect(working.left, working.top, working.right, fy.coerceIn(working.top + minExt, 1f))
                            7 -> Rect(fx.coerceIn(0f, working.right - minExt), working.top, working.right, working.bottom)
                            else -> {
                                val d = toLocal(change.position) - toLocal(change.previousPosition)
                                val nl = (working.left + d.x / rr.width).coerceIn(0f, 1f - working.width)
                                val nt = (working.top + d.y / rr.height).coerceIn(0f, 1f - working.height)
                                Rect(nl, nt, nl + working.width, nt + working.height)
                            }
                        }
                        liveOnCropChange.value(working)
                        change.consume()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val transform: Modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // The crop's blow-up first, in the picture's own frame, then the pan and the zoom
                // on top of it — folded into one layer so the ink and the rectangle ride on exactly
                // the same transform the photograph does.
                val (k, tx, ty) = cropFit(size, intrinsic, rotationQuarters, crop).partway(cropFitAnim.value)
                scaleX = settledScale * k
                scaleY = settledScale * k
                translationX = tx * settledScale + offsetX
                translationY = ty * settledScale + offsetY
            }
            // Inside the layer, so the clip is the kept rectangle in the picture's own frame and
            // rides the same transform. Clipping only to the box left the cut-away sides showing
            // whenever the crop was a different shape from the screen.
            .drawWithContent {
                if (crop == FullCrop) {
                    drawContent()
                } else {
                    val rr = fittedRotatedImageRect(size, intrinsic, rotationQuarters)
                    val full = Rect(Offset.Zero, size)
                    val kept = Rect(
                        rr.left + crop.left * rr.width, rr.top + crop.top * rr.height,
                        rr.left + crop.right * rr.width, rr.top + crop.bottom * rr.height,
                    )
                    val t = cropFitAnim.value
                    clipRect(
                        full.left + (kept.left - full.left) * t,
                        full.top + (kept.top - full.top) * t,
                        full.right + (kept.right - full.right) * t,
                        full.bottom + (kept.bottom - full.bottom) * t,
                    ) { this@drawWithContent.drawContent() }
                }
            }
        // The picture alone is turned — the ink and the crop rectangle are already stored in the
        // turned picture's coordinates and drawn straight into [fittedRotatedImageRect], so only
        // the photo needs a `rotationZ`. A quarter or three-quarter turn also needs a uniform
        // shrink, because `ContentScale.Fit` fitted it to its unturned shape and the turned
        // bounding box is wider than the screen.
        val rotationLayer = Modifier.graphicsLayer {
            rotationZ = rotationQuarters * 90f
            if (rotationQuarters % 2 != 0 && intrinsic.width > 0 && intrinsic.height > 0) {
                val fitted = fittedImageRect(size, intrinsic)
                if (fitted.width > 0f && fitted.height > 0f) {
                    val fc = min(size.width / fitted.height, size.height / fitted.width)
                    scaleX = fc
                    scaleY = fc
                }
            }
        }
        val context = LocalContext.current
        AsyncImage(
            // The copy the conversation already has in memory stands in until the full-size decode
            // lands, so stepping to a picture shows the picture rather than a black screen.
            model = remember(uri) {
                ImageRequest.Builder(context).data(uri).placeholderMemoryCacheKey(thumbnailKey(uri)).build()
            },
            contentDescription = null,
            contentScale = ContentScale.Fit,
            // The only place the picture's real size is on offer. Read whether or not there is a
            // tool out, so picking one up doesn't have to wait on a decode.
            onSuccess = { state ->
                val decoded = IntSize(state.result.image.width, state.result.image.height)
                if (decoded != intrinsic) intrinsic = decoded
                onIntrinsic(decoded)
            },
            modifier = transform.then(rotationLayer),
        )
        // Over the picture and inside the same transform, so the ink is part of the photograph
        // rather than something laid on the glass in front of it: zoom in and the strokes grow with
        // what they were drawn on, which is the only way a mark stays where it was put.
        if (markup != null && (markup.strokes.any { it.blur } || markup.live?.blur == true)) {
            // A second, blurred copy of the picture cut down to the blur strokes: the strokes are
            // drawn first as a mask, and the copy is composited into them with SrcIn.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val boxPx = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                val radius = with(density) {
                    (fittedRotatedImageRect(boxPx, intrinsic, rotationQuarters).width * MarkupBlurRadius).toDp()
                }
                Box(
                    modifier = transform
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawBlurMask(
                                markup.strokes,
                                markup.live,
                                fittedRotatedImageRect(size, intrinsic, rotationQuarters),
                            )
                            drawIntoCanvas { canvas ->
                                val paint = Paint().apply { blendMode = BlendMode.SrcIn }
                                canvas.saveLayer(Rect(Offset.Zero, size), paint)
                                this@drawWithContent.drawContent()
                                canvas.restore()
                            }
                        },
                ) {
                    AsyncImage(
                        model = remember(uri) { ImageRequest.Builder(context).data(uri).build() },
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(radius, BlurredEdgeTreatment.Unbounded)
                            .then(rotationLayer),
                    )
                }
            }
        }
        if (markup != null && (penMode || !markup.isEmpty)) {
            Canvas(modifier = transform) {
                drawMarkup(
                    markup.strokes,
                    markup.live,
                    fittedRotatedImageRect(size, intrinsic, rotationQuarters),
                )
            }
        }
        // The crop rectangle: everything outside it dimmed, a hairline round it, and a tick at each
        // of the eight handles. In the same transform as the picture so it tracks the zoom.
        if (cropping && markup != null) {
            Canvas(modifier = transform) {
                val rr = fittedRotatedImageRect(size, intrinsic, rotationQuarters)
                if (rr.width <= 0f || rr.height <= 0f) return@Canvas
                val cr = Rect(
                    rr.left + crop.left * rr.width, rr.top + crop.top * rr.height,
                    rr.left + crop.right * rr.width, rr.top + crop.bottom * rr.height,
                )
                val dim = Color.Black.copy(alpha = 0.5f)
                // Four bands around the kept rectangle rather than one rect with a hole punched in.
                drawRect(dim, topLeft = rr.topLeft, size = Size(rr.width, cr.top - rr.top))
                drawRect(dim, topLeft = Offset(rr.left, cr.bottom), size = Size(rr.width, rr.bottom - cr.bottom))
                drawRect(dim, topLeft = Offset(rr.left, cr.top), size = Size(cr.left - rr.left, cr.height))
                drawRect(dim, topLeft = Offset(cr.right, cr.top), size = Size(rr.right - cr.right, cr.height))

                drawRect(
                    Color.White,
                    topLeft = cr.topLeft,
                    size = cr.size,
                    style = Stroke(width = 1.dp.toPx()),
                )
                val tick = 5.dp.toPx()
                listOf(
                    Offset(cr.left, cr.top), Offset(cr.right, cr.top),
                    Offset(cr.right, cr.bottom), Offset(cr.left, cr.bottom),
                    Offset(cr.center.x, cr.top), Offset(cr.right, cr.center.y),
                    Offset(cr.center.x, cr.bottom), Offset(cr.left, cr.center.y),
                ).forEach { drawCircle(Color.White, radius = tick, center = it) }
            }
        }
    }
}

/** ExoPlayer with its stock controls, released with the composable so nothing keeps decoding. */
@Composable
private fun VideoPlayer(
    uri: String,
    /** The stack this clip sits in, so it can be swiped past like any other picture in it. */
    swipe: ViewerSwipe? = null,
    onStep: ((Int) -> Unit)? = null,
    canStep: (Int) -> Boolean = { false },
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    // Two fingers pinch and pan the picture; one finger is left entirely to the player's own
    // controls. Scaled about the middle and held to the growth past the frame, so a zoomed video can
    // be moved to any edge it has grown past rather than having its top pinned to the screen's.
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var panX by remember(uri) { mutableFloatStateOf(0f) }
    var panY by remember(uri) { mutableFloatStateOf(0f) }
    var frame by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { frame = it }
            // Before the player's own controls, which are a platform view and would otherwise take
            // the whole gesture: a clip in the middle of a stack was the one thing in it that could
            // not be swiped past. See [stepSwipe] on why this one needs the initial pass.
            .stepSwipe(
                swipe = swipe,
                onStep = onStep,
                canStep = canStep,
                pass = PointerEventPass.Initial,
                atRest = { zoom <= 1f },
            )
            .pointerInput(uri) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed < 2) continue
                        val next = (zoom * event.calculateZoom()).coerceIn(1f, VIDEO_MAX_ZOOM)
                        val pan = event.calculatePan()
                        val reachX = frame.width * (next - 1f) / 2f
                        val reachY = frame.height * (next - 1f) / 2f
                        zoom = next
                        panX = (panX + pan.x).coerceIn(-reachX, reachX)
                        panY = (panY + pan.y).coerceIn(-reachY, reachY)
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.video_player_texture, null) as PlayerView).apply {
                    this.player = player
                    useController = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            // The picture alone is transformed, not the player: the controls stay where a thumb
            // expects them however far in the video has been zoomed.
            update = { view ->
                view.videoSurfaceView?.apply {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panX
                    translationY = panY
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** How far a video can be pinched in. */
private const val VIDEO_MAX_ZOOM = 5f
