package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.GroupMember

/** How many suggestions the strip shows before it scrolls. */
private val MaxHeight = 208.dp

/**
 * The half-typed `@name` at the end of a draft, or null when the composer isn't mentioning anyone.
 *
 * Anchored to the end of the text rather than to a cursor position: the composer works in plain
 * strings, and typing an at-sign then a name is how a mention is actually written. An at-sign
 * inside a word is left alone — an email address in a draft is not a mention — and a token that has
 * run past two words is given up on, so ordinary prose containing an at-sign stops suggesting.
 */
fun pendingMentionQuery(text: String): String? {
    val at = text.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !text[at - 1].isWhitespace()) return null
    val token = text.substring(at + 1)
    if (token.contains('\n')) return null
    if (token.count { it == ' ' } > 1) return null
    return token
}

/**
 * Replace the half-typed mention at the end of [text] with [member]'s full name.
 *
 * The trailing space matters: it closes the token, so the picker stops offering suggestions for a
 * mention that has already been made.
 */
fun applyMention(text: String, member: GroupMember): String {
    val at = text.lastIndexOf('@')
    if (at < 0) return text
    return text.substring(0, at) + "@" + member.name + " "
}

/**
 * The list of people a half-typed `@` could mean.
 *
 * Sits directly above the composer, matching on any word of a name so "@kim" finds "Alex Kim" —
 * people are as likely to reach for a surname as a first name.
 */
@Composable
fun MentionSuggestions(
    query: String,
    members: List<GroupMember>,
    onPick: (GroupMember) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matches = members.filter { member ->
        query.isBlank() || member.name.split(' ').any { it.startsWith(query, ignoreCase = true) }
    }
    if (matches.isEmpty()) return

    // The names are drawn on the bar's own glass, so they take the bar's own ink: white over the
    // Apple film, which is dark whatever the theme is, and the scheme's otherwise.
    val ink = if (composerGlassIsDark()) Color.White else MaterialTheme.colorScheme.onSurface
    ComposerGlass(
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .heightIn(max = MaxHeight),
    ) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(matches, key = { it.id }) { member ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(member) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    MemberAvatar(member = member, size = 34.dp)
                    Text(member.name, fontWeight = FontWeight.Medium, color = ink)
                }
            }
        }
    }
}
