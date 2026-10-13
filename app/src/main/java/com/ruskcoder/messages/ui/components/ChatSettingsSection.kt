package com.ruskcoder.messages.ui.components

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.Chat
import com.ruskcoder.messages.data.model.ChatThemeKey
import com.ruskcoder.messages.data.model.readReceiptsOn
import com.ruskcoder.messages.data.model.typingIndicatorsOn
import com.ruskcoder.messages.ui.theme.LocalSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.font.FontWeight
import com.ruskcoder.messages.data.repository.ChatRepository
import java.util.concurrent.TimeUnit

/**
 * How long a snooze can last, and what each one is called.
 *
 * Durations rather than clock times: "until tomorrow morning" reads well and then has to answer
 * what morning means on a phone that crossed a time zone last night. An hour is an hour anywhere.
 */
private enum class Snooze(val label: String, val millis: Long) {
    HOUR("For 1 hour", TimeUnit.HOURS.toMillis(1)),
    EIGHT_HOURS("For 8 hours", TimeUnit.HOURS.toMillis(8)),
    DAY("For 1 day", TimeUnit.DAYS.toMillis(1)),
    WEEK("For 1 week", TimeUnit.DAYS.toMillis(7)),
    FOREVER("Until I turn it back on", 0),
}

/**
 * The settings a conversation carries on its own, as one panel of the details page.
 *
 * Shared by the contact and group pages because they are the same page seen from either side of a
 * chat, and a setting that exists on one and not the other is a setting people go looking for. The
 * only thing that differs is [showBlock]: there is nobody to block in a group, where the equivalent
 * decisions are reporting it and leaving.
 */
@Composable
fun ChatSettingsSection(
    chat: Chat?,
    chatRepository: ChatRepository,
    /** Opens the same colour sheet the page's menu does, rather than a second copy of it. */
    onOpenTheme: () -> Unit,
    showBlock: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (chat == null) return
    val context = LocalContext.current
    var snoozeOpen by remember { mutableStateOf(false) }
    var blockOpen by remember { mutableStateOf(false) }

    val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            // Persisted, or the uri stops resolving the next time the process starts and the
            // conversation silently loses the picture behind it.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            chatRepository.setWallpaper(chat.id, uri.toString())
        }
    }

    val settings = LocalSettings.current
    val snoozedFor = remember(chat.snoozedUntil) { snoozeLabel(chat.snoozedUntil) }

    FrostedSection(modifier = modifier) {
        ChatOption(
            icon = Icons.Outlined.Palette,
            title = "Chat theme",
            description = chatThemeDescription(chat.theme),
            onClick = onOpenTheme,
        )
        ChatOption(
            icon = Icons.Outlined.Snooze,
            title = "Snooze chat",
            description = snoozedFor,
            onClick = { snoozeOpen = true },
        )
        ChatOption(
            icon = Icons.Outlined.NotificationsNone,
            title = "Notifications",
            // Stored as "muted" and shown as "notifications", so the switch reads the way every
            // other switch on the page does: on means the thing named in the row happens.
            trailing = {
                ChatSwitch(checked = !chat.muted) { on -> chatRepository.setMuted(chat.id, !on) }
            },
            onClick = { chatRepository.setMuted(chat.id, !chat.muted) },
        )
        ChatOption(
            icon = Icons.Outlined.Wallpaper,
            title = "Wallpaper",
            description = if (chat.wallpaperUri != null) "A picture of your own" else "Follows the app",
            onClick = {
                pickWallpaper.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            // Nothing to remove until there is a picture to remove, so the row is a plain chevron
            // until then.
            trailing = if (chat.wallpaperUri == null) {
                null
            } else {
                { TextButton(onClick = { chatRepository.setWallpaper(chat.id, null) }) { Text("Remove") } }
            },
        )
        // Both follow the switches on the Chats page until touched here; touching one pins this
        // conversation, and the subtitle says so and offers the way back.
        val readReceiptsOn = chat.readReceiptsOn(settings)
        ChatOption(
            icon = Icons.Outlined.DoneAll,
            title = "Send read receipts",
            subtitle = chat.readReceipts?.let { { ManuallySet(onReset = { chatRepository.setReadReceipts(chat.id, null) }) } },
            trailing = {
                ChatSwitch(checked = readReceiptsOn) { on -> chatRepository.setReadReceipts(chat.id, on) }
            },
            onClick = { chatRepository.setReadReceipts(chat.id, !readReceiptsOn) },
        )
        val typingOn = chat.typingIndicatorsOn(settings)
        ChatOption(
            icon = Icons.Outlined.Keyboard,
            title = "Send typing indicators",
            subtitle = chat.typingIndicators?.let { { ManuallySet(onReset = { chatRepository.setTypingIndicators(chat.id, null) }) } },
            trailing = {
                ChatSwitch(checked = typingOn) { on -> chatRepository.setTypingIndicators(chat.id, on) }
            },
            onClick = { chatRepository.setTypingIndicators(chat.id, !typingOn) },
        )
        if (showBlock) {
            ChatOption(
                icon = Icons.Outlined.Block,
                title = if (chat.blocked) "Unblock" else "Block",
                destructive = true,
                onClick = { blockOpen = true },
            )
        }
    }

    if (snoozeOpen) {
        SnoozeDialog(
            snoozed = chat.snoozedUntil > System.currentTimeMillis(),
            onDismiss = { snoozeOpen = false },
            onPick = { choice ->
                snoozeOpen = false
                chatRepository.setSnoozedUntil(
                    chat.id,
                    // "Until I turn it back on" is not a snooze at all; it is the mute switch, so
                    // that is what it sets rather than an end date a century away.
                    if (choice == Snooze.FOREVER) 0 else System.currentTimeMillis() + choice.millis,
                )
                if (choice == Snooze.FOREVER) chatRepository.setMuted(chat.id, true)
            },
            onClear = {
                snoozeOpen = false
                chatRepository.setSnoozedUntil(chat.id, 0)
            },
        )
    }

    if (blockOpen) {
        val blocking = !chat.blocked
        AppDialog(
            title = if (blocking) "Block ${chat.name}?" else "Unblock ${chat.name}?",
            message = if (blocking) {
                "They won't be able to message you, and you won't see anything they send. " +
                    "They aren't told."
            } else {
                "You'll start receiving their messages again."
            },
            onDismiss = { blockOpen = false },
            buttons = {
                TextButton(onClick = { blockOpen = false }) { Text("Cancel", color = Color.White) }
                TextButton(onClick = { chatRepository.setBlocked(chat.id, blocking); blockOpen = false }) {
                    Text(
                        if (blocking) "Block" else "Unblock",
                        color = if (blocking) MaterialTheme.colorScheme.error else Color.White,
                    )
                }
            },
        )
    }
}

@Composable
private fun SnoozeDialog(
    snoozed: Boolean,
    onDismiss: () -> Unit,
    onPick: (Snooze) -> Unit,
    onClear: () -> Unit,
) {
    AppDialog(
        title = "Snooze chat",
        onDismiss = onDismiss,
        content = {
            // Plain rows rather than radio buttons: picking one closes the dialog and does the
            // thing, so there is never a moment where one of them is the selected option. An
            // unselected radio beside every line would be a control promising a state that does
            // not exist.
            Snooze.entries.forEach { option ->
                Text(
                    option.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPick(option) }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        },
        buttons = {
            if (snoozed) TextButton(onClick = onClear) { Text("Un-snooze", color = Color.White) }
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
        },
    )
}

/** How long is left on a snooze, in the words the row under "Snooze chat" reads back. */
private fun snoozeLabel(until: Long): String {
    val left = until - System.currentTimeMillis()
    if (left <= 0) return "Off"
    val hours = TimeUnit.MILLISECONDS.toHours(left)
    return when {
        hours < 1 -> "Until the hour is up"
        hours < 24 -> "For another ${hours}h"
        else -> "For another ${TimeUnit.MILLISECONDS.toDays(left) + 1} days"
    }
}

private fun chatThemeDescription(theme: ChatThemeKey): String =
    if (theme == ChatThemeKey.DEFAULT) "Follows the app" else "Set for this chat"

/**
 * One row of the panel.
 *
 * A `ListItem` with no container of its own: the panel underneath is the surface, and a row that
 * painted its own would cover the glass the panel is made of. The same shape the group page's
 * options already use, hoisted here now that both pages draw from it.
 */
@Composable
private fun ChatOption(
    icon: ImageVector,
    title: String,
    description: String? = null,
    /** Replaces [description] when the subtitle is more than a line of text. */
    subtitle: (@Composable () -> Unit)? = null,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(title, color = color) },
        supportingContent = subtitle ?: description?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = color) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

/** The subtitle on a switch that no longer follows the app-wide one, with the way back to it. */
@Composable
private fun ManuallySet(onReset: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Manually Set")
        Text(
            "Reset",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onReset),
        )
    }
}

/** The app's switch colours, matching the ones the settings pages use. */
@Composable
private fun ChatSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.secondary,
            checkedThumbColor = MaterialTheme.colorScheme.onSecondary,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
        ),
    )
}
