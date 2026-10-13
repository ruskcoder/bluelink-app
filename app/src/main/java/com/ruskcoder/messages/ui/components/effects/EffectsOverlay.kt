package com.ruskcoder.messages.ui.components.effects

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.MessageEffect
import com.ruskcoder.messages.ui.components.ChatBackdrop
import com.ruskcoder.messages.ui.components.ComposerActionRow
import com.ruskcoder.messages.ui.components.chatBackdropBlur

/**
 * The whole screen, out of focus, with the three things you can still touch standing on top of it.
 *
 * One blur over everything rather than a blur applied piece by piece. The difference is not
 * cosmetic: blurring the conversation and then the send bar's pill left the app bar sharp, left the
 * send button sitting in a layer of its own, and left the draft field underneath still taking
 * touches — a screen that *looked* stepped back but had not. Here a single sheet covers the lot,
 * app bar included, and swallows every press that reaches it, so what is behind it is out of reach
 * as well as out of focus.
 *
 * What stands above it is only what the mode is for: the message being dressed, the card of
 * effects, and the two controls that end it — a cross that puts the message back and a send button
 * that lets it go. Those are drawn here rather than uncovered from the bar below, because there is
 * no way to punch a hole in a blur around two buttons in the middle of a row.
 *
 * The blur is a replay of a recording taken around the whole screen — the same trick the frosted
 * bars use, one level up. It has to be a *sibling* of what it records, never a child: a layer that
 * replays itself while being recorded draws nothing.
 */
@Composable
fun BoxScope.EffectsOverlay(
    visible: Boolean,
    /** A recording of the entire screen, taken outside this. Null where the platform cannot blur. */
    backdrop: ChatBackdrop?,
    /** The navigation bar's height: the card sits above it, as the "+" sheet does. */
    bottomInset: Dp,
    /** The card's height, matched to the sheet it replaces so nothing moves as the mode opens. */
    cardHeight: Dp,
    selected: MessageEffect?,
    onSelect: (MessageEffect?) -> Unit,
    surface: Color,
    onClose: () -> Unit,
    onSend: () -> Unit,
    /** The message, drawn as the bubble it is about to become. */
    preview: @Composable () -> Unit,
) {
    val fade by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) OpenMs else CloseMs),
        label = "effectsOverlay",
    )
    if (fade <= 0.01f) return

    Box(
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer { alpha = fade }
            // Every press stops here. The conversation and the draft field are behind the sheet and
            // must not answer a finger that lands on them — a blurred control that still works is a
            // worse state than either a sharp one or none.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        if (backdrop != null) {
            Box(modifier = Modifier.matchParentSize().chatBackdropBlur(backdrop, RectangleShape, radius = ScreenBlur))
        }
        // Heavier where there is nothing to blur, since on those devices the dim is the whole effect.
        Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = if (backdrop != null) 0.28f else 0.55f)))
    }

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(bottom = bottomInset)
            .graphicsLayer { alpha = fade },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The message, centred over a screen that has gone quiet for it.
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            preview()
        }
        // The bar's own two end controls, redrawn sharp over the blurred copy of themselves —
        // same shapes, same glyphs, same places. See [ComposerActionRow], which is where the
        // geometry lives so that there is only ever one of it.
        ComposerActionRow(
            onClose = onClose,
            onSend = onSend,
            closeLabel = "Close effects",
            sendLabel = "Send with effect",
        )
        EffectPickerPanel(
            selected = selected,
            onSelect = onSelect,
            surface = surface,
            height = cardHeight,
        )
    }
}

/** How far out of focus the screen goes. Matched to the lift's own radius — one idea, one number. */
private val ScreenBlur = 20.dp

private const val OpenMs = 260
private const val CloseMs = 200
