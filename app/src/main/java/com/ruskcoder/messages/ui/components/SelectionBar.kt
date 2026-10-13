package com.ruskcoder.messages.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The app-bar transformation for multi-select mode. Ported from `selection-bar.tsx`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(
    count: Int,
    canEdit: Boolean,
    canReply: Boolean,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    /** Whether anything selected has a file behind it to save to the phone. */
    canSave: Boolean = false,
    /** Saves every photo, video, voice message and file in the selection. */
    onSave: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    StyledTopAppBar(
        title = { Text("$count", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Cancel selection") } },
        actions = {
            IconButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy") }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Delete") }
            IconButton(onClick = onEdit, enabled = canEdit) { Icon(Icons.Rounded.Edit, contentDescription = "Edit") }
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
            AppDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (canReply) {
                    AppMenuItem(
                        text = { Text("Reply") },
                        trailingIcon = { Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null) },
                        onClick = { menuOpen = false; onReply() },
                    )
                }
                AppMenuItem(
                    text = { Text("Forward") },
                    trailingIcon = { Icon(Icons.AutoMirrored.Rounded.Forward, contentDescription = null) },
                    onClick = { menuOpen = false; onForward() },
                )
                if (canSave) {
                    AppMenuItem(
                        text = { Text("Save all to phone") },
                        trailingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                        onClick = { menuOpen = false; onSave() },
                    )
                }
                AppMenuDivider()
                AppMenuItem(
                    text = { Text("Cancel selection") },
                    trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) },
                    onClick = { menuOpen = false; onClose() },
                )
            }
        },
    )
}

/**
 * The app bar while one message's text is being highlighted.
 *
 * The copy button lives up here rather than in a floating bubble beside the selection because the
 * message it belongs to has been lifted out of the conversation and is being held under a blur:
 * a second floating object over that would be one layer too many, and the bar is the one piece of
 * chrome that stays put and stays legible while the overlay is up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSelectionBar(canCopy: Boolean, onClose: () -> Unit, onCopy: () -> Unit) {
    StyledTopAppBar(
        title = { Text("Select text", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Cancel selection") } },
        actions = {
            // Disabled rather than hidden: the button is the whole point of the mode, and having it
            // come and go as you drag the handles would be worse than having it sit there greyed.
            IconButton(onClick = onCopy, enabled = canCopy) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy selected text")
            }
        },
    )
}
