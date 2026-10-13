package com.ruskcoder.messages.ui.screens.settings

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import com.ruskcoder.messages.ui.components.EmojiPickerSheet
import com.ruskcoder.messages.data.model.quickReactionEmoji
import com.ruskcoder.messages.data.model.EMPTY_REACTION_SLOT
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.SwipeLeft
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Vibration
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.data.model.DoubleTapAction
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.ui.components.settings.SettingsBlock
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow
import com.ruskcoder.messages.ui.components.settings.SettingsSegmented
import com.ruskcoder.messages.ui.components.settings.SettingsToggle
import com.ruskcoder.messages.ui.components.AppDialog
import androidx.compose.material.icons.outlined.Download


/** Ported from `settings/chats.tsx`. */
@OptIn(ExperimentalLayoutApi::class)
/** The editor's slots, drawn at the size the real bar draws them. */
private val EditorSlot = 44.dp

/**
 * The reaction bar, laid out flat and editable.
 *
 * Deliberately the *shape* of the real thing and not the thing itself: full width, the same slot
 * size, the same glyph size — but plain buttons rather than the sliding, growing row a long press
 * opens. That row's gesture is one continuous drag that picks whatever you lift off, which is
 * exactly wrong for choosing what goes *in* a slot, and its hover growth would fight the sheet
 * opening on top of it. There is no "+" at the end either: this bar is six slots and the palette
 * behind that button is what each slot opens anyway.
 *
 * An emptied slot is a dotted ring rather than a gap, because the arrangement is fixed-length —
 * see [EMPTY_REACTION_SLOT]. The gap only closes where the bar is actually used.
 */
@Composable
private fun ReactionBarEditor(slots: List<String>, onChange: (List<String>) -> Unit) {
    var editing by remember { mutableStateOf<Int?>(null) }
    val outline = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        slots.forEachIndexed { index, glyph ->
            Box(
                modifier = Modifier
                    .size(EditorSlot)
                    .clip(CircleShape)
                    .clickable { editing = index },
                contentAlignment = Alignment.Center,
            ) {
                if (glyph.isBlank()) {
                    Box(
                        modifier = Modifier
                            .size(EditorSlot - 8.dp)
                            .dashedRing(outline.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Empty slot",
                            tint = outline,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Text(glyph, fontSize = 26.sp)
                }
            }
        }
    }

    editing?.let { index ->
        EmojiPickerSheet(
            active = slots.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { listOf(it) },
            onClear = { onChange(slots.mapIndexed { i, old -> if (i == index) EMPTY_REACTION_SLOT else old }) },
            onDismiss = { editing = null },
            onPick = { picked ->
                editing = null
                onChange(slots.mapIndexed { i, old -> if (i == index) picked else old })
            },
        )
    }
}

/** A dotted ring, for a slot with nothing in it. */
private fun Modifier.dashedRing(color: Color): Modifier = drawBehind {
    drawCircle(
        color = color,
        radius = size.minDimension / 2 - 1.dp.toPx(),
        style = Stroke(
            width = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
        ),
    )
}

@Composable
fun ChatsSettingsScreen(settingsRepository: SettingsRepository, chatRepository: ChatRepository, onOpenAppearance: () -> Unit, onBack: () -> Unit) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val chats by chatRepository.chats.collectAsStateWithLifecycle()
    var showClearConfirm by remember { mutableStateOf(false) }
    val chatCount = if (chats.size == 1) "1 chat" else "${chats.size} chats"

    SettingsPage(title = "Chats", onBack = onBack) {
        // The app-wide defaults. A conversation follows them until its own switch is touched on
        // its details page, which pins that conversation and leaves these unable to reach it.
        SettingsGroup(title = "Messaging") {
            SettingsToggle(
                icon = Icons.Filled.DoneAll,
                title = "Read receipts",
                checked = settings.readReceipts,
                onCheckedChange = { v -> settingsRepository.update { it.copy(readReceipts = v) } },
            )
            SettingsToggle(
                icon = Icons.Outlined.Keyboard,
                title = "Typing indicators",
                checked = settings.typingIndicators,
                onCheckedChange = { v -> settingsRepository.update { it.copy(typingIndicators = v) } },
            )
        }

        SettingsGroup(
            title = "Reactions",
        ) {
            SettingsBlock(
                title = "Reaction bar",
            ) {
                ReactionBarEditor(
                    slots = settings.reactionBar,
                    onChange = { next -> settingsRepository.update { it.copy(reactionBar = next) } },
                )
            }
            SettingsBlock(title = "Double tap a message") {
                SettingsSegmented(
                    options = listOf(DoubleTapAction.REACTIONS, DoubleTapAction.QUICK, DoubleTapAction.NONE),
                    selected = settings.doubleTapAction,
                    label = { when (it) { DoubleTapAction.REACTIONS -> "Menu"; DoubleTapAction.QUICK -> "Quick: ${settings.quickReactionEmoji()}"; DoubleTapAction.NONE -> "Off" } },
                    onSelect = { value -> settingsRepository.update { it.copy(doubleTapAction = value) } },
                )
            }
        }

        SettingsGroup(
            title = "Gestures",
        ) {
            SettingsToggle(
                icon = Icons.Outlined.SwipeLeft,
                title = "Swipe left for timestamps",
                checked = settings.swipeTimestamps,
                onCheckedChange = { v -> settingsRepository.update { it.copy(swipeTimestamps = v) } },
            )
            SettingsToggle(
                icon = Icons.Outlined.TouchApp,
                title = "Long press menu",
                checked = settings.longPressMenu,
                onCheckedChange = { v -> settingsRepository.update { it.copy(longPressMenu = v) } },
            )
            SettingsToggle(
                icon = Icons.Outlined.Vibration,
                title = "Haptic feedback",
                checked = settings.haptics,
                onCheckedChange = { v -> settingsRepository.update { it.copy(haptics = v) } },
            )
            SettingsToggle(
                icon = Icons.Outlined.Download,
                title = "Save media to phone",
                checked = settings.autoSaveMedia,
                onCheckedChange = { v -> settingsRepository.update { it.copy(autoSaveMedia = v) } },
            )
        }

        SettingsGroup(title = "Composer") {
            SettingsToggle(
                icon = Icons.AutoMirrored.Outlined.KeyboardReturn,
                title = "Enter key sends",
                checked = settings.enterToSend,
                onCheckedChange = { v -> settingsRepository.update { it.copy(enterToSend = v) } },
            )
        }

        SettingsGroup(
            title = "Chat history",
        ) {
            SettingsRow(
                icon = Icons.Filled.DeleteSweep,
                title = "Clear all chats",
                destructive = true,
                onClick = { showClearConfirm = true },
            )
        }
    }

    if (showClearConfirm) {
        AppDialog(
            title = "Clear all chats?",
            message = "Every conversation and message on this device is deleted. This can't be undone.",
            onDismiss = { showClearConfirm = false },
            buttons = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel", color = Color.White) }
                TextButton(onClick = { chatRepository.clearChats(); showClearConfirm = false }) {
                    Text("Clear chats", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}
