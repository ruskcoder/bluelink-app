package com.ruskcoder.messages.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.MemberView

private const val GridColumns = 3

/** Grid or list layout for a group's roster. Ported from `member-list.tsx`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemberList(
    members: List<GroupMember>,
    view: MemberView,
    onAddPeople: () -> Unit,
    onMessage: (GroupMember) -> Unit,
    onShowContact: (GroupMember) -> Unit,
    onRemove: (GroupMember) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (view == MemberView.GRID) {
        // A plain wrapping row, not a `LazyVerticalGrid`: this is rendered inside the group page's
        // vertical scroll, and a lazy grid measured against an unbounded height throws. A roster is
        // small enough that there is nothing to virtualize anyway.
        FlowRow(maxItemsInEachRow = GridColumns, modifier = modifier) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(1f / GridColumns).padding(8.dp).clickable(onClick = onAddPeople),
            ) {
                Box(modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = "Add people")
                }
                Text("Add", style = MaterialTheme.typography.labelSmall)
            }
            members.forEach { member ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(1f / GridColumns).padding(8.dp),
                ) {
                    MemberAvatar(member = member, size = 56.dp)
                    Text(
                        member.name.substringBefore(' '),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            ListItem(
                headlineContent = { Text("Add people") },
                leadingContent = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                // The panel this list is inset into *is* the surface. A row that paints its own
                // covers the glass the panel is made of, and the roster ends up looking like a
                // second, opaque card sitting inside the first.
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onAddPeople),
            )
            members.forEach { member ->
                var menuOpen by remember(member.id) { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(member.name) },
                    supportingContent = member.phone?.let { { Text(it) } },
                    leadingContent = {
                        MemberAvatar(member = member, size = 40.dp)
                    },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            AppDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                AppMenuItem(
                                    text = { Text("Message") },
                                    trailingIcon = { Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null) },
                                    onClick = { menuOpen = false; onMessage(member) },
                                )
                                AppMenuItem(
                                    text = { Text("Show contact") },
                                    trailingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                                    onClick = { menuOpen = false; onShowContact(member) },
                                )
                                AppMenuItem(
                                    text = { Text("Remove from group") },
                                    trailingIcon = { Icon(Icons.Outlined.PersonRemove, contentDescription = null) },
                                    colors = AppMenu.destructiveItemColors(),
                                    onClick = { menuOpen = false; onRemove(member) },
                                )
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}
