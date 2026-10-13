package com.ruskcoder.messages.ui.components

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import com.ruskcoder.messages.data.model.RunFormat
import com.ruskcoder.messages.data.model.TextFlag

/**
 * What the selection menu can do to the text under it, beyond cut and copy.
 *
 * Handed in as callbacks rather than as the draft itself: the toolbar has no idea what a draft is,
 * and the screen that owns one already knows how to apply a format to whatever is selected.
 */
class FormatActions(
    val toggle: (TextFlag) -> Unit,
    /** Opens the panel with the eight animations on it — too many to put in a floating menu. */
    val openEffects: () -> Unit,
)

/**
 * The text-selection menu, with the formatting controls on it.
 *
 * Compose's own toolbar offers cut, copy, paste and select-all, and there is no way to add to it —
 * the platform implementation builds its menu from the four callbacks the interface takes and
 * nothing else. So this is the whole toolbar, ours: the same floating `ActionMode` Android shows
 * for a text selection anywhere else, with the four standard items built from the same callbacks
 * and the formatting items appended after them.
 *
 * Only the four flags go on the menu. The eight animations would not fit — a floating action mode
 * overflows past four or five items and the rest disappear behind a chevron, which is a worse place
 * to find them than the panel — so the menu offers one way through to that panel instead.
 *
 * The menu is dismissed as soon as a format is applied. Leaving it up would be defensible, but the
 * selection it was acting on is exactly what it is covering.
 */
private class FormattingTextToolbar(
    private val view: View,
    private val actions: () -> FormatActions,
) : TextToolbar {
    private var mode: ActionMode? = null

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    private var onCopy: (() -> Unit)? = null
    private var onPaste: (() -> Unit)? = null
    private var onCut: (() -> Unit)? = null
    private var onSelectAll: (() -> Unit)? = null
    private var contentRect: Rect = Rect.Zero

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        contentRect = rect
        onCopy = onCopyRequested
        onPaste = onPasteRequested
        onCut = onCutRequested
        onSelectAll = onSelectAllRequested
        // Already up: the selection has changed under it, so the items are rebuilt in place rather
        // than the mode being torn down and started again — which flickers, and on some devices
        // loses the selection along with the mode.
        mode?.let {
            it.invalidate()
            return
        }
        mode = view.startActionMode(Callback(), ActionMode.TYPE_FLOATING)
        status = TextToolbarStatus.Shown
    }

    override fun hide() {
        mode?.finish()
        mode = null
        status = TextToolbarStatus.Hidden
    }

    private inner class Callback : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = rebuild(menu)

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear()
            return rebuild(menu)
        }

        /**
         * The standard items first, in the order the platform puts them, then ours.
         *
         * An item is added only where there is a callback behind it, which is how Compose says
         * "this one does not apply here" — paste into a read-only field, copy with nothing
         * selected. Ours are added unconditionally, since a selection is the only state this menu
         * is ever shown in.
         */
        private fun rebuild(menu: Menu): Boolean {
            onCut?.let { menu.add(0, ITEM_CUT, 0, android.R.string.cut) }
            onCopy?.let { menu.add(0, ITEM_COPY, 1, android.R.string.copy) }
            onPaste?.let { menu.add(0, ITEM_PASTE, 2, android.R.string.paste) }
            onSelectAll?.let { menu.add(0, ITEM_SELECT_ALL, 3, android.R.string.selectAll) }
            menu.add(0, ITEM_BOLD, 4, "Bold")
            menu.add(0, ITEM_ITALIC, 5, "Italic")
            menu.add(0, ITEM_UNDERLINE, 6, "Underline")
            menu.add(0, ITEM_STRIKE, 7, "Strikethrough")
            menu.add(0, ITEM_EFFECTS, 8, "Effects")
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val format = actions()
            when (item.itemId) {
                ITEM_CUT -> onCut?.invoke()
                ITEM_COPY -> onCopy?.invoke()
                ITEM_PASTE -> onPaste?.invoke()
                // The one item that deliberately leaves the menu up: selecting all is a change to
                // what the *other* items would act on, not an action of its own.
                ITEM_SELECT_ALL -> {
                    onSelectAll?.invoke()
                    return true
                }
                ITEM_BOLD -> format.toggle(TextFlag.BOLD)
                ITEM_ITALIC -> format.toggle(TextFlag.ITALIC)
                ITEM_UNDERLINE -> format.toggle(TextFlag.UNDERLINE)
                ITEM_STRIKE -> format.toggle(TextFlag.STRIKETHROUGH)
                ITEM_EFFECTS -> format.openEffects()
                else -> return false
            }
            hide()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            this@FormattingTextToolbar.mode = null
            status = TextToolbarStatus.Hidden
        }

        /**
         * Where the floating bar parks itself: around the selection, in the view's coordinates.
         *
         * Compose reports the rect in window coordinates and the action mode wants view ones, so
         * the view's own position comes off first. Without it the bar drifted by the height of the
         * status bar and the width of any inset the window carried.
         */
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            val origin = IntArray(2)
            view.getLocationInWindow(origin)
            outRect.set(
                (contentRect.left - origin[0]).toInt(),
                (contentRect.top - origin[1]).toInt(),
                (contentRect.right - origin[0]).toInt(),
                (contentRect.bottom - origin[1]).toInt(),
            )
        }
    }
}

private const val ITEM_CUT = 0
private const val ITEM_COPY = 1
private const val ITEM_PASTE = 2
private const val ITEM_SELECT_ALL = 3
private const val ITEM_BOLD = 4
private const val ITEM_ITALIC = 5
private const val ITEM_UNDERLINE = 6
private const val ITEM_STRIKE = 7
private const val ITEM_EFFECTS = 8

/**
 * A [TextToolbar] carrying [actions], to provide over whatever should offer them.
 *
 * Scoped rather than installed app-wide on purpose: this menu belongs to a field whose text can
 * *be* formatted. Offering "Bold" over a message bubble, a contact's name or a search box would be
 * offering something that cannot happen.
 */
@Composable
fun rememberFormattingTextToolbar(actions: FormatActions): TextToolbar {
    val view = LocalView.current
    // Read through a holder so the toolbar — which outlives any one composition — always calls the
    // callbacks belonging to the current draft rather than the ones it was built with.
    val latest by rememberUpdatedState(actions)
    return remember(view) { FormattingTextToolbar(view) { latest } }
}

/** The format the menu would report as already applied, for callers that want to reflect it. */
typealias ActiveFormat = RunFormat?
