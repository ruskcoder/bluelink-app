package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.Contact
import com.ruskcoder.messages.util.toComposeColor

/** A search box + filtered contact list. Backs both `new-chat.tsx` and `contact-picker.tsx`. */
@Composable
fun ContactList(
    contacts: List<Contact>,
    query: String,
    onQueryChange: (String) -> Unit,
    selectedIds: Set<String>,
    onToggle: (Contact) -> Unit,
    disabledIds: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val filtered = if (query.isBlank()) contacts else contacts.filter { it.name.contains(query, ignoreCase = true) }
    LazyColumn(modifier = modifier) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search contacts") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }
        items(filtered, key = { it.id }) { contact ->
            val disabled = contact.id in disabledIds
            val selected = contact.id in selectedIds
            ListItem(
                headlineContent = { Text(contact.name, fontWeight = FontWeight.Medium) },
                supportingContent = contact.about?.let { { Text(it) } },
                leadingContent = {
                    Avatar(
                        initials = contact.initials,
                        color = contact.color.toComposeColor(),
                        size = 44.dp,
                        photoUri = contact.photoUri,
                    )
                },
                trailingContent = if (selected) {
                    { Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
                } else {
                    null
                },
                // Transparent for the same reason every other list in the app is: the sheet
                // underneath is the surface, and a row with a fill of its own hides the glass it
                // is standing on.
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.alpha(if (disabled) 0.4f else 1f).clickable(enabled = !disabled) { onToggle(contact) },
            )
        }
    }
}
