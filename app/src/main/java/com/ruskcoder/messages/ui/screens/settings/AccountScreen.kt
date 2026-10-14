package com.ruskcoder.messages.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.data.model.USER_NAME
import com.ruskcoder.messages.data.model.displayInitials
import com.ruskcoder.messages.data.model.displayName
import com.ruskcoder.messages.imessage.IMessageService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ruskcoder.messages.ui.components.Avatar
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow
import com.ruskcoder.messages.ui.components.settings.SettingsToggle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.graphics.Color
import com.ruskcoder.messages.ui.components.AppDialog
import com.ruskcoder.messages.ui.components.dialogFieldColors

/**
 * Ported from `settings/account.tsx`, where the whole page was cosmetic. The two switches now hold
 * a real stored preference.
 */
@Composable
fun AccountScreen(
    settingsRepository: SettingsRepository,
    imessageService: IMessageService,
    onOpenSetup: () -> Unit,
    onBack: () -> Unit,
) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            // Persisted, or the uri stops resolving the next time the process starts and the avatar
            // silently reverts to initials.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            settingsRepository.update { it.copy(profilePhotoUri = uri.toString()) }
        }
    }
    // The gallery app, for devices with no system photo picker: `PickVisualMedia` falls back to the
    // files browser there, which is not where anyone keeps their photographs. A gallery pick is
    // only readable for as long as this process lives, so the picture is copied in rather than
    // remembered by address.
    val pickFromGallery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            val copy = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(context.filesDir, "profile_photo_${System.currentTimeMillis()}")
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    // Named by time so the avatar reloads; the ones before it are of no use now.
                    context.filesDir.listFiles { file -> file.name.startsWith("profile_photo_") && file != target }
                        ?.forEach { it.delete() }
                    Uri.fromFile(target).toString()
                }.getOrNull()
            }
            if (copy != null) settingsRepository.update { it.copy(profilePhotoUri = copy) }
        }
    }
    fun choosePhoto() {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            return
        }
        val launched = runCatching {
            pickFromGallery.launch(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        }.isSuccess
        // No gallery app to answer it: `GET_CONTENT` without `CATEGORY_OPENABLE`, so whatever
        // gallery there is gets offered alongside the files browser.
        if (!launched) runCatching { pickFromGallery.launch(Intent(Intent.ACTION_GET_CONTENT).setType("image/*")) }
    }

    // Read once on entry rather than observed: this is disk state that only changes by going
    // through setup, which leaves this screen to do.
    var appleId by remember { mutableStateOf<String?>(null) }
    var registered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        registered = runCatching { imessageService.isRegistered() }.getOrDefault(false)
        appleId = runCatching { imessageService.savedAppleId() }.getOrNull()
    }

    SettingsPage(title = "Account", onBack = onBack) {
        SettingsGroup(
            title = "You",
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { choosePhoto() }
                    .padding(16.dp),
            ) {
                Avatar(
                    initials = settings.displayInitials(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    size = 64.dp,
                    photoUri = settings.profilePhotoUri,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(settings.displayName(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (settings.profilePhotoUri == null) "Tap to add a photo" else "Tap to change your photo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SettingsRow(
                icon = Icons.Outlined.Badge,
                title = "Name",
                description = settings.displayName(),
                onClick = { nameDraft = settings.displayName(); editingName = true },
            )
            if (settings.profilePhotoUri != null) {
                SettingsRow(
                    icon = Icons.Outlined.HideImage,
                    title = "Remove photo",
                    destructive = true,
                    onClick = { settingsRepository.update { it.copy(profilePhotoUri = null) } },
                )
            }
        }

        SettingsGroup(
            title = "iMessage",
        ) {
            SettingsRow(
                icon = Icons.Outlined.Message,
                title = if (registered) "Connected" else "Not connected",
                description = appleId ?: if (registered) null else "Tap to sign in",
                onClick = onOpenSetup,
            )
        }

        SettingsGroup(title = "Security") {
            SettingsToggle(
                icon = Icons.Outlined.GppGood,
                title = "Security notifications",
                checked = settings.securityNotifications,
                onCheckedChange = { v -> settingsRepository.update { it.copy(securityNotifications = v) } },
            )
            SettingsToggle(
                icon = Icons.Outlined.Password,
                title = "Two-step verification",
                checked = settings.twoStepVerification,
                onCheckedChange = { v -> settingsRepository.update { it.copy(twoStepVerification = v) } },
            )
        }
    }

    if (editingName) {
        AppDialog(
            title = "Your name",
            onDismiss = { editingName = false },
            content = {
                // Outlined rather than filled now that it sits on glass: a filled field brings a
                // container of its own, and a second surface inside a frosted panel is one too many.
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    placeholder = { Text(USER_NAME) },
                    colors = dialogFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
            },
            buttons = {
                TextButton(onClick = { editingName = false }) { Text("Cancel", color = Color.White) }
                TextButton(onClick = {
                    settingsRepository.update { it.copy(profileName = nameDraft.trim()) }
                    editingName = false
                }) { Text("Save", color = Color.White) }
            },
        )
    }
}
