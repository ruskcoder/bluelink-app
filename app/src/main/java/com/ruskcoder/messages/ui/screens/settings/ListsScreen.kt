package com.ruskcoder.messages.ui.screens.settings

import android.Manifest
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.outlined.PermContactCalendar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.data.repository.ContactsRepository
import com.ruskcoder.messages.permissions.rememberPermissionState
import com.ruskcoder.messages.ui.components.settings.SettingsGroup
import com.ruskcoder.messages.ui.components.settings.SettingsPage
import com.ruskcoder.messages.ui.components.settings.SettingsRow

/** Ported from `settings/lists.tsx`. */
@Composable
fun ListsScreen(chatRepository: ChatRepository, contactsRepository: ContactsRepository, onOpenPermissions: () -> Unit, onBack: () -> Unit) {
    val counts by chatRepository.counts.collectAsStateWithLifecycle()
    val contactsPermission = rememberPermissionState(Manifest.permission.READ_CONTACTS)
    var contactCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(contactsPermission.granted) {
        if (contactsPermission.granted) contactCount = contactsRepository.loadContacts().size
    }

    SettingsPage(title = "Lists", onBack = onBack) {
        SettingsGroup(title = "Your lists") {
            SettingsRow(icon = Icons.Filled.Groups, title = "Groups", description = "${counts.groups} chats")
        }
        SettingsGroup(title = "Contacts") {
            SettingsRow(
                icon = Icons.Outlined.PermContactCalendar,
                title = "Phone contacts",
                description = if (contactsPermission.granted) "$contactCount on this device" else "Access not granted yet",
                chevron = !contactsPermission.granted,
                onClick = if (contactsPermission.granted) null else onOpenPermissions,
            )
        }
    }
}
