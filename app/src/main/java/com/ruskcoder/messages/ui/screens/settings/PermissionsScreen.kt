package com.ruskcoder.messages.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.permissions.PermissionRequester
import com.ruskcoder.messages.permissions.rememberPermissionState
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow

private data class PermissionInfo(val label: String, val icon: ImageVector, val reason: String)

/** Live list of OS permissions. Ported from `settings/permissions.tsx`. */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val microphone = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    val camera = rememberPermissionState(Manifest.permission.CAMERA)
    val photos = rememberPermissionState(
        if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
    )
    val location = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)
    val contacts = rememberPermissionState(Manifest.permission.READ_CONTACTS)

    val rows = listOf(
        PermissionInfo("Microphone", Icons.Outlined.Mic, "Record voice messages and send them in a chat.") to microphone,
        PermissionInfo("Camera", Icons.Outlined.CameraAlt, "Take a photo or video without leaving the conversation.") to camera,
        PermissionInfo("Photos", Icons.Outlined.Image, "Pick pictures and videos from your library to send.") to photos,
        PermissionInfo("Location", Icons.Outlined.Place, "Share where you are as a pin in a chat.") to location,
        PermissionInfo("Contacts", Icons.Outlined.Contacts, "Find people to message and send their contact cards.") to contacts,
    )

    SettingsPage(title = "Permissions", onBack = onBack) {
        SettingsGroup(title = "Device access") {
            rows.forEach { (info, state) ->
                SettingsRow(
                    icon = info.icon,
                    title = info.label,
                    description = if (state.granted) "Allowed" else "Not allowed",
                    trailing = {
                        if (state.granted) {
                            Text("On", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        } else {
                            AllowButton(state)
                        }
                    },
                )
            }
        }


        OutlinedButton(
            onClick = {
                val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                context.startActivity(intent)
            },
            modifier = Modifier.padding(start = 16.dp, top = 20.dp),
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Open system settings")
        }
    }
}

@Composable
private fun AllowButton(state: PermissionRequester) {
    FilledTonalButton(
        onClick = { state.request() },
        contentPadding = ButtonDefaults.ContentPadding,
    ) { Text("Allow") }
}
