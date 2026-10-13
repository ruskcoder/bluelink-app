package com.ruskcoder.messages.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.ui.theme.LocalSettings
import kotlin.math.roundToInt

/**
 * One container for every menu in the app but one — the app bar's overflow, the selection bar's,
 * the member row's, the chat list's peek, and the long-press menu on a Material bubble.
 *
 * All of them are the card iOS puts under a long press: a dark film over a blurred copy of the
 * screen, with the rows held in off its edges so the highlight is a rounded shape of its own rather
 * than a full-bleed band that squares off the corners. The card is dark whatever the app's theme
 * is, the way iOS's context menus are — so its colours are literals here rather than roles out of
 * the colour scheme.
 *
 * The exception is the menu on an Apple bubble, which keeps its own card. See [BubbleMenu].
 */
object AppMenu {
    /** Tighter than a Material menu's corner, which is what makes the card read as iOS's. */
    val shape: Shape = RoundedCornerShape(14.dp)

    /** How far a row sits in from the card's edge. */
    val itemInset = 6.dp

    /**
     * The card's radius less the inset, which is the one radius that leaves an even gap all the
     * way around a corner. Any other number and the two curves visibly disagree.
     */
    val itemShape: Shape = RoundedCornerShape(8.dp)

    /**
     * Shorter than Material's 48dp row. These menus are read as a list rather than reached for as
     * separate targets, and the drag that runs down them wants them within one thumb's travel.
     */
    val itemHeight = 40.dp

    /**
     * The film over the blur — black, and the knob for how black: raise the alpha toward `FF` for a
     * more solid card, lower it to let more of the blur through.
     */
    val film = Color(0x99000000)

    /** The hairline that sets a destructive action apart from the rest. */
    val hairline = Color(0x1AFFFFFF)

    /** What the finger is over, as it runs down the card. */
    val highlight = Color(0x24FFFFFF)

    /** Light on dark, always. */
    val contentColor = Color.White

    /** systemRed on dark, which is what iOS tints a destructive context-menu row. */
    val destructiveColor = Color(0xFFFF453A)

    /** Wide enough that two-word labels don't wrap, narrow enough to stay a menu and not a sheet. */
    val minWidth = 216.dp
    val maxWidth = 280.dp

    val blurRadius = 30.dp

    /** Decelerating, so the card arrives at full size rather than easing out of it. */
    val entranceEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    const val ENTRANCE_MS = 140

    fun itemColors(color: Color = contentColor): AppMenuItemColors =
        AppMenuItemColors(color, color.copy(alpha = DISABLED_ALPHA))

    /** For the one action in a menu that destroys something. */
    fun destructiveItemColors(): AppMenuItemColors = itemColors(destructiveColor)
}

@Immutable
class AppMenuItemColors(val content: Color, val disabled: Color) {
    internal fun color(enabled: Boolean) = if (enabled) content else disabled
}

private const val DISABLED_ALPHA = 0.38f

/** How far the card is held off the anchor, and off the edges of the window. */
private val MenuGap = 4.dp
private val WindowMargin = 8.dp

/** A touch of dimming, so the card is read as being over the screen rather than in it. */
private val Scrim = Color(0x40000000)

/**
 * How the highlight travels between rows. A spring rather than a tween: it eases out of the row it
 * is leaving and settles into the next instead of running at one speed between the two, which is
 * what makes a snap read as smooth rather than as abrupt. Critically damped — it arrives and stops,
 * with none of the overshoot that made it look like it was hunting for the row.
 */
private val SlideSpec =
    spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/** The fade, which has no distance to cover and so has no reason to be a spring. */
private const val FADE_MS = 120

// ---------------------------------------------------------------------------------------------
// The host
// ---------------------------------------------------------------------------------------------

/**
 * Why a menu is not a `DropdownMenu` here.
 *
 * A blur in Compose can only be applied to content a layer draws itself, so blurring what is
 * *behind* something means recording the screen into a [ChatBackdrop] and replaying that recording
 * underneath — the trick the composer and the bubble menu already use. A recording belongs to the
 * window that made it (`LocalGraphicsContext` comes off the owner), and a `DropdownMenu` lives in
 * a popup window of its own, so it can never draw this window's copy of the screen.
 *
 * So menus are drawn in the window instead: [AppMenuHost] wraps the app, records what is under it
 * while a menu is open, and puts the open menu above it, positioned against the anchor's bounds
 * the way the popup used to position itself.
 */
@Stable
class AppMenuHostState internal constructor() {
    internal var open by mutableStateOf<OpenAppMenu?>(null)
        private set

    /** Separate from [open] so the card can animate out before it leaves the composition. */
    internal var visible by mutableStateOf(false)
        private set

    /** Where the host sits in the window, to read anchor bounds into its own coordinates. */
    internal var originInWindow by mutableStateOf(Offset.Zero)

    internal fun show(menu: OpenAppMenu) {
        open = menu
        visible = true
    }

    internal fun hide(menu: OpenAppMenu) {
        if (open === menu) visible = false
    }

    /** Called once the exit has run. Guarded, in case another menu opened in the meantime. */
    internal fun release(menu: OpenAppMenu) {
        if (open === menu && !visible) open = null
    }
}

@Stable
internal class OpenAppMenu {
    var anchorInWindow by mutableStateOf(Rect.Zero)
    var onDismissRequest: () -> Unit by mutableStateOf({})
    var content: @Composable ColumnScope.() -> Unit by mutableStateOf({})
}

val LocalAppMenuHost = compositionLocalOf<AppMenuHostState?> { null }

/**
 * The finger that opened a menu, handed from the thing it long-pressed to the menu that appeared
 * under it.
 *
 * A press belongs to the pointer-input nodes that were under it when it went down. A menu that
 * appears *because* of that press was not there to be hit, so it never receives the rest of the
 * gesture — which is why holding a bubble and sliding onto its menu would otherwise do nothing
 * until you lifted and pressed again. The node that took the long press keeps reporting the
 * finger here instead, in window coordinates, and any menu that is open reads it as its own.
 */
@Stable
class MenuDragRelay {
    internal var state by mutableStateOf(MenuDragState())
        private set

    private var armedAt = Offset.Zero
    private var slopPx = 0f

    /**
     * The long press fired: from here the finger belongs to whatever it opened.
     *
     * [slopPx] is how far it has to travel before it counts. The menu comes up *under* the finger,
     * so without this a long press that is simply released — the ordinary way of opening a menu to
     * look at it — would land on whichever row happened to appear beneath the thumb.
     */
    fun arm(at: Offset, slopPx: Float) {
        armedAt = at
        this.slopPx = slopPx
        val next = state.seq + 1
        state = MenuDragState(at = at, down = true, seq = next, armSeq = next)
    }

    fun move(at: Offset) {
        if (!state.down) return
        // One-way: once it has travelled far enough the press is live, and coming back to rest
        // near where it started does not put it to sleep again.
        val engaged = state.engaged || (at - armedAt).getDistance() >= slopPx
        state = state.copy(at = at, engaged = engaged, seq = state.seq + 1)
    }

    /** Lifted, or cancelled. Dropping [MenuDragState.down] is what tells the menu to commit. */
    fun end() {
        if (state.down) state = state.copy(down = false, seq = state.seq + 1)
    }
}

/**
 * Where the finger is and whether it is still down. [seq] is what keeps a move and the lift that
 * follows it in the same frame from being conflated into one reading — without it a fast tap onto
 * a row could arrive as a lift with no move in front of it, and select nothing.
 */
@Immutable
internal data class MenuDragState(
    val at: Offset = Offset.Zero,
    val down: Boolean = false,
    val seq: Int = 0,
    /** Whether the finger has travelled far enough for the menu to take it seriously. */
    val engaged: Boolean = false,
    /**
     * When the press was armed. A menu only answers to a finger that was handed over *before* the
     * menu existed, because that is the only finger that opened it — one armed later belongs to
     * something else on screen. Without this, long-pressing a message inside the chat list's peek
     * would drive the actions card already sitting under it, and a drag could delete the chat.
     */
    val armSeq: Int = 0,
)

val LocalMenuDragRelay = compositionLocalOf<MenuDragRelay?> { null }


/**
 * Wraps the app so every menu under it has somewhere to be drawn. One of these, around the
 * navigation host.
 *
 * The screen is only recorded while a menu is actually open: an offscreen pass over the whole
 * window is not something to pay for on every idle frame.
 */
@Composable
fun AppMenuHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { AppMenuHostState() }
    val relay = remember { MenuDragRelay() }
    val backdrop = rememberChatBackdrop()
    val recording = if (state.open != null) backdrop else null

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { state.originInWindow = it.positionInWindow() },
    ) {
        Box(modifier = Modifier.fillMaxSize().chatBackdropSource(recording)) {
            CompositionLocalProvider(
                LocalAppMenuHost provides state,
                LocalMenuDragRelay provides relay,
            ) { content() }
        }
        state.open?.let { AppMenuOverlay(state, it, backdrop) }
    }
}

@Composable
private fun AppMenuOverlay(state: AppMenuHostState, menu: OpenAppMenu, backdrop: ChatBackdrop?) {
    val entrance = remember(menu) { Animatable(0f) }
    LaunchedEffect(menu, state.visible) {
        entrance.animateTo(
            targetValue = if (state.visible) 1f else 0f,
            animationSpec = tween(AppMenu.ENTRANCE_MS, easing = AppMenu.entranceEasing),
        )
        state.release(menu)
    }

    BackHandler(enabled = state.visible) { menu.onDismissRequest() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The popup dismissed itself on an outside tap; in the window that has to be caught.
            // A press that lands on the card consumes itself, so this only ever sees the misses.
            .pointerInput(menu) { detectTapGestures { menu.onDismissRequest() } }
            .background(Scrim.copy(alpha = Scrim.alpha * entrance.value)),
    ) {
        // A plain `Layout` rather than an offset: the card has to be measured before it can be
        // told whether it fits below the anchor, and this way that happens in the same pass it is
        // placed in, instead of a frame later.
        Layout(
            content = {
                Box(
                    modifier = Modifier.graphicsLayer {
                        alpha = entrance.value
                        scaleX = 0.8f + 0.2f * entrance.value
                        scaleY = 0.8f + 0.2f * entrance.value
                        // Out of the corner nearest the button that opened it.
                        transformOrigin = TransformOrigin(1f, 0f)
                    },
                ) {
                    AppMenuCard(
                        backdrop = backdrop,
                        modifier = Modifier.widthIn(min = AppMenu.minWidth, max = AppMenu.maxWidth),
                        content = menu.content,
                    )
                }
            },
        ) { measurables, constraints ->
            val card = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val gap = MenuGap.roundToPx()
            val margin = WindowMargin.roundToPx()
            val anchor = menu.anchorInWindow.translate(-state.originInWindow)

            // Ends flush with the anchor, the way an overflow menu hangs off its button.
            val maxX = (constraints.maxWidth - card.width - margin).coerceAtLeast(margin)
            val x = (anchor.right.roundToInt() - card.width).coerceIn(margin, maxX)

            // Below the anchor if it fits, above it if it doesn't.
            val below = anchor.bottom.roundToInt() + gap
            val y =
                if (below + card.height + margin <= constraints.maxHeight) below
                else (anchor.top.roundToInt() - gap - card.height)
                    .coerceIn(margin, (constraints.maxHeight - card.height).coerceAtLeast(margin))

            layout(constraints.maxWidth, constraints.maxHeight) { card.place(x, y) }
        }
    }
}

/**
 * A menu hanging off whatever it is called next to — the same arguments a `DropdownMenu` takes,
 * and the same anchoring, but drawn in the window rather than in a popup so it can be frosted.
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalAppMenuHost.current
    if (host == null) {
        // No host above us — a preview, most likely. The popup can't be frosted, but it can at
        // least be the same card.
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            shape = AppMenu.shape,
            containerColor = AppMenu.film.overBlack(),
            modifier = modifier,
        ) {
            CompositionLocalProvider(LocalContentColor provides AppMenu.contentColor) { content() }
        }
        return
    }

    val menu = remember { OpenAppMenu() }
    SideEffect {
        menu.onDismissRequest = onDismissRequest
        menu.content = content
    }
    // A zero-size marker standing where the popup used to. Material anchors a menu to its parent's
    // bounds, so reading the parent's keeps every menu opening exactly where it did before.
    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            menu.anchorInWindow = (coordinates.parentLayoutCoordinates ?: coordinates).boundsInWindow()
        },
    )
    LaunchedEffect(expanded) { if (expanded) host.show(menu) else host.hide(menu) }
}

// ---------------------------------------------------------------------------------------------
// Press and drag
// ---------------------------------------------------------------------------------------------

/**
 * A menu you can work without lifting your finger: press, and the highlight appears under your
 * thumb; drag, and it slides and settles onto whichever row you are over; let go, and that row is
 * the one you picked.
 *
 * This is why the rows have no `clickable` of their own. One gesture on the card owns the whole
 * press — a per-row clickable would end the moment the finger left the row it started on, which is
 * exactly the movement this is built around. A tap is simply the short case: press and release
 * without going anywhere.
 */
@Stable
internal class AppMenuGesture {
    val rows = mutableStateListOf<AppMenuRowState>()

    /** The row under the finger, or null while it is between rows or off the card. */
    var pressed by mutableStateOf<AppMenuRowState?>(null)

    /** The card's own coordinates, which pointer positions and row bounds are both read against. */
    var cardCoordinates: LayoutCoordinates? = null

    /** The finger's last position in the card's space, so a ripple can start where it actually is. */
    var pointerInCard by mutableStateOf(Offset.Zero)
        private set

    /** A row's top and height in the card's space, or null before either has been placed. */
    fun boundsOf(row: AppMenuRowState): Pair<Float, Float>? {
        val card = cardCoordinates?.takeIf { it.isAttached } ?: return null
        val own = row.coordinates?.takeIf { it.isAttached } ?: return null
        return card.localPositionOf(own, Offset.Zero).y to own.size.height.toFloat()
    }

    /** Which row a point in the card's space is over. Disabled rows are not on offer. */
    fun rowAt(at: Offset): AppMenuRowState? {
        val card = cardCoordinates?.takeIf { it.isAttached } ?: return null
        // Beside the card counts as off it, or a finger dragged down the screen alongside the menu
        // would light up every row it passed.
        if (at.x < 0f || at.x > card.size.width) return null
        return rows.firstOrNull { row ->
            if (!row.enabled) return@firstOrNull false
            val (top, height) = boundsOf(row) ?: return@firstOrNull false
            at.y >= top && at.y < top + height
        }
    }

    /** The finger moved, in the card's own space. */
    fun press(at: Offset) {
        pointerInCard = at
        pressed = rowAt(at)
    }

    /** The finger moved, in the window's space — the relay's way in. */
    fun pressFromWindow(at: Offset) {
        val card = cardCoordinates?.takeIf { it.isAttached } ?: return
        press(card.windowToLocal(at))
    }

    /** Nothing under the finger, and nothing to answer for when it lifts. */
    fun clear() {
        pressed = null
    }

    /** Lifted. Whatever it came to rest on is the choice; off the rows it is nothing. */
    fun commit() {
        val chosen = pressed
        pressed = null
        chosen?.onClick?.invoke()
    }
}

@Stable
internal class AppMenuRowState {
    var enabled by mutableStateOf(true)
    var onClick: () -> Unit by mutableStateOf({})
    var coordinates: LayoutCoordinates? = null
}

internal val LocalAppMenuGesture = compositionLocalOf<AppMenuGesture?> { null }

// ---------------------------------------------------------------------------------------------
// The menu itself
// ---------------------------------------------------------------------------------------------

/**
 * A gesture for one card, wired to the finger that opened it and to the phone's haptics.
 *
 * Both cards use this — the difference between them is only what they draw in response. The shared
 * card slides a bar behind the row ([AppMenuHighlight]); an Apple bubble's card ripples the row
 * itself, which is why the row under the finger is published rather than drawn here.
 */
@Composable
internal fun rememberAppMenuGesture(): AppMenuGesture {
    val gesture = remember { AppMenuGesture() }
    val relay = LocalMenuDragRelay.current
    val haptics = LocalHapticFeedback.current
    val hapticsEnabled = LocalSettings.current.haptics

    // The finger that opened this menu, if it is still down and still travelling.
    // Where the relay stood when this card appeared. See [MenuDragState.armSeq].
    val baseline = remember { relay?.state?.seq ?: 0 }
    LaunchedEffect(gesture, relay) {
        if (relay == null) return@LaunchedEffect
        var touched = false
        snapshotFlow { relay.state }.collect { drag ->
            if (drag.armSeq > baseline) return@collect
            if (!drag.engaged) {
                // Still resting where it long-pressed. Nothing is highlighted, so letting go here
                // leaves the menu standing rather than picking whatever it opened over.
                gesture.clear()
                if (!drag.down) touched = false
                return@collect
            }
            // Applied either way: on a lift this is the position the finger ended at, which is the
            // row being chosen when the move and the lift arrive together.
            gesture.pressFromWindow(drag.at)
            if (drag.down) {
                touched = true
            } else if (touched) {
                // A menu that opened after the finger had already gone selects nothing.
                touched = false
                gesture.commit()
            }
        }
    }

    // One tick each time the highlight lands somewhere new, so a snap can be felt as well as seen.
    // `SegmentTick` is the platform's own word for this: a control that moves between stops.
    LaunchedEffect(gesture, hapticsEnabled) {
        snapshotFlow { gesture.pressed }.collect { row ->
            if (row != null && hapticsEnabled) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        }
    }
    return gesture
}

/**
 * The press that starts *on* the card, for a menu already open under a finger that has been lifted
 * once. The relay handles the other case; between them a row can be reached either way.
 */
internal fun Modifier.menuDrag(gesture: AppMenuGesture): Modifier =
    this.onGloballyPositioned { gesture.cardCoordinates = it }
        .pointerInput(gesture) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Consumed so the scrim behind doesn't read this as a tap to dismiss.
                down.consume()
                gesture.press(down.position)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    gesture.press(change.position)
                }
                gesture.commit()
            }
        }

/**
 * Registers one row with its card, and hands back the handle the card drives it by. The row keeps
 * no click of its own: the card's gesture owns the press, so that it can begin on another row, or
 * on the bubble that opened the menu in the first place.
 */
@Composable
internal fun rememberMenuRow(
    gesture: AppMenuGesture?,
    enabled: Boolean,
    onClick: () -> Unit,
): AppMenuRowState {
    val row = remember { AppMenuRowState() }
    SideEffect {
        row.enabled = enabled
        row.onClick = onClick
    }
    if (gesture != null) {
        DisposableEffect(gesture, row) {
            gesture.rows.add(row)
            onDispose { gesture.rows.remove(row) }
        }
    }
    return row
}

/**
 * What a row without a `clickable` still owes: its bounds, so the card can tell when the finger is
 * over it, and the semantics of a button, so there is something for TalkBack to activate.
 */
internal fun Modifier.menuRow(row: AppMenuRowState, enabled: Boolean, onClick: () -> Unit): Modifier =
    this.onGloballyPositioned { row.coordinates = it }
        .semantics(mergeDescendants = true) {
            role = Role.Button
            if (!enabled) disabled()
            onClick { onClick(); true }
        }


/**
 * Ripples a row from the card's drag rather than from a click of its own — an Apple bubble's menu
 * wants the press drawn on the row, not as a bar sliding behind it.
 *
 * The press is placed where the finger actually is, so the ripple grows out from under it instead
 * of from a corner.
 */
@Composable
internal fun rememberMenuRowRipple(
    gesture: AppMenuGesture?,
    row: AppMenuRowState,
): MutableInteractionSource {
    val interaction = remember { MutableInteractionSource() }
    if (gesture == null) return interaction

    val pressedHere = gesture.pressed === row
    val held = remember { mutableStateOf<PressInteraction.Press?>(null) }
    val origin =
        if (pressedHere) {
            val top = gesture.boundsOf(row)?.first ?: 0f
            Offset(gesture.pointerInCard.x, gesture.pointerInCard.y - top)
        } else {
            Offset.Zero
        }
    LaunchedEffect(pressedHere) {
        if (pressedHere) {
            val press = PressInteraction.Press(origin)
            held.value = press
            interaction.emit(press)
        } else {
            held.value?.let {
                interaction.emit(PressInteraction.Release(it))
                held.value = null
            }
        }
    }
    return interaction
}

/** The card the rows sit on: blurred screen, dark film, everything on it light. */
@Composable
fun AppMenuCard(
    backdrop: ChatBackdrop?,
    modifier: Modifier = Modifier,
    /**
     * For a card given a width of its own rather than sized to its rows — the chat list's peek,
     * which is as wide as the window above it. Without this the card filled that width while the
     * rows inside stayed at their intrinsic one, and the menu read as half-drawn.
     */
    fillWidth: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val gesture = rememberAppMenuGesture()

    Box(modifier = modifier.clip(AppMenu.shape).menuDrag(gesture)) {
        Box(modifier = Modifier.matchParentSize().chatBackdropBlur(backdrop, AppMenu.shape, AppMenu.blurRadius))
        Box(modifier = Modifier.matchParentSize().background(AppMenu.film))
        AppMenuHighlight(gesture, modifier = Modifier.matchParentSize())
        CompositionLocalProvider(
            LocalContentColor provides AppMenu.contentColor,
            LocalAppMenuGesture provides gesture,
        ) {
            // `IntrinsicSize.Max` is what a Material menu sizes its column to: every row as wide
            // as the longest label, none of them wider — then held inside the card's own bounds.
            //
            // The clamp belongs on the *column*, not on the card around it. Applied from outside,
            // a card forced up to its minimum width still measured its rows at their intrinsic one,
            // and the difference showed as a band of bare film down the trailing edge: a menu with
            // a gap between its labels and its own right-hand side.
            Column(
                modifier = Modifier
                    .padding(vertical = AppMenu.itemInset)
                    .then(
                        if (fillWidth) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.widthIn(min = AppMenu.minWidth, max = AppMenu.maxWidth).width(IntrinsicSize.Max)
                        },
                    ),
                content = content,
            )
        }
    }
}

/**
 * The bar that follows the finger. It is drawn by the card rather than by the rows so that it can
 * travel between them — a highlight owned by a row could only ever appear and disappear.
 */
@Composable
private fun AppMenuHighlight(gesture: AppMenuGesture, modifier: Modifier = Modifier) {
    val pressed = gesture.pressed
    val target = pressed?.let { gesture.boundsOf(it)?.first }

    // Whether the bar was already showing on the previous composition. A plain field, not snapshot
    // state: it is written during composition, and state written there invalidates the composable
    // that wrote it.
    val showing = remember { BooleanArray(1) }
    // A finger arriving on the card, as opposed to one moving across it. The bar used to slide in
    // from wherever the last press had left it, which after a drag off the card was one of its
    // edges: pressing a row showed the highlight travelling toward it from somewhere else. It now
    // appears under the finger at once, and only travels between rows the finger drags across.
    val arriving = target != null && !showing[0]
    showing[0] = target != null

    // Held where it was last when the press ends, so it fades out there.
    val top = remember { Animatable(target ?: 0f) }
    val alpha = remember { Animatable(if (target != null) 1f else 0f) }
    LaunchedEffect(target) {
        val to = target ?: return@LaunchedEffect
        if (arriving) top.snapTo(to) else top.animateTo(to, SlideSpec)
    }
    LaunchedEffect(target != null) {
        // In at once, with the position; out on a fade.
        if (target != null) alpha.snapTo(1f) else alpha.animateTo(0f, tween(FADE_MS))
    }

    if (alpha.value <= 0f) return
    // Sized off the card rather than measured with it, so the bar can never be what decides how
    // wide the card is.
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppMenu.itemHeight)
                .offset { IntOffset(0, top.value.roundToInt()) }
                .padding(horizontal = AppMenu.itemInset)
                .clip(AppMenu.itemShape)
                .background(AppMenu.highlight.copy(alpha = AppMenu.highlight.alpha * alpha.value)),
        )
    }
}

/**
 * A row on that card. Inset from the card's edge, and without a click of its own: the card's drag
 * gesture owns the press, and [AppMenuHighlight] draws what a ripple used to.
 *
 * Outside a card — the popup fallback, or a preview — it falls back to being clickable.
 */
@Composable
fun AppMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: AppMenuItemColors = AppMenu.itemColors(),
) {
    val gesture = LocalAppMenuGesture.current
    val row = rememberMenuRow(gesture, enabled, onClick)

    val content = colors.color(enabled)
    Row(
        modifier = modifier
            .padding(horizontal = AppMenu.itemInset)
            .clip(AppMenu.itemShape)
            .then(
                if (gesture != null) Modifier.menuRow(row, enabled, onClick)
                else Modifier.clickable(enabled = enabled, onClick = onClick)
            )
            .fillMaxWidth()
            // Preferred min and max width used during the intrinsic measurement, as Material's own
            // menu rows are.
            .sizeIn(minWidth = 112.dp, maxWidth = AppMenu.maxWidth)
            .height(AppMenu.itemHeight)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            CompositionLocalProvider(LocalContentColor provides content) {
                if (leadingIcon != null) {
                    Box(modifier = Modifier.defaultMinSize(minWidth = 24.dp)) { leadingIcon() }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            start = if (leadingIcon != null) 12.dp else 0.dp,
                            end = if (trailingIcon != null) 12.dp else 0.dp,
                        ),
                ) {
                    text()
                }
                if (trailingIcon != null) {
                    Box(modifier = Modifier.defaultMinSize(minWidth = 24.dp)) { trailingIcon() }
                }
            }
        }
    }
}

/** Inset to the line the rows are held to, so it stops where their highlights stop. */
@Composable
fun AppMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = AppMenu.itemInset, vertical = 4.dp),
        color = AppMenu.hairline,
    )
}

private class FloatHolder(var value: Float = 0f)

/** The film's colour as an opaque one, for the fallback menu that has nothing to blur. */
private fun Color.overBlack(): Color =
    Color(red = red * alpha, green = green * alpha, blue = blue * alpha, alpha = 1f)
