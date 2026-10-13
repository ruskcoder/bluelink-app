package com.ruskcoder.messages.ui.screens.chatlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.saveable.rememberSaveable
import com.ruskcoder.messages.imessage.ConnectionHealth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.data.model.Chat
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.ui.components.GoogleStatusBadge
import com.ruskcoder.messages.ui.components.StyledTopAppBar
import com.ruskcoder.messages.data.model.ThemeMode
import com.ruskcoder.messages.data.model.displayInitials
import com.ruskcoder.messages.data.model.mentionsMe
import com.ruskcoder.messages.data.model.myMentionNames
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.permissions.rememberBatteryExemption
import com.ruskcoder.messages.ui.components.BackgroundDeliveryBanner
import com.ruskcoder.messages.ui.components.Avatar
import com.ruskcoder.messages.ui.components.ChatAvatar
import com.ruskcoder.messages.ui.components.chatBackdropSource
import com.ruskcoder.messages.ui.components.rememberChatBackdrop
import com.ruskcoder.messages.ui.components.previewIcon
import com.ruskcoder.messages.ui.components.previewPrefix
import com.ruskcoder.messages.ui.components.typingLabel
import com.ruskcoder.messages.ui.theme.AppPalette
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.util.toComposeColor
import kotlinx.coroutines.flow.StateFlow
import com.ruskcoder.messages.ui.components.AppDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    chatRepository: ChatRepository,
    /** Who is writing where, so a row can say so instead of showing its last message. */
    typingByChat: StateFlow<Map<String, List<String>>>,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onNewChat: () -> Unit,
    /** Records the one dismissal the background-delivery banner takes for an answer. */
    settingsRepository: SettingsRepository,
    /** How the connection to Apple is doing, so the list can say when messages may not be arriving. */
    connection: StateFlow<ConnectionHealth>? = null,
    /** Opens Settings → Connection, where the banner's details and fixes are. */
    onOpenConnection: () -> Unit = {},
) {
    val settings = LocalSettings.current
    val battery = rememberBatteryExemption()
    // Granting it and later revoking it is a different situation from the one that was waved away,
    // so the dismissal is forgotten the moment the exemption is seen to be in place. Otherwise a
    // reinstall — which clears the exemption but keeps the settings — would leave the banner
    // permanently suppressed for the one case it exists to catch.
    LaunchedEffect(battery.exempt) {
        if (battery.exempt && settings.batteryBannerDismissed) {
            settingsRepository.update { it.copy(batteryBannerDismissed = false) }
        }
    }
    val chats by chatRepository.chats.collectAsStateWithLifecycle()
    val ready by chatRepository.ready.collectAsStateWithLifecycle()
    val typing by typingByChat.collectAsStateWithLifecycle()
    val health by (connection ?: NoConnection).collectAsStateWithLifecycle()

    // Only once a problem has lasted a moment. Every launch connects from nothing, and a banner that
    // flashed "Connecting…" for half a second each time would teach everyone to ignore it — which is
    // the opposite of what it is for.
    var connectionBannerVisible by remember { mutableStateOf(false) }
    LaunchedEffect(health.isProblem) {
        if (!health.isProblem) {
            connectionBannerVisible = false
            return@LaunchedEffect
        }
        delay(ConnectionBannerGraceMs)
        connectionBannerVisible = true
    }

    /** Whether the list is showing Archived rather than the conversations in use. */
    var showingArchived by rememberSaveable { mutableStateOf(false) }
    val archived = remember(chats) { chats.filter { it.archived } }
    val visibleChats = remember(chats, showingArchived) { chats.filter { it.archived == showingArchived } }
    // Unarchiving the last one leaves nothing to look at, so the page puts itself away.
    LaunchedEffect(archived.isEmpty()) { if (archived.isEmpty()) showingArchived = false }
    BackHandler(enabled = showingArchived) { showingArchived = false }

    // The conversations near the top are the ones about to be opened, so their messages are pulled
    // into memory now rather than during the transition that opens them. Only the first screenful:
    // warming the whole list would be the entire message store mapped on every launch.
    LaunchedEffect(chats) {
        chats.take(PrefetchChats).forEach { chatRepository.prefetchThread(it.id) }
    }

    var confirmDelete by remember { mutableStateOf<Chat?>(null) }
    /** The conversation currently being peeked at, held open by the long press that picked it. */
    var previewId by remember { mutableStateOf<String?>(null) }
    // Looked up on every recomposition rather than captured: the peeked conversation keeps moving —
    // a message arrives, it is pinned, it is read — and the window has to be showing it as it is.
    val previewChat = remember(chats, previewId) { chats.find { it.id == previewId } }

    // AMOLED-style pure black in dark mode; light mode keeps the theme's usual background.
    val pageBackground = if (settings.mode == ThemeMode.DARK) AppPalette.AmoledBlack else MaterialTheme.colorScheme.background

    // The list records itself: the bar is frosted glass over it, and a peek frosts it again behind
    // the actions card. The bar is *not* in the recording, which is what keeps its own title sharp.
    val listBackdrop = rememberChatBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = pageBackground,
            topBar = {
                StyledTopAppBar(
                    title = {
                        Text(
                            if (showingArchived) "Archived" else "Messages",
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                        )
                    },
                    navigationIcon = {
                        if (showingArchived) {
                            IconButton(onClick = { showingArchived = false }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        // Search is a button rather than the full-width box it used to be: the box
                        // cost a whole row of the list to say something an icon says, and pushed the
                        // first conversation below the fold on a short screen.
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search")
                        }
                        Avatar(
                            initials = settings.displayInitials(),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            size = 32.dp,
                            modifier = Modifier
                                .padding(start = 2.dp, end = 12.dp)
                                // Clipped before it is clickable, so the press ripple is a circle
                                // rather than the square that used to flash behind the face.
                                .clip(CircleShape)
                                .clickable(onClick = onOpenSettings),
                            photoUri = settings.profilePhotoUri,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            labelSize = 13.sp,
                        )
                    },
                    backdrop = listBackdrop,
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onNewChat,
                    shape = RoundedCornerShape(18.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    icon = { Icon(Icons.Rounded.AddComment, contentDescription = null) },
                    text = { Text("Start chat", fontWeight = FontWeight.Medium) },
                )
            },
        ) { padding ->
            // The bar's padding is spent as the list's own top inset rather than as a gap above it,
            // so rows pass *under* the glass instead of stopping short of it. Nothing to frost is
            // what made the old bar have to be opaque.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding())
                    .background(pageBackground)
                    .chatBackdropSource(listBackdrop),
            ) {
                if (!ready) return@Column

                if (chats.isEmpty()) {
                    EmptyState(topInset = padding.calculateTopPadding())
                    return@Column
                }

                LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 88.dp)) {
                    // Above the conversations rather than over them: it is a standing condition
                    // about the whole list, and it scrolls away like anything else on it.
                    // First of all: whether messages are arriving at all is the one thing on this
                    // page more important than which conversations are in it.
                    if (connectionBannerVisible && !showingArchived) {
                        item(key = "connection") {
                            ConnectionBanner(health = health, onClick = onOpenConnection, modifier = Modifier.animateItem())
                        }
                    }
                    if (!showingArchived && archived.isNotEmpty()) {
                        item(key = "archived") {
                            ArchivedEntry(
                                count = archived.size,
                                unread = archived.count { it.unread > 0 },
                                containerColor = pageBackground,
                                onClick = { showingArchived = true },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    if (!showingArchived && !battery.exempt && !settings.batteryBannerDismissed) {
                        item(key = "background-delivery") {
                            BackgroundDeliveryBanner(
                                onAllow = { battery.request() },
                                onDismiss = {
                                    settingsRepository.update { it.copy(batteryBannerDismissed = true) }
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    items(visibleChats, key = { it.id }) { chat ->
                        // A chat that jumps to the top on a new message slides there.
                        ChatRow(
                            chat = chat,
                            // The row has one line to say it in, so it names whoever started.
                            typingSender = typing[chat.id]?.firstOrNull(),
                            // A muted group is exactly where a mention has to be surfaced: the badge is
                            // otherwise the only signal, and muting takes it away.
                            mentionsMe = chat.unread > 0 && chat.last?.mine == false &&
                                mentionsMe(chat.preview, settings.myMentionNames()),
                            containerColor = pageBackground,
                            onClick = { onOpenChat(chat.id) },
                            // A long press peeks at the conversation rather than dropping a menu on it;
                            // the menu's own actions come up with the window. See [ChatPreviewOverlay].
                            onLongClick = { previewId = chat.id },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        previewChat?.let { chat ->
            ChatPreviewOverlay(
                chat = chat,
                chatRepository = chatRepository,
                listBackdrop = listBackdrop,
                typingSender = typing[chat.id]?.firstOrNull(),
                onOpen = { previewId = null; onOpenChat(chat.id) },
                onPin = { chatRepository.setPinned(chat.id, !chat.pinned) },
                onToggleRead = {
                    if (chat.unread > 0) chatRepository.markRead(chat.id) else chatRepository.markUnread(chat.id)
                },
                onMute = { chatRepository.setMuted(chat.id, !chat.muted) },
                onArchive = { chatRepository.setArchived(chat.id, !chat.archived) },
                onDelete = { confirmDelete = chat },
                onClosed = { previewId = null },
            )
        }
    }

    confirmDelete?.let { chat ->
        AppDialog(
            title = "Delete chat?",
            message = "This removes the conversation with ${chat.name} and all of its messages from this device.",
            onDismiss = { confirmDelete = null },
            buttons = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel", color = Color.White) }
                TextButton(onClick = { chatRepository.deleteChat(chat.id); confirmDelete = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
private fun EmptyState(topInset: Dp) {
    Column(
        // Clear of the bar, which the rest of the page is laid out underneath.
        modifier = Modifier.fillMaxSize().padding(top = topInset + 72.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No chats yet", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Tap the button below to start a conversation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: Chat,
    typingSender: String?,
    mentionsMe: Boolean,
    /** The page's own colour, painted behind the inset row. See below for why the row cannot guess it. */
    containerColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unread = chat.unread > 0
    // Two layers on purpose. The outer box is opaque and full-bleed; the inner row is a rounded
    // pill, so a press and a long press light up a shape the size of the row rather than flashing
    // edge to edge.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A group shows two of its members' faces overlapped; a one-to-one shows the contact's own
            // photo, falling back to the initials — or a silhouette for a bare number — underneath it.
            if (chat.group) {
                ChatAvatar(chat = chat, size = 52.dp, ringColor = containerColor)
            } else {
                // Always the person's own colour. It used to be one flat container blue unless Material
                // You was switched on, which made a list of fifty conversations look like one thing.
                Avatar(
                    initials = chat.initials,
                    color = chat.color.toComposeColor(),
                    size = 52.dp,
                    photoUri = chat.photoUri,
                    labelSize = 15.sp,
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(chat.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val previewColor = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                // Someone writing displaces the last message entirely, in the accent colour — it is the
                // more current answer to "what is happening in here", and only for as long as it lasts.
                if (typingSender != null) {
                    Text(
                        typingLabel(typingSender, chat.members, chat.group),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Your own last message carries its tick here as well as in the thread, so the list
                    // answers "did that send" without opening the conversation.
                    // The same badge the conversation draws under the message itself: one tick for
                    // gone, two for arrived, and the disc fills in once it has been read. The list
                    // and the thread should not be telling the same three facts in two languages.
                    val sentStatus = chat.last?.takeIf { it.mine }?.status
                    if (sentStatus != null) {
                        GoogleStatusBadge(
                            status = sentStatus,
                            // Read fills the disc white rather than picking up the theme's accent:
                            // the badge is the one thing on this line that is not about the
                            // conversation, and a coloured one read as a chat that had been
                            // recoloured rather than as a message that had been read.
                            tint = if (sentStatus.isRead) Color.White else previewColor,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    previewIcon(chat.last, chat.preview)?.let { icon ->
                        Icon(icon, contentDescription = null, tint = previewColor, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        previewPrefix(chat) + chat.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = previewColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    chat.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chat.pinned) {
                        Icon(
                            Icons.Outlined.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (mentionsMe) {
                        Icon(
                            Icons.Rounded.AlternateEmail,
                            contentDescription = "You were mentioned",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (chat.muted) {
                        Icon(
                            Icons.Outlined.NotificationsOff,
                            contentDescription = "Muted",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        if (unread) Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (unread) {
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 20.dp)
                                .height(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary)
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // `Alignment.Center` centres the text's *line box*, and a line box is
                            // not the digits in it. Left to the ambient style it also keeps that
                            // style's line height — a body style's 24sp, inherited even though the
                            // size here is overridden to 12 — so the box came out taller than the
                            // circle it sits in, with the leftover space split unevenly above and
                            // below the number. That split is what dropped the digit below the
                            // middle of the badge. Pinned to the glyphs' own height and centred
                            // inside it, the box and the number are the same thing, and centring
                            // one centres the other.
                            Text(
                                chat.unread.toString(),
                                color = MaterialTheme.colorScheme.onSecondary,
                                fontSize = 12.sp,
                                lineHeight = 12.sp,
                                fontWeight = FontWeight.Bold,
                                style = LocalTextStyle.current.copy(
                                    lineHeightStyle = LineHeightStyle(
                                        alignment = LineHeightStyle.Alignment.Center,
                                        trim = LineHeightStyle.Trim.Both,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** How many conversations are warmed ahead of being opened — about a screenful. */
private const val PrefetchChats = 10

/** How long a connection problem has to last before the list says anything about it. */
private const val ConnectionBannerGraceMs = 3_000L

/** What a list with nothing to report about the connection reads. */
private val NoConnection: StateFlow<ConnectionHealth> = MutableStateFlow(ConnectionHealth())

/**
 * The line at the top of the list that says messages may not be arriving right now.
 *
 * Tappable, into Settings → Connection, because "not connected" with nothing to do about it is only
 * worry — the page behind it says why and offers the fixes.
 */
@Composable
private fun ConnectionBanner(health: ConnectionHealth, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val connecting = health.status == ConnectionHealth.Status.CONNECTING
    val title = when (health.status) {
        ConnectionHealth.Status.OFFLINE -> "Waiting for network"
        ConnectionHealth.Status.FAILED -> "Can't reach iMessage"
        else -> "Connecting to iMessage…"
    }
    val container = if (connecting) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
    val content = if (connecting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (connecting) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = content)
        } else {
            Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = content)
            Text(
                "Messages you send will go out once connected",
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.8f),
            )
        }
    }
}

/**
 * The row at the top of the list that leads into Archived.
 *
 * Shaped like a conversation row, so it reads as one more place in the list rather than a control
 * laid over it — and only there at all while something is archived.
 */
@Composable
private fun ArchivedEntry(
    count: Int,
    unread: Int,
    containerColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Archive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                "Archived",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (unread > 0) unread.toString() else count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (unread > 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
