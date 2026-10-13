package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.Contact
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.ReactionSummary
import com.ruskcoder.messages.imessage.Handles
import com.ruskcoder.messages.util.toComposeColor

/** One reactor: who they are, and which glyph they put on the message. */
private data class Reactor(val emoji: String, val member: GroupMember?, val label: String)

/**
 * Who reacted to a message, and with what.
 *
 * The bubble can only ever show glyphs and a count. This is where they resolve into people —
 * matched against the group's roster where there is one, and falling back to the handle itself for
 * someone who has since left or was never in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionsSheet(
    reactions: List<ReactionSummary>,
    members: List<GroupMember>,
    /** Our own handles, so our own reaction reads as "You" rather than as our phone number. */
    myHandles: Set<String>,
    /**
     * Who the conversation is with, for a one-to-one — where there is no roster to match against.
     *
     * A group carries a [members] list built from the address book, so a reactor in one resolves to
     * a name. A one-to-one carries an empty one by design — the other person *is* the conversation
     * — which left the only reaction anyone but us could have placed labelled with a phone number.
     * The chat's own name is that person, already resolved, so it is what the row says. Null in a
     * group, where "the other person" is not a thing.
     */
    peerName: String? = null,
    /**
     * The address book, for a handle the roster does not know — someone who has left the group, or
     * a member added before the roster was last refreshed.
     */
    contacts: List<Contact> = emptyList(),
    onDismiss: () -> Unit,
) {
    val byKey = members.associateBy { Handles.key(it.id) }
    val reactors = reactions.flatMap { reaction ->
        reaction.senders.map { sender ->
            val key = Handles.key(sender)
            val member = byKey[key]
            // Falls through in order of how specific the answer is: this conversation's roster,
            // then the phone's address book, then — one-to-one only — the person the conversation
            // is with. The handle itself is the last resort, not the first.
            val contact = if (member == null) contacts.firstOrNull { Handles.matchesNumber(sender, it.about) } else null
            Reactor(
                emoji = reaction.emoji,
                member = member ?: contact?.let { GroupMember(it.id, it.name, it.initials, it.color, it.about, it.photoUri) },
                label = when {
                    sender in myHandles || key in myHandles -> "You"
                    member != null -> member.name
                    contact != null -> contact.name
                    else -> peerName?.takeIf { it.isNotBlank() } ?: Handles.display(sender)
                },
            )
        }
    }

    AppBottomSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                if (reactors.size == 1) "1 reaction" else "${reactors.size} reactions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp),
            )
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(reactors, key = { "${it.emoji}-${it.label}" }) { reactor ->
                    ListItem(
                        headlineContent = { Text(reactor.label) },
                        leadingContent = {
                            val member = reactor.member
                            if (member != null) {
                                MemberAvatar(member = member, size = 40.dp)
                            } else {
                                Avatar(
                                    initials = reactor.label.take(1).uppercase(),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    size = 40.dp,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingContent = { Text(reactor.emoji, fontSize = 20.sp) },
                        // The sheet's own glass is the surface. A row that paints its own covers
                        // it, and the list ends up looking like an opaque card inside a frosted
                        // one — which is the whole point of the sheet, undone one row at a time.
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
