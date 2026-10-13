package com.ruskcoder.messages.imessage

import android.util.Log
import com.ruskcoder.messages.data.db.MessageEntity
import com.ruskcoder.messages.data.db.toJson
import androidx.core.net.toUri
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.StickerPlacement
import com.ruskcoder.messages.data.model.genmojiLabel
import com.ruskcoder.messages.data.model.Polls
import com.ruskcoder.messages.data.model.decodePollUrl
import com.ruskcoder.messages.data.model.ChatIdentity
import com.ruskcoder.messages.data.model.Contact
import com.ruskcoder.messages.data.db.toJsonOrNull
import com.ruskcoder.messages.data.model.RunFormat
import com.ruskcoder.messages.data.model.TextEffectKind
import com.ruskcoder.messages.data.model.TextRun
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.StoredReaction
import com.ruskcoder.messages.data.model.display
import com.ruskcoder.messages.data.model.TapbackKind
import com.ruskcoder.messages.data.model.nameFromMembers
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.data.repository.ContactsRepository
import com.ruskcoder.messages.data.repository.IMessageChatFields
import com.ruskcoder.messages.util.appJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.collections.buildList
import java.io.File
import uniffi.imessage_core.ChatTarget
import uniffi.imessage_core.IncomingEvent
import uniffi.imessage_core.TapbackKind as CoreTapbackKind
import com.ruskcoder.messages.games.GamePigeon

/**
 * Writes everything Apple sends into the conversation store.
 *
 * This is the only place iMessage and the app's own data model meet. Every screen still reads from
 * Room and knows nothing about Apple; a message arriving over the push socket is just another write
 * that Room's flows re-emit, exactly like one composed locally.
 */
class IMessageSyncer(
    private val service: IMessageService,
    private val chats: ChatRepository,
    private val contacts: ContactsRepository,
    private val notifications: MessageNotifications,
    private val downloads: AttachmentDownloads,
    private val scope: CoroutineScope,
    /**
     * The conversation on screen, if any; it notifies itself by simply being visible.
     *
     * "On screen" means visible, not merely open — see `AppContainer.visibleChatId`.
     */
    private val activeChatId: StateFlow<String?> = MutableStateFlow(null),
    /** Where an upload's progress reports are filed — see [IMessageSender.transfers]. */
    private val transfers: AttachmentTransfers = AttachmentTransfers(),
    /**
     * Held while an inbound push is being filed, so the device cannot doze off part-way through
     * storing a message and posting its notification. Null in tests and on a build with no
     * platform behind it, where the reader is the only thing running anyway.
     */
    private val wakeLock: PushWakeLock? = null,
    /** Pushes an opponent's game turn into a session the pigeon app may already have open. */
    private val pigeonClient: com.ruskcoder.messages.games.PigeonClient? = null,
    /** The same, for OpenPigeon. Both are told: either app may have the game on screen. */
    private val openPigeonClient: com.ruskcoder.messages.games.OpenPigeonClient? = null,
    /** Keeps the link cards messages arrive with. Null in tests, where they are simply dropped. */
    private val linkPreviews: LinkPreviews? = null,
) {
    /** Who is currently typing, keyed by chat id — transient, so it never reaches the database. */
    /**
     * Who is writing in each conversation, in the order they started.
     *
     * A list rather than one handle: a group can have three people typing at once, and holding only
     * the newest meant each keystroke elsewhere silently replaced whoever was already there. Order
     * is kept because the indicator shows their faces, and faces that reshuffle every time somebody
     * presses a key read as people joining and leaving rather than as two people writing.
     */
    private val _typing = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val typing: StateFlow<Map<String, List<String>>> = _typing.asStateFlow()

    private var job: Job? = null

    /** Serialises [reconnect] against itself, so two triggers arriving together are one rebuild. */
    private val reconnectMutex = Mutex()

    /** Address book, loaded on start and re-read on demand; used to name handles. */
    private var addressBook: List<Contact> = emptyList()

    /** When it was last read, so a cold start followed straight away by a resume is one read. */
    private var addressBookReadAt = 0L

    /**
     * Every handle we are registered at, canonicalized — the set that decides who counts as "us"
     * when deriving a conversation's identity. Empty until the session can answer, which only
     * degrades identity to the sender-only behaviour rather than breaking it.
     */
    private var localKeys: Set<String> = emptySet()

    /** Per-chat counter letting a newer typing event supersede an older one's expiry timer. */
    // Keyed by conversation *and* writer: counted per chat alone, a second person starting to type
    // renewed the first one's timer, so one of them could sit there indefinitely after going quiet.
    private val typingGenerations = mutableMapOf<Pair<String, String>, Int>()

    /** The split-conversation repair runs once per process, the first time handles are known. */
    private var repaired = false

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            ensureLocalHandles()

            // Conversations stored before a contact was named, renamed, or given a photo pick that
            // up here rather than waiting for the next message to arrive in them.
            refreshAddressBook()

            resumePendingDownloads()

            // A handle registered mid-session — the phone number, switched on in Settings — is
            // ours from then on. Added to rather than replaced: after the number is switched off,
            // the conversations that came in on it are still ours to recognise.
            launch {
                service.state.collect { state ->
                    if (state is IMessageService.State.Connected && state.handles.isNotEmpty()) {
                        val added = state.handles.map { Handles.key(it) }.toSet() - localKeys
                        if (added.isNotEmpty() && localKeys.isNotEmpty()) {
                            localKeys = localKeys + added
                            Log.i(TAG, "local handles now: $localKeys")
                        }
                    }
                }
            }

            // A conversation started mid-session is somebody the subscription taken at connect
            // could not have named, and their Focus would go unheard until the next reconnect.
            // Keyed on the set of one-to-one ids so a preview or unread change is not a resubscribe.
            launch {
                chats.chats
                    .map { list -> list.filterNot { it.group }.map { it.id }.toSet() }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { subscribeFocusStatus() }
            }

            // The stream ends whenever the push socket goes — a network change, Apple dropping the
            // connection — and also finishes immediately if setup has not completed yet. Either way
            // it has to be picked up again, or the app goes quiet for the rest of the process's
            // life without anything looking wrong.
            // Filing an event is ordinary work — database writes, notifications, an attachment
            // fetch — and the Rust reader decrypts nothing until the collector returns, so doing it
            // in the reader means the socket is not read at all while a message is being stored.
            // An indicator that arrives behind a slow message is delivered that late, which is how
            // typing came to feel a second or two behind the other end. The queue keeps the reader
            // free to pull the next push; one consumer keeps the order events arrived in.
            val inbox = Channel<IncomingEvent>(Channel.UNLIMITED)
            val filing = launch {
                for (event in inbox) {
                    // One bad push must not take the consumer down with it. Under a wake lock,
                    // because everything after the socket read is ordinary work the device is
                    // otherwise free to sleep through — see [PushWakeLock].
                    val filed = wakeLock?.holding { runCatching { apply(event) } }
                        ?: runCatching { apply(event) }
                    filed.onFailure { Log.w(TAG, "failed to apply $event", it) }
                }
            }
            try {
                while (isActive) {
                    // Renewed per session rather than once per process: the interest is held on the
                    // push socket, so a reconnection that replaced that socket left us subscribed to
                    // nothing. Cheap, and silent on an account that cannot have presence at all.
                    subscribeFocusStatus()
                    try {
                        service.events.collect { event ->
                            onEvent()
                            // Typing is in-memory state and nothing else waits on it, so it skips
                            // the queue entirely: it is worth nothing if it is not immediate, and
                            // it must never be the event that holds up the ones behind it.
                            if (event is IncomingEvent.Typing) {
                                runCatching { receiveTyping(event) }
                                    .onFailure { Log.w(TAG, "failed to apply $event", it) }
                            } else {
                                inbox.send(event)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "push reader failed", e)
                    }

                    // The socket that would have carried "they stopped" is gone, so every indicator
                    // still up is a claim we can no longer stand behind. Dropped rather than left to
                    // time out: a conversation that went on saying somebody was writing for the
                    // length of a reconnection was the other half of the indicator feeling stuck.
                    clearAllTyping()

                    // The old session owns a socket that is already gone, so the next attempt has to
                    // build a new one rather than reuse it.
                    runCatching { service.invalidateSession() }
                    delay(RECONNECT_DELAY_MS)
                }
            } finally {
                inbox.close()
                filing.cancel()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Told about every push that arrives, so the connection's health can say when the last one did. */
    var onEvent: () -> Unit = {}

    /**
     * Where attachment fetches run: on the IO pool, and under a job of their own.
     *
     * A download is the one chance this device gets at what a message was carrying. The sender can
     * take the message back at any moment and Apple stops serving the bytes, so a fetch has to start
     * as the message lands and then be left alone to finish — which means it must not be queued
     * behind anything. [scope] is the application's, and that runs on the default dispatcher: a pool
     * sized for computation, where a dozen blocking MMCS transfers starve everything else on it.
     * Supervised so one failed transfer is one failed transfer.
     */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set while a pass over the undownloaded attachments is running, so two never overlap. */
    private val resumingDownloads = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Fetches every received attachment whose bytes never arrived.
     *
     * Run when the reader starts, again each time the connection comes back — a download that
     * failed for want of a network is exactly the one worth trying again then — and when somebody
     * asks from a bubble. The MMCS token stays on the row until a fetch succeeds, so trying again is
     * always safe; a pass already in progress simply answers for the second request.
     */
    fun resumePendingDownloads() {
        if (!resumingDownloads.compareAndSet(false, true)) return
        downloadScope.launch {
            try {
                kotlinx.coroutines.coroutineScope {
                    chats.pendingAttachmentDownloads().forEach { row ->
                        launch {
                            val fetched = downloads.resume(row) ?: return@launch
                            if (row.kind == "audio") storeVoiceClip(row.id, fetched)
                            else chats.setAttachmentPayload(row.id, fetched)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not resume attachment downloads", e)
            } finally {
                resumingDownloads.set(false)
            }
        }
    }

    /**
     * Throw away the push connection and build a fresh one.
     *
     * The reader cannot tell a quiet conversation from a dead socket. A connection that goes away
     * without being closed — a Wi-Fi handover, a carrier NAT dropping an idle mapping, a captive
     * portal — leaves `nextEvent()` blocked on a socket nothing will ever arrive on, and no amount
     * of waiting recovers it: [start] sees a job that is still very much alive and returns, so even
     * reopening the app leaves the app quietly receiving nothing until the process is killed. This
     * is the way out, and the caller is whoever noticed the network move underneath us.
     *
     * The session is closed *before* the job is cancelled: the reader is parked in a blocking
     * native call that no coroutine cancellation can interrupt, and closing the socket out from
     * under it is what makes that call return.
     */
    fun reconnect() {
        scope.launch {
            reconnectMutex.withLock {
                Log.i(TAG, "rebuilding push connection")
                runCatching { service.invalidateSession() }
                job?.cancelAndJoin()
                job = null
                start()
            }
        }
    }

    /**
     * Whether a message came from us rather than the other end.
     *
     * Decided against every handle we are registered at, not by comparing the sender to
     * `chat.sender`. Those two can never be equal for a message we sent ourselves: `chat.sender` is
     * the identity to *reply* from, and it is chosen to be a local handle that is not the message's
     * sender — so when we are the sender it deliberately picks a different one, or falls back to an
     * arbitrary handle. Comparing them therefore reported every message sent from another of our
     * own devices as though the other person had sent it.
     */
    private fun isMine(sender: String, chat: ChatTarget): Boolean {
        if (localKeys.isNotEmpty()) return Handles.key(sender) in localKeys
        // Before the session can list our handles there is nothing better to go on.
        return sender == chat.sender
    }

    /** Who a quoted message came from, as the reply bubble labels it. */
    private fun quotedName(quoted: MessageEntity): String = when {
        quoted.mine -> "You"
        else -> quoted.handle?.let { memberFor(it).name } ?: ""
    }

    /**
     * The one-line rendering of a quoted message.
     *
     * An attachment has no text of its own, so it is named instead — a quote reading nothing at all
     * is exactly the empty bubble this is here to avoid.
     */
    private fun quotedText(quoted: MessageEntity): String = when {
        quoted.body.isNotBlank() -> quoted.body
        quoted.kind == "attachment" -> "Attachment"
        quoted.kind == "audio" -> VOICE_MESSAGE
        else -> ""
    }

    /**
     * Files a received voice message's clip on the row that is already waiting for it.
     *
     * A CAF this device cannot read gives up the voice bubble and shows the file instead — see
     * [CafAudio] for which containers that covers. The original is kept either way: it is what
     * Apple sent, and it is what gets shared out of the app.
     */
    private suspend fun storeVoiceClip(messageId: String, fetched: Attachment) {
        val file = fetched as? Attachment.File ?: return
        val path = file.uri.takeIf { it.isNotBlank() }?.toUri()?.path
        if (path == null) {
            // Nothing was downloaded. The row keeps its MMCS token, so the next start tries the
            // fetch again rather than settling for a file bubble pointing at nothing.
            Log.w(TAG, "voice message $messageId has no bytes yet; leaving it to be resumed")
            return
        }
        val clip = CafAudio.playable(File(path))
        if (clip == null) {
            Log.w(TAG, "voice message $messageId cannot be played here; showing ${file.name} as a file")
            chats.demoteVoiceMessage(messageId, file)
            return
        }
        // A plain path, not a `file:` uri — the same thing the composer stores for a clip recorded
        // here, and what everything that later reads one back expects.
        chats.setVoiceClip(messageId, clip.file.absolutePath, clip.duration, clip.waveform)
    }

    /**
     * Make sure we know our own handles before deriving any conversation's identity.
     *
     * Retried until it succeeds rather than read once at startup: the session connects lazily, so
     * the first attempt usually runs before there is a client to ask. Getting an empty set and
     * never trying again is what put messages sent from another of our devices into a conversation
     * of their own — with no handles to exclude, our own address stays in the participant set and
     * derives a different id.
     */
    private suspend fun ensureLocalHandles() {
        if (localKeys.isNotEmpty()) return

        val handles = runCatching { service.session().handles() }
            .onFailure { Log.i(TAG, "handles not available yet, will retry", it) }
            .getOrDefault(emptyList())
        if (handles.isEmpty()) return

        localKeys = handles.map { Handles.key(it) }.toSet()
        Log.i(TAG, "local handles: $localKeys")

        // Only now can a conversation be asked what its id should be, so this is the first moment
        // threads that split under the old derivation can be recognised and folded together.
        if (!repaired) {
            repaired = true
            runCatching { chats.repairSplitChats(localKeys) }
                .onFailure { Log.w(TAG, "could not merge split conversations", it) }
        }
    }

    /**
     * Ask Apple to push us Focus changes for everyone we talk to one-to-one.
     *
     * Apple only sends a status to devices that have asked for that person's channel, and only for
     * people who have chosen to share their Focus with this account — so a handle we hold no key
     * for simply does not appear in the resulting subscription. That is why nothing here reports a
     * failure per person: not being told is the ordinary case, not an error.
     */
    private suspend fun subscribeFocusStatus() {
        val handles = runCatching { chats.focusStatusHandles() }
            .onFailure { Log.w(TAG, "could not list handles to watch", it) }
            .getOrDefault(emptyList())
            // Never ourselves. A stored participant list names every party to the conversation, us
            // included, and our own address is in every one of them — subscribing to it would turn
            // *our* Focus into "they have notifications silenced" in every chat at once.
            .filterNot { Handles.key(it) in localKeys }
        if (handles.isEmpty()) return
        runCatching { service.session().watchFocusStatus(handles) }
            .onSuccess { Log.d(TAG, "watching focus status for ${handles.size} handles") }
            .onFailure { Log.i(TAG, "focus status is not available on this account", it) }
    }

    private suspend fun apply(event: IncomingEvent) {
        // Cheap once it has succeeded, and every branch below derives a conversation id.
        ensureLocalHandles()
        when (event) {
            is IncomingEvent.Text -> receiveText(event)
            is IncomingEvent.Tapback -> receiveTapback(event)
            is IncomingEvent.Sticker -> receiveSticker(event)
            is IncomingEvent.Edit -> chats.receiveEdit(event.targetGuid, event.targetPart.toLong(), event.newText)
            is IncomingEvent.Unsend -> chats.receiveUnsend(event.targetGuid, event.targetPart.toLong())
            is IncomingEvent.Delivered ->
                chats.receiveReceipt(event.guid, event.sender, DeliveryStatus.DELIVERED, event.timestamp.toLong())
            is IncomingEvent.Read ->
                chats.receiveReceipt(event.guid, event.sender, DeliveryStatus.READ, event.timestamp.toLong())
            is IncomingEvent.Played ->
                chats.receiveReceipt(event.guid, event.sender, DeliveryStatus.PLAYED, event.timestamp.toLong())
            is IncomingEvent.SendConfirm -> receiveSendConfirm(event)
            // Straight to the tracker and no further: this is a number a bubble draws for a few
            // seconds, not a fact about the conversation, and nothing about it belongs in the store.
            is IncomingEvent.UploadProgress ->
                transfers.advance(event.transferId, event.sent.toLong(), event.total.toLong())
            is IncomingEvent.Typing -> receiveTyping(event)
            is IncomingEvent.GroupRenamed -> receiveRename(event)
            is IncomingEvent.GroupParticipantsChanged -> receiveParticipants(event)
            is IncomingEvent.Failed -> chats.receiveSendFailure(event.guid, event.message)
            is IncomingEvent.AppMessage -> receiveAppMessage(event)
            // Not a message and attached to none: it says only that somebody's phone will or will
            // not make a sound the next time we say something.
            is IncomingEvent.FocusStatus -> {
                Log.d(TAG, "focus: ${event.handle} silenced=${event.silenced} mode=${event.mode}")
                // Same guard as the subscription's, for the same reason and because a reflection
                // from another of our own devices can carry it even when we never asked.
                if (Handles.key(event.handle) !in localKeys) {
                    chats.setNotificationsSilenced(event.handle, event.silenced)
                }
            }
        }
    }

    private suspend fun receiveText(event: IncomingEvent.Text) {
        val chatId = Handles.chatId(event.chat, localKeys)
        val mine = isMine(event.sender, event.chat)

        // Nothing from a blocked conversation is stored — not even our own messages echoed back
        // from another device, since storing half of a thread is worse than storing none of it.
        // Dropped here rather than filtered at render time so unblocking does not hand back a
        // backlog of everything that was said in the meantime.
        if (chats.isBlocked(chatId)) {
            Log.d(TAG, "dropping ${event.guid}: chat=$chatId is blocked")
            return
        }

        // Which conversation a message was filed under, and which side of it, is the thing that
        // goes wrong invisibly — the message is stored, just not where anyone is looking.
        Log.d(
            TAG,
            "text ${event.guid} -> chat=$chatId mine=$mine sender=${event.sender} " +
                "chatSender=${event.chat.sender} participants=${event.chat.participants} " +
                "group=${event.chat.groupId} localKeys=$localKeys",
        )

        // A reply carries only the guid of what it answers, never its text, so the quote has to be
        // resolved from what we already stored — otherwise the reply bubble renders empty.
        val quoted = event.replyToGuid?.let { chats.quotedMessage(it, event.replyToPart) }
        if (event.replyToGuid != null && quoted == null) {
            Log.d(TAG, "reply ${event.guid} quotes ${event.replyToGuid} which is not stored here")
        }

        // Quoting one of our messages back at us is proof the sender read it, and it survives a
        // read receipt that was pushed while this device was not listening — see [noteSeen].
        if (!mine && quoted != null && quoted.mine) {
            chats.noteSeen(quoted.wireGuid, event.sender, event.timestamp.toLong())
        }

        // The message is what they were typing, so the indicator has served its purpose. Waiting
        // for Apple's cancel leaves it hanging under the message that just arrived. Only this
        // sender stops: in a group, somebody else may still be part-way through their own.
        clearTyping(chatId, event.sender)

        // A message carrying a file is an attachment message whether or not it also has text —
        // the text is the caption. Deciding on the text instead would silently drop the photo from
        // every captioned photo.
        val caption = event.text.takeIf { it.isNotBlank() }
        // How the sender dressed it. Empty for a plain message, which is what almost every one is
        // — and the runs are checked against the body they claim to describe before anything is
        // drawn from them, so a caption trimmed differently from the parts degrades to plain text
        // rather than to formatting on the wrong words. See `decodeTextRuns`.
        val runs = event.runs
            .map { spec ->
                TextRun(
                    text = spec.text,
                    format = spec.effect
                        ?.let { id -> TextEffectKind.entries.firstOrNull { it.appleId == id.toInt() } }
                        ?.let { RunFormat(effect = it) }
                        ?: RunFormat(
                            bold = spec.bold,
                            italic = spec.italic,
                            underline = spec.underline,
                            strikethrough = spec.strikethrough,
                        ),
                )
            }
            .toJsonOrNull()
        // The card the sender fetched for a link in the message. Its picture came inside the message,
        // so keeping it costs a file write and no network — this phone never contacts the site.
        val link = event.linkPreview
            ?.let { incoming -> runCatching { linkPreviews?.storeIncoming(event.guid, incoming) }.getOrNull() }
            ?.let { appJson.encodeToString(com.ruskcoder.messages.data.model.LinkPreview.serializer(), it) }
        val parts = buildList {
            event.attachments.forEachIndexed { index, attachment ->
                // A voice message is stored as one from the moment it lands, before its bytes are
                // here — the clip fills in later. The alternative is a file bubble that turns into
                // a voice message once the download finishes, which moves the conversation under
                // whoever is reading it. See [CafAudio].
                val voice = attachment.isVoiceMessage
                add(
                    MessageEntity(
                        id = if (isEmpty()) event.guid else "${event.guid}#${attachment.part}#$index",
                        chatId = chatId,
                        kind = if (voice) "audio" else "attachment",
                        // A voice note has no words to index or preview — mirrors what [toEntity]
                        // stores for one of our own. Everything else is named by what it is: a
                        // photo's filename is the sender's business, and a chat list reading
                        // `____.png` tells whoever glances at it less than "Photo" does. A document
                        // keeps its name, which is the one case where the name *is* the thing.
                        body = when {
                            voice -> ""
                            attachment.genmoji != null -> genmojiLabel(attachment.genmoji)
                            attachment.mediaKind == MediaKind.IMAGE -> "Photo"
                            attachment.mediaKind == MediaKind.VIDEO -> "Video"
                            else -> attachment.name
                        },
                        detail = null,
                        missed = null,
                        mine = mine,
                        senderId = event.sender.takeIf { !mine && Handles.isGroup(event.chat, localKeys) },
                        status = if (mine) DeliveryStatus.SENT.name else null,
                        replyToId = quoted?.id ?: event.replyToGuid,
                        replyToName = quoted?.let { quotedName(it) },
                        replyToText = quoted?.let { quotedText(it) },
                        audioUri = null,
                        audioDuration = null,
                        audioWaveform = null,
                        payload = appJson.encodeToString(
                            Attachment.serializer(),
                            downloads.describeOnly(attachment, caption = null),
                        ),
                        reactions = null,
                        edited = false,
                        createdAt = event.timestamp.toLong(),
                        handle = event.sender,
                        part = attachment.part.toLong(),
                        replyToPart = event.replyToPart,
                        service = SERVICE_IMESSAGE,
                        effect = event.effect,
                        subject = event.subject,
                        errorText = null,
                        wireGuid = event.guid,
                        attachmentToken = attachment.token,
                        verificationFailed = event.verificationFailed,
                    ),
                )
            }
            if (caption != null || event.attachments.isEmpty()) {
                add(
                    MessageEntity(
                        id = if (isEmpty()) event.guid else "${event.guid}#${event.textPart}#text",
                        chatId = chatId,
                        kind = "text",
                        body = caption.orEmpty(),
                        detail = null,
                        missed = null,
                        mine = mine,
                        senderId = event.sender.takeIf { !mine && Handles.isGroup(event.chat, localKeys) },
                        status = if (mine) DeliveryStatus.SENT.name else null,
                        replyToId = quoted?.id ?: event.replyToGuid,
                        replyToName = quoted?.let { quotedName(it) },
                        replyToText = quoted?.let { quotedText(it) },
                        audioUri = null,
                        audioDuration = null,
                        audioWaveform = null,
                        payload = null,
                        reactions = null,
                        edited = false,
                        createdAt = event.timestamp.toLong(),
                        handle = event.sender,
                        part = event.textPart.toLong(),
                        replyToPart = event.replyToPart,
                        service = SERVICE_IMESSAGE,
                        effect = event.effect,
                        subject = event.subject,
                        errorText = null,
                        wireGuid = event.guid,
                        textRuns = runs,
                        linkPreview = link,
                        verificationFailed = event.verificationFailed,
                    ),
                )
            }
        }

        val identity = identityFor(event.chat)
        val inserted = chats.receiveMessages(
            identity = identity,
            entities = parts,
            chatFields = fieldsFor(event.chat),
            unread = !mine,
        )
        if (!inserted) return

        // Our own messages arrive back over the socket when another of our devices sent them;
        // notifying for those would mean being told about something we just did. Nor is there
        // anything to announce about a message that just landed in the conversation on screen, or
        // about one in a conversation that has been muted, snoozed, or blocked — the message is
        // still stored either way, it simply arrives quietly.
        val announce = !mine && chatId != activeChatId.value && chats.announces(chatId)

        // Off the event loop, so a slow download cannot stall the messages behind it, but started
        // now and never cancelled: the bytes are pulled into internal storage the moment the message
        // lands, because the sender can take the message back at any point and Apple stops serving
        // what it was carrying. A copy that is already here survives that; one still being waited
        // for does not. Each Apple part has its own Room row, and therefore its own token and target
        // part. Once a picture is here it is folded into the notification that already went up.
        //
        // A fetch that came back with nothing leaves the row alone — see [AttachmentDownloads.fetch]
        // — so its token stays put and [resumePendingDownloads] can try again rather than the
        // conversation being left with a document bubble pointing at nothing.
        event.attachments.forEachIndexed { index, attachment ->
            val rowId = if (index == 0) event.guid else "${event.guid}#${attachment.part}#$index"
            downloadScope.launch {
                val fetched = downloads.fetch(rowId, attachment, caption = null)
                if (fetched == null) {
                    Log.w(TAG, "no bytes yet for ${attachment.name} on $rowId; leaving it to be resumed")
                    return@launch
                }
                if (attachment.isVoiceMessage) {
                    storeVoiceClip(rowId, fetched)
                } else {
                    chats.setAttachmentPayload(rowId, fetched)
                }
                if (announce && index == 0 && fetched is Attachment.Image) {
                    fetched.uri.toUri().path?.let { notifications.attachPicture(chatId, it) }
                }
            }
        }

        val first = event.attachments.firstOrNull()
        // What the notification says a wordless message is. Never the filename: an iPhone names its
        // pictures whatever it likes, and a line reading `____.png` says less than "Photo" does.
        val body = caption
            ?: first?.let { info ->
                when {
                    info.isVoiceMessage -> VOICE_MESSAGE
                    info.genmoji != null -> genmojiLabel(info.genmoji)
                    info.mediaKind == MediaKind.IMAGE -> "Photo"
                    info.mediaKind == MediaKind.VIDEO -> "Video"
                    else -> info.name
                }
            }
            ?: ""

        if (announce) {
            val sender = memberFor(event.sender)
            notifications.notify(
                chatId = chatId,
                chatName = identity.name,
                senderName = sender.name,
                text = body,
                messageId = event.guid,
                // The face beside the line, and the face on the conversation itself. A group has
                // no photo of its own, so the shade falls back to whoever last spoke.
                senderPhotoUri = sender.photoUri,
                chatPhotoUri = identity.photoUri,
            )
        }
    }

    /**
     * A sticker laid on a message, or taken back off it.
     *
     * Stored as a small image row answering the message it was put on, so it appears in the thread
     * against that message and survives a restart like anything else said there. It used to be
     * dropped outright — the sticker never appeared on this side at all.
     *
     * Its id starts with the message and the person, because that is all a removal names; see
     * [stickerRowId]. No notification: a sticker is a reaction, and reactions do not buzz twice.
     */
    private suspend fun receiveSticker(event: IncomingEvent.Sticker) {
        val chatId = chats.chatIdForMessage(event.targetGuid) ?: return
        if (chats.isBlocked(chatId)) return
        if (!event.enable) {
            chats.removeStickers(chatId, event.targetGuid, event.sender)
            return
        }
        val attachment = event.attachments.firstOrNull() ?: return
        val placement = event.placement?.let {
            StickerPlacement(it.x.toFloat(), it.y.toFloat(), it.scale.toFloat(), it.rotation.toFloat())
        }
        val mine = isMine(event.sender, event.chat)
        val quoted = chats.quotedMessage(event.targetGuid, null)
        val rowId = com.ruskcoder.messages.data.repository.stickerRowId(event.targetGuid, event.sender, event.guid)
        val entity = MessageEntity(
            id = rowId,
            chatId = chatId,
            kind = "attachment",
            body = "Sticker",
            detail = null,
            missed = null,
            mine = mine,
            senderId = event.sender.takeIf { !mine && Handles.isGroup(event.chat, localKeys) },
            status = if (mine) DeliveryStatus.SENT.name else null,
            replyToId = quoted?.id ?: event.targetGuid,
            replyToName = quoted?.let { quotedName(it) },
            replyToText = quoted?.let { quotedText(it) },
            audioUri = null,
            audioDuration = null,
            audioWaveform = null,
            // An image from the first frame, with no picture yet, so it never draws as a file bubble.
            payload = appJson.encodeToString(
                Attachment.serializer(),
                Attachment.Image(uri = "", width = 0, height = 0, sticker = true, placement = placement),
            ),
            reactions = null,
            edited = false,
            createdAt = event.timestamp.toLong(),
            handle = event.sender,
            part = 0,
            replyToPart = event.targetPart.toString(),
            service = SERVICE_IMESSAGE,
            effect = null,
            subject = null,
            errorText = null,
            wireGuid = event.guid,
            attachmentToken = attachment.token,
        )
        val inserted = chats.receiveMessages(
            identity = identityFor(event.chat),
            entities = listOf(entity),
            chatFields = fieldsFor(event.chat),
            unread = false,
        )
        if (!inserted) return
        downloadScope.launch {
            // Nothing back means nothing arrived; the row keeps its token so the sticker is fetched
            // again later rather than being fixed here as a picture that will never load.
            val fetched = downloads.fetch(rowId, attachment, caption = null) ?: return@launch
            // Kept an image whatever the download made of it: a sticker that could not be decoded is
            // still a sticker, and a file bubble named "sticker.heic" is not what anyone sent.
            val sticker = (fetched as? Attachment.Image)?.copy(sticker = true, placement = placement)
                ?: Attachment.Image(uri = "", width = 0, height = 0, sticker = true, placement = placement)
            chats.setAttachmentPayload(rowId, sticker)
        }
    }

    private suspend fun receiveTapback(event: IncomingEvent.Tapback) {
        val chatId = chats.chatIdForMessage(event.targetGuid)
            // A tapback on a message we never stored has nothing to attach to. That happens for
            // history predating setup, and dropping it is the only sensible outcome.
            ?: return

        chats.applyRemoteReaction(
            chatId = chatId,
            wireGuid = event.targetGuid,
            part = event.targetPart.toLong(),
            sender = event.sender,
            reaction = event.kind.toStored(event.targetPart.toLong()),
            enable = event.enable,
        )

        // Reacting to a message means having read it, which is worth recording in its own right:
        // the read receipt that said so may never have reached this device. Ours are skipped —
        // reacting to your own message says nothing about the other end.
        if (!isMine(event.sender, event.chat)) {
            chats.noteSeen(event.targetGuid, event.sender, event.timestamp.toLong())
        }

        // Someone reacting is worth a notification the same way a message is — but only a reaction
        // being added, from someone other than us, in a conversation that is neither on screen nor
        // silenced. A reaction being taken back is not something to be pinged about.
        if (event.enable && !isMine(event.sender, event.chat) &&
            chatId != activeChatId.value && chats.announces(chatId)
        ) {
            val identity = identityFor(event.chat)
            val sender = memberFor(event.sender)
            val emoji = event.kind.toStored(event.targetPart.toLong()).display()
            val preview = chats.reactionTargetPreview(event.targetGuid, event.targetPart.toLong()).orEmpty()
            notifications.notifyReaction(
                chatId = chatId,
                chatName = identity.name,
                senderName = sender.name,
                emoji = emoji,
                targetPreview = preview,
                senderPhotoUri = sender.photoUri,
                chatPhotoUri = identity.photoUri,
            )
        }
    }

    /**
     * A Polls balloon: somebody asking one, or somebody answering.
     *
     * The two arrive by the same route and are told apart by `updatesGuid` — an association names
     * the bubble it belongs to, an opening message names nothing. Both are stored as rows, because
     * an answer is a real thing that really happened and the tally is assembled from them; only the
     * opening one is a message the conversation draws. See the thread's fold, which takes the
     * updates back out of the list.
     */
    private suspend fun receivePoll(
        event: IncomingEvent.AppMessage,
        chatId: String,
        mine: Boolean,
        quoted: MessageEntity?,
    ) {
        val payload = decodePollUrl(event.url)
        val options = payload?.item?.orderedPollOptions.orEmpty()
        val poll = Attachment.Poll(
            sessionId = event.session.orEmpty(),
            url = event.url,
            bundleId = event.bundleId,
            ldText = event.ldText.takeIf { it.isNotBlank() } ?: Polls.ldText(options),
            // An opening message is its own root, so every update — and the poll itself — carries
            // the same guid and the thread can group them without a special case.
            rootGuid = event.updatesGuid ?: event.guid,
            updateOf = event.updatesGuid,
        )

        val identity = identityFor(event.chat)
        val entity = MessageEntity(
            id = event.guid,
            chatId = chatId,
            kind = "attachment",
            body = event.ldText,
            detail = null,
            missed = null,
            mine = mine,
            senderId = if (!mine && Handles.isGroup(event.chat, localKeys)) event.sender else null,
            status = if (mine) DeliveryStatus.SENT.name else null,
            replyToId = quoted?.id ?: event.replyToGuid,
            replyToName = quoted?.let { quotedName(it) },
            replyToText = quoted?.let { quotedText(it) },
            audioUri = null,
            audioDuration = null,
            audioWaveform = null,
            payload = appJson.encodeToString(Attachment.serializer(), poll),
            reactions = null,
            edited = false,
            createdAt = event.timestamp.toLong(),
            handle = event.sender,
            part = 0,
            replyToPart = event.replyToPart,
            service = SERVICE_IMESSAGE,
            effect = null,
            subject = null,
            errorText = null,
            wireGuid = event.guid,
            verificationFailed = event.verificationFailed,
        )

        chats.receiveMessages(
            identity = identity,
            entities = listOf(entity),
            chatFields = fieldsFor(event.chat),
            // A vote is not something to be told about. The poll it lands on is already in the
            // conversation, and marking the thread unread every time somebody taps a choice would
            // make a five-person poll ring five times for no news.
            unread = !mine && event.updatesGuid == null,
        )
    }

    private suspend fun receiveAppMessage(event: IncomingEvent.AppMessage) {
        val chatId = Handles.chatId(event.chat, localKeys)
        val mine = isMine(event.sender, event.chat)

        if (chats.isBlocked(chatId)) {
            Log.d(TAG, "dropping app message ${event.guid}: chat=$chatId is blocked")
            return
        }

        val quoted = event.replyToGuid?.let { chats.quotedMessage(it, event.replyToPart) }
        if (event.replyToGuid != null && quoted == null) {
            Log.d(TAG, "app message ${event.guid} quotes ${event.replyToGuid} which is not stored here")
        }

        if (!mine && quoted != null && quoted.mine) {
            chats.noteSeen(quoted.wireGuid, event.sender, event.timestamp.toLong())
        }

        clearTyping(chatId, event.sender)

        // A poll is a balloon too, and nothing else about it is a game — a different payload, a
        // different bubble, and updates that must not become messages. Split before any of the
        // GamePigeon decoding below runs on a document that is not GamePigeon's.
        if (event.bundleId.contains(POLLS_BUNDLE_MARKER, ignoreCase = true) ||
            decodePollUrl(event.url)?.item != null
        ) {
            receivePoll(event, chatId, mine, quoted)
            return
        }

        if (event.bundleId != GamePigeon.BUNDLE_ID) {
            Log.w(TAG, "received app message for unrecognised bundle ${event.bundleId}")
        }

        // Decode the payload once here so the display name and status line come from the game
        // state rather than from whatever the sender's client happened to put in ldText/caption.
        val state = GamePigeon.decode(event.url)
        val gameId = state["game"].orEmpty()
        val gameName = state["game_name"]?.takeIf { it.isNotBlank() }
            ?: event.ldText.takeIf { it.isNotBlank() }
            ?: gameId
        // No local uuid needed here: an incoming turn's `sender` is always the other player, so
        // this resolves to "Your move." (or the right win/lose line) without it.
        val caption = GamePigeon.caption(state, senderUuid = "")

        val invite = Attachment.GameInvite(
            gameId = gameId,
            gameName = gameName,
            sessionId = event.session.orEmpty(),
            url = event.url,
            bundleId = event.bundleId,
            ldText = gameName,
            caption = caption,
            subcaption = event.subcaption,
            previewPath = null,
            isLive = event.isLive,
        )

        // A turn on a game already here is its own bubble, exactly as it is on Apple's clients:
        // the new board arrives as a message from whoever played it and the turn before it
        // collapses to a line. The thread's collapse pass decides which one stays open; all this
        // has to do is carry the root guid forward so they group together.
        val root = event.updatesGuid?.let { chats.balloonRow(it) }
        val previous = root?.payload?.let { payload ->
            runCatching {
                appJson.decodeFromString(Attachment.serializer(), payload)
            }.getOrNull() as? Attachment.GameInvite
        }
        val turn = invite.copy(
            // An opening message is its own root, so every turn of a game — including the first —
            // carries the same guid and the thread can group them without a special case.
            rootGuid = event.updatesGuid ?: event.guid,
            // Keep whatever picture the game already had. An incoming balloon's own preview is
            // never downloaded (the opening turn does not fetch one either), so there is nothing
            // better to put here.
            previewPath = previous?.previewPath,
            sessionId = invite.sessionId.ifBlank { previous?.sessionId.orEmpty() },
        )

        val identity = identityFor(event.chat)
        val entity = MessageEntity(
            id = event.guid,
            chatId = chatId,
            kind = "attachment",
            body = event.ldText,
            detail = null,
            missed = null,
            mine = mine,
            senderId = if (!mine && Handles.isGroup(event.chat, localKeys)) event.sender else null,
            status = if (mine) DeliveryStatus.SENT.name else null,
            replyToId = quoted?.id ?: event.replyToGuid,
            replyToName = quoted?.let { quotedName(it) },
            replyToText = quoted?.let { quotedText(it) },
            audioUri = null,
            audioDuration = null,
            audioWaveform = null,
            payload = appJson.encodeToString(Attachment.serializer(), turn),
            reactions = null,
            edited = false,
            createdAt = event.timestamp.toLong(),
            handle = event.sender,
            part = 0,
            replyToPart = event.replyToPart,
            service = SERVICE_IMESSAGE,
            effect = null,
            subject = null,
            errorText = null,
            wireGuid = event.guid,
            verificationFailed = event.verificationFailed,
        )

        val inserted = chats.receiveMessages(
            identity = identity,
            entities = buildList { add(entity) },
            chatFields = fieldsFor(event.chat),
            unread = !mine,
        )

        if (!inserted) return

        // The board render the sender drew, which rides alongside the balloon as an ordinary
        // attachment. Fetched here rather than left alone: the bubble *is* this picture (see
        // AttachmentContent.isEdgeToEdge), so without it every game they send is a grey icon tile
        // while every game we send shows its board. Off the event loop, and the bubble simply
        // stays a tile if it never arrives.
        val artwork = event.attachments.firstOrNull()
        if (artwork != null && turn.previewPath.isNullOrBlank()) {
            downloadScope.launch {
                val fetched = runCatching {
                    downloads.fetch("${event.guid}-game-preview", artwork, caption = null)
                }.getOrNull()
                val path = (fetched as? Attachment.Image)?.uri?.toUri()?.path ?: return@launch
                chats.setAttachmentPayload(event.guid, turn.copy(previewPath = path))
            }
        }

        // If the game is open in pigeon right now, fold the opponent's turn straight into it so
        // the board updates live; otherwise the new bubble is there to be tapped.
        //
        // Addressed by the turn's own session rather than the balloon's: that is the id the game
        // was opened under, and a turn whose balloon has lost it still belongs to the session the
        // game before it named. Pushing under the wrong id — or not at all — leaves the board on
        // screen sitting on the move before this one.
        if (!mine && turn.sessionId.isNotBlank() && state.isNotEmpty()) {
            runCatching { pigeonClient?.pushOpponentUpdate(turn.sessionId, state) }
        }
        // And the same for OpenPigeon, which keys a running game by the balloon session and
        // redraws its own board from it. Told unconditionally rather than only when it is the
        // selected app: a game opened before the setting changed is still on somebody's screen.
        val openPigeon = openPigeonClient
        if (!mine && openPigeon?.isInstalled() == true) {
            runCatching {
                openPigeon.pushOpponentUpdate(
                    com.ruskcoder.messages.games.MadridBridge.toMadrid(turn, guid = event.guid),
                )
            }
        }

        val announce = !mine && chatId != activeChatId.value && chats.announces(chatId)
        if (announce) {
            val sender = memberFor(event.sender)
            notifications.notify(
                chatId = chatId,
                chatName = identity.name,
                senderName = sender.name,
                text = event.ldText,
                senderPhotoUri = sender.photoUri,
                chatPhotoUri = identity.photoUri,
            )
        }
    }

    private suspend fun receiveSendConfirm(event: IncomingEvent.SendConfirm) {
        val error = event.error
        if (error != null) {
            chats.receiveSendFailure(event.guid, error)
        } else {
            chats.receiveSendAccepted(event.guid)
        }
    }

    private fun receiveTyping(event: IncomingEvent.Typing) {
        // Off the filing queue, so nothing upstream has resolved our own addresses for us. Only
        // worth asking when they are still unknown — `localKeys` is what decides which conversation
        // a chat belongs to, and an indicator filed under the wrong id shows up in no conversation
        // at all.
        if (localKeys.isEmpty()) scope.launch { ensureLocalHandles() }
        val chatId = Handles.chatId(event.chat, localKeys)
        if (!event.active) {
            clearTyping(chatId, event.sender)
            return
        }
        // Appended rather than replacing, and only once: a run of keystrokes from someone already
        // in the list must not shuffle them to the end of it.
        val writing = _typing.value[chatId].orEmpty()
        if (event.sender !in writing) _typing.value = _typing.value + (chatId to (writing + event.sender))

        // Someone writing a reply has read what they are replying to, whether or not their client
        // is willing to say so. Only in a one-to-one, where "they" is one person and the tick can
        // mean what it says; in a group one member typing tells us nothing about the rest.
        if (!Handles.isGroup(event.chat, localKeys)) {
            scope.launch { chats.markReadFromTyping(chatId) }
        }

        // Apple sends a cancel when the other end stops, but a dropped socket or a client that
        // simply goes away leaves the indicator running forever. Expire it independently, counting
        // per chat so a keystroke in one conversation cannot cancel another's timer.
        val key = chatId to event.sender
        val generation = (typingGenerations[key] ?: 0) + 1
        typingGenerations[key] = generation
        scope.launch {
            delay(TYPING_EXPIRY_MS)
            if (typingGenerations[key] == generation) clearTyping(chatId, event.sender)
        }
    }

    /** Drops every indicator at once, for when the connection they were arriving over has gone. */
    private fun clearAllTyping() {
        typingGenerations.clear()
        _typing.value = emptyMap()
    }

    /** Takes one writer out of a conversation, and the conversation out once nobody is left in it. */
    private fun clearTyping(chatId: String, sender: String) {
        typingGenerations.remove(chatId to sender)
        val left = _typing.value[chatId].orEmpty() - sender
        _typing.value = if (left.isEmpty()) _typing.value - chatId else _typing.value + (chatId to left)
    }

    private suspend fun receiveRename(event: IncomingEvent.GroupRenamed) {
        val chatId = Handles.chatId(event.chat, localKeys)
        chats.setMembers(chatId, membersOf(event.chat), event.name)
        chats.recordSystemMessage(
            chatId = chatId,
            wireGuid = event.guid,
            text = "${actorName(event.sender)} named the conversation \"${event.name}\"",
            mine = isMine(event.sender, event.chat),
            at = event.timestamp.toLong(),
        )
    }

    private suspend fun receiveParticipants(event: IncomingEvent.GroupParticipantsChanged) {
        val chatId = Handles.chatId(event.chat, localKeys)
        val mine = localKeys + Handles.key(event.chat.sender)
        val members = event.participants
            .filterNot { Handles.key(it) in mine }
            .distinctBy { Handles.key(it) }
            .map { memberFor(it) }

        // Worked out against what we had *before* the write, so the notice can name who arrived or
        // left rather than just reporting that the roster is now different.
        val before = chats.membersOf(chatId)
        chats.setMembers(chatId, members, event.chat.groupName)
        chats.recordSystemMessage(
            chatId = chatId,
            wireGuid = event.guid,
            text = participantChangeText(before, members, event.sender),
            mine = isMine(event.sender, event.chat),
            at = event.timestamp.toLong(),
        )
    }

    /**
     * What changed about a group's roster, as a sentence.
     *
     * A membership event reports the whole participant list rather than a delta, so the difference
     * has to be taken here. Falls back to a bare statement when the two sets differ in both
     * directions at once — Apple can coalesce several changes into one event, and inventing an
     * order for them would be worse than not claiming one.
     */
    private fun participantChangeText(
        before: List<GroupMember>,
        after: List<GroupMember>,
        sender: String,
    ): String {
        val actor = actorName(sender)
        val senderKey = Handles.key(sender)
        val beforeKeys = before.associateBy { Handles.key(it.id) }
        val afterKeys = after.associateBy { Handles.key(it.id) }
        val added = afterKeys.filterKeys { it !in beforeKeys }.values.map { it.name }
        val removedKeys = beforeKeys.keys - afterKeys.keys
        val removed = removedKeys.mapNotNull { beforeKeys[it]?.name }

        return when {
            // Taking only yourself out is leaving, and every app words it that way.
            removedKeys == setOf(senderKey) && added.isEmpty() -> "$actor left"
            added.isNotEmpty() && removed.isEmpty() -> "$actor added ${joinNames(added)}"
            removed.isNotEmpty() && added.isEmpty() -> "$actor removed ${joinNames(removed)}"
            // Apple can coalesce several changes into one event. Claiming an order for them would
            // be worse than not claiming one.
            added.isNotEmpty() && removed.isNotEmpty() -> "$actor changed who is in the conversation"
            else -> "$actor updated the conversation"
        }
    }

    /** "A", "A and B", "A, B and C" — the way a notice reads rather than a comma-joined list. */
    private fun joinNames(names: List<String>): String = when (names.size) {
        0 -> "someone"
        1 -> names.first()
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /** Who did it, named from the address book, or "You" when it was us. */
    private fun actorName(sender: String): String =
        if (Handles.key(sender) in localKeys) "You" else memberFor(sender).name

    // -----------------------------------------------------------------------------------------
    // Identity
    // -----------------------------------------------------------------------------------------

    private fun identityFor(target: ChatTarget): ChatIdentity {
        val chatId = Handles.chatId(target, localKeys)
        val group = Handles.isGroup(target, localKeys)
        val members = membersOf(target)
        val name = target.groupName?.takeIf { it.isNotBlank() }
            ?: if (group) nameFromMembers(members) else members.firstOrNull()?.name
            ?: Handles.display(chatId)

        return ChatIdentity(
            id = chatId,
            name = name,
            color = Handles.avatarColor(name),
            initials = Handles.avatarInitials(name),
            group = group,
            members = members.takeIf { group },
            // Only a one-to-one conversation has a single face to show. A group's photos live on
            // its roster instead, where the list can overlap two of them.
            photoUri = if (group) null else members.firstOrNull()?.photoUri,
        )
    }

    private fun fieldsFor(target: ChatTarget) = IMessageChatFields(
        participants = appJson.encodeToString(ListSerializer(String.serializer()), target.participants),
        senderHandle = target.sender,
        groupId = target.groupId,
        service = SERVICE_IMESSAGE,
    )

    /**
     * The other people in a conversation.
     *
     * Keyed against every handle of ours, not just `target.sender`, so a conversation Apple listed
     * under a second handle of ours does not gain us as a member.
     */
    private fun membersOf(target: ChatTarget): List<GroupMember> {
        val mine = localKeys + Handles.key(target.sender)
        return target.participants
            .filterNot { Handles.key(it) in mine }
            .distinctBy { Handles.key(it) }
            .map { memberFor(it) }
    }

    /**
     * Re-read the address book and push what it now says into every stored conversation.
     *
     * Called on start and again whenever the app comes back to the foreground, because Contacts is
     * edited in another app entirely — there is no event to wait for, and the moment the user
     * returns is the moment they expect the rename they just made to be visible.
     *
     * A read that comes back empty is discarded rather than applied. An empty address book and a
     * denied permission are the same answer here, and applying it would strip every conversation
     * back to a phone number.
     */
    suspend fun refreshAddressBook() {
        val now = System.currentTimeMillis()
        if (now - addressBookReadAt < ADDRESS_BOOK_MIN_INTERVAL_MS) return
        addressBookReadAt = now

        val book = runCatching { contacts.loadContacts() }.getOrDefault(emptyList())
        if (book.isEmpty()) return
        addressBook = book
        // Our own handles go with it: a one-to-one conversation is named after whoever in it is not
        // us, and without them the naming cannot tell. See `ChatRepository.otherHandle`.
        runCatching { chats.refreshFromAddressBook(localKeys) { handle -> contacts.contactForHandle(handle, book) } }
            .onFailure { Log.w(TAG, "address book refresh failed", it) }
    }

    /** Names a handle from the address book, falling back to the address itself. */
    private fun memberFor(handle: String): GroupMember {
        val contact = addressBook.firstOrNull { Handles.matchesNumber(handle, it.about) }
        val name = contact?.name ?: Handles.display(handle)
        return GroupMember(
            id = handle,
            name = name,
            initials = Handles.avatarInitials(name),
            color = Handles.avatarColor(name),
            phone = Handles.display(handle),
            photoUri = contact?.photoUri,
        )
    }

    private companion object {
        const val TAG = "IMessageSyncer"

        /**
         * Enough of Apple's Polls bundle id to recognise one.
         *
         * Matched on a substring rather than in full: the middle of a balloon bundle id is a team
         * identifier, and there is no reason to believe every build of the plugin carries the same
         * one. A payload that decodes as a poll is taken as one either way — see the caller.
         */
        const val POLLS_BUNDLE_MARKER = "PollsExtension"
        const val SERVICE_IMESSAGE = "iMessage"

        /**
         * What a voice message is called where its own contents cannot be shown.
         *
         * The wire name is `Audio Message.caf`, which is a filename and reads like one — nobody
         * wants to be told over a notification that they have received a `.caf`.
         */
        const val VOICE_MESSAGE = "Voice message"

        /**
         * How long to wait before rebuilding a dropped connection.
         *
         * Long enough that a device with no network does not spin rebuilding a socket it cannot
         * open, short enough that coming back into coverage does not leave messages waiting.
         */
        const val RECONNECT_DELAY_MS = 5_000L

        /**
         * How long a typing indicator survives without being renewed.
         *
         * Apple's clients announce that somebody started writing *once* and then say nothing until
         * they stop, so this is not a heartbeat interval to sit just above — it is the whole time
         * somebody may spend composing a message. At fifteen seconds the indicator went out from
         * under anyone writing more than a line or two, which reads as an indicator that sometimes
         * does not show up at all; a minute is what iOS itself holds for. What stops a client that
         * vanished from claiming to be writing forever is no longer this timer but [clearAllTyping],
         * which fires the moment the connection carrying the indicator goes.
         */
        const val TYPING_EXPIRY_MS = 60_000L

        /**
         * The shortest gap between two full reads of the address book.
         *
         * Every resume asks for one, and starting the app is a resume too — so without this a cold
         * start scans the whole address book twice within a second of itself. Well under the time
         * it takes to leave for Contacts, make an edit, and come back, which is the case that has
         * to keep working.
         */
        const val ADDRESS_BOOK_MIN_INTERVAL_MS = 10_000L
    }
}

/** Bridges the Rust tapback enum to the stored one. */
internal fun CoreTapbackKind.toStored(part: Long): StoredReaction = when (this) {
    is CoreTapbackKind.Heart -> StoredReaction(TapbackKind.HEART, part = part)
    is CoreTapbackKind.Like -> StoredReaction(TapbackKind.LIKE, part = part)
    is CoreTapbackKind.Dislike -> StoredReaction(TapbackKind.DISLIKE, part = part)
    is CoreTapbackKind.Laugh -> StoredReaction(TapbackKind.LAUGH, part = part)
    is CoreTapbackKind.Emphasize -> StoredReaction(TapbackKind.EMPHASIZE, part = part)
    is CoreTapbackKind.Question -> StoredReaction(TapbackKind.QUESTION, part = part)
    is CoreTapbackKind.Emoji -> StoredReaction(TapbackKind.EMOJI, emoji = emoji, part = part)
}

/** The reverse, for sending. */
internal fun StoredReaction.toCore(): CoreTapbackKind = when (kind) {
    TapbackKind.HEART -> CoreTapbackKind.Heart
    TapbackKind.LIKE -> CoreTapbackKind.Like
    TapbackKind.DISLIKE -> CoreTapbackKind.Dislike
    TapbackKind.LAUGH -> CoreTapbackKind.Laugh
    TapbackKind.EMPHASIZE -> CoreTapbackKind.Emphasize
    TapbackKind.QUESTION -> CoreTapbackKind.Question
    TapbackKind.EMOJI -> CoreTapbackKind.Emoji(emoji ?: "")
}
