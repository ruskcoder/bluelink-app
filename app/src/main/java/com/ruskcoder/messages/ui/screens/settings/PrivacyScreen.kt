package com.ruskcoder.messages.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ruskcoder.messages.MessagesApp
import com.ruskcoder.messages.security.AppLock
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.data.model.MessageTimer
import com.ruskcoder.messages.data.model.Visibility
import com.ruskcoder.messages.ui.components.settings.SettingsChoice
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow
import com.ruskcoder.messages.ui.components.settings.SettingsToggle

/** The lock timeouts offered, in seconds. */
private val LockTimeouts = listOf(0, 60, 5 * 60, 30 * 60)

private fun lockTimeoutLabel(seconds: Int): String = when (seconds) {
    0 -> "Immediately"
    60 -> "After 1 minute"
    else -> "After ${seconds / 60} minutes"
}

/** Ported from `settings/privacy.tsx`. */
@Composable
fun PrivacyScreen(settingsRepository: SettingsRepository, onOpenPermissions: () -> Unit, onBack: () -> Unit) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()

    SettingsPage(title = "Privacy", onBack = onBack) {
        SettingsGroup(
            title = "Who can see my personal info",
        ) {
            SettingsChoice(
                icon = Icons.Outlined.Schedule,
                title = "Last seen and online",
                options = Visibility.entries,
                selected = settings.lastSeenVisibility,
                label = { it.label },
                onSelect = { v -> settingsRepository.update { it.copy(lastSeenVisibility = v) } },
            )
            SettingsChoice(
                icon = Icons.Outlined.AccountCircle,
                title = "Profile photo",
                options = Visibility.entries,
                selected = settings.profilePhotoVisibility,
                label = { it.label },
                onSelect = { v -> settingsRepository.update { it.copy(profilePhotoVisibility = v) } },
            )
            SettingsChoice(
                icon = Icons.Outlined.Info,
                title = "About",
                options = Visibility.entries,
                selected = settings.aboutVisibility,
                label = { it.label },
                onSelect = { v -> settingsRepository.update { it.copy(aboutVisibility = v) } },
            )
        }

        SettingsGroup(
            title = "Messaging",
        ) {
            // The sender fetches a link's card; turning this off means this phone never contacts a
            // site just because a link was typed. Cards on received messages still draw.
            SettingsToggle(
                icon = Icons.Outlined.Link,
                title = "Send link previews",
                checked = settings.linkPreviews,
                onCheckedChange = { v -> settingsRepository.update { it.copy(linkPreviews = v) } },
            )
            SettingsChoice(
                icon = Icons.Outlined.HourglassEmpty,
                title = "Default message timer",
                options = MessageTimer.entries,
                selected = settings.messageTimer,
                label = { it.label },
                onSelect = { v -> settingsRepository.update { it.copy(messageTimer = v) } },
            )
        }

        SettingsGroup(title = "App lock") {
            val context = LocalContext.current
            // Offered only where there is something to unlock with. A lock that could not be opened
            // would lock somebody out of their own messages.
            val canLock = remember { AppLock.canAuthenticate(context) }
            SettingsToggle(
                icon = Icons.Outlined.Fingerprint,
                title = if (canLock) "Require unlock" else "Require unlock (set a screen lock first)",
                checked = settings.appLock && canLock,
                enabled = canLock,
                onCheckedChange = { v ->
                    if (v) (context.applicationContext as MessagesApp).container.appLock.onLockEnabled()
                    settingsRepository.update { it.copy(appLock = v) }
                },
            )
            SettingsChoice(
                icon = Icons.Outlined.Timer,
                title = "Lock after",
                options = LockTimeouts,
                selected = settings.appLockTimeoutSeconds.takeIf { it in LockTimeouts } ?: 0,
                label = ::lockTimeoutLabel,
                enabled = settings.appLock && canLock,
                onSelect = { v -> settingsRepository.update { it.copy(appLockTimeoutSeconds = v) } },
            )
            SettingsToggle(
                icon = Icons.Outlined.VisibilityOff,
                title = "Hide message text in notifications",
                checked = settings.hideNotificationContent,
                onCheckedChange = { v -> settingsRepository.update { it.copy(hideNotificationContent = v) } },
            )
        }

        SettingsGroup(title = "Device access") {
            SettingsRow(
                icon = Icons.Outlined.Shield,
                title = "App permissions",
                chevron = true,
                onClick = onOpenPermissions,
            )
        }

        // No chevron and no tap: blocking isn't implemented, and a row that opens nothing is worse
        // than a row that plainly reads as a status line.
        SettingsGroup(title = "Blocking") {
            SettingsRow(icon = Icons.Outlined.Block, title = "Blocked contacts", description = "None")
        }
    }
}
