package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.ruskcoder.messages.data.model.Attachment
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.launch

private fun Attachment.mediaUri(): String = when (this) {
    is Attachment.Image -> uri
    is Attachment.Video -> uri
    else -> ""
}

/** The group's photos and videos as the viewer and the grid page take them, in the order sent. */
fun Attachment.MediaGroup.viewables(): List<ViewableMedia> = items.mapNotNull { it.asViewableMedia() }

/**
 * Where each card stack has been flicked to, keyed by its first picture.
 *
 * Held outside composition for two reasons: a stack scrolled off and back should still show the
 * card you left on top, and a tap reaches the screen through the thread rather than through the
 * stack — so the screen needs somewhere to ask which card that tap landed on.
 */
object MediaStackPositions {
    private val orders = mutableMapOf<String, List<Int>>()

    private fun Attachment.MediaGroup.key(): String = items.joinToString("|") { it.mediaUri() }

    fun order(group: Attachment.MediaGroup): List<Int> =
        orders[group.key()]?.takeIf { it.size == group.items.size } ?: group.items.indices.toList()

    fun save(group: Attachment.MediaGroup, order: List<Int>) {
        orders[group.key()] = order
    }

    /** The message the top card arrived in — what an unsend on the stack retracts. */
    fun topMessageId(group: Attachment.MediaGroup): String? = group.messageIds.getOrNull(order(group).first())

    /** The picture on top of the stack right now, as the viewer opens it. */
    fun top(group: Attachment.MediaGroup): ViewableMedia? {
        val all = group.viewables()
        val uri = group.items.getOrNull(order(group).first())?.mediaUri()
        return all.firstOrNull { it.uri == uri } ?: all.firstOrNull()
    }
}

/**
 * Which tile of a grid bubble a finger last went down on.
 *
 * A tap on the bubble reaches the screen through the thread, which knows the message and not where
 * in it the finger was — so each tile reports its press here, without consuming it (the thread
 * still needs the same press for long-press and double-tap), and the screen asks afterwards which
 * tile the tap landed on. Held only briefly: a press that never became a tap must not decide what
 * some later tap opens.
 */
object MediaTilePresses {
    /** Stands for the "+N" tile, which opens the whole group rather than one picture. */
    const val OVERFLOW = -1

    private const val FRESH_MS = 1_500L
    private var key: String? = null
    private var index = 0
    private var at = 0L

    private fun Attachment.MediaGroup.pressKey(): String = items.joinToString("|") { it.mediaUri() }

    internal fun press(group: Attachment.MediaGroup, tile: Int) {
        key = group.pressKey()
        index = tile
        at = android.os.SystemClock.uptimeMillis()
    }

    /** The tile just pressed on [group] — an index into its items, or [OVERFLOW] — and forgets it. */
    fun take(group: Attachment.MediaGroup): Int? {
        val fresh = key == group.pressKey() && android.os.SystemClock.uptimeMillis() - at <= FRESH_MS
        key = null
        return if (fresh) index else null
    }
}

private val GroupWidth = 236.dp
private val TileGap = 3.dp
private val TileRadius = 12.dp

/**
 * Several pictures as one bubble: the first four in a 2x2 grid of squares, the fourth carrying a
 * count of however many more there are — the same treatment the contact page's media strip uses.
 *
 * No tap handling here: opening the group goes through the thread's `onTap`, like a single photo.
 */
@Composable
fun MediaGroupBubble(group: Attachment.MediaGroup) {
    val shown = group.items.take(4)
    val hidden = group.items.size - shown.size
    Column(modifier = Modifier.width(GroupWidth), verticalArrangement = Arrangement.spacedBy(TileGap)) {
        shown.chunked(2).forEachIndexed { row, pair ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                pair.forEachIndexed { column, item ->
                    val index = row * 2 + column
                    val overflow = if (index == shown.lastIndex && hidden > 0) hidden else 0
                    GroupTile(
                        item = item,
                        // Three pictures leave the last one alone on its row, where it spans both
                        // columns rather than leaving a hole.
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(if (pair.size == 1) 2f else 1f)
                            .pointerInput(group, index) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    MediaTilePresses.press(group, if (overflow > 0) MediaTilePresses.OVERFLOW else index)
                                }
                            },
                        overflow = overflow,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupTile(item: Attachment, modifier: Modifier, overflow: Int = 0, radius: Dp = TileRadius) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(radius)).background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        val uri = item.mediaUri()
        if (uri.isNotBlank()) {
            MediaThumbnail(uri, modifier = Modifier.fillMaxSize())
        }
        if (item is Attachment.Video) PlayBadge()
        if (overflow > 0) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("+$overflow", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun PlayBadge() {
    Box(
        modifier = Modifier.size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

private val CardWidth = 200.dp
private val CardHeight = 250.dp
private val CardRadius = 18.dp

/** Room around the stack for the fanned cards' corners, which rotate out past the top card. */
private val StackPadding = 22.dp

/** How far down, and how much smaller, each card behind the top one sits. */
private val DepthStep = 9.dp
private const val DEPTH_SHRINK = 0.055f

/** Cards this deep or deeper are faded out; two behind the top one are enough to read as a stack. */
private const val MAX_DEPTH = 3

/** Each card's own resting tilt once it is behind the top one, so the stack looks hand-dropped. */
private val CardTilt = floatArrayOf(-5f, 4f, -3f, 5.5f, -4.5f, 3f)

/** How far the top card leans over at a full card-width of drag. */
private const val DRAG_TILT = 14f

/** A drag past this share of the card's width throws it, however slowly it was let go. */
private const val THROW_FRACTION = 0.3f

/** And a release faster than this throws it however short the drag was. */
private val FlingVelocity = 700.dp

/**
 * Several pictures as a stack of cards.
 *
 * The animation is the "card deck" pattern — Tinder's swipe crossed with a deck being shuffled
 * through rather than discarded:
 *  - the top card follows the finger horizontally and leans into the drag, pivoting near its
 *    bottom edge the way a card held at the base does;
 *  - let go short of the threshold and it springs back, carrying the release velocity;
 *  - past it (or flicked), it is thrown out on an accelerating curve, then re-ordered to the
 *    bottom of the deck and springs home *underneath* the others — so it visibly goes to the back
 *    rather than vanishing;
 *  - every card's depth is spring-animated, so the card below rises, straightens and grows into
 *    the top slot on the same beat.
 *
 * Horizontal drags only, so a vertical fling that starts on the stack still scrolls the thread.
 */
@Composable
fun MediaCardStack(group: Attachment.MediaGroup) {
    val items = group.items
    val order = remember(group) { mutableStateListOf<Int>().apply { addAll(MediaStackPositions.order(group)) } }
    val drag = remember(group) { Animatable(0f) }
    /** The card on its way out and back, which the drag offset belongs to until it lands. */
    var flying by remember(group) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val widthPx = with(density) { CardWidth.toPx() }
    val flingPx = with(density) { FlingVelocity.toPx() }
    val reveal = LocalTimestampReveal.current

    Box(
        modifier = Modifier
            .padding(StackPadding)
            .size(CardWidth, CardHeight)
            // A sideways drag here is the stack's, not the conversation's time column. The column's
            // observer claims nothing and sees every drag, so it is told outright — held from the
            // moment a finger lands, before either gesture has decided what it is.
            .pointerInput(reveal) {
                if (reveal == null) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    reveal.lockForReply()
                    try {
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                    } finally {
                        reveal.releaseReply()
                    }
                }
            }
            .pointerInput(group) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onHorizontalDrag = { change, amount ->
                        if (flying == null && order.size > 1) {
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            scope.launch { drag.snapTo(drag.value + amount) }
                        }
                    },
                    onDragEnd = {
                        if (flying == null) {
                            val velocity = tracker.calculateVelocity().x
                            val x = drag.value
                            val thrown = abs(x) > widthPx * THROW_FRACTION ||
                                (abs(velocity) > flingPx && (x == 0f || sign(velocity) == sign(x)))
                            scope.launch {
                                if (!thrown || order.size < 2) {
                                    drag.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), initialVelocity = velocity)
                                } else {
                                    val top = order.first()
                                    flying = top
                                    val direction = if (x != 0f) sign(x) else sign(velocity)
                                    drag.animateTo(direction * widthPx * 1.2f, tween(170, easing = FastOutLinearInEasing))
                                    order.removeAt(0)
                                    order.add(top)
                                    MediaStackPositions.save(group, order.toList())
                                    drag.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
                                    flying = null
                                }
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
                    },
                )
            },
    ) {
        val moving = flying ?: order.firstOrNull()
        order.forEachIndexed { depth, index ->
            // A card deeper than the fade has nothing to draw, and composing it anyway meant a
            // twenty-picture group decoded all twenty at once — the top card queueing behind
            // pictures nobody could see. One past the visible ones is kept, already loaded, so the
            // card that rises into view on a flick has its picture when it gets there.
            if (depth > MAX_DEPTH && index != flying) return@forEachIndexed
            key(index) {
                StackCard(
                    item = items[index],
                    tilt = CardTilt[index % CardTilt.size],
                    depth = depth,
                    zIndex = (order.size - depth).toFloat(),
                    drag = if (index == moving) ({ drag.value }) else null,
                    widthPx = widthPx,
                )
            }
        }
    }
}

@Composable
private fun StackCard(item: Attachment, tilt: Float, depth: Int, zIndex: Float, drag: (() -> Float)?, widthPx: Float) {
    val settled by animateFloatAsState(
        targetValue = depth.coerceAtMost(MAX_DEPTH).toFloat(),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "stackDepth",
    )
    val stepPx = with(LocalDensity.current) { DepthStep.toPx() }
    Box(
        modifier = Modifier
            .zIndex(zIndex)
            .fillMaxSize()
            .graphicsLayer {
                val x = drag?.invoke() ?: 0f
                translationX = x
                translationY = settled * stepPx
                val scale = 1f - DEPTH_SHRINK * settled
                scaleX = scale
                scaleY = scale
                rotationZ = tilt * settled.coerceAtMost(1f) + x / widthPx * DRAG_TILT
                val fade = (MAX_DEPTH - settled).coerceIn(0f, 1f)
                alpha = fade
                transformOrigin = TransformOrigin(0.5f, 0.9f)
                // Gone with the card: a shadow under a see-through card shows through the card
                // itself, as a dark smear across the picture.
                shadowElevation = 8.dp.toPx() * fade
                shape = RoundedCornerShape(CardRadius)
                clip = true
            }
            .background(Color(0xFF2A2A2E)),
        contentAlignment = Alignment.Center,
    ) {
        val uri = item.mediaUri()
        if (uri.isNotBlank()) {
            MediaThumbnail(uri, modifier = Modifier.fillMaxSize())
        }
        if (item is Attachment.Video) PlayBadge()
    }
}

private val PageEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * Every picture in a group, two to a row.
 *
 * A dialog for the same reason the viewer is one: it is a look at part of the conversation, not a
 * place in the history, and back should leave the thread where it was.
 */
@Composable
fun MediaGroupPage(
    entries: List<MediaPageItem>,
    /** What a double tap drops on a picture. */
    likeEmoji: String,
    onOpen: (ViewableMedia) -> Unit,
    onLike: (MediaPageItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val media = entries.map { it.media }
    // The page is a `Dialog`, so the conversation it covers is in another window and there is
    // nothing behind it in this composition to blur. Copied from the host before the dialog's own
    // window exists — the same glass the app's other pages are made of, taken the way its dialogs
    // take theirs.
    val hostView = LocalView.current
    var behind by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(hostView) { behind = ScreenSnapshot.copyOf(hostView) }
    val opened = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { opened.animateTo(1f, tween(280, easing = PageEasing)) }
    val close: () -> Unit = {
        if (!leaving) {
            leaving = true
            scope.launch {
                opened.animateTo(0f, tween(180))
                onDismiss()
            }
        }
    }
    val title = when {
        media.all { it is ViewableMedia.Photo } -> "${media.size} Photos"
        media.all { it is ViewableMedia.Clip } -> "${media.size} Videos"
        else -> "${media.size} Items"
    }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        FrostedBackground(
            image = behind,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = opened.value
                    translationY = (1f - opened.value) * 60.dp.toPx()
                },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    IconButton(onClick = close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                ) {
                    items(entries) { entry ->
                        MediaPageTile(
                            entry = entry,
                            likeEmoji = likeEmoji,
                            onOpen = { onOpen(entry.media) },
                            onLike = { onLike(entry) },
                        )
                    }
                }
            }
        }
    }
}

/** One picture on the grid page, and the message it arrived in so it can be liked on its own. */
data class MediaPageItem(
    val media: ViewableMedia,
    val messageId: String?,
    /** Every reaction on that message, for the badge. */
    val reactions: List<String>,
    /** The ones that are yours, so a double tap knows whether it is adding or taking one away. */
    val mine: List<String>,
)

@Composable
private fun MediaPageTile(entry: MediaPageItem, likeEmoji: String, onOpen: () -> Unit, onLike: () -> Unit) {
    /** 0 at rest; runs to 1 over the emoji that pops up on a like. */
    val burst = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val open by rememberUpdatedState(onOpen)
    val like by rememberUpdatedState(onLike)
    val adding by rememberUpdatedState(likeEmoji !in entry.mine)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(entry.messageId) {
                detectTapGestures(
                    onTap = { open() },
                    onDoubleTap = {
                        if (entry.messageId != null) {
                            like()
                            // Only a like that lands gets the pop; taking one back just clears the badge.
                            if (adding) {
                                scope.launch {
                                    burst.snapTo(0.001f)
                                    burst.animateTo(1f, tween(650))
                                    burst.snapTo(0f)
                                }
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        MediaThumbnail(entry.media.uri, modifier = Modifier.fillMaxSize())
        if (entry.media is ViewableMedia.Clip) PlayBadge()
        if (burst.value > 0f) {
            Text(
                likeEmoji,
                fontSize = 64.sp,
                modifier = Modifier.graphicsLayer {
                    val t = burst.value
                    // Overshoots to 120% in the first third, settles back, fades over the last third.
                    val scale = if (t < 0.3f) t / 0.3f * 1.2f else 1.2f - 0.2f * ((t - 0.3f) / 0.7f)
                    scaleX = scale
                    scaleY = scale
                    alpha = if (t < 0.7f) 1f else (1f - t) / 0.3f
                },
            )
        }
        if (entry.reactions.isNotEmpty()) {
            Text(
                entry.reactions.joinToString(""),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
