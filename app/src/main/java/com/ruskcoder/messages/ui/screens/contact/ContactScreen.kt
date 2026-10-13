package com.ruskcoder.messages.ui.screens.contact

import android.content.ContentUris
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import com.ruskcoder.messages.ui.components.frostedFill
import com.ruskcoder.messages.ui.components.ControlTint
import com.ruskcoder.messages.ui.components.ControlBlur
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.data.model.ChatThemeKey
import com.ruskcoder.messages.data.model.Contact
import com.ruskcoder.messages.data.model.initialsFor
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.ui.components.Avatar
import com.ruskcoder.messages.data.repository.ContactsRepository
import com.ruskcoder.messages.ui.components.ChatColorPicker
import com.ruskcoder.messages.ui.components.DetailHeaderHeight
import com.ruskcoder.messages.ui.components.FrostedDetailPage
import com.ruskcoder.messages.ui.components.ChatSettingsSection
import com.ruskcoder.messages.ui.components.ChatSharedMediaSection
import com.ruskcoder.messages.ui.components.sharedMediaOf
import com.ruskcoder.messages.ui.components.SectionInset
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.util.toComposeColor

private data class ContactAction(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String)

/**
 * The row of buttons under the header.
 *
 * "Contact" is the way out to the phone's own address book — this page is about a conversation, and
 * everything that is not about the conversation (the number, the email, the birthday, editing any
 * of it) already has a far better home in Contacts than a copy of it here would be. It appears only
 * when the handle actually matches somebody in the address book; there is nothing to open for a
 * bare number nobody has saved.
 */
private val ACTIONS = listOf(
    ContactAction(Icons.Filled.Call, "Audio"),
    ContactAction(Icons.Filled.Videocam, "Video"),
    ContactAction(Icons.Filled.Search, "Search"),
    ContactAction(Icons.Filled.Person, "Contact"),
)
/** Daylight between one panel and the next, matching the group page's. */
private val SectionGap = 14.dp

/** One-to-one contact info page. Ported from `contact/[id].tsx`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactScreen(
    chatId: String,
    chatRepository: ChatRepository,
    contactsRepository: ContactsRepository,
    onBack: () -> Unit,
    /** Back to the conversation with find-in-chat up — what the Search action does. */
    onOpenSearch: (String) -> Unit,
    /** The full search page, confined to this conversation — what "Links & More" opens. */
    onBrowseChat: (String) -> Unit,
) {
    val settings = LocalSettings.current
    val chats by chatRepository.chats.collectAsStateWithLifecycle()
    val chat = chats.find { it.id == chatId }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    LaunchedEffect(Unit) { contacts = contactsRepository.loadContacts() }
    // Matched by handle first. A one-to-one conversation is filed under the other person's
    // canonicalized handle, not under their address-book row id, so comparing the two found nothing
    // and the page fell back to the chat's stored name for everything — no photo, no number, and
    // nothing to hand Contacts. The id comparison stays for a chat created before that was true.
    val contact = remember(contacts, chatId) {
        contactsRepository.contactForHandle(chatId, contacts) ?: contacts.find { it.id == chatId }
    }
    val context = LocalContext.current

    /**
     * Open this person in whichever app owns the address book.
     *
     * Addressed by contact id rather than by a lookup key, because the id is what was read out of
     * the provider in the first place. Guarded: a phone with no contacts app to answer the intent
     * would otherwise take the whole screen down with it.
     */
    fun openInContacts() {
        val id = contact?.id?.toLongOrNull() ?: return
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { Toast.makeText(context, "No contacts app to open this in", Toast.LENGTH_SHORT).show() }
    }

    var showColorPicker by remember { mutableStateOf(false) }

    // The pictures actually in this conversation, newest first. The strip used to be four grey
    // squares and the number beside it a constant, which told you nothing about the conversation
    // you were looking at and was the same on every one of them.
    // Every message, not the chat screen's window — shared media is the whole conversation's.
    val thread by remember(chatId) { chatRepository.allMessagesFlow(chatId) }.collectAsStateWithLifecycle(emptyList())
    val media = remember(thread) { sharedMediaOf(thread) }

    val name = contact?.name ?: chat?.name ?: "Unknown"
    val initials = contact?.initials ?: chat?.initials ?: initialsFor(name)
    val subtitle = contact?.about ?: if (chat?.group == true) "Group info" else "Contact info"
    val fallbackColor = (contact?.color ?: chat?.color)?.toComposeColor() ?: MaterialTheme.colorScheme.primaryContainer
    val avatarColor = if (settings.materialYou) fallbackColor else MaterialTheme.colorScheme.primaryContainer
    val photoUri = contact?.photoUri ?: chat?.photoUri

    // Held here rather than inside the scroll modifier: the header reads it to work out how far
    // it has collapsed, and a state created down there is not something this page could hand it.
    val scroll = rememberScrollState()

    // A page in its own right, over a frosted copy of the conversation it was opened from — read
    // as something opened *about* this chat rather than as somewhere else entirely, without the
    // dialog window that used to buy that look and cost the page its menus and its transition.
    //
    // The picture and the name are the page's header *and* its bar title — one pair that travels
    // between the two as you scroll, rather than one set fading out while another fades in.
    FrostedDetailPage(
        name = name,
        subtitle = subtitle,
        scroll = scroll,
        avatar = { size ->
            Avatar(
                initials = initials,
                color = avatarColor,
                size = size,
                photoUri = photoUri,
                labelColor = if (settings.materialYou) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        // No overflow menu. Both things it held are on the page itself now — Search is one of the
        // three buttons under the header, and the colours are a row in the settings panel — and a
        // menu whose every entry is duplicated a thumb's width below it is a menu nobody opens.
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(bottom = 48.dp)) {
            // The room the expanded header stands in. It is drawn over the page rather than in it,
            // so this is the only thing holding its place — and holding it exactly, or the header
            // and the content would drift apart as one scrolled and the other collapsed.
            Spacer(modifier = Modifier.height(DetailHeaderHeight))

            Row(
                // Tighter than it was: a fourth button does not fit beside three 76dp ones with
                // 24dp of air between them, and the row would have wrapped a label onto two lines.
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Nothing to open for a number nobody has saved, so the button is not offered.
                ACTIONS.filterNot { it.label == "Contact" && contact == null }.forEach { action ->
                    val onAction: () -> Unit = when (action.label) {
                        // Find-in-chat, back in the conversation — the same place the row this
                        // page was opened from would have taken you.
                        "Search" -> ({ onOpenSearch(chatId) })
                        "Contact" -> ::openInContacts
                        // Audio and Video have nothing behind them yet; they stay as the shape of
                        // the page rather than pretending to do something.
                        else -> ({})
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            // Glass rather than a solid chip: these sit on a frosted page, and a
                            // filled surface on top of it was the one thing on the screen the
                            // conversation behind did not show through.
                            modifier = Modifier
                                .width(72.dp)
                                .height(46.dp)
                                .frostedFill(RoundedCornerShape(23.dp), blur = ControlBlur, tint = ControlTint)
                                .clickable(onClick = onAction),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(action.icon, contentDescription = action.label, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                        Text(action.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            ChatSharedMediaSection(media = media, onBrowse = { onBrowseChat(chatId) })

            Spacer(modifier = Modifier.height(SectionGap))

            ChatSettingsSection(
                chat = chat,
                chatRepository = chatRepository,
                onOpenTheme = { showColorPicker = true },
            )
        }
    }

    if (showColorPicker) {
        ChatColorPicker(
            selected = chat?.theme ?: ChatThemeKey.DEFAULT,
            selectedHue = chat?.themeHue ?: ChatThemeKey.DEFAULT_HUE,
            onDismiss = { showColorPicker = false },
            onConfirm = { theme, hue -> showColorPicker = false; chatRepository.setChatTheme(chatId, theme, hue) },
        )
    }
}
