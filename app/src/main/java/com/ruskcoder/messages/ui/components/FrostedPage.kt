package com.ruskcoder.messages.ui.components

import android.os.Build
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import kotlin.math.max
import kotlin.math.roundToInt

/** How hard the frozen screen is blurred under the page, on top of the copy's own coarseness. */
private val PageBlur = 28.dp

/**
 * How much of the page's surface sits over the blur. Low enough that the shapes of the conversation
 * still read through — the whole point of the frost — and high enough to read text over.
 */
private const val PageScrim = 0.62f

/**
 * A panel's own blur and tint, over and above the page's — see [FrostedSection].
 *
 * Heavier than the page it sits on by a wide margin, which is the only thing separating the two:
 * a panel has no border and no shadow, so if its glass is not visibly thicker than the page's it
 * does not read as a panel at all. Every inset section in the app is made of this — settings cards,
 * the contact and group pages' lists — so they are all the same thickness of glass by construction.
 */
private val PanelBlur = 34.dp
private const val PanelTint = 0.38f

/** Panels are inset from the page's edges and generously rounded, the way a grouped list is. */
val SectionInset = 16.dp
val SectionShape = RoundedCornerShape(22.dp)

/**
 * A details page laid over a frosted copy of the screen it was opened from.
 *
 * These pages used to be dialogs, purely so the platform could blur the live window behind them;
 * that put them in a window of their own, where their dropdown menus surfaced *underneath* the page
 * and the app's page transition never ran. So they are ordinary destinations now — they slide in
 * with everything else, and their menus sit where menus go — and the blur comes from
 * [ScreenSnapshot] instead of from the compositor.
 *
 * The bar carries no material of its own: it is a hole in the page through which the same frost
 * shows, and the content below is inset past it rather than scrolling under it, so there is nothing
 * for a second blur to be for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrostedPage(
    navigationIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    /**
     * Drawn over the bar as well as over the content — where a header that travels up into the bar
     * goes. The scaffold's own content slot cannot reach: it is laid out under the bar, not over it.
     */
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    FrostedBackground(modifier = modifier) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = title,
                    navigationIcon = navigationIcon,
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            content = content,
        )
        overlay()
    }
}

/**
 * The frost on its own, for a screen that brings its own chrome instead of the bar [FrostedPage]
 * gives it — a search field that is the whole header, a recipient picker with a composer under it.
 *
 * Everything the caller draws is recorded, so panels laid on the page can frost it a second time
 * ([FrostedSection], [frostedFill]) — and nothing outside this is, which is what keeps those panels'
 * own contents sharp over their blur.
 */
@Composable
fun FrostedBackground(
    modifier: Modifier = Modifier,
    /**
     * The screen to frost, for a surface that took its own copy.
     *
     * Null — the ordinary case — means the one the navigation left behind in [ScreenSnapshot]. A
     * page inside a `Dialog` cannot use that: it has a window of its own and is opened from a
     * screen that is still very much alive, so it captures the host itself and hands the copy in
     * here rather than publishing it and pulling the ground out from under whoever else is drawing
     * the shared one. See [AppDialog], which takes its glass the same way.
     */
    image: ImageBitmap? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberChatBackdrop()
    // The frozen screen is held for as long as there is a page made of it. Without this the copy
    // is dropped on whatever moment the navigation calls "arrived", and a page still on screen —
    // sliding out, or left behind by a back gesture that was let go of — goes flat. Only for the
    // shared copy: a caller that brought its own is holding it itself.
    if (image == null) {
        DisposableEffect(Unit) {
            ScreenSnapshot.retain()
            onDispose { ScreenSnapshot.release() }
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().chatBackdropSource(backdrop)) {
            FrostedBackdrop(image ?: ScreenSnapshot.image)
        }
        CompositionLocalProvider(LocalChatBackdrop provides backdrop) {
            Box(modifier = Modifier.fillMaxSize(), content = content)
        }
    }
}

/**
 * A panel on a frosted page: inset from the edges, rounded, and made of the page's own backdrop
 * blurred again under a dark tint — a thicker piece of the same glass rather than a card sitting on
 * top of it.
 *
 * Below API 31 there is no `RenderEffect` to blur with and the tint carries the panel on its own,
 * which is dark enough to read as a distinct surface without it.
 */
@Composable
fun FrostedSection(modifier: Modifier = Modifier, shape: Shape = SectionShape, content: @Composable ColumnScope.() -> Unit) {
    val backdrop = LocalChatBackdrop.current
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = SectionInset).clip(shape)) {
        Box(modifier = Modifier.matchParentSize().chatBackdropBlur(backdrop, shape, radius = PanelBlur))
        Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = PanelTint)))
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * The same material as [FrostedSection], for something that sets its own bounds — a pill, a tile.
 *
 * [tint] is the film over the blur, and which way it leans is the whole difference between the two
 * things this draws: black for a panel, which sinks into the page, and white for a control standing
 * on one, which lifts off it. Both are the same glass.
 *
 * Unlike [FrostedSection], which stacks its glass in sibling boxes behind its content, this goes on
 * the container itself — so the blur has to be one that paints *behind* the node rather than one
 * that wraps it. See [chatBackdropBlurBehind]: with the wrapping kind, everything the caller put
 * inside a frosted pill was blurred along with the wallpaper under it.
 */
@Composable
fun Modifier.frostedFill(
    shape: Shape,
    blur: Dp = PanelBlur,
    tint: Color = Color.Black.copy(alpha = PanelTint),
): Modifier {
    val backdrop = LocalChatBackdrop.current
    return this
        .clip(shape)
        .chatBackdropBlurBehind(backdrop, radius = blur)
        .background(tint)
}

/** A control resting *on* frosted glass: a heavier blur under a pale film, rather than a solid fill. */
val ControlTint = Color.White.copy(alpha = 0.13f)
val ControlBlur = 30.dp

// ---------------------------------------------------------------------------
// The collapsing header
// ---------------------------------------------------------------------------

/** The picture's two sizes: filling the top of the page, and sitting in the bar's title slot. */
private val AvatarExpanded = 140.dp
private val AvatarCollapsed = 32.dp

/** Material's own small top app bar height, which the header has to land inside. */
private val BarHeight = 64.dp

/** Where the collapsed picture sits: clear of the back button, where a bar's title would start. */
private val CollapsedLeft = 56.dp
private val CollapsedGap = 10.dp

/** The expanded header's own spacing, and therefore the space the page has to leave for it. */
private val AvatarTopPad = 4.dp
private val NameGap = 14.dp
private val SubtitleGap = 2.dp

/** How much room the page's content must leave at the top for the expanded header. */
val DetailHeaderHeight = 240.dp

/** The subtitle is gone well before the picture lands, rather than riding it all the way in. */
private const val SUBTITLE_FADE = 2.2f

/**
 * A details page whose picture and name travel up into the bar as you scroll, rather than sliding
 * away and being replaced by a title.
 *
 * The header is drawn over the page rather than in it: what has to move crosses the bar, and the
 * scaffold's content slot is laid out underneath the bar with no way to reach across it. The page's
 * own content leaves [DetailHeaderHeight] clear at the top for the expanded header to stand in, and
 * is inset past the bar besides — so the two never overlap at any point along the way, and nothing
 * of the conversation ever shows through the bar behind the name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrostedDetailPage(
    name: String,
    subtitle: String,
    /** The page's own scroll, which is what drives the collapse. */
    scroll: ScrollState,
    /** The picture, drawn to whatever size the header currently wants it at. */
    avatar: @Composable (Dp) -> Unit,
    navigationIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Null leaves the name inert; a group hangs its rename off it. */
    onNameClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    FrostedPage(
        navigationIcon = navigationIcon,
        modifier = modifier,
        actions = actions,
        // Reading the scroll here rather than in the page body: this is the one thing that changes
        // sixty times a second, and it recomposes only itself.
        overlay = { DetailHeader(name, subtitle, scroll, avatar, onNameClick) },
        content = content,
    )
}

@Composable
private fun DetailHeader(
    name: String,
    subtitle: String,
    scroll: ScrollState,
    avatar: @Composable (Dp) -> Unit,
    onNameClick: (() -> Unit)?,
) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // Fully collapsed by the time the header's own slot has scrolled up to the bar's lower edge —
    // which is also the last moment before the page's real content would reach it.
    val distance = with(LocalDensity.current) { (DetailHeaderHeight - BarHeight).toPx() }
    val collapse = (scroll.value / max(distance, 1f)).coerceIn(0f, 1f)

    val avatarSize = lerp(AvatarExpanded, AvatarCollapsed, collapse)
    val nameStyle = lerp(
        MaterialTheme.typography.headlineMedium,
        MaterialTheme.typography.titleMedium,
        collapse,
    )

    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Box { avatar(avatarSize) }
            Text(
                name,
                style = nameStyle,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onNameClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onNameClick,
                    )
                },
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = (1f - collapse * SUBTITLE_FADE).coerceIn(0f, 1f) },
            )
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val barCentre = statusBar.roundToPx() + BarHeight.roundToPx() / 2
        val headerTop = statusBar.roundToPx() + BarHeight.roundToPx()

        val avatarPlaceable = measurables[0].measure(Constraints())
        // The name is given the page's width while it is standing in the middle of it, and only
        // what is left between the back button and the menu once it has landed in the bar.
        val nameRoom = lerp(
            width - 2 * SectionInset.roundToPx(),
            width - CollapsedLeft.roundToPx() - AvatarCollapsed.roundToPx() - CollapsedGap.roundToPx() - BarHeight.roundToPx(),
            collapse,
        )
        val namePlaceable = measurables[1].measure(Constraints(maxWidth = nameRoom.coerceAtLeast(0)))
        val subtitlePlaceable = measurables[2].measure(Constraints(maxWidth = (width - 2 * SectionInset.roundToPx()).coerceAtLeast(0)))

        // Both ends of the journey are fixed points worked out from the *expanded* sizes, so the
        // path between them doesn't shift as the pieces shrink along it. Everything is placed by
        // its centre, which is what lets a name that is re-measured smaller every frame travel
        // smoothly instead of stepping as its box changes.
        val expandedAvatar = AvatarExpanded.roundToPx()
        val avatarTop = headerTop + AvatarTopPad.roundToPx()
        val nameTop = avatarTop + expandedAvatar + NameGap.roundToPx()

        fun place(
            expandedCentreX: Int,
            expandedCentreY: Int,
            collapsedCentreX: Int,
            collapsedCentreY: Int,
            size: IntSize,
        ): IntOffset = IntOffset(
            lerp(expandedCentreX, collapsedCentreX, collapse) - size.width / 2,
            lerp(expandedCentreY, collapsedCentreY, collapse) - size.height / 2,
        )

        val avatarAt = place(
            expandedCentreX = width / 2,
            expandedCentreY = avatarTop + expandedAvatar / 2,
            collapsedCentreX = CollapsedLeft.roundToPx() + AvatarCollapsed.roundToPx() / 2,
            collapsedCentreY = barCentre,
            size = IntSize(avatarPlaceable.width, avatarPlaceable.height),
        )
        val nameAt = place(
            expandedCentreX = width / 2,
            expandedCentreY = nameTop + namePlaceable.height / 2,
            collapsedCentreX = CollapsedLeft.roundToPx() + AvatarCollapsed.roundToPx() + CollapsedGap.roundToPx() + namePlaceable.width / 2,
            collapsedCentreY = barCentre,
            size = IntSize(namePlaceable.width, namePlaceable.height),
        )
        // Follows the name rather than aiming anywhere of its own: it has faded out long before the
        // difference could be seen, and a second destination would only be a second thing to tune.
        val subtitleAt = IntOffset(
            nameAt.x + namePlaceable.width / 2 - subtitlePlaceable.width / 2,
            nameAt.y + namePlaceable.height + SubtitleGap.roundToPx(),
        )

        layout(constraints.maxWidth, constraints.maxHeight) {
            avatarPlaceable.place(avatarAt)
            namePlaceable.place(nameAt)
            subtitlePlaceable.place(subtitleAt)
        }
    }
}

/**
 * The frozen screen, blown back up to fill the page and blurred, under the page's own scrim.
 *
 * The copy is a fifth of the screen in each direction, so drawing it at full size is already a blur
 * of sorts; the [BlurEffect] on top is what turns the pixel grid that leaves behind into something
 * smooth. That effect is API 31 and up — below it the upscale carries the look alone, which is
 * soft rather than sharp and reads as intended.
 *
 * With no snapshot to show — opened from somewhere that never captured one, or a copy that failed —
 * the page falls back to its plain surface.
 */
@Composable
private fun FrostedBackdrop(image: ImageBitmap?) {
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (image != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.graphicsLayer {
                        // Clipped, or the blur samples past the page and leaves its edges thin.
                        clip = true
                        val radius = PageBlur.toPx()
                        renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    }
                } else {
                    Modifier
                }
            )
            .drawBehind {
                if (image == null) {
                    drawRect(surface)
                    return@drawBehind
                }
                // Filled rather than stretched: the copy was taken at the screen's shape, but a
                // rotation between capture and draw would otherwise smear it sideways.
                val scale = max(size.width / image.width, size.height / image.height)
                val sourceWidth = (size.width / scale).roundToInt().coerceIn(1, image.width)
                val sourceHeight = (size.height / scale).roundToInt().coerceIn(1, image.height)
                drawImage(
                    image = image,
                    srcOffset = IntOffset((image.width - sourceWidth) / 2, (image.height - sourceHeight) / 2),
                    srcSize = IntSize(sourceWidth, sourceHeight),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
            },
    )
    Box(modifier = Modifier.fillMaxSize().background(surface.copy(alpha = PageScrim)))
}
