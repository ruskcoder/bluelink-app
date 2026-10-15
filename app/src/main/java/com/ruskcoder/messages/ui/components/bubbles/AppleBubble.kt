package com.ruskcoder.messages.ui.components.bubbles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.ui.components.MemberMenuTarget
import com.ruskcoder.messages.ui.components.MemberAvatar
import com.ruskcoder.messages.ui.components.mentionAccent
import com.ruskcoder.messages.ui.components.rememberMentionText
import com.ruskcoder.messages.data.model.ReplyPreview
import com.ruskcoder.messages.data.model.TextRun
import com.ruskcoder.messages.data.model.textScale
import com.ruskcoder.messages.ui.components.AppleQuotePreview
import com.ruskcoder.messages.ui.components.ConnectorEnd
import com.ruskcoder.messages.ui.components.QuoteGap
import com.ruskcoder.messages.ui.components.TapbackOverlap
import com.ruskcoder.messages.ui.components.TapbackSize
import com.ruskcoder.messages.ui.components.EmojiJumboMax
import com.ruskcoder.messages.ui.components.connectorShift
import com.ruskcoder.messages.ui.components.drawReplyConnector
import com.ruskcoder.messages.ui.components.emojiJumboLineHeight
import com.ruskcoder.messages.ui.components.emojiJumboSize
import com.ruskcoder.messages.ui.components.emojiOnlyCount
import com.ruskcoder.messages.ui.components.rememberConnectorInk
import com.ruskcoder.messages.ui.components.AttachmentContent
import com.ruskcoder.messages.ui.components.BubbleText
import com.ruskcoder.messages.ui.components.VoiceMessage
import com.ruskcoder.messages.ui.components.isFlushMedia
import com.ruskcoder.messages.ui.components.isBareMedia
import com.ruskcoder.messages.ui.theme.LocalChatTheme
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.util.toComposeColor
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * iMessage-style speech bubble filled with this conversation's palette. The shape itself — a
 * nine-sliced squircle with a curling tail — is built by [AppleBubbleShape]. Ported from
 * `apple-bubble.tsx`.
 *
 * Unlike the RN source, this doesn't need the "measured-size cache to avoid a placeholder frame"
 * dance: `Modifier.background(color, shape)` gets the real laid-out size from Compose's own layout
 * pass before anything is painted, so there's no SVG-needs-explicit-dimensions round trip to work
 * around.
 */

private val SameSenderGap = 3.dp
private val DifferentSenderGap = 5.dp

/**
 * The gutter an incoming group bubble reserves to its leading side for the sender's avatar — the
 * width of the avatar slot in [AppleBubble]'s attributed row. Anything drawn beside the bubble
 * rather than inside it — the reply quote, the edit history, the status line — has to carry this
 * same inset in a group, or it comes out flush with the screen instead of with the message.
 */
val AppleGroupGutter = 34.dp

/** The face itself, which sits at the trailing edge of that gutter. */
val AppleAvatarSize = 26.dp

/**
 * How far a reacted, first-in-run group bubble drops so its tapback clears the sender's name.
 *
 * The tapback lands on the bubble's top corner and sinks [this] above it — which is exactly where
 * the name sits. The name holds still; only the bubble moves down, and it animates.
 */
private val ReactionNameClearance = 20.dp

/**
 * How much of the bubble's trailing edge the tapback claims — its own width plus the inset it keeps
 * off the corner, plus a little air. Only when the name would reach into that band is the bubble
 * dropped; a message long enough to hold the tapback clear of its name doesn't move.
 */
private val TapbackNameMargin = 56.dp

/**
 * The gap under the message that closes a thread.
 *
 * The one place a thread changes the conversation's spacing. Carrying a quote does not, and used
 * to: every reply was given clearance at both ends and a tail of its own, which pulled apart
 * exchanges that should have been grouped and left a conversation full of single-message runs.
 * Only the end of a thread is set apart, which is what makes a finished exchange read as one.
 */
private val ThreadEndGap = 10.dp

/**
 * The room under a message that wears a tail only because the reply below it points at it.
 *
 * A run gap is three dip — enough to hold two bubbles in one stack apart, and not enough for a tail
 * to read as a tail: drawn that close, the point lands on the bubble underneath and comes out as a
 * notch between them. A message with a line leaving it is not mid-stack any more, so it is given
 * the air that says so.
 */
private val ConnectedTailGap = 10.dp

/**
 * The clearance above a reply that draws the message it answers.
 *
 * The hollow bubble is a whole extra object standing in the row, and at run spacing it sat almost
 * against whatever was above it — two outlines a couple of dip apart, reading as one stack of
 * boxes rather than as a message and the thing it refers to. Only where the preview is actually
 * drawn: an adjacent reply has a connector in that gap instead, and widening it there would only
 * stretch the line.
 */
private val QuotePreviewGap = 8.dp

/**
 * How far the outline steps up when the reply under it is wearing a tapback that would cover it.
 *
 * The badge sits on the bubble's outer top corner and rises most of its own height above it, which
 * is further than [QuoteGap] leaves between the bubble and the reference above. Two short messages
 * never meet — the badge is on one side of the row and the outline on the other — but two long ones
 * do, and the reaction was drawn straight over the top of the message being answered. Spent only
 * when the two actually overlap across the row, so a conversation of ordinary-length messages is
 * not permanently loosened to make room for a collision it never has.
 */
private val ReactionQuoteClearance = TapbackSize - TapbackOverlap - QuoteGap + 4.dp

/**
 * The climb to a message a row up that never reported where it was.
 *
 * Only ever a fallback: the thread hands every reply the measured box of the message it answers,
 * and this stands in for the frame or two after a row scrolls into view before it has been laid
 * out. A run gap, so the line points at roughly where a grouped message above would be.
 */
private val AdjacentFallbackGap = 6.dp
private val PaddingHorizontal = 12.dp
private val PaddingTop = 8.dp
private const val FONT_SIZE = 16
private const val LINE_HEIGHT = 21
private val MinWidth = 48.dp

private val AppleMine = Color(0xFF3291EF)
private val AppleTheirs = Color(0xFF26252B)
private val AppleMeta = Color(0x99FFFFFF)

/** Bubble fill / text / meta colors for the active theme. */
@Composable
fun useAppleBubbleColors(mine: Boolean): BubbleColors {
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
        else -> BubbleColors(background = if (mine) AppleMine else AppleTheirs, text = Color.White, meta = AppleMeta)
    }
}

/**
 * The bubble's corner radius cap at a given text scale: a one-line bubble's half-height, plus a
 * little, so a scaled-up bubble keeps its stadium.
 */
fun appleMaxRadius(scale: Float): Dp = (ceil((PaddingTop.value + PaddingTop.value + LINE_HEIGHT * scale) / 2) + 2).dp

/**
 * How far in from the bubble's edge the line under it starts, clear of the tail's curl.
 *
 * The lowest part of the tail reaches about one corner radius in from the edge (x = 379.6 of the
 * 504-wide art, against a radius of 120). The caption sits level with the tail, not under it, so it
 * starts just short of that: the curl tapers to nothing at its inner end.
 */
@Composable
fun appleTailClearance(): Dp = appleMaxRadius(LocalSettings.current.textScale()) * TailReach - 1.dp

private const val TailReach = (504f - 379.6f) / 120f

/**
 * The room under a bubble's straight edge that its tail hangs into, handed to the caller to keep.
 *
 * By default a bubble pads itself underneath by the tail's overhang. In the conversation that made
 * the caption under it sit below the *tail* rather than the bubble, and made the bubble's own box
 * change height when a newer message took its tail away. The row then settled that height from the
 * bottom up, so the message dropped a few dip and climbed back. Handed over, the bubble's box is the
 * bubble alone, and the row keeps the room under it as the larger of this and the caption.
 *
 * Read in layout only. [height] is eased, so a lost tail gives its room back gradually.
 */
class AppleTailReserve {
    var height: () -> Dp = { 0.dp }
}

private const val TailReserveMs = 260
private val TailReserveEasing = CubicBezierEasing(0.22f, 0.68f, 0.32f, 1f)

@Composable
fun AppleBubble(
    isFromMe: Boolean,
    showTail: Boolean = true,
    isFirstInRun: Boolean = false,
    text: String,
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
    revealX: (() -> Float)? = null,
    /**
     * Whether this message carries a quote *in the conversation*, which is not always the same as
     * whether one is being drawn: an overlay can lift the bubble out on its own and leave the quote
     * behind. The run gap above it follows the message, not the drawing, so a lifted bubble keeps
     * exactly the spacing it had in the thread.
     */
    hasQuote: Boolean = replyTo != null,
    /**
     * Whether the message being quoted is the one directly above this in the conversation.
     *
     * Decided by the thread, which is the only thing that can see both — and which also has to say
     * whether a day separator came between them, since a pair either side of a rule is not a pair
     * you can take in at a glance and does want its preview drawn. See [AppleQuotePreview].
     */
    quoteAdjacent: Boolean = false,
    /**
     * Whether the message this one answers is itself an answer, and whether something answers this
     * one from directly below.
     *
     * Between them they say how many connectors land on which bubble, which is the one thing the
     * drawing cannot work out for itself — see [ConnectorSplit].
     */
    splitTop: Boolean = false,
    splitBottom: Boolean = false,
    /**
     * Hides the bubble and nothing else — the quote above it and the connector beside it stay.
     *
     * What a thread lift needs. The bubble is being drawn by the overlay instead, so it must not
     * be drawn twice; the quote and the line are *not* being drawn there, so taking them away with
     * it left the conversation missing pieces the moment the animation began. They belong to the
     * row, they never move, and they should never blink.
     */
    bubbleHidden: Boolean = false,
    /** True for the message that closes a thread — see [ThreadEndGap]. */
    threadEnd: Boolean = false,
    /** Whether this message wears a reaction, so a named bubble can drop clear of its tapback. */
    hasReactions: Boolean = false,
    /**
     * Who wrote the message being quoted, where that is someone else in a group.
     *
     * The outline is drawn as the message was: indented past the avatar gutter with that person's
     * face beside it. It used to be an outline flush against the screen's edge while the bubble
     * underneath it started a gutter in, which read as two things belonging to different columns —
     * and left the connector nothing to leave from but a shape floating in the margin.
     */
    quotedSender: GroupMember? = null,
    /** Where the message this one answers is sitting, when it is a row away — see [MessageBubble]. */
    quotedAnchor: (() -> com.ruskcoder.messages.ui.components.ThreadAnchor?)? = null,
    /** Reports the sender's face, so a reply a row below can leave from it. */
    onAvatarLayout: ((LayoutCoordinates?) -> Unit)? = null,
    /**
     * Whether this conversation reserves an avatar gutter beside received messages.
     *
     * Not the same question as `sender != null`, which is only true of a received message and says
     * nothing about one of your own — and a connector between your message and theirs has to know
     * that the column exists in order to leave from it. See [ConnectorEnd.inGroup].
     */
    inGroup: Boolean = false,
    /** Whether the tie to the message this one answers is drawn — see [MessageBubble]. */
    drawConnector: Boolean = true,
    /** Draws the face even without the tail, because a connector leaves from it. */
    forcedAvatar: Boolean = false,
    /**
     * Draws the tail — and makes room under it — on a message that a reply below is pointing at.
     *
     * A message mid-run wears no tail: it is one bubble in a stack and the stack's last bubble
     * speaks for all of them. But a connector leaving it makes it the head of something, and a line
     * coming out of a square-cornered bubble packed three dip from the next one had nowhere to
     * leave from and no air to leave through. See [ConnectedTailGap].
     */
    forcedTail: Boolean = false,
    /** Bumped when any row becomes measurable, so a connector waiting on one redraws. */
    anchorRevision: (() -> Int)? = null,
    /** Whether this reply's line ends on the named row's outline rather than on its bubble. */
    targetIsOutline: Boolean = false,
    /** Reports this row's own outline, so a reply below it can aim at that instead. */
    onOutlineLayout: ((LayoutCoordinates?) -> Unit)? = null,
    selectable: Boolean = false,
    /** Takes over the room under the tail, so the line beneath can share it. See [AppleTailReserve]. */
    tailReserve: AppleTailReserve? = null,
) {
    // The tail as drawn, which is not always the tail the run asked for — see [forcedTail].
    @Suppress("NAME_SHADOWING")
    val showTail = showTail || forcedTail
    val colors = useAppleBubbleColors(isFromMe)
    // Read here rather than at the draw: it comes from the chat theme, and a draw scope has no
    // composition to ask. See [rememberConnectorInk].
    val connectorInk = rememberConnectorInk()
    val scale = LocalSettings.current.textScale()

    // Derived every recomposition rather than a constant: the text-size setting changes what a
    // one-line bubble measures, and the cap has to track it or a scaled-up bubble loses its stadium.
    val maxRadiusDp = appleMaxRadius(scale)
    // Approximates the RN source's measured tail overflow (radius actually caps at height/2, but
    // that's ≤ maxRadius always) — exact for the common one/two-line stadium case, a hair generous
    // for a very tall bubble.
    val tailOverflow = if (showTail) maxRadiusDp * TAIL_OVERFLOW_RATIO else 0.dp
    // A bare photo is not put in a bubble — it *is* the bubble: masked to the outline, tail and
    // all, with the picture as the fill. Which needs the flush variant of the shape, whose tail
    // hangs inside the box rather than below it: clipped to the ordinary one there is nothing for
    // the tail to be made of and it comes out as a transparent notch.
    // Keyed on whether the *message* carries a quote, not on whether one is being drawn. An overlay
    // that lifts the bubble out leaves the quote behind, and a photo that went flush there but not
    // in the thread would be a different size in the two places — so the lift would start with a
    // jump. Same reasoning as [hasQuote] itself.
    val mediaFlush = isFlushMedia(attachment, hasQuote = hasQuote)
    val bubbleShape = AppleBubbleShape(isFromMe, showTail, maxRadiusDp)
    val flushShape = AppleFlushBubbleShape(isFromMe, showTail, maxRadiusDp)

    // Everything a reply's connector is drawn from, in the quote box's own space rather than the
    // screen's. Local coordinates are what make it cheap: a scroll moves the whole row and leaves
    // every one of these numbers exactly where it was, so the drawing is never invalidated for a
    // message simply going past. Held out here rather than beside the box because the bubble does
    // its own reporting, and the bubble is built well above the point the box is.
    var quoteBox by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Whole boxes now, not tops and heights. The connector aims at the middle of a bubble and stops
    // a fixed gap short of its near edge, and neither of those is answerable from a top and a
    // height — which is why the arms used to be a constant length and came out touching a long
    // bubble and stranded beside a short one.
    var bubbleBox by remember { mutableStateOf<Rect?>(null) }
    var previewBox by remember { mutableStateOf<Rect?>(null) }
    var avatarBox by remember { mutableStateOf<Rect?>(null) }
    var quotedAvatarBox by remember { mutableStateOf<Rect?>(null) }

    /**
     * Reads a child's box against the quote box's, once both have been laid out, with whatever the
     * time column has slid that child by taken back out again.
     *
     * [shiftedBy] is not bookkeeping — a coordinate transform walks the layers between the two
     * nodes and folds their translations into the answer, so a box read while the column is open
     * comes back already moved. The connector is then slid by that same travel at the draw, and the
     * line leaves a face at twice the face's own speed. Nothing here is supposed to know how far
     * the column is open: every box is the box at rest.
     */
    fun locate(child: LayoutCoordinates, shiftedBy: Float, apply: (Rect) -> Unit) {
        val box = quoteBox ?: return
        if (!box.isAttached || !child.isAttached) return
        val at = box.localPositionOf(child, Offset.Zero)
        apply(Rect(Offset(at.x - shiftedBy, at.y), child.size.toSize()))
    }

    // How far each piece of this row has slid while the time column is open. Read here and applied
    // as a layer translation on each piece, so the measurements above stay taken at rest and the
    // connector adds the shift back itself rather than chasing three moving boxes.
    val shift = com.ruskcoder.messages.ui.components.LocalRevealShift.current
    /** The reply swipe, which moves the bubble alone — see [placed]. */
    val swipeX: () -> Float = revealX ?: { 0f }

    // Only the message that wears the tail wears the face — the rest of a run is indented past an
    // empty gutter. A reply below one of those has no face to leave from, and has to be told so
    // rather than left aiming at whatever this message reported the last time it had one.
    val hasFace = sender != null && (showTail || forcedAvatar)
    LaunchedEffect(hasFace) { if (!hasFace) onAvatarLayout?.invoke(null) }
    // Same for the outline: a row that stops drawing one has to say so, or a reply below it keeps
    // aiming at a reference that is no longer on screen.
    val hasOutline = replyTo != null && !quoteAdjacent
    LaunchedEffect(hasOutline) { if (!hasOutline) onOutlineLayout?.invoke(null) }

    // Nothing but emoji, and few enough of them to stand on their own — see [emojiOnlyCount]. Only
    // for a plain text message: a photo with a caption of emoji is still a photo, and a voice note
    // has no text to read at all.
    val jumbo = if (audio == null && attachment == null) emojiOnlyCount(text) else 0
    val jumboEmoji = jumbo in 1..EmojiJumboMax

    // The overhang the tail hangs into, below the bubble's straight edge. Reserved under the bubble
    // itself unless the caller has taken it over — see [AppleTailReserve].
    val tailBelow = if (mediaFlush || jumboEmoji) 0.dp else tailOverflow
    if (tailReserve != null) {
        // Eased rather than switched: a message losing its tail to the one sent after it gives the
        // room back over the same beat the bubble above it would have resized on.
        val reserve = remember { Animatable(tailBelow, Dp.VectorConverter) }
        LaunchedEffect(tailBelow) {
            if (reserve.targetValue != tailBelow) reserve.animateTo(tailBelow, tween(TailReserveMs, easing = TailReserveEasing))
        }
        tailReserve.height = { reserve.value }
    }

    val bubble: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                // First in the chain, before anything that draws.
                //
                // A `graphicsLayer` only carries what comes *after* it: put below the background,
                // it left the bubble's own fill painting into the parent layer while the text
                // inside rode the translation. Dragging the time column slid every word in the
                // conversation out of its own bubble, which stayed exactly where it was.
                .graphicsLayer { translationX = shift.of(isFromMe) + swipeX() }
                .padding(horizontal = BubbleRowInset)
                // Beside an avatar there is less row to take a fraction of, so the share is a
                // larger one — otherwise a group's bubbles come out visibly narrower than a
                // one-to-one's for no reason the eye can see.
                //
                // Their bubbles stop a little short of yours: an incoming message is the one the
                // time column opens *beside*, and at the old share a long one ran under the clock
                // rather than leaving room for it. Yours never had the problem — they move out of
                // the column's way instead.
                .bubbleMaxWidth(bubbleWidthFraction(isFromMe))
                // A flush photo keeps no room under itself for the tail, because its own shape
                // carries the tail inside the box; every other bubble reserves that overhang.
                .then(if (tailReserve != null) Modifier else Modifier.padding(bottom = tailBelow))
                // Nothing under a flush photo: its tile lays the bubble colour down itself while it
                // loads and takes it away once the picture arrives, so a transparent PNG does not
                // come up filled in blue or grey. A jumbo emoji has no ground either: it is drawn
                // on the conversation itself, with no bubble under it and no tail on it.
                .then(
                    if (jumboEmoji || mediaFlush || isBareMedia(attachment)) Modifier else Modifier.bubbleSurface(colors.background, bubbleShape),
                )
                // Measured *outside* the bubble's own padding: this is what the corner tapback
                // positions itself against, and reporting the text's width instead left it floating
                // a padding's width off the bubble's edge.
                // The reading is taken here, inside the tail's overhang padding and outside the
                // bubble's own, so it is the filled body of the bubble that is measured — which is
                // what a connector reaching for the message's middle has to aim at.
                .onGloballyPositioned {
                    onBubbleLayout?.invoke(it)
                    locate(it, shift.of(isFromMe) + swipeX()) { box -> if (box != bubbleBox) bubbleBox = box }
                }
                .then(
                    when {
                        isBareMedia(attachment) -> Modifier
                        mediaFlush -> Modifier.clip(flushShape)
                        // No minimum width and no bubble padding: the glyph is the message, and a
                        // 48dp tap target around it would put it back in an invisible bubble.
                        jumboEmoji -> Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                        else -> Modifier.widthIn(min = MinWidth).padding(horizontal = PaddingHorizontal, vertical = PaddingTop)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                jumboEmoji -> Text(
                    text.trim(),
                    color = colors.text,
                    fontSize = (emojiJumboSize(jumbo).value * scale).sp,
                    lineHeight = (emojiJumboLineHeight(jumbo).value * scale).sp,
                )
                audio != null -> VoiceMessage(
                            audio.uri, audio.duration, audio.waveform,
                            colors.text, colors.background, colors.meta,
                            audio.transcript, audio.transcribing, audio.transcriptFailed,
                            audio.transcriptNeedsModel, audio.transcriptOffered,
                            audio.onTranscribe,
                            audio.onPlay,
                        )
                attachment != null -> AttachmentContent(attachment, colors, flush = mediaFlush)
                else -> BubbleText(
                    rememberMentionText(text, mentionNames, mentionAccent(isFromMe, colors.text)),
                    color = colors.text,
                    fontSize = (FONT_SIZE * scale).sp,
                    lineHeight = (LINE_HEIGHT * scale).sp,
                    selectable = selectable,
                    runs = runs,
                )
            }
        }
    }

    val attributed: @Composable () -> Unit = {
        if (sender != null) {
            // The avatar sits at the trailing edge of its gutter, the same 8dp off the screen the
            // typing indicator's does — [AppleGroupGutter] − avatar width — rather than centred in
            // the gutter, which left it a few dip proud of the dots.
            Row(verticalAlignment = Alignment.Bottom) {
                Box(
                    modifier = Modifier
                        .size(width = AppleGroupGutter, height = AppleAvatarSize)
                        // With the time column, not with the bubble beside it: the face is what a
                        // connector leaves from, and the bubble is giving up exactly this gutter's
                        // width to finish flush against the margin. So they separate as the drag
                        // goes on and the face slides out past the edge, which is where a line
                        // leaving it has to go too.
                        .graphicsLayer { translationX = shift.full() },
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    if (showTail || forcedAvatar) {
                        MemberMenuTarget(sender) {
                            MemberAvatar(
                                member = sender,
                                size = AppleAvatarSize,
                                labelSize = 12.sp,
                                modifier = Modifier.onGloballyPositioned { coordinates ->
                                    onAvatarLayout?.invoke(coordinates)
                                    locate(coordinates, shift.full()) { box -> if (box != avatarBox) avatarBox = box }
                                },
                            )
                        }
                    }
                }
                Column {
                    // Both measured so the drop below is spent only when it is needed: the name's
                    // right edge against the band the tapback claims off the bubble's trailing edge.
                    var nameWidthPx by remember { mutableIntStateOf(0) }
                    var bubbleWidthPx by remember { mutableIntStateOf(0) }
                    if (isFirstInRun) {
                        MemberMenuTarget(
                            sender,
                            modifier = Modifier
                                // Travels with the bubble underneath it while the time column is
                                // open, and *only* then: a reply swipe deliberately leaves the name
                                // and the face standing while the message alone slides away, but
                                // the column opening is the whole conversation squaring up, and a
                                // name left behind by its own message reads as belonging to
                                // whatever ended up under it.
                                .graphicsLayer { translationX = shift.of(isFromMe) }
                                .padding(start = 12.dp, bottom = 2.dp)
                                .onGloballyPositioned { nameWidthPx = it.size.width },
                        ) {
                            Text(
                                sender.name,
                                color = colors.meta,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // The name holds where it is; the bubble drops far enough that a tapback on its
                    // top corner no longer lands over the name — but only when the bubble is too
                    // short to hold the tapback clear of the name on its own. Animated so a reaction
                    // arriving eases the message down rather than jumping it.
                    val marginPx = with(LocalDensity.current) { TapbackNameMargin.toPx() }
                    val wouldCollide = hasReactions && isFirstInRun &&
                        nameWidthPx > 0 && bubbleWidthPx > 0 &&
                        bubbleWidthPx < nameWidthPx + marginPx
                    val nameClearance by animateDpAsState(
                        targetValue = if (wouldCollide) ReactionNameClearance else 0.dp,
                        animationSpec = tween(220),
                        label = "reactionNameClearance",
                    )
                    Spacer(modifier = Modifier.height(nameClearance))
                    Box(modifier = Modifier.onGloballyPositioned { bubbleWidthPx = it.size.width }) {
                        bubble()
                    }
                }
            }
        } else {
            bubble()
        }
    }

    Column(
        horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                // The run gap only. The extra room a thread's end or a message with a line leaving it
                // keeps is laid under the row's caption instead — see [AppleThreadGap] — so the reply
                // count and the receipt stay tucked against the bubble they describe.
                bottom = SameSenderGap,
                top = when {
                    hasQuote && !quoteAdjacent -> QuotePreviewGap
                    isFirstInRun && !hasQuote -> DifferentSenderGap - SameSenderGap
                    else -> 0.dp
                },
            ),
    ) {
        val placed: @Composable () -> Unit = {
            // Only the bubble is ever taken away, and only ever by being made invisible: it keeps
            // its space, so nothing around it reflows while it is off being drawn somewhere else.
            //
            // The reply swipe is deliberately *not* applied here any more. Sliding this box carried
            // the whole attributed row — the face and the sender's name with it — so swiping a
            // group message dragged the person who wrote it across the screen, and the reply arrow
            // was uncovered beside the block rather than beside the message. The bubble carries the
            // swipe in its own layer instead; everything standing beside it holds still.
            Box(modifier = Modifier.graphicsLayer { alpha = if (bubbleHidden) 0f else 1f }) { attributed() }
        }
        if (replyTo == null) {
            placed()
        } else {
            // The original is only redrawn when it isn't already on screen directly above — see the
            // note at the head of the quote file. Adjacent, the connector alone carries the link.
            val showPreview = !quoteAdjacent
            val quotedMine = replyTo.mine ?: false
            // A reference to someone else's message is drawn the way their message is: past the
            // avatar gutter, with their face beside it. Only in a group, and only for theirs —
            // your own quotes hang on the far side, where there is no gutter to line up with.
            val quotedFace = quotedSender?.takeIf { !quotedMine }

            // The tapback on this reply sits on its bubble's outer top corner and rises well past
            // it — further than [QuoteGap] leaves between the bubble and the outline above. Where
            // the two are both long enough to meet across the row, the outline steps up out of the
            // way rather than being covered by a badge that belongs to the message underneath it.
            // Any reacted reply with a drawn reference steps up. The old test asked whether the two
            // boxes overlapped across the row, but the measured boxes lag a frame behind and a
            // cluster of reactions is wider than the single badge it modelled, so it kept missing
            // collisions that were plainly on screen.
            val tapbackCrowdsQuote = hasReactions && showPreview
            val quoteClearance by animateDpAsState(
                targetValue = if (tapbackCrowdsQuote) ReactionQuoteClearance else 0.dp,
                animationSpec = tween(220),
                label = "reactionQuoteClearance",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { quoteBox = it }
                    // Behind the pair rather than beside it: the connector lives out in the empty
                    // half of the row and reaches above its own box, which no layout can express
                    // and a draw can.
                    .drawBehind {
                        if (!drawConnector) return@drawBehind
                        val bubble = bubbleBox ?: return@drawBehind
                        // Resolved here rather than cached from a layout callback. The row this
                        // aims at is somebody else's and may not have been measured yet when this
                        // one was placed; read once and kept, that "not yet" became "never", since
                        // nothing about this row changes afterwards to prompt a second look.
                        // Reading the revision registers the dependency that fixes it.
                        anchorRevision?.invoke()
                        val adjacentBox = if (showPreview) {
                            null
                        } else {
                            val frame = quoteBox?.takeIf { it.isAttached }
                            val root = frame?.positionInRoot()
                            quotedAnchor?.invoke()?.takeIf { root != null }?.let { anchor ->
                                // Every rect together, including the outline — which `copy` used to
                                // carry through untranslated, leaving one endpoint in root
                                // coordinates while the rest were local to this drawing.
                                anchor.copy(
                                    bubble = anchor.bubble.translate(-root!!.x, -root.y),
                                    avatar = anchor.avatar?.translate(-root.x, -root.y),
                                    outline = anchor.outline?.translate(-root.x, -root.y),
                                )
                            }
                        }
                        // Both ends, each carrying whatever the time column has slid it by — the
                        // faces travel with the column and the bubbles under them do not, so the
                        // shape is built at rest and slid as one piece afterwards — see
                        // [connectorShift]. Measured while the column is shut, the geometry cannot
                        // depend on how far it happens to be open.
                        val to = ConnectorEnd(
                            box = bubble,
                            avatar = avatarBox,
                            mine = isFromMe,
                            inGroup = inGroup,
                        )
                        val from = when {
                            showPreview -> ConnectorEnd(
                                box = previewBox ?: return@drawBehind,
                                avatar = quotedAvatarBox,
                                mine = quotedMine,
                                inGroup = inGroup,
                            )
                            adjacentBox != null -> adjacentBox.let { anchor ->
                                // The reference is drawn once, on the row that first needed it, and
                                // a chain below answers that picture rather than the bubble it
                                // stands for. An outline has no face and belongs to whoever wrote
                                // the message it shows, not to the row holding it.
                                val outline = anchor.outline.takeIf { targetIsOutline }
                                if (outline != null) {
                                    ConnectorEnd(box = outline, mine = anchor.outlineMine, inGroup = inGroup)
                                } else {
                                    ConnectorEnd(
                                        box = anchor.bubble,
                                        avatar = anchor.avatar,
                                        mine = anchor.mine,
                                        inGroup = inGroup,
                                    )
                                }
                            }
                            // The message this answers has not reported where it is — scrolled
                            // away, or disposed. Nothing is drawn until it does.
                            //
                            // This used to stand in a guessed box a run gap above, on the reasoning
                            // that a line pointing roughly somewhere beat one that blinked. It does
                            // not: the guess is anchored to nothing, so it came out as a line
                            // hanging off an avatar next to a message that answers nobody. A
                            // connector with no measured other end has nothing true to say.
                            else -> return@drawBehind
                        }
                        drawReplyConnector(
                            from = from,
                            to = to,
                            splitFrom = splitTop,
                            splitTo = splitBottom,
                            translateX = connectorShift(from, to, shift.full(), shift.received()),
                            // Each end's own travel, so a bubble sliding over the line covers it
                            // rather than shoving it aside — see [drawReplyConnector].
                            fromShift = shift.of(from.mine),
                            toShift = shift.of(to.mine),
                            color = connectorInk,
                        )
                    },
            ) {
                Column(
                    horizontalAlignment = if (isFromMe) Alignment.End else Alignment.Start,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (showPreview) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier
                                // Hung on the side of whoever wrote the message being quoted, which
                                // is not always the side of the message quoting it.
                                .align(if (quotedMine) Alignment.End else Alignment.Start)
                                // Opened below the outline, between it and the bubble, where the
                                // tapback actually is. The connector is drawn from the two measured
                                // boxes, so it lengthens by the same amount without being told.
                                .padding(bottom = QuoteGap + quoteClearance),
                        ) {
                            if (quotedFace != null) {
                                Box(
                                    modifier = Modifier
                                        .size(width = AppleGroupGutter, height = AppleAvatarSize)
                                        .graphicsLayer { translationX = shift.full() },
                                    contentAlignment = Alignment.BottomEnd,
                                ) {
                                    MemberAvatar(
                                        member = quotedFace,
                                        size = AppleAvatarSize,
                                        labelSize = 12.sp,
                                        modifier = Modifier.onGloballyPositioned { child ->
                                            locate(child, shift.full()) { box -> if (box != quotedAvatarBox) quotedAvatarBox = box }
                                        },
                                    )
                                }
                            } else if (sender != null && !quotedMine) {
                                // A group with nobody resolvable behind the quote still lines the
                                // outline up with the bubble under it rather than with the edge.
                                Spacer(modifier = Modifier.size(width = AppleGroupGutter, height = 1.dp))
                            }
                            AppleQuotePreview(
                                text = replyTo.text,
                                quotedMine = quotedMine,
                                // Inside the row inset, so the arm reaching for it stops the same
                                // distance short as the arm reaching for the bubble underneath.
                                onOutlineLayout = { child ->
                                    onOutlineLayout?.invoke(child)
                                    locate(child, shift.of(quotedMine)) { box -> if (box != previewBox) previewBox = box }
                                },
                                modifier = Modifier.graphicsLayer { translationX = shift.of(quotedMine) },
                            )
                        }
                    }
                    placed()
                }
            }
        }
    }
}
