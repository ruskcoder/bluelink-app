package com.ruskcoder.messages.ui.screens.settings

import android.content.Intent
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
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Download
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
import com.ruskcoder.messages.imessage.PhoneRegistration
import kotlinx.coroutines.launch
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
 * a real stored preference; the rows below them belong to an account service this app doesn't have,
 * so they say so when tapped rather than looking like buttons that swallowed the press.
 */
@Composable
fun AccountScreen(
    settingsRepository: SettingsRepository,
    imessageService: IMessageService,
    onOpenSetup: () -> Unit,
    onBack: () -> Unit,
) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    var unavailable by remember { mutableStateOf<String?>(null) }
    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf("") }

    val context = LocalContext.current
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

    // Read once on entry rather than observed: this is disk state that only changes by going
    // through setup, which leaves this screen to do.
    var appleId by remember { mutableStateOf<String?>(null) }
    var registered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        registered = runCatching { imessageService.isRegistered() }.getOrDefault(false)
        appleId = runCatching { imessageService.savedAppleId() }.getOrNull()
    }

    // The number registered with iMessage, read from the registration itself — so the switch
    // follows what Apple has, not what was last asked for.
    val scope = rememberCoroutineScope()
    var phoneNumbers by remember { mutableStateOf<List<String>>(emptyList()) }
    var phoneBusy by remember { mutableStateOf(false) }
    var phoneError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(registered) {
        if (registered) phoneNumbers = PhoneRegistration.registeredNumbers(imessageService)
    }
    fun setPhone(on: Boolean) {
        phoneBusy = true
        scope.launch {
            runCatching {
                if (on) PhoneRegistration.enable(context, imessageService) else PhoneRegistration.disable(context, imessageService)
            }.onFailure { phoneError = it.message ?: "Something went wrong" }
            phoneNumbers = PhoneRegistration.registeredNumbers(imessageService)
            phoneBusy = false
        }
    }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (PhoneRegistration.hasPermissions(context)) setPhone(true)
        else phoneError = "Verifying your number needs permission to send and receive texts."
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
                    .clickable {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
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
            if (registered) {
                SettingsToggle(
                    icon = Icons.Filled.Phone,
                    title = "Use phone number",
                    description = when {
                        phoneBusy && phoneNumbers.isEmpty() -> "Verifying with Apple…"
                        phoneBusy -> "Removing…"
                        phoneNumbers.isNotEmpty() -> "${phoneNumbers.joinToString { it.removePrefix("tel:") }} · iPhones text it as iMessage"
                        else -> "Let iPhones reach you by your number, not just your email"
                    },
                    checked = phoneNumbers.isNotEmpty(),
                    enabled = !phoneBusy,
                    onCheckedChange = { on ->
                        when {
                            !on -> setPhone(false)
                            PhoneRegistration.canEnable(context) -> setPhone(true)
                            else -> smsPermission.launch(PhoneRegistration.permissions)
                        }
                    },
                )
            }
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
            SettingsRow(
                icon = Icons.Filled.Devices,
                title = "Linked devices",
                description = "No devices linked",
                onClick = { unavailable = "Linking a second device needs an account service, which this app doesn't have — everything lives on this phone." },
            )
        }
        SettingsGroup(title = "Account") {
            SettingsRow(
                icon = Icons.Filled.Phone,
                title = "Change number",
                onClick = { unavailable = "There's no phone number registered to change — this build isn't backed by an account." },
            )
            SettingsRow(
                icon = Icons.Outlined.Download,
                title = "Request account info",
                onClick = { unavailable = "Everything this app holds is already on this device. Storage and data shows how much of it there is." },
            )
            SettingsRow(
                icon = Icons.Filled.PersonRemove,
                title = "Delete account",
                destructive = true,
                onClick = { unavailable = "There's no account to delete. To wipe what's on this phone, use Clear data under Storage and data." },
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
    phoneError?.let { message ->
        AppDialog(
            title = "Phone number",
            message = message,
            onDismiss = { phoneError = null },
            buttons = { TextButton(onClick = { phoneError = null }) { Text("OK", color = Color.White) } },
        )
    }
    unavailable?.let { message ->
        AppDialog(
            title = "Not in this build",
            message = message,
            onDismiss = { unavailable = null },
            buttons = { TextButton(onClick = { unavailable = null }) { Text("Got it", color = Color.White) } },
        )
    }
}
