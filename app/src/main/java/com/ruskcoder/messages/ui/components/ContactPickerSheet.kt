package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.Contact

/** Bottom-sheet contact picker, e.g. for adding a member to a group. Ported from `contact-picker.tsx`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPickerSheet(
    contacts: List<Contact>,
    disabledIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<Contact>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }

    AppBottomSheet(onDismiss = onDismiss) {
        // The sheet's own search field opens the keyboard over it, so the content gives way to the
        // keyboard — otherwise the "Add" button ends up behind it, out of reach. The list caps its
        // height rather than fixing it, leaving the button room in whatever is left.
        Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
            ContactList(
                contacts = contacts,
                query = query,
                onQueryChange = { query = it },
                selectedIds = selected,
                disabledIds = disabledIds,
                onToggle = { contact -> selected = if (contact.id in selected) selected - contact.id else selected + contact.id },
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 420.dp),
            )
            Button(
                onClick = { onConfirm(contacts.filter { it.id in selected }); onDismiss() },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text("Add") }
        }
    }
}
