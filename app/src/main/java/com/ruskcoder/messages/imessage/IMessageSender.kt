package com.ruskcoder.messages.imessage

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.ruskcoder.messages.data.db.MessageEntity
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.ATTACHMENT_KIND
import com.ruskcoder.messages.data.model.AUDIO_KIND
import com.ruskcoder.messages.data.model.asShareableText
import com.ruskcoder.messages.data.model.fileUri
import com.ruskcoder.messages.data.model.canRetrySend
import com.ruskcoder.messages.data.model.ChatIdentity
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.data.model.GameCatalogEntry
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.LOCAL_SENDER
import com.ruskcoder.messages.data.model.StickerPlacement
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.UNSEND_WINDOW_MS
import com.ruskcoder.messages.data.model.unsendable
import com.ruskcoder.messages.data.model.MessageEffect
import com.ruskcoder.messages.data.model.PollItem
import com.ruskcoder.messages.data.model.PollPayload
import com.ruskcoder.messages.data.model.Polls
import com.ruskcoder.messages.data.model.encodePollUrl
import com.ruskcoder.messages.data.model.TextRun
import com.ruskcoder.messages.data.db.toJsonOrNull
import com.ruskcoder.messages.data.model.trimRuns
import uniffi.imessage_core.TextRunSpec
import com.ruskcoder.messages.data.model.ReplyPreview
import com.ruskcoder.messages.data.model.display
import com.ruskcoder.messages.data.model.preview
import com.ruskcoder.messages.data.model.reactionFromEmoji
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.data.repository.IMessageChatFields
import com.ruskcoder.messages.data.repository.IMessageMessageTarget
import com.ruskcoder.messages.data.repository.IMessageTarget
import com.ruskcoder.messages.games.GamePigeon
import com.ruskcoder.messages.games.GamePreview
import com.ruskcoder.messages.games.PigeonClient
import com.ruskcoder.messages.util.appJson
import com.ruskcoder.messages.util.makeId
import com.ruskcoder.messages.util.formatClock
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import uniffi.imessage_core.ChatTarget
import uniffi.imessage_core.OutgoingAttachment
import uniffi.imessage_core.OutgoingSticker
import uniffi.imessage_core.OutgoingTapback
import uniffi.imessage_core.OutgoingText
import com.ruskcoder.messages.util.wireGuidFor
import com.ruskcoder.messages.util.saveAttachmentToDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The outbound half: turns a composer action into an Apple send, and records it locally.
 *
 * Every method falls back to the app's existing local-only behavior when the conversation has no
 * Apple identity, so a chat created before sign-in keeps working exactly as it did rather than
 * silently doing nothing.
 *
 * Sends are recorded in the store as soon as Apple accepts them for delivery. Whether they actually
 * reached everyone comes back later as a `SendConfirm` event through [IMessageSyncer] — the same
 * stream inbound messages arrive on, so there is no second path for "did my own message land".
 */
class IMessageSender(
    private val service: IMessageService,
    private val chats: ChatRepository,
    private val scope: CoroutineScope,
    /**
     * Which attachments are still on their way out, for the bubbles that are drawing them.
     *
     * Shared with the syncer rather than owned here: the progress reports come back up the same
     * event stream as inbound messages, so the half of the app that reads that stream is the half
     * that writes the counts, and the half that starts the sends only opens and closes them.
     */
    val transfers: AttachmentTransfers = AttachmentTransfers(),
    /**
     * The pigeon app, for the opening state of a new game.
     *
     * Null in a build without it, and null-safe throughout: a game invite must never depend on
     * pigeon being installed, only on it being there to fill the game in.
     */
    private val pigeon: PigeonClient? = null,
) {

    /** The rows a send writes before it sets off — see [OutgoingRows]. */
    private val rows = OutgoingRows(chats)

    /**
     * Begin a conversation with these addresses, returning the chat it will live in.
     *
     * Unlike most messaging services there is no "create chat" call to make: an iMessage
     * conversation *is* its participant set, so this resolves the addresses, checks who Apple can
     * actually reach, and writes the conversation locally under the id those participants imply.
     * Opening the same set of people again lands on the same conversation.
     *
     * Returns null when iMessage is not set up, or when none of the addresses can receive — the
     * caller falls back to a local-only chat rather than offering a conversation that cannot send.
     */
    /**
     * Which of these addresses Apple can actually reach, canonicalized the way [Handles.key] does.
     *
     * The same question [startConversation] asks before it writes anything, asked early: a picker
     * can then say who is not on iMessage while the recipients are still being chosen, rather than
     * letting a conversation be started and quietly turned into a local one.
     *
     * Null means the question could not be answered — iMessage is not set up here, or the lookup
     * failed — which is emphatically not the same answer as "nobody". Drawn as though it were, an
     * app with no iMessage on it would mark everyone in the address book as unreachable.
     */
    suspend fun reachableTargets(addresses: List<String>): Set<String>? {
        val handles = addresses.map(Handles::normalize).distinct()
        if (handles.isEmpty()) return emptySet()
        return try {
            // Bounded: this runs while somebody is picking people, and a lookup that never came
            // back would leave the send bar shut for as long as the screen stayed open.
            withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                val sender = service.session().handles().firstOrNull() ?: return@withTimeoutOrNull null
                service.session().validateTargets(handles, sender).map(Handles::key).toSet()
            }
        } catch (e: Exception) {
            Log.i(TAG, "could not check who is on iMessage", e)
            null
        }
    }

    suspend fun startConversation(identity: ChatIdentity, addresses: List<String>): NewConversation? {
        val handles = addresses.map(Handles::normalize).distinct()
        if (handles.isEmpty()) return null

        val sender = try {
            service.session().handles().firstOrNull() ?: return null
        } catch (e: Exception) {
            Log.i(TAG, "iMessage unavailable, staying local", e)
            return null
        }

        val reachable = try {
            service.session().validateTargets(handles, sender)
        } catch (e: Exception) {
            Log.w(TAG, "could not validate targets", e)
            return null
        }
        if (reachable.isEmpty()) return null

        // Apple's own conversation includes us, and `is_group` is decided by that full count.
        val participants = (reachable + sender).distinct()
        // A group *is* its identifier, and the identifier is the conversation's id on this device
        // too — the two have to be the same string or a reply, whose id is derived from Apple's
        // identifier, files itself in a second conversation.
        val groupId = identity.id.takeIf { identity.group }
        val target = ChatTarget(
            participants = participants,
            sender = sender,
            groupName = identity.name.takeIf { identity.group },
            // Persist the identifier before the first send. RustPush otherwise mutates only its
            // temporary ConversationData and a later send would create a different Apple thread.
            groupId = groupId,
        )

        chats.createIMessageChat(
            identity = identity,
            fields = IMessageChatFields(
                participants = appJson.encodeToString(
                    ListSerializer(String.serializer()),
                    participants,
                ),
                senderHandle = sender,
                groupId = groupId,
                service = SERVICE_IMESSAGE,
            ),
        )

        return NewConversation(
            chatId = identity.id,
            unreachable = handles.filterNot { it in reachable },
        )
    }

    /**
     * Open a conversation and put the first message in it, without anything waiting on Apple.
     *
     * The recipient picker used to hold the screen until [startConversation] came back, because the
     * chat id was whatever that call derived — so pressing Send left you on the picker, with the
     * draft already gone, for the length of a network round trip. The id is decided by the caller
     * now (a group's own identifier, or the one handle a one-to-one is named by, both of which are
     * exactly what the inbound path derives for the same people), so the conversation can be put on
     * the device and opened at once and the registration can happen behind it.
     *
     * Runs on the sender's own scope rather than the screen's: the picker is gone a frame later,
     * and a send cancelled because the screen that started it was disposed is a lost message.
     */
    fun startConversationAndSend(identity: ChatIdentity, addresses: List<String>, text: String, replyTo: ReplyPreview?) {
        scope.launch {
            // Before anything else, so the conversation the picker just navigated to has a chat to
            // draw rather than an empty screen with no name in its header.
            chats.ensureChat(identity)
            if (startConversation(identity, addresses) == null) {
                // Nobody reachable on iMessage, or no account at all: the conversation is a local
                // one, exactly as it was before iMessage existed.
                chats.sendMessage(identity, text, replyTo)
            } else {
                sendText(identity.id, identity, text, replyTo)
            }
        }
    }

    /**
     * Send a text message, returning the one that was stored.
     *
     * The message is stored — and so drawn — before Apple is told about it, and returns as soon as
     * it is: a send that waited for the round trip left the composer empty and the conversation
     * unchanged for as long as the network took, which read as the app having missed the tap. The
     * trip itself continues on [scope], so it also survives leaving the conversation mid-send.
     *
     * Returning the message rather than nothing because the caller may need the result — the
     * conversation's focus mode lifts a just-sent reply onto its stack.
     */
    suspend fun sendText(
        chatId: String,
        identity: ChatIdentity,
        text: String,
        replyTo: ReplyPreview? = null,
        /**
         * The body broken into formatted stretches, or empty for plain text.
         *
         * Trimmed against the trimmed body below rather than sent as written: the draft's leading
         * and trailing whitespace goes, and the marks that went with it have to go too or the runs
         * stop spelling the text they are describing.
         */
        runs: List<TextRun> = emptyList(),
        /** The send effect to play at both ends, where one was picked. */
        effect: MessageEffect? = null,
    ): Message? {
        val body = text.trim()
        if (body.isEmpty()) return null
        // One reading of the formatting, used twice: once for the wire and once for the row this
        // conversation draws. The two must agree, or your own message looks different to you than
        // it does to whoever receives it.
        val stored = trimRuns(text, body, runs)
        val wire = stored.map { it.toSpec() }

        val target = chats.imessageTarget(chatId)
            ?: return chats.sendMessage(identity, body, replyTo)
        val replyTarget = replyTo?.let { chats.imessageMessageTarget(it.id) }
        if (replyTo != null) {
            // A quote whose target cannot be resolved is sent as an ordinary message, which looks
            // from the other end exactly like replying having done nothing at all.
            Log.d(TAG, "reply to ${replyTo.id} -> guid=${replyTarget?.guid} part=${replyTarget?.replyPart}")
        }

        val pending = rows.recordPending(target, body, replyTo, stored, effect) ?: return null

        // In the table before the trip starts, so a resume that runs while it is out does not
        // mistake it for a send the process lost — see [resumeInterruptedSends].
        sendingText.add(pending.id)
        scope.launch {
            try {
                // The card for a link in the message, fetched here before the send rather than
                // alongside it: it travels inside the message, so it has to exist before the message
                // does. Held to a short timeout, and a page that does not answer in time simply
                // sends as plain text — see [fetchLinkPreview].
                val link = fetchLinkPreview(pending.id, body)
                // Once, unless it fails before it could have been tried — see
                // [retryingQuickFailures]. A real timeout is the end of this attempt, and the next
                // is the person's to ask for — see [wireGuidFor], which is what keeps that next one
                // from being a copy.
                val guid = retryingQuickFailures("text") {
                    service.session().sendText(
                        OutgoingText(
                            chat = target.toCore(),
                            text = body,
                            subject = null,
                            attachments = emptyList(),
                            replyToGuid = replyTarget?.guid,
                            // The whole of the quoted part — see [IMessageMessageTarget.replyPart]
                            // for why this is a range rather than the part index it looks like.
                            replyToPart = replyTarget?.replyPart,
                            effect = effect?.wireId,
                            voice = false,
                            runs = wire,
                            guid = wireGuidFor(pending.id),
                            linkPreview = link,
                        )
                    )
                }
                chats.markSent(pending.id, guid)
            } catch (e: Exception) {
                Log.w(TAG, "send failed", e)
                failOrQueue(pending.id, e)
            } finally {
                sendingText.remove(pending.id)
            }
        }

        return pending
    }

    /**
     * One conversation a share is going to.
     *
     * [addresses] is empty for a conversation that already exists — it has Apple's own fields
     * stored and needs no introduction. It is filled only for someone picked out of the address
     * book who has never been messaged here, where the conversation has to be registered before
     * anything can be sent into it.
     */
    data class ShareTarget(val identity: ChatIdentity, val addresses: List<String> = emptyList())

    /**
     * Send one piece of shared content to several conversations at once.
     *
     * Sequential rather than parallel, and deliberately so: each conversation may need registering
     * with Apple first, and firing a dozen of those at a rate-limited service concurrently is how
     * a share of five pictures to five people becomes five failed conversations. The order within
     * one conversation matters too — the files, then the words — because that is the order they
     * would have been sent in by hand.
     *
     * [attachments] pairs each description with the uri its bytes are at. Both must already be the
     * app's own copies: the grant on whatever the sharing app handed over does not outlive the
     * screen this was started from. See [AttachmentStaging.stageShared].
     *
     * A lone file with words beside it goes as one captioned message, the way a picture picked in
     * a conversation does. Anything else sends the files first and the words after, because a
     * caption can only belong to one of them.
     */
    fun shareTo(
        context: Context,
        targets: List<ShareTarget>,
        attachments: List<Pair<Attachment, Uri>>,
        text: String,
    ) {
        if (targets.isEmpty()) return
        val body = text.trim()
        val captioned = attachments.size == 1 && body.isNotEmpty()
        scope.launch {
            targets.forEach { target ->
                val identity = target.identity
                runCatching {
                    // Before anything is sent, so the conversation exists to hold it — and so a
                    // share to somebody new shows up in the list even if the send then fails.
                    chats.ensureChat(identity)
                    if (target.addresses.isNotEmpty() && chats.imessageTarget(identity.id) == null) {
                        startConversation(identity, target.addresses)
                    }
                    attachments.forEach { (attachment, uri) ->
                        sendAttachment(
                            context = context,
                            chatId = identity.id,
                            identity = identity,
                            attachment = attachment,
                            uri = uri,
                            caption = body.takeIf { captioned },
                        )
                    }
                    if (body.isNotEmpty() && !captioned) sendText(identity.id, identity, body)
                }.onFailure {
                    // One conversation refusing the share must not take the rest of it down: the
                    // others are separate sends to separate people and have nothing to do with it.
                    Log.w(TAG, "could not share to ${identity.id}", it)
                }
            }
        }
    }

    /**
     * Send a file, with an optional caption.
     *
     * The upload happens inside the Rust core as part of the send, so this suspends for as long as
     * the file takes to reach Apple. Falls back to the local-only store when the conversation has
     * no Apple identity, which keeps the composer's behavior unchanged for a local chat.
     */
    suspend fun sendAttachment(
        context: Context,
        chatId: String,
        identity: ChatIdentity,
        attachment: Attachment,
        /** Null for an attachment with no file behind it — a dropped pin, a contact card. */
        uri: Uri?,
        caption: String? = null,
        replyTo: ReplyPreview? = null,
    ): Boolean {
        val target = chats.imessageTarget(chatId)
        if (target == null) {
            chats.sendAttachment(identity, attachment, replyTo)
            return true
        }
        // Nothing to upload and no file of its own — a dropped pin, or a game invite reaching this
        // path without having gone through [sendGameInvite] (a conversation that lost its Apple
        // identity mid-compose) — goes as words. A contact card is written to a real vCard below.
        if (uri == null && attachment !is Attachment.ContactCard) {
            return sendText(chatId, identity, attachment.asShareableText(), replyTo) != null
        }

        val replyTarget = replyTo?.let { chats.imessageMessageTarget(it.id) }

        // The row goes in *first*, before a byte has been copied or uploaded, exactly as a text
        // send does. It used to go in last — staged, uploaded, and only then stored — so a photo
        // appeared in the conversation when the upload finished and a large video simply did not
        // appear at all for the better part of a minute. The picker closed onto a thread with
        // nothing new in it, which reads as the send having been dropped.
        //
        // It is drawn from the picked `content://` URI, which is the same uri the finished row
        // carries anyway, so there is nothing to swap over when the upload lands.
        val pendingId = rows.recordPendingAttachment(target, attachment, caption, replyTo) ?: return false
        pushAttachment(context, target, pendingId, attachment, uri, caption, replyTarget)
        return true
    }

    /**
     * Stage the file and put one already-recorded attachment row on the wire.
     *
     * Split out from [sendAttachment] because a retry does exactly this and nothing else: the row
     * exists, its place in the thread is settled, and all that is left is the part that failed.
     * Everything before it — deciding there is something to send, writing the bubble — happens once
     * per message however many times it is attempted.
     */
    private fun pushAttachment(
        context: Context,
        target: IMessageTarget,
        pendingId: String,
        attachment: Attachment,
        uri: Uri?,
        caption: String?,
        replyTarget: IMessageMessageTarget?,
    ) {
        // On the sender's own scope, so leaving the conversation — or the whole screen — does not
        // cancel an upload that is already halfway out.
        track(pendingId) {
            transfers.begin(pendingId, uri?.let { AttachmentStaging.sizeOf(context, it) } ?: 0L)
            try {
                val staged = when {
                    // A shared contact has no file behind it, so one is written: sent as a vCard it
                    // arrives as a card the recipient can add to Contacts, where its text arrives
                    // as a message.
                    uri == null && attachment is Attachment.ContactCard ->
                        AttachmentStaging.stageContact(context, attachment.name, attachment.phone)
                    // No file to read — never a reason to take the process down.
                    uri == null -> null
                    else -> AttachmentStaging.stage(context, uri)
                }
                // Staged once, outside the retrying below: the copy is what is uploaded, and taking
                // it again on every attempt would re-read the whole video off disk each time.
                if (staged == null) {
                    Log.w(TAG, "could not read $uri for sending")
                    chats.markSendFailed(pendingId, "Could not read the file")
                    return@track
                }

                keepTrying("attachment", wireGuid = wireGuidFor(pendingId)) {
                    val guid = service.session().sendText(
                        OutgoingText(
                            chat = target.toCore(),
                            text = caption.orEmpty(),
                            subject = null,
                            // Labelled with the row it belongs to, so the core's progress reports
                            // come back naming the bubble that should draw them.
                            attachments = listOf(staged.copy(transferId = pendingId)),
                            replyToGuid = replyTarget?.guid,
                            replyToPart = replyTarget?.replyPart,
                            effect = null,
                            voice = false,
                            runs = emptyList(),
                            guid = wireGuidFor(pendingId),
                        )
                    )
                    chats.markSent(pendingId, guid)
                }
                // Once it has gone, and outside the retries: a save that failed is not a send that
                // failed, and must not send the picture again.
                if (autoSaveMedia() && (attachment is Attachment.Image || attachment is Attachment.Video)) {
                    val saved = attachment.fileUri()?.takeIf { it.isNotBlank() } ?: uri?.toString()
                    if (saved != null) {
                        withContext(Dispatchers.IO) { runCatching { saveAttachmentToDevice(context, saved) } }
                    }
                }
            } finally {
                transfers.finish(pendingId)
                AttachmentStaging.prune(context)
            }
        }
    }

    /**
     * Send a new game invite as a real iMessage balloon — the way GamePigeon on iOS does it.
     *
     * A fresh session is opened and its starting state encoded into the balloon's `url`
     * ([GamePigeon.encode]); the pigeon app only ever sees the decoded map. [gameId] must match a
     * game in pigeon's `GameRegistry`. A conversation with no Apple identity has no balloon to
     * carry it, so it falls back to a local-only invite row like other attachments.
     */
    suspend fun sendGameInvite(
        context: Context,
        chatId: String,
        identity: ChatIdentity,
        gameId: String,
        gameName: String,
        /** GamePigeon's state-format version for this game — see [GameCatalogEntry]. */
        version: String,
        replyTo: ReplyPreview? = null,
    ): Boolean {
        // The balloon session is an NSUUID on the wire, not a free-form string. It used to be one
        // of our own `m_<base36>` row ids, which the encoder could not parse and which took the
        // whole send down with it — no game ever left the device.
        val session = UUID.randomUUID().toString()
        // Each game's own opening fields come from pigeon, which is the app that knows them — a
        // Knockout board, Darts' mode and avatar. Without them GamePigeon on iOS cannot open the
        // game and offers to update the app instead. A missing pigeon leaves the bare envelope,
        // which still shows a bubble here but will not open on an iPhone.
        val opening = pigeon?.newGameState(gameId).orEmpty()
        val state = GamePigeon.newGame(context, gameId, gameName, version) + opening
        val displayName = state["game_name"]?.takeIf { it.isNotBlank() } ?: gameName
        val url = GamePigeon.encode(state)
        val caption = GamePigeon.caption(state, GamePigeon.senderUuid(context))
        val previewStaged = stageGamePreview(context, gameId, displayName)

        val invite = Attachment.GameInvite(
            gameId = gameId,
            gameName = displayName,
            sessionId = session,
            url = url,
            bundleId = GamePigeon.BUNDLE_ID,
            ldText = displayName,
            caption = caption,
            previewPath = previewStaged?.path,
            isLive = true,
        )

        val target = chats.imessageTarget(chatId)
        if (target == null) {
            chats.sendAttachment(identity, invite, replyTo)
            return true
        }

        val replyTarget = replyTo?.let { chats.imessageMessageTarget(it.id) }
        val pendingId = rows.recordPendingAttachment(target, invite, caption = null, replyTo = replyTo) ?: return false

        track(pendingId) {
            try {
                keepTrying("game invite") {
                    val guid = service.session().sendAppMessage(
                        uniffi.imessage_core.OutgoingAppMessage(
                            chat = target.toCore(),
                            bundleId = GamePigeon.BUNDLE_ID,
                            appName = GamePigeon.APP_NAME,
                            appId = GamePigeon.APP_ID.toULong(),
                            ldText = displayName,
                            url = url,
                            session = session,
                            caption = caption,
                            subcaption = null,
                            imageTitle = null,
                            imageSubtitle = null,
                            secondarySubcaption = null,
                            tertiarySubcaption = null,
                            isLive = true,
                            icon = null,
                            preview = previewStaged?.copy(transferId = pendingId),
                            replyToGuid = replyTarget?.guid,
                            replyToPart = replyTarget?.replyPart,
                        )
                    )
                    chats.markSent(pendingId, guid)
                    // Now that Apple has named the message, the invite can record the guid every
                    // later turn of this game will address.
                    chats.setAttachmentPayload(pendingId, invite.copy(rootGuid = guid))
                }
            } finally {
                AttachmentStaging.prune(context)
            }
        }
        return true
    }

    /**
     * Put a game an extension composed on the wire, as its own balloon.
     *
     * The counterpart to [sendGameInvite] for the OpenPigeon path: there is no opening state to
     * ask for and no envelope to build, because the extension has already produced the whole
     * message. All that is left is the part only a messaging app can do.
     *
     * [continues] is the invite this turn belongs to, when it is a turn rather than a new game —
     * its Apple guid is the association target, which is what keeps every turn of a game in one
     * bubble thread instead of a column of unrelated invites.
     */
    fun sendMadridGameInBackground(
        context: Context,
        chatId: String,
        identity: ChatIdentity,
        invite: Attachment.GameInvite,
        continues: String? = null,
        /** The picture the extension drew for this turn — see [MadridBridge.artwork]. */
        artwork: ByteArray? = null,
    ) {
        scope.launch {
            runCatching {
                if (continues != null) {
                    sendGameTurnFromInvite(context, chatId, identity, continues, invite, artwork)
                } else {
                    sendComposedGame(context, chatId, identity, invite, artwork)
                }
            }.onFailure { Log.e(TAG, "could not send extension game message", it) }
        }
    }

    /**
     * [sendGameTurn], started on the application's own scope and not waited for.
     *
     * A move is reported by the pigeon app, over AIDL, to a callback registered when the game was
     * opened — and by the time it arrives the pigeon activity is in front and the screen that
     * registered it is stopped, or gone. A send launched on that screen's coroutine scope is
     * cancelled with the composition, so the turn is played on the board and never leaves the
     * device: the two players watch the same game diverge with nothing in the thread between them.
     *
     * The turn belongs to the conversation, not to whoever happened to be looking at it, so it is
     * sent from the scope that outlives every screen.
     */
    fun sendGameTurnInBackground(
        context: Context,
        chatId: String,
        identity: ChatIdentity,
        messageId: String,
        state: Map<String, String>,
    ) {
        scope.launch {
            runCatching { sendGameTurn(context, chatId, identity, messageId, state) }
                .onFailure { Log.e(TAG, "could not send game turn for $messageId", it) }
        }
    }

    /**
     * Send the local player's move as the next turn on a game already in the thread.
     *
     * This is deliberately *not* another balloon. Apple carries a continuation as an association
     * against the message that opened the exchange, so the bubble already on screen updates in
     * place; sending each turn as a fresh message — which is what this used to do — leaves the
     * other player looking at a column of identical invites, and GamePigeon on iOS never chains
     * them into one game.
     *
     * [messageId] is the row the game was opened from. Its Apple guid is the association target,
     * and its stored payload is the board this move updates.
     */
    /**
     * Ask a poll — Apple's Polls balloon, as its own message.
     *
     * The counterpart to [sendGameInvite], and deliberately shaped like it: a poll is an iMessage
     * app message, not text, so an iPhone in the thread draws the real thing rather than a numbered
     * list somebody has to read. See [PollPayload] for what rides in the url.
     */
    suspend fun sendPoll(
        chatId: String,
        identity: ChatIdentity,
        title: String,
        choices: List<String>,
        replyTo: ReplyPreview? = null,
    ): Boolean {
        val trimmed = choices.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmed.size < 2) return false
        // An NSUUID on the wire, as for a game — see [sendGameInvite]'s note.
        val session = UUID.randomUUID().toString()
        val myHandle = chats.myHandles(chatId).firstOrNull()
        val options = trimmed.map { Polls.newOption(it, myHandle) }
        val payload = PollPayload(
            item = PollItem(
                orderedPollOptions = options,
                votes = emptyList(),
                creatorHandle = myHandle,
                title = title.trim().ifEmpty { Polls.DEFAULT_TITLE },
            ),
            version = 1,
        )
        val url = encodePollUrl(payload)
        val ldText = Polls.ldText(options)

        val poll = Attachment.Poll(
            sessionId = session,
            url = url,
            bundleId = Polls.BUNDLE_ID,
            ldText = ldText,
        )

        val target = chats.imessageTarget(chatId)
        if (target == null) {
            chats.sendAttachment(identity, poll, replyTo)
            return true
        }

        val replyTarget = replyTo?.let { chats.imessageMessageTarget(it.id) }
        val pendingId = rows.recordPendingAttachment(target, poll, caption = null, replyTo = replyTo)
            ?: return false

        track(pendingId) {
            keepTrying("poll") {
                val guid = service.session().sendAppMessage(
                    uniffi.imessage_core.OutgoingAppMessage(
                        chat = target.toCore(),
                        bundleId = Polls.BUNDLE_ID,
                        appName = Polls.APP_NAME,
                        appId = null,
                        ldText = ldText,
                        url = url,
                        session = session,
                        caption = null,
                        subcaption = null,
                        imageTitle = null,
                        imageSubtitle = null,
                        secondarySubcaption = null,
                        tertiarySubcaption = null,
                        // A poll's whole point is that the bubble changes as people answer it.
                        isLive = true,
                        icon = null,
                        preview = null,
                        replyToGuid = replyTarget?.guid,
                        replyToPart = replyTarget?.replyPart,
                    )
                )
                chats.markSent(pendingId, guid)
                // Now that Apple has named the message, the poll can record the guid every vote
                // and every added choice will address.
                chats.setAttachmentPayload(pendingId, poll.copy(rootGuid = guid))
            }
        }
        return true
    }

    /**
     * Answer a poll, or add a choice to one.
     *
     * An *association* against the poll's own bubble — never a reply and never a new message. That
     * distinction is the whole reason this exists: a vote sent as a message is a bubble saying "I
     * vote Tacos" under a poll that still says nobody has voted, on every device in the thread.
     *
     * [payload] is what the update carries: only our own answers for a vote, the whole document
     * again for an added choice. See [votePayload] and [optionsPayload].
     */
    suspend fun sendPollUpdate(
        chatId: String,
        identity: ChatIdentity,
        pollMessageId: String,
        payload: PollPayload,
    ): Boolean {
        val row = chats.storedRow(pollMessageId) ?: return false
        val poll = runCatching {
            appJson.decodeFromString(Attachment.serializer(), row.payload.orEmpty())
        }.getOrNull() as? Attachment.Poll ?: return false

        val target = chats.imessageTarget(chatId)
        // Every update addresses the message that asked the poll. A row that never learned which
        // that was falls back to naming itself, which is right for the poll and the best guess
        // available for anything else.
        val rootGuid = poll.rootGuid ?: chats.imessageMessageTarget(pollMessageId)?.guid
        val url = encodePollUrl(payload)
        val options = payload.item?.orderedPollOptions.orEmpty()
        val ldText = if (options.isEmpty()) poll.ldText.orEmpty() else Polls.ldText(options)

        val update = poll.copy(
            url = url,
            ldText = ldText,
            rootGuid = rootGuid,
            // What marks this as an answer rather than a question. The thread reads it to keep the
            // update out of the conversation and fold it into the poll instead.
            updateOf = rootGuid ?: poll.sessionId,
        )

        if (target == null || rootGuid == null) {
            chats.sendAttachment(identity, update, replyTo = null)
            return true
        }
        val pendingId = rows.recordPendingAttachment(target, update, caption = null, replyTo = null)
            ?: return false

        track(pendingId) {
            keepTrying("poll update") {
                val guid = service.session().sendAppUpdate(
                    uniffi.imessage_core.OutgoingAppUpdate(
                        chat = target.toCore(),
                        targetGuid = rootGuid,
                        bundleId = poll.bundleId ?: Polls.BUNDLE_ID,
                        appName = Polls.APP_NAME,
                        appId = null,
                        ldText = ldText,
                        url = url,
                        session = poll.sessionId,
                        caption = null,
                        subcaption = null,
                        imageTitle = null,
                        imageSubtitle = null,
                        secondarySubcaption = null,
                        tertiarySubcaption = null,
                        isLive = true,
                        icon = null,
                        preview = null,
                        // Silent. Apple raises no notification for a vote, and a thread of five
                        // people answering a poll would otherwise buzz five times.
                        notificationText = "",
                    )
                )
                chats.markSent(pendingId, guid)
            }
        }
        return true
    }

    suspend fun sendGameTurn(
        context: Context,
        chatId: String,
        identity: ChatIdentity,
        messageId: String,
        state: Map<String, String>,
    ): Boolean {
        val row = chats.storedRow(messageId) ?: return false
        val existing = runCatching {
            appJson.decodeFromString(Attachment.serializer(), row.payload.orEmpty())
        }.getOrNull() as? Attachment.GameInvite ?: return false

        val gameId = state["game"]?.takeIf { it.isNotBlank() } ?: existing.gameId
        val gameName = state["game_name"]?.takeIf { it.isNotBlank() } ?: existing.gameName
        val url = GamePigeon.encode(state)
        val caption = GamePigeon.caption(state, GamePigeon.senderUuid(context))
        val previewStaged = stageGamePreview(context, gameId, gameName)

        val target = chats.imessageTarget(chatId)
        // Every turn addresses the message that opened the game. A row that never learned which
        // that was — an invite sent before this was recorded — falls back to naming itself, which
        // is right for the opening message and the best guess available for anything else.
        val rootGuid = existing.rootGuid ?: chats.imessageMessageTarget(messageId)?.guid

        val updated = existing.copy(
            gameId = gameId,
            gameName = gameName,
            url = url,
            ldText = gameName,
            caption = caption,
            previewPath = previewStaged?.path ?: existing.previewPath,
            rootGuid = rootGuid,
        )

        // A turn is its own bubble, the way it is on Apple's clients: the board moves on in a new
        // message and the one before it collapses to a line, rather than the first bubble quietly
        // rewriting itself. See the thread's collapse pass for which of them stays open.
        if (target == null || rootGuid == null) {
            chats.sendAttachment(identity, updated, replyTo = null)
            return true
        }
        val pendingId = rows.recordPendingAttachment(target, updated, caption = null, replyTo = null)
            ?: return false

        track(pendingId) {
            try {
                keepTrying("game turn") {
                    val guid = service.session().sendAppUpdate(
                        uniffi.imessage_core.OutgoingAppUpdate(
                            chat = target.toCore(),
                            targetGuid = rootGuid,
                            bundleId = existing.bundleId ?: GamePigeon.BUNDLE_ID,
                            appName = GamePigeon.APP_NAME,
                            appId = GamePigeon.APP_ID.toULong(),
                            ldText = gameName,
                            url = url,
                            session = existing.sessionId,
                            caption = caption,
                            subcaption = null,
                            imageTitle = null,
                            imageSubtitle = null,
                            secondarySubcaption = null,
                            tertiarySubcaption = null,
                            isLive = existing.isLive,
                            icon = null,
                            preview = previewStaged?.copy(transferId = pendingId),
                            notificationText = caption,
                        )
                    )
                    chats.markSent(pendingId, guid)
                }
            } finally {
                AttachmentStaging.prune(context)
            }
        }
        return true
    }

    /**
     * A brand new game, composed by an extension, as its own balloon.
     *
     * Unlike [sendGameInvite] there is nothing to assemble: `url`, captions and session all came
     * from the extension already encoded, and re-encoding them here would only be a chance to get
     * GamePigeon's byte-exact payload wrong.
     */
    private suspend fun sendComposedGame(
        context: Context,
        chatId: String,
        identity: ChatIdentity,
        invite: Attachment.GameInvite,
        artwork: ByteArray?,
    ): Boolean {
        val displayName = invite.displayName
        val previewStaged = stageArtwork(context, artwork)
            ?: stageGamePreview(context, invite.gameId, displayName)
        val staged = invite.copy(previewPath = previewStaged?.path ?: invite.previewPath)

        val target = chats.imessageTarget(chatId)
        if (target == null) {
            chats.sendAttachment(identity, staged, replyTo = null)
            return true
        }
        val pendingId = rows.recordPendingAttachment(target, staged, caption = null, replyTo = null)
            ?: return false

        track(pendingId) {
            try {
                keepTrying("extension game") {
                    val guid = service.session().sendAppMessage(
                        uniffi.imessage_core.OutgoingAppMessage(
                            chat = target.toCore(),
                            bundleId = staged.bundleId ?: GamePigeon.BUNDLE_ID,
                            appName = GamePigeon.APP_NAME,
                            appId = GamePigeon.APP_ID.toULong(),
                            ldText = displayName,
                            url = staged.url.orEmpty(),
                            session = staged.sessionId,
                            caption = staged.caption,
                            subcaption = staged.subcaption,
                            imageTitle = staged.imageTitle,
                            imageSubtitle = staged.imageSubtitle,
                            secondarySubcaption = staged.secondaryCaption,
                            tertiarySubcaption = staged.tertiaryCaption,
                            isLive = staged.isLive,
                            icon = null,
                            preview = previewStaged?.copy(transferId = pendingId),
                            replyToGuid = null,
                            replyToPart = null,
                        )
                    )
                    chats.markSent(pendingId, guid)
                    chats.setAttachmentPayload(pendingId, staged.copy(rootGuid = guid))
                }
            } finally {
                AttachmentStaging.prune(context)
            }
        }
        return true
    }

    /**
     * A turn an extension composed, addressed to the game it continues.
     *
     * [messageId] is the row the game was opened from; its guid is the association target, so the
     * turn lands in the same bubble thread rather than as a fresh invite.
     */
    private suspend fun sendGameTurnFromInvite(
        context: Context,
        chatId: String,
        identity: ChatIdentity,
        messageId: String,
        invite: Attachment.GameInvite,
        artwork: ByteArray?,
    ): Boolean {
        val row = chats.storedRow(messageId) ?: return false
        val existing = runCatching {
            appJson.decodeFromString(Attachment.serializer(), row.payload.orEmpty())
        }.getOrNull() as? Attachment.GameInvite ?: return false

        val displayName = invite.displayName
        val previewStaged = stageArtwork(context, artwork)
            ?: stageGamePreview(context, invite.gameId, displayName)
        val target = chats.imessageTarget(chatId)
        val rootGuid = existing.rootGuid ?: chats.imessageMessageTarget(messageId)?.guid

        val updated = invite.copy(
            sessionId = invite.sessionId.ifBlank { existing.sessionId },
            bundleId = invite.bundleId ?: existing.bundleId,
            previewPath = previewStaged?.path ?: existing.previewPath,
            rootGuid = rootGuid,
        )

        if (target == null || rootGuid == null) {
            chats.sendAttachment(identity, updated, replyTo = null)
            return true
        }
        val pendingId = rows.recordPendingAttachment(target, updated, caption = null, replyTo = null)
            ?: return false

        track(pendingId) {
            try {
                keepTrying("extension game turn") {
                    val guid = service.session().sendAppUpdate(
                        uniffi.imessage_core.OutgoingAppUpdate(
                            chat = target.toCore(),
                            targetGuid = rootGuid,
                            bundleId = updated.bundleId ?: GamePigeon.BUNDLE_ID,
                            appName = GamePigeon.APP_NAME,
                            appId = GamePigeon.APP_ID.toULong(),
                            ldText = displayName,
                            url = updated.url.orEmpty(),
                            session = updated.sessionId,
                            caption = updated.caption,
                            subcaption = updated.subcaption,
                            imageTitle = updated.imageTitle,
                            imageSubtitle = updated.imageSubtitle,
                            secondarySubcaption = updated.secondaryCaption,
                            tertiarySubcaption = updated.tertiaryCaption,
                            isLive = updated.isLive,
                            icon = null,
                            preview = previewStaged?.copy(transferId = pendingId),
                            notificationText = updated.caption.orEmpty(),
                        )
                    )
                    chats.markSent(pendingId, guid)
                }
            } finally {
                AttachmentStaging.prune(context)
            }
        }
        return true
    }

    /**
     * The extension's own artwork, staged for the balloon.
     *
     * Preferred over [stageGamePreview] whenever it exists: that one draws a coloured card with the
     * game's name on it, which is a placeholder for not having a picture. This is a picture of the
     * actual board.
     */
    private suspend fun stageArtwork(
        context: Context,
        jpegBytes: ByteArray?,
    ): uniffi.imessage_core.OutgoingAttachment? {
        val bytes = jpegBytes?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { AttachmentStaging.stageGamePreview(context, bytes) }.getOrNull()
    }

    /**
     * The bubble's preview image.
     *
     * A GamePigeon balloon always carries one, and iMessage wants an attachment on a balloon
     * message for the receiving client to lay it out. The pigeon app has no board render to hand
     * back, so this is a stand-in card (see [GamePreview]). Null when staging fails, which costs
     * the picture but not the turn.
     */
    private suspend fun stageGamePreview(
        context: Context,
        gameId: String,
        gameName: String,
    ): uniffi.imessage_core.OutgoingAttachment? = runCatching {
        AttachmentStaging.stageGamePreview(context, GamePreview.jpeg(context, gameId, gameName))
    }.getOrNull()

    /**
     * Runs one upload on the sender's own scope, holding on to the job so it can be stopped.
     *
     * A send that will not give up needs something that can call it off, and the only thing with
     * any business doing that is the person who started it: an unsend on a message still going out
     * cancels this rather than asking Apple to recall something that never arrived. See [unsend].
     *
     * Started lazily so the job is in the table before it can possibly finish and try to leave it —
     * otherwise a send that failed instantly would remove an entry that had not been added yet, and
     * leave a dead job sitting in the table under a live row's name.
     */
    private fun track(messageId: String, block: suspend CoroutineScope.() -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: SendTimedOut) {
                // Given up on rather than tried again — see [keepTrying]. The row says so, and the
                // next attempt is the person's to make.
                Log.w(TAG, "send timed out; leaving it failed", e)
                chats.markSendFailed(
                    messageId,
                    e.message?.takeIf { it.isNotBlank() } ?: SEND_FAILED,
                    wireGuid = e.wireGuid,
                )
            }
        }
        sending[messageId]?.cancel()
        sending[messageId] = job
        job.invokeOnCompletion { sending.remove(messageId, job) }
        job.start()
    }

    /**
     * Attempts [send] until it works, or until somebody cancels the send.
     *
     * There is no attempt limit and no deadline, which is the point. A video going out over bad
     * coverage can take several minutes and can fail several times on the way — every layer under
     * this has its own patience and eventually runs out of it — and the old behaviour was to write
     * "Not Delivered" on the bubble at the first refusal and make the whole upload the user's
     * problem again. Now the row simply stays on its way, and the way out is the same one it is for
     * a message you have changed your mind about: hold it down and unsend it.
     *
     * The risk this accepts is a duplicate: a send whose acknowledgement was lost, rather than the
     * send itself, gets made twice. That is the same trade a retry button has always made, taken
     * automatically — and against the alternative, which is a message that certainly never arrived.
     *
     * Except a timeout. That is the one failure that most often means the message *did* arrive and
     * only the answer went missing, so trying again is exactly how it arrived twice. A timeout ends
     * the send and leaves the row failed; [track] writes that on it, and "Try again" goes out under
     * the same GUID — see [wireGuidFor].
     */
    private suspend fun keepTrying(
        what: String,
        /** The GUID the send goes out under, where the app chose it — kept on the row if it times out. */
        wireGuid: String? = null,
        send: suspend () -> Unit,
    ) {
        var attempt = 0
        var quickRetries = 0
        while (true) {
            val started = SystemClock.elapsedRealtime()
            try {
                send()
                return
            } catch (e: CancellationException) {
                // Unsent while it was going out. Nothing to report and nothing to write on the row:
                // whoever cancelled this is deleting it.
                throw e
            } catch (e: Exception) {
                if (e.isSendTimeout()) {
                    // A "timeout" that came back at once waited for nothing — see [retryingQuickFailures].
                    val quick = SystemClock.elapsedRealtime() - started < QUICK_FAILURE_MS
                    if (!quick || quickRetries >= QUICK_RETRIES) throw SendTimedOut(e, wireGuid)
                    quickRetries++
                    Log.w(TAG, "$what send failed at once, retry $quickRetries of $QUICK_RETRIES", e)
                    delay(QUICK_RETRY_WAIT_MS * quickRetries)
                    continue
                }
                val wait = RETRY_BACKOFF_MS shl attempt.coerceAtMost(RETRY_BACKOFF_STEPS)
                Log.w(TAG, "$what send failed, trying again in ${wait}ms", e)
                attempt++
                delay(wait.coerceAtMost(RETRY_BACKOFF_CEILING_MS))
            }
        }
    }

    /**
     * Runs [send], trying again when it fails too fast to have really been tried.
     *
     * A send that comes back with an error inside [QUICK_FAILURE_MS] never waited on Apple for
     * anything: the push socket had died while the phone sat idle, or was halfway through
     * reconnecting, and the write was refused on the spot. That is the failure "Try again" always
     * fixes, because by the time it is pressed the connection is back — so it is pressed here, up
     * to [QUICK_RETRIES] times, before anything is written on the bubble. Every attempt goes out
     * under the same GUID (see [wireGuidFor]), so one that did land is not a second message.
     *
     * A failure that took longer than that is a real answer and is thrown as it is.
     */
    private suspend fun <T> retryingQuickFailures(what: String, send: suspend () -> T): T {
        var retries = 0
        while (true) {
            val started = SystemClock.elapsedRealtime()
            try {
                return send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val quick = SystemClock.elapsedRealtime() - started < QUICK_FAILURE_MS
                if (!quick || retries >= QUICK_RETRIES) throw e
                retries++
                Log.w(TAG, "$what send failed at once, retry $retries of $QUICK_RETRIES", e)
                delay(QUICK_RETRY_WAIT_MS * retries)
            }
        }
    }

    /**
     * Hand a failed message to Apple again, in place.
     *
     * The row that is already in the conversation is re-used rather than a new one being composed
     * from it: a retry is the same message having another go, so it keeps its place in the thread,
     * its timestamp, and the quote it was replying to. Nothing is inserted and nothing moves.
     *
     * Every kind that can fail can be retried, because every one of them still has its source: the
     * row records the picture or the document it was made from and the recording lives on disk for
     * playback, and what a send stages is a *copy* of that. It used to be text only, on the reading
     * that the staged copy was the original — so a photo that failed on a flaky connection could
     * only be deleted and picked again.
     *
     * A send that actually reached Apple but whose acknowledgement did not come back will be
     * delivered twice by this. That is the trade a retry always makes, and the alternative — never
     * offering one — leaves the message undelivered for certain rather than duplicated by accident.
     */
    fun retrySend(context: Context, chatId: String, messageId: String) {
        scope.launch {
            val row = chats.storedRow(messageId) ?: return@launch
            // Only a message that is still failed. One a receipt has since proved delivered has had
            // its failure taken off, and a second press of "Try again" while the first attempt is out
            // finds the failure already cleared — either way, sending again is how it arrives twice.
            if (row.errorText == null) return@launch
            if (!canRetrySend(row.kind)) return@launch
            if (chats.imessageTarget(chatId) == null) return@launch
            // Marked before anything is attempted, so the bubble drops its failure badge the
            // instant the button is pressed rather than when the second attempt comes back.
            chats.markSendRetrying(messageId)
            resend(context, row)
        }
    }

    /**
     * Puts back on the wire every send the app lost track of, and gives recent failures one more go.
     *
     * Run each time the connection comes up (see `ConnectionMonitor`). Two kinds of message are
     * waiting for that moment:
     *
     *  - **Interrupted**: a row written, with no delivery state and no failure on it. The process
     *    died while it was going out, or it was composed with no connection and deliberately left
     *    queued (see [failOrQueue]). Nothing else will ever send it. Rows younger than a few seconds
     *    are left alone, as are any this process is still sending — those are still on their way.
     *  - **Recently failed**: one automatic retry each, per process, for failures in the last few
     *    hours. A failure while the connection was flapping is the common case, and the retry goes
     *    out under the same guid as the first attempt, so if that one did arrive after all the
     *    receiving phone recognises this as the same message rather than a second copy.
     */
    suspend fun resumeInterruptedSends(context: Context) = resumeMutex.withLock {
        val now = System.currentTimeMillis()
        chats.interruptedSends()
            .filter { !sending.containsKey(it.id) && it.id !in sendingText && now - it.createdAt >= RESUME_GRACE_MS }
            .forEach { row ->
                Log.i(TAG, "resuming send ${row.id}, which never finished")
                resend(context, row)
            }
        chats.recentFailedSends(now - AUTO_RETRY_WINDOW_MS)
            .filter { !sending.containsKey(it.id) && it.id !in sendingText && canRetrySend(it.kind) && autoRetried.add(it.id) }
            .forEach { row ->
                Log.i(TAG, "retrying failed send ${row.id} once, now that the connection is back")
                chats.markSendRetrying(row.id)
                resend(context, row)
            }
    }

    /**
     * A send that did not go, recorded as the right one of two things.
     *
     * With no connection at all it is not a failure yet: the row is left with neither a status nor
     * an error, which is exactly what [resumeInterruptedSends] picks up when the connection returns.
     * Writing "Not Delivered" on a message typed in a tunnel made every one of them the person's to
     * notice and retry by hand. With a connection, the refusal is real and is written on the bubble.
     */
    private suspend fun failOrQueue(messageId: String, error: Exception) {
        if (service.state.value !is IMessageService.State.Connected) {
            Log.i(TAG, "no connection; $messageId stays queued until there is one")
            return
        }
        chats.markSendFailed(
            messageId,
            error.message?.takeIf { it.isNotBlank() } ?: SEND_FAILED,
            wireGuid = wireGuidFor(messageId),
        )
    }

    /** Sends one stored row again, whatever kind it is — the part a retry and a resume share. */
    private suspend fun resend(context: Context, row: MessageEntity) {
        val chatId = row.chatId
        val messageId = row.id
        run {
            val target = chats.imessageTarget(chatId) ?: return
            // Resolved against the *quoted* message, which has been sent and so has a guid of its
            // own; the failed row's own guid is not involved.
            val replyTarget = row.replyToId?.let { chats.imessageMessageTarget(it) }

            when (row.kind) {
                AUDIO_KIND -> {
                    val file = row.audioUri?.let { File(Uri.parse(it).path ?: it) }
                    if (file?.isFile != true) {
                        chats.markSendFailed(messageId, "The recording is no longer on this phone")
                        return
                    }
                    pushAudio(target, messageId, file, replyTarget)
                }
                ATTACHMENT_KIND -> {
                    val attachment = row.payload
                        ?.let { runCatching { appJson.decodeFromString(Attachment.serializer(), it) }.getOrNull() }
                    if (attachment == null) {
                        chats.markSendFailed(messageId, SEND_FAILED)
                        return
                    }
                    // Games, polls and other balloons go out on their own paths, not as file
                    // uploads; resending one here has no file behind it. Leave those rows alone.
                    val fileBacked = attachment is Attachment.Image || attachment is Attachment.Video ||
                        attachment is Attachment.File || attachment is Attachment.ContactCard
                    if (!fileBacked) return
                    pushAttachment(
                        context = context,
                        target = target,
                        pendingId = messageId,
                        attachment = attachment,
                        uri = attachment.fileUri()?.takeIf { it.isNotBlank() }?.let(Uri::parse),
                        // The caption is the row's own body: it is what was stored with the file,
                        // and re-sending without it would quietly drop the words.
                        caption = row.body.takeIf { it.isNotBlank() },
                        replyTarget = replyTarget,
                    )
                }
                else -> {
                    if (row.body.isBlank()) return
                    sendingText.add(messageId)
                    try {
                        val guid = retryingQuickFailures("text retry") {
                            service.session().sendText(
                                OutgoingText(
                                    chat = target.toCore(),
                                    text = row.body,
                                    subject = null,
                                    attachments = emptyList(),
                                    replyToGuid = replyTarget?.guid,
                                    replyToPart = replyTarget?.replyPart,
                                    effect = null,
                                    voice = false,
                                    runs = emptyList(),
                                    // The GUID the first attempt used, so this is that message again
                                    // rather than a second one — see [wireGuidFor].
                                    guid = wireGuidFor(messageId),
                                )
                            )
                        }
                        chats.markSent(messageId, guid)
                    } catch (e: Exception) {
                        Log.w(TAG, "retry failed", e)
                        failOrQueue(messageId, e)
                    } finally {
                        sendingText.remove(messageId)
                    }
                }
            }
        }
    }

    /** Whether a photo or video is saved to the phone once it has been sent — see [Settings.autoSaveMedia]. */
    var autoSaveMedia: () -> Boolean = { false }

    /** Fetches link cards; null in a build or test without one, which sends links as plain text. */
    var linkPreviews: LinkPreviews? = null

    /** Whether cards are sent at all — see [Settings.linkPreviews]. */
    var linkPreviewsEnabled: () -> Boolean = { true }

    /**
     * The card for the first link in [body], stored on the row so this side draws it too, and handed
     * back in the shape the wire takes. Null for a message without a link, with previews switched
     * off, or for a page that did not answer quickly enough to be worth holding the message for.
     */
    private suspend fun fetchLinkPreview(messageId: String, body: String): uniffi.imessage_core.LinkPreviewSpec? {
        val previews = linkPreviews ?: return null
        if (!linkPreviewsEnabled()) return null
        val url = com.ruskcoder.messages.data.model.firstLinkIn(body) ?: return null
        val fetched = withTimeoutOrNull(LINK_PREVIEW_TIMEOUT_MS) { previews.fetch(messageId, url) } ?: return null
        runCatching { chats.setLinkPreview(messageId, fetched.preview) }
            .onFailure { Log.w(TAG, "could not store the link card for $messageId", it) }
        return fetched.spec
    }

    /** Sends a recorded AAC/M4A clip through Apple, then stores the playable local recording. */
    /**
     * Send a voice message.
     *
     * The bubble lands before the trip to Apple starts, the way a text does — the recording is
     * already on disk, so there is nothing left to find out about it. It used to be written only
     * once the send came back, which meant the conversation sat unchanged for as long as the
     * conversion and the upload took, with the recorded clip still sitting in the send bar looking
     * unsent. Everything the wait was for — the guid, the tick, the failure — is applied to the row
     * afterwards, in exactly the places [sendText] applies them.
     */
    suspend fun sendAudio(
        chatId: String,
        identity: ChatIdentity,
        uri: String,
        duration: Float,
        waveform: List<Float>,
        replyTo: ReplyPreview? = null,
    ): Boolean {
        val target = chats.imessageTarget(chatId)
        if (target == null) {
            chats.sendAudioMessage(identity, uri, duration, waveform, replyTo)
            return true
        }
        val file = File(uri)
        if (!file.isFile) return false
        val replyTarget = replyTo?.let { chats.imessageMessageTarget(it.id) }
        val pendingId = rows.recordPendingAudio(target, uri, duration, waveform, replyTo) ?: return false
        pushAudio(target, pendingId, file, replyTarget)
        return true
    }

    /**
     * Convert one already-recorded clip and put it on the wire — [sendAudio] without the bubble.
     *
     * The same split as [pushAttachment], for the same reason: this is the half a retry repeats.
     */
    private fun pushAudio(
        target: IMessageTarget,
        pendingId: String,
        file: File,
        replyTarget: IMessageMessageTarget?,
    ) {
        track(pendingId) {
            // Sent as CAF, which is the only container the receiving client will play inline;
            // the recording itself stays as it is, because Android's media stack cannot read
            // CAF back for local playback. A conversion failure falls back to sending the
            // recording as a file, which is worse than an audio message and better than
            // sending nothing.
            val caf = AudioMessages.toCaf(file)
            // No transfer id: a voice note is a few seconds of speech, and a progress ring over
            // a waveform that is gone before it can be read is noise rather than information.
            val attachment = if (caf != null) {
                OutgoingAttachment(
                    path = caf.absolutePath,
                    name = AudioMessages.FILE_NAME,
                    mime = AudioMessages.MIME,
                    uti = AudioMessages.UTI,
                    transferId = null,
                )
            } else {
                Log.w(TAG, "sending ${file.name} as a file: CAF conversion failed")
                OutgoingAttachment(
                    path = file.absolutePath,
                    name = file.name,
                    mime = "audio/mp4",
                    uti = "public.mpeg-4-audio",
                    transferId = null,
                )
            }
            keepTrying("voice message", wireGuid = wireGuidFor(pendingId)) {
                val guid = service.session().sendText(
                    OutgoingText(
                        chat = target.toCore(),
                        text = "",
                        subject = null,
                        attachments = listOf(attachment),
                        replyToGuid = replyTarget?.guid,
                        replyToPart = replyTarget?.replyPart,
                        effect = null,
                        // The flag that makes this arrive as an audio message rather than a
                        // sound file.
                        voice = true,
                        runs = emptyList(),
                        guid = wireGuidFor(pendingId),
                    ),
                )
                chats.markSent(pendingId, guid)
            }
        }
    }

    /** Forward each supported row through the destination's Apple identity. */
    suspend fun forwardMessages(context: Context, target: ChatIdentity, messages: List<Message>) {
        for (message in messages) {
            when (message) {
                is Message.Text -> sendText(target.id, target, message.text)
                is Message.Audio -> sendAudio(target.id, target, message.uri, message.duration, message.waveform)
                // A group is several pictures sent together; each goes on as the picture it is.
                is Message.AttachmentMessage if message.attachment is Attachment.MediaGroup -> {
                    (message.attachment as Attachment.MediaGroup).items.forEach { item ->
                        val uri = when (item) {
                            is Attachment.Image -> item.uri
                            is Attachment.Video -> item.uri
                            else -> ""
                        }.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return@forEach
                        sendAttachment(context, target.id, target, item, uri)
                    }
                }
                is Message.AttachmentMessage -> {
                    val uri = message.attachment.let {
                        when (it) {
                            is Attachment.Image -> it.uri
                            is Attachment.Video -> it.uri
                            is Attachment.File -> it.uri
                            else -> ""
                        }
                    }.takeIf { it.isNotBlank() }?.let(Uri::parse)
                    sendAttachment(context, target.id, target, message.attachment, uri)
                }
                else -> Unit
            }
        }
    }

    /**
     * Add or remove our own tapback.
     *
     * The store is updated first so the bubble reacts immediately. Whether this is an add or a
     * removal has to be decided before that write, since afterwards the stored state is the new one.
     */
    /**
     * Every handle a reaction of ours on this conversation could be filed under.
     *
     * Both, not one: a chat with an Apple identity records our own handle, and one that only ever
     * existed on this device uses [LOCAL_SENDER] — and a conversation can hold reactions from
     * before it was matched up with Apple, so recognising only the current answer would show our
     * own older tapbacks as somebody else's.
     */
    suspend fun myHandles(chatId: String): Set<String> = chats.myHandles(chatId)

    fun toggleTapback(chatId: String, messageId: String, messageText: String, emoji: String) {
        scope.launch {
            val target = chats.imessageTarget(chatId)
            if (target == null) {
                chats.toggleReaction(chatId, messageId, emoji)
                return@launch
            }

            val picked = reactionFromEmoji(emoji)
            val messageTarget = chats.imessageMessageTarget(messageId) ?: return@launch
            val existing = chats.reactionOf(chatId, messageId, target.senderHandle)
            val enable = existing?.display() != picked.display()

            chats.toggleReaction(chatId, messageId, emoji, target.senderHandle)

            try {
                service.session().sendTapback(
                    OutgoingTapback(
                        chat = target.toCore(),
                        targetGuid = messageTarget.guid,
                        targetPart = messageTarget.part.toULong(),
                        targetText = messageText,
                        kind = picked.toCore(),
                        enable = enable,
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "tapback failed", e)
            }
        }
    }

    /**
     * Lays [emoji] on a message as a sticker, where it was dropped.
     *
     * Drawn straight away from a local row, then sent as Apple's sticker tapback; a send that fails
     * takes the sticker back off, since the other side never saw it.
     */
    fun sendSticker(
        context: Context,
        chatId: String,
        messageId: String,
        messageText: String,
        emoji: String,
        placement: StickerPlacement,
        /** How wide the bubble it was dropped on is, in dp — Apple's points. */
        bubbleWidth: Float,
        /**
         * The row of one of our placed stickers this is moving. Taken off here and laid down again
         * where it was dropped; Apple has no way to move one, so the other side gets it anew.
         */
        replacing: String? = null,
    ) {
        scope.launch {
            if (replacing != null) chats.removeStickerRow(chatId, replacing)
            val rendered = withContext(Dispatchers.IO) { StickerImages.renderEmoji(context, emoji) }
            val image = Attachment.Image(
                uri = android.net.Uri.fromFile(rendered.file).toString(),
                width = StickerImages.SIDE,
                height = StickerImages.SIDE,
                sticker = true,
                placement = placement,
                // Kept so the sticker can be picked back up and moved — see `PlacedSticker.emoji`.
                caption = emoji,
            )
            val guid = java.util.UUID.randomUUID().toString().uppercase()
            val target = chats.imessageTarget(chatId)
            val messageTarget = chats.imessageMessageTarget(messageId)
            if (target == null || messageTarget == null) {
                chats.addOwnSticker(chatId, messageId, messageId, 0, LOCAL_SENDER, guid, image)
                return@launch
            }
            val rowId = chats.addOwnSticker(
                chatId, messageId, messageTarget.guid, messageTarget.part, target.senderHandle, guid, image,
            )
            try {
                service.session().sendSticker(
                    OutgoingSticker(
                        chat = target.toCore(),
                        targetGuid = messageTarget.guid,
                        targetPart = messageTarget.part.toULong(),
                        targetText = messageText,
                        image = OutgoingAttachment(
                            path = rendered.file.absolutePath,
                            name = rendered.file.name,
                            mime = "image/png",
                            uti = "public.png",
                            transferId = null,
                        ),
                        placement = uniffi.imessage_core.StickerPlacement(
                            x = placement.x.toDouble(),
                            y = placement.y.toDouble(),
                            scale = placement.scale.toDouble(),
                            rotation = placement.rotation.toDouble(),
                            bubbleWidth = bubbleWidth.toDouble(),
                            effectType = 0,
                        ),
                        hash = rendered.hash,
                        emoji = emoji,
                        label = com.ruskcoder.messages.data.model.ALL_EMOJI.firstOrNull { it.glyph == emoji }
                            ?.keywords?.split(' ', ',')?.firstOrNull { it.isNotBlank() } ?: emoji,
                        guid = guid,
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "sticker failed", e)
                chats.removeStickerRow(chatId, rowId)
            }
        }
    }

    fun edit(chatId: String, messageId: String, newText: String) {
        val body = newText.trim()
        if (body.isEmpty()) return

        scope.launch {
            val target = chats.imessageTarget(chatId)
            if (target == null) {
                chats.editMessage(chatId, messageId, body)
                return@launch
            }
            val messageTarget = chats.imessageMessageTarget(messageId) ?: return@launch
            runCatching { service.session().editMessage(target.toCore(), messageTarget.guid, messageTarget.part.toULong(), body) }
                .onSuccess { chats.editMessage(chatId, messageId, body) }
                .onFailure { Log.w(TAG, "edit failed", it) }
        }
    }

    /**
     * Whether "Unsend" still means something for this message.
     *
     * Always, for one still going out: that is calling it off, and there is no window on changing
     * your mind about something nobody has received. Otherwise only inside [UNSEND_WINDOW_MS] —
     * see [Message.unsendable].
     */
    fun canUnsend(message: Message, now: Long = System.currentTimeMillis()): Boolean =
        message.mine && message !is Message.Deleted &&
            (sending.containsKey(message.id) || message.id in sendingText || message.unsendable(now))

    fun unsend(chatId: String, messageId: String) {
        scope.launch {
            val target = chats.imessageTarget(chatId)
            if (target == null) {
                chats.deleteMessages(chatId, listOf(messageId), forEveryone = true)
                return@launch
            }
            // Still on its way out: called off rather than recalled. An upload keeps trying for as
            // long as it takes — see [keepTrying] — so this is the button that stops one, and it
            // is the same gesture as changing your mind about a message that did arrive.
            if (sending.remove(messageId)?.also { it.cancel() } != null) {
                chats.deleteMessages(chatId, listOf(messageId), forEveryone = false)
                return@launch
            }
            // Past the window a recall is one the other end ignores: the message would stay on
            // their phone and be marked unsent on this one. Checked here as well as in the menus, so
            // every way of asking — the web client, a selection held open past the limit — gets the
            // same answer.
            val sentAt = chats.messageCreatedAt(messageId)
            if (sentAt != null && sentAt > 0 && System.currentTimeMillis() - sentAt > UNSEND_WINDOW_MS) {
                Log.i(TAG, "not unsending $messageId: past the ${UNSEND_WINDOW_MS / 1000}s window")
                return@launch
            }
            val messageTarget = chats.imessageMessageTarget(messageId)
            if (messageTarget == null) {
                // No guid, and nothing sending under this id either: the row never made it onto the
                // wire — a send interrupted by the app being killed, most likely. There is nothing
                // at the far end to take back, so it is simply removed from here.
                chats.deleteMessages(chatId, listOf(messageId), forEveryone = false)
                return@launch
            }
            runCatching { service.session().unsendMessage(target.toCore(), messageTarget.guid, messageTarget.part.toULong()) }
                .onSuccess { chats.deleteMessages(chatId, listOf(messageId), forEveryone = true) }
                .onFailure { Log.w(TAG, "unsend failed", it) }
        }
    }

    fun setTyping(chatId: String, active: Boolean) {
        scope.launch {
            val target = chats.imessageTarget(chatId) ?: return@launch
            runCatching { service.session().setTyping(target.toCore(), active) }
                .onFailure { Log.w(TAG, "typing failed", it) }
        }
    }

    /** Voice messages this session has already reported as played, so a replay stays quiet. */
    private val playedReported = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Tell the sender their voice message was listened to — Apple's played receipt.
     *
     * Takes the row id and sends the wire guid: an attachment row past the first is keyed
     * `guid#part#index`, and the receipt has to name the message Apple knows.
     */
    fun markPlayed(chatId: String, messageId: String) {
        val guid = messageId.substringBefore('#')
        if (guid.isBlank() || !playedReported.add(guid)) return
        scope.launch {
            val target = chats.imessageTarget(chatId) ?: return@launch
            runCatching { service.session().markPlayed(target.toCore(), guid) }
                .onFailure {
                    playedReported.remove(guid)
                    Log.w(TAG, "played receipt failed", it)
                }
        }
    }

    /** Tell the sender their message was read. */
    fun markRead(chatId: String, lastMessageId: String) {
        scope.launch {
            val target = chats.imessageTarget(chatId) ?: return@launch
            runCatching { service.session().markRead(target.toCore(), lastMessageId) }
                .onFailure { Log.w(TAG, "read receipt failed", it) }
        }
    }

    /**
     * Break through the recipient's Focus for the message they were last sent quietly.
     *
     * Recorded locally first and unconditionally. The break-through is not something Apple answers
     * — there is no receipt for it and no second chance to ask — so a screen that waited to be told
     * would sit on "Delivered Quietly" with a button that appeared to do nothing. If the send does
     * fail, the worst of it is a line that says "Delivered" about a message that stayed quiet,
     * which is a far smaller lie than a control that does not respond.
     */
    fun notifyAnyway(chatId: String) {
        scope.launch {
            val guid = chats.quietlyDeliveredMessage(chatId) ?: return@launch
            chats.markNotifiedAnyway(chatId)
            val target = chats.imessageTarget(chatId) ?: return@launch
            runCatching { service.session().notifyAnyway(target.toCore(), guid) }
                .onFailure { Log.w(TAG, "notify anyway failed", it) }
        }
    }

    fun renameGroup(chatId: String, name: String) {
        scope.launch {
            // Noted before the send, so the conversation shows what happened whether or not Apple
            // ever echoes the change back to the device that made it.
            noteLocally(chatId, "You named the conversation \"$name\"")
            val target = chats.imessageTarget(chatId) ?: return@launch
            runCatching { service.session().renameGroup(target.toCore(), name) }
                .onFailure { Log.w(TAG, "rename failed", it) }
        }
    }

    /**
     * Put a conversation event we caused into the thread.
     *
     * Optimistic on purpose: the alternative is waiting for Apple to tell us about our own change,
     * which it may never do for the device that made it — leaving a rename with nothing on screen
     * to show for it. The repository's own echo window keeps this from doubling up if Apple does
     * send it back.
     */
    private suspend fun noteLocally(chatId: String, text: String) {
        chats.recordSystemMessage(
            chatId = chatId,
            wireGuid = makeId(),
            text = text,
            mine = true,
            at = System.currentTimeMillis(),
        )
    }

    /**
     * Tell Apple who is in the group now.
     *
     * iMessage has no add or remove — a membership change is the whole new list, ordered against
     * other changes by a version number that only ever goes up. Ours is bumped and stored here so
     * a second change in the same session does not reuse the number the first one claimed.
     *
     * The roster is read, sent, and only then written back locally — in that order, in one
     * coroutine. Apple's wire message is a *diff*: it carries the conversation's current
     * participants as the "from" side and this list as the "to" side, and the recipients work out
     * who joined or left. The screen used to write the new roster to the database first and then
     * call this; the target then read the already-reduced list, so "from" and "to" came out equal,
     * the diff was empty, and nobody was actually added or removed even though the local list had
     * changed. Mirrors openbubbles' `chatParticipant`, which builds the change off the live
     * conversation and reflects its own message afterwards. [name] is carried through so a custom
     * group name survives the roster write.
     */
    fun setParticipants(chatId: String, members: List<GroupMember>, name: String? = null) {
        scope.launch {
            val target = chats.imessageTarget(chatId) ?: return@launch

            // Members that came from Apple carry their handle as their id; members picked out of
            // the address book carry a contact id and a phone number instead.
            val handles = members
                .map { member ->
                    if (member.id.startsWith("mailto:") || member.id.startsWith("tel:")) member.id
                    else Handles.normalize(member.phone ?: member.id)
                }
                .plus(target.senderHandle)
                .distinct()

            val version = target.groupVersion + 1
            runCatching { service.session().setParticipants(target.toCore(), handles, version.toULong()) }
                .onSuccess {
                    // The change is on its way — reflect it locally now, the way the reference
                    // does. Note is worked out before the roster is overwritten so it still sees
                    // what changed; a failed send leaves the local list untouched.
                    noteParticipantChange(chatId, members)
                    chats.setMembers(chatId, members, name)
                    chats.setGroupVersion(chatId, version)
                }
                .onFailure { Log.w(TAG, "participant change failed", it) }
        }
    }

    /** Works out what our own roster change did, and says so in the thread. */
    private suspend fun noteParticipantChange(chatId: String, members: List<GroupMember>) {
        val before = chats.membersOf(chatId)
        val beforeKeys = before.associateBy { Handles.key(it.id) }
        val afterKeys = members.associateBy { Handles.key(it.id) }
        val added = afterKeys.filterKeys { it !in beforeKeys }.values.map { it.name }
        val removed = beforeKeys.filterKeys { it !in afterKeys }.values.map { it.name }
        val text = when {
            added.isNotEmpty() && removed.isEmpty() -> "You added ${joinNames(added)}"
            removed.isNotEmpty() && added.isEmpty() -> "You removed ${joinNames(removed)}"
            added.isNotEmpty() && removed.isNotEmpty() -> "You changed who is in the conversation"
            else -> return
        }
        noteLocally(chatId, text)
    }

    /** "A", "A and B", "A, B and C" — the way a notice reads rather than a comma-joined list. */
    private fun joinNames(names: List<String>): String = when (names.size) {
        0 -> "someone"
        1 -> names.first()
        2 -> "${names[0]} and ${names[1]}"
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /**
     * Uploads that are still going, by the row each belongs to.
     *
     * Concurrent because it is written from the sender's scope and read from whichever coroutine an
     * unsend arrives on.
     */
    private val sending = ConcurrentHashMap<String, Job>()

    /** Text sends out on the wire right now — not in [sending], which is only for cancellable uploads. */
    private val sendingText: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Failures already given their one automatic retry in this process. */
    private val autoRetried: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Two connections coming up in quick succession are one catch-up, not two sends of each row. */
    private val resumeMutex = Mutex()

    private companion object {
        const val TAG = "IMessageSender"
        const val SERVICE_IMESSAGE = "iMessage"

        /** Shown on the bubble when the send threw something with nothing readable to say. */
        const val SEND_FAILED = "Not Delivered"

        /** How long a "who is on iMessage?" lookup is given before the picker stops waiting. */
        const val LOOKUP_TIMEOUT_MS = 8_000L

        /** The first wait between attempts at an upload, doubling from there. */
        const val RETRY_BACKOFF_MS = 2_000L

        /** How many times that doubling is allowed to apply, and where it settles. */
        const val RETRY_BACKOFF_STEPS = 4
        const val RETRY_BACKOFF_CEILING_MS = 30_000L

        /** A failure sooner than this was never really attempted — see [retryingQuickFailures]. */
        const val QUICK_FAILURE_MS = 5_000L

        /** How many times such a failure is tried again before the bubble says so. */
        const val QUICK_RETRIES = 3

        /** The wait before the first of those, growing by as much again each time. */
        const val QUICK_RETRY_WAIT_MS = 1_500L

        /** How old an unfinished row has to be before a resume treats it as lost rather than in flight. */
        const val RESUME_GRACE_MS = 15_000L

        /** The longest a message waits for its link card before going without one. */
        const val LINK_PREVIEW_TIMEOUT_MS = 5_000L

        /** How far back a failure still earns its one automatic retry when the connection returns. */
        const val AUTO_RETRY_WINDOW_MS = 6L * 60 * 60 * 1000
    }
}

/**
 * A conversation that was just created, and who could not be included.
 *
 * `unreachable` is worth surfacing rather than dropping: an address Apple cannot reach means that
 * person will not see anything sent here, which is not something to discover later.
 */
data class NewConversation(
    val chatId: String,
    val unreachable: List<String>,
)

private fun IMessageTarget.toCore() = ChatTarget(
    participants = participants,
    sender = senderHandle,
    groupName = name.takeIf { group },
    groupId = groupId,
)

/** One run in the shape the core takes. The trimming that precedes it lives in [trimRuns]. */
private fun TextRun.toSpec(): TextRunSpec = TextRunSpec(
    text = text,
    bold = format.bold,
    italic = format.italic,
    underline = format.underline,
    strikethrough = format.strikethrough,
    // Apple numbers its own effects; see [TextEffectKind].
    effect = format.effect?.appleId?.toUInt(),
)

/** A send that timed out, which is given up on rather than attempted again — see [IMessageSender]. */
private class SendTimedOut(cause: Throwable, val wireGuid: String?) : Exception(cause.message, cause)

/** Whether a send failed by timing out: the push connection not answering, rather than a refusal. */
private fun Throwable.isSendTimeout(): Boolean = generateSequence(this) { it.cause }.any { cause ->
    val text = cause.message.orEmpty()
    text.contains("timeout", ignoreCase = true) || text.contains("timed out", ignoreCase = true)
}


