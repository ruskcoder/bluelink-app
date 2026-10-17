package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.BubbleStyle
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.data.model.MessageReceipts
import kotlin.math.roundToInt
import com.ruskcoder.messages.ui.components.bubbles.AppleTailReserve
import com.ruskcoder.messages.ui.components.bubbles.BubbleRowInset
import com.ruskcoder.messages.ui.components.bubbles.appleTailClearance
import com.ruskcoder.messages.ui.components.bubbles.useAppleBubbleColors
import com.ruskcoder.messages.util.formatReadTime

/**
 * How far an outgoing message has got, rendered in each bubble style's own idiom: WhatsApp turns
 * its ticks blue, Google shows a filled badge next to the time, Apple prints a caption under the
 * last bubble in the thread. Ported from `delivery-status.tsx`.
 */


/**
 * The type size every Apple caption is set at — the receipt, the tapped time, the failure.
 *
 * A point down from where these started. The line under a bubble is a footnote to it and was
 * competing with the message for the eye at the old size, which is exactly what a caption must not
 * do — but two points down was past legible. Change it here and every Apple status line follows.
 */
private val AppleCaptionSize = 10.sp

/** How far the whole Apple line under a bubble is lifted toward it. See [appleCaptionLine]. */
private val AppleCaptionRise = 6.dp

/**
 * Hangs the Apple line off the bubble's straight edge and shares the room under it with the tail.
 *
 * The bubble no longer pads itself for its tail ([AppleTailReserve]), so this row starts at the
 * straight edge whether there is a tail or not. That gives a tailed and an untailed bubble the same
 * caption distance, and the caption sits beside the tail rather than under it (see
 * [appleTailClearance]). Open, the row claims whichever is taller: the tail's overhang, or the
 * line, lifted by [AppleCaptionRise].
 *
 * Between shut and open the height is blended on the slot's own [progress], not taken as a plain
 * max. A max sat on the tail's height for most of a grow and only started pushing near the end, so
 * in a handover the old line shrank from the first frame while the new one seemed to wait. Blended,
 * both rows move together from the same frame.
 */
fun Modifier.appleCaptionLine(tailReserve: AppleTailReserve, progress: SlotProgress): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val f = progress.value().coerceIn(0f, 1f)
    val rise = AppleCaptionRise.toPx() * f
    val tail = tailReserve.height().toPx()
    // tail·(1−f) + max(tail, full − rise)·f, written with the slot's current height (full·f).
    val height = tail * (1f - f) + maxOf(tail * f, placeable.height - rise)
    layout(placeable.width, height.roundToInt().coerceAtLeast(0)) { placeable.place(0, -rise.roundToInt()) }
}

/** How far open a [ReceiptSlot] is, 0 to 1, for a layout around it to read. */
class SlotProgress {
    var value: () -> Float = { 0f }
}

/** The same idea, gentler: the Google bubble has no tail, so there is no notch to climb into. */
private val GoogleReceiptRise = 5.dp

/**
 * How far in from the row's trailing edge the receipt line sits.
 *
 * Pulled well inside the bubble's own corner rather than tucked under it. A Google bubble is
 * rounded hard at that corner, so a line flush with the row's edge sat out past the curve with
 * nothing above it — reading as a caption on the screen's margin rather than on the message.
 */
private val GoogleReceiptInset = 10.dp

/** WhatsApp's blue double tick. */
private val WhatsAppRead = Color(0xFF53BDEB)

/** Google Messages' RCS accent, taken from its dark theme. */
private val GoogleRead = Color(0xFF8AB4F8)

/** How long a receipt takes to grow in or shrink out, and the ease-in-out it moves on. */
private const val ReceiptGrowMs = 420
/** Slow off the mark and slower still into the stop — nothing about this line is in a hurry. */
private val ReceiptEasing = CubicBezierEasing(0.5f, 0f, 0.2f, 1f)

private val STATUS_LABELS = mapOf(
    DeliveryStatus.SENT to "Sent",
    DeliveryStatus.DELIVERED to "Delivered",
    DeliveryStatus.READ to "Read",
    DeliveryStatus.PLAYED to "Played",
)

/**
 * The word under the bubble, with the one qualification a delivery can carry.
 *
 * "Delivered Quietly" is not a fourth state — the message is delivered, and every other part of the
 * app treats it as such. It is the same fact with the other end's Focus noted on it, which is why
 * it is spelt as a longer word here rather than as another entry in the map above.
 */
private fun statusLabel(status: DeliveryStatus, quietly: Boolean): String {
    val word = STATUS_LABELS[status] ?: return ""
    return if (quietly && status == DeliveryStatus.DELIVERED) "$word Quietly" else word
}

/**
 * A send that did not happen, in the same slot the receipt would have taken.
 *
 * Every style gets this one, unchanged: a message that never left is not a delivery state to render
 * in an idiom, it is the absence of one, and it should read the same wherever it appears.
 *
 * Two words and no more. The reason Apple gave is often a sentence of network diagnostics, and
 * printed under the bubble it wrapped to three lines, pushed the conversation around and could not
 * be dismissed or acted on. It belongs behind the badge beside the message instead — see
 * [FailedBadge], which is the thing you can actually press.
 */
@Composable
fun FailedReceipt(modifier: Modifier = Modifier) {
    Text(
        "Not Delivered",
        color = MaterialTheme.colorScheme.error,
        fontSize = AppleCaptionSize,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

/**
 * The mark beside a message that never left: a red disc with an "i" in it, opening the reason.
 *
 * Beside the bubble rather than under it, because the caption underneath says only *that* the send
 * failed — this is the part you can act on, and a failure the user can do nothing about is a
 * failure they will keep re-reading. It is a full touch target in its own right rather than a
 * decoration on the bubble: a tap on the bubble already means something else in every style.
 *
 * Material's `Info` glyph is a disc with the letter knocked *out* of it, so on its own the "i"
 * shows whatever is behind the badge — the wallpaper — and stops reading as a letter at all. A
 * plain disc of the page's own colour sits behind it to give the counter something solid to be.
 */
@Composable
fun FailedBadge(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(FailedBadgeTouchTarget)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .size(FailedBadgeSize * 0.8f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onError),
        )
        Icon(
            Icons.Rounded.Info,
            contentDescription = "Not delivered",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(FailedBadgeSize),
        )
    }
}

/** The disc itself, and the tap area around it — Material's minimum, so it can actually be hit. */
val FailedBadgeSize = 20.dp
val FailedBadgeTouchTarget = 36.dp

/** The tick glyphs tucked into the bottom-right of a WhatsApp bubble. */
@Composable
fun WhatsAppTicks(status: DeliveryStatus, color: Color) {
    if (status == DeliveryStatus.SENT) {
        Icon(Icons.Default.Check, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
    } else {
        Icon(
            Icons.Default.DoneAll,
            contentDescription = null,
            tint = if (status.isRead) WhatsAppRead else color,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * Google Messages' receipt: send time + a badge, filled blue once read. Hangs off the bottom-right
 * of the last outgoing bubble — the `marginTop`/`marginRight` from the RN source's `googleRow`.
 */
@Composable
fun GoogleReceipt(
    status: DeliveryStatus,
    time: String,
    modifier: Modifier = Modifier,
    groupLabel: String? = null,
    /**
     * Google says so inside its own receipt rather than under the bubble.
     *
     * Only Apple grew a standing label for this — see [MessageMetaLabels] — because only Apple's
     * conversation has the room under a bubble for one. Here it stays a footnote on the line.
     */
    edited: Boolean = false,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val isRead = status.isRead
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // Flush with the bubble's trailing edge above it — the same 8dp inset the bubble row keeps.
        // Tight under the bubble's trailing edge: the line belongs to the message above it, and
        // the space it was keeping read as a gap between two messages instead.
        // The row is already inset by BubbleRowInset, wider than GoogleReceiptInset, so this has to
        // push outward — an offset, since a negative padding throws.
        modifier = modifier.offset(x = BubbleRowInset - GoogleReceiptInset, y = -GoogleReceiptRise),
    ) {
        // A dot keeps the two facts apart: Google has no blue-link idiom to borrow, and a
        // coloured word here would read as a warning.
        Text(if (edited) "Edited · ${groupLabel ?: time}" else (groupLabel ?: time), color = muted, fontSize = 11.sp)
        Spacer(modifier = Modifier.width(4.dp))
        GoogleReceiptTicks(status, tint = if (isRead) GoogleRead else muted, size = 14.dp)
    }
}


/**
 * Google's receipt under a bubble: one check-circle once the message has left, a second stacked
 * behind it once it has arrived, and both filled in once it has been read.
 *
 * Three states told by two independent things — how many circles, and whether they are filled —
 * which is what makes them tellable apart at fourteen dip. A single glyph swapping between three
 * outlines was three variations on the same circle and read as one state throughout.
 *
 * The pair is stacked rather than set side by side: the front badge covers the last quarter of the
 * one behind it, so the two read as one mark that has gained a layer rather than as two separate
 * ticks. A gap the width of the glyph's own stroke is cut out of the rear badge where the front one
 * crosses it — without it two rings intersect into a Venn diagram, and two filled discs merge into
 * a single blob.
 */
@Composable
fun GoogleReceiptTicks(status: DeliveryStatus, tint: Color, modifier: Modifier = Modifier, size: Dp = 15.dp) {
    val stacked = status != DeliveryStatus.SENT
    val filled = status.isRead
    val glyph = if (filled) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle
    val step = size * StackStep

    Box(modifier = modifier.size(width = if (stacked) size + step else size, height = size)) {
        if (stacked) {
            Icon(
                glyph,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .size(size)
                    // Offscreen so the cut below has a layer of its own to erase out of, rather
                    // than punching a hole through everything drawn under the badge.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // The glyph's circle stops 10/24ths out from the middle of Material's icon
                        // box and its stroke is 2/24ths wide, so a bite of exactly half the box is
                        // the front badge plus one stroke of clearance.
                        drawCircle(
                            color = Color.Black,
                            radius = this.size.minDimension / 2f,
                            center = Offset(step.toPx() + this.size.width / 2f, this.size.height / 2f),
                            blendMode = BlendMode.Clear,
                        )
                    },
            )
        }
        Icon(
            glyph,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size).offset(x = if (stacked) step else 0.dp),
        )
    }
}

/** How far the front badge sits along the one behind it — covering its last quarter. */
private const val StackStep = 0.75f

/**
 * The chat list's receipt: one circular badge whose *contents* count the message's progress.
 *
 * A single tick once it has left, two once it has arrived, and the circle fills in once it has been
 * read. Kept to one badge, unlike the stacked pair under a bubble: this sits inline at the head of
 * a preview line, where a mark that changes width would shunt the words along with it.
 */
@Composable
fun GoogleStatusBadge(status: DeliveryStatus, tint: Color, modifier: Modifier = Modifier, size: Dp = 15.dp) {
    val filled = status.isRead
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .then(
                if (filled) {
                    Modifier.background(tint, CircleShape)
                } else {
                    Modifier.border(size * BadgeGlyph * IconStroke, tint, CircleShape)
                },
            ),
    ) {
        Icon(
            if (status == DeliveryStatus.SENT) Icons.Rounded.Check else Icons.Rounded.DoneAll,
            contentDescription = null,
            // On a filled disc the tick is knocked out of it rather than drawn over it.
            tint = if (filled) MaterialTheme.colorScheme.surface else tint,
            // A double tick is wider than a single one, so it is given more of the disc to sit in
            // — sized to the same optical weight rather than to the same box.
            modifier = Modifier.size(size * if (status == DeliveryStatus.SENT) 0.58f else BadgeGlyph),
        )
    }
}

/**
 * The ring around the chat list's badge, as a share of the badge.
 *
 * Material draws its glyphs with a 2dp stroke on a 24dp grid, so the ring is derived from the tick's
 * own drawn size rather than set to a round number of its own: at any badge size the circle is
 * exactly as heavy as the mark inside it. A ring drawn thicker than that read as a button with a
 * tick on it instead of as one drawn shape.
 */
private const val IconStroke = 2f / 24f
private const val BadgeGlyph = 0.72f

/**
 * How far an outgoing message has got in a group, as a count rather than a single word.
 *
 * "Delivered" tells you nothing useful once there is more than one recipient — the question is how
 * many of them. Returns null for a one-to-one conversation, where the plain state is the whole
 * answer and the count would just be noise.
 */
fun groupReceiptLabel(receipts: MessageReceipts?, recipientCount: Int): String? {
    if (recipientCount < 2) return null
    val read = receipts?.readCount() ?: 0
    val delivered = receipts?.deliveredCount() ?: 0
    return when {
        read >= recipientCount -> "Read by everyone"
        read > 0 -> "Read by $read of $recipientCount"
        // Not counted. A message goes to a group in one act — Apple takes it once and fans it out —
        // so "delivered to some of them" is not a state the send can be in, and the fraction was
        // reporting how many receipts had *come back to this phone*, which is a fact about the
        // network and not about the message. It sat at "Delivered to 1 of 2" indefinitely whenever
        // a participant's device never bothered to acknowledge. Either it left or it did not.
        //
        // Reads keep their count, because those genuinely happen one person at a time and the
        // number is the answer to a question worth asking.
        delivered > 0 -> "Delivered"
        else -> "Sent"
    }
}

/**
 * iMessage's receipt: the state, and the time it was read, in one small grey line under the last
 * outgoing bubble.
 *
 * A change of state is only a change of words — the line is already there, so nothing moves.
 * Arriving and leaving belong to [ReceiptSlot] around it.
 */
@Composable
fun AppleReceipt(
    status: DeliveryStatus,
    time: String,
    modifier: Modifier = Modifier,
    groupLabel: String? = null,
    /** Delivered into a Focus, so it arrived without making a sound. See [statusLabel]. */
    quietly: Boolean = false,
    /**
     * When it was read — or, for a [DeliveryStatus.PLAYED] message, when it was listened to — in
     * epoch millis; null falls back to [time] for a receipt that kept none.
     */
    readAt: Long? = null,
) {
    // The state is the thing being reported, so it carries the weight. The time beside it is a
    // detail hanging off that word rather than a second announcement, and printing the pair at one
    // weight made the line read as a single long label instead of a state with a timestamp on it.
    val label = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(groupLabel ?: statusLabel(status, quietly)) }
        if (groupLabel == null && status.isRead) {
            append(" ${readAt?.let { formatReadTime(it) } ?: time}")
        }
    }
    Text(
        label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = AppleCaptionSize,
        modifier = modifier,
    )
}

/**
 * The caption a tap on a bubble opens underneath it: the same small line the newest outgoing
 * message carries permanently, offered one message at a time anywhere else in the conversation.
 *
 * Each style answers in its own idiom, so a tapped message says exactly what the bottom of the
 * thread would if it were the newest one. Two cases have no delivery state to report and fall back
 * to the one thing they do know — when the message was sent: an incoming message, which was never
 * ours to deliver, and a WhatsApp outgoing one, whose ticks are drawn on the bubble itself and
 * which therefore has no caption of its own to borrow.
 */
@Composable
fun TappedStatusLine(
    mine: Boolean,
    bubbleType: BubbleStyle,
    status: DeliveryStatus,
    time: String,
    groupLabel: String?,
    /** Forwarded to Google's line only; Apple carries the word in its own standing label. */
    edited: Boolean = false,
    /** Apple's line only, for the same reason: it is the only one that prints the word at all. */
    quietly: Boolean = false,
    /** Apple's line only: when the message was read. See [AppleReceipt]. */
    readAt: Long? = null,
    modifier: Modifier = Modifier,
) {
    when {
        mine && bubbleType == BubbleStyle.APPLE -> AppleReceipt(status, time, modifier, groupLabel, quietly, readAt)
        mine && bubbleType == BubbleStyle.GOOGLE -> GoogleReceipt(status, time, modifier, groupLabel, edited)
        // WhatsApp says it on the bubble itself, beside the clock — so the caption here is only
        // ever the bare time, the same as it is for an incoming message.
        else -> Text(
            time,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = if (bubbleType == BubbleStyle.APPLE) AppleCaptionSize else 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = modifier,
        )
    }
}

/**
 * The space a receipt occupies, and the only thing that ever animates it open or shut.
 *
 * A receipt belongs to the newest outgoing message, so sending one hands it from the message above
 * to the message just sent. Rendered as a plain removal and insertion that reads as a stutter: the
 * old row loses the line's height in a single frame — dropping the whole conversation — and the new
 * row then takes it back over the length of an animation. So both rows keep a slot through the
 * handover and the two run in opposite directions at the same time. The heights cancel, the
 * conversation above never moves, and what you see is the line travelling from one message to the
 * next.
 *
 * Height follows the same curve as the scale, and the content is centred in whatever height the
 * slot currently claims, so the words spread from their own middle rather than unrolling from a
 * corner.
 */
@Composable
fun ReceiptSlot(
    visible: Boolean,
    modifier: Modifier = Modifier,
    /** Handed the slot's progress, for a layout around it — see [appleCaptionLine]. */
    progress: SlotProgress? = null,
    content: @Composable () -> Unit,
) {
    // Starts settled: a receipt already on screen when a row is composed — a chat being opened, a
    // row scrolled back into view — was never an arrival, and must not play one.
    val shown = remember { Animatable(if (visible) 1f else 0f) }
    progress?.value = { shown.value }
    LaunchedEffect(visible) {
        val target = if (visible) 1f else 0f
        if (shown.value != target) shown.animateTo(target, tween(ReceiptGrowMs, easing = ReceiptEasing))
    }
    // Whatever is inside stays composed until the slot has finished shutting, so the way out shrinks
    // the words rather than an empty box. A shut slot composes nothing at all.
    val open by remember { derivedStateOf { shown.value > 0f } }
    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val height = (placeable.height * shown.value).roundToInt()
                layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
            }
            .graphicsLayer {
                scaleX = shown.value
                scaleY = shown.value
                alpha = shown.value
                transformOrigin = TransformOrigin.Center
            },
    ) {
        if (visible || open) content()
    }
}


/**
 * The room the delivery line takes *beside* the labels standing under a message, rather than under
 * them.
 *
 * [ReceiptSlot] opens downward, which is right when the line is the only thing there — the
 * conversation eases apart to make space for it. It is wrong the moment the message already carries
 * a standing label: the label would be pushed down a line for a caption that belongs on the same
 * line as it. So this one sits on that same line, beside them.
 *
 * Not animated: the words simply appear, change, or go. Revealing it by width read as the text being
 * wiped in from one side.
 */
@Composable
fun StatusSlot(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (visible) Box(modifier = modifier) { content() }
}

/**
 * The one container for everything on the line under a message: the replay control, the status
 * and the standing labels.
 *
 * It owns the inset from the bubble's edge and the space between the pieces. They used to carry
 * their own paddings and borrow each other's as gaps, so the line only held together when the
 * right ones happened to be present. On Apple the inset also clears the tail, the same for a bubble
 * with a tail and one without, so the line does not jump sideways when a message loses its tail.
 */
@Composable
fun StatusLine(
    mine: Boolean,
    bubbleType: BubbleStyle,
    modifier: Modifier = Modifier,
    /**
     * Apple only: whether the line sits in past the tail. True under a tailed bubble and for the
     * standing receipt while it travels to a new message. False for a line tapped open under an
     * untailed bubble, which has no tail to clear and sits against the edge.
     */
    clearTail: Boolean = true,
    content: @Composable () -> Unit,
) {
    val outer = BubbleRowInset + if (bubbleType == BubbleStyle.APPLE && clearTail) appleTailClearance() else 0.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MetaLabelGap),
        modifier = modifier.padding(top = 1.dp, start = if (mine) 0.dp else outer, end = if (mine) outer else 0.dp),
    ) {
        content()
    }
}

/**
 * What a message says about itself under its own bubble, permanently: that it was rewritten, and
 * how many replies it has drawn.
 *
 * Neither is a delivery state, which is why neither waits for a tap to be shown. "Edited" used to
 * be a footnote inside the receipt, so a message you had corrected only admitted it while its
 * status happened to be open — and a reply count that only appears on demand is one nobody ever
 * finds. Standing here they are simply part of the message, and the status line opens *beside*
 * them when it is asked for. See [StatusSlot].
 *
 * Drawn in the conversation's own colour: these are the parts of the line that are about the
 * message rather than about its delivery, and a recoloured chat recolours them with it.
 */
@Composable
fun MessageMetaLabels(
    mine: Boolean,
    bubbleType: BubbleStyle,
    edited: Boolean,
    replyCount: Int,
    /** Opens the versions this message had before. Null where there are none kept to read back. */
    onShowEdits: (() -> Unit)? = null,
    /** Lifts the whole thread out of the conversation. The count is what you press to read it. */
    onOpenThread: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (!edited && replyCount <= 0) return
    // Read off your own bubbles whoever sent the message: this is chrome hung on the conversation,
    // and the conversation's colour is the one that identifies it. At full strength, unlike the
    // footnote this used to be — standing on its own rather than buried in a grey caption, a
    // knocked-back blue read as a disabled link.
    val accent = useAppleBubbleColors(mine = true).background
    // Sat on the same line the receipt would be, by the same numbers — the two take turns in one
    // slot, and a label that sits a few dip off where the caption sits reads as the line moving.
    val rise = when (bubbleType) {
        BubbleStyle.GOOGLE -> GoogleReceiptRise
        else -> 0.dp
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.offset(y = -rise),
    ) {
        if (edited) {
            Text(
                "Edited",
                color = accent,
                fontSize = MetaLabelSize,
                modifier = if (onShowEdits == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onShowEdits,
                    )
                },
            )
        }
        if (edited && replyCount > 0) Spacer(modifier = Modifier.width(MetaLabelGap))
        if (replyCount > 0) {
            Text(
                if (replyCount == 1) "1 Reply" else "$replyCount Replies",
                color = accent,
                fontSize = MetaLabelSize,
                modifier = if (onOpenThread == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Show replies",
                        onClick = onOpenThread,
                    )
                },
            )
        }
    }
}

/** A shade up from the caption beside them: these labels are the message's own, not a note on it. */
private val MetaLabelSize = 10.sp

/** Enough to keep two labels apart without a separator between them. */
private val MetaLabelGap = 8.dp

/** The arrow beside "Replay", set to the cap height of the word rather than to the line. */
private val MetaIconSize = 12.dp

/**
 * The offer to play a message's send effect again.
 *
 * An effect fires once, when the message lands, and a message you were not looking at when it
 * arrived has already spent it. So it says so, and offers it back — which is what iMessage does and
 * the only way an effect is ever seen twice.
 *
 * Its own label rather than one of [MessageMetaLabels], because it sits on the other side of the
 * status line from them: "Edited" and a reply count are facts *about* the message and read after
 * whatever the line says about delivery, while this is a thing you can press and belongs where the
 * eye lands first.
 */
@Composable
fun MessageReplayLabel(bubbleType: BubbleStyle, mine: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = useAppleBubbleColors(mine = true).background
    val rise = when (bubbleType) {
        BubbleStyle.GOOGLE -> GoogleReceiptRise
        else -> 0.dp
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .offset(y = -rise)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Replay effect",
                onClick = onClick,
            ),
    ) {
        // The glyph carries it. "Replay" beside a counter-clockwise arrow is legible in a language
        // nobody has to read, and at ten point the word alone would be a smudge.
        Icon(Icons.Rounded.Replay, contentDescription = null, tint = accent, modifier = Modifier.size(MetaIconSize))
        Spacer(modifier = Modifier.width(3.dp))
        Text("Replay", color = accent, fontSize = MetaLabelSize)
    }
}
