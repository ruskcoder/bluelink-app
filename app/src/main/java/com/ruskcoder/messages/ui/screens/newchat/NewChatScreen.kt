package com.ruskcoder.messages.ui.screens.newchat

import android.Manifest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.imessage.Handles
import com.ruskcoder.messages.imessage.IMessageSender
import java.util.UUID
import com.ruskcoder.messages.data.model.ChatIdentity
import com.ruskcoder.messages.data.model.Contact
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.ReplyPreview
import com.ruskcoder.messages.data.model.sendBar
import com.ruskcoder.messages.data.model.colorFor
import com.ruskcoder.messages.data.model.initialsFor
import com.ruskcoder.messages.data.model.preview
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.ui.components.frostedFill
import com.ruskcoder.messages.ui.components.FrostedBackground
import com.ruskcoder.messages.ui.components.ControlTint
import com.ruskcoder.messages.ui.components.ControlBlur
import com.ruskcoder.messages.ui.components.Avatar
import com.ruskcoder.messages.data.repository.ContactsRepository
import com.ruskcoder.messages.permissions.rememberPermissionState
import com.ruskcoder.messages.ui.components.ChatComposer
import com.ruskcoder.messages.ui.components.MessageThread
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.util.toComposeColor

private fun firstName(name: String): String = name.substringBefore(' ').filter { it.isLetterOrDigit() || it == '\'' }

/**
 * The green everything that is not iMessage has always been drawn in.
 *
 * Written here rather than taken from the theme on purpose: it does not belong to the palette and
 * must not follow it. It is a statement about which service a person is on, and it has to read the
 * same on every wallpaper and in both light and dark — a "not iMessage" green that turned mauve
 * under Material You would be saying nothing at all.
 */
private val SmsGreen = Color(0xFF34C759)

/** Long enough to read as a pill changing colour rather than a pill being replaced. */
private const val PILL_TINT_MS = 220

/** Recipient picker + first message. Ported from `new-chat.tsx`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewChatScreen(
    chatRepository: ChatRepository,
    /** Creates the conversation on Apple's side when the chosen people can be reached there. */
    imessageSender: IMessageSender,
    contactsRepository: ContactsRepository,
    onBack: () -> Unit,
    onChatCreated: (String) -> Unit,
) {
    val settings = LocalSettings.current
    val permission = rememberPermissionState(Manifest.permission.READ_CONTACTS)
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var loadingContacts by remember { mutableStateOf(true) }
    LaunchedEffect(permission.granted) {
        if (permission.granted) {
            contacts = contactsRepository.loadContacts()
            loadingContacts = false
        } else {
            loadingContacts = false
        }
    }

    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(listOf<Contact>()) }

    /**
     * Who among the people picked so far can be reached on iMessage: their canonical handle,
     * against the answer Apple gave for it.
     *
     * Accumulated rather than recomputed, and keyed by handle rather than by contact, so removing
     * somebody and putting them back does not ask again — and so two address-book entries for the
     * same number share one answer. An address nobody has asked about is simply absent, which is a
     * third state and not the same as false: it is what the bar waits on rather than what it
     * refuses for.
     */
    var reachable by remember { mutableStateOf(mapOf<String, Boolean>()) }
    /** Raised for as long as a lookup is out, which is the other thing the bar waits on. */
    var checking by remember { mutableStateOf(false) }
    var composerText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<ReplyPreview?>(null) }

    val q = query.trim()
    val results = remember(contacts, q) { if (q.isEmpty()) contacts else contacts.filter { it.name.contains(q, ignoreCase = true) } }
    val isGroup = selected.size > 1
    // With the search field empty the picking is done, so the recipient's own conversation preview
    // takes over the page. Typing again means more members are being added, which puts the list back.
    val showThread = selected.isNotEmpty() && q.isEmpty()

    var threadMessages by remember { mutableStateOf<List<Message>>(emptyList()) }
    // Keyed by the conversation's own id — the recipient's canonicalized handle — rather than by
    // their address-book row id, which is not what any conversation is filed under.
    val previewChatId = remember(isGroup, selected) {
        selected.singleOrNull()?.about?.takeIf { it.isNotBlank() }?.let { Handles.key(Handles.normalize(it)) }
    }
    LaunchedEffect(previewChatId) {
        threadMessages = emptyList()
        if (previewChatId != null) chatRepository.threadFlow(previewChatId).collect { threadMessages = it }
    }

    /**
     * Everyone picked who Apple has told us it cannot reach.
     *
     * A contact with no number or address at all is one of them without asking: there is nothing
     * to look up, and nothing to send to either.
     */
    val notOnIMessage = remember(selected, reachable) {
        selected.filter { contact ->
            val address = contact.about?.takeIf { it.isNotBlank() }
            if (address == null) true else reachable[Handles.key(Handles.normalize(address))] == false
        }
    }

    // Asked once per address, as people are added. Nothing is asked again on a removal, and a
    // failed or timed-out lookup leaves the address unknown rather than recording a "no" that was
    // never actually said — on a phone with no iMessage set up at all, every lookup fails, and
    // marking the whole address book unreachable there would take the send bar away for good.
    LaunchedEffect(selected) {
        val unknown = selected
            .mapNotNull { it.about?.takeIf { address -> address.isNotBlank() } }
            .filterNot { Handles.key(Handles.normalize(it)) in reachable }
            .distinct()
        if (unknown.isEmpty()) return@LaunchedEffect
        checking = true
        val answer = imessageSender.reachableTargets(unknown)
        checking = false
        if (answer != null) {
            reachable = reachable + unknown.associate { address ->
                val key = Handles.key(Handles.normalize(address))
                key to (key in answer)
            }
        }
    }

    fun toggle(contact: Contact) {
        selected = if (selected.any { it.id == contact.id }) selected.filterNot { it.id == contact.id } else selected + contact
        query = ""
    }

    /**
     * The conversation these recipients make, id and all.
     *
     * The id is settled *here* rather than by whatever Apple hands back, and it is settled to
     * exactly what the inbound path would derive for the same people — see `Handles.chatId`.
     *
     * For one person that is their handle, canonicalized. For a group it is a fresh identifier,
     * which is then also the identifier Apple's thread carries. Deriving it from the participants
     * instead is what merged a new group into somebody's existing chat: a group whose members are
     * not all reachable on iMessage comes back from `validateTargets` with one other person in it,
     * a set of one is a one-to-one by every rule in the app, and the "new group" was filed under
     * that person's own conversation and appended to their history. A group is a group because it
     * was made as one.
     */
    fun identityForSelection(): ChatIdentity? {
        if (selected.isEmpty()) return null
        if (isGroup) {
            val name = selected.joinToString(", ") { firstName(it.name) }
            val members = selected.map { GroupMember(it.id, it.name, it.initials, it.color, it.about, it.photoUri) }
            return ChatIdentity(UUID.randomUUID().toString(), name, colorFor(name), initialsFor(name), true, members)
        }
        val contact = selected.first()
        // The address-book row id only as a last resort: a contact with no number cannot be reached
        // on iMessage anyway, so the conversation is a local one and any stable id will do.
        val id = contact.about?.takeIf { it.isNotBlank() }?.let { Handles.key(Handles.normalize(it)) } ?: contact.id
        return ChatIdentity(id, contact.name, contact.color, contact.initials, false, null, contact.photoUri)
    }

    fun handleSend() {
        if (composerText.isBlank()) return
        // The bar is already shut in both of these cases; this is the same rule stated where the
        // send actually happens, so a stale press cannot slip past it.
        if (notOnIMessage.isNotEmpty() || checking) return
        val identity = identityForSelection() ?: return
        val body = composerText
        val quoted = replyingTo
        composerText = ""
        replyingTo = null

        // Straight there. Registering the conversation with Apple is a network round trip, and
        // waiting for it left the picker on screen with an empty composer for as long as it took —
        // which reads as the button having done nothing. The id is already known, so the
        // conversation can be opened now and registered behind it.
        onChatCreated(identity.id)
        imessageSender.startConversationAndSend(identity, selected.mapNotNull { it.about }, body, quoted)
    }

    // Edge-to-edge: the window is never resized for the keyboard, so the whole column gives way to
    // it — the RN screen's `KeyboardAvoidingView behavior="height"` around the same three rows.
    FrostedBackground {
      Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
      ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // No top padding. The button is 48dp tall around a centred glyph, and the field beside
            // it is 48dp for its first row too (a 32dp pill inside 8dp of padding) — so the two are
            // aligned by construction and the 4dp was pushing the arrow below the text it sits with.
            // Top-aligned rather than centred, so a second row of pills grows downward past a
            // button that stays put.
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .frostedFill(RoundedCornerShape(24.dp), blur = ControlBlur, tint = ControlTint)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (selected.isEmpty()) {
                    // Centred against the field's own line rather than nudged down by a hand-picked
                    // padding: the flow lays its items out from the top, so a 22dp glyph beside a
                    // 32dp input sat ten dip too high and the padding that "fixed" it was applied
                    // *inside* the icon's box, squashing the glyph instead of moving it. Given the
                    // input's height to sit in, the two line up because they are the same height.
                    Box(modifier = Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                selected.forEach { contact ->
                    // Green for somebody Apple cannot reach — the colour the platform has used to
                    // mean "this one is not iMessage" for fifteen years, said here while the
                    // recipients are still being picked rather than after a message has gone.
                    val offImessage = contact in notOnIMessage
                    val pill by animateColorAsState(
                        if (offImessage) SmsGreen else MaterialTheme.colorScheme.secondaryContainer,
                        tween(PILL_TINT_MS),
                        label = "recipientPill",
                    )
                    val ink = if (offImessage) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(32.dp)
                            // Capped so a long name can't take the whole line and push the input
                            // onto a second one.
                            .widthIn(max = 190.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(pill)
                            .padding(start = 12.dp, end = 8.dp),
                    ) {
                        Text(
                            contact.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = ink,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(modifier = Modifier.padding(start = 2.dp))
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove ${contact.name}",
                            tint = ink,
                            modifier = Modifier.size(16.dp).clickable { toggle(contact) },
                        )
                    }
                }
                // Takes whatever is left of the line the pills are on rather than demanding a fixed
                // width: a 90dp floor beside a pill didn't fit, so the flow wrapped the field to a
                // second row and the whole field doubled in height.
                Box(
                    modifier = Modifier.weight(1f, fill = false).widthIn(min = 56.dp).height(32.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty() && selected.isEmpty()) {
                        // No placeholder once a pill is showing, matching the RN source.
                        Text(
                            "Search name or number",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace && query.isEmpty() && selected.isNotEmpty()) {
                                selected = selected.dropLast(1)
                                true
                            } else {
                                false
                            }
                        },
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (showThread) {
                // Deliberately not [ChatWallpaper]: that paints the conversation's opaque backdrop,
                // which on this page is a slab of near-black dropped over the frosted glass the
                // rest of the screen is made of — and on an empty preview, which is most of them,
                // that slab is the entire page. The frost shows through instead, and going on to
                // the conversation is then a fade from the glass to the wallpaper rather than a cut
                // between two blacks.
                Box(modifier = Modifier.fillMaxSize()) {
                    MessageThread(
                        messages = threadMessages,
                        members = emptyList(),
                        swipeTimestampsEnabled = settings.swipeTimestamps,
                        selecting = false,
                        selectedIds = emptySet(),
                        onReply = { message, _ -> replyingTo = ReplyPreview(message.id, if (message.mine) "You" else selected.first().name, message.preview(), message.mine) },
                        onLongPress = {},
                        onDoubleTap = {},
                        onToggleSelect = {},
                    )
                }
                if (threadMessages.isEmpty()) {
                    Text(
                        if (isGroup) "New group with ${selected.joinToString(", ") { firstName(it.name) }}" else "No messages with ${firstName(selected.first().name)} yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        // Rests on the send bar, the way the inverted thread's empty state does.
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .frostedFill(RoundedCornerShape(10.dp), blur = ControlBlur, tint = ControlTint)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text(
                            if (q.isNotEmpty()) "Results for \"$q\"" else "Contacts on this phone",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    if (loadingContacts || !permission.granted) {
                        item { ContactsEmptyState(granted = permission.granted, loading = loadingContacts, query = q, onGrant = permission::request) }
                    } else if (results.isEmpty()) {
                        item { ContactsEmptyState(granted = true, loading = false, query = q, onGrant = permission::request) }
                    } else {
                        items(results, key = { it.id }) { contact ->
                            val isSelected = selected.any { it.id == contact.id }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                    .clickable { toggle(contact) }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Box {
                                    Avatar(
                                        initials = contact.initials,
                                        color = if (settings.materialYou) contact.color.toComposeColor() else MaterialTheme.colorScheme.primaryContainer,
                                        size = 48.dp,
                                        photoUri = contact.photoUri,
                                        labelColor = if (settings.materialYou) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    if (isSelected) {
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
                                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(13.dp))
                                        }
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(contact.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        contact.about ?: "Hey there! I am using WhatsApp.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // No "+": there is nothing to attach to a message that has no conversation yet, and the
        // button opened a sheet whose every tile did nothing.
        //
        // And no fill behind the bar. It used to paint the wallpaper's own backdrop under itself so
        // that it did not read as a slab of theme colour under the thread — but the page is frosted
        // glass now, and painting anything opaque across the bottom of it put back exactly the seam
        // that was being avoided.
        // Why the bar has gone quiet, named. A control that has simply stopped answering is a bug
        // as far as anyone using it is concerned; the same control with a line above it saying who
        // is in the way is an instruction.
        AnimatedVisibility(
            visible = notOnIMessage.isNotEmpty(),
            enter = fadeIn(tween(PILL_TINT_MS)) + expandVertically(tween(PILL_TINT_MS)),
            exit = fadeOut(tween(140)) + shrinkVertically(tween(160)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .frostedFill(RoundedCornerShape(14.dp), blur = ControlBlur, tint = ControlTint)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(SmsGreen))
                Text(
                    // Named while there is a name to say: "Alex isn't on iMessage" is something you
                    // can act on, where "someone isn't" sends you back through the pills to find out
                    // which one.
                    if (notOnIMessage.size == 1) {
                        "${firstName(notOnIMessage.first().name)} isn't on iMessage — remove them to send"
                    } else {
                        "${notOnIMessage.size} people aren't on iMessage — remove them to send"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        ChatComposer(
            // Shut while anyone picked is off iMessage, and while we are still finding out. There
            // is nothing to send to a green pill — this app has no SMS of its own to fall back to —
            // so a bar that took the message would be taking it nowhere.
            enabled = notOnIMessage.isEmpty() && !checking,
            text = composerText,
            onTextChange = { composerText = it },
            onSend = ::handleSend,
            // No microphone. There is no conversation to record into yet, and the button offered
            // one anyway — so the bar is a Send button that waits for something to send.
            sendBarType = settings.bubbleType.sendBar(),
            replyPreview = replyingTo,
            onCancelReply = { replyingTo = null },
            modifier = Modifier.padding(bottom = 6.dp),
        )
      }
    }
}

@Composable
private fun ContactsEmptyState(granted: Boolean, loading: Boolean, query: String, onGrant: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 64.dp, start = 32.dp, end = 32.dp),
    ) {
        when {
            loading -> {
                CircularProgressIndicator(modifier = Modifier.padding(bottom = 8.dp))
                Text("Loading your contacts…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            !granted -> {
                Icon(Icons.Filled.PersonOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                Text("Contacts aren't available", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "Allow access to your contacts to pick someone to message.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(onClick = onGrant, modifier = Modifier.padding(top = 12.dp)) { Text("Allow access") }
            }
            else -> {
                Text(if (query.isNotEmpty()) "No contacts found" else "No contacts on this phone", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (query.isNotEmpty()) "Try a different name or number" else "Add someone to your address book to start a chat.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
