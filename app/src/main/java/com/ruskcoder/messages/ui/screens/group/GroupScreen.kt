package com.ruskcoder.messages.ui.screens.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Palette
import com.ruskcoder.messages.ui.components.AppDialog
import com.ruskcoder.messages.ui.components.frostedFill
import com.ruskcoder.messages.ui.components.ControlTint
import com.ruskcoder.messages.ui.components.ControlBlur
import com.ruskcoder.messages.ui.components.AppMenu
import com.ruskcoder.messages.ui.components.AppDropdownMenu
import com.ruskcoder.messages.ui.components.AppMenuDivider
import com.ruskcoder.messages.ui.components.AppMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.data.model.Contact
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.imessage.IMessageSender
import com.ruskcoder.messages.data.repository.ContactsRepository
import com.ruskcoder.messages.ui.components.ChatColorPicker
import com.ruskcoder.messages.ui.components.ContactPickerSheet
import com.ruskcoder.messages.ui.components.DetailHeaderHeight
import com.ruskcoder.messages.ui.components.FrostedDetailPage
import com.ruskcoder.messages.ui.components.ChatSettingsSection
import com.ruskcoder.messages.ui.components.ChatSharedMediaSection
import com.ruskcoder.messages.ui.components.sharedMediaOf
import com.ruskcoder.messages.ui.components.FrostedSection
import com.ruskcoder.messages.ui.components.SectionInset
import com.ruskcoder.messages.ui.components.GroupAvatarCluster
import com.ruskcoder.messages.ui.components.MemberList
import com.ruskcoder.messages.ui.theme.LocalSettings

/** Daylight between one panel and the next. */
private val SectionGap = 14.dp

/** Group info page. Ported from `group/[id].tsx`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(
    chatId: String,
    chatRepository: ChatRepository,
    /** Mirrors renames and membership changes to Apple for an iMessage group. */
    imessageSender: IMessageSender,
    contactsRepository: ContactsRepository,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    /** Opens the contact details page for one group member, addressed by their handle. */
    onShowContact: (String) -> Unit,
    onOpenSearch: (String) -> Unit,
    /** The full search page, confined to this conversation. */
    onBrowseChat: (String) -> Unit,
    onLeaveGroup: () -> Unit,
) {
    val settings = LocalSettings.current
    val chats by chatRepository.chats.collectAsStateWithLifecycle()
    val chat = chats.find { it.id == chatId }
    val members = chat?.members ?: emptyList()

    var editingName by remember { mutableStateOf(false) }
    var nameDraft by remember(chat?.name) { mutableStateOf(chat?.name ?: "") }
    var showColorPicker by remember { mutableStateOf(false) }
    var showAddPeople by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }

    fun commitName() {
        editingName = false
        val next = nameDraft.trim()
        if (next.isNotEmpty() && next != chat?.name) {
            chatRepository.setMembers(chatId, members, next)
            imessageSender.renameGroup(chatId, next)
        }
    }

    // The pictures actually in this conversation, newest first.
    // Every message, not the chat screen's window — shared media is the whole conversation's.
    val thread by remember(chatId) { chatRepository.allMessagesFlow(chatId) }.collectAsStateWithLifecycle(emptyList())
    val media = remember(thread) { sharedMediaOf(thread) }

    val scroll = rememberScrollState()

    // Same frosted sheet the one-to-one details page wears, over a copy of the conversation it was
    // opened from, and the same travelling header — the two pages are one page seen from either
    // side of a chat, and the group's name is drawn in the same ink as a contact's rather than as a
    // blue link. Renaming still hangs off it; that is what the tap does, not what the colour says.
    FrostedDetailPage(
        name = chat?.name ?: "Group",
        subtitle = if (members.size == 1) "1 person" else "${members.size} people",
        scroll = scroll,
        avatar = { size -> GroupAvatarCluster(members = members, onCameraClick = {}, size = size) },
        onNameClick = { nameDraft = chat?.name ?: ""; editingName = true },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        actions = {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
            AppDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                AppMenuItem(
                    text = { Text("Change colors") },
                    trailingIcon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                    onClick = { menuOpen = false; showColorPicker = true },
                )
                AppMenuItem(
                    text = { Text("Rename group") },
                    trailingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                    onClick = { menuOpen = false; nameDraft = chat?.name ?: ""; editingName = true },
                )
                AppMenuDivider()
                AppMenuItem(
                    text = { Text("Leave group") },
                    trailingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                    colors = AppMenu.destructiveItemColors(),
                    onClick = { menuOpen = false; showLeaveConfirm = true },
                )
            }
        },
    ) { padding ->
        // `imePadding` so the rename field stays above the keyboard it opens; consuming the
        // scaffold's insets first keeps the navigation bar from being counted twice.
        Column(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scroll)
                .padding(bottom = 48.dp),
        ) {
            // The room the expanded header stands in. It is drawn over the page rather than in it,
            // so this is the only thing holding its place — and holding it exactly, or the header
            // and the content would drift apart as one scrolled and the other collapsed.
            Spacer(modifier = Modifier.height(DetailHeaderHeight))

            // The rename field opens *under* the header rather than in place of the name: the name
            // is up in the header now, on a path into the app bar, and a field swapped into
            // something halfway through travelling has nowhere sensible to stand.
            if (editingName) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SectionInset),
                ) {
                    TextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = ::commitName) { Text("Save") }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 28.dp),
            ) {
                GroupAction(Icons.Filled.PersonAdd, "Add people") { showAddPeople = true }
                GroupAction(Icons.Filled.Search, "Search") { onOpenSearch(chatId) }
            }

            // What has been shared in here, above who is in here: the same card the one-to-one
            // page carries, because it answers the same question about the same conversation.
            ChatSharedMediaSection(media = media, onBrowse = { onBrowseChat(chatId) })

            Spacer(modifier = Modifier.height(SectionGap))

            // Then the roster, and the decisions there are to make about a group. Everything else
            // that used to be listed here — colours, pinned messages, an invite link, a
            // disappearing-message switch that was permanently off — either had nothing behind it
            // or already lives in the menu at the top, and a list of rows that do not do anything
            // is worse than a short list that does.
            FrostedSection {
                MemberList(
                    members = members,
                    view = settings.memberView,
                    onAddPeople = { showAddPeople = true },
                    onMessage = { member -> onOpenChat(member.id) },
                    onShowContact = { member -> onShowContact(member.id) },
                    onRemove = { member ->
                        // The sender reads the live roster, sends the change, then writes the new
                        // list locally on success — writing it here first made Apple see no diff.
                        imessageSender.setParticipants(
                            chatId,
                            members.filterNot { it.id == member.id },
                            chat?.name,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                )
            }

            Spacer(modifier = Modifier.height(SectionGap))

            // The same panel the contact page carries, minus the block: there is nobody to block
            // in a group, and the two decisions that stand in for it — reporting it and leaving —
            // are the panel below. The mute switch that used to be the whole of this lived in a
            // local `var` and forgot itself the moment you left the page; it is stored now, along
            // with everything beside it.
            ChatSettingsSection(
                chat = chat,
                chatRepository = chatRepository,
                onOpenTheme = { showColorPicker = true },
                showBlock = false,
            )

            Spacer(modifier = Modifier.height(SectionGap))

            // Reporting and leaving share a panel because they are the same kind of decision: the
            // things you do when you want out of a conversation, rather than something you do
            // inside one.
            FrostedSection {
                GroupOption(Icons.Outlined.Flag, "Report group", destructive = true)
                GroupOption(Icons.AutoMirrored.Filled.Logout, "Leave group", destructive = true, onClick = { showLeaveConfirm = true })
            }
        }
    }

    if (showColorPicker && chat != null) {
        ChatColorPicker(
            selected = chat.theme,
            selectedHue = chat.themeHue,
            onDismiss = { showColorPicker = false },
            onConfirm = { theme, hue -> showColorPicker = false; chatRepository.setChatTheme(chatId, theme, hue) },
        )
    }

    if (showAddPeople) {
        LaunchedEffect(Unit) { contacts = contactsRepository.loadContacts() }
        ContactPickerSheet(
            contacts = contacts,
            disabledIds = members.map { it.id }.toSet(),
            onDismiss = { showAddPeople = false },
            onConfirm = { picked ->
                showAddPeople = false
                val newMembers = picked.map { GroupMember(it.id, it.name, it.initials, it.color, it.about, it.photoUri) }
                imessageSender.setParticipants(chatId, members + newMembers, chat?.name)
            },
        )
    }

    if (showLeaveConfirm) {
        // The app's own glass rather than Material's opaque card: this is asked from a frosted page,
        // and a solid white slab dropped on top of one reads as a different app's dialog.
        AppDialog(
            title = "Leave this group?",
            message = "You'll stop receiving messages, and the conversation is removed from this device.",
            onDismiss = { showLeaveConfirm = false },
            buttons = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("Cancel", color = Color.White) }
                TextButton(onClick = { chatRepository.deleteChat(chatId); showLeaveConfirm = false; onLeaveGroup() }) {
                    Text("Leave", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
private fun GroupAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            // The same glass as the contact page's row of actions, which this is the group's copy of.
            modifier = Modifier
                .width(96.dp)
                .height(54.dp)
                .frostedFill(RoundedCornerShape(27.dp), blur = ControlBlur, tint = ControlTint)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GroupOption(icon: ImageVector, title: String, description: String? = null, destructive: Boolean = false, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(title, color = color) },
        supportingContent = description?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = color) },
        trailingContent = trailing,
        // The panel underneath is the surface; a row that painted its own would cover the glass
        // the panel is made of and read as a card sitting on the page instead of part of it.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
