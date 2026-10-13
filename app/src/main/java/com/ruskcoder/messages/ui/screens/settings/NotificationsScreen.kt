package com.ruskcoder.messages.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.permissions.rememberBatteryExemption
import com.ruskcoder.messages.permissions.rememberPermissionState
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow
import com.ruskcoder.messages.ui.components.settings.SettingsToggle

/**
 * Ported from `settings/notifications.tsx`.
 *
 * Tones, vibration patterns and ringtones are the system's to own on Android — an app that drew its
 * own pickers for them would be storing preferences the platform then ignores. So those rows hand
 * off to the real notification settings for this app instead of pretending to hold the value.
 */
@Composable
fun NotificationsScreen(settingsRepository: SettingsRepository, onBack: () -> Unit) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val postNotifications = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    val battery = rememberBatteryExemption()
    // Below API 33 there is no runtime permission — notifications are allowed unless the user has
    // switched them off in system settings, which the row below opens.
    val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || postNotifications.granted
    val enabled = settings.notifications && permitted
    val toneOrOff = if (enabled) "Default" else "Off"

    fun openSystemNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Some OEM builds ship without the per-app notification screen; app info always exists.
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.recoverCatching { context.startActivity(fallback) }
    }

    SettingsPage(title = "Notifications", onBack = onBack) {
        SettingsGroup(
            title = "All notifications",
        ) {
            SettingsToggle(
                icon = Icons.Outlined.NotificationsNone,
                title = "Notifications",
                checked = enabled,
                onCheckedChange = { value ->
                    // Turning them on has to clear Android's own gate first; the app preference is
                    // only meaningful once the system has said yes.
                    if (value && !permitted) postNotifications.request()
                    settingsRepository.update { it.copy(notifications = value) }
                },
            )
            if (!permitted) {
                SettingsRow(
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    title = "Allow notifications",
                    chevron = true,
                    onClick = { openSystemNotificationSettings() },
                )
            }
        }

        // Permanent, and shown in both states rather than only when it is missing.
        //
        // Being able to see that it is *on* is the point of keeping the row: the exemption is
        // cleared by an uninstall and reinstall, and nothing about the app looks any different
        // afterwards — the service still runs, the socket still opens, and messages simply stop
        // being timely. A row that disappeared once granted would leave nowhere to check.
        SettingsGroup(
            title = "Background delivery",
        ) {
            if (battery.exempt) {
                SettingsRow(
                    icon = Icons.Outlined.CheckCircle,
                    title = "Background delivery",
                    chevron = false,
                    onClick = { battery.request() },
                )
            } else {
                SettingsRow(
                    icon = Icons.Outlined.BatteryAlert,
                    title = "Allow background delivery",
                    chevron = true,
                    onClick = { battery.request() },
                )
            }
        }

        // The per-category rows read as unavailable while the master switch is off.
        SettingsGroup(
            title = "Messages",
        ) {
            SettingsRow(
                icon = Icons.Outlined.MusicNote,
                title = "Notification tone",
                description = toneOrOff,
                chevron = true,
                enabled = enabled,
                onClick = { openSystemNotificationSettings() },
            )
            SettingsRow(
                icon = Icons.Outlined.Vibration,
                title = "Vibrate",
                description = toneOrOff,
                chevron = true,
                enabled = enabled,
                onClick = { openSystemNotificationSettings() },
            )
        }

        SettingsGroup(title = "Groups") {
            SettingsRow(
                icon = Icons.Outlined.MusicNote,
                title = "Notification tone",
                description = toneOrOff,
                chevron = true,
                enabled = enabled,
                onClick = { openSystemNotificationSettings() },
            )
        }

        SettingsGroup(title = "Calls") {
            SettingsRow(
                icon = Icons.Outlined.Phone,
                title = "Ringtone",
                description = toneOrOff,
                chevron = true,
                enabled = enabled,
                onClick = { openSystemNotificationSettings() },
            )
        }
    }
}
