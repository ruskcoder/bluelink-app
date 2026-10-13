package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.Chat
import com.ruskcoder.messages.util.toComposeColor

/**
 * Where to forward the selected messages. Mounted only while open, so the list is read fresh each
 * time rather than held open behind the thread. Ported from `forward-sheet.tsx`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardSheet(count: Int, chats: List<Chat>, excludeId: String, onDismiss: () -> Unit, onForward: (Chat) -> Unit) {
    val targets = chats.filter { it.id != excludeId }

    AppBottomSheet(onDismiss = onDismiss) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Forward to", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (count == 1) "1 message" else "$count messages",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }

        if (targets.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
                Text("No other conversations yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // Capped rather than fixed, and clear of the navigation bar: a fixed 420dp list runs its
            // last row under the gesture pill on a short screen.
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).navigationBarsPadding()) {
                items(targets, key = { it.id }) { chat ->
                    ListItem(
                        headlineContent = { Text(chat.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(
                                chat.preview.ifEmpty { "No messages yet" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = { ChatAvatar(chat = chat, size = 44.dp) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) },
                        // The sheet's glass is the surface here; a row painting its own would lay an
                        // opaque slab over the blurred screen showing through everything around it.
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onForward(chat) },
                    )
                }
            }
        }
    }
}
