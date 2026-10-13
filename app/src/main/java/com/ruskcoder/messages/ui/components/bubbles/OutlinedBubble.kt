package com.ruskcoder.messages.ui.components.bubbles

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.BubbleStyle
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.ui.theme.LocalSettings

/**
 * Whether bubbles drawn under this are outlines rather than fills.
 *
 * A composition local rather than a parameter threaded through three bubble styles and everything
 * that calls them. What changes is one line inside each bubble — its own surface — and *nothing*
 * else: the shape, the padding, the text size, the tail, the maximum width and the run-merging
 * rules are all the same code drawing the same way, which is the whole point. Anything reconstructed
 * alongside them drifts the moment one of those numbers changes, and a hand-rolled copy of the
 * Apple tail path was already wrong at small sizes before anybody touched it.
 */
val LocalBubbleOutlined = compositionLocalOf { false }

/** How heavy the edge is when a bubble is drawn as one. */
private val OutlineWidth = 1.5.dp

/**
 * A bubble's own surface: its fill, or — inside an outlined tree — its edge and nothing behind it.
 *
 * The colour is the same either way, so an outlined bubble is recognisably the bubble it stands in
 * for: blue for yours, grey for theirs, whatever the conversation's own scheme says.
 */
@Composable
fun Modifier.bubbleSurface(color: Color, shape: Shape): Modifier =
    if (!LocalBubbleOutlined.current) {
        background(color, shape)
    } else {
        // Drawn rather than `Modifier.border`, which masks the stroke through a layer the size of
        // the node — and the Apple tail is deliberately drawn *below* that box, into the padding
        // reserved for it, so a border cut it off. Worse the further the text is scaled up, since
        // the tail grows with the corner radius. `drawBehind` has no such bounds, which is exactly
        // why the filled case has never had the problem.
        drawBehind {
            drawOutline(
                outline = shape.createOutline(size, layoutDirection, this),
                color = color,
                style = Stroke(OutlineWidth.toPx()),
            )
        }
    }

/**
 * A message that is no longer the message: one that was unsent, or a version an edit replaced.
 *
 * The conversation's real bubble, drawn as an outline. It is the same composable the thread uses,
 * so it inherits every rule about how a message is laid out — including the ones nobody remembers
 * are there.
 */
@Composable
fun OutlinedBubble(
    text: String,
    mine: Boolean,
    /** Kept for the styles that print a clock inside the bubble; blank simply leaves it out. */
    time: String = "",
    /**
     * What it was, for a message that was not words — a picture, a clip, a file, a contact card.
     *
     * Handed to the same bubble the thread would have drawn it in, so an unsent photo is revealed
     * as the photo rather than as the word "Photo". A picture fills its bubble outright and has no
     * outline to draw, which is correct: the frame is the message.
     */
    attachment: Attachment? = null,
    isFirst: Boolean = true,
    isLast: Boolean = true,
) {
    CompositionLocalProvider(LocalBubbleOutlined provides true) {
        when (LocalSettings.current.bubbleType) {
            BubbleStyle.APPLE -> AppleBubble(
                isFromMe = mine,
                showTail = isLast,
                isFirstInRun = isFirst,
                text = text,
                attachment = attachment,
            )
            BubbleStyle.GOOGLE -> GoogleBubble(
                text = text,
                mine = mine,
                isFirst = isFirst,
                isLast = isLast,
                attachment = attachment,
            )
            BubbleStyle.WHATSAPP -> WhatsAppBubble(
                text = text,
                mine = mine,
                time = time,
                status = DeliveryStatus.SENT,
                showTail = isFirst,
                isLastInRun = isLast,
                attachment = attachment,
            )
        }
    }
}
