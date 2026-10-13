package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import coil3.compose.AsyncImage
import com.ruskcoder.messages.data.model.AVATAR_INK
import com.ruskcoder.messages.data.model.Chat
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.NO_INITIALS
import com.ruskcoder.messages.util.toComposeColor

/**
 * The one place a round avatar is drawn.
 *
 * Every screen used to inline its own `Box(CircleShape) { Text(initials) }`, which is why adding
 * contact photos meant touching nine call sites — and why they had all drifted apart on label
 * weight and size. A photo is layered *over* the initials rather than swapped in for them, so a
 * uri that fails to decode (a contact whose photo was deleted, a revoked permission) falls back to
 * the letters underneath instead of a blank circle.
 */
@Composable
fun Avatar(
    initials: String,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    photoUri: String? = null,
    /**
     * Dark on every fill, because [AVATAR_INK] is what the palette is built to carry. A caller can
     * still override it for a colour that did not come out of that palette.
     */
    labelColor: Color = AvatarInk,
    /** Defaults to a proportion of [size], so one knob sets both without every caller doing the sum. */
    labelSize: TextUnit = (size.value * LabelRatio).sp,
) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        // A conversation with a bare phone number has no letters to show. Every other app draws a
        // silhouette there; this one used to draw a literal "#", which read as a real name.
        val letters = initials.trim().takeIf { it.isNotEmpty() && it != NO_INITIALS }
        if (letters != null) {
            // Regular weight, not bold. A single letter set this large carries on its own, and
            // bold at this size reads as shouting rather than as a name.
            //
            // Both trims matter, and they are why the letter used to sit low in its circle. A
            // Text's box is a *line* box, not a glyph box: it carries the font's leading above the
            // ascent and below the descent, and centring that box centres the leading along with
            // the letter. At avatar sizes — where the glyph is more than half the circle — the few
            // dip of unpaired descent that a capital letter never uses is plainly visible as the
            // monogram riding below the middle.
            Text(
                letters,
                color = labelColor,
                fontWeight = FontWeight.Normal,
                fontSize = labelSize,
                style = LocalTextStyle.current.merge(
                    TextStyle(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                ),
            )
        } else {
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier.size(size * 0.6f),
            )
        }
        if (!photoUri.isNullOrBlank()) {
            AsyncImage(
                model = photoUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

/** The same avatar for one group member, whose colour and photo travel with them. */
@Composable
fun MemberAvatar(
    member: GroupMember,
    size: Dp,
    modifier: Modifier = Modifier,
    labelSize: TextUnit = (size.value * LabelRatio).sp,
) {
    Avatar(
        initials = member.initials,
        color = member.color.toComposeColor(),
        size = size,
        modifier = modifier,
        photoUri = member.photoUri,
        labelSize = labelSize,
    )
}

/**
 * Two members' faces overlapped, the way every messaging app distinguishes a group row at a glance.
 *
 * Deliberately only two: at chat-list size a third circle is smaller than the letter inside it.
 * Falls back to a single avatar for a group whose roster we have not synced yet, so a row is never
 * left blank while the participant list is still arriving.
 */
@Composable
fun GroupAvatar(
    members: List<GroupMember>,
    fallbackInitials: String,
    fallbackColor: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    /**
     * What the gap between the two faces is filled with. Defaults to the theme's background, which
     * is right everywhere except the chat list in AMOLED dark — that page paints itself pure black,
     * and the default left a dark-grey halo floating on it.
     */
    ringColor: Color = MaterialTheme.colorScheme.background,
) {
    if (members.size < 2) {
        val only = members.firstOrNull()
        Avatar(
            initials = only?.initials ?: fallbackInitials,
            color = only?.color?.toComposeColor() ?: fallbackColor,
            size = size,
            modifier = modifier,
            photoUri = only?.photoUri,
        )
        return
    }

    val face = size * 0.66f
    // The back face is ringed in the page's own colour so the two read as separate circles rather
    // than as one blob wherever their fills happen to be close.
    val ring = ringColor
    Box(modifier = modifier.size(size)) {
        MemberAvatar(
            member = members[1],
            size = face,
            modifier = Modifier.align(Alignment.TopEnd),
            labelSize = (face.value * LabelRatio).sp,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(face + RingWidth * 2)
                .offset(x = -RingWidth, y = RingWidth)
                .clip(CircleShape)
                .background(ring),
            contentAlignment = Alignment.Center,
        ) {
            MemberAvatar(member = members[0], size = face, labelSize = (face.value * LabelRatio).sp)
        }
    }
}

private val RingWidth = 1.5.dp

/**
 * How much of the circle the letter fills.
 *
 * Deliberately large. A single letter at the old proportion sat in the middle of its circle like a
 * label on a badge; set this big it *is* the avatar, which is what makes a row findable by its
 * monogram when you are scrolling past thirty of them. The palette being uniformly pale is what
 * allows it — a letter this size in white on a mid-tone would have been a glare.
 */
private const val LabelRatio = 0.56f

/** The one colour a monogram is drawn in. See [AVATAR_INK]. */
private val AvatarInk = AVATAR_INK.toComposeColor()

/** Whichever of the two a conversation needs — the single decision point for a chat's avatar. */
@Composable
fun ChatAvatar(
    chat: Chat,
    size: Dp,
    modifier: Modifier = Modifier,
    ringColor: Color = MaterialTheme.colorScheme.background,
) {
    if (chat.group) {
        GroupAvatar(
            members = chat.members,
            fallbackInitials = chat.initials,
            fallbackColor = chat.color.toComposeColor(),
            size = size,
            modifier = modifier,
            ringColor = ringColor,
        )
    } else {
        Avatar(
            initials = chat.initials,
            color = chat.color.toComposeColor(),
            size = size,
            modifier = modifier,
            photoUri = chat.photoUri,
        )
    }
}
