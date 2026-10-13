package com.ruskcoder.messages.ui.screens.share

import android.Manifest
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.Chat
import com.ruskcoder.messages.data.model.ChatIdentity
import com.ruskcoder.messages.data.model.Contact
import com.ruskcoder.messages.data.model.formatBytes
import com.ruskcoder.messages.data.model.sendBar
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.data.repository.ContactsRepository
import com.ruskcoder.messages.imessage.AttachmentStaging
import com.ruskcoder.messages.imessage.Handles
import com.ruskcoder.messages.imessage.IMessageSender
import com.ruskcoder.messages.permissions.rememberPermissionState
import com.ruskcoder.messages.ui.components.Avatar
import com.ruskcoder.messages.ui.components.ChatAvatar
import com.ruskcoder.messages.ui.components.ChatComposer
import com.ruskcoder.messages.ui.components.ControlBlur
import com.ruskcoder.messages.ui.components.ControlTint
import com.ruskcoder.messages.ui.components.FrostedBackground
import com.ruskcoder.messages.ui.components.SectionInset
import com.ruskcoder.messages.ui.components.frostedFill
import com.ruskcoder.messages.ui.components.isVideoUri
import com.ruskcoder.messages.ui.components.mediaAttachmentFor
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.util.toComposeColor
import java.io.File

/** Matches the search page's bar, which this one is a sibling of. */
private val FieldHeight = 52.dp
private val FieldShape = RoundedCornerShape(26.dp)

/** How far a row that cannot be picked fades back. The send bar's own disabled value. */
private const val DisabledAlpha = 0.4f

/** What a one-line notice about somebody should call them. */
private fun firstName(name: String): String = name.substringBefore(' ').ifEmpty { name }

/**
 * One place a share can go, whether or not it has ever been messaged before.
 *
 * A conversation and an address-book entry are the same choice as far as the person sharing is
 * concerned — "send this to Alex" — so they are one row type and one selection set. What differs is
 * only what has to happen before the send: an existing conversation is ready, and a contact needs
 * [addresses] so one can be registered with Apple first.
 */
private data class ShareRow(
    val id: String,
    val name: String,
    val subtitle: String,
    val identity: ChatIdentity,
    val addresses: List<String>,
    /** Set for a conversation, so the row can draw a group's overlapped faces. */
    val chat: Chat? = null,
    val initials: String,
    val color: String,
    val photoUri: String?,
)

/** A shared file, once it is the app's own copy rather than the sending app's. */
private data class StagedShare(val attachment: Attachment, val uri: Uri, val isMedia: Boolean)

/**
 * Where something shared from another app goes.
 *
 * Deliberately not the recipient picker. That screen picks the people a *new* conversation is made
 * of — one conversation — and everything about it, the pills, the reachability checks, the thread
 * preview underneath, is about settling on that one set. A share is the opposite shape: the message
 * already exists, several conversations can each have a copy, and none of them are being made into
 * a group. So this is a checklist with a draft attached, and the only thing the two screens have in
 * common is the send bar at the bottom.
 *
 * Everything shared is copied into the app's own storage before this screen will send anything, so
 * that finishing the activity cannot pull the files out from under the uploads still running behind
 * it. See [AttachmentStaging.stageShared].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareTargetScreen(
    shared: SharedContent,
    chatRepository: ChatRepository,
    contactsRepository: ContactsRepository,
    imessageSender: IMessageSender,
    /** Backing out without sending. */
    onClose: () -> Unit,
    /** The share is on its way; the screen has nothing left to do. */
    onSent: (targets: Int) -> Unit,
    /** A conversation picked in the system share sheet's own row, which starts out ticked. */
    preselectedChatId: String? = null,
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val chats by chatRepository.chats.collectAsStateWithLifecycle()

    val permission = rememberPermissionState(Manifest.permission.READ_CONTACTS)
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    LaunchedEffect(permission.granted) {
        if (permission.granted) {
            contacts = runCatching { contactsRepository.loadContacts() }.getOrDefault(emptyList())
        }
    }

    // Copied while the grant that lets us read them is still good, which is now — see the note on
    // the screen. Null until it finishes, which is what the send bar waits on.
    var staged by remember { mutableStateOf<List<StagedShare>?>(null) }
    LaunchedEffect(shared) {
        // Off the main thread as a whole, not just for the copying. Describing a clip opens it with
        // a `MediaMetadataRetriever` to read its frame size and duration, and doing that for a
        // handful of shared videos on the composition's own dispatcher is a visibly stuck screen
        // before the picker has drawn a single row.
        staged = withContext(Dispatchers.IO) {
            shared.uris.mapNotNull { source ->
                val copy = AttachmentStaging.stageShared(context, source) ?: return@mapNotNull null
                val media = isMediaUri(context, copy)
                StagedShare(
                    attachment = if (media) mediaAttachmentFor(context, copy) else fileAttachment(context, copy),
                    uri = copy,
                    isMedia = media,
                )
            }
        }
    }

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var query by remember { mutableStateOf("") }
    // An existing conversation, so it needs no reachability lookup — it has already proved it can
    // be sent to by existing.
    var selected by remember { mutableStateOf(setOfNotNull(preselectedChatId?.takeIf { it.isNotBlank() })) }

    /**
     * What Apple has said about each row, by row id — absent until somebody picks it.
     *
     * Asked on selection rather than for the whole list up front. The list is every conversation
     * plus the address book, and looking all of them up would be hundreds of queries against a
     * service that rate-limits, to answer a question about the two people the share is actually
     * going to. So nothing is asked until a name is ticked, and the answer is then kept: unticking
     * somebody and putting them back does not ask again.
     *
     * A row that comes back false stays false for the life of the screen — that is what disables it
     * — and a lookup that simply failed records nothing at all, which is a third state and not the
     * same as a "no". On a phone with no iMessage set up every lookup fails, and writing those down
     * as refusals would grey out the entire list.
     */
    var reachable by remember { mutableStateOf(mapOf<String, Boolean>()) }

    /** Rows with a lookup in flight, so the tick can be a spinner while Apple is being asked. */
    var checking by remember { mutableStateOf(setOf<String>()) }
    var text by remember { mutableStateOf(shared.text) }
    var sending by remember { mutableStateOf(false) }

    val rows = remember(chats, contacts) { shareRows(chats, contacts) }
    val q = query.trim()
    val results = remember(rows, q) {
        if (q.isEmpty()) rows
        else rows.filter { it.name.contains(q, ignoreCase = true) || it.subtitle.contains(q, ignoreCase = true) }
    }
    val chosen = remember(rows, selected) { rows.filter { it.id in selected } }

    val media = staged.orEmpty().filter { it.isMedia }
    val files = staged.orEmpty().filterNot { it.isMedia }
    val ready = staged != null

    /**
     * Whether this row can be reached on iMessage, or null when Apple would not say.
     *
     * A conversation that already has Apple's own fields stored has proved this by existing, and a
     * group is not one address to ask about — partial membership is the normal state of a group, and
     * a member who has left iMessage does not make the thread unusable. Everything else is a single
     * handle, and that is what gets asked.
     */
    suspend fun verify(row: ShareRow): Boolean? {
        if (row.chat?.group == true) return true
        if (row.addresses.isEmpty() && chatRepository.imessageTarget(row.id) != null) return true
        // A conversation's id is already the canonical form of the one handle in it, which is
        // exactly what a contact row carries in `addresses`.
        val addresses = row.addresses.ifEmpty { listOf(row.id) }
        val answer = imessageSender.reachableTargets(addresses) ?: return null
        return addresses.any { Handles.key(Handles.normalize(it)) in answer }
    }

    fun toggle(row: ShareRow) {
        // Already known to be unreachable: the row is not tappable, and this is the same rule said
        // where the state actually changes so a stale press cannot slip past it.
        if (reachable[row.id] == false) return
        if (row.id in selected) {
            selected = selected - row.id
            return
        }
        // Ticked straight away rather than after the round trip. The answer is usually yes and
        // usually fast, and a checkbox that waits on the network before it fills in reads as a
        // list that has stopped responding.
        selected = selected + row.id
        if (reachable[row.id] == true || row.id in checking) return

        scope.launch {
            checking = checking + row.id
            val answer = runCatching { verify(row) }.getOrNull()
            checking = checking - row.id
            when (answer) {
                true -> reachable = reachable + (row.id to true)
                false -> {
                    reachable = reachable + (row.id to false)
                    selected = selected - row.id
                    snackbar.showSnackbar(firstName(row.name) + " isn't on iMessage")
                }
                // Nothing was learned, so nothing is recorded and the tick stands. The send itself
                // still falls back to a local-only message if it turns out there is nowhere to go.
                null -> Unit
            }
        }
    }

    fun send() {
        val items = staged ?: return
        // The same rule the bar is already shut by, stated where the send actually happens so a
        // press that beat the last answer back cannot slip past it.
        if (sending || chosen.isEmpty() || checking.isNotEmpty()) return
        if (text.isBlank() && items.isEmpty()) return
        sending = true
        imessageSender.shareTo(
            context = context,
            targets = chosen.map { IMessageSender.ShareTarget(it.identity, it.addresses) },
            attachments = items.map { it.attachment to it.uri },
            text = text,
        )
        onSent(chosen.size)
    }

    FrostedBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            ShareField(query = query, onQueryChange = { query = it }, onClose = onClose)

            // Who it is going to, gathered where they can be seen and taken off again — the list
            // below can be scrolled a long way from a name that was ticked at the top of it.
            if (chosen.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SectionInset, vertical = 2.dp),
                ) {
                    chosen.forEach { row ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .height(32.dp)
                                .widthIn(max = 190.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer)
                                .clickable { toggle(row) }
                                .padding(start = 12.dp, end = 8.dp),
                        ) {
                            Text(
                                row.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(modifier = Modifier.padding(start = 2.dp))
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove " + row.name,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            when {
                                q.isNotEmpty() -> "Results"
                                chosen.isEmpty() -> "Send to"
                                chosen.size == 1 -> "Sending to 1 person"
                                else -> "Sending to " + chosen.size + " people"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = SectionInset, vertical = 12.dp),
                        )
                    }
                    if (results.isEmpty()) {
                        item {
                            ShareEmptyState(
                                searching = q.isNotEmpty(),
                                contactsGranted = permission.granted,
                                onGrant = permission::request,
                            )
                        }
                    } else {
                        items(results, key = { it.id }) { row ->
                            ShareRowItem(
                                row = row,
                                checked = row.id in selected,
                                checking = row.id in checking,
                                unreachable = reachable[row.id] == false,
                                materialYou = settings.materialYou,
                                onToggle = { toggle(row) },
                            )
                        }
                    }
                    // Under the list rather than only on an empty one. With conversations to show,
                    // the empty state never appears — so the offer to look in the address book had
                    // nowhere to be made, and sharing with somebody you have not messaged before
                    // was quietly impossible for anyone who had ever declined the permission.
                    if (!permission.granted) {
                        item { ContactsFooter(onGrant = permission::request) }
                    }
                }
            }

            // Named rather than drawn: a document has no thumbnail, and an empty grey square where
            // a picture would be says less than the file's own name does.
            if (files.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                        .frostedFill(RoundedCornerShape(14.dp), blur = ControlBlur, tint = ControlTint)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        describeFiles(files),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Inside the column rather than floating over it, so it always lands just above the
            // send bar — the bar's height follows the conversation's pinch zoom, and a snackbar
            // positioned by a hand-picked offset would sit on top of it at one text size and
            // halfway up the list at another.
            SnackbarHost(hostState = snackbar)

            ChatComposer(
                // Shut until there is somebody to send to and the files are ours to send. Copying
                // is quick, but a share of twenty photographs is not instant, and a bar that took
                // the message before its own attachments existed would send the words alone.
                // Also shut while Apple is still being asked about somebody just ticked: the
                // answer may be no, and a share that went the instant it was requested would have
                // sent to them anyway — silently, since a message to somebody unreachable falls
                // back to a local-only one that never leaves the phone.
                enabled = chosen.isNotEmpty() && ready && !sending && checking.isEmpty(),
                text = text,
                onTextChange = { text = it },
                onSend = { send() },
                sendBarType = settings.bubbleType.sendBar(),
                // The pictures ride in the bar's own strip, which is where a draft's pictures live
                // everywhere else in the app — so they can be looked at and dropped one at a time
                // rather than being an all-or-nothing block above the list.
                draftMedia = media.map { it.uri },
                draftAttachments = files.size,
                onRemoveDraftMedia = { uri -> staged = staged?.filterNot { it.uri == uri } },
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}

/** The bar: a cross rather than a back arrow, because leaving here leaves the share entirely. */
@Composable
private fun ShareField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(FieldHeight)
            .frostedFill(FieldShape, blur = ControlBlur, tint = ControlTint)
            .padding(start = 4.dp, end = 6.dp),
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel sharing")
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    "Search people and chats",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, contentDescription = "Clear search")
            }
        }
    }
}

/**
 * One row of the checklist.
 *
 * The tick is drawn on the avatar rather than in a box of its own, the way the recipient picker
 * marks a chosen contact — one selected-ness in the app, said the same way twice.
 */
@Composable
private fun ShareRowItem(
    row: ShareRow,
    checked: Boolean,
    /** Apple is being asked about this one right now. */
    checking: Boolean,
    /** Apple has said no. The row keeps its place in the list but stops being a choice. */
    unreachable: Boolean,
    materialYou: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(if (checked) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            // Faded and deaf together. A row that still looked live but refused to tick would read
            // as the list having broken rather than as an answer about this person.
            .alpha(if (unreachable) DisabledAlpha else 1f)
            .clickable(enabled = !unreachable, onClick = onToggle)
            .padding(horizontal = SectionInset, vertical = 8.dp),
    ) {
        Box {
            val chat = row.chat
            if (chat != null) {
                ChatAvatar(chat = chat, size = 48.dp)
            } else {
                Avatar(
                    initials = row.initials,
                    color = if (materialYou) row.color.toComposeColor() else MaterialTheme.colorScheme.primaryContainer,
                    size = 48.dp,
                    photoUri = row.photoUri,
                    labelColor = if (materialYou) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (checked || checking) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    // The badge is already there and already filled — asking Apple only changes
                    // what is drawn inside it, so a slow answer never makes the row move.
                    if (checking) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 1.5.dp,
                            modifier = Modifier.size(11.dp),
                        )
                    } else {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The reason replaces the preview rather than sitting under it: the row is one line of
            // supporting text tall, and the only thing worth saying about somebody you cannot pick
            // is why not.
            if (unreachable) {
                Text(
                    "Not on iMessage",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            } else if (row.subtitle.isNotBlank()) {
                Text(
                    row.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The way to the rest of the address book, for a list that is only showing conversations. */
@Composable
private fun ContactsFooter(onGrant: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onGrant)
            .padding(horizontal = SectionInset, vertical = 14.dp),
    ) {
        Icon(
            Icons.Filled.PersonAdd,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Share with someone else",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Allow access to your contacts to reach people you haven't messaged yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShareEmptyState(searching: Boolean, contactsGranted: Boolean, onGrant: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 56.dp, start = 32.dp, end = 32.dp),
    ) {
        Text(
            if (searching) "Nobody by that name" else "No conversations yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            when {
                searching -> "Try a different name."
                !contactsGranted -> "Allow access to your contacts to share with someone you haven't messaged yet."
                else -> "Start a conversation first, then anything you share will have somewhere to go."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (!searching && !contactsGranted) {
            Text(
                "Allow access",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp).clickable(onClick = onGrant),
            )
        }
    }
}

/**
 * Every conversation, then everyone in the address book who is not already one of them.
 *
 * Deduplicated on the id a one-to-one conversation would have — the contact's canonicalized handle
 * — so somebody you talk to daily appears once, as their conversation, rather than twice with one
 * of the two entries silently doing nothing.
 */
private fun shareRows(chats: List<Chat>, contacts: List<Contact>): List<ShareRow> {
    val existing = chats.map { it.id }.toSet()
    val conversations = chats.map { chat ->
        ShareRow(
            id = chat.id,
            name = chat.name,
            subtitle = if (chat.group) groupSubtitle(chat) else chat.preview,
            identity = ChatIdentity(chat.id, chat.name, chat.color, chat.initials, chat.group, chat.members, chat.photoUri),
            addresses = emptyList(),
            chat = chat,
            initials = chat.initials,
            color = chat.color,
            photoUri = chat.photoUri,
        )
    }
    val fresh = contacts.mapNotNull { contact ->
        val address = contact.about?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val id = Handles.key(Handles.normalize(address))
        if (id in existing) return@mapNotNull null
        ShareRow(
            id = id,
            name = contact.name,
            subtitle = address,
            identity = ChatIdentity(id, contact.name, contact.color, contact.initials, false, null, contact.photoUri),
            addresses = listOf(address),
            initials = contact.initials,
            color = contact.color,
            photoUri = contact.photoUri,
        )
    }
    // One contact can hold two numbers that canonicalize to the same handle; the first wins, as it
    // does everywhere else the address book is folded down to conversations.
    return conversations + fresh.distinctBy { it.id }
}

private fun groupSubtitle(chat: Chat): String =
    chat.members.take(3).joinToString(", ") { it.name.substringBefore(' ') }.ifEmpty { "Group" }

/** The name while there is one file, a count once there are several. */
private fun describeFiles(files: List<StagedShare>): String {
    val single = files.singleOrNull()?.attachment as? Attachment.File
        ?: return files.size.toString() + " files"
    return listOfNotNull(single.name, formatBytes(single.size)).joinToString(" · ")
}

/**
 * Whether a staged copy is something the send bar can draw a thumbnail of.
 *
 * Asked of the *copy*, which is a `file://` uri with no provider to answer for its type — so the
 * extension the copy was given is what settles it. See `AttachmentStaging.stageShared`.
 */
private fun isMediaUri(context: Context, uri: Uri): Boolean {
    val mime = context.contentResolver.getType(uri)
        ?: MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(uri.toString().substringAfterLast('.', "").lowercase())
    return mime?.startsWith("image") == true || mime?.startsWith("video") == true || isVideoUri(context, uri)
}

/**
 * A staged copy described as a file bubble.
 *
 * The name has the staging prefix taken back off it: the copy is stored under a nanosecond stamp so
 * two shares of the same file cannot collide, and that stamp is bookkeeping rather than anything
 * the recipient should be shown.
 */
private fun fileAttachment(context: Context, uri: Uri): Attachment.File {
    val path = uri.path
    val name = path?.substringAfterLast('/')?.substringAfter('-')?.takeIf { it.isNotBlank() } ?: "Attachment"
    val size = path?.let { File(it).length() }?.takeIf { it > 0 }
    return Attachment.File(
        uri = uri.toString(),
        name = name,
        size = size,
        mimeType = context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()),
    )
}
