package com.ruskcoder.messages.ui.screens.chat

import android.net.Uri
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import com.ruskcoder.messages.ui.components.LocalAttachmentRetry
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.asShareableText
import com.ruskcoder.messages.data.model.readReceiptsOn
import com.ruskcoder.messages.data.model.typingIndicatorsOn
import com.ruskcoder.messages.data.model.ChatIdentity
import com.ruskcoder.messages.data.model.ChatThemeKey
import com.ruskcoder.messages.ui.components.GamesPanel
import com.ruskcoder.messages.ui.components.OpenPigeonGamesPanel
import com.ruskcoder.messages.ui.components.LocalStickerTargets
import com.ruskcoder.messages.ui.components.StickerDragLayer
import com.ruskcoder.messages.ui.components.StickerDragState
import com.ruskcoder.messages.ui.components.StickerDropTargets
import com.ruskcoder.messages.ui.components.StickerPanel
import com.ruskcoder.messages.ui.components.StickerSearchStrip
import com.ruskcoder.messages.ui.components.stickerDragHost
import com.ruskcoder.messages.ui.components.stickerPlacementOn
import com.ruskcoder.messages.data.model.quickReactionEmoji
import com.ruskcoder.messages.data.model.MAX_TEXT_SCALE
import com.ruskcoder.messages.data.model.MIN_TEXT_SCALE
import com.ruskcoder.messages.data.model.Contact
import com.ruskcoder.messages.data.model.DoubleTapAction
import com.ruskcoder.messages.data.model.editable
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.savableAttachments
import com.ruskcoder.messages.data.model.deliveredQuietly
import com.ruskcoder.messages.data.model.status
import com.ruskcoder.messages.data.model.ReplyMode
import com.ruskcoder.messages.data.model.sendBar
import androidx.compose.ui.text.TextRange
import com.ruskcoder.messages.data.model.RichDraft
import com.ruskcoder.messages.data.model.RunFormat
import com.ruskcoder.messages.ui.components.QuickFormatBar
import com.ruskcoder.messages.ui.components.RichTextPanel
import androidx.compose.ui.platform.LocalTextToolbar
import com.ruskcoder.messages.ui.components.FormatActions
import com.ruskcoder.messages.ui.components.rememberFormattingTextToolbar
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import com.ruskcoder.messages.data.model.EffectKind
import com.ruskcoder.messages.data.model.MessageEffect
import com.ruskcoder.messages.ui.components.MessageBubble
import com.ruskcoder.messages.ui.components.effects.BubbleEffectLayer
import com.ruskcoder.messages.ui.components.effects.EffectsOverlay
import com.ruskcoder.messages.ui.components.effects.EffectPickerPanel
import com.ruskcoder.messages.ui.components.effects.ScreenEffectLayer
import com.ruskcoder.messages.ui.components.effects.rememberBubbleEffect
import com.ruskcoder.messages.ui.components.effects.rememberScreenEffect
import com.ruskcoder.messages.data.model.ReplyPreview
import com.ruskcoder.messages.data.model.ThemeMode
import com.ruskcoder.messages.data.model.canReact
import com.ruskcoder.messages.data.model.caption
import com.ruskcoder.messages.data.model.fileUri
import com.ruskcoder.messages.data.model.colorFor
import com.ruskcoder.messages.data.model.initialsFor
import com.ruskcoder.messages.data.model.preview
import com.ruskcoder.messages.data.model.LOCAL_SENDER
import com.ruskcoder.messages.data.model.mine
import com.ruskcoder.messages.data.model.myMentionNames
import com.ruskcoder.messages.data.model.canRetrySend
import com.ruskcoder.messages.data.model.error
import com.ruskcoder.messages.data.model.reactions
import com.ruskcoder.messages.data.model.receipts
import com.ruskcoder.messages.data.model.replyTo
import com.ruskcoder.messages.data.model.senderId
import com.ruskcoder.messages.data.model.textScale
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.data.repository.ContactsRepository
import com.ruskcoder.messages.imessage.AttachmentStaging
import com.ruskcoder.messages.imessage.IMessageSender
import com.ruskcoder.messages.imessage.ScheduledSendAlarm
import com.ruskcoder.messages.data.repository.ScheduledSends
import com.ruskcoder.messages.transcribe.SpeechModel
import com.ruskcoder.messages.transcribe.SpeechModelStore
import com.ruskcoder.messages.transcribe.TranscriptionState
import com.ruskcoder.messages.transcribe.VoiceTranscriptions
import com.ruskcoder.messages.ui.components.barFilm
import com.ruskcoder.messages.ui.components.AppDropdownMenu
import com.ruskcoder.messages.ui.components.AppMenuItem
import com.ruskcoder.messages.ui.components.AttachmentPanel
import com.ruskcoder.messages.ui.components.Avatar
import com.ruskcoder.messages.ui.components.ChatComposer
import com.ruskcoder.messages.ui.components.ChatWallpaper
import com.ruskcoder.messages.ui.components.composerSurfaceFor
import com.ruskcoder.messages.ui.components.EditScrim
import com.ruskcoder.messages.ui.components.LocalChatBackdrop
import com.ruskcoder.messages.ui.components.chatBackdropBlur
import com.ruskcoder.messages.ui.components.chatBackdropSource
import com.ruskcoder.messages.ui.components.rememberChatBackdrop
import com.ruskcoder.messages.ui.components.ContactPickerSheet
import com.ruskcoder.messages.ui.components.DeleteMessagesDialog
import com.ruskcoder.messages.ui.components.SendFailureDialog
import com.ruskcoder.messages.ui.components.ForwardSheet
import com.ruskcoder.messages.ui.components.GroupAvatar
import com.ruskcoder.messages.ui.components.InChatSearchBar
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import com.ruskcoder.messages.ui.components.FOCUS_CLOSE_MS
import com.ruskcoder.messages.ui.components.FOCUS_OPEN_MS
import com.ruskcoder.messages.ui.components.FocusedEntry
import com.ruskcoder.messages.ui.components.HomeShape
import com.ruskcoder.messages.ui.components.FocusedMessageOverlay
import com.ruskcoder.messages.ui.components.FocusedReplyOverlay
import com.ruskcoder.messages.ui.components.MediaViewer
import com.ruskcoder.messages.ui.components.MediaGroupPage
import com.ruskcoder.messages.ui.components.MediaTilePresses
import com.ruskcoder.messages.ui.components.MediaPageItem
import com.ruskcoder.messages.ui.components.MediaStackPositions
import com.ruskcoder.messages.ui.components.viewables
import com.ruskcoder.messages.ui.components.MentionSuggestions
import com.ruskcoder.messages.data.model.PollOption
import com.ruskcoder.messages.data.model.Polls
import com.ruskcoder.messages.data.model.displayInitials
import com.ruskcoder.messages.data.model.displayName
import com.ruskcoder.messages.data.model.optionsPayload
import com.ruskcoder.messages.data.model.pollStatesIn
import com.ruskcoder.messages.data.model.votePayload
import com.ruskcoder.messages.ui.components.LocalPollHost
import com.ruskcoder.messages.ui.components.PollHost
import com.ruskcoder.messages.ui.components.MessageInfoSheet
import com.ruskcoder.messages.ui.components.applyMention
import com.ruskcoder.messages.ui.components.pendingMentionQuery
import com.ruskcoder.messages.ui.components.MessageThread
import com.ruskcoder.messages.ui.components.ViewableMedia
import com.ruskcoder.messages.ui.components.asViewableMedia
import com.ruskcoder.messages.ui.components.ThreadRowRect
import com.ruskcoder.messages.ui.components.rememberMessageThreadState
import com.ruskcoder.messages.ui.components.ReactionBar
import com.ruskcoder.messages.ui.components.ScheduleSendDialog
import com.ruskcoder.messages.ui.components.ScheduledMessagesSheet
import com.ruskcoder.messages.ui.components.ReactionsSheet
import com.ruskcoder.messages.ui.components.groupSubtitle
import com.ruskcoder.messages.ui.components.mediaAttachmentFor
import com.ruskcoder.messages.ui.components.rememberAttachmentFlow
import com.ruskcoder.messages.ui.components.rememberVoiceRecorder
import com.ruskcoder.messages.permissions.rememberPermissionState
import com.ruskcoder.messages.ui.components.BubbleSelection
import com.ruskcoder.messages.ui.components.SelectionBar
import com.ruskcoder.messages.data.model.inThread
import com.ruskcoder.messages.data.model.threadOf
import com.ruskcoder.messages.ui.components.quoteAdjacentFor
import com.ruskcoder.messages.ui.components.runFlagsFor
import com.ruskcoder.messages.ui.components.TextSelectionBar
import com.ruskcoder.messages.ui.components.pinchZoom
import com.ruskcoder.messages.ui.theme.ChatThemeProvider
import com.ruskcoder.messages.ui.theme.LocalSettings
import com.ruskcoder.messages.util.KeyboardHeightStore
import com.ruskcoder.messages.util.SavedTo
import com.ruskcoder.messages.util.copyMediaToClipboard
import com.ruskcoder.messages.util.saveAttachmentToDevice
import com.ruskcoder.messages.util.shareMedia

/**
 * A picked-out message and where its row sits on screen, so the reaction bar and the icon menu can
 * hang off the bubble itself. Measured once, when the message is picked: the dismissal scrim over
 * the conversation means it can't scroll out from under them while they're up.
 */
private data class MessageAnchor(
    val message: Message,
    val top: Float,
    val height: Float,
    /**
     * Where the bubble itself starts, which is not where its row does — an Apple reply's quote, a
     * group's sender name and a tapback's gap all stand above it. Anything that hangs off the
     * *message* measures from here.
     */
    val bubbleTop: Float,
    /** The shape the message is already wearing in the thread, which the overlay must not change. */
    val isFirstInRun: Boolean = true,
    val isLastInRun: Boolean = true,
    /** And whether its quote pointed at the bubble above it, which changes how the quote is drawn. */
    val quoteAdjacent: Boolean = false,
    /** And who sent it, in a group — the avatar and the name the row is drawn with. */
    val sender: GroupMember? = null,
    /** And who wrote the message it quotes, whose face goes beside the outline above it. */
    val quotedSender: GroupMember? = null,
    /** And the tail and the face a connector gives its row, which the lift must not take away. */
    val forcedTail: Boolean = false,
    val forcedAvatar: Boolean = false,
)

/** The reaction bar's own height — one 44dp slot plus its 6dp padding either side. */
private val ReactionBarHeight = 56.dp

/** How far off the bubble's edge either bar parks — near enough to read as attached to it. */
private val AnchorGap = 2.dp

/**
 * The default for [ChatScreen]'s compose-request signal: one that never asks for anything.
 *
 * A shared instance rather than a `MutableStateFlow(null)` written into the parameter list, which
 * would build a fresh flow on every recomposition that fell back to it.
 */
private val NoComposeRequests: StateFlow<String?> = MutableStateFlow(null)

/** Stands in for the transcription service on a build without one, for the same reason as above. */
private val NoTranscriptions: StateFlow<Set<String>> = MutableStateFlow(emptySet())
private val NoModels: StateFlow<Set<SpeechModel>> = MutableStateFlow(emptySet())

/** How much daylight the row being edited keeps above the send bar once the keyboard is up. */
private val EditRevealMargin = 12.dp

/** The attachment panel's height before any keyboard has been seen to measure against. */
private val DefaultPanelHeight = 260.dp

/**
 * How much of the screen the full-height gallery leaves above the send bar: enough for the last
 * message and the header, so the conversation is never pushed entirely off the top.
 */
private val GalleryHeadroom = 140.dp

/**
 * How far down the card has to be pulled by its grab bar, as a share of its own height, before
 * letting go dismisses it rather than putting it back — or how fast it has to be thrown, in pixels
 * a second, for the distance not to matter.
 */
private const val PanelDismissFraction = 0.35f
private const val PanelDismissVelocity = 900f

/** The send bar's own gap above the navigation bar, which the "+" card takes over while it stands. */
private val ComposerBottomMargin = 6.dp

/** Below this, an IME inset is a keyboard mid-animation rather than a keyboard. */
private val MinKeyboardHeight = 120.dp

/** How long the lift is willing to aim at a remembered keyboard height before giving up on one. */
private const val KeyboardPredictionMs = 700L

/**
 * How long the card below the send bar takes to open into an empty slot, or close back out of one.
 *
 * Only ever used when there is no keyboard involved. Opened from a keyboard, or closed into one,
 * the card takes and gives up its height instantly and the keyboard's own slide is the whole of
 * the animation — see [ChatScreen]'s `panelSlotSnaps`.
 */
private const val PanelSlotMs = 220

/**
 * How long to let the keyboard climb over the card's slot before the card lets go of it.
 *
 * Comfortably longer than any IME's own show animation. Letting go late costs nothing — the slot
 * is behind the keyboard by then, so nothing on screen moves when it goes — while letting go early
 * drops the send bar to the bottom of the screen and walks it back up behind the keys.
 */
private const val KeyboardCoverMs = 400L

/** Material's emphasized-decelerate, shared by the chrome that comes and goes over the thread. */
private val ChromeEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * How a bar that takes the header's place arrives and leaves: dropped in from a third of its own
 * height above, fading as it comes. The header underneath only fades, so the two read as one bar
 * sliding over another rather than two panels trading places.
 */
private val BarEnter =
    fadeIn(tween(200)) + slideInVertically(tween(260, easing = ChromeEasing)) { -it / 3 }
private val BarExit =
    fadeOut(tween(140)) + slideOutVertically(tween(200, easing = ChromeEasing)) { -it / 3 }

/** Which of the card's faces is in the send bar's slot. */
private enum class PanelFace { ATTACHMENTS, GAMES, RICH_TEXT, EFFECTS, STICKERS }

/**
 * Bottom padding worked out in the layout pass rather than in composition.
 *
 * Everything this file pads the send bar's stack by moves on an animation — the keyboard's inset,
 * the card's slot — and a `Modifier.padding` reading either of those would recompose the whole
 * conversation on every frame of it. Measured here instead, the animation only ever re-lays-out.
 */
private fun Modifier.bottomSpace(space: Density.() -> Int): Modifier = layout { measurable, constraints ->
    val extra = space().coerceAtLeast(0)
    val placeable = measurable.measure(constraints.offset(vertical = -extra))
    layout(placeable.width, placeable.height + extra) { placeable.place(0, 0) }
}

/**
 * The keyboard's inset, less whatever height the card below the send bar is already holding.
 *
 * `imePadding` alone stacks the two: the bar sits above the keyboard and the card hangs below the
 * bar, so opening the card with the keyboard up asked the keyboard to leave and the card to grow at
 * the same moment, in opposite directions, on two unrelated curves. The keyboard is a window drawn
 * *over* this one rather than anything this one contains, so the arrangement that matches what the
 * eye already believes is the simpler one: the card sits in its slot the whole time and the
 * keyboard is layered on top of it. Whatever the keyboard covers, the card is already holding — so
 * this pads out only the part of the keyboard the card is *not* under, and while the two are the
 * same height that is nothing at all. The send bar never moves; the keyboard slides off the card,
 * or back over it, and that slide is the entire animation.
 *
 * Goes after `navigationBarsPadding`, which has taken the navigation bar's share already.
 */
@Composable
private fun Modifier.keyboardSpaceBeyond(slot: State<Dp>): Modifier {
    val ime = WindowInsets.ime
    val navigation = WindowInsets.navigationBars
    return bottomSpace { ime.getBottom(this) - navigation.getBottom(this) - slot.value.roundToPx() }
}

/** The conversation screen. Ported from `chat/[id].tsx`, wiring together every Phase 3 component. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    chatId: String,
    initialName: String?,
    initialGroup: Boolean,
    initialMembers: List<GroupMember>,
    /** Opens straight into find-in-chat — where the details page's search button lands. */
    startInSearch: Boolean = false,
    /** A message to scroll to and light up on arrival — where a search result leads. */
    initialMessageId: String? = null,
    /**
     * Names the conversation whose send bar has been asked to take the keyboard, or null for none.
     *
     * What sets it is a notification tap. Answering a message is why the conversation was opened at
     * all, and making somebody tap the bar to begin writing the reply they already committed to is
     * a step that exists only because the app forgot what it was asked for.
     *
     * A signal rather than a parameter fixed at construction, because the screen it is meant for
     * may already be on screen — see `AppContainer.composeInChat`.
     */
    composeRequests: StateFlow<String?> = NoComposeRequests,
    /** The request has been taken; whoever raised it should put it down. */
    onComposerFocused: () -> Unit = {},
    chatRepository: ChatRepository,
    /**
     * Routes sends, tapbacks, edits and unsends to Apple when the conversation has an iMessage
     * identity, and falls back to [chatRepository] when it does not — so a chat created before
     * sign-in keeps behaving exactly as it did.
     */
    imessageSender: IMessageSender,
    /** Runs a tapped game invite in the pigeon app; no-ops when it isn't installed. */
    pigeonClient: com.ruskcoder.messages.games.PigeonClient,
    /** The other games app, spoken to over the Madrid extension protocol. */
    openPigeonClient: com.ruskcoder.messages.games.OpenPigeonClient,
    /**
     * Who is currently typing, by chat id. Live rather than stored — a typing indicator that
     * survived a restart would be describing something that stopped being true long ago.
     */
    typingByChat: StateFlow<Map<String, List<String>>>,
    /** Called when the conversation is read, to take down any notification still standing for it. */
    onChatRead: (String) -> Unit,
    contactsRepository: ContactsRepository,
    /** Written to by the pinch that resizes the conversation's text. */
    settingsRepository: SettingsRepository,
    /** On-device transcription of voice messages, or null on a build without a speech model. */
    voiceTranscriptions: VoiceTranscriptions? = null,
    /** The downloaded speech models, which decide whether the action can run at all. */
    speechModels: SpeechModelStore? = null,
    /** Opens the transcription settings, where a model is downloaded. */
    onOpenTranscriptionSettings: () -> Unit = {},
    onBack: () -> Unit,
    onOpenContact: (String) -> Unit,
    /** Opens the one-to-one conversation with a group member, addressed by their handle. */
    onOpenChat: (String) -> Unit = {},
    onOpenGroup: (String) -> Unit,
    onOpenSearch: (String) -> Unit,
    /** Fetches received attachments whose bytes never arrived — see [LocalAttachmentRetry]. */
    onRetryDownloads: () -> Unit = {},
) {
    val storedSettings = LocalSettings.current
    /**
     * The size a pinch has the message text at right now, or 0 when no pinch is in flight. It is
     * kept here and only written to disk when the fingers come off: the conversation resizes on
     * every frame of the gesture, and a stored setting is not something to rewrite at 60Hz.
     */
    var pinchTextScale by remember { mutableFloatStateOf(0f) }
    val settings = remember(storedSettings, pinchTextScale) {
        if (pinchTextScale > 0f) storedSettings.copy(messageTextScale = pinchTextScale) else storedSettings
    }
    val chats by chatRepository.chats.collectAsStateWithLifecycle()
    val chat = remember(chats, chatId) { chats.find { it.id == chatId } }
    val identity = remember(chat, chatId, initialName, initialGroup, initialMembers) {
        if (chat != null) {
            ChatIdentity(chat.id, chat.name, chat.color, chat.initials, chat.group, chat.members, chat.photoUri)
        } else {
            val name = initialName ?: ""
            ChatIdentity(chatId, name, colorFor(name), initialsFor(name), initialGroup, initialMembers)
        }
    }

    // Read straight off the repository's warm copy, so a conversation that was prefetched from the
    // list is already on screen on the very first composition rather than arriving a frame or two
    // into the transition. See [ChatRepository.threadFlow].
    val thread = remember(chatId) { chatRepository.threadFlow(chatId) }
    val messages by thread.collectAsStateWithLifecycle()

    /**
     * Whether this conversation's messages have actually been looked for yet.
     *
     * An empty list means two different things — "nothing has loaded" and "there is nothing here" —
     * and the notice below is only true of the second. Told apart, the way into a chat stops
     * flashing the empty-conversation line at you while its messages are still on their way.
     */
    var threadLoaded by remember(chatId) { mutableStateOf(messages.isNotEmpty()) }
    LaunchedEffect(chatId) {
        if (!threadLoaded) {
            // One real reading is enough; after that the list speaks for itself.
            thread.first { it.isNotEmpty() }
            threadLoaded = true
        }
    }

    /**
     * How many messages were unread when this conversation was opened.
     *
     * Latched on the first look, because opening a chat marks it read a moment later — read the
     * count after that and the divider never appears. Held for as long as the screen is, so the
     * line stays put while you scroll back through what you missed rather than vanishing under you.
     */
    var unreadAtOpen by remember(chatId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(chatId, chat) {
        if (unreadAtOpen == null && chat != null) unreadAtOpen = chat.unread
    }
    // The thread holds a window of the newest messages, not the whole history. An unread run longer
    // than that window would put the "unread from here" rule somewhere nobody could scroll to.
    LaunchedEffect(chatId, unreadAtOpen) {
        val unread = unreadAtOpen ?: return@LaunchedEffect
        if (unread > 0) chatRepository.ensureWindow(chatId, unread + UnreadWindowSlack)
    }
    // Leaving puts the window back to a page, so one long scroll back does not stay loaded for good.
    DisposableEffect(chatId) { onDispose { chatRepository.resetWindow(chatId) } }
    // Opening a conversation is the moment a picture that never downloaded is noticed, so it is also
    // the moment to have another go at it — without making anyone find and press the bubble.
    LaunchedEffect(chatId) { onRetryDownloads() }

    /** The oldest message the unread run starts at, or null when there was nothing unread. */
    val firstUnreadId = remember(messages, unreadAtOpen) {
        val count = unreadAtOpen ?: 0
        if (count <= 0) null else messages.filterNot { it.mine }.takeLast(count).firstOrNull()?.id
    }
    /**
     * This conversation's notification goes when the conversation is opened, whatever brought it —
     * and only this one.
     *
     * Kept apart from the unread count below, which is not the same question. A reaction someone
     * placed is announced in the shade but adds nothing to the count, so a conversation opened on
     * the back of a reaction had nothing marked read and its notification was never cleared: the
     * shade went on saying somebody had reacted to a message you were looking at. Opening the
     * thread is what has seen it, so opening the thread is what clears it.
     */
    LaunchedEffect(chatId) { if (chatId.isNotBlank()) onChatRead(chatId) }
    LaunchedEffect(chatId, chat?.unread) {
        if (chatId.isNotBlank() && (chat?.unread ?: 0) > 0) {
            chatRepository.markRead(chatId)
            onChatRead(chatId)
            // Tell the sender too, if the switch says so and there is anyone to tell: this
            // conversation's own choice if it has one, the global setting otherwise.
            if (chat?.readReceiptsOn(settings) ?: settings.readReceipts) {
                messages.lastOrNull { !it.mine }?.let { imessageSender.markRead(chatId, it.id) }
            }
        }
    }

    val typingByChatState by typingByChat.collectAsStateWithLifecycle()
    /** The handle of whoever is writing in this conversation, or null. */
    val typingSenders = typingByChatState[chatId].orEmpty()

    var composerText by rememberSaveable { mutableStateOf("") }

    /**
     * What the draft is wearing, character by character — see [RichDraft].
     *
     * Kept beside [composerText] rather than replacing it. The plain string is what the draft *is*
     * and what every other part of this screen already reads — the typing indicator, the mention
     * picker, the scheduler — and none of them has any business knowing about formatting. This is
     * carried alongside and folded back in at the one place it matters, which is the send.
     */
    var richDraft by remember(chatId) { mutableStateOf(RichDraft()) }
    /** Where the caret is in the field, which is what the formatting controls act on. */
    var draftSelection by remember(chatId) { mutableStateOf(TextRange.Zero) }
    /**
     * What the next thing typed will wear, with nothing selected.
     *
     * The state every editor has and nobody names: press bold with a bare caret and the *next*
     * word is bold. Without it the buttons would do nothing at all until you had already written
     * the words you wanted formatted, and then gone back and selected them.
     */
    var pendingFormat by remember(chatId) { mutableStateOf(RunFormat.Plain) }
    /** True while the "+" card is showing the formatting controls instead of the attachments. */
    var richTextOpen by remember(chatId) { mutableStateOf(false) }
    /** True while the "+" card is showing the games picker instead of the attachments. */
    var gamesOpen by remember(chatId) { mutableStateOf(false) }
    /** True while the "+" card is showing the stickers instead of the attachments. */
    var stickersOpen by remember(chatId) { mutableStateOf(false) }
    /** What the sticker search last asked for; kept while the card and the keyboard trade places. */
    var stickerQuery by remember(chatId) { mutableStateOf("") }
    /** True while the sticker search is up above the send bar, with the keyboard. */
    var stickerSearching by remember(chatId) { mutableStateOf(false) }
    val stickerDrag = remember { StickerDragState() }
    val stickerTargets = remember(chatId) { StickerDropTargets() }

    /**
     * True while the card is showing the send effects, which is a mode rather than a face.
     *
     * The other two faces of that card are just controls. This one takes the conversation out of
     * focus and puts the message itself in the send bar, because picking an effect is choosing how
     * *this* message will land — and you cannot judge that against a bar you cannot see it in.
     */
    var effectsOpen by remember(chatId) { mutableStateOf(false) }
    /** The effect the next send will carry, or null. */
    var armedEffect by remember(chatId) { mutableStateOf<MessageEffect?>(null) }
    /** The one full-screen effect playing right now — see [ScreenEffectState]. */
    val screenEffects = rememberScreenEffect()
    /** The preview's own bubble effect, replayed each time a bubble effect is pressed. */
    val previewEffect = rememberBubbleEffect(armedEffect, autoPlay = false)
    /** Where the preview bubble is, for the screen effects that aim at the message. */
    var previewBox by remember { mutableStateOf(Rect.Zero) }

    /** The draft a "send later" dialog is open about, or null when none is. */
    var scheduling by rememberSaveable { mutableStateOf<String?>(null) }

    /** Whether the list of already-queued messages is open. */
    var managingScheduled by rememberSaveable { mutableStateOf(false) }

    /** The photo or video currently open full screen, if any. */
    var viewingMedia by remember { mutableStateOf<ViewableMedia?>(null) }
    /** The group the open picture was sent with, which gives the viewer its grid button. */
    var viewingGroup by remember { mutableStateOf<Attachment.MediaGroup?>(null) }
    /** A group open two to a row, if any. */
    var mediaPage by remember { mutableStateOf<Attachment.MediaGroup?>(null) }
    /**
     * The draft the lightbox was opened from, or null for a picture out of the conversation.
     *
     * What it decides is whether the viewer offers a pen: a draft has not been sent, so drawing on
     * it just changes what is about to go. Held as the uri rather than a flag because the marked-up
     * copy has to go back into the draft in the same place the original came out of.
     */
    var editingDraft by remember { mutableStateOf<Uri?>(null) }

    /** The message whose per-person receipts are open, if any. */
    var infoTarget by remember { mutableStateOf<Message?>(null) }

    /** The message whose reactor list is open, if any. */
    var reactorsTarget by remember { mutableStateOf<Message?>(null) }

    /**
     * The undelivered message whose failure dialog is open, held by id rather than by value.
     *
     * The dialog is describing a row that is still live: a retry started from it succeeds a moment
     * later, and the message that comes back through the thread is a different object from the one
     * the badge was tapped on. Re-resolved from the current thread each time, the dialog closes
     * itself the moment the message stops being failed — or stops existing — instead of standing
     * there describing a state that has already been fixed.
     */
    var failureId by rememberSaveable { mutableStateOf<String?>(null) }
    val failureTarget = remember(messages, failureId) {
        messages.find { it.id == failureId }?.takeIf { it.error() != null }
    }

    /**
     * Every handle a reaction of ours could be filed under on this conversation.
     *
     * Resolved once per chat rather than per bubble: it comes out of storage, and asking for it
     * while drawing a thread would put a database read on the scroll path.
     */
    var myHandles by remember(chatId) { mutableStateOf(setOf(LOCAL_SENDER)) }
    LaunchedEffect(chatId) {
        if (chatId.isNotBlank()) myHandles = imessageSender.myHandles(chatId)
    }

    /**
     * Mirrors the composer to the other end as a typing indicator.
     *
     * Keyed on the text so every keystroke restarts the effect, which restarts the idle timer with
     * it — Apple's own client stops the indicator after a lull rather than the moment a key goes
     * up, and repeating "started" on every character would be pure noise on the wire.
     */
    var typingSignalled by remember(chatId) { mutableStateOf(false) }
    val typingEnabled = chat?.typingIndicatorsOn(settings) ?: settings.typingIndicators
    LaunchedEffect(chatId, typingEnabled) {
        val enabled = typingEnabled
        try {
            // The draft is read through a flow rather than used as a key. Keyed on the text, every
            // keystroke recomposed the whole conversation screen just to restart this timer;
            // collected latest-first, a keystroke restarts the timer and nothing else.
            snapshotFlow { composerText }.collectLatest { text ->
                // Switched off for this conversation, or nothing written, there is nothing to
                // mirror — and if we had already said we were typing, the other end is still
                // waiting to be told we stopped.
                if (!enabled || text.isBlank()) {
                    if (typingSignalled) {
                        typingSignalled = false
                        imessageSender.setTyping(chatId, false)
                    }
                    return@collectLatest
                }
                if (!typingSignalled) {
                    typingSignalled = true
                    imessageSender.setTyping(chatId, true)
                }
                delay(TYPING_IDLE_MS)
                typingSignalled = false
                imessageSender.setTyping(chatId, false)
            }
        } finally {
            // Backing out of a conversation mid-draft cancels this effect, and with it the only
            // thing that was ever going to tell the other end we stopped — so their phone kept the
            // indicator up until it timed out on its own. Withdrawn here instead, outside
            // cancellation so the send actually goes.
            if (typingSignalled) {
                typingSignalled = false
                withContext(NonCancellable) { imessageSender.setTyping(chatId, false) }
            }
        }
    }
    var replyTo by remember { mutableStateOf<ReplyPreview?>(null) }
    // Focus-thread replies: the lifted bubbles, and whether they are on their way back.
    var focusedEntries by remember(chatId) { mutableStateOf<List<FocusedEntry>>(emptyList()) }
    var focusClosing by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }

    /** How tall the editor is right now, so a field that wraps re-pins itself above the bar. */
    var editorHeight by remember { mutableIntStateOf(0) }
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var attachmentPanelVisible by remember { mutableStateOf(false) }
    /**
     * Whether the four styles are parked above the send bar.
     *
     * Taken out of the formatting card, which holds the keyboard's slot for as long as it is open.
     * Out here they cost a corner of the conversation and nothing else, so a style can be turned on
     * mid-sentence without the keyboard going anywhere.
     */
    var quickFormatOpen by remember { mutableStateOf(false) }
    var reactionTarget by remember { mutableStateOf<MessageAnchor?>(null) }

    /**
     * The bubble the double-tap reaction bar is drawn against, kept one beat longer than
     * [reactionTarget] itself.
     *
     * The bar unrolls itself open but had no way out: dropped from the composition on the frame the
     * emoji was picked, it vanished mid-gesture. Holding the anchor gives it something to shrink
     * back toward, and the retained value is only ever read while the bar is on its way off.
     */
    var lastReactionAnchor by remember { mutableStateOf<MessageAnchor?>(null) }
    var menuTarget by remember { mutableStateOf<MessageAnchor?>(null) }

    /**
     * Whether the full emoji palette is up over the double-tap reaction bar.
     *
     * Only that bar's. The one inside a long press already stands over a blurred conversation, and
     * the message it belongs to is lifted out of the thread rather than sitting in it.
     */
    var emojiPaletteOpen by remember { mutableStateOf(false) }
    /** Raised to send the lifted bubble home; the overlay clears [menuTarget] when it lands. */
    var menuClosing by remember { mutableStateOf(false) }
    /**
     * Whether the lifted message's words are being highlighted, and which part of them is.
     *
     * The range lives up here rather than in the bubble because the button that acts on it is in
     * the app bar: what is selected has to be readable from outside the conversation's scroll.
     */
    var selectingText by remember { mutableStateOf(false) }
    val bubbleSelection = remember { BubbleSelection() }
    /** What the menu was tapped for, run once the bubble is back in the thread. */
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showForwardSheet by remember { mutableStateOf(false) }
    var headerMenuOpen by remember { mutableStateOf(false) }
    var showAddPeople by remember { mutableStateOf(false) }
    var pickableContacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    // What tapping a face or name in a group offers. Call hands the number to the phone's dialler:
    // there is no in-app calling yet, so it is the one real thing a call can mean.
    val latestOpenChat by rememberUpdatedState(onOpenChat)
    val latestOpenContact by rememberUpdatedState(onOpenContact)
    // Takes a phone number or a handle; an email address is a handle nobody can ring.
    val dial = remember(context) {
        { handle: String, name: String ->
            val number = handle.takeIf { id -> id.any { it.isDigit() } && !id.contains('@') }
            if (number == null) {
                Toast.makeText(context, "No phone number for $name", Toast.LENGTH_SHORT).show()
            } else {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { Toast.makeText(context, "No phone app to call with", Toast.LENGTH_SHORT).show() }
            }
        }
    }
    val memberActions = remember(context) {
        com.ruskcoder.messages.ui.components.MemberActions(
            onMessage = { member -> latestOpenChat(member.id) },
            onCall = { member -> dial(member.phone?.takeIf { it.isNotBlank() } ?: member.id, member.name) },
            onDetails = { member -> latestOpenContact(member.id) },
        )
    }

    /**
     * What this conversation has waiting to be sent.
     *
     * Read on entry and after every edit rather than observed: the queue lives in preferences,
     * which have no change feed. Only two things change it while this screen is up — scheduling a
     * message from here, and the sheet that manages them — and both say so.
     */
    var scheduled by remember(chatId) { mutableStateOf(ScheduledSends.forChat(context, chatId)) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // The one thing that can take the caret out of the send bar without also taking the bar away.
    val focusManager = LocalFocusManager.current
    /** A recording of the whole screen, for the one thing that blurs all of it — see [EffectsOverlay]. */
    val screenBackdrop = rememberChatBackdrop()
    val threadState = rememberMessageThreadState()

    /** Which of this conversation's attachments are still on their way out. */
    val attachmentTransfers by imessageSender.transfers.active.collectAsStateWithLifecycle()

    /**
     * Which voice messages are being transcribed, and which last failed.
     *
     * App-wide rather than per-conversation, and read rather than owned: the work runs outside this
     * screen so that leaving the conversation does not cancel it — see [VoiceTranscriptions].
     */
    val transcribing by (voiceTranscriptions?.running ?: NoTranscriptions).collectAsStateWithLifecycle()
    val transcriptionFailed by (voiceTranscriptions?.failed ?: NoTranscriptions).collectAsStateWithLifecycle()

    /**
     * Whether the model the settings name is actually on the phone.
     *
     * Read here rather than found out by pressing: the bubble should offer to set transcription up
     * when it is not set up, instead of offering to run something that cannot run.
     */
    val installedModels by (speechModels?.installed ?: NoModels).collectAsStateWithLifecycle()
    val chosenModel by settingsRepository.settings.collectAsStateWithLifecycle()
    val transcriptionReady = speechModels != null && chosenModel.speechModel in installedModels

    /**
     * Pictures pasted into the draft and not yet sent.
     *
     * Per conversation, and deliberately not saved across process death: they are copies in the
     * outbox that a restart would have no way to describe to you beyond a row of thumbnails you
     * did not put there this time.
     */
    var draftMedia by remember(chatId) { mutableStateOf<List<Uri>>(emptyList()) }
    /**
     * Documents picked from the "+" card and not sent yet, beside [draftMedia] in the same strip.
     *
     * Kept as the attachment rather than as a bare uri: a file's tile is its name, and the name is
     * read from the provider once, at the moment it is picked.
     */
    var draftFiles by remember(chatId) {
        mutableStateOf<List<com.ruskcoder.messages.data.model.Attachment.File>>(emptyList())
    }
    /**
     * The gallery pictures that are in the draft, and the copy of each that went in.
     *
     * Kept by the picture the grid drew rather than by what was staged: tapping a picture stages a
     * copy of it under a new uri, and without the pair there is no way back from the thing in the
     * draft to the thing on the grid — which is what un-ticking one has to find.
     */
    var gallerySelection by remember(chatId) { mutableStateOf<Map<Uri, Uri>>(emptyMap()) }

    /**
     * A game OpenPigeon has composed and handed over, waiting in the draft to be sent.
     *
     * Held rather than sent on arrival, and held *here* rather than inside the games panel. Two
     * reasons for holding it at all. The panel used to close the instant a message arrived, which
     * is why a game's options menu appeared for a split second and vanished — it was the picker
     * being torn down under it. And OpenPigeon's `ChooseGameCallback` composes a message *before*
     * you have configured a configurable game (it sets the config view and then falls through to
     * `addMessage` with no return), so sending on the first callback sends the default setup.
     * Holding it lets the options screen stay up, and a later choice simply replaces this.
     *
     * At screen scope because it outlives the panel: choosing a game stages it in the send bar and
     * the picker closes, exactly the way choosing a picture does. Per conversation for the reason
     * [draftMedia] is — a game set up for one thread is not a game for the next.
     */
    var pendingGame by remember(chatId) {
        mutableStateOf<com.bluebubbles.messaging.MadridMessage?>(null)
    }

    /**
     * The choices of a poll being written, or null when the draft has no poll in it.
     *
     * At screen scope and per conversation, exactly like [draftMedia] and [pendingGame], and for
     * the same reason: it is half of a message being composed, and the thing that sends it is the
     * bar's own send button. The words typed alongside it become the poll's question, so nothing
     * about the draft's text has to change to carry one.
     */
    var pollDraft by remember(chatId) { mutableStateOf<List<String>?>(null) }

    /**
     * The poll the draft is *editing*, rather than composing from nothing.
     *
     * Set by "Add Choice": the poll's choices come back into the send bar and the copy in the
     * thread stands down to a line until the edit is sent — see [PollPlaceholder]. Null means the
     * draft's poll is a new one.
     */
    var pollEditing by remember(chatId) { mutableStateOf<String?>(null) }

    /**
     * The choices that poll already had, as Apple named them.
     *
     * Kept because an option's identity is its `optionIdentifier`, not its text or its position:
     * votes point at identifiers, so a choice that came back for editing has to go out again under
     * the identifier it left with or every answer already given to it is silently thrown away.
     */
    var pollEditingOptions by remember(chatId) { mutableStateOf<List<PollOption>>(emptyList()) }

    /**
     * Whether the conversation has been scrolled back off its tail — which is what stops an arriving
     * message, or someone starting to type, from yanking the thread out from under you.
     *
     * The list is reversed, so the newest message is index 0 and anything above it means there is
     * conversation below the viewport. Three rather than one, to leave room for the row or two of
     * overscroll a fling settles back through.
     */
    val scrolledUp by remember {
        derivedStateOf { threadState.listState.firstVisibleItemIndex > 2 }
    }
    LaunchedEffect(reactionTarget) { if (reactionTarget != null) lastReactionAnchor = reactionTarget }

    val voice = rememberVoiceRecorder(coroutineScope)
    /** Shuts the send button between the tap and the bar returning to idle. See `onSendVoice`. */
    var sendingVoice by remember(chatId) { mutableStateOf(false) }
    val micPermission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    // Every tile in the "+" panel goes through a platform picker and comes back as a real message.
    val attachmentFlow = rememberAttachmentFlow(
        onPick = onPick@{ attachment ->
            attachmentPanelVisible = false
            // A document waits in the draft beside the pictures and goes when Send is pressed —
            // see [draftFiles]. Only what has nowhere in the bar to sit still sends on the spot.
            if (attachment is com.ruskcoder.messages.data.model.Attachment.File) {
                if (draftFiles.none { it.uri == attachment.uri }) draftFiles = draftFiles + attachment
                return@onPick
            }
            val quoted = replyTo
            replyTo = null

            coroutineScope.launch {
                // Photos, videos and documents are uploaded; a pin or contact card has no file, and
                // the sender turns those into text rather than leaving the recipient with nothing.
                imessageSender.sendAttachment(
                    context = context,
                    chatId = chatId,
                    identity = identity,
                    attachment = attachment,
                    uri = attachment.fileUri()?.toUri(),
                    caption = attachment.caption(),
                    replyTo = quoted,
                )
            }
        },
        // The gallery is the one source that fills the draft instead of sending — see
        // [AttachmentFlow.stage]. Copied into the outbox on the way in, for the same reason a paste
        // is: a picked uri's grant is not ours to rely on by the time the message is actually sent.
        onStage = { picked ->
            attachmentPanelVisible = false
            coroutineScope.launch {
                val kept = picked.mapNotNull { AttachmentStaging.stagePasted(context, it) }
                if (kept.isNotEmpty()) draftMedia = draftMedia + kept
            }
        },
    )
    /** The mic was tapped before the permission existed; recording starts as soon as it does. */
    var micWanted by remember { mutableStateOf(false) }
    LaunchedEffect(micPermission.granted) {
        if (micPermission.granted && micWanted) {
            micWanted = false
            voice.start()
        }
    }
    val composerFocusRequester = remember { FocusRequester() }

    /**
     * Hand the send bar the keyboard when this conversation was opened in order to answer it.
     *
     * An effect rather than a call in the body: a [FocusRequester] has nothing to give focus to
     * until the bar it is attached to has been composed, and this runs on the far side of that.
     *
     * The request is put down before it is acted on, so it cannot be honoured twice — a keyboard
     * that opens once is what was asked for, where one that reopens on every recomposition is a
     * keyboard that will not stay closed after the person dismissed it to read what they are
     * replying to.
     */
    val composeRequest by composeRequests.collectAsStateWithLifecycle()
    val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(composeRequest) {
        if (composeRequest != chatId) return@LaunchedEffect
        onComposerFocused()
        // A notification tap lands here while the shade is still animating away, so the window
        // does not have input focus yet — and a keyboard asked for by an unfocused window is
        // silently refused. Wait for the window, then keep trying for a moment: on a cold start
        // the bar may not be composed (or laid out) on the first frame the request is seen.
        kotlinx.coroutines.withTimeoutOrNull(2_000) {
            snapshotFlow { windowInfo.isWindowFocused }.first { it }
            repeat(20) {
                // Swallowed rather than propagated: a bar that is not there to take focus is a
                // keyboard that does not open, not a reason to take the screen down with it.
                val focused = runCatching { composerFocusRequester.requestFocus() }.isSuccess
                if (focused) {
                    keyboardController?.show()
                    return@withTimeoutOrNull
                }
                kotlinx.coroutines.delay(50)
            }
        }
    }

    /**
     * The measured height of the whole bottom bar — the composer plus, when it's open, the
     * attachment panel below it. The bar floats over the thread rather than sharing the column with
     * it, so the thread needs this to pad its scroll content clear of the bar's opaque parts.
     */
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    /**
     * The slot the bar rides on: the keyboard when it's up, the navigation bar otherwise. The
     * thread's viewport runs the full window height — an edge-to-edge activity is never resized by
     * `adjustResize` — so without adding this to the scroll inset the newest messages sit *behind*
     * the keyboard, which is what makes them look like they vanished.
     */
    //
    // Every inset below is read through a function rather than as a value, and that is the point.
    // The keyboard's inset changes on every frame of its animation, and a value read here in the
    // body of the screen recomposed the *entire* conversation screen — header, thread, send bar,
    // overlays — sixty times a second for as long as the keyboard was moving. Read inside a layout
    // or draw lambda, or inside a flow, the same number only re-lays-out what depends on it.
    val imeInsets = WindowInsets.ime
    val navigationInsets = WindowInsets.navigationBars
    val imeTargetInsets = WindowInsets.imeAnimationTarget
    fun navBottomNow(): Dp = with(density) { navigationInsets.getBottom(density).toDp() }
    fun bottomSlotNow(): Dp =
        with(density) { maxOf(imeInsets.getBottom(density), navigationInsets.getBottom(density)).toDp() }
    // The same slot with the keyboard left out of it is just [navBottomNow] — what an edit scrolls
    // against, since adding the keyboard's height walked the row being edited off the top.
    /**
     * Where the keyboard is *going* rather than where it is. A focused reply opens the keyboard as it
     * lifts the bubble, so aiming the stack at the keyboard's target is what makes the lift and the
     * keyboard one movement instead of the bubbles flying to the bottom of the screen and then
     * correcting once it arrives.
     */
    fun imeTargetNow(): Dp = with(density) { imeTargetInsets.getBottom(density).toDp() }
    fun focusSlotNow(): Dp = maxOf(imeTargetNow(), navBottomNow())

    /**
     * The keyboard's own height, so the attachment card can take exactly the slot it vacates.
     *
     * Seeded from disk rather than from a constant: the card only reads as the keyboard's
     * replacement if the two are the same size, and pressing "+" before ever typing — which is
     * most of the time somebody attaches a photo — used to fall back to a guess that was right on
     * no device in particular. See [KeyboardHeightStore]. The constant remains only for the very
     * first "+" of a fresh install, before any keyboard has stood to be measured.
     */
    var keyboardHeight by rememberSaveable { mutableStateOf(KeyboardHeightStore.read(context)) }

    /**
     * The card's own height: the keyboard's, plus the margin the send bar gives up as it opens.
     *
     * The margin has to be in here or the bar moves. With the keyboard up the bar rides 6dp above
     * the keys; with the card up it sits straight on the card, which brings the gap inside its own
     * inset instead. Those are two different distances, and swapping one for the other underneath a
     * bar that is not supposed to move dropped it by exactly that 6dp. Counted into the card, the
     * space below the bar is the same number in both states and nothing moves at all — the card's
     * glass simply starts 2dp lower than the keys did, which is not a distance anyone can see.
     */
    val panelHeight = (if (keyboardHeight > 0f) keyboardHeight.dp else DefaultPanelHeight) +
        ComposerBottomMargin
    // Where the keyboard is *going*, not where it is: the animating inset passes through every
    // height between zero and full, and sampling one of those left the panel short.
    LaunchedEffect(Unit) {
        // The IME inset runs to the bottom of the screen, navigation bar included; the panel sits
        // above that bar, so its share comes off first.
        snapshotFlow { imeTargetNow() - navBottomNow() }.collect { exposed ->
            // Whatever the keyboard is now, rather than the tallest one ever seen: an emoji sheet or
            // a one-off suggestion strip used to raise this permanently, leaving the card standing a
            // stripe taller than the keyboard it replaces for the rest of the install.
            if (exposed > MinKeyboardHeight && exposed.value != keyboardHeight) {
                keyboardHeight = exposed.value
                KeyboardHeightStore.write(context, exposed.value)
            }
        }
    }

    /**
     * How much room the card below the send bar is holding, and whether it took or gave up that
     * room instantly rather than sliding.
     *
     * The keyboard is a window laid *over* this one, not a thing this window contains — so the
     * honest way to open the card is to put it in place at full height behind the keyboard and let
     * the keyboard slide off it, revealing what was already there. Held separately from
     * [attachmentPanelVisible] because the slot outlives the card in one direction: when the
     * keyboard is called back, the card must go on holding its height until the keyboard has
     * climbed over it, or the send bar drops to the bottom of the screen and rides back up.
     */
    /**
     * How much taller than the keyboard's slot the "+" card is standing, in `GalleryMode.FULL`.
     *
     * The gallery takes this itself as it is scrolled, before the pictures move at all, so it is a
     * number a finger is holding rather than one being animated to — which is why the slot adds it
     * raw rather than folding it into [panelSlot].
     */
    var galleryExpansion by remember { mutableStateOf(0.dp) }
    /**
     * The room the slot below the send bar is actually measured with, in pixels.
     *
     * Read off the measurement rather than worked out from the screen: the column the card is the
     * last thing in is anchored to the bottom, so a card taller than what is left above the bar is
     * not moved up — it is laid out past the bottom of the column and the last band of it is simply
     * not drawn. That was the strip of pictures that went missing, growing as the card rose.
     */
    var gallerySlotRoomPx by remember { mutableIntStateOf(0) }
    /** Whether the gallery is standing anywhere but where it opened — read by back. */
    var galleryScrolled by remember { mutableStateOf(false) }
    /** Bumped to put a scrolled gallery back, leaving the card open. */
    var galleryResetKey by remember { mutableIntStateOf(0) }
    /** As far as the card may rise: everything but a thread's worth of room above the send bar. */
    val galleryMaxExpansion = run {
        val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - GalleryHeadroom
        val measured = if (gallerySlotRoomPx > 0) with(density) { gallerySlotRoomPx.toDp() } else screen
        (minOf(screen, measured) - panelHeight).coerceAtLeast(0.dp)
    }
    var panelSlotHeld by remember { mutableStateOf(false) }
    var panelSlotSnaps by remember { mutableStateOf(false) }
    /** Set when the card is closing into a keyboard on its way up, rather than into nothing. */
    var panelYieldingToKeyboard by remember { mutableStateOf(false) }
    /** True when the "+" was pressed with the keyboard up, so the card is opening behind it. */
    var panelOpeningUnderKeyboard by remember { mutableStateOf(false) }

    val panelSlot = animateDpAsState(
        targetValue = if (panelSlotHeld) panelHeight else 0.dp,
        // Nothing to animate when the keyboard is covering the slot: the movement the eye follows
        // is the keyboard's, and a card growing underneath it at its own speed is the second,
        // competing animation that made this feel like two things happening instead of one.
        animationSpec = if (panelSlotSnaps) snap() else tween(PanelSlotMs, easing = ChromeEasing),
        label = "attachmentSlot",
    )

    /**
     * Whether the card is in the tree at all — held on past [panelSlotHeld] for as long as the slot
     * is still sliding shut, so the card is there to be seen on its way out.
     *
     * Tracked rather than read off the animation: `panelSlot.value` changes every frame, and a
     * composition that reads it recomposes the whole conversation on every one of them, which is
     * the cost this whole arrangement exists to avoid.
     */
    var panelSlotOccupied by remember { mutableStateOf(false) }
    LaunchedEffect(panelSlotHeld, panelSlotSnaps) {
        if (panelSlotHeld) {
            panelSlotOccupied = true
        } else {
            if (!panelSlotSnaps) delay(PanelSlotMs.toLong())
            panelSlotOccupied = false
        }
    }

    /**
     * The gap the send bar keeps above whatever is standing under it, handed over as the card
     * arrives.
     *
     * With the keyboard up the bar rides this far above the keys. The card brings a gap of its own
     * inside its inset, so it takes this one over as it takes the slot — and since its height was
     * measured to include this, the two states leave the bar at exactly the same place.
     */
    fun composerGapNow(): Dp = (ComposerBottomMargin - panelSlot.value).coerceAtLeast(0.dp)

    /**
     * Everything the send bar's stack takes off the bottom of the screen: its own height, the gap
     * above whatever is under it, and whichever of the keyboard and the card that is.
     *
     * Whichever, not both. The keyboard is a window drawn over the card rather than under it, so
     * the two never stack — and adding both is what used to shrink and re-grow the conversation's
     * padding by a keyboard's height each time one replaced the other.
     *
     * Every part of this is read in the same composition, so the thread is never padded by a
     * mixture of one frame's card and another frame's keyboard. See the note on the inner column
     * that measures [bottomBarHeight].
     */
    fun composerStackNow(): Dp =
        bottomBarHeight + composerGapNow() + maxOf(bottomSlotNow(), navBottomNow() + panelSlot.value)

    /**
     * [composerStackNow] as one stable lambda, for the thread to read at layout time.
     *
     * Remembered once: everything it reads is snapshot state it looks up on each call, so the same
     * instance stays correct — and a new lambda every recomposition would stop the thread skipping.
     */
    val composerStackProvider: () -> Dp = remember { { composerStackNow() } }

    /**
     * True from the moment a focused reply asks for the keyboard until the keyboard is actually up.
     *
     * `imeAnimationTarget` only knows where the keyboard is going once the keyboard has agreed to
     * go there, and the request goes in on the very frame the bubble starts flying. For those few
     * frames the target still reads "closed", so the stack aims at the bottom of the screen and
     * then gets yanked upward when the real target arrives — which is the stutter. While this is
     * raised the destination is taken from the height the keyboard was last seen at instead, so the
     * bubble leaves already pointed at the right place.
     */
    var awaitingKeyboard by remember { mutableStateOf(false) }

    val imeVisible = WindowInsets.isImeVisible
    // The panel stands in for the keyboard rather than stacking with it, so whichever one opens
    // last wins: tapping the input closes the panel, the way tapping "+" closes the keyboard.
    //
    // It does not vacate the slot on the spot, though. The keyboard is already sliding up over the
    // card by the time this runs, so the card stays exactly where it is and lets the keyboard cover
    // it — the same trade in reverse, and for the same reason: one thing moves, not two.
    // Whatever put the card away, the gallery's extra height goes with it — but not the offset a
    // card that was *thrown* off is holding. See [closeAttachmentPanel].
    LaunchedEffect(attachmentPanelVisible) {
        if (!attachmentPanelVisible) galleryExpansion = minOf(galleryExpansion, 0.dp)
    }

    LaunchedEffect(imeVisible) {
        if (imeVisible && attachmentPanelVisible) {
            panelYieldingToKeyboard = true
            attachmentPanelVisible = false
        }
    }

    /**
     * The slot's side of that trade, wherever the card was closed from — the "+", the back gesture,
     * a tile that leads somewhere else. All of them only set [attachmentPanelVisible]; what the
     * height underneath does about it is decided once, here.
     */
    LaunchedEffect(attachmentPanelVisible, panelYieldingToKeyboard) {
        when {
            attachmentPanelVisible -> {
                // Reopened part-way through a handover — "+" pressed while the keyboard was still
                // climbing over the slot. The handover is off, or the next close would sit through
                // its 400ms wait for a keyboard that is not coming and then vanish without sliding.
                panelYieldingToKeyboard = false
                panelSlotSnaps = panelOpeningUnderKeyboard
                panelSlotHeld = true
            }
            // Hold the height until the keyboard has climbed over it, then let go underneath —
            // where letting go moves nothing, because the keyboard is already standing in the slot.
            panelYieldingToKeyboard -> {
                panelSlotSnaps = true
                delay(KeyboardCoverMs)
                panelSlotHeld = false
                panelYieldingToKeyboard = false
            }
            // Closed into an empty screen: there is nothing covering the slot, so the card is the
            // thing that has to move, and it slides out the way it slid in.
            else -> {
                panelSlotSnaps = false
                panelSlotHeld = false
            }
        }
    }

    /**
     * Opens the card in the keyboard's slot, and sends the keyboard away without taking the caret
     * with it.
     *
     * The draft stays focused throughout: what the "+" does is put the keyboard away, not end the
     * message you were writing, so a tap back on the pill brings the keys straight back rather than
     * having to re-enter a field you never left.
     */
    fun openAttachmentPanel() {
        // Recorded before the keyboard is asked to leave: `isImeVisible` flips the moment the hide
        // is requested, not when the keys are gone, so a moment later this cannot be read at all.
        panelOpeningUnderKeyboard = imeVisible
        // Whatever the gallery was left standing — or thrown — at, the card opens at its own height.
        galleryExpansion = 0.dp
        attachmentPanelVisible = true
        keyboardController?.hide()
    }

    // The sticker face starts at the card's own height, and hands back whatever it grew to when
    // it closes into the tiles.
    LaunchedEffect(stickersOpen) { galleryExpansion = minOf(galleryExpansion, 0.dp) }
    // A sticker lifted out of a card grown up the screen needs the conversation back to be dropped
    // on, so the card settles to its own height under the hand.
    LaunchedEffect(stickerDrag.active) {
        if (stickerDrag.active && stickersOpen && galleryExpansion > 0.dp) {
            androidx.compose.animation.core.animate(
                initialValue = galleryExpansion.value,
                targetValue = 0f,
            ) { value, _ -> galleryExpansion = value.dp }
        }
    }

    // The sticker search gives the keyboard back by bringing the card back, on the sticker face,
    // filtered by what was typed — the search was a trip out of the card, not a way to close it.
    LaunchedEffect(imeVisible) {
        if (!imeVisible && stickerSearching) {
            stickerSearching = false
            stickersOpen = true
            openAttachmentPanel()
        }
    }

    /**
     * Puts the card away, and puts back whatever it displaced.
     *
     * The "+" is a place you go and come back from, so coming back should leave the bar as it found
     * it: opened over the keyboard it hands the slot back to the keyboard, and opened over nothing
     * it closes into nothing. Anything else makes the button mean two different things depending on
     * a state you have to remember for yourself — and having to tap the draft again to carry on
     * writing a message you were already writing is the whole of what that costs.
     *
     * Only for the two ways of *dismissing* the card: the "+" itself, and back. A tile that leads
     * somewhere — a picker, the scheduled sheet, a game — closes the card on its way out, and
     * raising the keyboard behind a sheet that has just opened is not putting anything back.
     */
    fun closeAttachmentPanel() {
        attachmentPanelVisible = false
        // The card comes back at the keyboard's own height next time, not at whatever height the
        // gallery was left standing at — see [openAttachmentPanel], which is where it is cleared.
        //
        // Only the height it *gained* is given back here. A card pulled off the bottom by its
        // handle is holding an offset that has already taken it out of the slot, and zeroing that
        // put the whole card back on screen for the frame between the release and the slot
        // starting to slide — the jump up before it closed. Held, the slot is already empty and
        // there is nothing left to see.
        galleryExpansion = minOf(galleryExpansion, 0.dp)
        if (panelOpeningUnderKeyboard) {
            // The same handover as tapping the draft, because that is what this now is: the card
            // holds its slot while the keyboard climbs back over it, and lets go underneath.
            panelYieldingToKeyboard = true
            composerFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    /**
     * Which of the card's faces is showing, held past the moment the card is closed.
     *
     * Read straight off the three flags, the card flipped back to the grid as it went — a game
     * picked from the games face closes both at once, and the last thing you saw of the card was
     * something you had not been looking at.
     */
    val openFace = when {
        effectsOpen -> PanelFace.EFFECTS
        richTextOpen -> PanelFace.RICH_TEXT
        gamesOpen -> PanelFace.GAMES
        stickersOpen -> PanelFace.STICKERS
        else -> PanelFace.ATTACHMENTS
    }
    var panelFace by remember { mutableStateOf(PanelFace.ATTACHMENTS) }
    if (attachmentPanelVisible && panelFace != openFace) panelFace = openFace
    // The guess is only needed until the real thing turns up — and is dropped either way after a
    // moment, so a keyboard that never opens can't leave the stack hanging where one would be.
    LaunchedEffect(imeVisible) { if (imeVisible) awaitingKeyboard = false }
    LaunchedEffect(awaitingKeyboard) {
        if (awaitingKeyboard) {
            delay(KeyboardPredictionMs)
            awaitingKeyboard = false
        }
    }

    /**
     * Where the send bar will come to rest — the keyboard's own target once it has one, and until
     * then the height it was last seen at. Once the keyboard is actually up the two agree, so this
     * stops guessing on its own.
     */
    fun focusRestingSlotNow(): Dp = if (awaitingKeyboard && keyboardHeight > 0f) {
        maxOf(focusSlotNow(), keyboardHeight.dp + navBottomNow())
    } else {
        focusSlotNow()
    }

    /**
     * The keyboard on its way down, and how much of it is still showing.
     *
     * A lifted thread re-settles when the keyboard goes — off the send bar and into the middle of
     * the screen — and it used to wait to be told. Whether the keyboard is visible only turns false
     * once the keys have *finished* leaving, so the stack rode the bar down and then made a move of
     * its own afterwards: two movements for one dismissal. Read from where the keys are going and
     * how far they have got, the stack travels on the keyboard's own frames and arrives with it.
     */
    fun keyboardLeavingNow(): Boolean = imeTargetNow() == 0.dp && bottomSlotNow() > navBottomNow()
    fun keyboardShowingNow(): Float = if (keyboardHeight > 0f) {
        ((bottomSlotNow() - navBottomNow()) / keyboardHeight.dp).coerceIn(0f, 1f)
    } else {
        0f
    }
    /** How centred the lifted stack is: 0 resting on the send bar, 1 in the middle of the screen. */
    fun stackCenteringNow(): Float = if (awaitingKeyboard || imeTargetNow() > 0.dp) 0f else 1f - keyboardShowingNow()
    /**
     * The slot the stack is built up from: where the bar is going, or — while the keys are leaving —
     * where the bar actually is on this frame, since that is what is being followed.
     *
     * Only ever read while a stack is lifted, so only a lifted stack pays for following the keys.
     */
    fun stackSlotNow(): Dp = if (keyboardLeavingNow()) bottomSlotNow() else focusRestingSlotNow()

    /**
     * The name to put on the "has notifications silenced" line, or null when there is no line.
     *
     * A group never gets one: a Focus belongs to a person, and "somebody in here has one on" is not
     * a thing the conversation could act on even if Apple told us whose.
     */
    val focusSilencedName = if (chat?.notifsSilenced == true && !identity.group) {
        identity.name.ifBlank { "Recipient" }
    } else {
        null
    }

    /**
     * Whether the last thing said in here is still sitting silently on their phone.
     *
     * The newest message overall, not the newest of ours: once they have answered, the Focus
     * plainly did not stop them, and offering to ring their phone about a message they have already
     * replied past is offering to be a nuisance. Read as well as answered counts — a message that
     * has been read is not one that needs announcing.
     */
    val quietlyDelivered = messages.lastOrNull()?.let { newest ->
        newest.mine && newest.deliveredQuietly() && newest.status() == DeliveryStatus.DELIVERED
    } == true

    val chatThemeKey = chat?.theme ?: ChatThemeKey.DEFAULT
    val dark = settings.mode == ThemeMode.DARK
    val members = chat?.members ?: initialMembers

    /**
     * Who a message is drawn as being from, by exactly the rule the thread groups by.
     *
     * An overlay that lifts a bubble out of the conversation has to draw it the way the row did,
     * avatar gutter and all — otherwise it doesn't even start from where it was sitting.
     */
    // Groups only, exactly as the conversation itself decides it. In a one-to-one there is one other
    // person and the answer never changes, so the gutter was the same face repeated down the side of
    // every conversation — and a lift that drew one where the thread did not started from the wrong
    // place by a gutter's width.
    fun senderOf(message: Message): GroupMember? =
        if (message.mine || !identity.group) null else members.find { it.id == message.senderId() }

    /** The same, for the message this one quotes — the face that goes beside its outline. */
    fun quotedSenderOf(message: Message): GroupMember? =
        message.replyTo()?.id?.let { id -> messages.find { it.id == id } }?.let(::senderOf)


    /**
     * Every name an `@` in this conversation could be pointing at.
     *
     * Ours included, so a mention of you is highlighted in what someone else wrote — which is the
     * one it most matters to notice.
     */
    val mentionNames = remember(members, settings.profileName) {
        members.map { it.name } + settings.myMentionNames()
    }

    /** White on the header's dark backdrop: the bar is a translucent black film over the thread. */
    val headerIconTint = Color.White

    /** Everything a long press put on screen, together — they arrive together too. */
    fun closeMessageMenu() {
        if (menuTarget != null) menuClosing = true
        reactionTarget = null
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Message", text))
    }

    fun senderName(message: Message): String {
        if (message.mine) return "You"
        return members.find { it.id == message.senderId() }?.name ?: identity.name
    }

    val focused = focusedEntries.isNotEmpty()

    /**
     * How far out of focus the conversation is behind a lift: 0 sharp, 1 fully blurred.
     *
     * The blur is applied to the thread itself rather than replayed from the recording the frosted
     * bars sample, which is what lets a lifted bubble stay *inside* that recording — see the note
     * on [FocusedReplyOverlay]'s scrim. On the overlay's own clock, so the two are one movement.
     */
    val focusBlur by animateFloatAsState(
        targetValue = if (focused && !focusClosing) 1f else 0f,
        animationSpec = tween(if (focused && !focusClosing) FOCUS_OPEN_MS else FOCUS_CLOSE_MS),
        label = "focusBlur",
    )



    /**
     * The draft as it stands, and how it is dressed, put back together.
     *
     * The two can fall out of step for a frame — the field reports its text through one callback
     * and this screen carries the marks separately — so the marks are re-derived against whatever
     * the string actually is rather than trusted blindly. Almost always a no-op.
     */
    fun currentDraft(): RichDraft =
        if (richDraft.text == composerText) richDraft else richDraft.afterEdit(composerText, pendingFormat)

    /**
     * What the formatting controls are acting on, and what they should light up for.
     *
     * A selection wears one format or, where it spans a boundary, none — see
     * [RichDraft.formatOver]. A bare caret wears whatever the next character typed will.
     */
    fun activeFormat(): RunFormat? =
        if (draftSelection.collapsed) pendingFormat else currentDraft().formatOver(draftSelection.min, draftSelection.max)

    /** Applies one change to the selection, or to what is about to be typed where there is none. */
    fun applyFormat(transform: (RunFormat) -> RunFormat) {
        if (draftSelection.collapsed) {
            pendingFormat = transform(pendingFormat)
            return
        }
        richDraft = currentDraft().mapRange(draftSelection.min, draftSelection.max, transform)
    }

    /**
     * Arms an effect and plays it where it will actually be seen.
     *
     * A bubble effect plays on the preview, which is the message; a screen effect plays over the
     * whole screen, aimed at that same preview. Pressing the armed one again disarms and plays
     * nothing — there is nothing to show about *not* having an effect.
     */
    fun pickEffect(effect: MessageEffect?) {
        armedEffect = effect
        // Only the screen half is played from here. A bubble effect's state is rebuilt whenever the
        // armed effect changes, so calling play() on this line would reach the instance belonging
        // to the *previous* effect — which, on the first press, is the one built for null and does
        // nothing at all. The effect below runs after the rebuild, against the right one.
        if (effect != null && effect.kind == EffectKind.SCREEN) screenEffects.play(effect, previewBox)
    }

    /** Leaves the effects mode, keeping or dropping whatever was armed. */
    fun closeEffects(keep: Boolean) {
        effectsOpen = false
        attachmentPanelVisible = false
        if (!keep) armedEffect = null
    }

    // The bar is not what you are working in while an effect is chosen — the message is up on the
    // screen and the pill has gone out of focus with the conversation — so the caret goes too. Left
    // blinking in a blurred field it was the one sharp thing on a screen that had stepped back.
    LaunchedEffect(effectsOpen) { if (effectsOpen) focusManager.clearFocus() }

    // Plays the newly armed bubble effect on the preview, once, after the state for it exists.
    LaunchedEffect(armedEffect) {
        val armed = armedEffect ?: return@LaunchedEffect
        if (armed.kind == EffectKind.BUBBLE) previewEffect.play()
    }

    /**
     * Who a message came from, as a handle — which is the key a poll's votes are filed under.
     *
     * The domain model does not carry one: a group message has the sender's handle in `senderId`,
     * your own messages have nothing (there is only one you), and a one-to-one has nothing either
     * because there is only one other person. The roster fills in that last case.
     */
    fun senderHandleOf(message: Message): String = when {
        message.mine -> myHandles.firstOrNull() ?: LOCAL_SENDER
        else -> message.senderId() ?: members.firstOrNull { it.id !in myHandles }?.id ?: identity.name
    }

    /**
     * Where every poll in this conversation stands, keyed by the message that asked it.
     *
     * Assembled from the thread rather than stored: a vote is an update sent against the poll's own
     * balloon, so the standing is a view of the conversation and there is nothing that can drift
     * out of step with it. Recomputed only when the messages change.
     */
    val pollStates = remember(messages, myHandles, members) {
        pollStatesIn(messages, myHandles, ::senderHandleOf)
    }

    /**
     * Us, as a face — so our own vote shows up in the stack on a choice like everybody else's.
     *
     * Built from the profile rather than looked up in the roster: a group's members are the other
     * people in it, and we are never in that list.
     */
    val mePerson = remember(settings.profileName, settings.profilePhotoUri) {
        GroupMember(
            id = myHandles.firstOrNull() ?: LOCAL_SENDER,
            name = settings.displayName(),
            initials = settings.displayInitials(),
            color = "#8E8E93",
            photoUri = settings.profilePhotoUri,
        )
    }

    /**
     * A choice on a poll was pressed: answer it, or take the answer back.
     *
     * An *update against the poll's own balloon* — never a reply, and never a new message. That is
     * the whole point: a vote sent as a message is a bubble reading "I vote Tacos" sitting under a
     * poll that still says nobody has voted, on every device in the thread. See
     * [IMessageSender.sendPollUpdate], and [votePayload] for what an answer carries — our own
     * choices and nothing else, so two people answering at once cannot overwrite each other.
     */
    fun votePoll(poll: Message, option: PollOption) {
        val state = pollStates[poll.id] ?: return
        val handle = myHandles.firstOrNull() ?: LOCAL_SENDER
        val choices = state.toggled(option)
        if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        coroutineScope.launch {
            imessageSender.sendPollUpdate(chatId, identity, poll.id, votePayload(choices, handle))
        }
    }

    /**
     * "Add Choice": bring the poll back into the send bar.
     *
     * Deliberately not an edit in place. The thread is where messages are read; the composer is
     * where they are written, and a poll being extended is a message being written. It also means
     * the choice list gets the same card, the same growing-row rule and the same keyboard it had
     * when the poll was first asked, instead of a dialog with one text field in it.
     *
     * The poll's title comes back into the draft as well, so the words above the choices stay
     * editable — and the copy in the thread drops to a line while this is open, so there are never
     * two polls on screen disagreeing about what the choices are.
     */
    fun editPoll(poll: Message) {
        val state = pollStates[poll.id] ?: return
        pollEditing = poll.id
        pollEditingOptions = state.options
        pollDraft = state.options.map { it.text } + ""
        composerText = state.title.takeIf { it != Polls.DEFAULT_TITLE }.orEmpty()
        richDraft = RichDraft()
        attachmentPanelVisible = false
        composerFocusRequester.requestFocus()
        keyboardController?.show()
    }

    fun sendMessage() {
        // A poll swallows the draft's words: they are its title, not a message of its own. It also
        // goes out on a path of its own — a balloon, not text — so the ordinary send below never
        // sees it, and the formatting marks go with it, since they described a string that is now
        // a poll.
        val poll = pollDraft?.map { it.trim() }?.filter { it.isNotBlank() }?.takeIf { it.size >= 2 }
        val pollTitle = composerText
        val editingPoll = pollEditing
        val previousOptions = pollEditingOptions
        val body = if (poll != null) "" else composerText
        val pictures = draftMedia
        val files = draftFiles
        val game = pendingGame
        // A poll card that is open but not yet answerable stops the send outright, rather than
        // falling through and sending the question as an ordinary message with the poll silently
        // discarded. The bar gates its own button the same way; this is the second half of it.
        if (pollDraft != null && poll == null) return
        if (poll == null && body.isBlank() && pictures.isEmpty() && files.isEmpty() && game == null) return
        val runs = if (poll != null) emptyList() else currentDraft().runs()
        val effect = armedEffect
        // The effect belongs to the message that is going, not to the bar: the next one starts
        // plain, exactly as the next one starts empty.
        armedEffect = null
        effectsOpen = false
        composerText = ""
        richDraft = RichDraft()
        pendingFormat = RunFormat.Plain
        draftMedia = emptyList()
        draftFiles = emptyList()
        gallerySelection = emptyMap()
        pendingGame = null
        pollDraft = null
        pollEditing = null
        pollEditingOptions = emptyList()
        // The picker is still up when a game is sent — it stays open behind the bar so the game's
        // options can be answered — so sending has to put it away, the way choosing a picture does.
        if (game != null) {
            gamesOpen = false
            attachmentPanelVisible = false
        }
        // The short confirmation buzz the Chats setting describes.
        if (settings.haptics) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
        // No edit branch: the composer only ever sends now. Rewriting a message happens in the row
        // the message is already in, and the field there commits it directly.
        // Captured before the send starts: sending is asynchronous now that it may go to Apple, and
        // both of these are cleared or changed by the time it finishes.
        val quoted = replyTo
        val wasFocused = focused
        replyTo = null

        coroutineScope.launch {
            // One message per picture, in the order they were pasted, and the words last. Bundling
            // them as captions on the first picture would put text under an image the sender never
            // wrote it about; sending the words first would have them arrive as a comment on
            // pictures that had not shown up yet.
            //
            // The quote goes on the first thing sent, whatever that is: a reply is a reply to one
            // message, and repeating it on every picture would quote the same thing five times.
            var carriedQuote = quoted
            // The poll leads, and on its own path: a poll is a balloon, not text and not a file.
            // Extending one is an update against the balloon already in the thread rather than a
            // second poll — and the options that came back for editing go out again under the
            // identifiers they arrived with, because that is what the votes already cast point at.
            if (poll != null) {
                if (editingPoll == null) {
                    imessageSender.sendPoll(chatId, identity, pollTitle, poll, carriedQuote)
                } else {
                    val state = pollStates[editingPoll]
                    val handle = myHandles.firstOrNull() ?: LOCAL_SENDER
                    // Matched by position: the editor hands the choices back in the order it was
                    // given them, so row `i` is the option that was row `i`. A row past the end is
                    // new and gets an identifier of its own; a row that was deleted takes its
                    // option — and the votes for it — with it, which is what deleting a choice is.
                    val options = poll.mapIndexed { index, text ->
                        previousOptions.getOrNull(index)?.takeIf { it.text == text }
                            ?: previousOptions.getOrNull(index)?.copy(text = text, attributedText = text)
                            ?: Polls.newOption(text, handle)
                    }
                    if (state != null) {
                        imessageSender.sendPollUpdate(
                            chatId,
                            identity,
                            editingPoll,
                            optionsPayload(state.copy(title = pollTitle.ifBlank { state.title }), options, handle),
                        )
                    }
                }
                carriedQuote = null
            }
            // The game leads. It is the thing the message is about — anything typed alongside it is
            // a note on the game, and a note cannot arrive before what it is about. It goes out on
            // its own path rather than through `sendAttachment`: a game balloon is an app payload,
            // not a file, and the extension already composed it.
            if (game != null) {
                val bundleId = openPigeonClient.balloonIdentity()?.bundleId
                    ?: com.ruskcoder.messages.games.GamePigeon.BUNDLE_ID
                imessageSender.sendMadridGameInBackground(
                    context = context,
                    chatId = chatId,
                    identity = identity,
                    invite = com.ruskcoder.messages.games.MadridBridge.fromMadrid(
                        game,
                        bundleId = bundleId,
                    ),
                    artwork = com.ruskcoder.messages.games.MadridBridge.artwork(game),
                )
            }
            pictures.forEach { uri ->
                imessageSender.sendAttachment(
                    context = context,
                    chatId = chatId,
                    identity = identity,
                    attachment = mediaAttachmentFor(context, uri),
                    uri = uri,
                    replyTo = carriedQuote,
                )
                carriedQuote = null
            }
            files.forEach { file ->
                imessageSender.sendAttachment(
                    context = context,
                    chatId = chatId,
                    identity = identity,
                    attachment = file,
                    uri = file.uri.toUri(),
                    replyTo = carriedQuote,
                )
                carriedQuote = null
            }
            if (body.isBlank()) return@launch
            val sent = imessageSender.sendText(chatId, identity, body, carriedQuote, runs, effect)
            // In focus mode the reply joins the lifted stack instead of the thread behind the blur:
            // the quoted message rises to make room and the reply settles under it. The
            // conversation behind it is not what is being read, so it is left where it was.
            // Nothing to do in the thread's case: the row landing at the tail is what moves the
            // conversation, and only if it was at the tail to begin with. See the effect on
            // `messages.lastOrNull()`.
            if (wasFocused && sent != null) {
                // The same reading the rest of the stack gets — see [stackEntries]. A message
                // landing under one of your own continues that run rather than starting its own,
                // so a handful of sends is one stack of bubbles instead of a column of them each
                // wearing a tail and a name of its own.
                val under = focusedEntries.lastOrNull()?.message
                val continues = under != null && under.mine == sent.mine && under.senderId() == sent.senderId()
                focusedEntries = focusedEntries.mapIndexed { index, entry ->
                    if (continues && index == focusedEntries.lastIndex) entry.copy(isLastInRun = false) else entry
                } + FocusedEntry(
                    sent,
                    originTop = null,
                    isFirstInRun = !continues,
                    sender = senderOf(sent),
                    quotedSender = quotedSenderOf(sent),
                    // A reply joining the stack answers the bubble directly above it — that is what
                    // a stack is — so the reference it would draw is a picture of a message already
                    // on screen, an inch away. Left to work it out for itself it had no idea what
                    // was above it, drew the outline anyway, and the stack came out with a
                    // reference in it that vanished the moment the thread was reopened from the
                    // conversation, where the adjacency is visible.
                    quoteAdjacent = true,
                    hideQuote = true,
                )
                // And the next thing typed answers *this*, not the message the overlay opened on.
                //
                // The quote was cleared when the send started, as it is everywhere else, which left
                // a second message going out with nothing on it at all: it joined the stack on
                // screen and belonged to no thread whatsoever underneath, so reopening the thread
                // from the conversation found the first reply and nothing after it. While the
                // overlay is up the stack *is* the conversation, and everything sent into it goes
                // on the end of the same chain. Cleared again when the overlay closes.
                replyTo = ReplyPreview(sent.id, senderName(sent), sent.preview(), sent.mine)
            }
        }
    }

    /**
     * Where a message sits in its run of same-sender neighbours — the shape it is wearing at the
     * moment it is picked up, which the overlay then lifts it out with.
     *
     * The thread's own rule, called rather than mirrored. Kept as a second copy of the test it
     * drifted from it: the thread ends a run at a section break and this did not, so any message
     * that ended a section had a tail until you pressed it, at which point the lifted copy came up
     * without one.
     */
    fun runFlags(message: Message): Pair<Boolean, Boolean> =
        runFlagsFor(messages, messages.indexOfFirst { it.id == message.id }, firstUnreadId)

    /** The thread's own answer to whether this reply sits directly under what it answers. */
    fun quoteAdjacent(message: Message): Boolean =
        quoteAdjacentFor(messages, messages.indexOfFirst { it.id == message.id }, firstUnreadId)

    /**
     * The messages leading to this one through their quotes, oldest first, ending with it.
     *
     * Walked only through messages this conversation actually holds, and guarded against a cycle —
     * `replyTo` comes off the wire. A message that answers nothing is a chain of one.
     */
    fun replyChainOf(message: Message): List<Message> {
        val byId = messages.associateBy { it.id }
        val chain = mutableListOf(message)
        val seen = mutableSetOf(message.id)
        var current = message
        while (true) {
            val parent = current.replyTo()?.id?.let(byId::get) ?: break
            if (!seen.add(parent.id)) break
            chain += parent
            current = parent
        }
        return chain.asReversed()
    }

    /**
     * A run of messages as the focused overlay stacks them: in order, each read against its
     * neighbours *in the stack*, every quote closed.
     *
     * [swipedId] and [fromX] are the message a reply swipe let go of and how far it had dragged it,
     * so that one bubble flies on from where the swipe left it; everything else sets off from its
     * own place in the thread.
     */
    fun stackEntries(
        thread: List<Message>,
        viewport: Float,
        swipedId: String? = null,
        fromX: Float = 0f,
    ): List<FocusedEntry> =
        thread.mapIndexed { index, entry ->
            // Read against the *stack*, not against the conversation it was pulled out of.
            //
            // A thread is scattered: its messages are neighbours here and were nowhere near each
            // other there. Carried over, the run flags described a grouping that no longer exists —
            // a message that had been in the middle of somebody's run arrived with neither the
            // name above it nor the face beside it, because in the conversation the message
            // overhead had both and this one was a continuation of it. In the stack the message
            // overhead is a different person's, so it is the first of its own run and says who
            // wrote it. The same reading gives it back its tail, and with it the avatar, which is
            // only drawn on the message that wears one.
            val previous = thread.getOrNull(index - 1)
            val next = thread.getOrNull(index + 1)
            val first = previous == null || previous.mine != entry.mine || previous.senderId() != entry.senderId()
            val last = next == null || next.mine != entry.mine || next.senderId() != entry.senderId()
            FocusedEntry(
                message = entry,
                originTop = threadState.originFor(entry.id, viewport),
                originX = if (entry.id == swipedId) fromX else 0f,
                isFirstInRun = first,
                isLastInRun = last,
                quoteAdjacent = quoteAdjacent(entry),
                // The stack *is* the thread, in order — so every quote in it would be a picture of
                // the bubble directly above it. See [FocusedEntry.hideQuote].
                hideQuote = true,
                sender = senderOf(entry),
                quotedSender = quotedSenderOf(entry),
            )
        }

    /**
     * Replying takes one of the two shapes the reply-mode setting offers: a quote card on the send
     * bar, or the message itself lifting out of the conversation into the focused overlay.
     *
     * [fromX] is where the reply swipe left the bubble. The overlay flies on from there rather than
     * waiting for it to spring back, so the swipe and the lift read as one gesture.
     */
    fun startReply(message: Message, fromX: Float = 0f) {
        replyTo = ReplyPreview(message.id, senderName(message), message.preview(), message.mine)
        // Whichever shape the reply takes, it exists to be typed into — a long press, a swipe or the
        // selection bar all reach here with the input unfocused.
        composerFocusRequester.requestFocus()
        keyboardController?.show()
        if (settings.replyMode != ReplyMode.FOCUS) return
        // The bubble is about to fly at wherever the send bar is going to end up, and the keyboard
        // has only just been asked to move it.
        awaitingKeyboard = !imeVisible
        focusClosing = false
        // A message that is itself an answer lifts as the thread it answers, not as a bubble with
        // an outline of the question hung over it. The outline is the conversation's shorthand for
        // "this refers to something a long way up"; in the stack that something is right there, so
        // it comes up as the message it is and the reference is closed. Only the chain leading *to*
        // this message — what you are answering is this, not whatever was said under it later.
        val chain = replyChainOf(message)
        if (chain.size > 1) {
            val viewport = threadState.listState.layoutInfo.viewportSize.height.toFloat()
            focusedEntries = stackEntries(chain, viewport, swipedId = message.id, fromX = fromX)
            return
        }
        val (first, last) = runFlags(message)
        focusedEntries = listOf(
            FocusedEntry(
                message = message,
                originTop = threadState.rowRect(message.id)?.bubbleTop,
                originX = fromX,
                isFirstInRun = first,
                isLastInRun = last,
                quoteAdjacent = quoteAdjacent(message),
                sender = senderOf(message),
                quotedSender = quotedSenderOf(message),
                // Lifted on its own it is drawn exactly as its row is, the tail and the face a
                // connector gives it included — see [FocusedEntry.forcedTail].
                forcedTail = threadState.wearsForcedTail(message.id),
                forcedAvatar = threadState.wearsForcedAvatar(message.id),
            ),
        )
    }

    /**
     * Lifts a whole reply thread out of the conversation and stacks it, in order, over the blur.
     *
     * The case a quoted reply has never had an answer to: a thread more than two messages long is
     * scattered through a conversation, and reading it meant scrolling between bubbles that quote
     * each other. Lifted, it is simply the conversation it always was, in one place.
     *
     * Every bubble makes the trip over the same span of time, however far it has to come — see
     * [MessageThreadState.originFor], which is what gives the ones that were off screen somewhere
     * to travel *from*. They arrive together because they left together, not because they moved at
     * the same speed.
     *
     * The keyboard is deliberately not asked for. Opening a thread is a request to read one, and
     * where it comes to rest says which: on the send bar with the keys up, in the middle of the
     * screen without them. Typing is what moves it — tapping the input walks the stack down onto
     * the bar. Either way the reply goes to the newest message in the thread, which is what you are
     * answering when you answer a conversation.
     */
    fun openThread(message: Message) {
        // Not while selecting: a tap there picks the message, and the thread lifting out over the
        // checkboxes took the selection's screen away from under it.
        if (focused || selecting) return
        val thread = threadOf(messages, message.id)
        // One message is not a thread — nothing to lift it out of, and nothing to read.
        if (thread.size < 2) return
        val viewport = threadState.listState.layoutInfo.viewportSize.height.toFloat()
        focusClosing = false
        awaitingKeyboard = false
        val newest = thread.last()
        replyTo = ReplyPreview(newest.id, senderName(newest), newest.preview(), newest.mine)
        focusedEntries = stackEntries(thread, viewport)
    }

    /** Starts the return trip; the overlay tells us when the bubbles have landed. */
    fun closeFocus() {
        // A reply sent while the overlay was up has a row waiting at the end of a thread that was
        // held still the whole time it was open, so the conversation is brought back to its tail
        // before the bubbles are asked where to fly home to — otherwise the reply aims at a row
        // below the fold.
        if (focusedEntries.any { it.originTop == null }) {
            coroutineScope.launch { threadState.scrollToEnd(animated = false) }
        }
        focusClosing = true
    }

    /** Find-in-conversation — see [InChatSearchState]. */
    val search = rememberInChatSearch(chatId, startInSearch, chatRepository, threadState)

    /**
     * The message a search result opened the conversation at, lit up for a moment once it is on
     * screen so the eye lands on it — then let go, so it does not stay highlighted for good.
     */
    var arrivedAt by remember(chatId) { mutableStateOf<String?>(null) }
    LaunchedEffect(chatId, initialMessageId) {
        val id = initialMessageId ?: return@LaunchedEffect
        // Anywhere in the history, not only what the window holds — see [ChatRepository.ensureLoaded].
        if (!chatRepository.ensureLoaded(chatId, id)) return@LaunchedEffect
        var frames = 0
        while (!threadState.holds(id) && frames++ < ScrollWaitFrames) withFrameNanos { }
        threadState.scrollToMessage(id)
        arrivedAt = id
        delay(ArrivalHighlightMs)
        arrivedAt = null
    }
    /** Rows the overlay is drawing instead of the thread. */
    // Whole rows, drawn entirely by an overlay: a long press, and a focused reply, both of which
    // lift the message *and* the quote above it.
    val hiddenIds = remember(focusedEntries) {
        focusedEntries.filterNot { it.hideQuote }.map { it.message.id }.toSet()
    }
    /**
     * Rows whose bubble alone is away.
     *
     * A lifted thread leaves every quote and connector behind — the stack is the thread in order,
     * so it draws none of them — and those belong to the row. Taking them with the bubble made the
     * conversation lose pieces of itself the moment the lift began; left where they are, only the
     * message travels. The same flag decides both, since "the overlay is not drawing the quote" is
     * exactly "the row must keep it".
     */
    val bubbleHiddenIds = remember(focusedEntries, menuTarget) {
        focusedEntries.filter { it.hideQuote }.map { it.message.id }.toSet() +
            // A long press lifts the message and nothing it is joined to. The quote over it and the
            // connector reaching up from it are the row's; taken along with the bubble, the line
            // blinked out the moment the message was pressed. See [FocusedMessageOverlay].
            setOfNotNull(menuTarget?.message?.id)
    }

    /**
     * Pairs a message with its row's measured place in the thread. A row that isn't laid out — which
     * a long press can't reach anyway — falls back to the top of the conversation.
     */
    // The row being rewritten has to stay in sight of the keyboard that opened for it. Keyed on the
    // slot as well as the keyboard, so the bar growing a line — the field wrapping onto a second —
    // is enough to check again.
    LaunchedEffect(editingId) {
        val id = editingId ?: return@LaunchedEffect
        // Pinned rather than merely rescued: every one of these is something that moved the field
        // or the space under it, and "it was clear a moment ago" is not the question. The field
        // growing a line is included, which is what `editorHeight` is for. Collected rather than
        // keyed, so the keyboard sliding in does not recompose the screen on each frame to say so.
        snapshotFlow { Triple(bottomSlotNow(), bottomBarHeight, editorHeight) }.collectLatest { (slot, bar, _) ->
            threadState.revealAbove(
                messageId = id,
                insetPx = with(density) { (bar + slot + EditRevealMargin).roundToPx() },
                force = true,
            )
        }
    }

    fun messageAnchor(message: Message, rect: ThreadRowRect?): MessageAnchor {
        val (first, last) = runFlags(message)
        return MessageAnchor(
            message,
            rect?.top ?: 0f,
            rect?.height ?: 0f,
            bubbleTop = rect?.bubbleTop ?: 0f,
            isFirstInRun = first,
            isLastInRun = last,
            quoteAdjacent = quoteAdjacent(message),
            sender = senderOf(message),
            quotedSender = quotedSenderOf(message),
            forcedTail = threadState.wearsForcedTail(message.id),
            forcedAvatar = threadState.wearsForcedAvatar(message.id),
        )
    }

    /**
     * The shape a message is drawn with in the conversation right now: its run, and whatever tail
     * and face a connector has given it. What a bubble lifted into the focused stack changes back
     * into on its way home. Null for a message the conversation doesn't hold.
     */
    fun homeShapeOf(message: Message): HomeShape? {
        if (messages.none { it.id == message.id }) return null
        val (first, last) = runFlags(message)
        return HomeShape(
            isFirstInRun = first,
            isLastInRun = last,
            forcedTail = threadState.wearsForcedTail(message.id),
            forcedAvatar = threadState.wearsForcedAvatar(message.id),
        )
    }

    // A message lands at the tail, so the conversation follows it there. Keyed on the newest
    // message rather than fired at the send: the row only exists once the store has written it and
    // the thread has re-collected.
    LaunchedEffect(messages.lastOrNull()?.id) {
        val last = messages.lastOrNull() ?: return@LaunchedEffect
        if (focused) return@LaunchedEffect
        // Only when you were already at the bottom, and yours is no exception. The list keeps
        // its anchor on whichever row was first visible, so a message arriving underneath it was
        // laid out below the viewport and simply left there — you were at the tail, and the tail
        // moved without you; this is the send animation's own slide, the conversation following the
        // new message down by exactly one bubble's height as that bubble grows in.
        //
        // Scrolled back through history, the anchor is the whole point. An arrival must not drag
        // you away from what you were reading — and neither must a send. Writing a line while
        // looking at something further up is a perfectly ordinary thing to be doing, and being
        // thrown to the bottom for it loses your place with no way back but scrolling. The
        // jump-to-latest button is there for when you do want the tail.
        if (!scrolledUp) threadState.scrollToEnd()
    }

    // The typing row opens at the very bottom of the conversation, so it moves the tail for the
    // same reason a new message does. It grows out of index 0 on a reversed list — upward from the
    // bottom edge — so this is usually already true and the scroll is a no-op; it is here for the
    // case where the anchor had drifted a row or two off the bottom.
    LaunchedEffect(typingSenders) {
        if (typingSenders.isNotEmpty() && !focused && !scrolledUp) threadState.scrollToEnd()
    }

    // Deliberately nothing here. Tapping the input used to bring the tail back into view above the
    // keyboard, which is right when you were at the tail already — and there the list does it by
    // itself, since it is anchored to the bottom and the keyboard only changes the padding under
    // it. Scrolled back through the history it was simply wrong: reaching for the draft is not a
    // request to be taken somewhere else, and the one place you cannot get back to by scrolling is
    // the place you were before something moved you.

    // Back unwinds whatever is layered on top of the thread, innermost first — after the keyboard,
    // which takes the first press for itself the way it does everywhere else on the platform.
    BackHandler(enabled = search.open || selecting || selectingText || menuTarget != null || reactionTarget != null || attachmentPanelVisible || gamesOpen || stickersOpen || stickerSearching || richTextOpen || effectsOpen || focused || quickFormatOpen) {
        when {
            menuTarget != null || reactionTarget != null -> closeMessageMenu()
            // The editor has a back handler of its own, nested deeper; this is only the fallback for
            // an edit whose row has been scrolled out of the list and disposed.
            editingId != null -> editingId = null
            search.open -> search.close()
            focused -> closeFocus()
            selecting -> { selecting = false; selectedIds = emptySet() }
            // Back steps out of the panel sub-pages before closing the panel itself
            gamesOpen -> gamesOpen = false
            stickerSearching -> stickerSearching = false
            stickersOpen -> stickersOpen = false
            effectsOpen -> closeEffects(keep = false)
            richTextOpen -> richTextOpen = false
            // A gallery that has been scrolled into is a place inside the card, so back comes
            // out of it to the card as it opened — tiles showing — rather than closing the card
            // from under a scroll position you were still reading.
            attachmentPanelVisible && (galleryScrolled || galleryExpansion > 0.dp) -> {
                galleryResetKey++
                coroutineScope.launch {
                    androidx.compose.animation.core.animate(
                        initialValue = galleryExpansion.value,
                        targetValue = 0f,
                    ) { value, _ -> galleryExpansion = value.dp }
                }
            }
            // Last: the attachment panel itself (contains the three sub-pages above)
            attachmentPanelVisible -> closeAttachmentPanel()
            else -> quickFormatOpen = false
        }
    }

    // The bars are frosted glass over the conversation, and a focused reply or a picked-out message
    // blurs it away behind what has been lifted off it — so the conversation records itself for all
    // of them to sample.
    val backdrop = rememberChatBackdrop()

    // The header blurs and dims with the conversation while a message is picked out or a reply is
    // focused. Left sharp above a blurred thread it read as a hole in the overlay rather than the
    // top of it.
    // Except while the selection bar is up: the copy button is the one thing that has to be sharp
    // and tappable over the blur, and blurring the bar it sits in would put it out of reach.
    val chromeDimmed = (menuTarget != null && !menuClosing && !selectingText) || (focused && !focusClosing)
    // Matched to the overlay's own blur and film, so the header recedes by exactly as much as the
    // conversation under it does rather than reading as a second, heavier layer.
    val chromeBlur by animateDpAsState(if (chromeDimmed) 7.dp else 0.dp, tween(240), label = "chromeBlur")
    val chromeScrim by animateFloatAsState(if (chromeDimmed) 0.26f else 0f, tween(240), label = "chromeScrim")

    ChatThemeProvider(themeKey = chatThemeKey, darkTheme = dark, hue = chat?.themeHue ?: ChatThemeKey.DEFAULT_HUE) {
      CompositionLocalProvider(
          LocalChatBackdrop provides backdrop,
          LocalSettings provides settings,
          LocalAttachmentRetry provides onRetryDownloads,
          // Provided over the whole screen rather than only over the thread, so a poll lifted into
          // the long-press overlay or a focused reply still shows the votes it has collected and
          // can still be answered from where it is standing.
          LocalPollHost provides PollHost(
              state = { id -> pollStates[id] },
              onVote = ::votePoll,
              onAddChoice = ::editPoll,
              editing = pollEditing,
              // Ourselves included: our own vote belongs in the stack on a choice like anyone
              // else's, and we are never in a group's member list.
              member = { handle ->
                  if (handle in myHandles || handle == mePerson.id) mePerson
                  else members.firstOrNull { it.id == handle }
              },
              // The roster is the other people — we are never in it — so one is added for us. A
              // one-to-one whose roster has not been filled in yet still has somebody on the other
              // end.
              participants = members.count { it.id !in myHandles }.coerceAtLeast(1) + 1,
          ),
      ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Every finger on the screen belongs to a sticker while one is being carried; let
                // go over a bubble, it is stuck there where it was dropped, as big and as turned.
                .stickerDragHost(stickerDrag) { drop ->
                    val target = stickerTargets.at(drop.center) ?: return@stickerDragHost false
                    val emoji = drop.emoji ?: return@stickerDragHost false
                    if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    // Only one that actually stuck is a recent — lifting it and changing your mind
                    // leaves the list as it was.
                    if (drop.movingId == null) {
                        com.ruskcoder.messages.data.repository.RecentEmoji.push(context, emoji)
                    }
                    imessageSender.sendSticker(
                        context = context,
                        chatId = chatId,
                        messageId = target.messageId,
                        messageText = target.text,
                        emoji = emoji,
                        placement = stickerPlacementOn(target.rect, drop.center, drop.scale, drop.rotation),
                        bubbleWidth = with(density) { target.rect.width.toDp().value },
                        replacing = drop.movingId,
                    )
                    true
                },
        ) {
          // Everything on the screen, recorded — the conversation, the app bar and the send bar
          // alike — so the effects mode can put one blur over the lot. A second recording rather
          // than the one the frosted bars sample: those *are* replays of that first one, and a
          // layer that draws itself while being recorded draws nothing. This one is taken around
          // them and replayed by a sibling, which is the only arrangement that works.
          Box(modifier = Modifier.fillMaxSize().chatBackdropSource(screenBackdrop)) {
            Scaffold(
                topBar = {
                  Box {
                   // Clipped to the bar's own bounds: an unbounded blur smears the header down over the
                   // conversation it is supposed to be sitting on.
                   // The three bars share one slot and trade places by fading through each other
                   // rather than being swapped between frames. They are all TopAppBars, so the slot
                   // is the same height whichever of them is in it — and during a swap both are on
                   // screen at once, which is what keeps the conversation below from jumping.
                   Box(modifier = Modifier.blur(chromeBlur, BlurredEdgeTreatment.Rectangle)) {
                    AnimatedVisibility(visible = search.open, enter = BarEnter, exit = BarExit) {
                        InChatSearchBar(
                            query = search.query,
                            onQueryChange = { search.query = it },
                            current = if (search.hits.isEmpty()) 0 else search.index + 1,
                            total = search.hits.size,
                            onPrevious = { search.step(-1) },
                            onNext = { search.step(1) },
                            onClose = search::close,
                            // The wider search is its own page, still scoped to this conversation.
                            onAdvanced = { search.close(); onOpenSearch(chatId) },
                        )
                    }
                    // Search wins the slot outright: it is the one you asked for most recently, and
                    // both being true at once is only ever a frame of overlap on the way in or out.
                    AnimatedVisibility(visible = !search.open && selecting, enter = BarEnter, exit = BarExit) {
                        SelectionBar(
                            count = selectedIds.size,
                            // Same fifteen-minute window the long-press menu applies. Two ways in
                            // to one editor, and only one of them honouring the limit would be a
                            // limit in name only.
                            canEdit = selectedIds.size == 1 && messages.any { it.id in selectedIds && it.editable() },
                            canReply = selectedIds.size == 1,
                            onClose = { selecting = false; selectedIds = emptySet() },
                            onCopy = {
                                // In the order they appear in the conversation, not the order they
                                // happened to be ticked in.
                                copyToClipboard(messages.filter { it.id in selectedIds }.joinToString("\n") { it.preview() })
                                selecting = false; selectedIds = emptySet()
                            },
                            onDelete = { showDeleteDialog = true },
                            onEdit = {
                                val target = messages.find { it.id in selectedIds && it.editable() }
                                selecting = false; selectedIds = emptySet()
                                // Same editor the long-press menu opens: the row, not the composer.
                                if (target != null) editingId = target.id
                            },
                            onReply = {
                                val target = messages.find { it.id in selectedIds }
                                selecting = false; selectedIds = emptySet()
                                if (target != null) startReply(target)
                            },
                            onForward = { showForwardSheet = true },
                            canSave = messages.any { it.id in selectedIds && it.savableAttachments().isNotEmpty() },
                            onSave = {
                                // In conversation order, so the gallery lists them the way they
                                // were sent rather than the order they happened to be ticked in.
                                val files = messages.filter { it.id in selectedIds }.flatMap { it.savableAttachments() }
                                selecting = false; selectedIds = emptySet()
                                coroutineScope.launch {
                                    val saved = withContext(Dispatchers.IO) {
                                        files.count { (uri, name) -> saveAttachmentToDevice(context, uri, name) != null }
                                    }
                                    Toast.makeText(
                                        context,
                                        when {
                                            saved == files.size && saved == 1 -> "Saved"
                                            saved == files.size -> "Saved $saved items"
                                            saved == 0 -> "Couldn't save these"
                                            else -> "Saved $saved of ${files.size}"
                                        },
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )
                    }
                    // Highlighting one message's text takes the slot the same way, and for the
                    // same reason: the thing that acts on the selection is a button, and a button
                    // needs somewhere to live that the overlay's blur doesn't reach.
                    AnimatedVisibility(visible = !search.open && !selecting && selectingText, enter = BarEnter, exit = BarExit) {
                        TextSelectionBar(
                            canCopy = bubbleSelection.selected.isNotEmpty(),
                            onClose = ::closeMessageMenu,
                            onCopy = { copyToClipboard(bubbleSelection.selected); closeMessageMenu() },
                        )
                    }
                    // The header itself only fades. Sliding it as well would send two bars past
                    // each other in the same direction, which reads as a stutter rather than a swap.
                    AnimatedVisibility(
                        visible = !search.open && !selecting && !selectingText,
                        enter = fadeIn(tween(220)),
                        exit = fadeOut(tween(140)),
                    ) {
                      // The header is the same frosted glass as the send bar: the blur sits on its
                      // own layer behind the bar so the title and icons stay sharp.
                      Box {
                        if (backdrop != null) {
                            Box(modifier = Modifier.matchParentSize().chatBackdropBlur(backdrop, RectangleShape))
                        }
                        TopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { if (identity.group) onOpenGroup(chatId) else onOpenContact(chatId) },
                                ) {
                                    if (identity.group) {
                                        GroupAvatar(
                                            members = identity.members.orEmpty(),
                                            fallbackInitials = identity.initials,
                                            fallbackColor = MaterialTheme.colorScheme.primaryContainer,
                                            size = 40.dp,
                                        )
                                    } else {
                                        Avatar(
                                            initials = identity.initials,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            size = 40.dp,
                                            photoUri = identity.photoUri,
                                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            labelSize = 15.sp,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
// Two lines that have to read as one block, which means neither may carry the
                                     // leading its own type size would normally reserve. The title
                                     // is set at the bar's 22sp with a 28sp line box; stacked above
                                     // a 12sp subtitle with a 16sp one, the pair kept nearly a
                                     // line's worth of empty space between them and the whole block
                                     // sat low in the bar. Trimmed to their glyphs and given one
                                     // explicit gap, they sit at a natural line height.
                                     val trimmed = LineHeightStyle(
                                         alignment = LineHeightStyle.Alignment.Center,
                                         trim = LineHeightStyle.Trim.Both,
                                     )
                                     Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                         Text(
                                             identity.name.ifBlank { "New chat" },
                                             maxLines = 1,
                                             overflow = TextOverflow.Ellipsis,
                                             style = LocalTextStyle.current.copy(lineHeightStyle = trimmed),
                                         )
                                         // Only show group members; typing never shows in the top navbar.
                                         val subtitle = when {
                                             identity.group -> groupSubtitle(members)
                                             else -> null
                                         }
                                         if (subtitle != null) {
                                             Text(
                                                 subtitle,
                                                 fontSize = 12.sp,
                                                 maxLines = 1,
                                                 overflow = TextOverflow.Ellipsis,
                                                 color = LocalContentColor.current.copy(alpha = 0.7f),
                                                 style = LocalTextStyle.current.copy(lineHeightStyle = trimmed),
                                             )
                                         }
                                     }
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = headerIconTint)
                                }
                            },
                            actions = {
                                // One-to-one only, and handed to the phone's own calling app: there
                                // is no in-app calling, and a group has no one number to ring.
                                if (!identity.group) {
                                    IconButton(onClick = {
                                        // A one-to-one is filed under the other person's handle.
                                        dial(members.firstOrNull()?.phone?.takeIf { it.isNotBlank() } ?: chatId, identity.name)
                                    }) { Icon(Icons.Rounded.Call, contentDescription = "Call", tint = headerIconTint) }
                                }
                                IconButton(onClick = { headerMenuOpen = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = headerIconTint)
                                }
                                AppDropdownMenu(expanded = headerMenuOpen, onDismissRequest = { headerMenuOpen = false }) {
                                    AppMenuItem(
                                        text = { Text("Search in chat") },
                                        trailingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                        onClick = { headerMenuOpen = false; search.open = true },
                                    )
                                    AppMenuItem(
                                        text = { Text("Advanced search") },
                                        trailingIcon = { Icon(Icons.AutoMirrored.Rounded.ManageSearch, contentDescription = null) },
                                        onClick = { headerMenuOpen = false; onOpenSearch(chatId) },
                                    )
                                    // Groups only. A one-to-one chat used to carry this greyed out,
                                    // offering to start a group it could not actually start.
                                    if (identity.group) {
                                        AppMenuItem(
                                            text = { Text("Add people") },
                                            trailingIcon = { Icon(Icons.Rounded.GroupAdd, contentDescription = null) },
                                            onClick = {
                                                headerMenuOpen = false
                                                coroutineScope.launch {
                                                    pickableContacts = contactsRepository.loadContacts()
                                                    showAddPeople = true
                                                }
                                            },
                                        )
                                    }
                                }
                            },
                            // Lighter over a real blur — the flat fill is only carrying the whole
                            // translucent look when there's nothing behind it to frost.
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = barFilm(backdrop),
                                titleContentColor = Color.White,
                            ),
                        )
                      }
                    }
                   }
                   if (chromeScrim > 0f) {
                       Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = chromeScrim)))
                   }
                  }
                },
            ) { padding ->
                // The header overlays the conversation rather than sitting above it — the thread's
                // viewport runs the full height and only its scroll *content* is inset, which is
                // what lets bubbles pass under the Apple bar's blur. Same idea at the bottom, where
                // the send bar floats over the thread.
                Box(modifier = Modifier.fillMaxSize()) {
                    // Everything the frosted bars sample is recorded here — the wallpaper and the
                    // conversation scrolling over it, but not the bars themselves.
                    ChatWallpaper(
                        photoUri = chat?.wallpaperUri,
                        modifier = Modifier
                            .chatBackdropSource(backdrop)
                            // Two fingers on the conversation size its text. There is no setting
                            // for this any more — the gesture is the control — so the value it
                            // lands on is written down as the fingers come off.
                            .pinchZoom(
                                onZoom = { factor ->
                                    val base = if (pinchTextScale > 0f) pinchTextScale else storedSettings.textScale()
                                    pinchTextScale = (base * factor).coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
                                },
                                onZoomEnd = {
                                    val settled = pinchTextScale
                                    if (settled > 0f) settingsRepository.update { it.copy(messageTextScale = settled) }
                                    pinchTextScale = 0f
                                },
                            ),
                    ) {
                        // The conversation goes out of focus behind a lift by being blurred where
                        // it stands. Only the thread, not the wallpaper under it: what is being
                        // pushed back is the messages, and a flat colour or a faint doodle has
                        // nothing in it a blur would change.
                        Box(
                            modifier = Modifier.graphicsLayer {
                                if (backdrop != null && focusBlur > 0.01f) {
                                    val radius = FocusBlurRadius.toPx() * focusBlur
                                    renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                                }
                            },
                        ) {
                        CompositionLocalProvider(
                            com.ruskcoder.messages.ui.components.LocalMemberActions provides memberActions,
                            LocalStickerTargets provides stickerTargets,
                            com.ruskcoder.messages.ui.components.LocalStickerDrag provides stickerDrag,
                        ) {
                        MessageThread(
                            messages = messages,
                            // Near the oldest loaded message, the window reaches back another page.
                            onNearOldest = { chatRepository.loadOlder(chatId) },
                            members = members,
                            swipeTimestampsEnabled = settings.swipeTimestamps,
                            selecting = selecting,
                            selectedIds = selectedIds,
                            state = threadState,
                            // A lifted message keeps its place in the thread but is drawn by the
                            // overlay instead.
                            hiddenIds = hiddenIds,
                            bubbleHiddenIds = bubbleHiddenIds,
                            transfers = attachmentTransfers,
                            highlightedId = search.currentHitId ?: arrivedAt,
                            peerName = identity.name.ifBlank { "They" },
                            topInset = padding.calculateTopPadding(),
                            // Everything the bar covers: the slot it rides on (keyboard or
                            // navigation bar) plus its own measured height, panel included.
                            // The keyboard's slot counts while editing too. Held at the resting
                            // inset instead, the conversation's own padding shrank by the
                            // keyboard's height the moment an edit began — which is the jolt
                            // that dropped the row being typed into back under the send bar.
                            // Read by the list at layout time, never here — see [composerStackNow].
                            bottomInsetProvider = composerStackProvider,
                            focusSilencedName = focusSilencedName,
                            // Offered only while there is something to break through for. The line
                            // above stands either way — they have a Focus on regardless — it simply
                            // has nothing to offer about it.
                            onNotifyAnyway = if (quietlyDelivered) {
                                { imessageSender.notifyAnyway(chatId) }
                            } else {
                                null
                            },
                            editingId = editingId,
                            onEditorHeight = { editorHeight = it },
                            onEditCommit = { text ->
                                val id = editingId
                                editingId = null
                                if (id != null) imessageSender.edit(chatId, id, text)
                            },
                            onEditCancel = { editingId = null },
                            onReply = { message, fromX -> startReply(message, fromX) },
                            // Long press picks the message out and offers what can be done to it:
                            // with the menu on, the reaction bar comes up alongside it, and with it
                            // off the message is selected outright the way Google Messages does —
                            // still with the reactions, which belong to the one picked message.
                            onLongPress = { message ->
                                // The keyboard goes first. Whatever a long press opens — the menu
                                // with its bubble lifted into the middle of the screen, or the
                                // selection bar — wants the whole screen to work in, and the half
                                // of it the keyboard was holding is exactly where the message ends
                                // up. Left standing, the menu came up over the keys.
                                keyboardController?.hide()
                                // And the draft gives up focus with it. Hiding the keys on their own
                                // is a request the field can overrule: it still holds its input
                                // session, and anything that touches the bar while the menu comes
                                // up — the chrome dimming it, for one — brought the keyboard straight
                                // back. Replying from the menu asks for focus again, so nothing that
                                // wants the draft loses it.
                                focusManager.clearFocus()
                                // And so does whatever the "+" had open. The formatting card takes
                                // the keyboard's own slot, so leaving it standing left the menu
                                // opening over a panel that had nothing to do with the message
                                // being pressed — the keyboard's replacement, still holding the
                                // half of the screen the lifted bubble needs.
                                attachmentPanelVisible = false
                                richTextOpen = false
                                if (effectsOpen) closeEffects(keep = false)
                                // Measured where the row actually sits, so the bars can hang off the
                                // message rather than off the bottom of the screen.
                                val anchor = messageAnchor(message, threadState.rowRect(message.id))
                                if (settings.longPressMenu) {
                                    menuClosing = false
                                    menuTarget = anchor
                                } else {
                                    selecting = true
                                    selectedIds = setOf(message.id)
                                    if (message.canReact()) reactionTarget = anchor
                                }
                            },
                            onDoubleTap = { message ->
                                if (message.canReact()) {
                                    when (settings.doubleTapAction) {
                                        DoubleTapAction.REACTIONS ->
                                            reactionTarget = messageAnchor(message, threadState.rowRect(message.id))
                                        DoubleTapAction.QUICK -> imessageSender.toggleTapback(chatId, message.id, message.preview(), settings.quickReactionEmoji())
                                        DoubleTapAction.NONE -> Unit
                                    }
                                }
                            },
                            onToggleSelect = { id ->
                                // Reactions belong to one message, so they go away as soon as the
                                // selection is a batch.
                                reactionTarget = null
                                selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                                selecting = selectedIds.isNotEmpty()
                            },
                            // A tap opens whatever the message has to show full screen. Only photos
                            // and videos answer this; everything else it turns down, and the thread
                            // takes the tap instead to open that message's own time-and-status line.
                            onTap = { message, onBubble ->
                                val media = (message as? Message.AttachmentMessage)?.attachment?.asViewableMedia()
                                val invite = (message as? Message.AttachmentMessage)?.attachment as? Attachment.GameInvite
                                val group = (message as? Message.AttachmentMessage)?.attachment as? Attachment.MediaGroup
                                when {
                                    // A card stack opens the card on top; the grid bubble opens the
                                    // whole group two to a row.
                                    group != null && onBubble -> {
                                        editingDraft = null
                                        if (settings.multiMediaStyle == com.ruskcoder.messages.data.model.MultiMediaStyle.STACK) {
                                            viewingGroup = group
                                            viewingMedia = MediaStackPositions.top(group)
                                        } else {
                                            // The tile the finger was on opens on its own, with the
                                            // grid a button away in the viewer; the "+N" tile, or a
                                            // tap nothing saw land, opens the whole group.
                                            val tile = MediaTilePresses.take(group)
                                            val picked = tile?.let { group.items.getOrNull(it) }?.asViewableMedia()
                                            if (picked != null) {
                                                viewingGroup = group
                                                viewingMedia = picked
                                            } else {
                                                mediaPage = group
                                            }
                                        }
                                        true
                                    }
                                    // Only the picture itself opens the picture. The quote hanging
                                    // above a reply and the connector beside it are parts of the
                                    // row, not of the message, and a tap on either of them is
                                    // asking about the conversation the message belongs to.
                                    media != null && onBubble -> {
                                        viewingMedia = media
                                        // Sent, so there is nothing left to draw on — and nothing
                                        // a draft opened earlier left behind here either.
                                        editingDraft = null
                                        true
                                    }
                                    // Same rule as the picture: only the tile itself opens the game,
                                    // not the row's own delivery-line toggle. The invite's payload
                                    // is decoded and handed to the pigeon app, which runs the game;
                                    // a move made there comes back as the next balloon on the same
                                    // session.
                                    // A turn the game has moved past is a summary line, not a
                                    // board: the tap belongs to the row, as it does for any other
                                    // message you cannot act on.
                                    // OpenPigeon opens the game itself: it is handed the message
                                    // the bubble was tapped from and launches its own activity,
                                    // then reports a played turn back through the same handle. The
                                    // choice is the app-wide setting, not the bubble — nothing on
                                    // the wire records which app opened a game, and a thread whose
                                    // older bubbles stopped opening would be worse than either.
                                    invite != null && invite.url != null && !invite.superseded && onBubble &&
                                        openPigeonClient.isInstalled() -> {
                                        val bundleId = invite.bundleId
                                            ?: openPigeonClient.balloonIdentity()?.bundleId
                                            ?: com.ruskcoder.messages.games.GamePigeon.BUNDLE_ID
                                        openPigeonClient.openGame(
                                            message = com.ruskcoder.messages.games.MadridBridge.toMadrid(
                                                invite,
                                                guid = message.id,
                                            ),
                                            userCount = members.size.coerceAtLeast(2),
                                            onLocalMove = { played ->
                                                imessageSender.sendMadridGameInBackground(
                                                    context = context,
                                                    chatId = chatId,
                                                    identity = identity,
                                                    invite = com.ruskcoder.messages.games.MadridBridge.fromMadrid(
                                                        played,
                                                        bundleId = bundleId,
                                                        previous = invite,
                                                    ),
                                                    continues = message.id,
                                                )
                                            },
                                        )
                                        true
                                    }
                                    invite != null && invite.url != null && !invite.superseded && onBubble -> {
                                        val state = com.ruskcoder.messages.games.GamePigeon.decode(invite.url!!)
                                        pigeonClient.openGame(
                                            session = invite.sessionId,
                                            state = state,
                                            senderUuid = com.ruskcoder.messages.games.GamePigeon.senderUuid(context),
                                            // Not on this screen's scope: opening the game puts
                                            // pigeon in front, and a move comes back long after
                                            // this composition has stopped — or been disposed —
                                            // which cancelled the send and left the turn on that
                                            // device only. The thread is where the game lives, so
                                            // the send outlives whoever opened it.
                                            onLocalMove = { updated ->
                                                imessageSender.sendGameTurnInBackground(
                                                    context = context,
                                                    chatId = chatId,
                                                    identity = identity,
                                                    // The move updates the bubble it was played
                                                    // from, so the turn has to name that row
                                                    // rather than only its session.
                                                    messageId = message.id,
                                                    state = updated,
                                                )
                                            },
                                        )
                                    }
                                    // A message that is part of a conversation-within-the-
                                    // conversation opens that instead of its own delivery line.
                                    // The thread is the more useful answer to "what is this?", and
                                    // it is the only way to reach one that isn't all on screen.
                                    message.inThread(messages) -> {
                                        openThread(message)
                                        true
                                    }
                                    else -> false
                                }
                            },
                            myHandles = myHandles,
                            mentionNames = mentionNames,
                            firstUnreadId = firstUnreadId,
                            typingSenders = typingSenders,
                            isGroup = identity.group,
                            onOpenThread = ::openThread,
                            // A message that lands carrying one plays it once, aimed at its bubble.
                            onScreenEffect = { effect, at -> screenEffects.play(effect, at) },
                            onReactionsClick = { message ->
                                if (!message.reactions().isNullOrEmpty()) reactorsTarget = message
                            },
                            onFailureClick = { message -> failureId = message.id },
                            transcribing = transcribing,
                            transcriptionFailed = transcriptionFailed,
                            transcriptionReady = transcriptionReady,
                            // Null on a device the native library was not built for, which takes
                            // the action off every bubble rather than offering one that cannot work.
                            onTranscribe = voiceTranscriptions
                                ?.takeIf { it.isSupported }
                                ?.let { service ->
                                    { message: Message ->
                                        // The same button does the next useful thing: fetch a model
                                        // where there is none, and transcribe where there is one.
                                        if (!transcriptionReady) onOpenTranscriptionSettings()
                                        else (message as? Message.Audio)?.let(service::transcribe)
                                        Unit
                                    }
                                },
                            onVoicePlayed = { message -> imessageSender.markPlayed(chatId, message.id) },
                        )
                        }
                        }

                        // Inside the recording the frosted bars sample, and over the conversation
                        // it lifted from. Both matter: outside it, every bubble in flight dropped
                        // out from under the app bar the moment it left the thread.
                        //
                        // But outside the blur, and a sibling of it rather than a child: a lifted
                        // stack drawn inside the layer that blurs the conversation was blurred
                        // along with it, which put the focus-thread's own messages behind the very
                        // effect that is supposed to be separating them from the thread. The blur
                        // wraps the conversation alone; everything lifted off it is drawn sharp,
                        // on top.
                        if (focused) {
                            FocusedReplyOverlay(
                                entries = focusedEntries,
                                composerInset = with(density) { (stackSlotNow() + bottomBarHeight + 4.dp).toPx() },
                                // No keyboard means nothing is being typed into, so there is no send
                                // bar for the stack to belong to — it belongs to the screen instead.
                                // Handed over in step with the keys as they leave — see [keyboardLeavingNow].
                                centering = stackCenteringNow(),
                                following = keyboardLeavingNow(),
                                // Where a scrolled thread stops, so its oldest message parks under
                                // the app bar rather than behind it.
                                topInset = with(density) { padding.calculateTopPadding().toPx() },
                                // Off-screen rows included: a lifted thread can reach well past both
                                // edges of the conversation, and every one of those bubbles has to
                                // know where it is going home to.
                                measureReturn = { messageId ->
                                    threadState.originFor(messageId, threadState.listState.layoutInfo.viewportSize.height.toFloat())
                                },
                                // What each message looks like back in the conversation, for the
                                // stack to change back into on the way home.
                                homeShape = ::homeShapeOf,
                                closing = focusClosing,
                                onRequestClose = ::closeFocus,
                                onClosed = {
                                    focusedEntries = emptyList()
                                    focusClosing = false
                                    replyTo = null
                                },
                                // The conversation is moved first and the overlay told to close
                                // after, so the bubble that was pressed is already underneath the
                                // one in the stack: it has nowhere to travel, and the rest of the
                                // thread lands around a message that never moved.
                                onMessageClick = { message, bubbleTop ->
                                    coroutineScope.launch {
                                        threadState.alignBubble(message.id, bubbleTop)
                                        focusClosing = true
                                    }
                                },
                                myHandles = myHandles,
                            )
                        }
                    }

                    // The WhatsApp system notice that sits centered above a conversation's first
                    // message — which, on an empty thread resting on the send bar, is just above
                    // the composer.
                    if (messages.isEmpty() && threadLoaded) {
                        Text(
                            "Messages you send here are saved on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                // The same number the conversation's own bottom padding uses, so
                                // the notice rides on the bar rather than drifting off it while the
                                // keyboard and the card trade places. See `composerStackNow`.
                                .offset { IntOffset(0, -(composerStackNow() + 12.dp).roundToPx()) }
                                .padding(horizontal = 24.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }

                    // A long press picks the message out of the conversation: everything behind
                    // it blurs, the bubble lifts to a resting place clear of the send bar, and the
                    // reactions and the menu arrive attached to it. The row it came from is hidden
                    // in the thread, so the message is only ever drawn once.
                    menuTarget?.let { anchor ->
                        val target = anchor.message
                        // A photo group, and for a card stack the picture currently on top of it.
                        val targetGroup = (target as? Message.AttachmentMessage)?.attachment as? Attachment.MediaGroup
                        val targetSingle = (target as? Message.AttachmentMessage)?.attachment
                        val targetImage = targetSingle as? Attachment.Image
                        val stackTop = targetGroup
                            ?.takeIf { settings.multiMediaStyle == com.ruskcoder.messages.data.model.MultiMediaStyle.STACK }
                            ?.let { MediaStackPositions.top(it) }
                        FocusedMessageOverlay(
                            // An unsent message has its words put back while they are being
                            // selected: the tombstone says the message was unsent, and there is
                            // nothing on it to drag a handle through.
                            message = if (selectingText && target is Message.Deleted && target.text.isNotBlank()) {
                                Message.Text(
                                    id = target.id,
                                    text = target.text,
                                    mine = target.mine,
                                    time = target.time,
                                    createdAt = target.createdAt,
                                )
                            } else {
                                target
                            },
                            originTop = anchor.bubbleTop,
                            isFirstInRun = anchor.isFirstInRun,
                            isLastInRun = anchor.isLastInRun,
                            quoteAdjacent = anchor.quoteAdjacent,
                            sender = anchor.sender,
                            quotedSender = anchor.quotedSender,
                            forcedTail = anchor.forcedTail,
                            forcedAvatar = anchor.forcedAvatar,
                            // Asked on every frame of the way home, since the press itself put the
                            // keyboard away and moved the row — see [FocusedMessageOverlay].
                            returnTop = { threadState.rowRect(target.id)?.bubbleTop },
                            myHandles = myHandles,
                            topInset = padding.calculateTopPadding(),
                            bottomInset = composerStackNow(),
                            backdrop = backdrop,
                            // Only our own, so the bar lights up what tapping again would remove
                            // rather than whatever anyone in the group happened to pick.
                            activeReactions = if (target.canReact()) target.reactions().orEmpty().mine(myHandles) else null,
                            onPickReaction = { emoji ->
                                imessageSender.toggleTapback(chatId, target.id, target.preview(), emoji)
                                closeMessageMenu()
                            },
                            // Past the window an edit is one the other end declines, so the
                            // menu stops offering it rather than offering a no-op.
                            editable = target.editable(),
                            // An unsent message offers what it said and a way to clear it, and none of
                            // the things that act on a message that still exists.
                            onReply = if (target is Message.Deleted) null else ({ closeMessageMenu(); pendingAction = { startReply(target) } }),
                            // The overlay's only part in an edit is getting out of the way: the
                            // bubble goes home and the row it lands in becomes the editor.
                            onEdit = { closeMessageMenu(); pendingAction = { editingId = target.id } },
                            copyLabel = when {
                                stackTop != null -> "Copy current"
                                targetImage != null -> "Copy Image"
                                else -> "Copy"
                            },
                            onCopy = when {
                                // A video has nothing a clipboard can usefully hold, and the text
                                // behind a photo or a grid is only its preview ("Photo"), which is
                                // not what anyone pressing Copy on a picture wants.
                                stackTop is ViewableMedia.Clip -> null
                                targetSingle is Attachment.Video -> null
                                targetGroup != null && stackTop == null -> null
                                stackTop != null || targetImage != null -> ({
                                    val copied = copyMediaToClipboard(context, stackTop?.uri ?: targetImage!!.uri)
                                    Toast.makeText(
                                        context,
                                        if (copied) "Copied" else "Couldn't copy this one",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    closeMessageMenu()
                                })
                                target !is Message.Deleted -> ({ copyToClipboard(target.preview()); closeMessageMenu() })
                                target.text.isNotBlank() -> ({ copyToClipboard(target.text); closeMessageMenu() })
                                else -> null
                            },
                            // Highlighting part of a message only means something where there are
                            // words: a photo or a voice note has nothing to drag a handle through.
                            selection = if (selectingText) bubbleSelection else null,
                            onSelectText = if (target is Message.Text || (target is Message.Deleted && target.text.isNotBlank())) {
                                { selectingText = true }
                            } else {
                                null
                            },
                            // The one action that leaves something behind: the conversation stays in
                            // the checkbox column with this message already ticked.
                            onSelectMessages = if (target is Message.Deleted) {
                                null
                            } else {
                                { closeMessageMenu(); pendingAction = { selecting = true; selectedIds = setOf(target.id) } }
                            },
                            onDeleteForMe = if (target is Message.Deleted) {
                                {
                                    closeMessageMenu()
                                    pendingAction = { chatRepository.deleteMessages(chatId, listOf(target.id), forEveryone = false) }
                                }
                            } else {
                                null
                            },
                            // Only our own messages can be retracted, only while they still
                            // exist, and only for iMessage's two minutes (or while one is still
                            // going out, which is calling it off) — offering it anywhere else would
                            // promise something iMessage will not do.
                            onUnsend = if (imessageSender.canUnsend(target)) {
                                {
                                    // A grouped bubble stands for several messages. A card stack
                                    // retracts the card on top; the grid bubble retracts all of them.
                                    val group = (target as? Message.AttachmentMessage)?.attachment as? Attachment.MediaGroup
                                    val ids = when {
                                        group == null -> listOf(target.id)
                                        settings.multiMediaStyle == com.ruskcoder.messages.data.model.MultiMediaStyle.STACK ->
                                            listOf(MediaStackPositions.topMessageId(group) ?: target.id)
                                        else -> group.messageIds.ifEmpty { listOf(target.id) }
                                    }
                                    closeMessageMenu()
                                    pendingAction = { ids.forEach { imessageSender.unsend(chatId, it) } }
                                }
                            } else {
                                null
                            },
                            // Who has seen it is only a question worth asking about your own
                            // message, and only where there is more than one person to answer for.
                            onInfo = if (target.mine && target !is Message.Deleted && identity.group && members.isNotEmpty()) {
                                { closeMessageMenu(); pendingAction = { infoTarget = target } }
                            } else {
                                null
                            },
                            // Anything with a real file behind it can be taken out of the app;
                            // everything else offers no row rather than a greyed-out one.
                            downloadLabel = when {
                                targetGroup != null -> "Save all"
                                targetImage != null || targetSingle is Attachment.Video -> "Save to Gallery"
                                else -> "Download"
                            },
                            onDownloadCurrent = stackTop?.let { top ->
                                {
                                    closeMessageMenu()
                                    coroutineScope.launch {
                                        val saved = withContext(Dispatchers.IO) {
                                            saveAttachmentToDevice(context, top.uri)
                                        }
                                        Toast.makeText(
                                            context,
                                            if (saved != null) "Saved to your gallery" else "Couldn't save this one",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            onDownload = target.savableAttachments().takeIf { it.isNotEmpty() }?.let { files ->
                                {
                                    closeMessageMenu()
                                    coroutineScope.launch {
                                        val results = withContext(Dispatchers.IO) {
                                            files.map { (uri, name) -> saveAttachmentToDevice(context, uri, name) }
                                        }
                                        val saved = results.count { it != null }
                                        Toast.makeText(
                                            context,
                                            when {
                                                files.size > 1 -> when (saved) {
                                                    files.size -> "Saved $saved items to your gallery"
                                                    0 -> "Couldn't save these"
                                                    else -> "Saved $saved of ${files.size}"
                                                }
                                                results.first() == SavedTo.GALLERY -> "Saved to your gallery"
                                                results.first() == SavedTo.DOWNLOADS -> "Saved to Downloads"
                                                else -> "Couldn't save this one"
                                            },
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            // So a lifted voice message says exactly what the one in the thread
                            // says — see [FocusedMessageOverlay].
                            transcription = when {
                                voiceTranscriptions?.isSupported != true -> TranscriptionState.OFF
                                target.id in transcribing -> TranscriptionState.RUNNING
                                target.id in transcriptionFailed -> TranscriptionState.FAILED
                                else -> TranscriptionState.IDLE
                            },
                            closing = menuClosing,
                            onRequestClose = ::closeMessageMenu,
                            onClosed = {
                                menuTarget = null
                                menuClosing = false
                                // Held until the bubble has landed: dropped the moment the close
                                // began, the app bar would swap back to the header while the
                                // message it belongs to was still in the air.
                                selectingText = false
                                // Whatever the menu was tapped for runs once the bubble is home, so
                                // the two movements don't fight over the same frames.
                                pendingAction?.invoke()
                                pendingAction = null
                            },
                        )
                    }

                    // The double-tap reaction bar, which picks nothing out: it hangs off the
                    // bubble's top edge where it sits and leaves the conversation alone.
                    val reactionBarOpen = menuTarget == null && reactionTarget != null
                    // The two bands go in *before* the bar, so the bar and the palette over it are
                    // the only sharp things left — see [EditScrim] for why a hole in one sheet is
                    // not an option when the thing to leave out is a row inside the list.
                    val reactedRow = lastReactionAnchor?.let { threadState.rowRect(it.message.id) }
                    EditScrim(
                        // Down for the bar itself, not only for the palette over it. Picking a
                        // reaction is picking *this* message, and a bar floating over a conversation
                        // that carried on as normal behind it said nothing about which one — the
                        // scrim is what names the message, by being the one thing left sharp.
                        editing = reactionBarOpen || emojiPaletteOpen,
                        rowTopInRoot = reactedRow?.top ?: 0f,
                        rowHeight = reactedRow?.height ?: 0f,
                        backdrop = backdrop,
                    )
                    lastReactionAnchor?.let { anchor ->
                        val target = anchor.message
                        val headerBottomPx = with(density) { padding.calculateTopPadding().toPx() }
                        val barPx = with(density) { (ReactionBarHeight + AnchorGap).toPx() }
                        // Clamped under the header: reacting to the topmost visible bubble would
                        // otherwise open the bar behind the app bar.
                        val top = maxOf(headerBottomPx + with(density) { AnchorGap.toPx() }, anchor.bubbleTop - barPx)
                        // Only while the bar is actually up. It is invisible either way, and a
                        // full-screen tap target left behind after the bar has gone would swallow
                        // the next thing you touched.
                        if (reactionBarOpen) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { reactionTarget = null },
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset { IntOffset(0, top.roundToInt()) }
                                .padding(horizontal = 8.dp),
                            // Over the message's own half of the row rather than the middle of
                            // the screen, so the bar reads as belonging to that bubble.
                            contentAlignment = if (target.mine) Alignment.CenterEnd else Alignment.CenterStart,
                        ) {
                            AnimatedVisibility(
                                visible = reactionBarOpen,
                                // The bar brings its own entrance — the width unrolling while the
                                // emoji pop in one after another — and a second one layered over it
                                // would only fight it. All this has to supply is the way out.
                                enter = EnterTransition.None,
                                exit = fadeOut(tween(140)) + scaleOut(tween(170), targetScale = 0.8f),
                            ) {
                                ReactionBar(
                                    visible = true,
                                    active = target.reactions()?.mine(myHandles),
                                    onPaletteChange = { emojiPaletteOpen = it },
                                    onPick = { emoji ->
                                        imessageSender.toggleTapback(chatId, target.id, target.preview(), emoji)
                                        reactionTarget = null
                                    },
                                )
                            }
                        }
                    }

                    // The send bar rides on top of whichever of the keyboard and the navigation bar
                    // is taller — the `bottomSlot` the RN screen derives by hand. Without this the
                    // bar keeps its place at the bottom of the window and the keyboard opens straight
                    // over it: an edge-to-edge activity is never resized by `adjustResize`, so the
                    // inset has to be applied here.
                    // Out of the way while a message is picked out — it is behind the blur in
                    // spirit, and it draws over the overlay's scrim, so it fades instead.
                    val composerAlpha by animateFloatAsState(
                        targetValue = if (menuTarget != null && !menuClosing) 0f else 1f,
                        animationSpec = tween(180),
                        label = "composerFade",
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .graphicsLayer { alpha = composerAlpha }
                            .navigationBarsPadding()
                            // Not `imePadding`: the card below the bar already stands in the
                            // keyboard's slot, so only the part of the keyboard it is not under
                            // needs padding out. See [keyboardSpaceBeyond].
                            .keyboardSpaceBeyond(panelSlot),
                    ) {
                      Column(
                        // The bar and everything stacked on it, measured *without* the card below.
                        //
                        // A measurement arrives a frame after the layout that produced it, while
                        // the card's height is known exactly and immediately. The conversation pads
                        // its scroll content by the sum of the two, so adding a number that lags to
                        // one that does not left that padding a whole keyboard out for exactly one
                        // frame every time the card opened or closed — which is the band of empty
                        // page that flashed across the thread. Measured apart, the lagging half
                        // only changes when the bar itself does, and the card's half is added where
                        // it is already known. See `composerStackNow`.
                        //
                        // The bar's own margin above whatever is under it goes here rather than on
                        // the composer, and outside the measurement rather than inside it. Measured
                        // in, it was one more thing about the stack that this frame knew and the
                        // reported height did not — and since the margin is given up at the instant
                        // the card takes the slot, the conversation was padded by a margin that had
                        // already gone, which is the last of the jerk.
                        modifier = Modifier
                            .bottomSpace { composerGapNow().roundToPx() }
                            .onSizeChanged { size ->
                                bottomBarHeight = with(density) { size.height.toDp() }
                            },
                      ) {
                        // Above the composer, so the list of people grows out of the draft it is
                        // completing rather than covering it.
                        // The half-typed `@name` at the end of the draft, which opens the picker.
                        // Groups only. Worked out here, beside the one thing that draws it, so a
                        // keystroke recomposes the send bar's column and not the whole screen.
                        val mentionQuery = remember(composerText, identity.group) {
                            if (identity.group) pendingMentionQuery(composerText) else null
                        }
                        if (stickerSearching) {
                            StickerSearchStrip(
                                query = stickerQuery,
                                onQueryChange = { stickerQuery = it },
                                onDone = { focusManager.clearFocus(); keyboardController?.hide() },
                                drag = stickerDrag,
                                surface = composerSurfaceFor(settings),
                            )
                        }
                        mentionQuery?.let { query ->
                            MentionSuggestions(
                                query = query,
                                members = members,
                                onPick = { member -> composerText = applyMention(composerText, member) },
                            )
                        }
                        // In the corner above the bar, on the send button's side — the hand that
                        // reaches for it is already there. It grows out of the bar rather than
                        // appearing over it, which is the difference between a control the draft
                        // has put up and a dialog that has landed on top of one.
                        AnimatedVisibility(
                            visible = quickFormatOpen,
                            enter = fadeIn(tween(QUICK_FORMAT_MS)) +
                                scaleIn(tween(QUICK_FORMAT_MS), initialScale = 0.85f, transformOrigin = TransformOrigin(1f, 1f)) +
                                slideInVertically(tween(QUICK_FORMAT_MS)) { it / 2 },
                            exit = fadeOut(tween(140)) +
                                scaleOut(tween(160), targetScale = 0.85f, transformOrigin = TransformOrigin(1f, 1f)) +
                                slideOutVertically(tween(160)) { it / 2 },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            QuickFormatBar(
                                active = activeFormat(),
                                onToggleFlag = { flag -> applyFormat { it.toggle(flag) } },
                                onClose = { quickFormatOpen = false },
                                surface = composerSurfaceFor(settings),
                                modifier = Modifier.padding(end = 8.dp, bottom = 6.dp),
                            )
                        }
                        CompositionLocalProvider(
                            // Scoped to the send bar: this is the one field in the app whose text
                            // can carry formatting, so it is the one selection that should be
                            // offered any. See [rememberFormattingTextToolbar].
                            LocalTextToolbar provides rememberFormattingTextToolbar(
                                FormatActions(
                                    toggle = { flag -> applyFormat { it.toggle(flag) } },
                                    // The eight animations do not fit in a floating menu, so the
                                    // menu opens the card that holds them.
                                    openEffects = {
                                        richTextOpen = true
                                        openAttachmentPanel()
                                    },
                                ),
                            ),
                        ) {
                        ChatComposer(
                            // An edit up in the thread owns the keyboard while it lasts.
                            enabled = editingId == null,
                            text = composerText,
                            onTextChange = { updated ->
                                // The marks are carried across the edit before the text is, so the
                                // two are never briefly describing different strings.
                                richDraft = richDraft.afterEdit(updated, pendingFormat)
                                composerText = updated
                            },
                            richDraft = richDraft,
                            onSelectionChange = { draftSelection = it },
                            // The card is in the keyboard's slot and every press on it rewrites the
                            // field's styling. Left editable, each of those brought the keyboard
                            // back up over the card — see [ChatComposer]'s own note.
                            readOnly = attachmentPanelVisible && richTextOpen,
                            onSend = ::sendMessage,
                            onScheduleSend = { scheduling = composerText },
                            onAttachmentClick = {
                                if (attachmentPanelVisible) {
                                    // Only the card goes; which face it was showing is left alone
                                    // so it doesn't flip back to the grid on its way down.
                                    closeAttachmentPanel()
                                } else {
                                    // Reopened from the "+" it always shows the attachments: the
                                    // other faces are somewhere you go, not somewhere you are left.
                                    richTextOpen = false
                                    gamesOpen = false
                                    stickersOpen = false
                                    if (effectsOpen) closeEffects(keep = false)
                                    openAttachmentPanel()
                                }
                            },
                            onMicClick = {
                                // Asking is the screen's job, so the recorder itself never puts up
                                // a dialog: it starts only once the microphone has been granted.
                                if (micPermission.granted) {
                                    voice.start()
                                } else {
                                    micWanted = true
                                    micPermission.request()
                                }
                            },
                            // One press, one message.
                            //
                            // The bar used to stay live for the whole length of the send — and the
                            // clip stayed sitting in it, because the recorder was reset only once
                            // the send came back. A voice message takes a second or two to convert
                            // and upload, so a bar that still showed an unsent clip and still
                            // answered the button sent one copy per impatient tap.
                            //
                            // Everything the screen owns is now done up front: the bar goes back to
                            // idle, the conversation gets its new bubble (the send records the row
                            // before it leaves — see `IMessageSender.sendAudio`), and the trip to
                            // Apple happens behind all of it.
                            onSendVoice = {
                                val clip = if (sendingVoice) null else voice.build()
                                if (clip != null) {
                                    sendingVoice = true
                                    val quoted = replyTo
                                    replyTo = null
                                    voice.reset()
                                    if (settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                    coroutineScope.launch {
                                        try {
                                            imessageSender.sendAudio(
                                                chatId, identity, clip.uri, clip.duration, clip.waveform, quoted,
                                            )
                                        } finally {
                                            sendingVoice = false
                                        }
                                    }
                                }
                            },
                            voice = voice,
                            sendBarType = settings.bubbleType.sendBar(),
                            // In focus mode the quoted message is already on screen, lifted out of
                            // the thread, so the send bar doesn't repeat it as a card.
                            replyPreview = if (focused || settings.replyMode != ReplyMode.QUOTE) null else replyTo,
                            onCancelReply = { replyTo = null },
                            attachmentsOpen = attachmentPanelVisible,
                            draftMedia = draftMedia,
                            draftFiles = draftFiles,
                            onRemoveDraftFile = { file -> draftFiles = draftFiles - file },
                            onPasteImages = { picked ->
                                // Copied into our own storage before it is put on screen: the grant
                                // on a clipboard uri outlives neither the clipboard entry nor the
                                // app switch, and a draft can sit for minutes. See `stagePasted`.
                                coroutineScope.launch {
                                    val kept = picked.mapNotNull { AttachmentStaging.stagePasted(context, it) }
                                    if (kept.isNotEmpty()) draftMedia = draftMedia + kept
                                }
                            },
                            draftGame = pendingGame?.let { game ->
                                com.ruskcoder.messages.ui.components.DraftGame(
                                    // The extension's own two lines: ldText is the game's name
                                    // ("8 Ball"), caption the sentence the bubble leads with
                                    // ("Let's play 8 Ball!"). Same order they arrive in.
                                    name = game.ldText?.takeIf { it.isNotBlank() } ?: "Game",
                                    subtitle = game.caption?.takeIf { it.isNotBlank() }
                                        ?: game.subcaption?.takeIf { it.isNotBlank() },
                                    artwork = com.ruskcoder.messages.games.MadridBridge.artwork(game),
                                )
                            },
                            onRemoveDraftGame = { pendingGame = null },
                            pollDraft = pollDraft,
                            onPollChange = { choices -> pollDraft = choices },
                            // Taking the poll back out of the draft also abandons an edit, which
                            // is what puts the thread's own copy back — see [PollPlaceholder].
                            onRemovePoll = {
                                pollDraft = null
                                pollEditing = null
                                pollEditingOptions = emptyList()
                            },
                            onRemoveDraftMedia = { uri ->
                                draftMedia = draftMedia - uri
                                gallerySelection = gallerySelection.filterValues { it != uri }
                            },
                            // The same lightbox a sent picture opens: a thumbnail in the draft is
                            // 64dp, which is enough to tell one photograph from another and not
                            // enough to check you picked the right one.
                            onOpenDraftMedia = { uri ->
                                viewingMedia = mediaAttachmentFor(context, uri).asViewableMedia()
                                editingDraft = uri
                            },
                            focusRequester = composerFocusRequester,
                            // A flick down the pill puts the keyboard away, and whatever else is
                            // standing in its slot goes with it: the gesture asks for the
                            // conversation back, and a card left up answers half of that. Which
                            // face the card was showing is left alone, as it is when the "+" closes
                            // it — it is being put away, not reset.
                            onSwipeDismiss = { attachmentPanelVisible = false },
                        )
                        }
                      }
                        // Below the bar, in the slot the keyboard just gave up — and exactly as tall
                        // as the keyboard was, so the composer doesn't move when one replaces the
                        // other.
                        if (panelSlotOccupied) {
                          Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clipToBounds()
                                .layout { measurable, constraints ->
                                    // Measured at its full height and shown as much of as the slot
                                    // is currently holding. The card is a fixed thing being
                                    // uncovered, not a thing being stretched — which is what a
                                    // keyboard sliding off it has to look like, and what makes the
                                    // snap-open case (where the slot is taken all at once behind
                                    // the keys) cost nothing at all.
                                    // Measured with the ceiling taken off: in the full-height gallery
                                    // the card is taller than the slot's own constraints allow, and
                                    // measured against them it came back short — the slot showed the
                                    // height it had been given and the card ran out part-way down it.
                                    // What the column has left for the card, kept for
                                    // [galleryMaxExpansion] so the card is never grown past it.
                                    if (constraints.hasBoundedHeight &&
                                        constraints.maxHeight != gallerySlotRoomPx
                                    ) {
                                        gallerySlotRoomPx = constraints.maxHeight
                                    }
                                    val placeable = measurable.measure(
                                        constraints.copy(maxHeight = androidx.compose.ui.unit.Constraints.Infinity),
                                    )
                                    // Plus whatever the gallery has pulled the card up by, which is
                                    // driven by a finger rather than animated — see [galleryExpansion].
                                    // Never more than the column can show, whatever the card
                                    // was measured at, and never less than nothing: pulled below
                                    // its rest the card keeps its size and the slot shows less of
                                    // it, which reads as the card sliding off the bottom.
                                    val ceiling = minOf(
                                        placeable.height,
                                        if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height,
                                    )
                                    val shown = (panelSlot.value + galleryExpansion)
                                        .roundToPx().coerceIn(0, ceiling)
                                    layout(placeable.width, shown) { placeable.place(0, 0) }
                                },
                          ) {
                            // One card, several faces. The formatting controls are opened from the
                            // attachment sheet and take the same slot at the same size, so they read
                            // as that card turned over rather than a second one arriving over it.
                            if (panelFace == PanelFace.EFFECTS) {
                                // Nothing but its own height. The card you can see is the one in the
                                // overlay, above the blur; this holds the layout still underneath so
                                // the send bar — and the send button on it — does not move as the
                                // mode opens and closes.
                                Spacer(modifier = Modifier.fillMaxWidth().height(panelHeight))
                            } else if (panelFace == PanelFace.RICH_TEXT) {
                                RichTextPanel(
                                    active = activeFormat(),
                                    onToggleFlag = { flag -> applyFormat { it.toggle(flag) } },
                                    onPickEffect = { kind -> applyFormat { it.withEffect(kind) } },
                                    // The card gives up the slot it was holding and the keyboard
                                    // takes it back, with the four styles left above the bar: the
                                    // whole point of the small bar is that it is there *while* you
                                    // type, so the card closing has to put you back in the draft.
                                    onPopOut = {
                                        quickFormatOpen = true
                                        richTextOpen = false
                                        // The same handover the "+" makes, in the same direction as
                                        // tapping the draft: the card holds its slot while the
                                        // keyboard climbs over it rather than sliding out from
                                        // under a keyboard that is on its way in.
                                        panelYieldingToKeyboard = true
                                        attachmentPanelVisible = false
                                        composerFocusRequester.requestFocus()
                                        keyboardController?.show()
                                    },
                                    surface = composerSurfaceFor(settings),
                                    height = panelHeight,
                                )
                            } else if (panelFace == PanelFace.GAMES) {
                                // OpenPigeon draws the picker. Opening it hands the extension a
                                // way to send, and it calls back with a finished opening message
                                // once a game is chosen — so no game list lives in this app.
                                var pickerViews by remember { mutableStateOf<android.widget.RemoteViews?>(null) }
                                var openPigeonInstalled by remember {
                                    mutableStateOf(openPigeonClient.isInstalled())
                                }
                                // What the extension composes goes into the draft — see
                                // `pendingGame` up at screen scope — and the send bar takes it from
                                // there. The picker stays open around it, which is what lets a
                                // configurable game's options screen answer a question it has
                                // already composed a default answer to.
                                DisposableEffect(openPigeonInstalled, chatId) {
                                    openPigeonClient.openPicker(
                                        userCount = members.size.coerceAtLeast(2),
                                        onView = { pickerViews = it },
                                        onCompose = { composed -> pendingGame = composed },
                                    )
                                    onDispose { openPigeonClient.closePicker() }
                                }
                                OpenPigeonGamesPanel(
                                    views = pickerViews,
                                    installed = openPigeonInstalled,
                                    onGet = {
                                        runCatching {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(
                                                        "https://github.com/OpenBubbles/OpenPigeon",
                                                    ),
                                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                    },
                                    onRefresh = { openPigeonInstalled = openPigeonClient.isInstalled() },
                                    surface = composerSurfaceFor(settings),
                                    height = panelHeight,
                                )
                            } else if (panelFace == PanelFace.STICKERS) {
                                StickerPanel(
                                    query = stickerQuery,
                                    // The keyboard is about to take the card's slot; the search goes
                                    // up above the send bar with it, and comes back here after.
                                    onSearch = { stickerSearching = true },
                                    onClearQuery = { stickerQuery = "" },
                                    drag = stickerDrag,
                                    surface = composerSurfaceFor(settings),
                                    height = panelHeight,
                                    // The gallery's own growth: scrolled up, the card rises first.
                                    expansion = galleryExpansion,
                                    maxExpansion = galleryMaxExpansion,
                                    onExpand = { delta ->
                                        val next = (galleryExpansion + delta).coerceIn(0.dp, galleryMaxExpansion)
                                        val applied = next - galleryExpansion
                                        galleryExpansion = next
                                        applied
                                    },
                                )
                            } else if (panelFace == PanelFace.GAMES) {
                                GamesPanel(
                                    onPick = { game ->
                                        gamesOpen = false
                                        attachmentPanelVisible = false
                                        coroutineScope.launch {
                                            imessageSender.sendGameInvite(
                                                context = context,
                                                chatId = chatId,
                                                identity = identity,
                                                gameId = game.id,
                                                gameName = game.name,
                                                version = game.version,
                                            )
                                        }
                                    },
                                    surface = composerSurfaceFor(settings),
                                    height = panelHeight,
                                )
                            } else {
                                AttachmentPanel(
                                    onSelect = { source -> attachmentFlow.start(source) },
                                    // Matched to the bar above it under Material; the Apple bar is a
                                    // film rather than a colour, so that one keeps the neutral glass.
                                    surface = composerSurfaceFor(settings),
                                    onGames = { gamesOpen = true },
                                    onStickers = { stickersOpen = true },
                                    onScheduled = {
                                        attachmentPanelVisible = false
                                        managingScheduled = true
                                    },
                                    onRichText = { richTextOpen = true },
                                    onEffects = { effectsOpen = true },
                                    // The card goes away and the choices appear in the bar with the
                                    // keyboard back over them: a poll is something you *type*, and
                                    // leaving the panel up would have covered the thing it opened.
                                    // Re-opening it on a poll already in the draft is a no-op rather
                                    // than a reset, or the "+" would be a way to lose your choices.
                                    onPoll = {
                                        if (pollDraft == null) {
                                            pollDraft = List(Polls.STARTING_OPTIONS) { "" }
                                        }
                                        closeAttachmentPanel()
                                    },
                                    onReorder = { order ->
                                        settingsRepository.update { it.copy(attachmentTiles = order) }
                                    },
                                    // Straight into the draft with the card left up, so several can be
                                    // picked in a row. The pickers' own path, through the flow, closes it.
                                    onPickPhoto = { uri ->
                                        val staged = gallerySelection[uri]
                                        if (staged != null) {
                                            draftMedia = draftMedia - staged
                                            gallerySelection = gallerySelection - uri
                                        } else {
                                            coroutineScope.launch {
                                                AttachmentStaging.stagePasted(context, uri)?.let {
                                                    draftMedia = draftMedia + it
                                                    gallerySelection = gallerySelection + (uri to it)
                                                }
                                            }
                                        }
                                    },
                                    onPhotoCount = { count ->
                                        settingsRepository.update { it.copy(photosWidgetCount = count) }
                                    },
                                    // The full-height gallery's own scroll, taken as card height
                                    // until there is none left to take. Answers with what it used.
                                    expansion = galleryExpansion,
                                    maxExpansion = galleryMaxExpansion,
                                    onExpand = { delta ->
                                        // Below zero only by the grab bar's doing — the gallery's
                                        // own scroll is limited to what it took, so scrolling down
                                        // through the pictures settles the card and stops there.
                                        val next = (galleryExpansion + delta)
                                            .coerceIn(-panelHeight, galleryMaxExpansion)
                                        val applied = next - galleryExpansion
                                        galleryExpansion = next
                                        applied
                                    },
                                    // The bar may be pulled the whole of the card's height past its
                                    // rest, which puts the card off the bottom of the screen.
                                    collapseRoom = panelHeight,
                                    // Let go of past a third of the way down, or thrown down: the
                                    // card is being dismissed, and it closes the way a sheet does.
                                    // Anything short of that springs back to where it was.
                                    onPullRelease = { velocity ->
                                        val dismissed = galleryExpansion < -panelHeight * PanelDismissFraction ||
                                            velocity > PanelDismissVelocity
                                        if (dismissed) {
                                            coroutineScope.launch {
                                                // The rest of the way off, at the speed it was
                                                // thrown, and only then closed — so the card
                                                // leaves once, under the finger, rather than
                                                // snapping back and sliding out a second time.
                                                androidx.compose.animation.core.animate(
                                                    initialValue = galleryExpansion.value,
                                                    targetValue = -panelHeight.value,
                                                    initialVelocity = velocity,
                                                ) { value, _ -> galleryExpansion = value.dp }
                                                closeAttachmentPanel()
                                            }
                                        } else if (galleryExpansion < 0.dp) {
                                            coroutineScope.launch {
                                                androidx.compose.animation.core.animate(
                                                    initialValue = galleryExpansion.value,
                                                    targetValue = 0f,
                                                    initialVelocity = velocity,
                                                ) { value, _ -> galleryExpansion = value.dp }
                                            }
                                        }
                                    },
                                    resetSignal = galleryResetKey,
                                    onScrolledChange = { galleryScrolled = it },
                                    selectedPhotos = gallerySelection.keys,
                                    onResizeWidget = { columns, rows ->
                                        settingsRepository.update {
                                            it.copy(photosWidgetColumns = columns, photosWidgetRows = rows)
                                        }
                                    },
                                    // Nothing to dress without a message to dress.
                                    effectsEnabled = composerText.isNotBlank(),
                                    busy = attachmentFlow.busy,
                                    height = panelHeight,
                                )
                            }
                          }
                        }
                    }
                }
            }

            // Rewriting a message is a modal act: the conversation behind it goes out of focus and
            // stops answering, so the only things on screen you can still touch are the field and
            // its two buttons. Last in the box, so it covers the send bar as well as the thread —
            // and drawn as two bands with the row's own box between them rather than one sheet with
            // a hole punched in it, since the row is inside the list and cannot be lifted above a
            // scrim that covers the list.
            val editRow = threadState.editingRow
            EditScrim(
                editing = editingId != null,
                rowTopInRoot = editRow?.top ?: 0f,
                rowHeight = editRow?.height ?: 0f,
                backdrop = backdrop,
            )
          }

            // The blur, and the only three things allowed above it.
            EffectsOverlay(
                visible = effectsOpen,
                backdrop = screenBackdrop,
                bottomInset = navBottomNow(),
                cardHeight = panelHeight,
                selected = armedEffect,
                onSelect = ::pickEffect,
                surface = composerSurfaceFor(settings),
                onClose = { closeEffects(keep = false) },
                onSend = ::sendMessage,
                preview = {
                    BubbleEffectLayer(state = previewEffect, mine = true) {
                        Box(
                            modifier = Modifier.onGloballyPositioned {
                                previewBox = Rect(it.positionInRoot(), it.size.toSize())
                            },
                        ) {
                            MessageBubble(
                                message = Message.Text(
                                    id = EFFECT_PREVIEW_ID,
                                    text = composerText,
                                    runs = currentDraft().runs(),
                                    mine = true,
                                    time = "",
                                ),
                                isFirstInRun = true,
                                isLastInRun = true,
                            )
                        }
                    }
                },
            )

            // Last of all, over the blur as well: a screen effect happens to the room, and the room
            // includes whatever the room is currently doing.
            ScreenEffectLayer(state = screenEffects)

            // A sticker in the hand, over everything — the card it came out of included.
            StickerDragLayer(state = stickerDrag, targets = stickerTargets)
        }
      }
    }

    if (showAddPeople) {
        ContactPickerSheet(
            contacts = pickableContacts,
            disabledIds = members.map { it.id }.toSet(),
            onDismiss = { showAddPeople = false },
            onConfirm = { picked ->
                showAddPeople = false
                val newMembers = picked.map { GroupMember(it.id, it.name, it.initials, it.color, it.about, it.photoUri) }
                imessageSender.setParticipants(chatId, members + newMembers, chat?.name)
            },
        )
    }
    // The contact tile picks from the address book in-app rather than handing off to a system
    // picker, since a contact card is the app's own shape rather than a file.
    if (attachmentFlow.contactPickerOpen) {
        LaunchedEffect(Unit) { if (pickableContacts.isEmpty()) pickableContacts = contactsRepository.loadContacts() }
        ContactPickerSheet(
            contacts = pickableContacts,
            disabledIds = emptySet(),
            onDismiss = { attachmentFlow.closeContactPicker() },
            onConfirm = { picked ->
                attachmentFlow.closeContactPicker()
                attachmentPanelVisible = false
                coroutineScope.launch {
                    picked.forEach { contact ->
                        val card = com.ruskcoder.messages.data.model.Attachment.ContactCard(
                            contactId = contact.id,
                            name = contact.name,
                            // The address book's own "about" line is the only detail the app keeps
                            // for a contact; a card with no number just doesn't show one.
                            phone = contact.about,
                            initials = contact.initials,
                            color = contact.color,
                        )
                        // No file behind a contact card, so over iMessage it goes as text — see
                        // the attachment flow above for why that beats storing it locally only.
                        imessageSender.sendAttachment(
                            context = context,
                            chatId = chatId,
                            identity = identity,
                            attachment = card,
                            uri = null,
                            replyTo = null,
                        )
                    }
                }
            },
        )
    }
    failureTarget?.let { failed ->
        SendFailureDialog(
            reason = failed.error().orEmpty(),
            canRetry = failed.canRetrySend(),
            onDismiss = { failureId = null },
            // The dialog is dismissed on the way out rather than waiting for the send: the retry is
            // a round trip, and leaving the dialog up for it would leave the user staring at a
            // failure that is no longer the current answer. The badge is the thing that reports how
            // it went, and it comes straight back if the second attempt fails too.
            onRetry = {
                failureId = null
                imessageSender.retrySend(context, chatId, failed.id)
            },
            onDelete = {
                failureId = null
                // For me only. There is no "for everyone" to do — the message never reached anyone,
                // so there is nothing at the other end to take back.
                chatRepository.deleteMessages(chatId, listOf(failed.id), forEveryone = false)
            },
        )
    }
    if (showDeleteDialog) {
        DeleteMessagesDialog(
            // "For everyone" is an unsend, so it is on offer only where every one of them can
            // still be recalled — see [IMessageSender.canUnsend].
            canDeleteForEveryone = selectedIds.all { id ->
                messages.find { it.id == id }?.let { imessageSender.canUnsend(it) } == true
            },
            onDismiss = { showDeleteDialog = false },
            onDeleteForMe = {
                chatRepository.deleteMessages(chatId, selectedIds.toList(), forEveryone = false)
                showDeleteDialog = false; selecting = false; selectedIds = emptySet()
            },
            onDeleteForEveryone = {
                // "Delete for everyone" is iMessage's unsend, which is addressed one message at a
                // time; the local tombstone is left behind either way.
                selectedIds.forEach { id -> imessageSender.unsend(chatId, id) }
                showDeleteDialog = false; selecting = false; selectedIds = emptySet()
            },
        )
    }
    if (showForwardSheet) {
        ForwardSheet(
            count = selectedIds.size,
            chats = chats,
            excludeId = chatId,
            onDismiss = { showForwardSheet = false },
            onForward = { target ->
                val toForward = messages.filter { it.id in selectedIds }
                coroutineScope.launch {
                    imessageSender.forwardMessages(
                        context,
                        ChatIdentity(target.id, target.name, target.color, target.initials, target.group, target.members, target.photoUri),
                        toForward,
                    )
                }
                showForwardSheet = false; selecting = false; selectedIds = emptySet()
            },
        )
    }
    reactorsTarget?.let { target ->
        // A one-to-one has no roster to name a reactor from, so the address book is read for it
        // here. Loaded on open rather than with the screen: almost nobody opens this sheet, and a
        // contacts query on entering every conversation is a cost paid for nothing.
        LaunchedEffect(Unit) { if (pickableContacts.isEmpty()) pickableContacts = contactsRepository.loadContacts() }
        ReactionsSheet(
            reactions = target.reactions().orEmpty(),
            members = members,
            myHandles = myHandles,
            peerName = if (identity.group) null else identity.name,
            contacts = pickableContacts,
            onDismiss = { reactorsTarget = null },
        )
    }
    infoTarget?.let { target ->
        MessageInfoSheet(
            members = members,
            receipts = target.receipts(),
            sentAt = target.createdAt,
            onDismiss = { infoTarget = null },
        )
    }
    scheduling?.let { draft ->
        ScheduleSendDialog(
            text = draft,
            pending = scheduled.size,
            onDismiss = { scheduling = null },
            onSchedule = { at ->
                scheduling = null
                composerText = ""
                ScheduledSendAlarm.schedule(context, chatId, draft, at)
                scheduled = ScheduledSends.forChat(context, chatId)
                Toast.makeText(context, "Message scheduled", Toast.LENGTH_SHORT).show()
            },
            // The draft stays in the composer: opening the list is looking at the other messages,
            // not abandoning this one.
            onManage = { scheduling = null; managingScheduled = true },
        )
    }

    if (managingScheduled) {
        ScheduledMessagesSheet(
            chatId = chatId,
            onDismiss = { managingScheduled = false },
            onChanged = { all -> scheduled = all.filter { it.chatId == chatId } },
        )
    }

    // Before the viewer, so a picture opened from the grid comes up over it and closes back onto it.
    mediaPage?.let { group ->
        // Read against the live conversation, so a like shows on its tile as soon as it lands and a
        // picture unsent while the page is open drops out of it.
        val entries = group.items.mapIndexedNotNull { index, item ->
            val media = item.asViewableMedia() ?: return@mapIndexedNotNull null
            val id = group.messageIds.getOrNull(index)
            val live = id?.let { wanted -> messages.find { it.id == wanted } }
            if (live is Message.Deleted) return@mapIndexedNotNull null
            val reactions = live?.reactions().orEmpty()
            MediaPageItem(media, id, reactions.map { it.emoji }, reactions.mine(myHandles))
        }
        MediaGroupPage(
            entries = entries,
            likeEmoji = settings.quickReactionEmoji(),
            onOpen = { picked ->
                editingDraft = null
                viewingGroup = group
                viewingMedia = picked
            },
            onLike = { entry ->
                val id = entry.messageId ?: return@MediaGroupPage
                val preview = messages.find { it.id == id }?.preview() ?: "Photo"
                imessageSender.toggleTapback(chatId, id, preview, settings.quickReactionEmoji())
            },
            onDismiss = { mediaPage = null },
        )
    }

    viewingMedia?.let { media ->
        // The stack the picture on screen belongs to, so a swipe has somewhere to go. Built off the
        // group rather than off the conversation: what is either side of this picture is what it
        // was sent with, not whatever else happens to be nearby in the thread.
        val stack = viewingGroup?.items.orEmpty().mapNotNull { it.asViewableMedia() }
        val atIndex = stack.indexOfFirst { it.uri == media.uri }
        MediaViewer(
            media = media,
            onDismiss = { viewingMedia = null; viewingGroup = null; editingDraft = null },
            onOpenGrid = viewingGroup?.let { group -> { mediaPage = group } },
            // Offered only where the picture was actually found in its group: a stack it is not in
            // would step away from it and never come back.
            onStep = if (atIndex >= 0 && stack.size > 1) {
                { step: Int -> stack.getOrNull(atIndex + step)?.let { viewingMedia = it } }
            } else null,
            canStep = { step -> atIndex >= 0 && stack.getOrNull(atIndex + step) != null },
            neighbours = if (atIndex >= 0) listOfNotNull(stack.getOrNull(atIndex - 1), stack.getOrNull(atIndex + 1)) else emptyList(),
            onShare = { uri -> shareMedia(context, uri) },
            // A draft from the send bar opens stepped back with its controls up, not full screen.
            startWithChrome = editingDraft != null,
            // The marked-up copy takes the original's place in the strip rather than joining it:
            // what came back is the same picture with a line on it, and two of them side by side
            // would be a draft you have to weed.
            onEdited = editingDraft?.let { original ->
                { edited: Uri -> draftMedia = draftMedia.map { if (it == original) edited else it } }
            },
        )
    }
}

/** How long the styles take to arrive above the bar, and to leave it. */
private const val QUICK_FORMAT_MS = 220

/** How long a message a search result led to stays lit up. */
private const val ArrivalHighlightMs = 2_200L

/** How many frames a jump waits for a freshly loaded message to reach the list's layout. */
private const val ScrollWaitFrames = 30

/** Messages loaded beyond an unread run, so the rule above it has some conversation over it. */
private const val UnreadWindowSlack = 50

/**
 * How long the composer stays quiet before the typing indicator is withdrawn.
 *
 * Matches what Apple's client does closely enough that the indicator neither flickers between
 * words nor lingers after someone has walked away mid-sentence.
 */
private const val TYPING_IDLE_MS = 10_000L

/**
 * How far out of focus the conversation goes behind a lifted message.
 *
 * The radius the overlay's own scrim used to replay the recording at, moved onto the thread with
 * it so the two look the same.
 */
private val FocusBlurRadius = 20.dp

/** How long the conversation takes to go out of focus behind the effect picker, and to come back. */
private const val EFFECTS_OPEN_MS = 260
private const val EFFECTS_CLOSE_MS = 200

/**
 * The id the effect preview's stand-in message carries.
 *
 * It is never stored and never sent — it exists for the length of one composition so the bar can
 * draw the draft as the bubble it is about to become — but a [Message] needs an id, and one that
 * could collide with a real guid would be a bubble the conversation might try to act on.
 */
private const val EFFECT_PREVIEW_ID = "::effect-preview::"

