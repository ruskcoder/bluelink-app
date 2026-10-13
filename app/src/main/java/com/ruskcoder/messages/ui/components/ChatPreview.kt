package com.ruskcoder.messages.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneMissed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.ruskcoder.messages.data.model.Chat
import com.ruskcoder.messages.data.model.LastMessage

/**
 * The small glyph in front of a chat row's preview line, or null for plain text.
 *
 * A photo used to read as the bare word "Photo", indistinguishable from someone having typed it.
 * The icon is what makes the line scannable — you can tell at a glance which conversations are
 * waiting with a voice note and which with a sentence.
 *
 * Attachment kind is not stored on the row, so this is as fine-grained as the join allows: an
 * `attachment` covers photos, videos and files alike. The preview text itself still names which.
 */
fun previewIcon(last: LastMessage?, preview: String): ImageVector? = when (last?.kind) {
    "audio" -> Icons.Filled.Mic
    "call" -> Icons.Filled.PhoneMissed
    "attachment" -> when {
        // The body a media message stores is its own label, which is the only thing that
        // distinguishes the three at this level.
        preview.startsWith("Photo", ignoreCase = true) -> Icons.Filled.Image
        preview.startsWith("Video", ignoreCase = true) -> Icons.Filled.Videocam
        else -> Icons.Filled.Description
    }
    else -> null
}

/**
 * Who wrote the newest message, as the prefix on a chat row's preview line.
 *
 * "You: " on your own, a first name in a group, nothing in a one-to-one — where the only person it
 * could be is the one already named on the row above it. A conversation event names its own actor
 * in its text, so it takes no prefix either.
 */
fun previewPrefix(chat: Chat): String {
    val last = chat.last ?: return ""
    if (last.kind == "system") return ""
    if (last.mine) return "You: "
    if (!chat.group) return ""
    return last.senderName?.substringBefore(' ')?.let { "$it: " } ?: ""
}
