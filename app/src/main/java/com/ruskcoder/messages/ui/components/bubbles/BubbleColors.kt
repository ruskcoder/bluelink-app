package com.ruskcoder.messages.ui.components.bubbles

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The horizontal padding every bubble row keeps between the bubbles and the screen edges.
 *
 * A little more than the 8dp the RN styles used. At that width a bubble sat close enough to the
 * edge that its tail read as touching it, and on a device with rounded display corners the top and
 * bottom messages in a conversation were visibly closer to the glass than the ones in the middle.
 */
val BubbleRowInset = 12.dp

/**
 * Caps a bubble at a fraction of the width it is actually being offered — the `maxWidth: '82%'`
 * (WhatsApp) / `'78%'` (Google, Apple) of the RN styles.
 *
 * The fraction is taken from the incoming constraints rather than from the screen, which is the
 * only way it stays honest once something else has taken a bite out of the row: the row's own
 * inset, the avatar gutter beside a group message, the checkbox column a selection opens. Measured
 * off the screen instead, a long incoming bubble in a group ran past the right edge by exactly the
 * width of the avatar next to it.
 */
fun Modifier.bubbleMaxWidth(fraction: Float): Modifier = layout { measurable, constraints ->
    val cap = if (constraints.hasBoundedWidth) {
        (constraints.maxWidth * fraction).roundToInt().coerceAtLeast(constraints.minWidth)
    } else {
        constraints.maxWidth
    }
    val placeable = measurable.measure(constraints.copy(maxWidth = cap))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * How much of the row a bubble may take, by side.
 *
 * One rule for all three styles. They used to disagree — 0.78 here, 0.82 there, 0.88 beside an
 * avatar — so the same conversation changed shape when the style was switched, for no reason a
 * reader could see.
 *
 * Received bubbles stop a little shorter than your own, and that gap is not taste: an incoming
 * message is the one the time column opens beside, so its trailing edge has to clear the widest
 * time the column ever prints. Your own move out of the column's way instead, so they can run on.
 */
fun bubbleWidthFraction(mine: Boolean): Float = if (mine) 0.78f else 0.76f

/** Bubble fill / text / meta colors for the active bubble style + theme. */
data class BubbleColors(
    val background: Color,
    val text: Color,
    val meta: Color,
)

/**
 * Payload shared by every bubble style for a voice message, mirroring the RN `audio` prop shape.
 *
 * The transcript and its action ride along with the clip rather than being parameters of their own.
 * All three styles hand this straight to [com.ruskcoder.messages.ui.components.VoiceMessage] and
 * none of them look inside it, so anything the voice row needs is better carried here than threaded
 * through three bubble signatures that have no opinion about it.
 */
data class AudioClip(
    val uri: String,
    val duration: Float,
    val waveform: List<Float>,
    /**
     * What the clip says. Null means nobody has asked; empty means they asked and it had no words.
     */
    val transcript: String? = null,
    /** The model is working on this clip right now. */
    val transcribing: Boolean = false,
    /** The last attempt produced nothing, so the action offers to try again. */
    val transcriptFailed: Boolean = false,
    /** No speech model is downloaded yet, so the action offers to go and get one. */
    val transcriptNeedsModel: Boolean = false,
    /**
     * Whether this surface draws the transcript row at all.
     *
     * Separate from [onTranscribe], which only says whether it can be pressed. A lifted bubble draws
     * the row and cannot be pressed; a preview draws nothing.
     */
    val transcriptOffered: Boolean = false,
    /**
     * Runs the transcription. Null hides the action altogether — which is what every surface that
     * draws a bubble outside the conversation wants, and what a device with no speech model gets.
     */
    val onTranscribe: (() -> Unit)? = null,
    /**
     * Called each time playback starts — how a voice message that was sent to us tells its sender
     * it was listened to. Null on our own clips and on every surface outside the conversation.
     */
    val onPlay: (() -> Unit)? = null,
)
