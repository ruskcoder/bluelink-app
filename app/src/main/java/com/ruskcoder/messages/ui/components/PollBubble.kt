package com.ruskcoder.messages.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.PollOption
import com.ruskcoder.messages.data.model.PollState
import com.ruskcoder.messages.ui.components.bubbles.BubbleRowInset
import com.ruskcoder.messages.ui.components.bubbles.bubbleMaxWidth
import com.ruskcoder.messages.ui.components.bubbles.bubbleWidthFraction
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer

/**
 * What a poll needs from the conversation it is sitting in.
 *
 * A poll's standing is a fact about the *thread* — it is assembled from every update sent against
 * it — and a bubble knows only about itself. Rather than thread a state, a vote handler and a
 * roster through every caller that draws a message (the conversation, the long-press lift, a
 * focused reply, the chat list's preview), the screen provides one of these and everything under it
 * reads the same answer.
 *
 * The defaults are a poll nobody has answered with no way to answer it, which is exactly right for
 * the surfaces that are showing you a message rather than putting you in a conversation.
 */
data class PollHost(
    val state: (messageId: String) -> PollState? = { null },
    /** Null anywhere the poll is being displayed rather than participated in. */
    val onVote: ((message: Message, option: PollOption) -> Unit)? = null,
    /** Opens the poll back up in the composer to add a choice to it. Null where there is no composer. */
    val onAddChoice: ((message: Message) -> Unit)? = null,
    /** Which poll is currently open in the composer for editing, if any — see [PollPlaceholder]. */
    val editing: String? = null,
    /** A voter's handle to the person it belongs to, for the faces on a choice. */
    val member: (handle: String) -> GroupMember? = { null },
    /**
     * How many people in the conversation could answer, us included — what a choice's length is
     * measured against. See [PollCard].
     */
    val participants: Int = 2,
)

val LocalPollHost = compositionLocalOf { PollHost() }

@Composable
fun ProvidePollHost(host: PollHost, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPollHost provides host, content = content)
}

// ---------------------------------------------------------------------------
// Colour
//
// A poll is orange wherever it appears, and deliberately not the conversation's own colour: it is
// the one thing in the thread that is a *control* rather than something somebody said, and taking
// the bubble palette would have made it read as a message you could tap by accident. Two shades and
// one ink, used the same way in both themes — the pills are their own surface and have no backdrop
// to be light or dark against.

/** An unanswered choice: the dark orange the pills are cut out of. */
private val PollTrack = Color(0xFF5A2E05)

/** The words on one — light orange, which is the same hue at reading contrast. */
private val PollInk = Color(0xFFFFB865)

/** A choice you picked: the light orange it fills with. */
private val PollFill = Color(0xFFFF9F0A)

/** And the words on that, which have to clear a much brighter ground. */
private val PollPickedInk = Color.White

/** The line under the poll, and the title over it. */
private val PollAccent = Color(0xFFFF9F0A)

/** The pill: fully rounded, so the bar reads as a length rather than as a box. */
private val PillShape = RoundedCornerShape(percent = 50)

private val PillHeight = 38.dp
private val PillGap = 5.dp

/** The face on a choice, and how far each one behind it is tucked under the one in front. */
private val VoterFace = 24.dp
private val VoterOverlap = 8.dp

/** How many faces a choice shows before it starts counting instead. */
private const val MaxFaces = 3

/**
 * How narrow an unanswered choice is, as a fraction of the bubble's maximum width.
 *
 * Wide enough that every choice reads as a button and its words sit comfortably on one line;
 * short enough that the remaining third is visible room for the bar to grow into. Anything whose
 * text does not fit in this takes the width it needs instead: a bar chart that clips its own labels
 * has stopped being one.
 */
private const val PillFloor = 0.65f

/**
 * A poll, in the conversation.
 *
 * Drawn in place of a bubble rather than inside one — see [MessageBubble], which hands a message
 * carrying a poll over to this. There is deliberately no card and no bubble around it: the message
 * *is* the choices. A poll wrapped in a bubble was a control inside a speech shape, which said the
 * options were a thing somebody had said rather than a thing you can press.
 *
 * Each choice is a fully rounded pill that starts at [PillFloor] of the bubble's width and grows by
 * an equal step for every person in the conversation who picks it — so the poll reads as a bar chart
 * at a glance and the numbers never have to be printed. The people who picked it are stacked at its trailing end.
 *
 * Under the last pill, where a delivery receipt would go, is the way to add a choice of your own.
 * It opens the poll back in the send bar rather than editing it in place: the thread is not a form,
 * and the composer is already the place in this app where a message is written.
 */
@Composable
fun PollCard(
    message: Message,
    state: PollState,
    modifier: Modifier = Modifier,
    onBubbleLayout: ((LayoutCoordinates) -> Unit)? = null,
) {
    val host = LocalPollHost.current
    val mine = message.mine
    val revealShift = LocalRevealShift.current
    // Everyone who could answer, and never fewer than have: somebody who has since left the group
    // still has a vote on the poll, and a denominator smaller than the turnout would push a bar past
    // full.
    val electorate = maxOf(host.participants, state.total, 1)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = BubbleRowInset),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        Column(
            modifier = modifier
                // With the time column, like every other message — see [RevealShift].
                .graphicsLayer { translationX = revealShift.of(mine) }
                .bubbleMaxWidth(bubbleWidthFraction(mine))
                .then(
                    if (onBubbleLayout == null) Modifier
                    else Modifier.onGloballyPositioned(onBubbleLayout),
                ),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            // The question. "The message is just the choices" is true of the *bubble* — but a poll
            // whose question is not on screen is a list of words nobody can answer, and it is the
            // one thing here that is not pressable, so it is set as a label rather than as a pill.
            Text(
                state.title,
                color = PollAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
            )
            state.options.forEach { option ->
                PollPill(
                    option = option,
                    picked = state.picked(option),
                    // Measured against everyone who could answer, not against the leading choice.
                    // Against the leader, the very first vote threw one pill straight to full
                    // length and the rest of the poll could only ever shrink the others. Against
                    // the conversation, each person who picks a choice adds the same step to it,
                    // so a bar gets longer as more people choose it — and only fills when everyone
                    // has.
                    share = state.count(option).toFloat() / electorate,
                    voters = state.voters(option),
                    member = host.member,
                    onVote = host.onVote?.let { vote -> { vote(message, option) } },
                    modifier = Modifier.padding(vertical = PillGap / 2),
                )
            }
            // Where the delivery receipt sits, and reading like one: a quiet line under the
            // message rather than a button on it.
            host.onAddChoice?.let { add ->
                Text(
                    "Add Choice",
                    color = PollAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { add(message) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * One choice.
 *
 * The width is the whole of the design: [PillFloor] of the bubble's maximum width with nobody on it,
 * the rest of the way in proportion to [share], and never narrower than its own words. Two constraints, one measure —
 * see [pollWidth].
 *
 * Anchored to the sender's side. On your own poll the pills grow leftward from the right edge,
 * which is where your messages are; on somebody else's they grow rightward. A bar chart that grew
 * away from the side its message is on would read as pointing at the wrong person.
 */
@Composable
private fun PollPill(
    option: PollOption,
    picked: Boolean,
    share: Float,
    voters: List<String>,
    member: (String) -> GroupMember?,
    onVote: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Votes arrive one at a time and the bars should move like bars rather than cut to a new
    // length. Spring rather than tween: a bar that overshoots very slightly and settles reads as
    // something with weight, which is what stops a poll from feeling like a web form.
    val filled by animateFloatAsState(
        targetValue = PillFloor + (1f - PillFloor) * share.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "pollShare",
    )
    // Crossfaded rather than switched, so picking a choice is one gesture with one result instead
    // of a colour change that happens a frame before the length does.
    val fill by animateColorAsState(
        targetValue = if (picked) PollFill else PollTrack,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "pollFill",
    )
    val ink by animateColorAsState(
        targetValue = if (picked) PollPickedInk else PollInk,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "pollInk",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .pollWidth(filled)
            .heightIn(min = PillHeight)
            .clip(PillShape)
            .background(fill)
            // A hairline of the fill colour on the unpicked ones, so a short pill on a busy
            // wallpaper still has an edge. Invisible on a picked one, which is already that colour.
            .border(1.dp, PollFill.copy(alpha = if (picked) 0f else 0.45f), PillShape)
            .then(if (onVote == null) Modifier else Modifier.clickable(onClick = onVote))
            .padding(start = 14.dp, end = if (voters.isEmpty()) 14.dp else 5.dp),
    ) {
        Text(
            option.text,
            color = ink,
            fontSize = 14.sp,
            fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // One weight in this row, not two. A weighted spacer beside it to push the faces right
            // looked equivalent and was not: weights split the space between them, so a long choice
            // ellipsised at half the pill's width with the other half sitting empty. The faces are
            // last in the row, which is already the trailing end.
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        if (voters.isNotEmpty()) {
            VoterStack(voters = voters, member = member, ring = fill)
        }
    }
}

/**
 * The people who picked a choice, stacked at its trailing end.
 *
 * Overlapped rather than spaced: four faces in a row would be wider than some of the pills they sit
 * in. Each carries a ring of the pill's own colour so the stack reads front-to-back, and past
 * [MaxFaces] it stops adding faces and says how many more there are — a stack that grew without
 * limit would eat the choice's own words in a group of ten.
 */
@Composable
private fun VoterStack(
    voters: List<String>,
    member: (String) -> GroupMember?,
    ring: Color,
) {
    val shown = voters.take(MaxFaces)
    val extra = voters.size - shown.size
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(-VoterOverlap),
    ) {
        shown.forEach { handle ->
            val person = member(handle)
            Box(
                // Later faces in front, so the stack leans the way it is read.
                modifier = Modifier
                    .size(VoterFace)
                    .clip(CircleShape)
                    .background(ring)
                    .padding(1.5.dp),
            ) {
                if (person != null) {
                    MemberAvatar(member = person, size = VoterFace - 3.dp)
                } else {
                    // A handle nobody in this conversation matches — somebody who has since left
                    // the group, or a poll answered from an address the roster has not caught up
                    // with. Still a vote, and still worth a place in the stack.
                    Avatar(
                        initials = "?",
                        color = PollTrack,
                        size = VoterFace - 3.dp,
                        labelColor = PollInk,
                    )
                }
            }
        }
        if (extra > 0) {
            Box(
                modifier = Modifier.size(VoterFace).clip(CircleShape).background(ring).padding(1.5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(VoterFace - 3.dp)
                        .clip(CircleShape)
                        .background(PollTrack),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+$extra", color = PollInk, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * What a poll shrinks to while it is open in the composer.
 *
 * The choices are being edited in the send bar, so the copy in the thread is not the poll any more
 * — it is a note saying where the poll went. Left as a live set of pills it would be a second,
 * stale copy of the thing under your thumb, and pressing one would vote in a poll you are in the
 * middle of rewriting.
 *
 * On the sender's own side of the screen, like a receipt, because that is what it is standing in
 * for: a line about a message rather than a message.
 */
@Composable
fun PollPlaceholder(mine: Boolean, senderName: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = BubbleRowInset + 12.dp, vertical = 3.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            if (mine) "You sent a poll" else "${senderName ?: "They"} sent a poll",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
    }
}

/**
 * Takes [fraction] of the width on offer, or whatever this needs to draw itself — whichever is more.
 *
 * One measure, not two: a minimum width in the constraints is what expresses "at least this wide,
 * and wider if the contents say so", and Compose allows a child to be measured only once. Measuring
 * for the natural width and then again for the final one is the obvious way to write this and it
 * throws.
 */
private fun Modifier.pollWidth(fraction: Float): Modifier = layout { measurable, constraints ->
    val target = if (constraints.hasBoundedWidth) {
        (constraints.maxWidth * fraction.coerceIn(0f, 1f)).roundToInt().coerceIn(0, constraints.maxWidth)
    } else {
        0
    }
    val placeable = measurable.measure(constraints.copy(minWidth = target))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}
