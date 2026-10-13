package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.ui.components.bubbles.AppleBubbleShape
import com.ruskcoder.messages.ui.components.bubbles.BubbleRowInset
import com.ruskcoder.messages.ui.components.bubbles.useAppleBubbleColors
import kotlin.math.abs

/** The accent WhatsApp/Google use for quoted-reply UI — distinct from the send/bubble green. */
val ReplyAccent = Color(0xFF8B7FD6)

/**
 * The band across the head of a Google bubble carrying a quote, and the card inside it.
 *
 * Fixed rather than derived from the bubble. The band's whole job is to *stop* being the bubble for
 * a moment, so taking its colour from the bubble would undo it — and these two are near enough to
 * the wallpaper's own darkness that the band reads as a window cut through the message rather than
 * as a slab laid on it, whichever colour the conversation is set to.
 */
private val GoogleQuoteBand = Color(0xFF202124)
private val GoogleQuoteCard = Color(0xFF141517)

/** WhatsApp's quote card: rounded, and spanning the bubble it is the head of. */
private val WhatsAppQuoteShape = RoundedCornerShape(7.dp)

/**
 * The gap between the card and the message under it.
 *
 * The same number the bubble insets the card by on its other three sides — see the note on the
 * bubble's own padding. Equal all the way round is the point: a card 9dp from the sides and 6dp
 * from the top sits visibly high in its own space, and the eye reads that as a mistake long before
 * it can say which measurement is wrong.
 */
private val WhatsAppQuoteGap = 8.dp

/**
 * The film that makes the card a recess in the bubble rather than a panel on it.
 *
 * Always darker, never lighter. It used to lift over a dark bubble and sink under a light one, on
 * the reasoning that either direction reads as a change of surface — but only one of them reads as
 * a *recess*, and a quote is something the message is sitting on top of rather than something
 * raised out of it. Lightening it also put the quote nearer the eye than the reply, which is
 * backwards: the words you wrote are the message, and the ones you are answering are context.
 *
 * A heavier film over a light bubble than a dark one, because the same alpha of black is a far
 * smaller step against pale green than against deep green.
 */
private fun quoteRecess(bubbleColor: Color): Color =
    Color.Black.copy(alpha = if (bubbleColor.luminance() > 0.5f) 0.10f else 0.22f)

/**
 * The quoted-message strip shown above a reply, inside a WhatsApp bubble and on the send bar.
 *
 * A card, not a block quote. It used to be a bare accent bar with two lines of text beside it,
 * sitting directly on the bubble — which is what a quote looks like in running prose and not what
 * it looks like in WhatsApp, where the original is a distinctly darker rounded panel you can see
 * the edges of. Drawn as a card it also stops competing with the message underneath it: there is a
 * boundary between the two now rather than a change of colour partway down the same block.
 *
 * It spans the bubble rather than hugging its own two lines. Sized to its contents it came out a
 * different width on every message, and a stack of replies read as a ragged column of cards inside
 * a tidy column of bubbles — the card is the head of the message, and the head of a thing is as
 * wide as the thing. The bar is a child of the card and stretched to its full height, so it meets
 * both rounded ends from the inside; hung outside the clip instead, it overshot them.
 */
@Composable
fun ReplyQuote(name: String, text: String, mutedColor: Color, bubbleColor: Color = Color.Transparent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = WhatsAppQuoteGap)
            .clip(WhatsAppQuoteShape)
            .background(quoteRecess(bubbleColor))
            .height(IntrinsicSize.Min),
    ) {
        // Flush against the card's leading edge and running its whole height — no padding of any
        // kind between it and the clip, which is what lets the rounded corners crop it rather than
        // leaving a sliver of card showing past its ends.
        Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(ReplyAccent))
        Column(modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp)) {
            Text(
                name,
                color = ReplyAccent,
                fontSize = 13.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text,
                color = mutedColor,
                fontSize = 13.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The gap between the quote's own card and the section holding it, on every side. */
private val GoogleQuoteInset = 8.dp

/**
 * The corner the quote card takes where it is not sharing one with the bubble, and the least it
 * will round to anywhere.
 *
 * A floor, not just a default. Concentric rounding subtracts the inset from the outer radius, and
 * where the bubble's corner is the small one a run uses — 4dp, against an 8dp inset — that
 * arithmetic goes negative and the card came out with a square corner tucked under a barely-rounded
 * one. Concentric is a rule about *matching* two curves, and there is no curve to match there; the
 * card keeps its own corner instead of losing it.
 */
private val GoogleQuoteBottomRadius = 8.dp

/**
 * Google Messages' quoted reply: a band across the head of the bubble, in a near-black of its own,
 * with the quote itself sitting in a darker card inside it.
 *
 * Two surfaces rather than one, because they are answering two different questions. The band is
 * *not part of the message* — it is the head of the bubble given over to something the message is
 * about, so it leaves the bubble's colour entirely rather than being a shade off it, which is what
 * the old single tinted panel did and why the quote read as another paragraph of the message. The
 * card inside it is the quote proper.
 *
 * [topCorners] is the bubble's own radius at the two corners this band shares with it. The card
 * inside takes that radius *minus* the inset it sits at, which is what makes the two curves
 * concentric — they hold the same gap all the way round the corner instead of the outer one
 * pulling away from the inner. Its bottom corners share nothing with the bubble, so they take a
 * plain radius of their own.
 *
 * The inset is one number on all four sides for the same reason: a card centred in a band it
 * touches unequally reads as misplaced even where nothing states the rule.
 */
@Composable
fun GoogleReplyQuote(
    name: String,
    text: String,
    textColor: Color,
    metaColor: Color,
    /** The bubble's radius at this band's own two top corners, so the card can be concentric. */
    topStart: Dp,
    topEnd: Dp,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GoogleQuoteBand)
            .padding(GoogleQuoteInset),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(
                    RoundedCornerShape(
                        topStart = (topStart - GoogleQuoteInset).coerceAtLeast(GoogleQuoteBottomRadius),
                        topEnd = (topEnd - GoogleQuoteInset).coerceAtLeast(GoogleQuoteBottomRadius),
                        bottomStart = GoogleQuoteBottomRadius,
                        bottomEnd = GoogleQuoteBottomRadius,
                    ),
                )
                .background(GoogleQuoteCard)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(name, color = metaColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text,
                color = textColor.copy(alpha = 0.85f),
                fontSize = 13.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


/**
 * Google's quote as the send bar carries it: the dark card from the head of a Google reply bubble,
 * on its own, with the way out in its top corner.
 *
 * The band around it in [GoogleReplyQuote] exists to interrupt a bubble — to stop being the message
 * for a moment. There is no bubble here to interrupt, so the band would be a second surface between
 * the bar's glass and the card, saying nothing. What is left is the part that was always the quote:
 * the card.
 *
 * Squared off at the bottom rather than rounded all round: it is embedded in the head of the bar
 * rather than floating above it, and a fully rounded card inside a fully rounded bar read as two
 * stacked pills instead of one control.
 */
@Composable
fun GoogleComposerQuote(name: String, text: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GoogleQuoteBottomRadius))
            .background(GoogleQuoteCard)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        // The words keep clear of the corner the control sits in rather than running under it.
        Column(modifier = Modifier.padding(end = 28.dp)) {
            Text(name, color = muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                fontSize = 13.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.Close,
            contentDescription = "Cancel reply",
            tint = muted,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel,
                ),
        )
    }
}

/**
 * iMessage's quoted reply, in two halves: the *preview* — the original redrawn as a hollow bubble
 * above the answer — and the *connector*, the short length of rounded-rectangle edge that ties the
 * two together down the far side of the row.
 *
 * Which of the two is drawn is not a style choice, it is a reading of where the original is:
 *
 * - **Directly above the reply.** No preview. Quoting a message you can already see, one line up,
 *   is showing it to you twice — so the connector alone does the work, spanning the pair to say
 *   "this answers that". [AppleQuotePreview] is simply not composed.
 * - **Anywhere else in the conversation.** The preview is drawn, since the message it answers is
 *   off screen or buried, and the connector runs from it down into the reply.
 *
 * And the connector's own shape follows who wrote the two messages:
 *
 * - **Same sender.** A C — a rounded rectangle with the side facing the bubbles cut away —
 *   spanning from the original down to the reply, both arms reaching toward the column the pair
 *   sits in.
 * - **Different senders.** The same shape with its top half gone: one rounded corner, dropping out
 *   from under the original and turning across the row toward the reply. There is no top arm to
 *   draw because the original is on *this* side already — the line leaves it directly.
 *
 * Both live on the side of the row the reply is *not* on: your own replies carry it down the far
 * left, theirs down the far right, clear of the bubbles either way.
 */

/** The hollow bubble's outline weight. A hairline: the shape is the message, not the stroke. */
private val QuoteStroke = 1.dp

/**
 * The preview's own padding, smaller than a real bubble's 12/8.
 *
 * A quote is a reference to a message and not a message, so it is drawn a size down — and the
 * padding has to come down with the type, or the outline ends up conspicuously wider than the
 * bubble holding the same words. Which is exactly what it looked like before: same text, same
 * shape, visibly bigger.
 */
private val QuotePaddingH = 9.dp
private val QuotePaddingV = 5.dp
private const val QUOTE_FONT = 12
private const val QUOTE_LINE = 15

/** Caps the outline's corner radius the way [AppleBubble] caps its own — see the note there. */
private val QuoteMaxRadius = ((QuotePaddingV.value * 2 + QUOTE_LINE) / 2 + 2).dp

/**
 * The narrowest the preview goes. Well under a bubble's own 48dp minimum: a bubble is a thing you
 * press and an outline is not, so it has no reason to hold a tap target's width, and a two-letter
 * quote drawn at the bubble's minimum came out a stadium with a letter adrift in the middle of it.
 */
private val QuoteMinWidth = 32.dp
private val QuoteMaxWidth = 220.dp

/** The gap the connector crosses, between the preview and the bubble answering it. */
val QuoteGap = 10.dp

/**
 * The ink a quoted incoming message — and every connector — is drawn in: the received bubble's own
 * colour, exactly.
 *
 * Not a shade of it, and not a lift off it. The hollow bubble is that message, drawn as an outline
 * rather than a fill, and the line linking a reply to it is part of the same drawing; anything
 * paler read as a third colour in a conversation that only has two. It is dark against the
 * wallpaper on purpose — this is scaffolding, and the messages are what should carry the eye.
 *
 * Derived rather than fixed, so a recoloured conversation takes its quotes and its connectors with
 * it.
 */
@Composable
private fun quoteInk(): Color = useAppleBubbleColors(mine = false).background

private val ConnectorStroke = 3.5.dp

/**
 * How much further than the radius a corner eases into the straight line either side of it.
 *
 * Zero is a circular arc: the curve starts dead on the radius and the straight runs meet it with a
 * sudden change in curvature, which is the "just rounded" look. Past zero the bend starts earlier and
 * finishes later with the tightest part pulled toward the corner, the way a squircle or a smoothed
 * CSS corner does — the line flows into its turn instead of stepping onto an arc.
 */
private const val CORNER_SMOOTHING = 0.6f

/** Where a circular arc's control points sit, as a share of the way from the tangent point to the corner. */
private const val CIRCLE_HANDLE = 0.5523f

/** And where a fully smoothed corner's sit — closer in, which is what concentrates the bend. */
private const val SMOOTH_HANDLE = 0.8f
/**
 * The quoted message redrawn as a hollow bubble: the same nine-sliced outline a real one is filled
 * with, tail and all, stroked instead of filled.
 *
 * [quotedMine] decides everything about it — which side of the row it hangs on, which way its tail
 * curls, and its colour: the conversation's own blue for something you sent, grey for something
 * they did.
 */
@Composable
fun AppleQuotePreview(
    text: String,
    quotedMine: Boolean,
    /**
     * Whether this is drawn on the send bar's glass rather than on the conversation.
     *
     * It changes one thing, and only for a quote of *their* message: the outline gives up the
     * bubble's own near-black and takes the same light grey as the words inside it.
     *
     * In the thread that near-black is right — it is what the message is drawn in, and it sits on a
     * dark wallpaper where a shape reads at a contrast a paragraph would not. The send bar is a
     * frosted grey panel, and against it the same stroke all but disappears: the quote came out as
     * floating text with a hint of an edge around it rather than as a bubble. One colour for the
     * whole thing puts the shape back. Your own quotes are unaffected — blue reads on either
     * ground, and the blue/grey split is how the bar says who you are answering.
     */
    onGlass: Boolean = false,
    /**
     * Where the outline itself landed — inside the row inset, not around it.
     *
     * Reported from in here rather than measured by the caller off [modifier], because those are
     * two different boxes: the caller's modifier wraps the row padding as well, and a connector
     * aiming at that stops a row inset *plus* its own gap short of the shape it is pointing at,
     * while the arm reaching for the bubble underneath — measured inside its padding — stops at the
     * gap alone. Two arms on the same connector, visibly different lengths, for no reason on screen.
     */
    onOutlineLayout: ((LayoutCoordinates) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // The words are never the bubble's own colour where that colour is a received bubble's.
    //
    // A received bubble is very nearly the wallpaper's black, so text set in it would be a quote
    // you cannot read. The words take the colour that message writes its *own* meta in, which is
    // the conversation's answer to "light grey" and moves with a recoloured chat. Your own quotes
    // stay in the blue they are outlined in, where there is contrast to spare.
    val label = if (quotedMine) useAppleBubbleColors(mine = true).background else useAppleBubbleColors(mine = false).meta
    val ink = when {
        quotedMine -> label
        onGlass -> label
        else -> quoteInk()
    }
    Box(
        modifier = modifier
            .padding(horizontal = BubbleRowInset)
            .then(if (onOutlineLayout == null) Modifier else Modifier.onGloballyPositioned(onOutlineLayout))
            .widthIn(min = QuoteMinWidth, max = QuoteMaxWidth)
            .border(QuoteStroke, ink, AppleBubbleShape(quotedMine, showTail = true, maxRadius = QuoteMaxRadius))
            .padding(horizontal = QuotePaddingH, vertical = QuotePaddingV),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = label,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            fontSize = QUOTE_FONT.sp,
            lineHeight = QUOTE_LINE.sp,
        )
    }
}

/**
 * One end of a connector: the box it attaches to, the face beside it, and whose message it is.
 *
 * Everything the drawing needs is measured now rather than assumed. The old connector was built out
 * of three constants — a spine 30dp in, an arm 22dp long, and a fixed 24dp climb to the message
 * overhead — none of which knew how wide the bubbles were, how tall a line of text is at the
 * reader's chosen size, or whether the row above was carrying a reply count under it. So the arms
 * came out unequal from one pair to the next, the climb landed wherever it landed, and at a large
 * text size the line ran under the very message it was pointing at.
 *
 * [avatar] is the sender's face beside a received bubble in a group, where there is one. It is what
 * the line actually leaves from: a connector starting at the edge of a received bubble starts a
 * gutter's width away from the person who wrote it, and the picture of them is what the eye reads
 * as the message's origin. Null in a one-to-one, where the bubble's own leading edge stands in.
 */
data class ConnectorEnd(
    /** The filled body of the message, or the outline standing in for it, in the drawing's space. */
    val box: Rect,
    /** The sender's face, on the one message of a run that draws one. */
    val avatar: Rect? = null,
    /** Which side of the row it hangs on. */
    val mine: Boolean,
    /**
     * Whether a gutter is reserved beside it for a face — true for every received message in a
     * group, including the ones mid-run that draw no face of their own.
     *
     * Kept apart from [avatar] being non-null, and that distinction is the whole fix for a line
     * that "sometimes" came out offset. Only the message wearing the tail draws a face; the rest of
     * a run is indented past an empty gutter. Anchored to the avatar when there was one and to the
     * bubble's edge when there wasn't, the same conversation drew its spine in two different places
     * a gutter's width apart, depending on nothing the reader can see. In a group the column is
     * fixed, so the spine is taken from the column rather than from whatever happens to be in it.
     */
    val inGroup: Boolean = false,
) {
    /** Whether this end anchors on the avatar column rather than on its own bubble. */
    val onGutter: Boolean get() = !mine && inGroup

    /**
     * The x the line runs down for this end.
     *
     * The middle of the avatar column in a group — a constant, so every connector in the
     * conversation shares one vertical. Otherwise a little inside the bubble's own leading corner,
     * so the line reads as dropping out from under the message rather than as a rule in the margin.
     */
    fun DrawScope.spineX(): Float = when {
        // The measured face where there is one, and the column it would have sat in where there is
        // not. Taking the constant unconditionally would be wrong the moment anything insets the
        // row — the checkbox column a selection opens moves the gutter without moving this.
        onGutter -> avatar?.center?.x ?: AppleGutterCentre.toPx()
        mine -> box.right - CornerInset.toPx()
        else -> laneX(inGroup = false)
    }
}

/**
 * The middle of the avatar column, from the row's leading edge.
 *
 * Duplicated from `AppleBubble`'s own gutter rather than imported, to keep the drawing free of a
 * dependency on the bubble that calls it. The two must agree; they are both derived from the same
 * two numbers.
 */
private val AppleGutterCentre = 34.dp - 26.dp / 2

/** How far inside a bubble's leading corner the spine drops, where there is no face to leave from. */
private val CornerInset = 14.dp

/**
 * The one vertical every connector in a conversation runs down, measured from the row's leading edge.
 *
 * In a group, the middle of the avatar column. In a one-to-one, a little inside the leading corner of
 * the received bubbles, where a line drops out from under a message. Every shape takes its spine from
 * here — the elbow out of their message, the C between two of yours, the straight line between two of
 * theirs — and every arm is the same length, so the spines line up with each other and the arms all
 * end in the same place. A C that sat in the avatar column beside an elbow that dropped from the
 * bubble's corner was two marks a few dip apart at both ends, which reads as a mistake.
 */
private fun DrawScope.laneX(inGroup: Boolean): Float =
    (if (inGroup) AppleGutterCentre else BubbleRowInset + CornerInset).toPx()

/**
 * The clear air a line keeps from whatever it leaves — the edge of a face, or the edge of a bubble.
 *
 * One number for both, which is what makes two connectors in the same conversation look like the
 * same object. It is a gap at the *origin* only: the far end of a line is a stub that stops in open
 * row rather than arriving anywhere, so it has nothing to keep a gap from.
 */
val ConnectorGap = 8.dp

/**
 * Extra air a line leaving the *bottom of a bubble* keeps, on top of [ConnectorGap].
 *
 * A bubble's measured box is its filled body, and the tail curls out below that — into the padding
 * the bubble reserves for it, most of a gap's depth. A line started one gap under the body began
 * right at the tip of the tail, so a reply to their message looked tied on to the tail rather than
 * dropping out from under the bubble. A face has no tail and keeps the plain gap.
 */
private val TailClearance = 5.dp

/**
 * How far a horizontal arm travels before it stops.
 *
 * A fixed stub, not a reach. An arm that ran all the way to the bubble it pointed at was a line
 * measured by the width of whatever it happened to be answering — long under a short message, a
 * tick under a long one, and different on every pair in the conversation. The arm's job is to say
 * which way the line is going, and a constant length says it the same way every time.
 */
private val ConnectorArm = 30.dp

/**
 * The corner radius — one number, and the same one on every connector in the conversation.
 *
 * It used to be capped at a *share* of the arm and of the vertical run, which meant no two
 * connectors on screen were bent to the same curve: a pair of short messages turned tighter than a
 * pair of tall ones, and the same reply re-bent itself when a reaction made its bubble grow. A
 * corner is part of how the mark is drawn, not something to be derived from what it happens to be
 * joining. The clamps below it are geometric floors — the radius can never eat more line than there
 * is — and in any ordinary conversation none of them bites.
 */
private val ConnectorRadius = 16.dp

/**
 * How far apart two connectors are held where they would otherwise meet on the same bubble.
 *
 * A message in the middle of a chain is both an answer and something answered: one line comes down
 * and stops at it, and the next starts from it and carries on down. Both aim at its middle, on the
 * same side of the row, so drawn as written they crossed — the two arms lay on top of each other
 * and the pair read as one line with a kink in it rather than as two.
 *
 * So where a bubble carries both, each gives up half of this: the incoming line stops just short of
 * the middle and the outgoing one leaves just past it. A bubble carrying only one is unaffected and
 * keeps its line dead centre, which is where a single line belongs.
 */
val ConnectorSplit = 10.dp

/**
 * Draws the tie between a reply and what it answers.
 *
 * Three shapes, and which one is drawn follows from who wrote the two messages:
 *
 * - **Both received, in a group.** A straight vertical between the two faces, a gap off each. They
 *   are already in the same column, so there is nothing for a line to turn across; anything more
 *   than a rule between them is decoration.
 * - **One received, one of yours.** An elbow. The spine drops (or climbs) out of the received
 *   message's face, and one arm turns across toward your message — a stub that says which way, not
 *   a line that arrives. Which end is which decides whether it reads as an L or a flipped one: the
 *   received message owns the spine whether it is the question or the answer.
 * - **Both yours.** A C down the left of the row — see [RowSpine] — with a stub at each end, one
 *   on each message's own row. Your side has no faces for a line to leave from, so the mark is
 *   given a lane of its own rather than being hung off the bubbles it joins.
 *
 * [translateX] slides the whole finished drawing, rather than each end being moved separately. The
 * time column moves faces and bubbles at different rates, so a connector built from two
 * independently-shifted ends stretched and re-bent for the whole length of the drag. Moved as one
 * piece it stays exactly the shape it was, travelling with the thing it leaves from.
 *
 * [top] and [bottom] are in the drawing layer's own vertical space and [top] may sit above it: the
 * line for an adjacent pair reaches up into the row above, which is a band this layer does not own
 * and nothing else draws in. Nothing clips between here and the list, so the overhang lands where
 * it is aimed.
 */
fun DrawScope.drawReplyConnector(
    /** The message being answered, wherever it is — an outline above, or a bubble a row up. */
    from: ConnectorEnd,
    /** The reply itself. */
    to: ConnectorEnd,
    /**
     * Whether a second connector already meets [from] or [to] at the same end, so the two are held
     * half of [ConnectorSplit] apart rather than laid over each other.
     */
    splitFrom: Boolean = false,
    splitTo: Boolean = false,
    /** How far the whole drawing has slid with the anchor it leaves from. */
    translateX: Float = 0f,
    /**
     * How far each end's own box has slid, which is not the same as [translateX].
     *
     * Used only to know where the bubbles have got to, so the part of the line one of them is over
     * can be left undrawn — the line runs under it. The line's own shape is never rebuilt from these: it is measured once, at
     * rest, and from then on it can be moved but not restretched. An arm that grew and shrank with
     * the drag read as the connector breathing, which is a great deal more movement than a column
     * of times sliding out deserves.
     */
    fromShift: Float = 0f,
    toShift: Float = 0f,
    /** The received bubble's own colour — see [quoteInk], which the caller reads for us. */
    color: Color,
) {
    val stroke = ConnectorStroke.toPx()
    val gap = ConnectorGap.toPx()
    val arm = ConnectorArm.toPx()
    val split = ConnectorSplit.toPx() / 2f

    // Which end the line leaves from. A received message owns the spine whether it is the thing
    // being answered or the thing answering; between two of your own there is no such end, and the
    // spine goes out into the empty half of the row instead.
    val origin = when {
        from.mine == to.mine -> null
        !from.mine -> from
        else -> to
    }

    val spine: Float
    val fromArm: Float?
    val toArm: Float?
    if (origin != null) {
        spine = with(origin) { spineX() }
        // The end that owns the spine has nothing to reach with; the arm belongs to the *other*
        // one. Written the other way round — the arm handed to the end the line leaves from — the
        // stub came out of the face instead of at the far end of the run, so a received reply put
        // its elbow beside its own avatar and the line went nowhere near the message it answered.
        val toward = if (origin === from) to else from
        val stub = if (toward.mine) spine + arm else spine - arm
        fromArm = if (origin === to) stub else null
        toArm = if (origin === from) stub else null
    } else if (from.mine) {
        // Both yours: a C down the left of the row, with a stub at each end.
        //
        // The spine is placed off the row's own leading edge rather than off the bubbles it joins.
        // Set a fixed gap from the nearer of the two, it moved with every message's width — the
        // same reply drew its mark in a different place depending on how long the words in it were,
        // and a stack of them came out as a ragged left edge of little brackets. Given a column, it
        // is the same vertical every time, and deliberately the column a group's faces already
        // hang their connectors in: one lane for scaffolding down the side of a conversation.
        //
        // The arms are [ConnectorArm] stubs, as everywhere else: each takes the turn out of the
        // spine on its own message's row and stops there. Run all the way across to the bubble
        // instead, they were two long rules reaching over the empty half of the conversation — and
        // their length was set by whatever the messages happened to measure, so no two pairs drew
        // the same mark. What the arm has to say is which row the line belongs to, and a stub the
        // same length every time says it without drawing a line under a message.
        spine = laneX(from.inGroup)
        fromArm = spine + arm
        toArm = spine + arm
    } else if (from.onGutter && to.onGutter) {
        // Both theirs in a group: one column, one straight line, no arms at all.
        spine = AppleGutterCentre.toPx()
        fromArm = null
        toArm = null
    } else {
        // Both theirs with no gutter — a one-to-one. The same straight line a group draws between two
        // faces, down the same lane every other connector uses, rather than a C out on the far side of
        // the bubbles where nothing else in the conversation ever runs.
        spine = laneX(inGroup = false)
        fromArm = null
        toArm = null
    }

    // Where the line meets each end. The end it leaves from is met a gap off the edge of its own
    // face or bubble, on the side the line is travelling; the end it merely points at is met at the
    // middle of the message, which is the one place that does not move when a row grows a name
    // above it or a reply count under it.
    val fromShape = if (from.onGutter) from.avatar ?: from.box else from.box
    val toShape = if (to.onGutter) to.avatar ?: to.box else to.box
    // A straight line between two faces is met a gap off each of them, with neither end "owning"
    // it — it is the one shape where both ends are origins.
    val top = when {
        // Off a bubble rather than a face, the line clears the tail hanging under it too.
        fromArm == null -> fromShape.bottom + gap + (if (fromShape === from.box) TailClearance.toPx() else 0f)
        else -> fromShape.center.y + (if (splitFrom) split else 0f)
    }
    val bottom = when {
        toArm == null -> toShape.top - gap
        else -> toShape.center.y - (if (splitTo) split else 0f)
    }
    if (bottom <= top) return

    val corners = (if (fromArm != null) 1 else 0) + (if (toArm != null) 1 else 0)
    // Never more radius than there is line to bend into it: a short connector comes out a shallower
    // curve rather than an arc doubling back through itself.
    val radius = if (corners == 0) {
        0f
    } else {
        listOfNotNull(
            ConnectorRadius.toPx(),
            fromArm?.let { abs(it - spine) },
            toArm?.let { abs(it - spine) },
            (bottom - top) / corners,
        ).min()
    }

    // Where a bubble slides over the line, the line goes under it — it is not moved out of the way.
    //
    // It used to be: a bubble closing on an arm pushed the whole connector sideways ahead of it for
    // the rest of the time-column drag. That is the line visibly making way for the message, which is
    // a lot of movement for scaffolding and reads as the connector being shoved about. Now the
    // connector stays exactly where its own end has carried it, and whatever part of it a bubble is
    // currently over is simply not drawn — which is what being underneath looks like.
    //
    // Only the ends an arm reaches *for* can cover it. The end the spine leaves from is one the line
    // starts outside of, a gap off its edge, and it travels with the line anyway.
    val covering = Path().apply {
        if (fromArm != null) addRect(from.box.translate(fromShift, 0f))
        if (toArm != null) addRect(to.box.translate(toShift, 0f))
    }

    // Smoothed corners rather than circular arcs — see [CORNER_SMOOTHING].
    //
    // Each corner is one cubic from a point on the incoming straight to a point on the outgoing one,
    // both [reach] from the corner itself, with its handles pulled in toward that corner. The radius
    // above stays the size of the bend; the smoothing only lengthens how gradually the line gets into
    // and out of it, and gives way first where there is not the straight line to spend on it, so a
    // cramped connector keeps its radius and loses only the ease.
    val limit = listOfNotNull(
        fromArm?.let { abs(it - spine) },
        toArm?.let { abs(it - spine) },
        if (corners > 0) (bottom - top) / corners else null,
    ).minOrNull() ?: 0f
    val reach = minOf(radius * (1f + CORNER_SMOOTHING), limit)
    val eased = if (radius > 0f) ((reach / radius - 1f) / CORNER_SMOOTHING).coerceIn(0f, 1f) else 0f
    val handle = CIRCLE_HANDLE + (SMOOTH_HANDLE - CIRCLE_HANDLE) * eased
    /** How far a control point sits from its tangent point, toward the corner. */
    val pull = reach * handle

    val path = Path()
    if (fromArm != null && radius > 0f) {
        // Along the arm, into the spine, then down it.
        val side = if (fromArm < spine) -1f else 1f
        path.moveTo(fromArm, top)
        path.lineTo(spine + side * reach, top)
        path.cubicTo(
            spine + side * (reach - pull), top,
            spine, top + (reach - pull),
            spine, top + reach,
        )
    } else {
        path.moveTo(spine, top)
    }
    if (toArm != null && radius > 0f) {
        // Down the spine, into the arm, then along it.
        val side = if (toArm < spine) -1f else 1f
        path.lineTo(spine, bottom - reach)
        path.cubicTo(
            spine, bottom - (reach - pull),
            spine + side * (reach - pull), bottom,
            spine + side * reach, bottom,
        )
        path.lineTo(toArm, bottom)
    } else {
        path.lineTo(spine, bottom)
    }

    // One translation for the whole shape, applied at the draw rather than folded into the numbers
    // above, so nothing about the geometry depends on how far the time column happens to be open.
    // The cover-up is outside it: the bubbles are where they are, not where the line has slid.
    clipPath(covering, clipOp = ClipOp.Difference) {
        translate(left = translateX) {
            drawPath(path, color = color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * How far the whole connector slides while the time column is open: whatever its anchor slid by.
 *
 * A face travels with the column and the bubble beside it does not, so "the connector's shift" is
 * only a well-formed question once you have said which end it belongs to — and it belongs to the
 * end it leaves from. See [drawReplyConnector].
 */
fun connectorShift(from: ConnectorEnd, to: ConnectorEnd, full: Float, received: Float): Float {
    val origin = when {
        from.mine == to.mine -> null
        !from.mine -> from
        else -> to
    }
    // A line hanging off the avatar column goes where the avatar column goes.
    if (origin?.onGutter == true) return full
    if (origin != null) return if (origin.mine) full else received
    // Same-sided pairs: a pair of received messages in a group hangs off the column, and everything
    // else off the bubbles themselves.
    if (from.onGutter && to.onGutter) return full
    return if (from.mine) full else received
}

/**
 * The colour a reply's connector is drawn in, read in composition and handed to the draw.
 *
 * A [DrawScope] cannot ask for it: the received bubble's colour comes from the chat theme and the
 * settings, both of which are composition locals. So the bubble reads it once and passes it down.
 */
@Composable
fun rememberConnectorInk(): Color = quoteInk()
