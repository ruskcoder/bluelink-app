package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.getSelectedText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import com.ruskcoder.messages.data.model.TextRun
import com.ruskcoder.messages.ui.components.effects.InkVeil

/**
 * The words inside a bubble — and, while the conversation is in text-selection mode, the handles
 * that let part of them be taken out.
 *
 * Selecting inside a message can't be a property of the bubble alone, because the thing that acts
 * on a selection lives in the app bar: what is highlighted has to be readable from outside the
 * conversation's scroll. So the range is hoisted into a [BubbleSelection] the screen owns and the
 * bubble is handed it through a composition local, which keeps every bubble's signature the plain
 * `selectable: Boolean` it already had.
 */
class BubbleSelection {
    /** The field's own state: the text as drawn, and which part of it is highlighted. */
    var value by mutableStateOf(TextFieldValue(AnnotatedString("")))

    /**
     * What Copy would put on the clipboard.
     *
     * Trailing whitespace is dropped because one style pads its text with an invisible run of
     * spaces to hold the timestamp's footprint open — a layout device, not something anybody typed,
     * and not something that should end up on the clipboard.
     */
    val selected: String get() = value.getSelectedText().text.trimEnd()
}

val LocalBubbleSelection = staticCompositionLocalOf<BubbleSelection?> { null }

/** The blue both platforms drag a selection by. */
private val SelectionHandle = Color(0xFF3291EF)

/**
 * A [TextToolbar] that never shows anything.
 *
 * The platform's floating Copy/Share bubble is the wrong object here: the copy button was asked to
 * live in the app bar, and two of them on screen at once — one following your thumb, one pinned to
 * the top — would leave you guessing which was the real one.
 */
val NoTextToolbar: TextToolbar = object : TextToolbar {
    override val status: TextToolbarStatus get() = TextToolbarStatus.Hidden
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit

    override fun hide() = Unit
}

/**
 * The body text of a bubble, in whichever of its two forms the conversation is currently in.
 *
 * Ordinarily this is a plain [Text] — the overwhelmingly common case, and nothing about a message
 * you are only reading should cost a text field. Under selection it becomes a read-only field
 * instead, which is what brings the drag handles with it; the field is styled to the caller's own
 * type so the words do not move when the two swap.
 */
@Composable
fun BubbleText(
    text: AnnotatedString,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    selectable: Boolean = false,
    /**
     * How the message's text is formatted, where any of it is.
     *
     * Empty for almost every message, and empty means "draw this exactly as it always was" — none
     * of the machinery below is reached, so an ordinary bubble is still a single [Text].
     */
    runs: List<TextRun> = emptyList(),
) {
    val selection = LocalBubbleSelection.current
    // Merged onto the ambient style rather than built from scratch, and then used by *both* halves
    // of the swap. A bare `TextStyle(color, fontSize, lineHeight)` inherits none of what
    // `LocalTextStyle` carries — font family, letter spacing, line-height alignment — so the field
    // laid the same words out to a different size than the [Text] beside it, and the bubble changed
    // shape the moment selection was turned on.
    val style = LocalTextStyle.current.merge(
        TextStyle(color = color, fontSize = fontSize, lineHeight = lineHeight),
    )
    // The flags become spans on the words; the effects come back as ranges, since an animation is
    // not something a span can carry. See [rememberFormattedText].
    val (formatted, effects) = rememberFormattedText(text, runs)
    // Links and addresses underlined and tappable. See [rememberLinkedText].
    val styled = rememberLinkedText(formatted)

    if (!selectable || selection == null) {
        // Animated stretches are drawn by hand, glyph by glyph, over a layout of the whole message
        // — see [EffectLayer]. Everything else stays the plain [Text] it has always been.
        //
        // The veil around both is invisible ink, which hides the words and leaves the bubble: it
        // wraps the text rather than the message for exactly that reason. A pass-through on every
        // message that is not inked, which is all but a handful of them.
        val words: @Composable () -> Unit = {
            InkVeil {
                if (effects.isEmpty()) Text(styled, style = style) else EffectLayer(styled, style, effects)
            }
        }
        val link = LocalLinkPreview.current?.takeIf { it.isUseful }
        if (link == null) {
            words()
            return
        }
        // A message that is nothing but the link is the card, the way Apple draws one; a sentence
        // with a link in it keeps its words and gets the card underneath.
        Column {
            val onlyTheLink = text.text.trim() == link.url
            if (!onlyTheLink) words()
            CompositionLocalProvider(LocalLinkPreview provides null) {
                LinkPreviewCard(
                    preview = link,
                    textColor = color,
                    modifier = if (onlyTheLink) Modifier else Modifier.padding(top = 6.dp),
                )
            }
        }
        return
    }

    // Everything, to begin with. Entering the mode with nothing highlighted would put a Copy button
    // in the app bar with nothing to copy, and "the whole message" is what you wanted often enough
    // that it is worth being the starting point you narrow down from.
    LaunchedEffect(styled) { selection.value = TextFieldValue(styled, TextRange(0, styled.length)) }

    // Blue handles, because that is what a selection handle is on both platforms this app borrows
    // from — and because they are the only part of the mode you can actually grab, so they have to
    // read as a control rather than as more of the message.
    //
    // The highlight behind the words is the bubble's own ink instead: a blue wash over an iMessage
    // blue bubble is no wash at all, while a translucent version of whatever the text is written in
    // shows up on every style's fill.
    val selectionColors = remember(color) {
        TextSelectionColors(handleColor = SelectionHandle, backgroundColor = color.copy(alpha = 0.30f))
    }
    // Held to the width the same words would have taken as a [Text].
    //
    // A text field takes every pixel it is offered, so dropped in unconstrained it stretched the
    // bubble out to its full 78% of the row the instant selection was turned on — the message
    // visibly grew under the finger that asked to select it. Measuring the text against the room
    // actually available and pinning the field to that keeps the bubble exactly the shape it was.
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints {
        // Both dimensions, not just the width. A text field derives its minimum height from the
        // style's line height while a [Text] takes it from the laid-out paragraph, and the two
        // disagree by a pixel or two — enough that turning selection on nudged the bubble, and the
        // whole lifted cluster with it, which is the jerk at either end of the fold.
        val size = remember(styled, style, constraints.maxWidth) {
            measurer.measure(styled, style, constraints = Constraints(maxWidth = constraints.maxWidth)).size
        }
        val width = size.width
        CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
            BasicTextField(
                value = selection.value,
                onValueChange = { selection.value = it },
                readOnly = true,
                textStyle = style,
                // No caret: this is a message being read, not one being written.
                cursorBrush = SolidColor(Color.Transparent),
                // The spare pixel is rounding insurance: a field measured a hair narrower than the
                // text it holds rewraps the last word onto a line of its own.
                modifier = Modifier.size(
                    with(density) { (width + 1).toDp() },
                    with(density) { size.height.toDp() },
                ),
            )
        }
    }
}
