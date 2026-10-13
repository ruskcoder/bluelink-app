package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ruskcoder.messages.data.model.reactionEmoji
import com.ruskcoder.messages.ui.theme.LocalSettings
import kotlinx.coroutines.delay

private val SlotSize = 44.dp
private val BarPadding = 6.dp

/** The bar's own shape, and the glass it is made of — the same card every menu in the app wears. */
private val BarShape = RoundedCornerShape(SlotSize / 2 + BarPadding)

/**
 * The film behind the "+" at the end of the bar.
 *
 * Grey rather than the theme's own tint: a saturated container behind one glyph in a row of six
 * reads as a seventh colour rather than as a control.
 */
private val SlotSelected = Color.White.copy(alpha = 0.16f)

/**
 * The "+" slot's key in the slide.
 *
 * It takes part in the row's one gesture rather than carrying a `clickable` of its own. A nested
 * click could not survive it: the row consumes the moves it tracks the finger by, and a consumed
 * move is exactly what tells `clickable` its press has been taken over — so the "+" worked only on
 * a tap with no movement in it at all, which is why it so often did nothing.
 *
 * Not a glyph, and not anything a keyboard produces, so it can never collide with one.
 */
private const val MORE_SLOT = "::more::"
/** One extra slot for the "+" that opens the full palette. */
private fun fullWidthFor(slots: Int) = SlotSize * (slots + 1) + BarPadding * 2
/** Decelerating — the bar arrives at its width rather than easing out of it (`Easing.out(cubic)`). */
private val WidthEasing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

/**
 * The emoji picker row that pops up on long-press: the bar widens from a single slot while the
 * emoji pop in one after another, so the two motions read as the row unrolling rather than a panel
 * simply appearing. Behind its "+" is the wider palette. Ported from `reaction-bar.tsx`.
 *
 * Composed only while open, which is what lets the entry animation start from its own first frame.
 */
@Composable
fun ReactionBar(
    visible: Boolean,
    active: List<String>? = null,
    onPick: (String) -> Unit,
    /**
     * Raised while the full palette is open over the bar.
     *
     * The bar cannot put the conversation out of focus itself — it does not know which row it was
     * opened from, and the thing that has to stay sharp is that row. So it reports, and whoever
     * opened it draws the scrim. Inside the focused overlay this is ignored, because everything
     * behind a lifted message is already blurred.
     */
    onPaletteChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Taken from the composition rather than passed in: every caller sits inside the conversation
    // that provides it, and threading it through two screens to reach here would say nothing the
    // composition local does not already.
    val backdrop = LocalChatBackdrop.current
    // Whatever the bar has been arranged into, with the emptied slots closed up rather than drawn
    // as gaps — a bar of two emoji is two slots wide. See `Settings.reactionEmoji`.
    val emoji = LocalSettings.current.reactionEmoji()
    if (!visible) return
    var paletteOpen by remember { mutableStateOf(false) }
    LaunchedEffect(paletteOpen) { onPaletteChange(paletteOpen) }
    // Whatever opened this bar is taking it away; the palette must not be left standing behind it.
    DisposableEffect(Unit) { onDispose { onPaletteChange(false) } }
    // The long press that opened this bar has not been released yet, so the finger is already here:
    // sliding onto an emoji and letting go picks it, without a second tap.
    val slide = rememberEmojiSlide()

    // Starts closed and opens on the frame after mounting, which is what gives the animation
    // something to run from.
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { open = true }
    val width by animateDpAsState(
        targetValue = if (open) fullWidthFor(emoji.size) else SlotSize + BarPadding * 2,
        animationSpec = tween(170, easing = WidthEasing),
        label = "reactionBarWidth",
    )

    // Three layers, and only the middle one is clipped.
    //
    // The bar has to cut its row off at its own animating edge for the unroll to read as a bar
    // widening rather than emoji being squeezed — but anything grown *inside* that clip is cut off
    // by it too, which is why a slot that scaled in place kept losing its top and its sides. So the
    // grown emoji is not in the row at all: the row hides it, and the overlay redraws it at the
    // same place out here, where nothing is clipping anything.
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .width(width)
                // One gesture for the whole row, the "+" included: the slide reports whichever slot
                // the finger comes off on, and only this knows that one of them is not an emoji.
                .emojiSlide(slide) { picked ->
                    if (picked == MORE_SLOT) paletteOpen = true else onPick(picked)
                }
                .clipToBounds(),
        ) {
            // The same glass the bubble menu under it is made of, rather than a tonal Material
            // surface: the two appear together around one message and were the only pair in the
            // app made of different materials.
            Box(modifier = Modifier.matchParentSize().clip(BarShape)) {
                Box(modifier = Modifier.matchParentSize().chatBackdropBlur(backdrop, BarShape, AppMenu.blurRadius))
                Box(modifier = Modifier.matchParentSize().background(AppMenu.film))
            }
            Row(
                modifier = Modifier
                    .wrapContentWidth(align = Alignment.Start, unbounded = true)
                    .padding(horizontal = BarPadding, vertical = BarPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            emoji.forEachIndexed { index, glyph ->
                EmojiSlot(emoji = glyph, index = index, selected = active?.contains(glyph) == true, slide = slide)
            }
            Box(
                modifier = Modifier
                    .size(SlotSize)
                    .clip(CircleShape)
                    // The bar stays where it is behind the palette: closing the palette without
                    // picking anything leaves the six quick reactions still on offer.
                    //
                    // Takes the row's gesture like every other slot, but answers it as a button
                    // rather than as an emoji — see [MoreSlotFace] and `grows`.
                    .emojiSlot(slide, MORE_SLOT, grows = false),
                contentAlignment = Alignment.Center,
            ) {
                MoreSlotFace(pressed = slide.hovered() == MORE_SLOT)
                }
            }
        }
        // Outside the clip, and drawn last so it is over the bar rather than under it. Positioned
        // against the same box the slots measured themselves in, which is the clipped one — and
        // that one sits at this box's origin, so the coordinates carry across unchanged.
        // Emoji only. The "+" never registers a growth, so it never reaches this — it answers the
        // finger where it stands instead. See [MoreSlotFace].
        EmojiSlideOverlay(slide, fontSize = 26.sp)
    }

    if (paletteOpen) {
        EmojiPickerSheet(
            active = active,
            onDismiss = { paletteOpen = false },
            onPick = { emoji -> paletteOpen = false; onPick(emoji) },
        )
    }
}

/**
 * The "+" at the end of the row: a disc with a plus in it, which answers the finger by lighting up
 * rather than by growing.
 *
 * Everything else in the bar is an emoji, and an emoji under a thumb has to leave the thumb to be
 * seen at all — hence the growth. This is a button, and a button that swells to nearly twice its
 * size reads as something going wrong. So it takes the same slide, reports the same hover, and
 * spends it the way a button spends a press: a ripple opening out of the disc and settling into a
 * held-down state, which is also exactly what "you are about to let go on this one" looks like.
 *
 * The disc itself never moves or changes size, so the plus stays put under the finger.
 */
@Composable
private fun MoreSlotFace(pressed: Boolean = false) {
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        // Out fast and back slowly, the way a ripple behaves: the answer to a finger arriving is
        // immediate, and the fade after it leaves is what stops the row flickering as you cross it.
        animationSpec = tween(if (pressed) 130 else 220, easing = WidthEasing),
        label = "moreSlotPress",
    )
    Box(modifier = Modifier.size(SlotSize), contentAlignment = Alignment.Center) {
        // The ripple: opens out of the disc's own edge to fill the slot, and holds there.
        if (press > 0f) {
            Box(
                modifier = Modifier
                    .size(MoreSlotFaceSize + (SlotSize - MoreSlotFaceSize) * press)
                    .clip(CircleShape)
                    .background(SlotSelected.copy(alpha = SlotSelected.alpha * press)),
            )
        }
        Box(
            modifier = Modifier.size(MoreSlotFaceSize).clip(CircleShape).background(SlotSelected),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "More reactions", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** The disc the plus sits in, which is smaller than the slot the ripple opens out to fill. */
private val MoreSlotFaceSize = 32.dp

@Composable
private fun EmojiSlot(emoji: String, index: Int, selected: Boolean, slide: EmojiSlide) {
    val scale = remember { Animatable(0f) }
    LaunchedEffect(emoji) {
        delay(index * 16L)
        scale.animateTo(1.18f, tween(70))
        scale.animateTo(1f, tween(60))
    }
    Box(
        modifier = Modifier
            .size(SlotSize)
            // The one you already put on this message wears the same disc as the action buttons on
            // a contact's page — a heavier blur of the conversation under a pale film — rather than
            // a flat white wash. Both are "a control resting on glass", and there is no reason for
            // the app to have two answers to that; a flat fill also had nothing behind it, so on a
            // dark wallpaper it read as a grey circle stuck to the bar rather than as part of it.
            .then(if (selected) Modifier.frostedFill(CircleShape, blur = ControlBlur, tint = ControlTint) else Modifier.clip(CircleShape))
            // No `clickable`: the whole row is one gesture now, and a tap is the shortest slide.
            .emojiSlot(slide, emoji),
        contentAlignment = Alignment.Center,
    ) {
        // The unroll's own pop, kept separate from the hover growth above it so the two can be in
        // flight at once without one cancelling the other.
        Text(emoji, fontSize = 26.sp, modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value })
    }
}
