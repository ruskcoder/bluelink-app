package com.ruskcoder.messages.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DonutSmall
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.displayInitials
import com.ruskcoder.messages.data.model.displayName
import com.ruskcoder.messages.imessage.IMessageService
import com.ruskcoder.messages.ui.components.Avatar
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow
import androidx.compose.material.icons.outlined.Computer
import uniffi.imessage_core.DeviceDetails

/**
 * Settings home / profile. Ported from `settings/index.tsx`.
 *
 * The RN source has the profile block float over the chat wallpaper with a collapsing header that
 * fades in on scroll, and the options card slides up over it with rounded top corners. That
 * scroll-driven parallax header isn't ported here — this uses the same solid-background app bar
 * every other settings page does — but the profile block, row icons, and subtitles are a 1:1 match.
 */
@Composable
fun SettingsHomeScreen(
    imessageService: IMessageService,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    val settings = LocalSettings.current

    // Read once per visit rather than held in the container: it changes only when setup is run
    // again, and a settings screen is not somewhere a stale name would go unnoticed for long.
    var device by remember { mutableStateOf<DeviceDetails?>(null) }
    LaunchedEffect(Unit) {
        device = runCatching { imessageService.hardware()?.details() }.getOrNull()
    }

    SettingsPage(title = "Settings", onBack = onBack) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Avatar(
                initials = settings.displayInitials(),
                color = MaterialTheme.colorScheme.primaryContainer,
                size = 96.dp,
                photoUri = settings.profilePhotoUri,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(settings.displayName(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        }

        // Above everything else because it answers the question the rest of the app never does:
        // which Mac this install is standing behind. Absent until setup has run.
        device?.let { hardware ->
            SettingsGroup(title = "This device") {
                SettingsRow(
                    icon = if (hardware.relayHost != null) Icons.Outlined.Dns else Icons.Rounded.LaptopMac,
                    title = hardware.name,
                    description = if (hardware.relayHost != null) {
                        "Registered through a relay"
                    } else {
                        "Serial ${hardware.serial} · macOS ${hardware.osVersion}"
                    },
                    chevron = true,
                    onClick = { onNavigate("device") },
                )
            }
        }

        SettingsGroup(title = "iMessage") {
            SettingsRow(
                icon = Icons.Outlined.Wifi,
                title = "Connection",
                chevron = true,
                onClick = { onNavigate("connection") },
            )
        }
        SettingsGroup(title = "Personalize") {
            SettingsRow(icon = Icons.Outlined.Palette, title = "Appearance", chevron = true, onClick = { onNavigate("appearance") })
            SettingsRow(icon = Icons.AutoMirrored.Outlined.Message, title = "Chats", chevron = true, onClick = { onNavigate("chats") })
            SettingsRow(icon = Icons.Outlined.NotificationsNone, title = "Notifications", chevron = true, onClick = { onNavigate("notifications") })
            SettingsRow(icon = Icons.Outlined.RecordVoiceOver, title = "Transcription", chevron = true, onClick = { onNavigate("transcription") })
            SettingsRow(icon = Icons.Outlined.Computer, title = "Web", chevron = true, onClick = { onNavigate("web") })
        }
        SettingsGroup(title = "Privacy and access") {
            SettingsRow(icon = Icons.Outlined.Lock, title = "Privacy", chevron = true, onClick = { onNavigate("privacy") })
            SettingsRow(icon = Icons.Outlined.Shield, title = "Permissions", chevron = true, onClick = { onNavigate("permissions") })
            SettingsRow(icon = Icons.Outlined.VpnKey, title = "Account", chevron = true, onClick = { onNavigate("account") })
        }
        SettingsGroup(title = "Data") {
            SettingsRow(icon = Icons.Outlined.Groups, title = "Lists", chevron = true, onClick = { onNavigate("lists") })
            SettingsRow(icon = Icons.Outlined.DonutSmall, title = "Storage and data", chevron = true, onClick = { onNavigate("storage") })
        }
        SettingsGroup(title = "About") {
            SettingsRow(icon = Icons.AutoMirrored.Outlined.HelpOutline, title = "Help", chevron = true, onClick = { onNavigate("help") })
            SettingsRow(
                icon = Icons.Outlined.PersonAdd,
                title = "Invite a friend",
                chevron = true,
                onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "Try Messages: https://github.com/ruskcoder/messages")
                    }
                    context.startActivity(Intent.createChooser(share, "Invite a friend"))
                },
            )
        }
    }
}
