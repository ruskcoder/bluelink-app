package com.ruskcoder.messages.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.MessageHit
import com.ruskcoder.messages.data.model.colorFor
import com.ruskcoder.messages.data.model.initialsFor
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.data.repository.RecentSearch
import com.ruskcoder.messages.data.repository.RecentSearches
import com.ruskcoder.messages.ui.components.Avatar
import com.ruskcoder.messages.ui.components.ControlBlur
import com.ruskcoder.messages.ui.components.ControlTint
import com.ruskcoder.messages.ui.components.FrostedBackground
import com.ruskcoder.messages.ui.components.MediaViewer
import com.ruskcoder.messages.ui.components.SectionInset
import com.ruskcoder.messages.ui.components.ViewableMedia
import com.ruskcoder.messages.ui.components.frostedFill
import com.ruskcoder.messages.util.formatChatTime
import com.ruskcoder.messages.util.shareMedia
import com.ruskcoder.messages.util.toComposeColor
import com.ruskcoder.messages.ui.components.AppDialog

/**
 * The kinds a search can be narrowed to.
 *
 * [UNREAD] and [GROUPS] are questions about conversations rather than about what is in one, so they
 * are only offered when the search spans all of them.
 */
private enum class SearchFilter(val label: String, val icon: ImageVector, val everywhereOnly: Boolean = false) {
    UNREAD("Unread", Icons.Outlined.MailOutline, everywhereOnly = true),
    GROUPS("Groups", Icons.Outlined.Groups, everywhereOnly = true),
    IMAGES("Images", Icons.Outlined.Image),
    VIDEOS("Videos", Icons.Outlined.Videocam),
    LINKS("Links", Icons.Outlined.Link),
    DOCS("Docs", Icons.Outlined.Description),
    /** Only what you wrote — finding the thing you know you said, somewhere. */
    FROM_ME("From me", Icons.Outlined.Person, everywhereOnly = true),
}

/** What a filter searches storage for when there is no query typed alongside it. */
private fun seedFor(filter: SearchFilter?): String = when (filter) {
    SearchFilter.IMAGES, SearchFilter.VIDEOS, SearchFilter.DOCS -> "."
    SearchFilter.LINKS -> "http"
    else -> ""
}

/** The bar: tall enough to hold a 24dp glyph and a line of text, and no taller. */
private val FieldHeight = 52.dp
private val FieldShape = RoundedCornerShape(FieldHeight / 2)

/** How many of each kind a section shows before "See all" has anything to reveal. */
private const val IMAGE_PREVIEW_COUNT = 8
private const val LIST_PREVIEW_COUNT = 3

/** Thumbnails per row in the image grid, matching the four-up strip the details page shows. */
private const val GRID_COLUMNS = 4

private data class ResultRow(
    val id: String,
    val chatId: String,
    val title: String,
    val subtitle: String,
    val time: String?,
    val color: String,
    val initials: String,
    val photoUri: String? = null,
    /** The message a message hit is, so opening it can land on it. Null for a conversation row. */
    val messageId: String? = null,
)

/** One picture or clip in the conversation, with enough of its message to caption and open it. */
private data class MediaHit(
    val messageId: String,
    val uri: String,
    val video: Boolean,
    val caption: String?,
    val time: String,
)

/** A link or a file: a line of text, who sent it, and when. */
private data class ShareHit(
    val messageId: String,
    val headline: String,
    val detail: String,
    val time: String,
)

private val LinkRef = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

/**
 * Search, either across every conversation or confined to one.
 *
 * Confined, it is also a browser: with nothing typed it lists what the conversation actually holds —
 * its pictures, its links, its files — rather than an empty state asking for a keyword. That is what
 * the details page's "Links & More" opens, and it is why the sections here are laid out as sections
 * with their own "See all" rather than as one flat list of hits.
 *
 * The bar is one frosted pill with the back button inside it. It used to be Material's `SearchBar`,
 * which brings a 56dp container, its own insets and its own docked/expanded state machine — none of
 * which fit a screen that *is* the expanded search, and all of which fought the frost it was asked
 * to wear.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    chatRepository: ChatRepository,
    scopedChatId: String?,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    /**
     * Opens a conversation at one message rather than at its newest — what a hit is for. A result
     * that dropped you at the bottom of a long conversation left you to find the message again.
     */
    onOpenMessage: (chatId: String, messageId: String) -> Unit = { chatId, _ -> onOpenChat(chatId) },
) {
    val context = LocalContext.current
    val chats by chatRepository.chats.collectAsStateWithLifecycle()

    val scopedChat = chats.find { it.id == scopedChatId }
    var query by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf<SearchFilter?>(null) }
    var recents by remember { mutableStateOf(RecentSearches.load(context)) }
    var clearDialogOpen by remember { mutableStateOf(false) }
    var messageHits by remember { mutableStateOf<List<MessageHit>>(emptyList()) }
    var viewing by remember { mutableStateOf<ViewableMedia?>(null) }
    /**
     * The grid the open picture was tapped in, so the viewer can be swiped through the rest of it.
     *
     * The grid rather than everything shared: opening a picture from the images section and coming
     * out the other end in the middle of the videos would be a stack nobody assembled. Held as its
     * own list because the sections are filtered and paged, and what is beside a picture is what
     * was beside it on screen.
     */
    var viewingStack by remember { mutableStateOf<List<MediaHit>>(emptyList()) }

    val q = query.trim()
    val scoped = scopedChatId != null

    // ---------------------------------------------------------------------------------------
    // Confined to one conversation: everything comes out of the thread itself, so a picture is a
    // real picture rather than a filename that matched a regex.
    // ---------------------------------------------------------------------------------------
    // The whole conversation rather than the window the chat screen keeps: a search confined to a
    // chat has to reach the photo sent last year, not only the last few hundred messages.
    val thread by remember(scopedChatId) { chatRepository.allMessagesFlow(scopedChatId.orEmpty()) }
        .collectAsStateWithLifecycle(emptyList())

    val allMedia: List<MediaHit> = remember(thread) {
        thread.asReversed().mapNotNull { message ->
            val attachment = (message as? Message.AttachmentMessage)?.attachment
            when (attachment) {
                is Attachment.Image -> MediaHit(message.id, attachment.uri, false, attachment.caption, message.time)
                is Attachment.Video -> MediaHit(message.id, attachment.uri, true, attachment.caption, message.time)
                else -> null
            }
        }.filter { it.uri.isNotBlank() }
    }
    val allLinks: List<ShareHit> = remember(thread) {
        thread.asReversed().flatMap { message ->
            val text = (message as? Message.Text)?.text ?: return@flatMap emptyList<ShareHit>()
            LinkRef.findAll(text)
                .map { match -> ShareHit(message.id, match.value, hostOf(match.value), formatChatTime(message.createdAt)) }
                .toList()
        }
    }
    val allDocs: List<ShareHit> = remember(thread) {
        thread.asReversed().mapNotNull { message ->
            val file = ((message as? Message.AttachmentMessage)?.attachment as? Attachment.File) ?: return@mapNotNull null
            ShareHit(message.id, file.name, if (message.mine) "You" else "Them", formatChatTime(message.createdAt))
        }
    }
    val scopedText: List<ShareHit> = remember(thread, q) {
        if (q.isEmpty()) emptyList()
        else thread.asReversed().mapNotNull { message ->
            val text = (message as? Message.Text)?.text?.takeIf { it.contains(q, ignoreCase = true) }
                ?: return@mapNotNull null
            ShareHit(message.id, text, if (message.mine) "You" else scopedChat?.name.orEmpty(), message.time)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Everywhere: the stored index, which is text and therefore only ever names its media.
    // ---------------------------------------------------------------------------------------
    val storeQuery = q.lowercase().ifEmpty { seedFor(activeFilter) }
    LaunchedEffect(storeQuery, scoped) {
        messageHits = if (scoped || storeQuery.isEmpty()) emptyList() else chatRepository.searchMessages(storeQuery)
    }
    val searchingEverywhere = !scoped && (q.isNotEmpty() || activeFilter != null)
    val chatMatches = remember(chats, q, activeFilter, searchingEverywhere) {
        if (!searchingEverywhere) emptyList()
        else chats
            .filter { chat -> q.isEmpty() || chat.name.contains(q, ignoreCase = true) }
            .filter { chat ->
                when (activeFilter) {
                    SearchFilter.UNREAD -> chat.unread > 0
                    SearchFilter.GROUPS -> chat.group
                    null -> true
                    // Nothing about a conversation makes it a picture, so a media filter takes the
                    // Chats section out rather than emptying it.
                    else -> false
                }
            }
            .map { ResultRow("chat-${it.id}", it.id, it.name, it.preview, it.time, it.color, it.initials, it.photoUri) }
    }
    val messageMatches = remember(messageHits, chats, activeFilter) {
        messageHits
            .filter { hit -> activeFilter != SearchFilter.FROM_ME || hit.mine }
            .mapNotNull { hit ->
                val chat = chats.find { it.id == hit.chatId } ?: return@mapNotNull null
                ResultRow(
                    "message-${hit.messageId}", hit.chatId, chat.name, hit.text, formatChatTime(hit.createdAt),
                    chat.color, chat.initials, chat.photoUri, messageId = hit.messageId,
                )
            }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    fun openChat(chatId: String, label: String, color: String, initials: String, photoUri: String?) {
        recents = RecentSearches.push(context, RecentSearch(chatId, label, chatId, color, initials, photoUri))
        onOpenChat(chatId)
    }
    fun submitSearch() {
        val label = query.trim()
        if (label.isEmpty()) return
        keyboard?.hide()
        recents = RecentSearches.push(context, RecentSearch("q-$label", label, null, colorFor(label), initialsFor(label)))
    }

    fun shows(filter: SearchFilter) = activeFilter == null || activeFilter == filter

    FrostedBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column(modifier = Modifier.statusBarsPadding()) {
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = if (scopedChat != null) "Search conversation" else "Search messages",
                        onBack = onBack,
                        onSearch = ::submitSearch,
                    )
                    // One scrolling line of chips rather than a wrapping block: six of them wrap to
                    // two ragged rows on a phone, and the ragged edge is what read as misaligned.
                    // No chip for the conversation itself — the page was opened from inside it and
                    // its own name on a pill was the one thing on the bar that did nothing useful.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = SectionInset, vertical = 10.dp),
                    ) {
                        SearchFilter.entries
                            .filter { !it.everywhereOnly || !scoped }
                            .forEach { filter ->
                                val selected = activeFilter == filter
                                FilterChip(
                                    selected = selected,
                                    onClick = { activeFilter = if (selected) null else filter },
                                    label = { Text(filter.label, maxLines = 1) },
                                    leadingIcon = {
                                        Icon(filter.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                                    },
                                )
                            }
                    }
                }
            },
        ) { padding ->
            // The keyboard is up for most of this screen's life, so the results give way to it
            // rather than scrolling underneath it. `consumeWindowInsets` is what stops `imePadding`
            // from counting the navigation bar a second time on top of the scaffold's own padding.
            Column(
                modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 48.dp),
            ) {
                if (scoped) {
                    val media = allMedia.filter { hit -> q.isEmpty() || hit.caption?.contains(q, ignoreCase = true) == true }
                    val images = media.filterNot { it.video }
                    val videos = media.filter { it.video }
                    val links = allLinks.filter { hit -> q.isEmpty() || hit.headline.contains(q, ignoreCase = true) }
                    val docs = allDocs.filter { hit -> q.isEmpty() || hit.headline.contains(q, ignoreCase = true) }

                    if (q.isNotEmpty() && activeFilter == null) {
                        Section("Messages", scopedText.size, seeAll = null)
                        if (scopedText.isEmpty()) {
                            EmptySection("No matching messages")
                        } else {
                            scopedText.forEach { hit ->
                                TextResultRow(hit) { onOpenMessage(scopedChatId, hit.messageId) }
                            }
                        }
                    }

                    if (shows(SearchFilter.IMAGES)) {
                        Section(
                            "Images",
                            images.size,
                            seeAll = if (activeFilter == null && images.size > IMAGE_PREVIEW_COUNT) {
                                { activeFilter = SearchFilter.IMAGES }
                            } else null,
                        )
                        if (images.isEmpty()) {
                            EmptySection("Nothing shared yet", Icons.Outlined.Image)
                        } else {
                            val shown = if (activeFilter == null) images.take(IMAGE_PREVIEW_COUNT) else images
                            MediaGrid(
                                items = shown,
                                onOpen = { hit -> viewing = hit.toViewable(); viewingStack = shown },
                            )
                        }
                    }

                    if (shows(SearchFilter.VIDEOS)) {
                        Section(
                            "Videos",
                            videos.size,
                            seeAll = if (activeFilter == null && videos.size > IMAGE_PREVIEW_COUNT) {
                                { activeFilter = SearchFilter.VIDEOS }
                            } else null,
                        )
                        if (videos.isEmpty()) {
                            EmptySection("No videos here", Icons.Outlined.Videocam)
                        } else {
                            val shown = if (activeFilter == null) videos.take(IMAGE_PREVIEW_COUNT) else videos
                            MediaGrid(
                                items = shown,
                                onOpen = { hit -> viewing = hit.toViewable(); viewingStack = shown },
                            )
                        }
                    }

                    if (shows(SearchFilter.LINKS)) {
                        Section(
                            "Links",
                            links.size,
                            seeAll = if (activeFilter == null && links.size > LIST_PREVIEW_COUNT) {
                                { activeFilter = SearchFilter.LINKS }
                            } else null,
                        )
                        if (links.isEmpty()) {
                            EmptySection("No links here", Icons.Outlined.Link)
                        } else {
                            ShareCards(
                                items = if (activeFilter == null) links.take(LIST_PREVIEW_COUNT) else links,
                                icon = Icons.Outlined.Link,
                                onOpen = { hit -> onOpenMessage(scopedChatId, hit.messageId) },
                            )
                        }
                    }

                    if (shows(SearchFilter.DOCS)) {
                        Section(
                            "Docs",
                            docs.size,
                            seeAll = if (activeFilter == null && docs.size > LIST_PREVIEW_COUNT) {
                                { activeFilter = SearchFilter.DOCS }
                            } else null,
                        )
                        if (docs.isEmpty()) {
                            EmptySection("No files here", Icons.Outlined.Description)
                        } else {
                            ShareCards(
                                items = if (activeFilter == null) docs.take(LIST_PREVIEW_COUNT) else docs,
                                icon = Icons.Outlined.Description,
                                onOpen = { hit -> onOpenMessage(scopedChatId, hit.messageId) },
                            )
                        }
                    }
                } else if (!searchingEverywhere) {
                    RecentSearchesRow(
                        recents = recents,
                        onClearAll = { clearDialogOpen = true },
                        onPick = { item ->
                            if (item.chatId != null) onOpenChat(item.chatId) else query = item.label
                        },
                    )
                } else {
                    if (activeFilter == null || activeFilter == SearchFilter.UNREAD || activeFilter == SearchFilter.GROUPS) {
                        Section("Chats", chatMatches.size, seeAll = null)
                        if (chatMatches.isEmpty()) {
                            EmptySection("No matching chats")
                        } else {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = SectionInset),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                chatMatches.forEach { item ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .width(76.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable { openChat(item.chatId, item.title, item.color, item.initials, item.photoUri) }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                    ) {
                                        Avatar(
                                            initials = item.initials,
                                            color = item.color.toComposeColor(),
                                            size = 58.dp,
                                            photoUri = item.photoUri,
                                            labelSize = 15.sp,
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(item.title, maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }

                    Section("Messages", messageMatches.size, seeAll = null)
                    if (messageMatches.isEmpty()) {
                        EmptySection("No matching messages")
                    } else {
                        messageMatches.forEachIndexed { index, item ->
                            ListItem(
                                headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(item.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                leadingContent = {
                                    Avatar(
                                        initials = item.initials,
                                        color = item.color.toComposeColor(),
                                        size = 40.dp,
                                        photoUri = item.photoUri,
                                    )
                                },
                                trailingContent = item.time?.let { time ->
                                    { Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable {
                                    val messageId = item.messageId
                                    if (messageId == null) {
                                        openChat(item.chatId, item.title, item.color, item.initials, item.photoUri)
                                    } else {
                                        recents = RecentSearches.push(
                                            context,
                                            RecentSearch(item.chatId, item.title, item.chatId, item.color, item.initials, item.photoUri),
                                        )
                                        onOpenMessage(item.chatId, messageId)
                                    }
                                },
                            )
                            if (index < messageMatches.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    viewing?.let { media ->
        val stack = viewingStack.map { it.toViewable() }
        val atIndex = stack.indexOfFirst { it.uri == media.uri }
        MediaViewer(
            media = media,
            onDismiss = { viewing = null; viewingStack = emptyList() },
            onShare = { uri -> shareMedia(context, uri) },
            onStep = if (atIndex >= 0 && stack.size > 1) {
                { step: Int -> stack.getOrNull(atIndex + step)?.let { viewing = it } }
            } else null,
            canStep = { step -> atIndex >= 0 && stack.getOrNull(atIndex + step) != null },
            neighbours = if (atIndex >= 0) listOfNotNull(stack.getOrNull(atIndex - 1), stack.getOrNull(atIndex + 1)) else emptyList(),
        )
    }

    if (clearDialogOpen) {
        AppDialog(
            title = "Clear recent searches?",
            message = "This removes your search history from this device.",
            onDismiss = { clearDialogOpen = false },
            buttons = {
                TextButton(onClick = { clearDialogOpen = false }) { Text("Cancel", color = Color.White) }
                TextButton(onClick = {
                    RecentSearches.clear(context)
                    recents = emptyList()
                    clearDialogOpen = false
                }) { Text("Clear all", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

/**
 * The bar: back arrow, field and clear button, all inside one pill of the page's own glass.
 *
 * A `BasicTextField` rather than a Material one so the pill is the only container there is. Material's
 * fields bring a container, a label slot and a minimum height of their own, and a frosted pill drawn
 * around one of those is two surfaces stacked — which is exactly what made this bar tall enough to
 * swallow its own text.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .height(FieldHeight)
            .frostedFill(FieldShape, blur = ControlBlur, tint = ControlTint)
            .padding(start = 4.dp, end = 6.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    placeholder,
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
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
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

/** A section's title, how much is in it, and the way to the rest of it. */
@Composable
private fun Section(title: String, count: Int, seeAll: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = SectionInset, end = 8.dp, top = 20.dp, bottom = 8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (count > 0 && seeAll == null) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        if (seeAll != null) {
            TextButton(onClick = seeAll) { Text("See all") }
        }
    }
}

@Composable
private fun EmptySection(label: String, icon: ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = SectionInset, vertical = 6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The thumbnails, four across, square, with a play badge on anything that moves. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaGrid(items: List<MediaHit>, onOpen: (MediaHit) -> Unit) {
    val gap = 4.dp
    FlowRow(
        maxItemsInEachRow = GRID_COLUMNS,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
        modifier = Modifier.fillMaxWidth().padding(horizontal = SectionInset),
    ) {
        items.forEach { item ->
            Box(
                modifier = Modifier
                    // A quarter of the row minus its share of the gaps, so four sit edge to edge.
                    .fillMaxWidth(1f / GRID_COLUMNS)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpen(item) },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.caption,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (item.video) {
                    Box(
                        modifier = Modifier.size(26.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** A link or a file, as one card of the page's glass — the shape the reference layout uses. */
@Composable
private fun ShareCards(items: List<ShareHit>, icon: ImageVector, onOpen: (ShareHit) -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = SectionInset),
    ) {
        items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .frostedFill(RoundedCornerShape(18.dp))
                    .clickable { onOpen(item) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.headline,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        item.time,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(ControlTint),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A matched line from this conversation. */
@Composable
private fun TextResultRow(hit: ShareHit, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(hit.headline, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(hit.detail) },
        trailingContent = {
            Text(hit.time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun RecentSearchesRow(
    recents: List<RecentSearch>,
    onClearAll: () -> Unit,
    onPick: (RecentSearch) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(start = SectionInset, end = 8.dp),
        ) {
            Text("Recent searches", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            if (recents.isNotEmpty()) TextButton(onClick = onClearAll) { Text("Clear all") }
        }
        if (recents.isEmpty()) {
            Text(
                "No recent searches",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SectionInset),
            )
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recents.forEach { item ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(76.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onPick(item) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    ) {
                        Avatar(
                            initials = item.initials,
                            color = item.color.toComposeColor(),
                            size = 64.dp,
                            photoUri = item.photoUri,
                            labelSize = 15.sp,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            item.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

private fun MediaHit.toViewable(): ViewableMedia =
    if (video) ViewableMedia.Clip(uri, caption) else ViewableMedia.Photo(uri, caption)

/** `pages.dev` out of `https://5a8318e8.dashboard.pages.dev/x` — the line under a link's own text. */
private fun hostOf(url: String): String {
    val host = url.substringAfter("://", url).substringBefore('/').substringBefore('?')
    val labels = host.split('.').filter { it.isNotEmpty() }
    return if (labels.size <= 2) host else labels.takeLast(2).joinToString(".")
}
