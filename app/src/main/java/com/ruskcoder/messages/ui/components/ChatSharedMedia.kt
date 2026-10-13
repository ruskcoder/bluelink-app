package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.Message

/** How many thumbnails the strip shows before it starts counting the rest. */
private const val MEDIA_TILE_COUNT = 4

/** Every picture and clip in a conversation, newest first, as plain uris. */
fun sharedMediaOf(thread: List<Message>): List<String> =
    thread.asReversed()
        .mapNotNull { (it as? Message.AttachmentMessage)?.attachment }
        // Pictures and video only. A file or a contact card is shared media too, but it has no
        // thumbnail to put in a strip, and a grid of grey document icons is not a strip of media.
        .mapNotNull {
            when (it) {
                is Attachment.Image -> it.uri
                is Attachment.Video -> it.uri
                else -> null
            }
        }
        .filter { it.isNotBlank() }

/**
 * What a conversation holds, as one panel: its pictures across the top, and a way to everything
 * else underneath.
 *
 * Shared by the contact and group pages, which ask the same question about a conversation and had
 * no reason to answer it in two different shapes. Both rows go to the same place — the search page
 * confined to this chat, which is where the links, the files and the rest are actually listed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatSharedMediaSection(
    media: List<String>,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FrostedSection(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBrowse)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "Images", because that is what the strip underneath holds. Everything else this
            // used to promise — the links, the files — is the row below, which goes somewhere they
            // are listed rather than naming them here and showing pictures.
            Text("Images", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${media.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Chevron()
            }
        }

        if (media.isEmpty()) {
            Text(
                "Nothing shared yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        } else {
            val shown = media.take(MEDIA_TILE_COUNT)
            // The overflow count goes on the last tile shown, and only when there is actually
            // something behind it — a "+0" badge on a conversation with exactly four pictures is
            // worse than no badge at all.
            val hidden = media.size - shown.size
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                shown.forEachIndexed { index, uri ->
                    Box(
                        modifier = Modifier
                            .width(150.dp)
                            .aspectRatio(1.35f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onBrowse),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (index == shown.lastIndex && hidden > 0) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("+$hidden", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        }

        ListItem(
            headlineContent = { Text("More") },
            leadingContent = { Icon(Icons.Outlined.MoreHoriz, contentDescription = null) },
            trailingContent = { Chevron() },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onBrowse),
        )
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}
