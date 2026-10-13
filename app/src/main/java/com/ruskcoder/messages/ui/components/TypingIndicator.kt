package com.ruskcoder.messages.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.imessage.Handles
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.ruskcoder.messages.data.model.BubbleStyle
import com.ruskcoder.messages.ui.components.bubbles.BubbleRowInset
import com.ruskcoder.messages.ui.components.bubbles.useAppleBubbleColors
import com.ruskcoder.messages.ui.theme.LocalSettings

/** How long one dot takes to rise and fall, and how far apart the three are staggered. */
private const val DotCycleMs = 1000
private const val DotStaggerMs = 160

/** How far a dot lifts, as a fraction of its own size. */
private const val DotLift = 0.35f

/**
 * The three-dot bubble at the tail of a conversation while the other end is writing.
 *
 * There was nowhere in the thread that said someone was typing — only a word under the header,
 * which is easy to miss and says nothing about who in a group. A bubble in the conversation is
 * where the message is about to appear, which is where the eye already is.
 */
@Composable
fun TypingBubble(senders: List<GroupMember> = emptyList(), modifier: Modifier = Modifier) {
    val apple = LocalSettings.current.bubbleType == BubbleStyle.APPLE
    // On Apple the circles hang out past the pill's leading edge, so the row starts that much
    // further in and the pill itself still lands on the margin received bubbles keep.
    val start = if (apple && senders.isEmpty()) BubbleRowInset - AppleTailReach else 8.dp
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = modifier.fillMaxWidth().padding(start = start, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        // The same avatar gutter an incoming group bubble keeps, so the dots line up with the
        // messages rather than sitting proud of them.
        //
        // Several faces overlap rather than lining up, the way a group's own picture does. Three
        // people writing at once is common and four is not, so the row is capped: laid out in full
        // the gutter grew with every extra writer and pushed the dots halfway across the screen,
        // which is a lot of movement to report something that resolves itself in a second.
        if (senders.isNotEmpty()) {
            Box(modifier = Modifier.padding(end = 8.dp)) {
                senders.take(TypingFacesShown).forEachIndexed { index, member ->
                    MemberAvatar(
                        member = member,
                        size = TypingFaceSize,
                        // Each face sits a little way along from the one behind it, and is drawn
                        // over it: a ring of the conversation's own background separates them, so
                        // the stack reads as distinct people rather than as one smeared shape.
                        modifier = Modifier
                            .padding(start = TypingFaceStep * index)
                            .border(TypingFaceRing, MaterialTheme.colorScheme.background, CircleShape)
                            .padding(TypingFaceRing),
                    )
                }
            }
        }
        if (apple) {
            AppleTypingPill()
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                repeat(3) { index -> Dot(index) }
            }
        }
    }
}

/**
 * iMessage's own typing indicator: a received bubble — the same fill, the same stadium — with the tail
 * swapped for a thought bubble. Two circles trail off its bottom-left corner: a larger one sitting over
 * the corner itself, and a smaller one further out below it, the way a cartoon's thought trails back
 * toward whoever is thinking it.
 *
 * Every measurement is taken off the design this was drawn from, a 376×248 canvas whose pill is 206
 * units tall. [U] is one of those units, so the numbers below read straight off that drawing and the
 * whole thing stays in proportion if the pill is ever resized.
 */
@Composable
private fun AppleTypingPill() {
    val colors = useAppleBubbleColors(mine = false)
    // Room for the trail, which is drawn outside the pill and would otherwise be cut off by whatever
    // sits beside and below it.
    Box(modifier = Modifier.padding(start = AppleTailReach, bottom = AppleTailDrop)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(U * 21),
            modifier = Modifier
                // Before the clip, so the circles are drawn outside the pill rather than inside it.
                .drawBehind {
                    val u = U.toPx()
                    // Over the corner: its left edge flush with the pill's, bulging out below it.
                    drawCircle(
                        color = colors.background,
                        radius = u * 33.5f,
                        center = Offset(u * 33.5f, u * 192.5f),
                    )
                    // Out below and behind it, touching it.
                    drawCircle(
                        color = colors.background,
                        radius = u * 15f,
                        center = Offset(u * -14f, u * 233f),
                    )
                }
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.background)
                .padding(horizontal = U * 68, vertical = U * 75),
        ) {
            repeat(3) { index -> Dot(index, color = colors.meta, size = U * 56) }
        }
    }
}

/** One unit of the design the Apple indicator is measured from: a 206-unit-tall pill drawn 34dp tall. */
private val U = 34.dp / 206

/** How far the trail reaches past the pill's leading edge: the small circle's far side. */
private val AppleTailReach = U * 29

/** And past its bottom edge. */
private val AppleTailDrop = U * 42

@Composable
private fun Dot(index: Int, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, size: Dp = 7.dp) {
    val transition = rememberInfiniteTransition(label = "typing")
    val lift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            // Keyframes rather than a plain tween: a dot spends most of the cycle at rest and only
            // the middle of it moving, which is what makes three staggered dots read as a wave
            // instead of as a shimmer.
            animation = keyframes {
                durationMillis = DotCycleMs
                0f at 0 using LinearEasing
                1f at DotCycleMs / 4 using LinearEasing
                0f at DotCycleMs / 2 using LinearEasing
                0f at DotCycleMs
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = androidx.compose.animation.core.StartOffset(index * DotStaggerMs),
        ),
        label = "dot$index",
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { translationY = -lift * this.size.height * DotLift }
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * What the header says while someone is writing.
 *
 * A group names them — "Alex is typing…" — because "typing…" over a conversation with five people
 * in it answers the less interesting half of the question. Falls back to the bare word when the
 * handle matches nobody on the roster.
 */
fun typingLabel(sender: String?, members: List<GroupMember>, group: Boolean): String {
    if (sender == null) return "typing…"
    if (!group) return "typing…"
    val name = memberFor(sender, members)?.name?.substringBefore(' ')
    return if (name == null) "typing…" else "$name is typing…"
}

/**
 * The roster entry for a handle off the wire.
 *
 * Matched through the canonical form rather than by string equality: a typing notice can name the
 * same person by a differently-spelled handle than the one the roster stored.
 */
fun memberFor(handle: String, members: List<GroupMember>): GroupMember? {
    val key = Handles.key(handle)
    return members.firstOrNull { Handles.key(it.id) == key }
}

/**
 * The line under a group's name in the header: who is in it.
 *
 * A group header showed only its own name, which for a group with a custom name told you nothing
 * about who you were writing to. First names, because the header is one line and there may be a
 * dozen of them; a count once the list would be longer than the line can hold.
 */
fun groupSubtitle(members: List<GroupMember>): String? {
    if (members.isEmpty()) return null
    val names = members.map { it.name.substringBefore(' ') }
    val joined = names.joinToString(", ")
    return if (joined.length <= SubtitleMaxChars) {
        joined
    } else {
        "${members.size} members"
    }
}

/** Roughly what fits on a header line beside the avatar and the three action buttons. */
private const val SubtitleMaxChars = 38

/** How many writers the indicator shows before it stops adding faces. */
private const val TypingFacesShown = 3

private val TypingFaceSize = 26.dp

/** How far along each face sits from the one behind it — enough to be seen, not enough to line up. */
private val TypingFaceStep = 14.dp

/** The ring of background that keeps one face from bleeding into the next. */
private val TypingFaceRing = 1.5.dp
