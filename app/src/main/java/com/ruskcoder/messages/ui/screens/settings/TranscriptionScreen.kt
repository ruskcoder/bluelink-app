package com.ruskcoder.messages.ui.screens.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.transcribe.ModelDownload
import com.ruskcoder.messages.transcribe.SpeechModel
import com.ruskcoder.messages.transcribe.SpeechModelStore
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow

/**
 * Voice-message transcription: which model, whether it is here, and how it got here.
 *
 * The whole page exists because the model is a download rather than part of the app. That is the
 * trade it is asking you to make — tens of megabytes of storage against being able to read a voice
 * note — and a setting is the honest place to put a decision of that size, rather than spending it
 * on everybody's behalf at install time.
 */
@Composable
fun TranscriptionScreen(
    settingsRepository: SettingsRepository,
    models: SpeechModelStore,
    onBack: () -> Unit,
) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val download by models.state.collectAsStateWithLifecycle()
    val installed by models.installed.collectAsStateWithLifecycle()

    SettingsPage(title = "Transcription", onBack = onBack) {
        SettingsGroup(
            title = "Speech model",
        ) {
            SpeechModel.entries.forEach { model ->
                ModelRow(
                    model = model,
                    selected = model == settings.speechModel,
                    installed = model in installed,
                    download = download as? ModelDownload.Running,
                    failure = (download as? ModelDownload.Failed)?.takeIf { it.model == model },
                    busy = download is ModelDownload.Running,
                    onSelect = { settingsRepository.update { it.copy(speechModel = model) } },
                    onDownload = {
                        settingsRepository.update { it.copy(speechModel = model) }
                        models.download(model, requireUnmetered = false)
                    },
                    onCancel = models::cancel,
                    onDelete = { models.delete(model) },
                )
            }
        }

        if (installed.isNotEmpty()) {
            SettingsGroup(title = "Storage") {
                SettingsRow(
                    title = "Downloaded models",
                    description = "${installed.size} of ${SpeechModel.entries.size}, " +
                        "${models.installedBytes() / 1_048_576} MB on this device",
                )
            }
        }
    }
}

/**
 * One model: what it is, whether it is here, and the one thing you can do to it right now.
 *
 * Selecting and downloading are the same gesture where the model is not installed yet — nobody
 * picks a model they have not got in order to leave it that way — so the row offers whichever of
 * the two is the useful one rather than both.
 */
@Composable
private fun ModelRow(
    model: SpeechModel,
    selected: Boolean,
    installed: Boolean,
    download: ModelDownload.Running?,
    failure: ModelDownload.Failed?,
    busy: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val downloading = download?.model == model

    Column(modifier = Modifier.animateContentSize()) {
        SettingsRow(
            title = model.label,
            description = "${model.megabytes} MB",
            icon = when {
                installed && selected -> Icons.Outlined.Check
                model == SpeechModel.SMALL -> Icons.Outlined.Bolt
                else -> Icons.Outlined.CloudDownload
            },
            // Only an installed model can be chosen; the rest offer their download instead.
            onClick = if (installed && !selected) onSelect else null,
            trailing = {
                when {
                    downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${(download.fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected) {
                            Text(
                                "In use",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        TextButton(onClick = onDelete) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                        }
                    }
                    // Greyed rather than hidden while another model downloads: two at once would
                    // compete for the same connection, and the row should say why it will not start.
                    else -> TextButton(onClick = onDownload, enabled = !busy) { Text("Download") }
                }
            },
        )

        if (downloading) {
            LinearProgressIndicator(
                progress = { download.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(3.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        failure?.let {
            Text(
                it.reason,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}
