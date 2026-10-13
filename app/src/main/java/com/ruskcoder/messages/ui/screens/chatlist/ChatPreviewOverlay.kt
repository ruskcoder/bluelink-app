package com.ruskcoder.messages.ui.screens.chatlist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ruskcoder.messages.data.model.Chat
import com.ruskcoder.messages.data.model.LOCAL_SENDER
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.ThemeMode
import com.ruskcoder.messages.data.model.myMentionNames
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.ui.components.barFilm
import com.ruskcoder.messages.ui.components.ChatAvatar
import com.ruskcoder.messages.ui.components.ChatBackdrop
import com.ruskcoder.messages.ui.components.ChatWallpaper
import com.ruskcoder.messages.ui.components.MessageThread
import com.ruskcoder.messages.ui.components.chatBackdropBlur
import com.ruskcoder.messages.ui.components.AppMenu
import com.ruskcoder.messages.ui.components.AppMenuCard
import com.ruskcoder.messages.ui.components.AppMenuDivider
import com.ruskcoder.messages.ui.components.AppMenuItem
import com.ruskcoder.messages.ui.components.chatBackdropSource
import com.ruskcoder.messages.ui.components.groupSubtitle
import com.ruskcoder.messages.ui.components.rememberChatBackdrop
import com.ruskcoder.messages.ui.components.typingLabel
import com.ruskcoder.messages.ui.theme.ChatThemeProvider
import com.ruskcoder.messages.ui.theme.LocalSettings

/** The tail of the conversation the window loads. Enough to scroll back through, not the whole log. */
private const val PREVIEW_MESSAGES = 60

/** The peeked header's height, which is also what the thread underneath is inset by. */
private val HeaderHeight = 60.dp

/**
 * How far in from the screen's edges the peeked window sits.
 *
 * Wide enough that the list is visibly still there behind it, on both sides — at a narrower inset
 * the window read as a page that had opened rather than as something held up over the list, which
 * is the whole distinction a peek is making.
 */
private val SideInset = 30.dp

private const val OPEN_MS = 320
private const val CLOSE_MS = 200

/** Material's emphasized-decelerate and emphasized-accelerate, for arriving and leaving. */
private val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * A long-pressed conversation, peeked without being opened: the list dims away and the conversation
 * rises into a window over it, with everything the long-press menu used to offer listed underneath.
 *
 * The thread inside scrolls, so the peek answers "what was said in here" rather than only "what was
 * said last" — but it is still only a look. **Nothing in here marks the conversation read.** Opening
 * a chat is what clears its badge and what sends a read receipt to the other end (see `ChatScreen`);
 * this reads the same stored messages and writes nothing, so a conversation can be scrolled through
 * here and still be sitting unread in the list afterwards. Anything added to this file has to keep
 * that true — a peek that quietly tells someone you read them is a peek nobody can trust.
 *
 * A tap anywhere on the window opens the conversation properly; a tap off it, or back, puts it away.
 */
@Composable
fun ChatPreviewOverlay(
    chat: Chat,
    chatRepository: ChatRepository,
    /** The list this went over, recorded so the actions card can frost it. */
    listBackdrop: ChatBackdrop? = null,
    /** Who is writing in this conversation right now, so the peek is as live as the row was. */
    typingSender: String?,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onToggleRead: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    /** Runs once the window has shrunk away, which is what takes the overlay out of the list. */
    onClosed: () -> Unit,
) {
    // Read-only: `threadFlow` is a projection of the stored messages, and this screen calls nothing
    // that writes. See the note on the class about why that has to stay the case.
    var messages by remember(chat.id) { mutableStateOf<List<Message>>(emptyList()) }
    LaunchedEffect(chat.id) {
        chatRepository.threadFlow(chat.id).collect { messages = it.takeLast(PREVIEW_MESSAGES) }
    }
    /**
     * Which handles are ours, which is what a tapback is coloured by.
     *
     * Without it every reaction in the peek came up in the *incoming* colour — a tapback takes the
     * palette of whoever placed it, and with no handles to match against, nothing had been placed
     * by us. So the same message showed a grey badge here and a blue one a tap later, in a window
     * whose whole job is to look like the conversation it is a peek at.
     */
    var myHandles by remember(chat.id) { mutableStateOf(setOf(LOCAL_SENDER)) }
    LaunchedEffect(chat.id) { myHandles = chatRepository.myHandles(chat.id) }

    // Held rather than derived from the caller's state so the window has something to sink back
    // into: the overlay stays composed until the exit transition has actually finished.
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    LaunchedEffect(visible.currentState, visible.targetState) {
        if (!visible.currentState && !visible.targetState) onClosed()
    }
    fun close() { visible.targetState = false }

    BackHandler(enabled = visible.targetState) { close() }

    /**
     * The entrance, as one number the whole cluster is drawn from: the window rises and grows into
     * place, and the menu follows it up a beat later rather than arriving alongside it.
     */
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible.targetState) {
        val open = visible.targetState
        progress.animateTo(
            targetValue = if (open) 1f else 0f,
            animationSpec = tween(if (open) OPEN_MS else CLOSE_MS, easing = if (open) EnterEasing else ExitEasing),
        )
    }

    // One transition for the lot: the scrim and the window have to leave together, and two of them
    // sharing a state would have whichever finished first decide the overlay was done.
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(OPEN_MS)),
        exit = fadeOut(tween(CLOSE_MS)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { close() },
                    ),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = SideInset, vertical = 12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PreviewWindow(
                    chat = chat,
                    messages = messages,
                    typingSender = typingSender,
                    myHandles = myHandles,
                    onOpen = onOpen,
                    // Everything the menu leaves over, which is what makes the window as tall as the
                    // screen allows without either of them having to guess at the other's height.
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            val p = progress.value
                            scaleX = 0.88f + 0.12f * p
                            scaleY = 0.88f + 0.12f * p
                            translationY = (1f - p) * 36.dp.toPx()
                            alpha = p
                        },
                )
                Spacer(modifier = Modifier.height(10.dp))
                // Everything the long press used to put in a dropdown, kept where the long press
                // now lands — a peek that costs you the actions would be a worse trade.
                PreviewActions(
                    chat = chat,
                    listBackdrop = listBackdrop,
                    onPin = { close(); onPin() },
                    onToggleRead = { close(); onToggleRead() },
                    onMute = { close(); onMute() },
                    onArchive = { close(); onArchive() },
                    onDelete = { close(); onDelete() },
                    modifier = Modifier.graphicsLayer {
                        // Held back a quarter of the way in, so it reads as following the window up
                        // rather than as a second thing that happened to appear at the same moment.
                        val p = ((progress.value - 0.25f) / 0.75f).coerceIn(0f, 1f)
                        alpha = p
                        translationY = (1f - p) * 20.dp.toPx()
                    },
                )
            }
        }
    }
}

/** The conversation itself, scrollable, under a header wearing the same bar the chat screen wears. */
@Composable
private fun PreviewWindow(
    chat: Chat,
    messages: List<Message>,
    typingSender: String?,
    /** Ours, so a reaction we placed is drawn in our own colour here as it is in the conversation. */
    myHandles: Set<String>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp,
    ) {
        ChatThemeProvider(themeKey = chat.theme, darkTheme = settings.mode == ThemeMode.DARK, hue = chat.themeHue) {
            // Same arrangement as the conversation screen: the header floats over the thread rather
            // than sitting above it, so an Apple bar has something to frost and messages pass under
            // it. The thread's scroll content is inset by exactly the header's height instead.
            val backdrop = rememberChatBackdrop()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // A tap on the window opens the conversation. Taps that land on a bubble are
                    // answered by the thread's own handler below; this catches the rest, and lets a
                    // drag through untouched so the thread can still be scrolled.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpen,
                    ),
            ) {
                ChatWallpaper(modifier = Modifier.chatBackdropSource(backdrop)) {
                    MessageThread(
                        messages = messages,
                        members = chat.members,
                        swipeTimestampsEnabled = false,
                        selecting = false,
                        selectedIds = emptySet(),
                        mentionNames = chat.members.map { it.name } + settings.myMentionNames(),
                        myHandles = myHandles,
                        typingSenders = listOfNotNull(typingSender),
                        isGroup = chat.group,
                        peerName = chat.name.ifBlank { "They" },
                        topInset = HeaderHeight,
                        // A bubble is not a control here: every gesture the thread offers answers
                        // with the one thing the window is for, or with nothing at all.
                        onTap = { _, _ -> onOpen(); true },
                        onLongPress = {},
                        onReply = { _, _ -> },
                        onDoubleTap = {},
                        onToggleSelect = {},
                    )
                }
                PreviewHeader(
                    chat = chat,
                    typingSender = typingSender,
                    backdrop = backdrop,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/**
 * The contact bar, drawn the way the conversation's own is: frosted black glass over the thread on
 * a real blur where the platform has one, a heavier flat fill where it doesn't.
 */
@Composable
private fun PreviewHeader(
    chat: Chat,
    typingSender: String?,
    backdrop: ChatBackdrop?,
    modifier: Modifier = Modifier,
) {
    val fill = barFilm(backdrop)
    val contentColor = Color.White

    Box(modifier = modifier.fillMaxWidth().height(HeaderHeight)) {
        if (backdrop != null) {
            Box(modifier = Modifier.matchParentSize().chatBackdropBlur(backdrop, RectangleShape))
        }
        Row(
            modifier = Modifier
                .matchParentSize()
                .background(fill)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatAvatar(chat = chat, size = 36.dp, ringColor = Color.Transparent)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    chat.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = when {
                    typingSender != null -> typingLabel(typingSender, chat.members, chat.group)
                    chat.group -> groupSubtitle(chat.members)
                    else -> null
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Pin, read, mute and delete, on the card every other menu in the app is drawn on — the peek is
 * holding the anchor, so this is a menu in everything but where it hangs.
 *
 * [listBackdrop] is the conversation list itself, recorded before this overlay went over it. The
 * scrim in between is a flat fill and blurring it would give the flat fill back, so the card
 * reaches past it to the rows underneath.
 */
@Composable
private fun PreviewActions(
    chat: Chat,
    listBackdrop: ChatBackdrop?,
    onPin: () -> Unit,
    onToggleRead: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // As wide as the window above it, so the rows have to be told to fill that rather than to
    // measure themselves against their own labels.
    AppMenuCard(backdrop = listBackdrop, modifier = modifier.fillMaxWidth(), fillWidth = true) {
        AppMenuItem(
            text = { Text(if (chat.pinned) "Unpin" else "Pin to top") },
            trailingIcon = { PreviewActionIcon(Icons.Outlined.PushPin) },
            onClick = onPin,
        )
        AppMenuItem(
            text = { Text(if (chat.unread > 0) "Mark as read" else "Mark as unread") },
            trailingIcon = {
                PreviewActionIcon(
                    if (chat.unread > 0) Icons.Outlined.MarkEmailRead else Icons.Outlined.MarkEmailUnread
                )
            },
            onClick = onToggleRead,
        )
        AppMenuItem(
            text = { Text(if (chat.muted) "Unmute" else "Mute") },
            trailingIcon = {
                PreviewActionIcon(
                    if (chat.muted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff
                )
            },
            onClick = onMute,
        )
        AppMenuItem(
            text = { Text(if (chat.archived) "Unarchive" else "Archive") },
            trailingIcon = {
                PreviewActionIcon(if (chat.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive)
            },
            onClick = onArchive,
        )
        AppMenuDivider()
        AppMenuItem(
            text = { Text("Delete chat") },
            trailingIcon = { PreviewActionIcon(Icons.Outlined.DeleteOutline) },
            colors = AppMenu.destructiveItemColors(),
            onClick = onDelete,
        )
    }
}

@Composable
private fun PreviewActionIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
}
