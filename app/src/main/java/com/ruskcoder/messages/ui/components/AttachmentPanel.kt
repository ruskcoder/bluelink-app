package com.ruskcoder.messages.ui.components

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ScrollMoveMode
import sh.calvin.reorderable.rememberReorderableLazyGridState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.PermContactCalendar
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import com.ruskcoder.messages.data.model.AttachmentSource
import com.ruskcoder.messages.data.model.GalleryMode
import com.ruskcoder.messages.ui.theme.LocalSettings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One capsule on the panel.
 *
 * [source] is null for the ones that are not an attachment at all — the way through to the
 * messages this conversation has queued for later, the formatting controls, the poll builder. They
 * sit on the panel because that is where the composer keeps everything that is about the message
 * you are writing rather than about the words in it.
 *
 * [id] is what a saved arrangement is written in, so it has to outlive the label: renaming a tile
 * must not scatter somebody's grid.
 */
private data class Tile(
    val id: String,
    val source: AttachmentSource?,
    val icon: ImageVector,
    val label: String,
    val action: PanelAction? = null,
)

/** The tiles that are not attachments at all — the ways through to something else. */
private enum class PanelAction { GAMES, SCHEDULED, RICH_TEXT, EFFECTS, POLL, RECENT_PHOTOS, STICKERS }

/** The one tile that is a widget: the latest pictures in the gallery, spanning several cells. */
private const val PHOTOS_WIDGET_ID = "recentphotos"

/**
 * What the "+" offers, in the order it ships in.
 *
 * None of them a colour: outlines on glass say what each label says in words and let the glass be
 * the surface. This is only the *default* order — what is drawn is whatever arrangement the grid
 * was last dragged into, see [orderedTiles].
 */
private val TILES = listOf(
    Tile(PHOTOS_WIDGET_ID, null, Icons.Outlined.Photo, "Recent", PanelAction.RECENT_PHOTOS),
    Tile("games", null, Icons.Outlined.SportsEsports, "Games", PanelAction.GAMES),
    Tile("gallery", AttachmentSource.PHOTOS, Icons.Outlined.PhotoLibrary, "Gallery"),
    Tile("camera", AttachmentSource.CAMERA, Icons.Outlined.PhotoCamera, "Camera"),
    Tile("video", AttachmentSource.VIDEO, Icons.Outlined.Videocam, "Video"),
    Tile("document", AttachmentSource.DOCUMENT, Icons.Outlined.Description, "Document"),
    Tile("contact", AttachmentSource.CONTACT, Icons.Outlined.PermContactCalendar, "Contact"),
    Tile("poll", null, Icons.Outlined.BarChart, "Polls", PanelAction.POLL),
    Tile("scheduled", null, Icons.Outlined.Schedule, "Scheduled", PanelAction.SCHEDULED),
    Tile("richtext", null, Icons.Outlined.TextFormat, "Rich text", PanelAction.RICH_TEXT),
    Tile("effects", null, Icons.Outlined.AutoAwesome, "Effects", PanelAction.EFFECTS),
    Tile("stickers", null, Icons.Outlined.EmojiEmotions, "Stickers", PanelAction.STICKERS),
)

/**
 * The panel's tiles in the arrangement [saved] describes.
 *
 * Ids the panel no longer ships are dropped and tiles the arrangement has never heard of are
 * appended in their shipped order, so adding a tile to [TILES] puts it on the end of everybody's
 * grid rather than reverting the grid they built.
 */
private fun orderedTiles(saved: List<String>, mode: GalleryMode = GalleryMode.WIDGET): List<Tile> {
    val tiles = if (mode == GalleryMode.WIDGET) TILES else TILES.filterNot { it.id == PHOTOS_WIDGET_ID }
    if (saved.isEmpty()) return tiles
    val byId = tiles.associateBy { it.id }
    val kept = saved.mapNotNull(byId::get)
    return kept + tiles.filter { tile -> kept.none { it.id == tile.id } }
}

/**
 * Where every tile lands on the grid, as a (column, row) cell for its top-left corner.
 *
 * First fit, in order: each tile takes the first place its span fits, scanning row by row. A tile
 * that spans more than one cell can leave a hole behind it, which the next single tile fills — the
 * same way a home screen packs its widgets.
 */
private fun packTiles(tiles: List<Tile>, spanOf: (Tile) -> IntSize): List<IntOffset> {
    val taken = HashSet<Long>()
    fun key(col: Int, row: Int) = row.toLong() * 64 + col
    fun fits(col: Int, row: Int, span: IntSize): Boolean {
        for (r in row until row + span.height) for (c in col until col + span.width) {
            if (key(c, r) in taken) return false
        }
        return true
    }
    return tiles.map { tile ->
        val span = spanOf(tile)
        var row = 0
        var placed: IntOffset? = null
        while (placed == null) {
            for (col in 0..(COLUMNS - span.width)) {
                if (fits(col, row, span)) {
                    placed = IntOffset(col, row)
                    break
                }
            }
            if (placed == null) row++
        }
        for (r in placed.y until placed.y + span.height) for (c in placed.x until placed.x + span.width) {
            taken += key(c, r)
        }
        placed
    }
}

/**
 * How close to the end of what has been read a gallery has to get before the next page is asked for.
 *
 * Counted in the grid's own items — pictures in the full gallery, columns of them in the widget —
 * and generous enough that the read is under way well before the scroll arrives.
 */
private const val GalleryPrefetch = 24
private const val WidgetPrefetch = 6

/** How many pills stand side by side. Three keeps each one comfortably wider than it is tall. */
private const val COLUMNS = 3

/** The tallest the photos widget may be stretched, in rows. */
private const val WIDGET_MAX_ROWS = 3

/**
 * How many pictures one read of the gallery brings back.
 *
 * A page rather than a ceiling. Asked for everything at once this is a scan of every image on the
 * device before the card can be drawn at all; asked for a page at a time it is a handful of rows
 * and the rest arrives as it is scrolled to. A phone with ten thousand photographs is the ordinary
 * case, not the awkward one.
 */
private const val PHOTO_PAGE = 120

/**
 * The picture counts each widget size offers, with the grid (columns to rows) each is laid out in.
 * The first entry is the size's own default, used when the saved count isn't one of its choices.
 */
private fun photoLayouts(span: IntSize): List<Pair<Int, IntSize>> = when {
    span.width == 1 -> listOf(2 to IntSize(2, 1), 1 to IntSize(1, 1))
    span.width == 2 && span.height == 1 -> listOf(3 to IntSize(3, 1), 4 to IntSize(4, 1))
    span.width == 2 && span.height == 2 -> listOf(6 to IntSize(3, 2), 4 to IntSize(2, 2))
    span.width == 2 -> listOf(8 to IntSize(2, 4), 6 to IntSize(2, 3), 4 to IntSize(2, 2))
    // Three cells across: the same layouts stretched, a column and a half wider.
    span.height == 1 -> listOf(5 to IntSize(5, 1), 6 to IntSize(6, 1))
    span.height == 2 -> listOf(10 to IntSize(5, 2), 8 to IntSize(4, 2), 6 to IntSize(3, 2))
    else -> listOf(12 to IntSize(4, 3), 9 to IntSize(3, 3), 6 to IntSize(2, 3))
}

/** The saved count if this size offers it, otherwise the size's default. */
private fun photoCountFor(span: IntSize, saved: Int): Int =
    photoLayouts(span).firstOrNull { it.first == saved }?.first ?: photoLayouts(span).first().first

/**
 * The one gap this card knows about, used on all four sides of it: to the screen's edges, and to the
 * send bar above.
 */
private val SheetInset = 8.dp
private val SheetShape = RoundedCornerShape(26.dp)

/** How much of the send bar's colour the card carries — the same proportion the bar keeps. */
private const val PanelSurfaceTint = 0.55f

/** The card's own padding, on all four sides, and the gap between its rows. */
private val TileInset = 14.dp
private val TileGap = 12.dp

/**
 * How tall one cell wants to be.
 *
 * A wish rather than a measurement: the widget card cuts its real cell height from its own height
 * so that a whole number of rows fills it exactly — see `tileHeight` in [AttachmentPanel]. Left
 * flat, the bottom row was sliced by however tall the keyboard this card stands in happened to be,
 * and the photos widget, whose span is counted in these cells, was sliced with it. The full-height
 * gallery has its own scroll and uses this as it stands.
 */
private val TileHeight = 78.dp

/** The pills: fully rounded ends, sized by the row they are in rather than fixed. */
private val PillShape = RoundedCornerShape(percent = 50)

private val WidgetShape = RoundedCornerShape(24.dp)

/** How much a picked-up tile lifts off the grid. */
private const val DraggedScale = 1.06f
private const val DraggedAlpha = 0.92f

/**
 * The composer's "+" attachment sheet.
 *
 * A card of the same frosted glass as the send bar, with the sources on it as wide capsules and a
 * widget of the latest pictures in the gallery. The grid scrolls, and anything on it can be picked
 * up and rearranged: hold it and it lifts off the glass, the rest slide around it, and letting go
 * writes the arrangement to settings. Holding the widget also brings up its resize handles, the way
 * a home screen widget does, and dragging those changes how many cells it spans.
 *
 * [height] is the keyboard's, measured by the screen: the sheet takes the keyboard's slot rather
 * than stacking with it.
 */
@Composable
fun AttachmentPanel(
    onSelect: (AttachmentSource) -> Unit,
    /** Opens the games picker in place of this card's own grid. */
    onGames: () -> Unit = {},
    /** The card's own colour, matched to the bar above it. */
    surface: Color,
    /** Opens what this conversation has waiting to be sent. */
    onScheduled: () -> Unit = {},
    /** Turns this card into the formatting controls. */
    onRichText: () -> Unit = {},
    /** Turns it into the send effects. */
    onEffects: () -> Unit = {},
    /** Opens the choice list in the send bar, above the words. */
    onPoll: () -> Unit = {},
    /** Opens the sticker face, to drag an emoji onto a message. */
    onStickers: () -> Unit = {},
    /** A picture tapped on the recent-photos widget, to go into the draft. */
    onPickPhoto: (Uri) -> Unit = {},
    /** Writes the widget's new span back to settings once a resize is let go of. */
    onResizeWidget: (columns: Int, rows: Int) -> Unit = { _, _ -> },
    /** Writes how many pictures the widget shows back to settings. */
    onPhotoCount: (Int) -> Unit = {},
    /**
     * How much taller than [height] the card is standing right now, and the most it may stand —
     * the gallery's own doing, in `GalleryMode.FULL`: scrolling the pictures grows the card before
     * it scrolls them. Answered with how much of the offered travel was actually taken, so the
     * scroll keeps whatever the card did not use.
     */
    expansion: Dp = 0.dp,
    maxExpansion: Dp = 0.dp,
    onExpand: (Dp) -> Dp = { 0.dp },
    /** How far below its resting height the card may be pulled by the grab bar before it is let go. */
    collapseRoom: Dp = 0.dp,
    /** The grab bar was let go of, with the fling's velocity — positive downwards. */
    onPullRelease: (Float) -> Unit = {},
    /** Bumped to put a gallery that has been scrolled into back where it started. */
    resetSignal: Int = 0,
    /** Whether the gallery is standing anywhere but at rest, so back can put it back. */
    onScrolledChange: (Boolean) -> Unit = {},
    /** The pictures already in the draft: picked out on the grid, and tapped again to take out. */
    selectedPhotos: Set<Uri> = emptySet(),
    /** Whether there is a message to put an effect on. */
    effectsEnabled: Boolean = true,
    /** Writes a rearranged grid back to settings, on the drop. */
    onReorder: (List<String>) -> Unit = {},
    busy: Boolean = false,
    height: Dp = 232.dp,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val saved = settings.attachmentTiles
    // One state object for the life of the panel, never `remember(saved)`: the drag handlers keep
    // the closure they were first built with, and a drop writes settings.
    val galleryMode = settings.galleryMode
    var order by remember { mutableStateOf(orderedTiles(saved, galleryMode)) }
    /** Which tile is in the hand, by id, or null when the grid is at rest. */
    var carried by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(saved, galleryMode) {
        if (carried == null) order = orderedTiles(saved, galleryMode)
    }

    /** The widget's span, held locally while a handle is being dragged so the grid follows the finger. */
    var widgetSpan by remember {
        mutableStateOf(clampSpan(settings.photosWidgetColumns, settings.photosWidgetRows))
    }
    /** Whether the widget's handles are up. Brought up by holding it; put away by touching anything else. */
    var resizeMode by remember { mutableStateOf(false) }
    var resizingNow by remember { mutableStateOf(false) }
    LaunchedEffect(settings.photosWidgetColumns, settings.photosWidgetRows) {
        if (!resizingNow) widgetSpan = clampSpan(settings.photosWidgetColumns, settings.photosWidgetRows)
    }
    val spanOf: (Tile) -> IntSize = { if (it.id == PHOTOS_WIDGET_ID) widgetSpan else IntSize(1, 1) }
    val placements = packTiles(order, spanOf)

    /**
     * Where the carried tile was when it was picked up, in pixels — the place it stays drawn from
     * for the whole carry, however the grid repacks under it. See the drop for how it is rebased.
     */
    var carriedOrigin by remember { mutableStateOf(Offset.Zero) }
    /** How far it has been carried from where it was picked up, in pixels. */
    var carriedBy by remember { mutableStateOf(Offset.Zero) }
    /** The cell that triggered the last move, so a still hand over a repacked grid does not shuttle. */
    var lastProbe by remember { mutableStateOf<IntOffset?>(null) }
    /** Whether the tile in the hand has been let go of and is on its way back into the grid. */
    var settling by remember { mutableStateOf(false) }
    /** How much of the carry offset is still applied while [settling]: 1 at the release, 0 once home. */
    var settle by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val currentOnReorder by rememberUpdatedState(onReorder)
    val currentOnResize by rememberUpdatedState(onResizeWidget)
    val currentPlacements by rememberUpdatedState(placements)
    /** Swallows the click that ends a long press nobody moved. */
    var armed by remember { mutableStateOf(true) }
    var carriedMoved by remember { mutableStateOf(false) }

    // The pictures behind the widget, read once access is there.
    var photoAccess by remember { mutableStateOf(hasPhotoAccess(context)) }
    /** Bumped by every answer to a permission request, so a widened grant reads the gallery again. */
    var accessChanges by remember { mutableIntStateOf(0) }
    var recentPhotos by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    /** Raised by a picture on the widget as it is held, so the widget's own carry stands aside. */
    val photoHold = remember { PhotoHold() }
    /** False once a page comes back short: there is nothing further down the gallery to ask for. */
    var morePhotos by remember { mutableStateOf(true) }
    /** Raised across a read, so a grid that reaches the end twice in two frames only asks once. */
    var readingPhotos by remember { mutableStateOf(false) }
    val accessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        photoAccess = hasPhotoAccess(context)
        accessChanges++
    }
    // Pictures alone were all the galleries once asked for, and a grant that old reads as access
    // while hiding every clip — so it is asked about the videos too, once per opening of the card.
    LaunchedEffect(Unit) {
        if (photoAccess && lacksVideoAccess(context)) {
            accessLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_VIDEO))
        }
    }
    val photoCount = photoCountFor(widgetSpan, settings.photosWidgetCount)
    LaunchedEffect(photoAccess, accessChanges) {
        if (!photoAccess) return@LaunchedEffect
        // More than any layout shows at once: both galleries scroll through the rest, and reaching
        // the end of what has been read asks for the next page rather than stopping there.
        val first = withContext(Dispatchers.IO) { queryRecentPhotos(context, PHOTO_PAGE, 0) }
        recentPhotos = first
        morePhotos = first.size == PHOTO_PAGE
    }

    /**
     * Reads the next page, for a gallery that has been scrolled to the end of this one.
     *
     * Safe to call on every frame of a scroll: it is its own guard, and it does nothing at all once
     * the gallery has been read to the bottom.
     */
    val loadMorePhotos: () -> Unit = {
        if (photoAccess && morePhotos && !readingPhotos) {
            readingPhotos = true
            scope.launch {
                val from = recentPhotos.size
                val page = withContext(Dispatchers.IO) { queryRecentPhotos(context, PHOTO_PAGE, from) }
                // Only if the list is still the one this page was read for: permission granted
                // mid-scroll re-reads from the top, and appending to that would double the front of
                // the gallery.
                if (recentPhotos.size == from) {
                    recentPhotos = recentPhotos + page
                    morePhotos = page.size == PHOTO_PAGE
                }
                readingPhotos = false
            }
        }
    }

    if (galleryMode == GalleryMode.FULL) {
        FullGalleryPanel(
            tiles = order,
            onReorder = onReorder,
            surface = surface,
            // Only the way up: pulled below its rest the card is *let go of*, not shrunk — the
            // slot shows less of a card that is the same size, which is a sheet sliding off rather
            // than a sheet being squashed.
            height = height + expansion.coerceAtLeast(0.dp),
            photos = recentPhotos,
            onLoadMorePhotos = loadMorePhotos,
            hasAccess = photoAccess,
            busy = busy,
            effectsEnabled = effectsEnabled,
            expansion = expansion,
            maxExpansion = maxExpansion,
            onExpand = onExpand,
            collapseRoom = collapseRoom,
            onPullRelease = onPullRelease,
            resetSignal = resetSignal,
            onScrolledChange = onScrolledChange,
            selected = selectedPhotos,
            onRequestAccess = { accessLauncher.launch(photoPermissions()) },
            onPhoto = onPickPhoto,
            onTile = { tile ->
                when {
                    tile.source != null -> onSelect(tile.source)
                    tile.action == PanelAction.GAMES -> onGames()
                    tile.action == PanelAction.RICH_TEXT -> onRichText()
                    tile.action == PanelAction.EFFECTS -> onEffects()
                    tile.action == PanelAction.POLL -> onPoll()
                    tile.action == PanelAction.STICKERS -> onStickers()
                    else -> onScheduled()
                }
            },
            modifier = modifier,
        )
        return
    }

    // Swiped down on, the card follows the finger off the bottom of the screen and is let go of
    // at the end of the swipe — the same dismissal the full-height gallery's grab bar gives, from
    // anywhere on the buttons rather than from one 36dp bar. The grid's own scroll comes first: it
    // is only once there is nothing left to scroll up there that the card starts to move.
    val panelDensity = LocalDensity.current
    val latestExpansion by rememberUpdatedState(expansion)
    val latestCollapse by rememberUpdatedState(collapseRoom)
    val latestExpand by rememberUpdatedState(onExpand)
    val latestRelease by rememberUpdatedState(onPullRelease)
    val pullDown = remember {
        object : NestedScrollConnection {
            /** Downwards is positive, and the card may only ever be *below* its resting height. */
            fun pull(dy: Float): Float {
                val wanted = with(panelDensity) { dy.toDp() }
                val target = (latestExpansion - wanted).coerceIn(-latestCollapse, 0.dp)
                val delta = target - latestExpansion
                if (delta == 0.dp) return 0f
                return with(panelDensity) { -latestExpand(delta).toPx() }
            }

            // Already off its rest: the card is what the finger is holding, either way it goes.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (latestExpansion < 0.dp) Offset(0f, pull(available.y)) else Offset.Zero

            // At rest: the grid scrolls first and the card takes what is left over of a swipe down.
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = if (available.y > 0f) Offset(0f, pull(available.y)) else Offset.Zero

            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity {
                if (latestExpansion >= 0.dp) return androidx.compose.ui.unit.Velocity.Zero
                latestRelease(available.y)
                return available
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(SheetInset)
            .frostedFill(SheetShape, tint = surface.copy(alpha = PanelSurfaceTint))
            .padding(horizontal = TileInset)
            .nestedScroll(pullDown),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().alpha(if (busy) 0.4f else 1f)) {
            val density = LocalDensity.current
            val scroll = rememberScrollState()
            val cellWidth = (maxWidth - TileGap * (COLUMNS - 1)) / COLUMNS
            // One cell's height, cut from the card rather than fixed. However many rows come
            // closest to the height a pill wants to be, then shared out exactly — so the card
            // always ends on a row's own edge, with the same margin under the last one as over the
            // first. The photos widget spans these cells, so its picture grid lines up with the
            // buttons beside it by construction rather than by a number that happens to agree.
            val usableHeight = maxHeight - TileInset * 2
            val restRows = ((usableHeight + TileGap) / (TileHeight + TileGap)).roundToInt().coerceAtLeast(1)
            val tileHeight = ((usableHeight - TileGap * (restRows - 1)) / restRows).coerceAtLeast(1.dp)
            val rows = order.indices.maxOfOrNull { placements[it].y + spanOf(order[it]).height } ?: 0
            val gridHeight = tileHeight * rows + TileGap * (rows - 1).coerceAtLeast(0)
            val cellPitchPx = with(density) { (cellWidth + TileGap).toPx() }
            val rowPitchPx = with(density) { (tileHeight + TileGap).toPx() }
            val insetPx = with(density) { TileInset.toPx() }
            val viewportPx = with(density) { maxHeight.toPx() }

            fun cellPx(cell: IntOffset) = Offset(cell.x * cellPitchPx, cell.y * rowPitchPx)
            fun widthFor(span: IntSize): Dp = cellWidth * span.width + TileGap * (span.width - 1)
            fun heightFor(span: IntSize): Dp = tileHeight * span.height + TileGap * (span.height - 1)

            // Creeps the grid while a carried tile is held against the top or bottom of the card.
            LaunchedEffect(carried) {
                if (carried == null) return@LaunchedEffect
                while (true) {
                    withFrameNanos { }
                    if (settling) break
                    if (!carriedMoved) continue
                    val probe = insetPx + carriedOrigin.y + carriedBy.y + rowPitchPx / 2f
                    val top = scroll.value.toFloat()
                    val step = when {
                        probe < top + AutoScrollEdge * rowPitchPx -> -AutoScrollStep
                        probe > top + viewportPx - AutoScrollEdge * rowPitchPx -> AutoScrollStep
                        else -> 0f
                    }
                    if (step == 0f) continue
                    val moved = scroll.scrollBy(step)
                    if (moved != 0f) carriedBy += Offset(0f, moved)
                }
            }

            // Every resting scroll position puts a row's top exactly one inset below the card's edge,
            // the margin the first row has at rest — so the tiles line up with the corner radius
            // wherever the grid stops, including at the far end. The content is padded at the bottom
            // until the furthest scroll is a whole number of rows, and flings settle onto rows.
            val gridHeightPx = with(density) { gridHeight.toPx() }
            val overflowPx = (insetPx * 2 + gridHeightPx - viewportPx).coerceAtLeast(0f)
            val endRows = kotlin.math.ceil(overflowPx / rowPitchPx - 0.001f).coerceAtLeast(0f)
            val tailPx = endRows * rowPitchPx - overflowPx
            val maxSnapPx = endRows * rowPitchPx
            val snapProvider = remember(scroll, rowPitchPx, maxSnapPx) {
                object : androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider {
                    override fun calculateSnapOffset(velocity: Float): Float {
                        val current = scroll.value / rowPitchPx
                        val row = when {
                            velocity > 0f -> kotlin.math.ceil(current)
                            velocity < 0f -> kotlin.math.floor(current)
                            else -> kotlin.math.round(current)
                        }
                        return (row * rowPitchPx).coerceIn(0f, maxSnapPx) - scroll.value
                    }
                }
            }
            val snapFling = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(snapProvider)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll, flingBehavior = snapFling)
                    .padding(top = TileInset, bottom = TileInset + with(density) { tailPx.toDp() }),
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(gridHeight)) {
                    order.forEachIndexed { index, tile ->
                      key(tile.id) {
                        val held = carried == tile.id
                        val isWidget = tile.id == PHOTOS_WIDGET_ID
                        val span = spanOf(tile)
                        val rest = if (held) carriedOrigin else cellPx(placements[index])
                        val position = remember { Animatable(rest, Offset.VectorConverter) }
                        LaunchedEffect(rest, held) {
                            if (held) position.snapTo(rest)
                            else position.animateTo(rest, spring(stiffness = Spring.StiffnessMediumLow))
                        }
                        val lift by animateFloatAsState(
                            targetValue = if (held && !settling) DraggedScale else 1f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "tileLift",
                        )

                        /** Puts the carry down where it stands, in whatever place it reached. */
                        fun land() {
                            val landed = order.indexOfFirst { it.id == tile.id }
                            if (landed >= 0) {
                                val home = cellPx(currentPlacements[landed])
                                carriedBy += carriedOrigin - home
                                carriedOrigin = home
                            }
                            settle = 1f
                            settling = true
                            if (carriedMoved) armed = true
                            currentOnReorder(order.map { it.id })
                            settleJob?.cancel()
                            settleJob = scope.launch {
                                animate(1f, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                                    settle = value
                                }
                                carried = null
                                carriedBy = Offset.Zero
                                settling = false
                            }
                        }

                        /** Lifts the tile off the grid, for the carry that follows. */
                        fun pickUp() {
                            settleJob?.cancel()
                            settleJob = null
                            val from = order.indexOfFirst { it.id == tile.id }.coerceAtLeast(0)
                            carried = tile.id
                            carriedOrigin = cellPx(currentPlacements[from])
                            carriedBy = Offset.Zero
                            lastProbe = currentPlacements[from]
                            settling = false
                            armed = false
                            carriedMoved = false
                            // Holding the widget brings its handles up; holding anything
                            // else puts them away.
                            resizeMode = isWidget
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }

                        /** One step of the carry, [amount] being the finger's own travel. */
                        fun carryBy(change: PointerInputChange, amount: Offset) {
                            change.consume()
                            carriedMoved = true
                            // The finger's own travel, unscaled: the lift is a `scaleX` about
                            // the tile's middle and the carry is a translation outside it, so
                            // the two do not compound. Multiplied by the lift the tile ran a
                            // few percent ahead of the finger, and further ahead the longer
                            // the drag — which is the drift that made the grid it was being
                            // aimed at disagree with the tile being aimed.
                            carriedBy += amount
                            val from = order.indexOfFirst { it.id == tile.id }
                            if (from < 0) return
                            // Probed at the carried tile's top-left cell, half a cell in, so a
                            // widget and a pill are aimed the same way: by their corner.
                            val corner = carriedOrigin + carriedBy + Offset(cellPitchPx / 2f, rowPitchPx / 2f)
                            // Measured against the cell last probed, not against where the
                            // carried tile currently sits. Those are the same thing until the
                            // first swap, and after it they are a feedback loop: repacking
                            // moves the carried tile, which moves the dead band the next probe
                            // is judged against, which lands on a different cell for a finger
                            // that never moved — so the grid swapped, unswapped and swapped
                            // again under a still hand. The last probe is what the finger
                            // actually said, and nothing the grid does can move it.
                            val anchor = lastProbe ?: currentPlacements[from]
                            val probe = IntOffset(
                                cellUnder(corner.x, cellPitchPx, anchor.x).coerceIn(0, COLUMNS - span.width),
                                cellUnder(corner.y, rowPitchPx, anchor.y).coerceAtLeast(0),
                            )
                            if (probe == lastProbe) return
                            lastProbe = probe
                            // Whichever tile covers that cell is the slot to move into; past
                            // the end of the grid, the end of the list.
                            val covering = order.indices.firstOrNull { i ->
                                if (i == from) return@firstOrNull false
                                val p = currentPlacements[i]
                                val s = spanOf(order[i])
                                probe.x in p.x until p.x + s.width && probe.y in p.y until p.y + s.height
                        }
                        val lastRow = order.indices.maxOf { currentPlacements[it].y }
                        val to = covering ?: if (probe.y > lastRow) order.lastIndex else return
                        if (to == from) return
                        val next = order.toMutableList()
                        next.add(to, next.removeAt(from))
                        order = next
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }

                        val carryGesture = Modifier.pointerInput(tile.id, busy, cellWidth, tileHeight) {
                            if (busy) return@pointerInput
                            // A hold that began on one of the widget's pictures is the picture's: it
                            // previews, and hands the carry over itself if it is dragged off — see
                            // [GalleryCarry]. This detector's own timer would fire under it all the same.
                            var ceded = false
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    ceded = isWidget && photoHold.claimed
                                    if (!ceded) pickUp()
                                },
                                onDrag = { change, amount -> if (!ceded) carryBy(change, amount) },
                                onDragEnd = { if (!ceded) land() },
                                onDragCancel = { if (!ceded) land() },
                            )
                        }
                        val photoCarry = if (isWidget) {
                            GalleryCarry(hold = photoHold, start = ::pickUp, drag = ::carryBy, end = ::land)
                        } else {
                            null
                        }

                        Box(
                            modifier = Modifier
                                .zIndex(if (held) 2f else if (isWidget && resizeMode) 1f else 0f)
                                .graphicsLayer {
                                    val base = if (held) rest else position.value
                                    val carry = if (held) carriedBy * (if (settling) settle else 1f) else Offset.Zero
                                    translationX = base.x + carry.x
                                    translationY = base.y + carry.y
                                    scaleX = lift
                                    scaleY = lift
                                    alpha = if (held && !settling) DraggedAlpha else 1f
                                }
                                .width(widthFor(span))
                                .height(heightFor(span)),
                        ) {
                            if (isWidget) {
                                RecentPhotosWidget(
                                    span = span,
                                    photos = recentPhotos,
                                    onLoadMore = loadMorePhotos,
                                    count = photoCount,
                                    editing = resizeMode,
                                    onCount = onPhotoCount,
                                    hasAccess = photoAccess,
                                    enabled = !busy,
                                    selected = selectedPhotos,
                                    modifier = Modifier.fillMaxSize().then(carryGesture),
                                    carry = photoCarry,
                                    onPhoto = { uri ->
                                        when {
                                            !armed -> armed = true
                                            resizeMode -> resizeMode = false
                                            else -> onPickPhoto(uri)
                                        }
                                    },
                                    onRequestAccess = {
                                        if (!armed) {
                                            armed = true
                                        } else {
                                            accessLauncher.launch(photoPermissions())
                                        }
                                    },
                                )
                                if (resizeMode && !held) {
                                    ResizeHandles(
                                        onResizeStart = { resizingNow = true },
                                        onResize = { drag ->
                                            val baseW = with(density) { widthFor(widgetSpan).toPx() }
                                            val baseH = with(density) { heightFor(widgetSpan).toPx() }
                                            val next = clampSpan(
                                                ((baseW + drag.x + TileGap.toPxWith(density)) / cellPitchPx).roundToInt(),
                                                ((baseH + drag.y + TileGap.toPxWith(density)) / rowPitchPx).roundToInt(),
                                            )
                                            if (next != widgetSpan) {
                                                widgetSpan = next
                                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                        onResizeEnd = {
                                            resizingNow = false
                                            currentOnResize(widgetSpan.width, widgetSpan.height)
                                        },
                                    )
                                }
                            } else {
                                AttachmentTile(
                                    tile = tile,
                                    enabled = !busy && (tile.action != PanelAction.EFFECTS || effectsEnabled),
                                    onClick = {
                                        if (!armed) {
                                            armed = true
                                            return@AttachmentTile
                                        }
                                        if (resizeMode) {
                                            resizeMode = false
                                            return@AttachmentTile
                                        }
                                        when {
                                            tile.source != null -> onSelect(tile.source)
                                            tile.action == PanelAction.GAMES -> onGames()
                                            tile.action == PanelAction.RICH_TEXT -> onRichText()
                                            tile.action == PanelAction.EFFECTS -> onEffects()
                                            tile.action == PanelAction.POLL -> onPoll()
                                            tile.action == PanelAction.STICKERS -> onStickers()
                                            else -> onScheduled()
                                        }
                                    },
                                    pillModifier = carryGesture,
                                )
                            }
                        }
                      }
                    }
                }
            }
            LaunchedEffect(busy) {
                if (busy) {
                    settleJob?.cancel()
                    settleJob = null
                    carried = null
                    carriedBy = Offset.Zero
                    settling = false
                    resizeMode = false
                }
            }
        }
    }
}

private fun Dp.toPxWith(density: androidx.compose.ui.unit.Density): Float = with(density) { toPx() }

private fun clampSpan(columns: Int, rows: Int): IntSize {
    val width = columns.coerceIn(1, COLUMNS)
    // One cell wide is only ever one cell tall: there is no layout for a narrow column of pictures.
    return IntSize(width, if (width == 1) 1 else rows.coerceIn(1, WIDGET_MAX_ROWS))
}

/**
 * The handles a held widget shows: a dot on each of its four corners. Each one grows the widget
 * along the two directions it points in — the top-left dot widens it leftward and grows it upward,
 * the bottom-right one the other way, and so on.
 *
 * [onResize] is handed the drag so far, measured from where the span last changed — it answers
 * whether the span moved, and when it did the drag starts counting again from the new size.
 */
@Composable
private fun ResizeHandles(
    onResizeStart: () -> Unit,
    onResize: (Offset) -> Boolean,
    onResizeEnd: () -> Unit,
) {
    val latestResize by rememberUpdatedState(onResize)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, Color.White.copy(alpha = 0.85f), WidgetShape),
    ) {
        listOf(
            Alignment.TopStart to Offset(-1f, -1f),
            Alignment.TopEnd to Offset(1f, -1f),
            Alignment.BottomStart to Offset(-1f, 1f),
            Alignment.BottomEnd to Offset(1f, 1f),
        ).forEach { (alignment, axes) ->
            Box(
                modifier = Modifier
                    .align(alignment)
                    .offset(x = (8 * axes.x).dp, y = (8 * axes.y).dp)
                    .size(32.dp)
                    .pointerInput(axes) {
                        var drag = Offset.Zero
                        detectDragGestures(
                            onDragStart = { drag = Offset.Zero; onResizeStart() },
                            onDrag = { change, amount ->
                                change.consume()
                                drag += Offset(amount.x * axes.x, amount.y * axes.y)
                                if (latestResize(drag)) drag = Offset.Zero
                            },
                            onDragEnd = onResizeEnd,
                            onDragCancel = onResizeEnd,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }
    }
}

/**
 * The latest pictures in the gallery, as a grid on a pane of the panel's glass.
 *
 * Three across and two down at its default square-ish size; a tall one stands them two across, and
 * a long flat one lays all six in a row. A tap puts that picture into the draft.
 */
@Composable
private fun RecentPhotosWidget(
    span: IntSize,
    photos: List<GalleryItem>,
    /** Asked for the next page as the widget is scrolled toward the end of this one. */
    onLoadMore: () -> Unit = {},
    count: Int,
    /** Whether the widget is held up for resizing — the only time its count badge shows. */
    editing: Boolean,
    onCount: (Int) -> Unit,
    hasAccess: Boolean,
    enabled: Boolean,
    /** The pictures already in the draft, shown picked out. */
    selected: Set<Uri>,
    onPhoto: (Uri) -> Unit,
    onRequestAccess: () -> Unit,
    /** The widget's own carry, for a held picture dragged off its preview. */
    carry: GalleryCarry? = null,
    modifier: Modifier = Modifier,
) {
    // Each size has a fixed handful of layouts — see [photoLayouts].
    val layouts = photoLayouts(span)
    val grid = layouts.firstOrNull { it.first == count }?.second ?: layouts.first().second
    val columns = grid.width
    val rows = grid.height
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.35f)
            .padding(6.dp),
    ) {
        if (!hasAccess || photos.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(enabled = enabled && !hasAccess, onClick = onRequestAccess),
            ) {
                Icon(Icons.Outlined.Photo, contentDescription = null, tint = Color.White, modifier = Modifier.size(23.dp))
                Text(
                    if (hasAccess) "No photos" else "Show recent photos",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            return@Box
        }
        // The layout fixes how many columns fit across; past those, the gallery carries on sideways
        // as a free scroll, newest first, a column of [rows] pictures at a time.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val gap = 4.dp
            val columnWidth = (maxWidth - gap * (columns - 1)) / columns
            val stacks = remember(photos, rows) { photos.chunked(rows) }
            // The same, sideways: the widget is a row of columns and runs out at its right-hand end.
            val strip = androidx.compose.foundation.lazy.rememberLazyListState()
            val nearEnd by remember(strip) {
                derivedStateOf {
                    val last = strip.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    last >= strip.layoutInfo.totalItemsCount - WidgetPrefetch
                }
            }
            LaunchedEffect(nearEnd, photos.size) { if (nearEnd && photos.isNotEmpty()) onLoadMore() }
            androidx.compose.foundation.lazy.LazyRow(
                state = strip,
                horizontalArrangement = Arrangement.spacedBy(gap),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(stacks.size) { s ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(gap),
                        modifier = Modifier.width(columnWidth).fillMaxHeight(),
                    ) {
                        repeat(rows) { r ->
                            val item = stacks[s].getOrNull(r)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.08f)),
                            ) {
                                if (item != null) {
                                    GalleryPhoto(
                                        item = item,
                                        selected = item.uri in selected,
                                        enabled = enabled,
                                        onClick = { onPhoto(item.uri) },
                                        carry = carry,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!editing) return@Box
        // The count, as a small badge over the top-right picture; tapping it offers the choices.
        var choosing by remember { mutableStateOf(false) }
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            Text(
                "$count",
                fontSize = 11.sp,
                color = Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(enabled = enabled) { choosing = true }
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
            AppDropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                layouts.map { it.first }.sorted().forEach { n ->
                    AppMenuItem(
                        text = { Text(if (n == 1) "1 photo" else "$n photos") },
                        onClick = {
                            choosing = false
                            onCount(n)
                        },
                    )
                }
            }
        }
    }
}

private fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Whether pictures are readable in full but clips are not — a grant from before videos were shown. */
private fun lacksVideoAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) !=
        PackageManager.PERMISSION_GRANTED

private fun hasPhotoAccess(context: Context): Boolean = photoPermissions().any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
} || (Build.VERSION.SDK_INT >= 34 &&
    ContextCompat.checkSelfPermission(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") ==
    PackageManager.PERMISSION_GRANTED)

/**
 * One page of the gallery: up to [limit] pictures and clips, newest first, starting [offset] in.
 *
 * Read from the files table rather than the images one, so the two kinds come back in the one order
 * they were taken in — and with a clip's length alongside its id, for the badge on its tile.
 *
 * Paged on the cursor rather than in SQL. `LIMIT`/`OFFSET` in the sort argument is a provider
 * detail that several OEM builds quietly ignore, and the `QUERY_ARG` bundle that replaces it is
 * honoured on some of them and not others — whereas a cursor is windowed by the framework whatever
 * is underneath it, so stepping to a position reads only the rows around it. The projection is a
 * few integer columns, which is what keeps that cheap.
 *
 * A page shorter than [limit] means the end of the gallery, which is how the caller knows to stop
 * asking.
 */
private fun queryRecentPhotos(context: Context, limit: Int, offset: Int = 0): List<GalleryItem> = runCatching {
    val image = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
    val video = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
    context.contentResolver.query(
        MediaStore.Files.getContentUri("external"),
        // `duration` by name: the column is on the files table on every version, but only has a
        // constant of its own there from Q.
        arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            "duration",
            MediaStore.Files.FileColumns.DATE_ADDED,
        ),
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($image, $video)",
        null,
        "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val durationColumn = cursor.getColumnIndex("duration")
        val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
        // `moveToPosition` on the row *before* the page, so the loop's own `moveToNext` lands on
        // the first one. Past the end it simply answers false and the page comes back empty.
        if (offset > 0 && !cursor.moveToPosition(offset - 1)) return@use emptyList()
        buildList {
            while (size < limit && cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                // The column is in seconds.
                val addedMs = cursor.getLong(addedColumn) * 1000L
                // Addressed through their own collections rather than the files table: those are
                // what the resolver names a type for, which the thumbnailer and the staging copy
                // both go by.
                add(
                    if (cursor.getInt(typeColumn) == video) {
                        GalleryItem(
                            uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                            durationMs = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L,
                            addedMs = addedMs,
                        )
                    } else {
                        GalleryItem(
                            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                            addedMs = addedMs,
                        )
                    },
                )
            }
        }
    }.orEmpty()
}.getOrDefault(emptyList())

/**
 * Which cell along one axis a carried tile is standing in, given the cell it is in now.
 *
 * [DragHysteresis] is a dead band either side of every boundary, so a still hand resting on one does
 * not shuttle the tile back and forth across it.
 */
private fun cellUnder(centre: Float, pitch: Float, current: Int): Int {
    if (pitch <= 0f) return current
    val raw = centre / pitch
    val cell = floor(raw).toInt()
    if (cell == current) return current
    val into = raw - cell
    return when {
        cell > current && into < DragHysteresis -> current
        cell < current && into > 1f - DragHysteresis -> current
        else -> cell
    }
}

/** How far into a new cell the carried tile must reach before the grid commits to it. */
private const val DragHysteresis = 0.18f

/** How close to the card's edge a carried tile must be held for the grid to creep, in rows. */
private const val AutoScrollEdge = 0.55f

/** How fast it creeps, in pixels per frame. Slow enough to stop on the row you meant. */
private const val AutoScrollStep = 9f

@Composable
private fun AttachmentTile(
    tile: Tile,
    enabled: Boolean,
    onClick: () -> Unit,
    /** The pick-up-and-carry gesture, on the pill alongside its tap detector — see `armed`. */
    pillModifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().alpha(if (enabled) 1f else 0.35f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .frostedFill(PillShape, blur = ControlBlur, tint = ControlTint)
                .then(pillModifier)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tile.icon, contentDescription = tile.label, tint = Color.White, modifier = Modifier.size(23.dp))
        }
        Text(
            tile.label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * One picture on either gallery, and whether it is in the draft already.
 *
 * Tapped, it goes in; tapped again it comes back out — so the grid is a set of choices you can
 * change your mind about rather than a list of things you have already sent. A chosen picture backs
 * off a little and takes a tick in the corner: the same two signals a photo grid gives anywhere
 * else, and enough to read at a glance across a page of them.
 */
@Composable
private fun GalleryPhoto(
    item: GalleryItem,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    /** The photos widget's carry, handed a hold dragged off its preview; null on the full gallery. */
    carry: GalleryCarry? = null,
    /** The tile's rounding, which the full gallery tightens as its tiles get smaller. */
    corner: Dp = 12.dp,
    /** The smallest zoom: clips show their length alone, without the play mark. */
    compactBadge: Boolean = false,
    /** Told when this tile's preview is up, so the grid doesn't treat its pinch as a zoom step. */
    onHoldChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) SelectedPhotoScale else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "photoPick",
    )
    // Held, it opens large; let go, it closes — see [galleryHold].
    val hold = remember { GalleryHoldState() }
    GalleryHoldPreview(item, hold)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(Color.White.copy(alpha = 0.08f))
            .semantics {
                role = Role.Button
                onClick {
                    onClick()
                    true
                }
            }
            .galleryHold(item = item, state = hold, enabled = enabled, onClick = onClick, carry = carry, onHoldChange = onHoldChange),
    ) {
        // The badge backs off with the picture, so a chosen clip still says it is one.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            AsyncImage(
                model = item.uri,
                contentDescription = if (item.isVideo) "Recent video" else "Recent photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            item.durationMs?.let { VideoBadge(it, modifier = Modifier.align(Alignment.BottomStart), compact = compactBadge) }
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .size(SelectedTickSize)
                    // The disc behind the tick, so it reads on a light picture as well as a dark
                    // one without a shadow over the photograph itself.
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** How far a chosen picture backs off, and the tick that says so. */
private const val SelectedPhotoScale = 0.86f
private val SelectedTickSize = 20.dp

/** How many tiles stand across the card's width in `GalleryMode.FULL`, in two rows. */
private const val FULL_TILE_COLUMNS = 3

/** How many rows of them there are. The rows fill downwards first, so index = column * rows + row. */
private const val FULL_TILE_ROWS = 2

/**
 * How far out the full gallery is zoomed: how many pictures run across it, and what they are grouped
 * by. A pinch steps between these and nothing in between.
 */
private enum class GalleryZoom(val columns: Int, val byMonth: Boolean, val corner: Dp) {
    LARGE(columns = 3, byMonth = false, corner = 12.dp),
    MEDIUM(columns = 4, byMonth = false, corner = 10.dp),
    SMALL(columns = 7, byMonth = true, corner = 6.dp),
}

/** One cell of the full gallery: a picture, or the date above the group of them that follows. */
private sealed interface GalleryEntry {
    val key: String
}

private class GalleryHeader(val label: String) : GalleryEntry {
    override val key = HeaderKeyPrefix + label
}

private class GalleryCell(val item: GalleryItem) : GalleryEntry {
    override val key = item.uri.toString()
}

/** Ahead of a header's key, which no picture's `content://` address can begin with. */
private const val HeaderKeyPrefix = "h:"

/** The buttons' row, by key. */
private const val TilesKey = "tiles"

/**
 * The pictures with a date ahead of each day's, or each month's, worth.
 *
 * They arrive newest first, so a group is a run and a header goes in wherever the day changes — the
 * date is only formatted there, not once a picture.
 */
private fun galleryEntries(photos: List<GalleryItem>, byMonth: Boolean): List<GalleryEntry> {
    val calendar = Calendar.getInstance()
    val format = SimpleDateFormat(if (byMonth) "MMMM yyyy" else "MMM d, yyyy", Locale.getDefault())
    var group = -1
    return buildList(photos.size + 16) {
        for (photo in photos) {
            calendar.timeInMillis = photo.addedMs
            val within = if (byMonth) calendar.get(Calendar.MONTH) else calendar.get(Calendar.DAY_OF_YEAR)
            val at = calendar.get(Calendar.YEAR) * 1000 + within
            if (at != group) {
                group = at
                add(GalleryHeader(format.format(calendar.time)))
            }
            add(GalleryCell(photo))
        }
    }
}

/**
 * A two-finger pinch, counted out in steps: `1` each time the fingers have closed by [PinchStep],
 * `-1` each time they have opened by it.
 *
 * Watched on the way down to the grid, and everything from the second finger on is taken there — so
 * neither the scroll nor a picture's hold sees a pinch as anything of theirs, through to the last
 * finger lifting.
 */
private fun Modifier.pinchSteps(enabled: Boolean, onStep: (Int) -> Unit): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        // The spread the next step is measured from; zero while there is only one finger down.
        var base = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val spread = (pressed[0].position - pressed[1].position).getDistance()
                pinching = true
                when {
                    base <= 0f -> base = spread
                    spread < base * PinchStep -> {
                        onStep(1)
                        base = spread
                    }
                    spread > base / PinchStep -> {
                        onStep(-1)
                        base = spread
                    }
                }
            } else {
                base = 0f
            }
            if (pinching) event.changes.forEach { it.consume() }
            if (pressed.isEmpty()) break
        }
    }
}

/** How far the fingers have to close, as a share of where they were, for the gallery to step out. */
private const val PinchStep = 0.78f

/** The gap between the pictures in that gallery. */
private val GalleryGap = 4.dp

/**
 * The grab bar along the card's top edge, and the strip it sits in.
 *
 * The same [TileInset] the tiles keep at their sides, so the card's margin reads as one number all
 * the way round rather than a wider band above the first row than beside it.
 */
private val HandleStrip = TileInset
private val HandleWidth = 36.dp
private val HandleThickness = 4.dp

/**
 * The "+" card with the gallery given the whole of it below the tiles — `GalleryMode.FULL`.
 *
 * Scrolling the pictures spends the room it is given in a fixed order and gives it back in the
 * reverse one: first the card grows out of the keyboard's slot towards the send bar, then the tiles
 * scroll up off the top of it — whole, at the speed of the finger, rather than shrinking — and only
 * then do the pictures themselves move. Coming back to the top of the gallery puts the tiles back
 * and then settles the card.
 *
 * The bar along the top edge is the way down that does not bring the tiles back with it: it only
 * ever gives back the card's own height, so a gallery you have scrolled into stays where it is.
 */
@Composable
private fun FullGalleryPanel(
    tiles: List<Tile>,
    onReorder: (List<String>) -> Unit,
    surface: Color,
    height: Dp,
    photos: List<GalleryItem>,
    /** Asked for the next page as the end of this one comes into view. See [AttachmentPanel]. */
    onLoadMorePhotos: () -> Unit = {},
    hasAccess: Boolean,
    busy: Boolean,
    effectsEnabled: Boolean,
    expansion: Dp,
    maxExpansion: Dp,
    onExpand: (Dp) -> Dp,
    collapseRoom: Dp,
    onPullRelease: (Float) -> Unit,
    resetSignal: Int,
    onScrolledChange: (Boolean) -> Unit,
    selected: Set<Uri>,
    onRequestAccess: () -> Unit,
    onPhoto: (Uri) -> Unit,
    onTile: (Tile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val latestExpand by rememberUpdatedState(onExpand)
    val latestReorder by rememberUpdatedState(onReorder)
    val room by rememberUpdatedState(maxExpansion - expansion)
    val taken by rememberUpdatedState(expansion)
    /** How far down there is left to go with the grab bar: the card's own height, and then off. */
    val pullRoom by rememberUpdatedState(expansion + collapseRoom)

    // The arrangement being dragged, which the card owns until the finger lifts and it is written
    // back to settings. Seeded from what settings says, and re-seeded whenever that changes under us.
    var order by remember { mutableStateOf(tiles) }
    /**
     * The tile in the hand, by id, or null when the rows are at rest.
     *
     * By id and not by index on purpose. The arrangement changes *under* the drag — that is what
     * the drag is for — and a gesture keyed to a position in a list that is being reordered was
     * torn down and rebuilt mid-press, which ended the long press the moment it moved anything.
     */
    var draggingId by remember { mutableStateOf<String?>(null) }
    // Never while a tile is in the hand: the drop writes the new arrangement to settings, which
    // comes straight back down here and would otherwise reset the very drag that caused it.
    LaunchedEffect(tiles) { if (draggingId == null) order = tiles }

    val tileRowsHeight = TileHeight * FULL_TILE_ROWS + TileGap * (FULL_TILE_ROWS - 1)
    /** The rows' own state, for the carry; the gallery's is [galleryState]. */
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    /**
     * The one scroll in this card.
     *
     * The buttons are the gallery's first item, spanning the whole width, and the pictures follow
     * underneath them — siblings in a single grid rather than two panes stacked on one another. So
     * they move because the same list moved, at the same speed, with nothing to keep in step.
     */
    val galleryState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    /** Read by the carry gesture, which must not be rebuilt when the arrangement changes. */
    val latestOrder by rememberUpdatedState(order)

    var zoomIndex by rememberSaveable { mutableIntStateOf(0) }
    /** A tile's preview is up: a pinch then belongs to it, not to the grid's zoom steps. */
    var previewHeld by remember { mutableStateOf(false) }
    val zoom = GalleryZoom.values()[zoomIndex]
    val entries = remember(photos, zoom.byMonth) { galleryEntries(photos, zoom.byMonth) }
    /**
     * The picture at the top of the grid when a pinch stepped it, to be put back there.
     *
     * The grid holds its place by key on its own, but the thing at the top is as often a date as a
     * picture, and a date does not survive going from days to months.
     */
    var zoomAnchor by remember { mutableStateOf<String?>(null) }
    val stepZoom: (Int) -> Unit = { by ->
        val next = (zoomIndex + by).coerceIn(0, GalleryZoom.values().lastIndex)
        if (next != zoomIndex) {
            // At the top the buttons stay where they are, and there is nothing to put back.
            zoomAnchor = if (galleryState.firstVisibleItemIndex == 0) {
                null
            } else {
                galleryState.layoutInfo.visibleItemsInfo
                    .firstNotNullOfOrNull { info ->
                        (info.key as? String)?.takeIf { it != TilesKey && !it.startsWith(HeaderKeyPrefix) }
                    }
            }
            zoomIndex = next
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    LaunchedEffect(zoom) {
        val key = zoomAnchor ?: return@LaunchedEffect
        zoomAnchor = null
        val at = entries.indexOfFirst { it.key == key }
        if (at < 0) return@LaunchedEffect
        // With its date, if it is the first of its group. One on for the buttons' row.
        val top = if (entries.getOrNull(at - 1) is GalleryHeader) at - 1 else at
        galleryState.scrollToItem(top + 1)
    }

    val scrolled by remember {
        derivedStateOf {
            galleryState.firstVisibleItemIndex > 0 || galleryState.firstVisibleItemScrollOffset > 0
        }
    }
    LaunchedEffect(scrolled) { onScrolledChange(scrolled) }

    /**
     * Spends scroll on the card's own height, and answers with the pixels it used.
     *
     * [down] is how far it may go the other way: the height it has taken, which is all a scroll
     * down through the pictures ever gives back — or that plus the card itself, for a pull, which
     * may carry on past the resting height and take the card off the bottom of the screen.
     */
    fun stretchBy(dy: Float, down: Dp): Float {
        val wanted = with(density) { dy.toDp() }
        val limited = when {
            wanted < 0.dp -> maxOf(wanted, -room)
            else -> minOf(wanted, down)
        }
        if (limited == 0.dp) return 0f
        return with(density) { -latestExpand(-limited).toPx() }
    }

    val latestRelease by rememberUpdatedState(onPullRelease)

    // The card's height is spent before the list moves and given back after it has stopped: scroll
    // up and the card grows towards the send bar first, then the buttons and the pictures scroll
    // away together; scroll down and the list comes back to the top before the card settles — and
    // past the top, on into the pull that takes it off the screen.
    val galleryScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0f) {
                    Offset(0f, stretchBy(available.y, taken))
                } else {
                    Offset.Zero
                }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = if (available.y > 0f) {
                Offset(0f, stretchBy(available.y, pullRoom))
            } else {
                Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity {
                if (taken >= 0.dp) return androidx.compose.ui.unit.Velocity.Zero
                latestRelease(available.y)
                return available
            }
        }
    }

    // The grab bar's own: the card's height and nothing else, so pulling it down never scrolls the
    // list back up. Past the resting height it goes on into [collapseRoom], and what happens then
    // is the release's business — see [onPullRelease].
    val handleDrag = rememberDraggableState { delta -> stretchBy(delta, pullRoom) }

    // Back, while the gallery is standing somewhere: the pictures go home, and the card settles
    // under the caller — which is what bumped [resetSignal] — with the card itself left open.
    LaunchedEffect(resetSignal) {
        if (resetSignal == 0) return@LaunchedEffect
        galleryState.animateScrollToItem(0)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(SheetInset)
            .frostedFill(SheetShape, tint = surface.copy(alpha = PanelSurfaceTint)),
    ) {
        Column(modifier = Modifier.fillMaxSize().alpha(if (busy) 0.4f else 1f)) {
            // The grab bar, in the strip the card keeps above everything else. It is only there
            // once the gallery has been scrolled into — at rest the buttons are the top of the card
            // and there is nothing for it to put away. The strip keeps its height either way, so
            // nothing below it moves as the bar fades in.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HandleStrip)
                    .draggable(
                        state = handleDrag,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity -> onPullRelease(velocity) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val handleAlpha by animateFloatAsState(
                    targetValue = if (scrolled) 1f else 0f,
                    label = "galleryHandle",
                )
                Box(
                    modifier = Modifier
                        .width(HandleWidth)
                        .height(HandleThickness)
                        .alpha(handleAlpha)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(Color.White.copy(alpha = 0.55f)),
                )
            }

            if (!hasAccess) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(WidgetShape)
                        .clickable(enabled = !busy, onClick = onRequestAccess),
                ) {
                    Icon(
                        Icons.Outlined.Photo,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(23.dp),
                    )
                    Text(
                        "Show recent photos",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                return@Column
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                // Three across the room the grid's own margin leaves, in whole pixels, with the
                // remainder spent on the gaps rather than left to round its way into a sliver of a
                // fourth tile at the edge: three tiles and two gaps add up to exactly the width the
                // buttons' row is given, so the fourth begins past the end of it and is never drawn.
                val insetPx = with(density) { TileInset.toPx() }
                val spanPx = with(density) { maxWidth.toPx() } - insetPx * 2
                val gapsPx = with(density) { TileGap.toPx() } * (FULL_TILE_COLUMNS - 1)
                val tileWidthPx = floor((spanPx - gapsPx) / FULL_TILE_COLUMNS)
                val tileWidth = with(density) { tileWidthPx.toDp() }
                val tileGap = with(density) {
                    ((spanPx - tileWidthPx * FULL_TILE_COLUMNS) / (FULL_TILE_COLUMNS - 1)).toDp()
                }

                val tileReorder = rememberReorderableLazyGridState(
                    gridState,
                    scrollMoveMode = ScrollMoveMode.INSERT,
                ) { from, to ->
                    order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }

                // The bottom of what has been read is not the bottom of the gallery. Watched a
                // screenful ahead of the last picture, so the next page is already in by the time
                // the scroll reaches where it goes — the alternative is a wall the finger hits and
                // a grid that grows under it a moment later.
                val nearEnd by remember(galleryState) {
                    derivedStateOf {
                        val last = galleryState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        // The same few rows ahead however many pictures a row holds.
                        val ahead = GalleryPrefetch * GalleryZoom.values()[zoomIndex].columns /
                            GalleryZoom.LARGE.columns
                        last >= galleryState.layoutInfo.totalItemsCount - ahead
                    }
                }
                // Nothing to be at the end of until the first page is in — without this an empty grid
                // reads as a grid scrolled to its bottom and asks for page two before page one.
                LaunchedEffect(nearEnd, photos.size) { if (nearEnd && photos.isNotEmpty()) onLoadMorePhotos() }

                LazyVerticalGrid(
                    state = galleryState,
                    columns = GridCells.Fixed(zoom.columns),
                    horizontalArrangement = Arrangement.spacedBy(GalleryGap),
                    verticalArrangement = Arrangement.spacedBy(GalleryGap),
                    // The card's margin at the sides, and nothing at the top: the grab bar's strip
                    // is the gap above the buttons, and the pictures run out to the bottom edge.
                    contentPadding = PaddingValues(horizontal = TileInset),
                    // Held still while a tile is carried, so the list cannot scroll out from under
                    // the tile the finger is moving.
                    userScrollEnabled = draggingId == null,
                    modifier = Modifier
                        .fillMaxSize()
                        .pinchSteps(enabled = !busy && draggingId == null && !previewHeld, onStep = stepZoom)
                        .nestedScroll(galleryScroll),
                ) {
                    // The buttons: the gallery's first row, the full width of it.
                    item(key = TilesKey, span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            LazyHorizontalGrid(
                                state = gridState,
                                rows = GridCells.Fixed(FULL_TILE_ROWS),
                                horizontalArrangement = Arrangement.spacedBy(tileGap),
                                verticalArrangement = Arrangement.spacedBy(TileGap),
                                modifier = Modifier.fillMaxWidth().height(tileRowsHeight),
                            ) {
                                items(order, key = { it.id }) { tile ->
                                    // The library owns the carry — where the tile is drawn, which
                                    // slot the finger is over, and walking the rows at the edges —
                                    // so the rows only ever see a list that has been reordered.
                                    ReorderableItem(tileReorder, key = tile.id) { held ->
                                        Box(
                                            modifier = Modifier
                                                .width(tileWidth)
                                                .fillMaxHeight()
                                                .graphicsLayer {
                                                    val lift = if (held) DraggedScale else 1f
                                                    scaleX = lift
                                                    scaleY = lift
                                                    alpha = if (held) DraggedAlpha else 1f
                                                }
                                                .longPressDraggableHandle(
                                                    enabled = !busy,
                                                    onDragStarted = {
                                                        draggingId = tile.id
                                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    },
                                                    onDragStopped = {
                                                        draggingId = null
                                                        latestReorder(latestOrder.map { it.id })
                                                    },
                                                ),
                                        ) {
                                            AttachmentTile(
                                                tile = tile,
                                                enabled = !busy &&
                                                    (tile.action != PanelAction.EFFECTS || effectsEnabled),
                                                onClick = { onTile(tile) },
                                            )
                                        }
                                    }
                                }
                            }
                            // The buttons' own gap above the pictures, less the row spacing the
                            // grid already puts under every item.
                            Spacer(modifier = Modifier.height(TileInset - GalleryGap))
                        }
                    }
                    items(
                        entries,
                        key = { it.key },
                        span = { if (it is GalleryHeader) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                        contentType = { it is GalleryHeader },
                    ) { entry ->
                        when (entry) {
                            is GalleryHeader -> Text(
                                entry.label,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                modifier = Modifier.animateItem().padding(start = 2.dp, top = 10.dp, bottom = 2.dp),
                            )
                            is GalleryCell -> GalleryPhoto(
                                item = entry.item,
                                selected = entry.item.uri in selected,
                                enabled = !busy,
                                onClick = { onPhoto(entry.item.uri) },
                                corner = zoom.corner,
                                compactBadge = zoom.byMonth,
                                onHoldChange = { previewHeld = it },
                                // Glides to its new place when a pinch regroups the grid.
                                modifier = Modifier.animateItem().fillMaxWidth().aspectRatio(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
