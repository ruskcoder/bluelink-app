package com.ruskcoder.messages.ui.screens.settings

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.imessage.ConnectionHealth
import com.ruskcoder.messages.imessage.ConnectionMonitor
import com.ruskcoder.messages.permissions.rememberBatteryExemption
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow

/**
 * Settings → Connection: whether iMessage is actually receiving, and what stands in its way.
 *
 * The page someone opens when they suspect messages are not arriving, so it answers that directly —
 * the state, when anything last came in — and then lists the two things on the phone that most often
 * cause it, each with the fix one tap away.
 */
@Composable
fun ConnectionScreen(monitor: ConnectionMonitor, onReconnect: () -> Unit, onBack: () -> Unit) {
    val health by monitor.health.collectAsStateWithLifecycle()
    val battery = rememberBatteryExemption()
    val context = LocalContext.current
    // Read on each composition rather than remembered: coming back from the system's notification
    // settings recomposes this page, and the row should reflect what was just changed there.
    val notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val now = remember(health) { System.currentTimeMillis() }

    SettingsPage(title = "Connection", onBack = onBack) {
        SettingsGroup(title = "iMessage") {
            SettingsRow(
                icon = when (health.status) {
                    ConnectionHealth.Status.CONNECTED -> Icons.Outlined.CloudDone
                    ConnectionHealth.Status.CONNECTING, ConnectionHealth.Status.UNKNOWN -> Icons.Outlined.CloudSync
                    else -> Icons.Outlined.CloudOff
                },
                title = statusTitle(health),
                description = statusDescription(health),
            )
            SettingsRow(
                icon = Icons.Outlined.History,
                title = "Last connected",
                description = relative(health.lastConnectedAt, now),
            )
            SettingsRow(
                icon = Icons.Outlined.Inbox,
                title = "Last received from Apple",
                description = relative(health.lastEventAt, now),
            )
            SettingsRow(
                icon = if (health.networkAvailable) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                title = "Network",
                description = if (health.networkAvailable) "Available" else "No connection",
            )
            SettingsRow(
                icon = Icons.Outlined.Sync,
                title = "Background service",
                description = buildString {
                    append(if (health.serviceRunning) "Running" else "Not running")
                    if (health.reconnects > 0) append(" · reconnected ${health.reconnects}× since the app started")
                },
            )
        }

        SettingsGroup(title = "Keeping messages arriving") {
            SettingsRow(
                icon = if (battery.exempt) Icons.Outlined.BatteryFull else Icons.Outlined.BatteryAlert,
                title = "Battery optimization",
                description = if (battery.exempt) {
                    "Unrestricted — messages arrive while the phone is idle"
                } else {
                    "Restricted — messages may wait until you open the app. Tap to allow."
                },
                chevron = !battery.exempt,
                onClick = if (battery.exempt) null else battery.request,
            )
            SettingsRow(
                icon = if (notificationsAllowed) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                title = "Notifications",
                description = if (notificationsAllowed) "Allowed" else "Blocked — you won't hear about new messages. Tap to change.",
                chevron = true,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        }

        SettingsGroup {
            SettingsRow(
                icon = Icons.Outlined.Refresh,
                title = "Reconnect now",
                description = "Closes the connection to Apple and opens a fresh one",
                onClick = onReconnect,
            )
        }
    }
}

private fun statusTitle(health: ConnectionHealth): String = when (health.status) {
    ConnectionHealth.Status.CONNECTED -> "Connected"
    ConnectionHealth.Status.CONNECTING -> "Connecting…"
    ConnectionHealth.Status.OFFLINE -> "Waiting for network"
    ConnectionHealth.Status.FAILED -> "Can't reach iMessage"
    ConnectionHealth.Status.NOT_SET_UP -> "Not signed in"
    ConnectionHealth.Status.UNKNOWN -> if (health.serviceRunning) "Starting…" else "Not running"
}

private fun statusDescription(health: ConnectionHealth): String = when (health.status) {
    ConnectionHealth.Status.CONNECTED -> "Messages are arriving normally"
    ConnectionHealth.Status.CONNECTING -> "Messages you send are queued and go out once connected"
    ConnectionHealth.Status.OFFLINE -> "This phone has no network. Messages you send are queued."
    ConnectionHealth.Status.FAILED -> health.detail ?: "Apple isn't answering. The app keeps retrying."
    ConnectionHealth.Status.NOT_SET_UP -> "Sign in under Account to send and receive iMessages"
    ConnectionHealth.Status.UNKNOWN -> "The connection starts once you have signed in"
}

private fun relative(at: Long, now: Long): String =
    if (at <= 0L) "Never" else DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
