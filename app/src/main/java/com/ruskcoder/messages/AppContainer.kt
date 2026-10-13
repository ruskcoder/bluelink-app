package com.ruskcoder.messages

import android.content.Context
import android.telephony.TelephonyManager
import com.ruskcoder.messages.data.backup.BackupManager
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.data.db.AppDatabase
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.data.repository.ContactsRepository
import com.ruskcoder.messages.imessage.AttachmentDownloads
import com.ruskcoder.messages.imessage.Handles
import com.ruskcoder.messages.imessage.AttachmentTransfers
import com.ruskcoder.messages.imessage.IMessageSender
import com.ruskcoder.messages.imessage.MessageNotifications
import com.ruskcoder.messages.imessage.PushWakeLock
import com.ruskcoder.messages.imessage.IMessageService
import com.ruskcoder.messages.imessage.IMessageSyncer
import com.ruskcoder.messages.transcribe.SpeechModelStore
import com.ruskcoder.messages.transcribe.VoiceTranscriber
import com.ruskcoder.messages.transcribe.VoiceTranscriptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.ruskcoder.messages.data.model.quickReactionEmoji

/** How long the chat list has to settle before the widget is redrawn. */
private const val WIDGET_DEBOUNCE_MS = 2_000L

/**
 * Hand-rolled dependency container — the app is single-module and small enough that Hilt's
 * KSP/codegen overhead wouldn't pay for itself. Lives for the process lifetime, held by
 * [MessagesApp].
 */
class AppContainer(context: Context) {
    /** Lives as long as the process; DB writes and receipt timers outlive any single screen. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The conversation the navigation graph is currently showing, or null when none is.
     *
     * Which conversation is *open*, not whether anyone is looking at it — a chat left open when the
     * user presses Home is still the current destination. [visibleChatId] is what decides whether a
     * notification is suppressed; nothing else should read this one.
     */
    val activeChatId = MutableStateFlow<String?>(null)

    /** Whether any of the app's UI is on screen. Driven by `MainActivity`'s start/stop. */
    val appVisible = MutableStateFlow(false)

    /**
     * The conversation the user can actually see right now.
     *
     * Notifications are suppressed for it: being buzzed about a message that just appeared in front
     * of you is noise, and the notification would linger unread afterwards. That reasoning only
     * holds while the app is on screen, which is why this is gated on [appVisible] — backgrounding
     * the app from inside a conversation does not dispose its destination, so [activeChatId] keeps
     * naming it, and every message in the one thread the user talks in most went unannounced for as
     * long as the app stayed in the background.
     */
    val visibleChatId: StateFlow<String?> =
        combine(activeChatId, appVisible) { id, visible -> id.takeIf { visible } }
            .stateIn(applicationScope, SharingStarted.Eagerly, null)

    /**
     * A conversation the user asked to open from outside the app — a notification tap or a
     * conversation shortcut. Held here rather than passed down because the intent arrives on the
     * activity while the navigation graph is what can act on it. Cleared once navigated.
     */
    val pendingChatId = MutableStateFlow<String?>(null)

    /**
     * A conversation whose send bar should take the keyboard as soon as it is on screen.
     *
     * Separate from [pendingChatId] rather than a flag on it, because the two are consumed by
     * different things at different times and one of them can be answered without the other. The
     * navigation graph reads the id and opens the thread; the thread itself reads this — and it has
     * to work whether or not opening it created anything.
     *
     * That last part is the whole reason this is not simply a navigation argument. Leaving a
     * conversation open, backgrounding the app and tapping its next notification navigates to a
     * destination that is *already* the current one, so nothing is rebuilt and no argument is
     * re-read; a screen already on screen would never learn it had been asked for. A signal it
     * observes reaches it in either case.
     *
     * Cleared by whoever answers it, so a request cannot be honoured twice.
     */
    val composeInChat = MutableStateFlow<String?>(null)

    init {
        // Canonicalizing a bare phone number into E.164 needs a dialling region, and the SIM is the
        // best guess available. Set before anything derives a conversation id from a handle.
        val sim = runCatching {
            context.getSystemService(TelephonyManager::class.java)?.simCountryIso
        }.getOrNull()
        Handles.configureRegion(sim)
    }

    private val database = AppDatabase.get(context)

    val settingsRepository = SettingsRepository(context)
    val chatRepository = ChatRepository(
        chatDao = database.chatDao(),
        messageDao = database.messageDao(),
        settingsRepository = settingsRepository,
        scope = applicationScope,
    )
    val contactsRepository = ContactsRepository(context)

    /** The app lock — see [com.ruskcoder.messages.security.AppLock]. */
    val appLock = com.ruskcoder.messages.security.AppLock { settingsRepository.settings.value }

    /**
     * Keeps the recent-chats widget in step with the list, when anyone has placed one.
     *
     * Debounced: a burst of arriving messages rewrites the chat list many times a second, and a
     * widget redrawn for each would be launcher work for a picture nobody sees change that fast.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private val widgetUpdates = applicationScope.launch {
        kotlinx.coroutines.flow.merge(
            chatRepository.chats,
            settingsRepository.settings,
        )
            .debounce(WIDGET_DEBOUNCE_MS)
            .collect { com.ruskcoder.messages.widget.RecentChatsWidget.refresh(context.applicationContext) }
    }

    /**
     * On-device speech recognition for voice messages.
     *
     * Lazy because loading it costs ~60MB of native memory and several hundred milliseconds, and
     * most sessions never ask for a transcript at all — the model is opened the first time somebody
     * presses Transcribe, not when the app starts.
     */
    val speechModels: SpeechModelStore by lazy {
        SpeechModelStore(context.applicationContext, applicationScope)
    }

    val voiceTranscriptions: VoiceTranscriptions by lazy {
        VoiceTranscriptions(
            transcriber = VoiceTranscriber(
                models = speechModels,
                // Read per run rather than captured, so choosing a different model in settings
                // takes effect on the next press instead of the next launch.
                chosen = { settingsRepository.settings.value.speechModel },
            ),
            chats = chatRepository,
            // The app's scope, not a screen's: a transcription has to survive backing out of the
            // conversation it was started from.
            scope = applicationScope,
        )
    }

    /**
     * Attachments on their way out, written by the syncer and read by the sender and the UI.
     *
     * One instance for the app, because the two halves of a send are on opposite sides of the
     * event stream: the sender opens and closes a transfer, and the progress reports for it come
     * back in through the syncer.
     */
    val attachmentTransfers = AttachmentTransfers()

    /**
     * iMessage, built lazily.
     *
     * Constructing it loads the Rust library and initializes Apple state on disk, which is real
     * work and can fail; deferring it keeps a cold start off that path and means a build without
     * the native library still runs everything else.
     */
    val imessageService: IMessageService by lazy {
        IMessageService(context.applicationContext, applicationScope)
    }

    val imessageNotifications: MessageNotifications by lazy {
        MessageNotifications(context.applicationContext).also { notifications ->
            notifications.hideContent = { settingsRepository.settings.value.hideNotificationContent }
            notifications.quickReaction = { settingsRepository.settings.value.quickReactionEmoji() }
        }
    }

    val imessageDownloads: AttachmentDownloads by lazy {
        AttachmentDownloads(context.applicationContext, imessageService).also { downloads ->
            downloads.autoSaveMedia = { settingsRepository.settings.value.autoSaveMedia }
        }
    }

    /** Link cards: fetched for links we send, kept for the ones that arrive — see [LinkPreviews]. */
    val linkPreviews: com.ruskcoder.messages.imessage.LinkPreviews by lazy {
        com.ruskcoder.messages.imessage.LinkPreviews(context.applicationContext)
    }

    /** Keeps the CPU up while an inbound push is being filed — see [PushWakeLock]. */
    private val pushWakeLock: PushWakeLock by lazy { PushWakeLock(context.applicationContext) }

    private val syncerDelegate = lazy {
        IMessageSyncer(
            service = imessageService,
            chats = chatRepository,
            contacts = contactsRepository,
            notifications = imessageNotifications,
            downloads = imessageDownloads,
            scope = applicationScope,
            activeChatId = visibleChatId,
            transfers = attachmentTransfers,
            wakeLock = pushWakeLock,
            pigeonClient = pigeonClient,
            openPigeonClient = openPigeonClient,
            linkPreviews = linkPreviews,
        ).also { syncer -> syncer.onEvent = { connectionMonitor.noteEvent() } }
    }

    val imessageSyncer: IMessageSyncer by syncerDelegate

    /**
     * How the connection to Apple is doing — see [ConnectionMonitor].
     *
     * Safe to read anywhere: constructing it does not touch the iMessage service, only starting it
     * does, and only the push service starts it. What coming back online owes is wired here, the one
     * place that can see both halves: sends that were cut off go out, attachments that never
     * arrived are fetched.
     */
    val connectionMonitor: com.ruskcoder.messages.imessage.ConnectionMonitor by lazy {
        com.ruskcoder.messages.imessage.ConnectionMonitor(context.applicationContext, applicationScope) { imessageService }
            .also { monitor ->
                monitor.onConnected = {
                    imessageSender.resumeInterruptedSends(context.applicationContext)
                    imessageSyncer.resumePendingDownloads()
                }
            }
    }

    /**
     * Pick up contact edits made while the app was in the background.
     *
     * Contacts is a different app, so a rename there arrives with no event attached to it; the
     * moment the user comes back is the moment they expect to see it. Asked for on every resume and
     * debounced inside the syncer, rather than scheduled here.
     *
     * Deliberately a no-op until the syncer already exists: touching it constructs the iMessage
     * service, which loads the Rust library and initializes Apple state on disk. A resume during
     * setup — before there is a registration, or any conversation to re-name — must not be what
     * triggers that.
     */
    fun refreshAddressBook() {
        if (!syncerDelegate.isInitialized()) return
        applicationScope.launch { imessageSyncer.refreshAddressBook() }
    }

    val imessageSender: IMessageSender by lazy {
        IMessageSender(imessageService, chatRepository, applicationScope, attachmentTransfers, pigeonClient).also { sender ->
            sender.autoSaveMedia = { settingsRepository.settings.value.autoSaveMedia }
            sender.linkPreviews = linkPreviews
            sender.linkPreviewsEnabled = { settingsRepository.settings.value.linkPreviews }
        }
    }

    /**
     * Browsers linked through Settings → Web.
     *
     * Takes the sender as a lambda for the same reason [backupManager] takes the service: linking a
     * browser, or a browser merely listing chats, must not be what loads the Rust library.
     */
    val webBridge: com.ruskcoder.messages.web.WebBridge by lazy {
        com.ruskcoder.messages.web.WebBridge(
            context = context.applicationContext,
            scope = applicationScope,
            chats = chatRepository,
            settings = settingsRepository,
            contacts = contactsRepository,
            sender = { imessageSender },
            typing = { imessageSyncer.typing },
            transcriptions = { voiceTranscriptions },
        )
    }

    /** Talks to the pigeon app that runs the games. Bound lazily on the first game invite. */
    val pigeonClient: com.ruskcoder.messages.games.PigeonClient by lazy {
        com.ruskcoder.messages.games.PigeonClient(context.applicationContext)
    }

    /**
     * Talks to OpenPigeon over the Madrid extension protocol — the other games app.
     *
     * Both are held, not one or the other: which app runs the games is a setting the user can
     * change (see [com.ruskcoder.messages.data.model.GameApp]), and a thread can hold games opened
     * under either, so the app that is not currently selected still has to be reachable to open an
     * older bubble.
     */
    val openPigeonClient: com.ruskcoder.messages.games.OpenPigeonClient by lazy {
        com.ruskcoder.messages.games.OpenPigeonClient(context.applicationContext)
    }

    /**
     * Backup and restore.
     *
     * Takes the iMessage service as a lambda rather than a reference so that merely opening the
     * storage settings page does not load the Rust library — the credentials are read only once a
     * backup is actually being written or applied.
     */
    val backupManager: BackupManager by lazy {
        BackupManager(
            context = context.applicationContext,
            settings = settingsRepository,
            chats = chatRepository,
            imessage = { imessageService },
        )
    }
}
