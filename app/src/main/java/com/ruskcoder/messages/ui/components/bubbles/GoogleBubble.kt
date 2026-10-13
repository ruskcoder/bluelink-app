package com.ruskcoder.messages.ui.components.bubbles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.ui.components.MemberMenuTarget
import androidx.compose.ui.graphics.graphicsLayer
import com.ruskcoder.messages.ui.components.LocalRevealShift
import com.ruskcoder.messages.ui.components.MemberAvatar
import com.ruskcoder.messages.ui.components.mentionAccent
import com.ruskcoder.messages.ui.components.rememberMentionText
import com.ruskcoder.messages.data.model.ReplyPreview
import com.ruskcoder.messages.data.model.TextRun
import com.ruskcoder.messages.data.model.textScale
import com.ruskcoder.messages.ui.components.AttachmentContent
import com.ruskcoder.messages.ui.components.BubbleText
import com.ruskcoder.messages.ui.components.GoogleReplyQuote
import com.ruskcoder.messages.ui.components.VoiceMessage
import com.ruskcoder.messages.ui.components.isFlushMedia
import com.ruskcoder.messages.ui.components.isBareMedia
import com.ruskcoder.messages.ui.theme.LocalChatTheme
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.util.toComposeColor

/**
 * Google Messages / RCS-style bubble: a plain rounded rectangle with no tail. Consecutive bubbles
 * from the same sender "merge" by shrinking the corners on the edge they share, which is the edge
 * the sender's own messages are stacked against — the right for yours, the left for theirs.
 * Ported from `google-bubble.tsx`.
 */

private val LargeRadius = 19.dp
private val SmallRadius = 4.dp
private val DifferentSenderGap = 9.dp
/** The gutter this style reserves beside a received group message for the sender's face. */
val GoogleGroupGutter = 36.dp

private val SameSenderGap = 3.dp
private val MinWidth = 40.dp

/**
 * How wide a bubble carrying a quote is, at the least.
 *
 * A quote band is two lines of small text across the head of the message, and a one-word reply left
 * the bubble as narrow as that word — so the band was squeezed to a column barely wider than it was
 * tall, with the quote inside it ellipsed to nothing. The floor is set by what the band needs to be
 * legible rather than by what the reply happens to say.
 */
private val QuotedMinWidth = 140.dp

private val GoogleMine = Color(0xFF014C69)
private val GoogleTheirs = Color(0xFF1B2026)
private val GoogleMeta = Color(0x99FFFFFF)

@Composable
fun useGoogleBubbleColors(mine: Boolean): BubbleColors {
    val chatTheme = LocalChatTheme.current
    val settings = LocalSettings.current
    val colorScheme = MaterialTheme.colorScheme
    return when {
        chatTheme != null -> BubbleColors(if (mine) chatTheme.mine else chatTheme.theirs, chatTheme.text, chatTheme.meta)
        settings.materialYou -> BubbleColors(
            background = if (mine) colorScheme.secondaryContainer else colorScheme.surfaceVariant,
            text = if (mine) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant,
            meta = colorScheme.onSurfaceVariant,
        )
        else -> BubbleColors(background = if (mine) GoogleMine else GoogleTheirs, text = Color.White, meta = GoogleMeta)
    }
}

@Composable
fun GoogleBubble(
    text: String,
    mine: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    replyTo: ReplyPreview? = null,
    audio: AudioClip? = null,
    attachment: Attachment? = null,
    sender: GroupMember? = null,
    /** Names an `@` mention in this conversation could point at, so they can be picked out. */
    mentionNames: List<String> = emptyList(),
    /** How this message's text is formatted, where any of it is — see [BubbleText]. */
    runs: List<TextRun> = emptyList(),
    /** Where the bubble itself landed, so reactions can be hung off its edges. */
    onBubbleLayout: ((LayoutCoordinates) -> Unit)? = null,
    selectable: Boolean = false,
) {
    val colors = useGoogleBubbleColors(mine)
    val scale = LocalSettings.current.textScale()
    // A voice message is a capsule of its own whatever it follows: squaring one corner of it to
    // merge with the text above cut into the waveform's own rounded shape.
    val roundAll = audio != null
    // The corners on the sender's own side of the screen: squared where a bubble continues a run,
    // round where the run starts or ends. The other side stays round throughout.
    val mergedTop = if (roundAll || isFirst) LargeRadius else SmallRadius
    val mergedBottom = if (roundAll || isLast) LargeRadius else SmallRadius
    // A bare picture is drawn as itself — rounded to the bubble's own corners so a run still reads
    // as a run, but with no fill and no padding behind it. There is nothing for a bubble to be:
    // no text to sit on a ground, and the inset only framed the photo in a colour.
    val flush = isFlushMedia(attachment, hasQuote = replyTo != null)
    // Mirrored for an incoming message. Squaring the trailing corners on both sides pointed the
    // sharp edge at the middle of the screen for theirs, which is the one place a run has nothing
    // to merge with — a stack of bubbles hinged on the wrong side.
    // Held as the two numbers as well as the shape: a quote band spans the bubble's own top edge
    // and its inner card is rounded concentrically with whatever corner it is sitting under, so it
    // has to be told what that corner actually is rather than assuming the large one.
    val topStartRadius = if (mine) LargeRadius else mergedTop
    val topEndRadius = if (mine) mergedTop else LargeRadius
    val shape = if (mine) {
        RoundedCornerShape(topStart = topStartRadius, bottomStart = LargeRadius, topEnd = topEndRadius, bottomEnd = mergedBottom)
    } else {
        RoundedCornerShape(topEnd = topEndRadius, bottomEnd = LargeRadius, topStart = topStartRadius, bottomStart = mergedBottom)
    }

    val revealShift = LocalRevealShift.current
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                // First in the chain, before anything that draws: a `graphicsLayer` only carries
                // what comes after it, so placed below the background the fill would stay put while
                // the text inside rode the translation. See [RevealShift].
                .graphicsLayer { translationX = revealShift.of(mine) }
                // A bubble with a sender column beside it doesn't cap itself again — the column
                // around it has already taken its share of the row (`bodyWithSender` in the RN
                // source).
                .then(if (sender != null) Modifier else Modifier.bubbleMaxWidth(bubbleWidthFraction(mine)))
                // With a quote panel inside it the bubble sizes to its widest child, so the panel
                // can span the message rather than hugging its own two lines of text. Only for
                // plain text: a waveform or an image has no width to ask it for.
                .then(if (replyTo != null && audio == null && attachment == null) Modifier.width(IntrinsicSize.Max) else Modifier)
                .then(if (flush) Modifier else Modifier.widthIn(min = if (replyTo != null) QuotedMinWidth else MinWidth))
                .then(
                    when {
                        isBareMedia(attachment) -> Modifier
                        flush -> Modifier.clip(shape)
                        else -> Modifier.bubbleSurface(colors.background, shape)
                    },
                )
                // `bubbleSurface` paints a rounded background but does not clip what is drawn over
                // it, which is fine for text inset from every edge and wrong for the quote band:
                // that reaches the bubble's own top edge, and unclipped its square corners sat
                // outside the two rounded ones underneath. Only where there is a band to clip.
                .then(if (replyTo != null) Modifier.clip(shape) else Modifier)
                // Reported outside the bubble's own padding, so callers get the bubble's width
                // rather than its text's.
                .onGloballyPositioned { onBubbleLayout?.invoke(it) },
        ) {
            // The band sits against the bubble's own top edge — no padding of its own to inset it —
            // which is the whole point of it: it is the head of the bubble given over to the quote,
            // sharing the bubble's two top corners. The message's padding therefore belongs to the
            // message rather than to the bubble, and moves down here with it.
            if (replyTo != null) {
                GoogleReplyQuote(
                    name = replyTo.name,
                    text = replyTo.text,
                    textColor = colors.text,
                    metaColor = colors.meta,
                    topStart = topStartRadius,
                    topEnd = topEndRadius,
                )
            }
            Column(
                modifier = if (flush) Modifier else Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                when {
                    audio != null -> VoiceMessage(
                            audio.uri, audio.duration, audio.waveform,
                            colors.text, colors.background, colors.meta,
                            audio.transcript, audio.transcribing, audio.transcriptFailed,
                            audio.transcriptNeedsModel, audio.transcriptOffered,
                            audio.onTranscribe,
                            audio.onPlay,
                        )
                    attachment != null -> AttachmentContent(attachment, colors, flush = flush)
                    else -> BubbleText(
                        rememberMentionText(text, mentionNames, mentionAccent(mine, colors.text)),
                        color = colors.text,
                        fontSize = (15 * scale).sp,
                        lineHeight = (20 * scale).sp,
                        selectable = selectable,
                        runs = runs,
                    )
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(top = if (isFirst) DifferentSenderGap - SameSenderGap else 0.dp)
            .padding(bottom = SameSenderGap),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        // The avatar belongs on the bubble's own line. Left to the default the row aligned to its
        // top, which put the face level with the sender's *name* — a line above the message it was
        // meant to be labelling, and further off the taller the bubble got.
        verticalAlignment = Alignment.Bottom,
    ) {
        if (sender != null) {
            Box(
                modifier = Modifier
                    .width(GoogleGroupGutter)
                    // With the time column, not with the bubble beside it — see [RevealShift].
                    .graphicsLayer { translationX = revealShift.full() },
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (isLast) {
                    MemberMenuTarget(sender) { MemberAvatar(member = sender, size = 28.dp, labelSize = 13.sp) }
                }
            }
            // What is left of the row once the avatar gutter has had its 36dp, less the share the
            // sender's own messages leave open on the other side.
            Column(modifier = Modifier.bubbleMaxWidth(bubbleWidthFraction(mine))) {
                if (isFirst) {
                    MemberMenuTarget(sender, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp)) {
                        Text(
                            sender.name,
                            color = sender.color.toComposeColor(),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                body()
            }
        } else {
            body()
        }
    }
}
