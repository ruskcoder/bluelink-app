package com.ruskcoder.messages.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * What the badge beside an undelivered message opens: the reason it gave, and the two things that
 * can be done about it.
 *
 * The reason is shown verbatim rather than translated into something friendlier. It comes from
 * Apple or from the network stack, and a message that will not send is a case where the exact words
 * are the only lead anyone has — "friendly" here would mean throwing away the one useful thing.
 * When there is no reason at all the dialog says so plainly instead of leaving an empty body.
 *
 * Retry sits in the confirm slot and Delete beside it, both away from the dismiss slot, so backing
 * out of the dialog is never the button next to the one that removes the message. Retry is absent
 * rather than disabled for a message that cannot be re-sent — see `canRetrySend`.
 */
@Composable
fun SendFailureDialog(
    reason: String,
    /** False for a message with nothing left to re-send, which is offered only Delete. */
    canRetry: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    AppDialog(
        title = "Not delivered",
        message = reason.takeIf { it.isNotBlank() && !it.equals("Not Delivered", ignoreCase = true) }
            ?: "This message could not be sent. No reason was given.",
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
            if (canRetry) TextButton(onClick = onRetry) { Text("Try again", color = Color.White) }
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
