package com.ruskcoder.messages.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.MemberView
import com.ruskcoder.messages.data.model.ReplyMode
import com.ruskcoder.messages.data.model.ReplyPreview
import com.ruskcoder.messages.data.model.Settings
import com.ruskcoder.messages.data.model.colorFor
import com.ruskcoder.messages.data.model.initialsFor
import com.ruskcoder.messages.data.model.sendBar
import com.ruskcoder.messages.ui.components.ChatComposer
import com.ruskcoder.messages.ui.components.ChatWallpaper
import com.ruskcoder.messages.ui.components.MemberAvatar
import com.ruskcoder.messages.ui.components.MessageBubble

private fun sample(id: String, mine: Boolean, text: String) = Message.Text(
    id = id,
    mine = mine,
    time = "9:41",
    text = text,
    status = DeliveryStatus.READ,
)

private val EARLIER = sample("reply-preview-a", mine = true, text = "Dinner at 7?")
private val ORIGINAL = sample("reply-preview-b", mine = false, text = "I booked the corner table")
private val ANSWER = sample("reply-preview-c", mine = true, text = "Perfect, see you there")

/**
 * What replying looks like in the chosen style, drawn from the real bubbles and the real send bar.
 *
 * Quick quote puts the message being answered inside the send bar, which is what the composer does
 * when it is handed a reply preview. Focus thread lifts the message out of a conversation that has
 * stepped back behind a blur and a dim — the same two things the conversation applies — with the
 * reply landing directly beneath it.
 */
@Composable
fun ReplyStylePreview(settings: Settings, modifier: Modifier = Modifier) {
    val focus = settings.replyMode == ReplyMode.FOCUS
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(20.dp))) {
        ChatWallpaper(modifier = Modifier.matchParentSize()) {}
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 10.dp)) {
            if (focus) {
                Box {
                    // Blurred, then dimmed: what the thread does behind a lifted message.
                    Column(modifier = Modifier.blur(8.dp)) {
                        MessageBubble(message = EARLIER, isFirstInRun = true, isLastInRun = true)
                    }
                    Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)))
                }
                MessageBubble(message = ORIGINAL, isFirstInRun = true, isLastInRun = true)
                MessageBubble(message = ANSWER, isFirstInRun = true, isLastInRun = true)
            } else {
                MessageBubble(message = EARLIER, isFirstInRun = true, isLastInRun = true)
                MessageBubble(message = ORIGINAL, isFirstInRun = true, isLastInRun = true)
            }
            ChatComposer(
                text = if (focus) "" else "Perfect, see you there",
                onTextChange = {},
                onSend = {},
                onAttachmentClick = {},
                onMicClick = {},
                sendBarType = settings.bubbleType.sendBar(),
                replyPreview = if (focus) null else ReplyPreview(id = ORIGINAL.id, name = "Alex", text = ORIGINAL.text, mine = false),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

private val SAMPLE_MEMBERS = listOf("Alex Rivera" to "+1 555 0101", "Sam Chen" to "+1 555 0102", "Jo Park" to "+1 555 0103")
    .map { (name, phone) -> GroupMember(id = name, name = name, initials = initialsFor(name), color = colorFor(name), phone = phone) }

/** A group's member list, in miniature, in the layout that is selected. */
@Composable
fun MemberLayoutPreview(view: MemberView, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp),
    ) {
        if (view == MemberView.GRID) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SAMPLE_MEMBERS.forEach { member ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MemberAvatar(member = member, size = 44.dp)
                        Text(member.name.substringBefore(' '), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SAMPLE_MEMBERS.forEach { member ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MemberAvatar(member = member, size = 32.dp)
                        Column {
                            Text(member.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                member.phone.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
