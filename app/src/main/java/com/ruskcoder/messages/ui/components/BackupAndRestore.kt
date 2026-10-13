package com.ruskcoder.messages.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.backup.BACKUP_MIME
import com.ruskcoder.messages.data.backup.BackupContents
import com.ruskcoder.messages.data.backup.BackupManager
import com.ruskcoder.messages.data.backup.OpenedBackup
import com.ruskcoder.messages.data.backup.RestoreOutcome
import com.ruskcoder.messages.data.backup.suggestedBackupName
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color

/**
 * The whole backup and restore interaction, wrapped around whatever offers it.
 *
 * A composable with a content slot rather than a state holder because both halves are built out of
 * activity-result launchers, which can only be registered from composition — and both entry points
 * that need this (the storage settings page and the very first screen of setup) look nothing alike
 * otherwise. [content] receives the two things to hang off a row or a button, and every dialog,
 * file picker and error along the way is handled here.
 */
@Composable
fun BackupAndRestore(
    manager: BackupManager,
    onRestored: (RestoreOutcome) -> Unit = {},
    content: @Composable (startBackup: () -> Unit, startRestore: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<Stage>(Stage.Idle) }

    // Held between the options dialog and the file picker: the system picker is a separate
    // activity, so what the user chose has to survive leaving this one.
    var pending by remember { mutableStateOf<PendingBackup?>(null) }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME),
    ) { uri: Uri? ->
        val options = pending
        pending = null
        // A null uri is the user backing out of the picker, which is not an error worth reporting.
        if (uri == null || options == null) {
            stage = Stage.Idle
            return@rememberLauncherForActivityResult
        }
        stage = Stage.Busy("Writing the backup…")
        scope.launch {
            stage = runCatching { manager.create(uri, options.includeMessages, options.passphrase) }
                .fold({ Stage.Wrote(it) }, { Stage.Failed(it.messageForUser()) })
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        stage = if (uri == null) Stage.Idle else Stage.Unlocking(uri, error = null)
    }

    content(
        { stage = Stage.Options },
        // Any mime type: a backup is written as `.mbk`, which no picker has a filter for, and some
        // file providers hand it back as `application/octet-stream` while others guess text/plain.
        { openFile.launch(arrayOf("*/*")) },
    )

    when (val current = stage) {
        is Stage.Idle -> Unit

        is Stage.Options -> BackupOptionsDialog(
            onDismiss = { stage = Stage.Idle },
            onConfirm = { includeMessages, passphrase ->
                pending = PendingBackup(includeMessages, passphrase)
                // Dismissed before the picker opens, so returning from it does not land back on a
                // dialog still holding the passphrase that was just used.
                stage = Stage.Idle
                createFile.launch(suggestedBackupName())
            },
        )

        is Stage.Busy -> BusyDialog(current.label)

        is Stage.Wrote -> AppDialog(
            title = "Backup saved",
            message = current.contents.summarize(saved = true),
            onDismiss = { stage = Stage.Idle },
            buttons = { TextButton(onClick = { stage = Stage.Idle }) { Text("Done", color = Color.White) } },
        )

        is Stage.Unlocking -> PassphraseDialog(
            title = "Open this backup",
            detail = "Enter the passphrase this backup was saved with.",
            confirmLabel = "Open",
            error = current.error,
            onDismiss = { stage = Stage.Idle },
            onConfirm = { passphrase ->
                stage = Stage.Busy("Opening the backup…")
                scope.launch {
                    stage = runCatching { manager.open(current.source, passphrase) }
                        .fold(
                            { Stage.Confirm(it) },
                            // Back to the passphrase field rather than a dead end: a mistyped
                            // passphrase is the overwhelmingly likely reason to be here.
                            { Stage.Unlocking(current.source, it.messageForUser()) },
                        )
                }
            },
        )

        is Stage.Confirm -> AppDialog(
            title = "Restore this backup?",
            message = current.opened.contents.summarize(saved = false),
            onDismiss = { stage = Stage.Idle },
            buttons = {
                TextButton(onClick = { stage = Stage.Idle }) { Text("Cancel", color = Color.White) }
                TextButton(onClick = {
                    stage = Stage.Busy("Restoring…")
                    scope.launch {
                        stage = runCatching { manager.restore(current.opened.payload) }
                            .fold({ Stage.Restored(it) }, { Stage.Failed(it.messageForUser()) })
                    }
                }) { Text("Restore", color = Color.White) }
            },
        )

        is Stage.Restored -> {
            // Reported on dismissal rather than the moment the restore lands. The caller's usual
            // response is to move the screen on — setup jumps to confirming the identity it just
            // adopted — and doing that underneath a dialog would tear it away mid-sentence.
            val finish = {
                stage = Stage.Idle
                onRestored(current.outcome)
            }
            AppDialog(
                title = "Restored",
                message = current.outcome.summarize(),
                onDismiss = finish,
                buttons = { TextButton(onClick = finish) { Text("Done", color = Color.White) } },
            )
        }

        is Stage.Failed -> AppDialog(
            title = "That didn't work",
            message = current.message,
            onDismiss = { stage = Stage.Idle },
            buttons = { TextButton(onClick = { stage = Stage.Idle }) { Text("Close", color = Color.White) } },
        )
    }
}

private data class PendingBackup(val includeMessages: Boolean, val passphrase: String)

private sealed interface Stage {
    data object Idle : Stage
    data object Options : Stage
    data class Busy(val label: String) : Stage
    data class Wrote(val contents: BackupContents) : Stage
    data class Unlocking(val source: Uri, val error: String?) : Stage
    data class Confirm(val opened: OpenedBackup) : Stage
    data class Restored(val outcome: RestoreOutcome) : Stage
    data class Failed(val message: String) : Stage
}

/**
 * What goes in, and the passphrase that seals it.
 *
 * Both in one dialog because they are one decision from the user's side, and because the file
 * picker comes next: asking for a passphrase after the file has already been named would mean
 * either writing an unsealed file first or holding a picker result across another prompt.
 */
@Composable
private fun BackupOptionsDialog(onDismiss: () -> Unit, onConfirm: (Boolean, String) -> Unit) {
    var includeMessages by remember { mutableStateOf(true) }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    val mismatch = confirmation.isNotEmpty() && confirmation != passphrase
    val ready = passphrase.length >= MIN_PASSPHRASE && confirmation == passphrase

    AppDialog(
        title = "Create a backup",
        onDismiss = onDismiss,
        content = {
            // Two paragraphs, a toggle and two fields do not fit a dialog on a short screen — and a
            // keyboard covering half of it is exactly when this is being read. The scrolling is
            // [AppDialog]'s now, which is what keeps the buttons under this in reach.
            Column(
                Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Your settings, your Apple Account sign-in and the Mac identity this device " +
                        "registers with are always included, so another device can pick up where " +
                        "this one left off.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Include messages", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Every conversation on this device. Photos and other attachments stay " +
                                "behind — they live outside the backup.",
                            style = MaterialTheme.typography.bodySmall,
                            color = DialogMutedInk,
                        )
                    }
                    Switch(checked = includeMessages, onCheckedChange = { includeMessages = it })
                }
                Text(
                    "The file is encrypted with this passphrase. Anyone holding both the file and " +
                        "the passphrase could sign in as you, and losing the passphrase makes the " +
                        "backup unopenable — there is no way back into it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DialogMutedInk,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Passphrase") },
                    supportingText = { Text("At least $MIN_PASSPHRASE characters") },
                    colors = dialogFieldColors(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Repeat passphrase") },
                    isError = mismatch,
                    supportingText = { if (mismatch) Text("These do not match") },
                    colors = dialogFieldColors(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                )
            }
        },
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
            TextButton(enabled = ready, onClick = { onConfirm(includeMessages, passphrase) }) {
                Text("Choose where to save", color = if (ready) Color.White else DialogMutedInk)
            }
        },
    )
}

@Composable
private fun PassphraseDialog(
    title: String,
    detail: String,
    confirmLabel: String,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }

    AppDialog(
        title = title,
        message = detail,
        onDismiss = onDismiss,
        content = {
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Passphrase") },
                    isError = error != null,
                    supportingText = { if (error != null) Text(error) },
                    colors = dialogFieldColors(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                )
            }
        },
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
            TextButton(enabled = passphrase.isNotEmpty(), onClick = { onConfirm(passphrase) }) {
                Text(confirmLabel, color = if (passphrase.isNotEmpty()) Color.White else DialogMutedInk)
            }
        },
    )
}

/** A dialog with no way out — every operation behind one runs to a terminal stage of its own. */
@Composable
private fun BusyDialog(label: String) {
    AppDialog(
        title = label,
        // No way out and nothing to press: the title carries what is happening, and the spinner
        // under it says it is still happening.
        onDismiss = {},
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
            }
        },
    )
}

/** Counts and dates, worded for the dialog that is about to write or apply this. */
private fun BackupContents.summarize(saved: Boolean): String = buildString {
    if (saved) {
        append("This backup holds ")
    } else {
        append("Saved ${formatBackupDate(createdAt)}. It holds ")
    }
    val parts = buildList {
        if (appleId != null) add("the Apple Account $appleId")
        if (hasHardware) add("the Mac identity this device registers with")
        if (hasSettings) add("your settings")
        if (hasMessages) {
            val chats = if (chatCount == 1) "1 chat" else "$chatCount chats"
            val messages = if (messageCount == 1) "1 message" else "$messageCount messages"
            add("$chats and $messages")
        }
    }
    append(if (parts.isEmpty()) "nothing" else parts.joinToStringWithAnd())
    append(".")
    if (!saved) {
        append(
            "\n\nThe conversations and settings on this device are replaced by what is in the file.",
        )
    }
}

private fun RestoreOutcome.summarize(): String = buildString {
    val parts = buildList {
        if (account) add("your Apple Account")
        if (hardware) add("the Mac identity")
        if (messages) add("your conversations")
        if (settings) add("your settings")
    }
    append(if (parts.isEmpty()) "There was nothing in the file to restore." else "Restored ${parts.joinToStringWithAnd()}.")

    if (failures.isNotEmpty()) {
        append("\n\nCould not restore ${failures.joinToStringWithAnd()}.")
    }
    if (signInRestored) {
        // Not automatic, and worth saying plainly: the restored device registers under its own push
        // token and its own identity, so Apple treats it as hardware it has never seen and verifies
        // the account again before it will deliver anything.
        append(
            "\n\nSign in to finish — Settings ▸ Account ▸ iMessage. Apple has not seen this phone " +
                "before, so it will ask to verify the account once more before messages arrive.",
        )
    }
}

private fun List<String>.joinToStringWithAnd(): String = when (size) {
    0 -> ""
    1 -> first()
    2 -> "${first()} and ${last()}"
    else -> "${dropLast(1).joinToString(", ")} and ${last()}"
}

private fun formatBackupDate(at: Long): String =
    SimpleDateFormat("d MMMM yyyy 'at' HH:mm", Locale.getDefault()).format(Date(at))

/**
 * Anything thrown on these paths, in wording a dialog can show.
 *
 * `BackupException` is already worded for a person; anything else is a file-system or provider
 * failure whose own message is not, so it becomes a sentence with the detail appended rather than
 * a bare `FileNotFoundException` on screen.
 */
private fun Throwable.messageForUser(): String =
    if (this is com.ruskcoder.messages.data.backup.BackupException) {
        message ?: "The backup could not be read."
    } else {
        "The file could not be read or written." + (message?.let { " ($it)" } ?: "")
    }

/** Short enough to type twice, long enough to be worth encrypting with. */
private const val MIN_PASSPHRASE = 8
