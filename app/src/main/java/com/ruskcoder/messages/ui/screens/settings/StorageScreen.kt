package com.ruskcoder.messages.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DonutSmall
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.ruskcoder.messages.data.backup.BackupManager
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.data.model.MediaAutoDownload
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.ui.components.BackupAndRestore
import com.ruskcoder.messages.ui.components.settings.SettingsChoice
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow
import androidx.compose.ui.graphics.Color
import com.ruskcoder.messages.ui.components.AppDialog

/** Bytes as the one- or two-digit figure a settings row wants, not an exact count. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes bytes"
}

/** Ported from `settings/storage.tsx`. */
@Composable
fun StorageScreen(
    settingsRepository: SettingsRepository,
    chatRepository: ChatRepository,
    backupManager: BackupManager,
    onBack: () -> Unit,
) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val chats by chatRepository.chats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showClearDataConfirm by remember { mutableStateOf(false) }
    val unread = chats.count { it.unread > 0 }
    val chatCount = if (chats.size == 1) "1 chat" else "${chats.size} chats"
    val manageDescription = chatCount + " on this device" + if (unread > 0) ", $unread unread" else ""
    // Re-read whenever the chat list changes, which is the only thing that grows the file.
    val databaseSize = remember(chats) {
        val bytes = context.getDatabasePath("messages.db").let { db ->
            // WAL and shared-memory files are part of what the database occupies.
            listOf(db, java.io.File("${db.path}-wal"), java.io.File("${db.path}-shm")).sumOf { if (it.exists()) it.length() else 0L }
        }
        formatBytes(bytes)
    }

    BackupAndRestore(manager = backupManager) { startBackup, startRestore ->
        SettingsPage(title = "Storage and data", onBack = onBack) {
            SettingsGroup(title = "Storage") {
                SettingsRow(icon = Icons.Outlined.DonutSmall, title = "Manage storage", description = manageDescription)
                SettingsRow(icon = Icons.Outlined.Storage, title = "On this device", description = "$databaseSize of conversation data")
                SettingsChoice(
                    icon = Icons.Outlined.CloudDownload,
                    title = "Media auto-download",
                    options = MediaAutoDownload.entries,
                    selected = settings.mediaAutoDownload,
                    label = { it.label },
                    onSelect = { v -> settingsRepository.update { it.copy(mediaAutoDownload = v) } },
                )
            }
            SettingsGroup(
                title = "Backup and restore",
            ) {
                SettingsRow(
                    icon = Icons.Outlined.Backup,
                    title = "Create a backup",
                    onClick = startBackup,
                )
                SettingsRow(
                    icon = Icons.Outlined.SettingsBackupRestore,
                    title = "Restore from a backup",
                    onClick = startRestore,
                )
            }
            SettingsGroup(
                title = "Reset",
            ) {
                SettingsRow(
                    icon = Icons.Outlined.DeleteOutline,
                    title = "Clear data",
                    destructive = true,
                    onClick = { showClearDataConfirm = true },
                )
            }
        }

        if (showClearDataConfirm) {
            AppDialog(
                title = "Clear all data?",
                message = "Every setting goes back to its default and all chats on this device are deleted. This can't be undone.",
                onDismiss = { showClearDataConfirm = false },
                buttons = {
                    TextButton(onClick = { showClearDataConfirm = false }) { Text("Cancel", color = Color.White) }
                    TextButton(onClick = {
                        showClearDataConfirm = false
                        settingsRepository.reset()
                        chatRepository.clearChats()
                    }) { Text("Clear data", color = MaterialTheme.colorScheme.error) }
                },
            )
        }
    }
}
