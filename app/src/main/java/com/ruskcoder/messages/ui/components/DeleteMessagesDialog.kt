package com.ruskcoder.messages.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Ported from `delete-messages-dialog.tsx`. */
@Composable
fun DeleteMessagesDialog(
    canDeleteForEveryone: Boolean,
    onDismiss: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
) {
    AppDialog(
        title = "Delete messages?",
        message = "This can't be undone.",
        onDismiss = onDismiss,
        // Cancel leads and the two deletions follow, so the way out of the dialog is never the
        // button beside the one that removes something.
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
            if (canDeleteForEveryone) {
                TextButton(onClick = onDeleteForEveryone) {
                    Text("Delete for everyone", color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onDeleteForMe) {
                Text("Delete for me", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
