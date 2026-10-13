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
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.MessageReceipts
import com.ruskcoder.messages.imessage.Handles
import com.ruskcoder.messages.util.formatChatTime
import com.ruskcoder.messages.util.formatClock

/** One member's line in the info list: who, what state, and when it reached that state. */
private data class ReceiptLine(val member: GroupMember, val label: String, val at: Long?)

/**
 * Who has seen one of your own messages, and when.
 *
 * A group's receipt line can only ever be a count; this is where the count opens up. Members are
 * ordered read-first and then by how recently each receipt landed, so the most recent reader is at
 * the top — which is the order the question "has anyone seen this yet" is actually asked in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInfoSheet(
    members: List<GroupMember>,
    receipts: MessageReceipts?,
    sentAt: Long,
    onDismiss: () -> Unit,
) {
    // Receipts are keyed by canonical handle and a roster carries raw ones, so both sides are put
    // through the same normalization before they are matched — otherwise `tel:+15551234567` and
    // `tel:5551234567` would look like two different people.
    val read = receipts?.read.orEmpty().mapKeys { Handles.key(it.key) }
    val delivered = receipts?.delivered.orEmpty().mapKeys { Handles.key(it.key) }

    val lines = members
        .map { member ->
            val key = Handles.key(member.id)
            when {
                read.containsKey(key) -> ReceiptLine(member, "Read", read[key])
                delivered.containsKey(key) -> ReceiptLine(member, "Delivered", delivered[key])
                else -> ReceiptLine(member, "Sent", null)
            }
        }
        .sortedWith(compareByDescending<ReceiptLine> { it.at ?: 0L }.thenBy { it.member.name })

    AppBottomSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                "Message info",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 4.dp),
            )
            Text(
                "Sent ${formatChatTime(sentAt)} at ${formatClock(sentAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp),
            )
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(lines, key = { it.member.id }) { line ->
                    ListItem(
                        headlineContent = { Text(line.member.name) },
                        supportingContent = {
                            Text(
                                line.at?.let { "${line.label} · ${formatClock(it)}" } ?: line.label,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        leadingContent = { MemberAvatar(member = line.member, size = 40.dp) },
                        // The sheet's glass is the surface here — see [ForwardSheet].
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
