package com.ruskcoder.messages.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.BubbleStyle
import com.ruskcoder.messages.data.model.textScale
import com.ruskcoder.messages.ui.components.bubbles.BubbleColors
import com.ruskcoder.messages.ui.components.bubbles.BubbleRowInset
import com.ruskcoder.messages.ui.components.bubbles.useAppleBubbleColors
import com.ruskcoder.messages.ui.components.bubbles.useGoogleBubbleColors
import com.ruskcoder.messages.ui.components.bubbles.useWhatsAppBubbleColors
import com.ruskcoder.messages.ui.theme.LocalSettings

/** The confirm/discard buttons, and the room the field gives up at each end to make space for them. */
private val EditorButton = 38.dp
private val EditorSlot = 48.dp

/** The corner the field starts on — near enough to every style's bubble to pass for it. */
private val FieldStartCorner = 18.dp

/** The bubble's own text inset, which the field matches so the two are the same height. */
private val BubblePaddingH = 12.dp
private val BubblePaddingV = 8.dp

/** The line height the field is set on, in dip at the app's own text scale. */
private const val LINE_DP = 21f

/**
 * How many lines the field grows to before it starts scrolling instead.
 *
 * Past this the box is taller than the message it is standing in and starts pushing the
 * conversation around; a message long enough to need more than a few lines is one you scroll
 * through, the same as you would in the composer.
 */
private const val MAX_LINES = 5

/**
 * The largest corner the field will round to.
 *
 * A stadium is right for one line and wrong for five: at half a tall box's height the radius eats
 * the straight edges and the field comes out as a lozenge. Capped, it opens as a capsule and grows
 * into a rounded rectangle, which is what a message box looks like.
 */
private val FieldMaxCorner = 22.dp

/**
 * A message being rewritten, in the row it already occupies.
 *
 * Editing deliberately does not lift the message or blur the conversation the way the action menu
 * does. The menu is a question about a message and wants your whole attention; an edit is a
 * correction to one you can already see, and moving it to the middle of a dimmed screen to make
 * that correction meant losing the thing you were correcting it against. So the bubble stays in its
 * run and the row is what changes: the bubble stretches into a field with a discard on one side and
 * a confirm on the other, and the conversation carries on around it.
 *
 * The field is outlined rather than filled. Filled in the bubble's own colour it still read as a
 * message — a bubble that had grown some buttons — and an edit in progress is not a message yet.
 */
@Composable
fun InlineMessageEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    colors: BubbleColors,
    /** 0 with the bubble untouched, 1 with the field at full width. */
    progress: Float,
    /** How solid the field is; runs ahead of the stretch so the cross-fade finishes early. */
    ink: Float,
    /** The share of the row the bubble it is replacing took, so the stretch starts at its edge. */
    startFraction: Float,
    mine: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scale = LocalSettings.current.textScale()
    val keyboard = LocalSoftwareKeyboardController.current
    // The buttons belong to the field, so they wait for it: nothing appears at the ends until the
    // stretch has actually opened somewhere for them to appear in.
    val buttons = ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f)
    var fieldHeightPx by remember { mutableIntStateOf(0) }
    // A stadium when it gets there, whatever the text has grown the field to: the corner it ends on
    // is exactly half its own measured height.
    val capsule = with(density) { (fieldHeightPx / 2f).toDp() }.coerceAtMost(FieldMaxCorner)
    val corner = lerp(FieldStartCorner, if (fieldHeightPx > 0) capsule else FieldStartCorner, progress)
    // Room for a few lines, then the field keeps its size and scrolls what it cannot show. Derived
    // from the text-size setting rather than fixed, so turning the text up buys taller lines rather
    // than fewer of them.
    val maxHeight = BubblePaddingV * 2 + (LINE_DP * scale * MAX_LINES).dp

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Back belongs to the field while the field is up — after the keyboard, which takes the first
    // press for itself the way it does everywhere else.
    BackHandler { keyboard?.hide(); onCancel() }

    Box(modifier = modifier.fillMaxWidth().padding(horizontal = BubbleRowInset)) {
        EditorAction(
            icon = Icons.Rounded.Close,
            description = "Discard changes",
            fill = MaterialTheme.colorScheme.surfaceVariant,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { keyboard?.hide(); onCancel() },
            // Bottom-aligned, not centred. On one line the two are the same place; on five, a
            // button floating at the middle of a tall box has nothing to do with it, and the send
            // bar has already established that the button belongs at the foot of the field.
            modifier = Modifier.align(Alignment.BottomStart).appearing(buttons),
        )
        EditorAction(
            icon = Icons.Rounded.Check,
            description = "Save edit",
            fill = colors.background,
            tint = colors.text,
            onClick = { keyboard?.hide(); onConfirm() },
            modifier = Modifier.align(Alignment.BottomEnd).appearing(buttons),
        )
        Box(
            modifier = Modifier
                // Anchored to the side the bubble was on, so the stretch opens away from the
                // message's own edge instead of sliding the whole thing across the row — and to the
                // top of it, so a second line opens downward from where the message already starts
                // rather than growing in both directions at once.
                .align(if (mine) Alignment.TopEnd else Alignment.TopStart)
                // The room the buttons move into. Taken from the field rather than added around it,
                // which is what keeps the outer edges pinned while the middle narrows.
                .padding(horizontal = EditorSlot * progress)
                .fillMaxWidth(startFraction + (1f - startFraction) * progress)
                .heightIn(max = maxHeight)
                .graphicsLayer { alpha = ink }
                .onSizeChanged { fieldHeightPx = it.height }
                .clip(RoundedCornerShape(corner))
                // Something to read the words against — the conversation's wallpaper is behind this
                // — but not the bubble's fill, which would make it a bubble again.
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                .border(1.5.dp, colors.background, RoundedCornerShape(corner))
                // The bubble's own padding, to the dip. Anything else and the field is a different
                // height from the message it is standing in for, which reads as the bubble growing
                // the moment you start editing it.
                .padding(horizontal = BubblePaddingH, vertical = BubblePaddingV),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = LocalTextStyle.current.merge(
                    TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = (16 * scale).sp,
                        lineHeight = (21 * scale).sp,
                    ),
                ),
                cursorBrush = SolidColor(colors.background),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onConfirm() }),
                // No `singleLine`, and no line cap: the field wraps, the box above grows with it
                // until it hits its ceiling, and the field's own scroller takes over from there —
                // keeping the caret in view as you type past the bottom.
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
    }
}

/** Bubble fill and ink for whichever style the conversation is drawn in. */
@Composable
fun activeBubbleColors(mine: Boolean): BubbleColors {
    // All three are called unconditionally — they're composables — and the active style picks the
    // winner, the same way the tombstone bubble does it.
    val whatsapp = useWhatsAppBubbleColors(mine)
    val google = useGoogleBubbleColors(mine)
    val apple = useAppleBubbleColors(mine)
    return when (LocalSettings.current.bubbleType) {
        BubbleStyle.WHATSAPP -> whatsapp
        BubbleStyle.GOOGLE -> google
        BubbleStyle.APPLE -> apple
    }
}

/** The draft a row starts an edit with: the message as it stands, caret at the end of it. */
fun editorDraft(text: String): TextFieldValue = TextFieldValue(text, TextRange(text.length))

/** One of the field's two answers: a round button, sized to sit inside the row it flanks. */
@Composable
private fun EditorAction(
    icon: ImageVector,
    description: String,
    fill: Color,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(EditorButton)
            .clip(RoundedCornerShape(EditorButton / 2))
            .background(fill)
            .clickable(onClick = onClick, onClickLabel = description),
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** How a button arrives once the field has opened room for it. */
private fun Modifier.appearing(progress: Float): Modifier = graphicsLayer {
    alpha = progress
    scaleX = 0.6f + 0.4f * progress
    scaleY = 0.6f + 0.4f * progress
}
