package com.ruskcoder.messages.ui.components.bubbles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.ui.components.MemberMenuTarget
import com.ruskcoder.messages.data.model.ReplyPreview
import com.ruskcoder.messages.data.model.TextRun
import com.ruskcoder.messages.data.model.textScale
import com.ruskcoder.messages.ui.components.AttachmentContent
import com.ruskcoder.messages.ui.components.BubbleText
import androidx.compose.ui.graphics.graphicsLayer
import com.ruskcoder.messages.ui.components.LocalRevealShift
import com.ruskcoder.messages.ui.components.MemberAvatar
import com.ruskcoder.messages.ui.components.isFlushMedia
import com.ruskcoder.messages.ui.components.isBareMedia
import com.ruskcoder.messages.ui.components.annotateMentions
import com.ruskcoder.messages.ui.components.mentionAccent
import com.ruskcoder.messages.ui.components.ReplyQuote
import com.ruskcoder.messages.ui.components.VoiceMessage
import com.ruskcoder.messages.ui.components.WhatsAppTicks
import com.ruskcoder.messages.ui.theme.LocalChatTheme
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.util.toComposeColor
import kotlin.math.ceil

/**
 * WhatsApp-style message bubble: a small-radius rectangle whose top outer corner is squared off
 * and replaced by a vector tail, with the timestamp (and delivery ticks for outgoing messages)
 * tucked into the bottom-right of the bubble. Ported from `whatsapp-bubble.tsx`.
 *
 * The meta row is drawn over the bubble's bottom-right corner and the message text carries a
 * trailing run of transparent non-breaking spaces of roughly the same width. That reproduces
 * WhatsApp's reflow: a short message keeps the timestamp on its own line, and a long one wraps
 * around it instead of colliding with it.
 */

private val TailWidth = 8.dp
private val TailHeight = 13.dp

// Vertical gap between consecutive bubbles.
private val SameSenderGap = 4.dp
private val DifferentSenderGap = 10.dp

// Just enough that a single character doesn't hug the corners.
private val MinWidth = 30.dp

/** The bubble's own padding, and where the meta row sits inside it. */
private val BodyPaddingH = 9.dp
private val BodyPaddingTop = 6.dp
private val BodyPaddingBottom = 8.dp
private val MetaPaddingEnd = 4.5.dp
private val MetaPaddingBottom = 1.dp

/** Breathing room between the last glyph of the message and the time beside it. */
private val MetaTextGap = 6.dp

/** The tick block: the gap before it, and the glyph itself. */
private val TickGap = 3.dp
private val TickSize = 15.dp

/** The meta row's own type size, which does *not* follow the message text's scale. */
private val MetaFontSize = 11.sp

/** The gutter a group's avatars run down, wide enough for the disc plus its breathing room. */
private val AvatarColumn = 36.dp

/** The same, named for the thread to read — see [RevealShift]. */
val WhatsAppGroupGutter = AvatarColumn
private val AvatarSize = 28.dp

private data class WhatsAppPalette(val out: Color, val incoming: Color, val text: Color, val meta: Color)

private val WaDark = WhatsAppPalette(Color(0xFF134D37), Color(0xFF202C33), Color(0xFFE9EDEF), Color(0xFF8696A0))
private val WaLight = WhatsAppPalette(Color(0xFFD9FDD3), Color(0xFFFFFFFF), Color(0xFF111B21), Color(0xFF667781))

/** Bubble fill / text / meta colors for the active theme. */
@Composable
fun useWhatsAppBubbleColors(mine: Boolean): BubbleColors {
    val chatTheme = LocalChatTheme.current
    val settings = LocalSettings.current
    val colorScheme = MaterialTheme.colorScheme
    val palette = if (settings.mode == com.ruskcoder.messages.data.model.ThemeMode.DARK) WaDark else WaLight
    return when {
        chatTheme != null -> BubbleColors(if (mine) chatTheme.mine else chatTheme.theirs, chatTheme.text, chatTheme.meta)
        settings.materialYou -> BubbleColors(
            background = if (mine) colorScheme.secondaryContainer else colorScheme.surfaceVariant,
            text = if (mine) colorScheme.onSecondaryContainer else colorScheme.onSurfaceVariant,
            meta = colorScheme.onSurfaceVariant,
        )
        else -> BubbleColors(background = if (mine) palette.out else palette.incoming, text = palette.text, meta = palette.meta)
    }
}

private fun buildTailPath(size: Size): Path {
    val sx = size.width / 8f
    val sy = size.height / 13f
    fun px(x: Float) = x * sx
    fun py(y: Float) = y * sy
    return Path().apply {
        moveTo(px(0f), py(0f))
        lineTo(px(6.5f), py(0f))
        cubicTo(px(7.9f), py(0f), px(8.5f), py(1.7f), px(7.5f), py(2.7f))
        cubicTo(px(4.6f), py(5.6f), px(2f), py(9f), px(0f), py(13f))
        close()
    }
}

@Composable
private fun Tail(mine: Boolean, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(TailWidth, TailHeight).graphicsLayer(scaleX = if (mine) 1f else -1f)) {
        drawPath(buildTailPath(size), color = color)
    }
}

@Composable
fun WhatsAppBubble(
    text: String,
    mine: Boolean,
    time: String,
    status: DeliveryStatus = DeliveryStatus.READ,
    showTail: Boolean = true,
    /** Last of its run, which is where a group's avatar sits — at the foot of the run, not its head. */
    isLastInRun: Boolean = true,
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
    /** Changed since it was sent, which WhatsApp says in the meta row just ahead of the clock. */
    edited: Boolean = false,
) {
    val colors = useWhatsAppBubbleColors(mine)
    val scale = LocalSettings.current.textScale()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val bodyStyle = TextStyle(fontSize = (15 * scale).sp, lineHeight = (20 * scale).sp)
    /**
     * The run of transparent spaces that holds the meta row's footprint open inside the text flow.
     *
     * Measured rather than counted. A fixed number of spaces is a fixed number of *glyphs*, and a
     * glyph grows with the message's text size while the time beside it does not — so turning the
     * text up left a widening band of nothing between the last word and the clock. This asks how
     * wide the meta actually is, asks how wide one space is at the current size, and reserves
     * exactly as many as it takes to cover the one with the other.
     */
    // "Edited" rides in front of the clock rather than under the bubble: WhatsApp keeps every fact
    // about a message inside it, and this is one more of them.
    val clock = if (edited) "Edited $time" else time
    val metaSpacer = remember(clock, mine, scale, measurer, density) {
        val clockPx = measurer.measure(clock, TextStyle(fontSize = MetaFontSize)).size.width
        val ticksPx = if (mine) with(density) { (TickGap + TickSize).toPx() } else 0f
        // Only the part that pokes into the text's column has to be held open: the meta stops
        // nearer the bubble's edge than the text does, and that difference is already free.
        val reservePx = clockPx + ticksPx + with(density) { (MetaPaddingEnd + MetaTextGap - BodyPaddingH).toPx() }
        val spacePx = measurer.measure(" ", bodyStyle).size.width.coerceAtLeast(1)
        " ".repeat(ceil(reservePx / spacePx).toInt().coerceIn(1, 40))
    }
    // Only plain text reflows around the overlaid meta row; every other kind is a fixed block, so
    // its timestamp sits in normal flow underneath instead.
    val inFlowMeta = audio != null || attachment != null
    // A bare picture is the message: no fill, no padding and no tail, just the photo with its
    // corners taken off. The clock goes on the picture rather than under it, which is the only
    // place left for it once the bubble is gone.
    val flush = isFlushMedia(attachment, hasQuote = replyTo != null)
    val shape = RoundedCornerShape(
        topStart = if (showTail && !mine && !flush) 0.dp else 10.dp,
        topEnd = if (showTail && mine && !flush) 0.dp else 10.dp,
        bottomStart = 10.dp,
        bottomEnd = 10.dp,
    )
    val meta: @Composable (Modifier) -> Unit = { metaModifier ->
        Row(modifier = metaModifier, verticalAlignment = Alignment.CenterVertically) {
            Text(clock, color = colors.meta, fontSize = MetaFontSize)
            if (mine) {
                Spacer(modifier = Modifier.width(TickGap))
                WhatsAppTicks(status, colors.meta)
            }
        }
    }

    val revealShift = LocalRevealShift.current
    val bubble: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                // First in the chain, before anything that draws: a `graphicsLayer` only carries
                // what comes after it, so placed below the background the fill would stay put while
                // the text inside rode the translation. See [RevealShift].
                .graphicsLayer { translationX = revealShift.of(mine) }
                // Beside an avatar gutter there is less row left to take a share of, so the share
                // is a larger one — the same reasoning as the other two styles.
                .bubbleMaxWidth(bubbleWidthFraction(mine))
                // The tail is drawn *outside* the bubble body, so without this it eats the row's
                // padding and the bubble reads as touching the screen edge. Reserving the tail's
                // width here puts its tip exactly on the row's padding line instead.
                //
                // Reserved whether or not this bubble has a tail: a run's later bubbles line up
                // with the first one's *body*, not with the tip of a tail they don't have — which
                // is what used to push them a tail's width toward the screen edge.
                .padding(start = if (!mine) TailWidth - 1.dp else 0.dp, end = if (mine) TailWidth - 1.dp else 0.dp),
        ) {
            if (showTail && !flush) {
                Tail(
                    mine = mine,
                    color = colors.background,
                    modifier = Modifier
                        .align(if (mine) Alignment.TopEnd else Alignment.TopStart)
                        .offset(x = if (mine) TailWidth - 1.dp else -(TailWidth - 1.dp)),
                )
            }
            if (flush && attachment != null) {
                Box(
                    modifier = Modifier
                        .then(if (isBareMedia(attachment)) Modifier else Modifier.clip(shape))
                        .onGloballyPositioned { onBubbleLayout?.invoke(it) },
                ) {
                    AttachmentContent(attachment, colors, flush = true)
                    // On the picture, in a soft dark pill: the bubble that used to carry the clock
                    // is gone, and a photo is not a background you can print grey text over.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color.Black.copy(alpha = 0.42f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(clock, color = Color.White, fontSize = MetaFontSize)
                        if (mine) {
                            Spacer(modifier = Modifier.width(TickGap))
                            WhatsAppTicks(status, Color.White)
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .widthIn(min = MinWidth)
                        .bubbleSurface(colors.background, shape)
                        .onGloballyPositioned { onBubbleLayout?.invoke(it) },
                ) {
                    Column(
                        // The bottom padding leaves room for the meta row overlaid below.
                        //
                        // The top is the quote's own inset when there is one, so the card sits the
                        // same distance from the bubble's top edge as from its sides — the body's
                        // usual 6dp against 9dp at the sides put it visibly high in its own space.
                        // The message below keeps its own spacing from the card, which the card
                        // carries as its bottom gap.
                        modifier = Modifier.padding(
                            start = BodyPaddingH,
                            end = BodyPaddingH,
                            top = if (replyTo != null) BodyPaddingH else BodyPaddingTop,
                            bottom = BodyPaddingBottom,
                        ),
                    ) {
                        if (replyTo != null) ReplyQuote(replyTo.name, replyTo.text, colors.meta, colors.background)
                        when {
                            audio != null -> VoiceMessage(
                            audio.uri, audio.duration, audio.waveform,
                            colors.text, colors.background, colors.meta,
                            audio.transcript, audio.transcribing, audio.transcriptFailed,
                            audio.transcriptNeedsModel, audio.transcriptOffered,
                            audio.onTranscribe,
                            audio.onPlay,
                        )
                            attachment != null -> AttachmentContent(attachment, colors)
                            else -> BubbleText(
                                // The trailing run reserves the meta row's footprint inside the text
                                // flow without drawing anything — see the file's doc comment. Under
                                // selection it is still there, holding the same space; what it is
                                // kept out of is the clipboard, which [BubbleSelection] handles.
                                buildAnnotatedString {
                                    append(annotateMentions(text, mentionNames, mentionAccent(mine, colors.text)))
                                    withStyle(SpanStyle(color = Color.Transparent)) { append(metaSpacer) }
                                },
                                color = colors.text,
                                fontSize = bodyStyle.fontSize,
                                lineHeight = bodyStyle.lineHeight,
                                selectable = selectable,
                                runs = runs,
                            )
                        }
                        // The audio and attachment rows aren't padded with a text spacer the way the
                        // message text is, so their meta sits in normal flow below them.
                        if (inFlowMeta) meta(Modifier.fillMaxWidth().padding(top = 4.dp).wrapContentWidth(Alignment.End))
                    }
                    // Hard against the bubble's bottom edge, inside less of a margin than the text
                    // keeps, so the time and its ticks sit lower than the last line beside them.
                    if (!inFlowMeta) meta(Modifier.align(Alignment.BottomEnd).padding(end = MetaPaddingEnd, bottom = MetaPaddingBottom))
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            // Extra breathing room where the sender changes and the tail reappears.
            .padding(top = if (showTail) DifferentSenderGap - SameSenderGap else 0.dp)
            .padding(bottom = SameSenderGap),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (sender != null) {
            // Who sent it is a face beside the message and a name above it, the way the other two
            // styles say it — rather than a "~ handle" line printed inside the bubble, which read
            // as part of what the person had written. The gutter is kept by every bubble in the
            // run so they all line up; only the last one puts a face in it.
            Box(
                modifier = Modifier
                    .width(AvatarColumn)
                    // With the time column, not with the bubble beside it — see [RevealShift].
                    .graphicsLayer { translationX = revealShift.full() },
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (isLastInRun) {
                    MemberMenuTarget(sender) { MemberAvatar(member = sender, size = AvatarSize, labelSize = 13.sp) }
                }
            }
            Column {
                if (showTail) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.padding(start = 4.dp, bottom = 3.dp, end = 8.dp),
                    ) {
                        MemberMenuTarget(sender, modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                sender.name,
                                color = sender.color.toComposeColor(),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!sender.phone.isNullOrBlank()) {
                            Text(
                                sender.phone,
                                color = colors.meta,
                                fontSize = 12.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
                bubble()
            }
        } else {
            bubble()
        }
    }
}

/** The call-log card WhatsApp drops inline in the thread. */
@Composable
fun WhatsAppCallBubble(mine: Boolean, title: String, detail: String, time: String, missed: Boolean = false) {
    val colors = useWhatsAppBubbleColors(mine)
    val errorColor = MaterialTheme.colorScheme.error

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = SameSenderGap),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .bubbleMaxWidth(bubbleWidthFraction(mine))
                .padding(start = if (!mine) TailWidth - 1.dp else 0.dp, end = if (mine) TailWidth - 1.dp else 0.dp),
        ) {
            Tail(
                mine = mine,
                color = colors.background,
                modifier = Modifier
                    .align(if (mine) Alignment.TopEnd else Alignment.TopStart)
                    .offset(x = if (mine) TailWidth - 1.dp else -(TailWidth - 1.dp)),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(colors.background, RoundedCornerShape(topStart = if (!mine) 0.dp else 10.dp, topEnd = if (mine) 0.dp else 10.dp, bottomStart = 10.dp, bottomEnd = 10.dp))
                    .padding(vertical = 10.dp, horizontal = 10.dp),
            ) {
                Box(modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Icon(
                        when {
                            missed -> Icons.AutoMirrored.Filled.CallMissed
                            mine -> Icons.AutoMirrored.Filled.CallMade
                            else -> Icons.AutoMirrored.Filled.CallReceived
                        },
                        contentDescription = null,
                        tint = if (missed) errorColor else colors.text,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f, fill = false).padding(horizontal = 12.dp)) {
                    Text(title, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(detail, color = colors.meta, fontSize = 14.sp, modifier = Modifier.padding(top = 1.dp))
                }
                Text(time, color = colors.meta, fontSize = 11.sp, modifier = Modifier.align(Alignment.Bottom))
            }
        }
    }
}
