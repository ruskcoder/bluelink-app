package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.repository.ScheduledSend
import com.ruskcoder.messages.data.repository.ScheduledSends
import com.ruskcoder.messages.imessage.ScheduledSendAlarm
import java.util.Date

/** Tall enough for three or four without the sheet swallowing the screen; longer lists scroll. */
private val ListMaxHeight = 420.dp

/**
 * The messages this conversation has waiting, and the two things you can do to one.
 *
 * Read once when the sheet opens and held here afterwards, rather than observed: the queue lives in
 * preferences, which have no change feed, and the only thing that edits it while this is on screen
 * is this. An entry that goes out under the sheet is the one case that would drift — a message due
 * within the seconds someone spends looking at the list — and it is one the next open corrects.
 */
@Composable
fun ScheduledMessagesSheet(
    chatId: String,
    onDismiss: () -> Unit,
    /** So the composer's own count follows what happens in here. */
    onChanged: (List<ScheduledSend>) -> Unit,
) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf(ScheduledSends.forChat(context, chatId)) }
    var editing by remember { mutableStateOf<ScheduledSend?>(null) }

    fun refresh() {
        val all = ScheduledSends.load(context)
        pending = all.filter { it.chatId == chatId }
        onChanged(all)
    }

    AppBottomSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(
                "Scheduled messages",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Text(
                "Waiting to be sent in this conversation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )

            if (pending.isEmpty()) {
                Text(
                    "Nothing scheduled here yet. Hold the send button on a message you have written " +
                        "to pick a time for it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = ListMaxHeight)) {
                items(pending, key = { it.id }) { entry ->
                    ScheduledRow(
                        entry = entry,
                        onEdit = { editing = entry },
                        onCancel = { ScheduledSendAlarm.cancel(context, entry.id); refresh() },
                    )
                    if (entry !== pending.last()) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 24.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        ScheduleTimeDialog(
            title = "Send at",
            initialAt = entry.at,
            onDismiss = { editing = null },
            onPick = { at ->
                editing = null
                ScheduledSendAlarm.reschedule(context, entry.id, at)
                refresh()
            },
        )
    }
}

@Composable
private fun ScheduledRow(entry: ScheduledSend, onEdit: () -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(
            entry.text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Icon(
                Icons.Rounded.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                ScheduledTimeLabel.format(Date(entry.at)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) {
                Text("Cancel send", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onEdit) { Text("Change time") }
        }
    }
}
