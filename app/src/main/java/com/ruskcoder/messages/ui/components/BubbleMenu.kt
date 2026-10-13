package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.BubbleStyle
import com.ruskcoder.messages.ui.theme.LocalSettings
import androidx.compose.material.icons.rounded.Delete

/** One action's shape. Hidden entries are dropped rather than shown disabled. */
private data class BubbleAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    /** Drawn in the error colour, the way both platforms mark an action that destroys something. */
    val destructive: Boolean = false,
)

/** iOS's context menu is a tight rounded card, and every row on it is a full-width band. */
private val AppleMenuShape = RoundedCornerShape(14.dp)
private val AppleMenuWidth = 232.dp
private val AppleRowHeight = 46.dp

/** Frosted glass: the same black film the other menus carry, plus a hairline between the rows. */
private val AppleMenuFilm = AppMenu.film
private val AppleMenuDivider = Color(0x1AFFFFFF)

/** systemRed on dark, which is what iOS tints a destructive context-menu row. */
private val AppleDestructive = Color(0xFFFF453A)

/**
 * The menu a long press puts under a bubble: the handful of things you can do to one message, as a
 * labelled list rather than a row of bare icons, so each action names itself instead of asking to
 * be recognised from its glyph. Ported from `bubble-menu.tsx`, laid out vertically.
 *
 * This is the one menu in the app that is not always an [AppMenuCard]. On Apple bubbles it keeps
 * the card it was drawn to sit under — full-width rows with hairlines between them, in the
 * proportions of the bubble it hangs off — because that is the pairing the look was built around.
 * Every other bubble style gets the card the rest of the app uses, drag-to-select and all.
 */
@Composable
fun BubbleMenu(
    visible: Boolean,
    editable: Boolean,
    /** Which side the bubble is on, so the menu grows out of that corner. */
    mine: Boolean,
    /** Null where there is nothing to answer — an unsent message. The same for [onCopy] and [onSelectMessages]. */
    onReply: (() -> Unit)?,
    onEdit: () -> Unit,
    onCopy: (() -> Unit)?,
    /** Null on a message with no words in it, where highlighting part of the text means nothing. */
    onSelectText: (() -> Unit)? = null,
    onSelectMessages: (() -> Unit)?,
    /** Null when the message cannot be unsent — someone else's, or already gone. */
    onUnsend: (() -> Unit)? = null,
    /** Null unless this is one of your own messages in a group, where "who has seen it" has an answer. */
    onInfo: (() -> Unit)? = null,
    /**
     * Copies the message's attachment out to the phone. Null on a message carrying nothing to save,
     * which is most of them — a row that is only ever greyed out is a row worth not drawing.
     */
    onDownload: (() -> Unit)? = null,
    /** "Download", or "Save all" on a photo stack. */
    downloadLabel: String = "Download",
    /** Saves only the top picture of a stack. Null everywhere else. */
    onDownloadCurrent: (() -> Unit)? = null,
    /** "Copy", or "Copy current" on a photo stack. */
    copyLabel: String = "Copy",
    /** Removes the message from this phone only. Offered on an unsent message, where it is the one way to clear it. */
    onDeleteForMe: (() -> Unit)? = null,
    /** The conversation's recording, which the card frosts. */
    backdrop: ChatBackdrop? = null,
    modifier: Modifier = Modifier,
) {
    val apple = LocalSettings.current.bubbleType == BubbleStyle.APPLE
    val actions = buildList {
        onReply?.let { add(BubbleAction(Icons.AutoMirrored.Rounded.Reply, "Reply", it)) }
        if (editable) add(BubbleAction(Icons.Rounded.Edit, "Edit", onEdit))
        onCopy?.let { add(BubbleAction(Icons.Rounded.ContentCopy, copyLabel, it)) }
        // Next to Copy, because it is the same verb narrowed down: take part of this message rather
        // than all of it.
        onSelectText?.let { add(BubbleAction(Icons.Rounded.TextFields, "Select text", it)) }
        // Beside Copy for the same reason Select text is: all three are "take this with you", and
        // this is the one that takes the file rather than the words.
        onDownloadCurrent?.let { add(BubbleAction(Icons.Rounded.Download, "Save current", it)) }
        onDownload?.let { add(BubbleAction(Icons.Rounded.Download, downloadLabel, it)) }
        onInfo?.let { add(BubbleAction(Icons.Rounded.Info, "Message info", it)) }
        // Last, and the only one that leaves anything on screen behind it: it swaps the whole
        // conversation into the checkbox column.
        onSelectMessages?.let { add(BubbleAction(Icons.Rounded.LibraryAddCheck, "Select messages", it)) }
        // Below the divider the destructive action gets by being last, so it is never the thing
        // under your thumb when the menu appears.
        onUnsend?.let { add(BubbleAction(Icons.Rounded.Undo, "Unsend", it, destructive = true)) }
        onDeleteForMe?.let { add(BubbleAction(Icons.Rounded.Delete, "Delete for me", it, destructive = true)) }
    }

    // The menu is composed only while a message is picked out, so the entrance needs a first frame
    // at hidden to run from — otherwise it would simply be there.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        entrance.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(AppMenu.ENTRANCE_MS, easing = AppMenu.entranceEasing),
        )
    }

    // The same scale-and-fade the app's other menus open with, growing from the corner nearest its
    // bubble rather than from the middle of the card.
    //
    // Run through `graphicsLayer` rather than `AnimatedVisibility` on purpose: the menu is part of
    // a cluster whose measured height decides where the whole thing comes to rest, and
    // `AnimatedVisibility` reports no height at all on its first frame. That made the cluster
    // re-measure as the menu appeared, which moved the target out from under the lift already in
    // flight. Laid out at full size from the start, the cluster is measured once and stays put.
    Box(
        modifier = modifier.graphicsLayer {
            alpha = entrance.value
            scaleX = 0.8f + 0.2f * entrance.value
            scaleY = 0.8f + 0.2f * entrance.value
            transformOrigin = TransformOrigin(if (mine) 1f else 0f, 0f)
        },
    ) {
        if (apple) AppleMenuCard(actions, backdrop) else SharedMenuCard(actions, backdrop)
    }
}

/** The card the rest of the app uses, with these actions poured into it. */
@Composable
private fun SharedMenuCard(actions: List<BubbleAction>, backdrop: ChatBackdrop?) {
    // No width of its own: the card sizes itself to its rows and keeps them inside its own
    // minimum. Constrained from out here instead, the card and its rows arrived at two different
    // widths and the leftover showed as a gap at the trailing edge.
    AppMenuCard(backdrop = backdrop) {
        actions.forEach { action ->
            if (action.destructive) AppMenuDivider()
            AppMenuItem(
                text = { Text(action.label) },
                trailingIcon = {
                    Icon(action.icon, contentDescription = null, modifier = Modifier.size(19.dp))
                },
                colors = if (action.destructive) AppMenu.destructiveItemColors() else AppMenu.itemColors(),
                onClick = action.onClick,
            )
        }
    }
}

/**
 * The card an Apple bubble gets. The blur samples the same recording of the conversation the rest
 * of the overlay does, so the menu is lit by whatever it happens to be sitting over rather than by
 * a flat grey; where the platform can't blur, the film alone carries the look.
 */
@Composable
private fun AppleMenuCard(actions: List<BubbleAction>, backdrop: ChatBackdrop?) {
    val gesture = rememberAppMenuGesture()
    Box(
        modifier = Modifier
            .widthIn(max = AppleMenuWidth)
            .clip(AppleMenuShape)
            .menuDrag(gesture),
    ) {
        Box(modifier = Modifier.matchParentSize().chatBackdropBlur(backdrop, AppleMenuShape, radius = 30.dp))
        Box(modifier = Modifier.matchParentSize().background(AppleMenuFilm))
        Column(modifier = Modifier.width(AppleMenuWidth)) {
            actions.forEachIndexed { index, action ->
                if (index > 0) Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppleMenuDivider))
                AppleMenuRow(gesture, action)
            }
        }
    }
}

/**
 * One row of that card. No `clickable`: the press is the card's, so that it can have begun on
 * another row — or on the bubble that opened the menu, and never have been lifted since. The
 * ripple is emitted by hand from wherever the finger currently is.
 */
@Composable
private fun AppleMenuRow(gesture: AppMenuGesture, action: BubbleAction) {
    val row = rememberMenuRow(gesture, enabled = true, onClick = action.onClick)
    val interaction = rememberMenuRowRipple(gesture, row)
    val tint = if (action.destructive) AppleDestructive else Color.White
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .height(AppleRowHeight)
            .menuRow(row, enabled = true, onClick = action.onClick)
            // White explicitly: the card sets its own colours rather than the theme's, and an
            // unspecified ripple would take the app's `onSurface` — dark, on a dark card.
            .indication(interaction, ripple(color = Color.White))
            .padding(horizontal = 16.dp),
    ) {
        Text(action.label, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Normal)
        // Out on the trailing edge, which is where iOS puts a context menu's glyph.
        Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
    }
}
