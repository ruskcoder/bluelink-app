package com.ruskcoder.messages.data.repository

import android.util.Log
import com.ruskcoder.messages.data.backup.BackupMessages
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.data.db.ChatDao
import com.ruskcoder.messages.data.db.ChatEntity
import com.ruskcoder.messages.data.db.MessageDao
import com.ruskcoder.messages.data.db.toStoredOverride
import com.ruskcoder.messages.data.db.MessageEntity
import com.ruskcoder.messages.data.db.decodeEditHistory
import com.ruskcoder.messages.data.db.editHistoryJsonOrNull
import com.ruskcoder.messages.data.db.decodeReactions
import com.ruskcoder.messages.data.db.decodeReceipts
import com.ruskcoder.messages.data.db.toChat
import com.ruskcoder.messages.data.db.toJsonOrNull
import com.ruskcoder.messages.data.db.toChatRecord
import com.ruskcoder.messages.data.db.toEntity
import com.ruskcoder.messages.data.db.toJson
import com.ruskcoder.messages.data.db.toStoredMessage
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.Chat
import com.ruskcoder.messages.data.model.ChatIdentity
import com.ruskcoder.messages.data.model.ChatThemeKey
import com.ruskcoder.messages.data.model.Contact
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.data.model.UNSENT_QUOTE
import com.ruskcoder.messages.data.model.withQuoted
import com.ruskcoder.messages.data.model.DELETED_KIND
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.LOCAL_SENDER
import com.ruskcoder.messages.data.model.MessageHit
import com.ruskcoder.messages.data.model.MessageReceipts
import com.ruskcoder.messages.data.model.ReplyPreview
import com.ruskcoder.messages.data.model.StoredReaction
import com.ruskcoder.messages.data.model.colorFor
import com.ruskcoder.messages.data.model.display
import com.ruskcoder.messages.data.model.initialsFor
import com.ruskcoder.messages.data.model.reactionFromEmoji
import com.ruskcoder.messages.data.model.nameFromMembers
import com.ruskcoder.messages.data.db.previewTextFor
import com.ruskcoder.messages.data.model.preview
import com.ruskcoder.messages.data.model.silenced
import com.ruskcoder.messages.imessage.Handles
import com.ruskcoder.messages.util.appJson
import com.ruskcoder.messages.util.formatClock
import com.ruskcoder.messages.util.makeId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import com.ruskcoder.messages.util.wireGuidFor

/**
 * The Apple-side identity of a conversation, which [ChatIdentity] has no room for.
 *
 * Split out rather than widened into `ChatIdentity` because every local caller — opening a chat,
 * forwarding, creating a group — would otherwise have to supply fields it knows nothing about.
 */
data class IMessageChatFields(
    /** JSON-encoded list of Apple handles. */
    val participants: String?,
    val senderHandle: String?,
    val groupId: String?,
    val service: String?,
)

/**
 * Everything needed to address a conversation on Apple's side.
 *
 * Read out of storage on demand rather than carried on `Chat`, so the display models stay free of
 * protocol detail — a screen renders a conversation without knowing whether it has an Apple
 * identity at all.
 */
data class IMessageTarget(
    val chatId: String,
    val name: String,
    val group: Boolean,
    val participants: List<String>,
    val senderHandle: String,
    val groupId: String?,
    /** Apple orders concurrent membership changes by this, so it has to be remembered and bumped. */
    val groupVersion: Long,
)

data class IMessageMessageTarget(val guid: String, val part: Long, val textLength: Int) {
    /**
     * The message part a reply points at, in the form iMessage threads on.
     *
     * Not the bare part index it looks like it should be. Apple's `threadOriginatorPart` is
     * `<part>:<start>:<end>` — the character range within that part that is being quoted — and the
     * whole part is what a reply from this app always answers. Sending just the index produced a
     * message iOS accepted and then rendered as an ordinary one, because there was no range for it
     * to attach a thread to; the reply only ever looked like a reply on the device that sent it.
     */
    val replyPart: String get() = "$part:0:$textLength"
}

/**
 * The app's core conversation store, backed by Room. This is the Kotlin analogue of the RN app's
 * `context/chats.tsx`, with one structural simplification it didn't have available: Room's
 * `@Query` `Flow`s auto-invalidate and re-emit after any write to the tables they read, on their
 * own. The RN version had to hand-roll optimistic in-memory patches (`bumpChat`, `patchThreads`,
 * a manual write queue) purely to fake that reactivity over `expo-sqlite`, which has none. Here
 * `chats` and `threadFlow(...)` are simple projections of the DAOs' own `Flow`s — a write lands,
 * Room notices, the UI updates. The write-serialization (`writeMutex`) and the simulated
 * sent → delivered → read receipt timers are real behavior, not reactivity plumbing, so those are
 * kept.
 */
class ChatRepository(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {
    data class Counts(
        val unread: Int = 0,
        val groups: Int = 0,
    )

    private val writeMutex = Mutex()
    private val receiptJobs = ConcurrentHashMap<String, Job>()

    private val _ready = MutableStateFlow(false)

    /** False until the first read from storage lands, so screens can avoid an empty-state flash. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    val chats: StateFlow<List<Chat>> = chatDao.observeAll()
        .map { entities -> entities.map { it.toChatRecord().toChat() } }
        .onEach { _ready.value = true }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val counts: StateFlow<Counts> = chats
        .map { list ->
            // WhatsApp counts conversations with unread messages, not the messages themselves.
            Counts(
                unread = list.count { it.unread > 0 },
                groups = list.count { it.group },
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, Counts())

    /**
     * One live thread per conversation, kept warm after the screen showing it has gone.
     *
     * Opening a chat used to start from nothing: the screen composed with an empty list, the
     * transition began, and the query and the mapping below ran while it was still moving — so the
     * conversation slid in blank, showed the "saved on this device" notice meant for a chat with no
     * messages in it, and only then filled. All of that work is the same work every time, and the
     * answer is already known before the tap.
     *
     * Held as a `StateFlow` so the value is there to be read synchronously on the first composition
     * rather than arriving a frame or two later, and kept for [ThreadCacheMs] after the last
     * subscriber leaves so going back to a conversation is instant too. Beyond that it is dropped:
     * a warm copy of every thread the user has ever opened is a memory leak with a nice name.
     */
    private val threads = mutableMapOf<String, StateFlow<List<Message>>>()

    /**
     * How many of each conversation's newest messages the live thread holds.
     *
     * A window rather than the whole history: see [MessageDao.observeRecentForChat]. It only grows
     * while a conversation is open — scrolling back ([loadOlder]) or jumping to something far up
     * ([ensureLoaded]) — and goes back to a page when the screen leaves ([resetWindow]).
     */
    private val windows = ConcurrentHashMap<String, MutableStateFlow<Int>>()

    private fun windowOf(chatId: String): MutableStateFlow<Int> =
        windows.getOrPut(chatId) { MutableStateFlow(ThreadPageSize) }

    @Synchronized
    fun threadFlow(chatId: String): StateFlow<List<Message>> = threads.getOrPut(chatId) {
        buildThreadFlow(chatId).stateIn(
            scope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = ThreadCacheMs),
            emptyList(),
        )
    }

    /**
     * Widens the window by a page, if there is anything further back to show.
     *
     * Called as the reader nears the top of what is loaded. A window that came back short of its own
     * size already holds the whole conversation, so asking again is a no-op rather than a query.
     */
    fun loadOlder(chatId: String) {
        if (chatId.isBlank()) return
        val window = windowOf(chatId)
        val loaded = threads[chatId]?.value?.size ?: return
        if (loaded < window.value) return
        window.value = window.value + ThreadPageSize
    }

    /** Makes sure at least the newest [count] messages are in the window — an unread run, say. */
    fun ensureWindow(chatId: String, count: Int) {
        if (chatId.isBlank()) return
        val window = windowOf(chatId)
        if (count > window.value) window.value = count
    }

    /**
     * Widens the window far enough to hold [messageId], and waits until the thread does.
     *
     * For anything that has to put a particular message on screen — a search hit, a quote tapped a
     * long way up. Returns false for a message this conversation doesn't hold, or one that did not
     * arrive in time.
     */
    suspend fun ensureLoaded(chatId: String, messageId: String): Boolean {
        if (chatId.isBlank()) return false
        val thread = threadFlow(chatId)
        if (thread.value.any { it.id == messageId }) return true
        val needed = messageDao.countFromMessage(chatId, messageId)
        if (needed <= 0) return false
        ensureWindow(chatId, needed + WindowSlack)
        return withTimeoutOrNull(EnsureLoadedTimeoutMs) { thread.first { list -> list.any { it.id == messageId } } } != null
    }

    /** Shrinks a conversation's window back to one page, once nobody is reading far back in it. */
    fun resetWindow(chatId: String) {
        windows[chatId]?.value = ThreadPageSize
    }

    /**
     * Every message in a conversation, live, with quotes resolved the same way [threadFlow] does.
     *
     * Cold and uncached, for the few readers that genuinely need the whole history rather than what
     * is on screen: a conversation's shared media, search confined to one chat, the web client
     * looking a message up by id.
     */
    fun allMessagesFlow(chatId: String): Flow<List<Message>> =
        if (chatId.isBlank()) flowOf(emptyList()) else messageDao.observeForChat(chatId).map(ThreadMapper()::map)

    /**
     * Whether this conversation's messages are already in hand.
     *
     * What tells a chat that has not loaded yet apart from one that is genuinely empty — the two
     * look identical from an empty list, which is why the empty-chat notice used to flash on the
     * way into every conversation.
     */
    fun threadReady(chatId: String): Boolean = threads[chatId]?.value?.isNotEmpty() == true

    /**
     * Warms a conversation in the background so opening it costs nothing.
     *
     * Subscribes just long enough to pull the first real value through, then lets go; the cache
     * above keeps it for a while afterwards. Bounded by a timeout so a conversation that genuinely
     * has no messages does not hold a subscription open waiting for one that is never coming.
     */
    fun prefetchThread(chatId: String) {
        if (chatId.isBlank() || threadReady(chatId)) return
        scope.launch {
            withTimeoutOrNull(PrefetchTimeoutMs) { threadFlow(chatId).first { it.isNotEmpty() } }
        }
    }

    /**
     * Live messages for one conversation's window; recomposes automatically as writes land.
     *
     * The mapper is made once per flow and kept across emissions, so a receipt landing on one
     * message re-decodes that one row rather than every row in the window — see [ThreadMapper].
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun buildThreadFlow(chatId: String): Flow<List<Message>> =
        if (chatId.isBlank()) {
            flowOf(emptyList())
        } else {
            val mapper = ThreadMapper()
            windowOf(chatId)
                .flatMapLatest { limit -> messageDao.observeRecentForChat(chatId, limit) }
                .map(mapper::map)
                .flowOn(Dispatchers.Default)
        }

    /**
     * Turns stored rows into the thread's display models, remembering what it has already decoded.
     *
     * Decoding a row means parsing up to five JSON columns, and a thread used to do that for every
     * row on every write. Rows are data classes, so an unchanged row compares equal to the one it
     * was decoded from and is reused as it is. The quote resolution below is a pass over plain
     * objects and costs nothing next to that.
     *
     * Not thread-safe, and doesn't need to be: a flow's `map` runs its emissions one after another.
     */
    private class ThreadMapper {
        private val decoded = HashMap<String, Pair<MessageEntity, Message>>()

        private fun decode(entity: MessageEntity): Message {
            val cached = decoded[entity.id]
            if (cached != null && cached.first == entity) return cached.second
            return entity.toStoredMessage().message.also { decoded[entity.id] = entity to it }
        }

        fun map(entities: List<MessageEntity>): List<Message> {
            // Anything that has left the window, or been deleted, is let go of.
            if (decoded.size > entities.size + ThreadResolution.CachePruneSlack) {
                val live = entities.mapTo(HashSet(entities.size)) { it.id }
                decoded.keys.retainAll(live)
            }
            return ThreadResolution.resolve(entities, ::decode)
        }
    }

    private object ThreadResolution {
        /** How many stale decodes a mapper may hold before it sweeps them. */
        const val CachePruneSlack = 64

        fun resolve(entities: List<MessageEntity>, decode: (MessageEntity) -> Message): List<Message> {
                val byId = entities.associateBy { it.id }
                // Which messages have since been taken back, so a reply quoting one shows a stub
                // rather than the words the unsend was supposed to remove.
                val unsentIds = entities.filter { it.kind == DELETED_KIND }.mapTo(mutableSetOf()) { it.id }
                // What each person is called, recovered from the quotes already stored against
                // their messages — the only place the table records a display name at all. In a
                // one-to-one it stays empty and the reply's own stored name is the peer's, which is
                // the same answer; it earns its keep in a group, where a thread has more than two
                // people in it and the quote could be any of them.
                val nameBySender = HashMap<String, String>()
                entities.forEach { entity ->
                    val sender = entity.replyToId?.let { byId[it] }?.senderId ?: return@forEach
                    val name = entity.replyToName?.takeIf(String::isNotBlank) ?: return@forEach
                    nameBySender.putIfAbsent(sender, name)
                }

                /**
                 * The newest message so far in each thread, keyed by the message that opened it.
                 *
                 * Apple addresses every reply to the message that *started* the thread, not to the
                 * one it answers — so a back-and-forth of six replies came out of storage as six
                 * copies of the same quote, all pointing at the opening line, and a thread read as
                 * a pile of messages that had each independently answered the same thing. Walking
                 * the conversation in order and remembering where each thread has got to turns that
                 * back into the chain it was: every reply quotes the one before it, and only the
                 * first quotes the message the thread opened with.
                 */
                val threadTail = HashMap<String, String>()

                return entities.map { entity ->
                    val message = decode(entity)
                    val root = entity.replyToId ?: return@map message
                    // A sticker answers nothing: it is stored against the message it is stuck on,
                    // and stays pointed at it. Chained like a reply, the second sticker on a
                    // message came out stuck to the first sticker, which is not a bubble, and
                    // vanished — and a reply after it would have quoted a sticker.
                    if (entity.id.startsWith(STICKER_ROW_PREFIX)) return@map message
                    val quotedId = threadTail[root] ?: root
                    threadTail[root] = entity.id
                    // A thread whose opening message isn't in this conversation — never synced, or
                    // deleted outright, or older than the window — keeps the preview the reply was
                    // stored with.
                    val quoted = byId[quotedId] ?: return@map message
                    message.withQuoted(
                        ReplyPreview(
                            id = quotedId,
                            name = when {
                                quoted.mine -> "You"
                                else -> quoted.senderId?.let(nameBySender::get) ?: entity.replyToName.orEmpty()
                            },
                            text = if (quotedId in unsentIds) UNSENT_QUOTE else decode(quoted).preview(),
                            // Which side of the conversation the quote hangs on. Nothing on the
                            // reply records this; it is a fact about the quoted message.
                            mine = quoted.mine,
                        )
                    )
                }
        }
    }

    fun markRead(chatId: String) {
        scope.launch { chatDao.setUnread(chatId, 0) }
    }

    /**
     * Mark a conversation unread again from the list.
     *
     * A count of 1 rather than restoring whatever it was: the number stood for messages that have
     * since been read, and re-inventing it would be a claim about history. What the badge means
     * here is "come back to this".
     */
    fun markUnread(chatId: String) {
        scope.launch { chatDao.setUnread(chatId, 1) }
    }

    /** Held at the top of the list, regardless of how recently anything was said in it. */
    fun setPinned(chatId: String, pinned: Boolean) {
        scope.launch { chatDao.setPinned(chatId, pinned) }
    }

    fun setMuted(chatId: String, muted: Boolean) {
        scope.launch { chatDao.setMuted(chatId, muted) }
    }

    /**
     * Silences a conversation until [until], an epoch-millis instant; 0 lifts the snooze.
     *
     * Stored as the moment it ends rather than as a flag with a timer behind it, so nothing has to
     * be running for a snooze to expire — it simply stops being in the future.
     */
    fun setSnoozedUntil(chatId: String, until: Long) {
        scope.launch { chatDao.setSnoozedUntil(chatId, until) }
    }

    /** A picture behind this conversation only; null puts the app's own wallpaper back. */
    fun setWallpaper(chatId: String, uri: String?) {
        scope.launch { chatDao.setWallpaper(chatId, uri) }
    }

    /** Sets this conversation's own read-receipt choice; null puts it back on the global setting. */
    fun setReadReceipts(chatId: String, enabled: Boolean?) {
        scope.launch { chatDao.setReadReceipts(chatId, enabled.toStoredOverride()) }
    }

    /** Sets this conversation's own typing-indicator choice; null puts it back on the global setting. */
    fun setTypingIndicators(chatId: String, enabled: Boolean?) {
        scope.launch { chatDao.setTypingIndicators(chatId, enabled.toStoredOverride()) }
    }

    fun setBlocked(chatId: String, blocked: Boolean) {
        scope.launch { chatDao.setBlocked(chatId, blocked) }
    }

    /**
     * Records what Apple's presence channel said about somebody's Focus.
     *
     * Named by handle rather than by conversation, because that is how it arrives: a Focus belongs
     * to a person, and the one-to-one chat with them is simply where it becomes visible. Matched
     * through [Handles.key], since the handle a status arrives under and the handle stored against
     * a conversation agree on the person without always agreeing on the spelling.
     */
    suspend fun setNotificationsSilenced(handle: String, silenced: Boolean) {
        val key = Handles.key(handle)
        chatDao.oneToOneParticipants()
            .filter { row -> decodeHandles(row.participants).any { Handles.key(it) == key } }
            .forEach { row -> chatDao.setNotifsSilenced(row.id, silenced) }
    }

    /**
     * Everyone we would like a Focus status for — the other party in each one-to-one conversation.
     *
     * Apple only pushes a status to devices that have asked for that person's channel, so this is
     * what the subscription is rebuilt from whenever the conversation list changes shape. Handles
     * go out exactly as they were stored rather than canonicalized: this one is matched on Apple's
     * side, against the channel keys somebody shared with us, and our own spelling of a number is
     * not something Apple has ever seen.
     */
    suspend fun focusStatusHandles(): List<String> =
        chatDao.oneToOneParticipants()
            .flatMap { decodeHandles(it.participants) }
            .distinct()

    /** Tolerant of unreadable JSON: a conversation with a corrupt list simply has no handles. */
    private fun decodeHandles(json: String): List<String> =
        runCatching { appJson.decodeFromString(ListSerializer(String.serializer()), json) }
            .getOrDefault(emptyList())

    /**
     * The message a "Notify Anyway" in this conversation would be about, if there is one.
     *
     * The newest of our own that was delivered quietly and has not been read since — which is
     * exactly the window in which breaking through is still worth anything.
     */
    suspend fun quietlyDeliveredMessage(chatId: String): String? =
        messageDao.newestQuietlyDelivered(chatId)

    /**
     * Records that we have broken through this conversation's Focus.
     *
     * Both halves matter: the messages already sent stop reading as quiet, and the conversation
     * remembers when, so the next few minutes' sends are not marked quiet either — Apple treats one
     * break-through as opening a window rather than as covering a single message.
     */
    suspend fun markNotifiedAnyway(chatId: String, at: Long = System.currentTimeMillis()) {
        writeMutex.withLock {
            chatDao.setNotifiedAnyways(chatId, at)
            messageDao.clearDeliveredQuietly(chatId)
        }
    }

    /**
     * Whether the shade should be told about something arriving in this conversation.
     *
     * Read off the already-loaded list rather than the database: this is asked on the path a push
     * takes through the app, and a query there would put disk between a message landing and the
     * notification for it. An unknown conversation announces itself — it is about to be created,
     * and nobody has muted anything yet.
     */
    fun announces(chatId: String): Boolean {
        val chat = chats.value.find { it.id == chatId } ?: return true
        return !chat.blocked && !chat.silenced()
    }

    /**
     * Whether this conversation has been blocked.
     *
     * Read the same way and for the same reason as [announces], and asked one step earlier: a
     * blocked conversation's messages are not stored at all, so unblocking does not produce a
     * backlog of everything that was said meanwhile. That is what blocking means everywhere else,
     * and a Block that only silenced the shade would be a promise the app does not keep.
     */
    fun isBlocked(chatId: String): Boolean = chats.value.find { it.id == chatId }?.blocked == true


    /** Returns the message that was created, or null if the text was blank. */
    fun sendMessage(identity: ChatIdentity, text: String, replyTo: ReplyPreview? = null): Message? {
        val body = text.trim()
        if (body.isEmpty()) return null
        val createdAt = System.currentTimeMillis()
        val message = Message.Text(
            id = makeId(),
            text = body,
            mine = true,
            time = formatClock(createdAt),
            createdAt = createdAt,
            status = DeliveryStatus.SENT,
            replyTo = replyTo,
        )
        commitMessage(identity, message, createdAt)
        return message
    }

    fun sendAudioMessage(
        identity: ChatIdentity,
        uri: String,
        duration: Float,
        waveform: List<Float>,
        replyTo: ReplyPreview? = null,
    ) {
        val createdAt = System.currentTimeMillis()
        val message = Message.Audio(
            id = makeId(),
            mine = true,
            time = formatClock(createdAt),
            createdAt = createdAt,
            uri = uri,
            duration = duration,
            waveform = waveform,
            status = DeliveryStatus.SENT,
            replyTo = replyTo,
        )
        commitMessage(identity, message, createdAt)
    }

    fun sendAttachment(identity: ChatIdentity, attachment: Attachment, replyTo: ReplyPreview? = null) {
        val createdAt = System.currentTimeMillis()
        val message = Message.AttachmentMessage(
            id = makeId(),
            mine = true,
            time = formatClock(createdAt),
            createdAt = createdAt,
            attachment = attachment,
            status = DeliveryStatus.SENT,
            replyTo = replyTo,
        )
        commitMessage(identity, message, createdAt)
    }

    /** Copies messages into another conversation as new outgoing ones — quote/reactions/sender dropped. */
    fun forwardMessages(target: ChatIdentity, messages: List<Message>) {
        for (source in messages) {
            val createdAt = System.currentTimeMillis()
            val copy: Message? = when (source) {
                is Message.Text -> Message.Text(id = makeId(), text = source.text, mine = true, time = formatClock(createdAt), createdAt = createdAt, status = DeliveryStatus.SENT)
                is Message.AttachmentMessage -> Message.AttachmentMessage(id = makeId(), mine = true, time = formatClock(createdAt), createdAt = createdAt, attachment = source.attachment, status = DeliveryStatus.SENT)
                is Message.Audio -> Message.Audio(id = makeId(), mine = true, time = formatClock(createdAt), createdAt = createdAt, uri = source.uri, duration = source.duration, waveform = source.waveform, status = DeliveryStatus.SENT)
                else -> null
            }
            if (copy != null) commitMessage(target, copy, createdAt)
        }
    }

    /**
     * When a message of ours should be shown as having been written, which is not always the clock.
     *
     * The conversation is ordered by `created_at`, and the two ends do not share a clock: an
     * incoming message is stamped by Apple, ours by this phone. A peer whose clock runs a second or
     * two ahead therefore lands a message that sorts *after* the reply you write to it — you read
     * theirs, answer it, and your answer appears above the thing it is answering.
     *
     * What the conversation is actually about is the order things appeared on this screen, so an
     * outgoing message is placed after everything already in the thread. The shift is only ever the
     * width of the clock disagreement — a second or so — which is below the resolution of the time
     * a bubble prints, and it applies to sends alone: history arriving from a sync is placed by its
     * own timestamps, which is the only order it has.
     *
     * Bounded by [SKEW_LIMIT_MS], because the correction compounds: every message after the first
     * is placed after the one before it, so a single inbound message stamped a day into the future
     * would drag every reply you ever write in that conversation along with it, and the date
     * separators with them. Past that the disagreement is not a clock being slightly out, and the
     * real time is the better answer even though it sorts oddly against the one bad row.
     */
    private suspend fun showingAt(chatId: String, createdAt: Long): Long {
        val newest = messageDao.newestCreatedAt(chatId) ?: return createdAt
        if (newest < createdAt || newest - createdAt > SKEW_LIMIT_MS) return createdAt
        return newest + 1
    }

    private fun commitMessage(identity: ChatIdentity, message: Message, createdAt: Long) {
        scope.launch {
            writeMutex.withLock {
                upsertIdentity(identity)
                val at = showingAt(identity.id, createdAt)
                messageDao.insert(message.toEntity(identity.id, at))
                chatDao.bumpAfterMessage(identity.id, message.preview(), at, unreadDelta = 0)
            }
        }
        scheduleReceipts(identity.id, message.id)
    }

    private suspend fun upsertIdentity(identity: ChatIdentity) {
        val membersJson = identity.members?.toJson()
        val updated = chatDao.updateIdentity(
            identity.id,
            identity.name,
            identity.color,
            identity.initials,
            identity.group,
            membersJson,
            identity.photoUri,
        )
        if (updated == 0) {
            chatDao.insert(
                ChatEntity(
                    id = identity.id,
                    name = identity.name,
                    color = identity.color,
                    initials = identity.initials,
                    isGroup = identity.group,
                    muted = false,
                    unread = 0,
                    preview = "",
                    updatedAt = System.currentTimeMillis(),
                    theme = ChatThemeKey.DEFAULT.storageKey,
                    members = membersJson,
                    photoUri = identity.photoUri,
                ),
            )
        }
    }

    /** Walks one just-sent message from SENT through DELIVERED to READ. */
    private fun scheduleReceipts(chatId: String, messageId: String) {
        receiptJobs[messageId] = scope.launch {
            delay(DELIVERED_AFTER_MS)
            messageDao.setStatus(chatId, messageId, DeliveryStatus.DELIVERED.name)
            delay(READ_AFTER_MS - DELIVERED_AFTER_MS)
            receiptJobs.remove(messageId)
            // Read at fire time, not captured at send time: turning read receipts off stops the
            // ones already in flight from ever reaching READ.
            if (settingsRepository.settings.value.readReceipts) {
                messageDao.setStatus(chatId, messageId, DeliveryStatus.READ.name)
            }
        }
    }

    /**
     * Adds our own reaction to a message, or takes it off if it is already the one there.
     *
     * Only our own key is touched, so reacting never disturbs anyone else's tapback on the same
     * message. `sender` is our handle on an iMessage conversation, and [LOCAL_SENDER] on one that
     * only exists on this device.
     */
    fun toggleReaction(chatId: String, messageId: String, emoji: String, sender: String = LOCAL_SENDER) {
        scope.launch {
            writeMutex.withLock {
                val current = messageDao.loadForChat(chatId).find { it.id == messageId } ?: return@withLock
                val existing = decodeReactions(current.reactions)
                val picked = reactionFromEmoji(emoji)
                // Picking the reaction already there removes it; picking a different one replaces
                // it, because iMessage allows a person only one tapback per message part.
                val next = if (existing[sender]?.display() == picked.display()) {
                    existing - sender
                } else {
                    existing + (sender to picked)
                }
                messageDao.setReactions(chatId, messageId, next.toJsonOrNull())
            }
        }
    }

    /**
     * Record a tapback that arrived from Apple.
     *
     * Separate from [toggleReaction] because nothing is being toggled: the sender already decided,
     * and `enable` says whether they added or removed it.
     */
    suspend fun applyRemoteReaction(
        chatId: String,
        wireGuid: String,
        part: Long,
        sender: String,
        reaction: StoredReaction,
        enable: Boolean,
    ) {
        writeMutex.withLock {
            // Falling back to the message's first stored part rather than dropping the tapback:
            // Apple addresses a reaction at a part index, and the parts we stored do not always
            // line up with the ones it counted — a captioned photo is two rows here, and a plain
            // text message sent from this app is row 0 whatever index comes back. A reaction on the
            // wrong part of the right message is still the right message.
            val current = messageDao.findByWirePart(wireGuid, part)
                ?: messageDao.findByWire(wireGuid).firstOrNull()
                ?: return@withLock
            val existing = decodeReactions(current.reactions)
            val next = if (enable) existing + (sender to reaction) else existing - sender
            messageDao.setReactions(chatId, current.id, next.toJsonOrNull())
        }
    }

    // -----------------------------------------------------------------------------------------
    // Inbound from Apple
    //
    // These take a fully-formed entity rather than a display `Message`, because a message off the
    // wire carries columns the display model has no room for — the sending handle, the part index,
    // the service, the effect. They are suspending rather than fire-and-forget so the syncer can
    // process one push before pulling the next, which is what keeps arrival order intact.
    // -----------------------------------------------------------------------------------------

    /**
     * Store a message that arrived from Apple, creating the conversation if this is the first.
     *
     * Ignores a message whose id is already stored: Apple re-sends until it is acknowledged, so the
     * same message can legitimately arrive more than once.
     */
    suspend fun receiveMessage(
        identity: ChatIdentity,
        entity: MessageEntity,
        chatFields: IMessageChatFields,
        unread: Boolean,
    ): Boolean = receiveMessages(identity, listOf(entity), chatFields, unread)

    /** Stores every part of one Apple message atomically and reports whether anything was new. */
    suspend fun receiveMessages(
        identity: ChatIdentity,
        entities: List<MessageEntity>,
        chatFields: IMessageChatFields,
        unread: Boolean,
    ): Boolean {
        var inserted = false
        writeMutex.withLock {
            val fresh = entities.filterNot { messageDao.existsWirePart(it.wireGuid, it.part) }
            // One of our own messages coming back from Apple under the GUID a failed row is already
            // carrying: it was sent. See [applyReceipt].
            entities.filter { it.mine && it.wireGuid.isNotBlank() }.map { it.wireGuid }.distinct().forEach { guid ->
                messageDao.findMineByWire(guid).filter { it.errorText != null }.forEach { row ->
                    messageDao.clearError(row.id)
                    messageDao.setWireGuid(row.id, guid, DeliveryStatus.SENT.name)
                }
            }
            if (fresh.isEmpty()) return@withLock
            upsertIdentity(identity)
            chatDao.setIMessageFields(
                identity.id,
                chatFields.participants,
                chatFields.senderHandle,
                chatFields.groupId,
                chatFields.service,
            )
            fresh.forEach { messageDao.insert(it) }
            inserted = true
            // A receipt that arrived before these rows existed can be filed now. Attachments and
            // voice notes reach the store this way rather than through [markSent] — they are
            // written only once Apple has named them — so they need the same drain.
            fresh.map { it.wireGuid }.filter { it.isNotBlank() }.distinct().forEach { guid ->
                heldReceipts.remove(guid)?.forEach { applyReceipt(guid, it.sender, it.status, it.at) }
            }
            val preview = fresh.last()
            chatDao.bumpAfterMessage(
                identity.id,
                preview.body.ifEmpty { "Attachment" },
                preview.createdAt,
                unreadDelta = if (unread) 1 else 0,
            )
        }
        return inserted
    }

    /**
     * Store one of our own messages before it has been sent.
     *
     * The thread draws what is in the store, so a bubble exists only once its row does — writing the
     * row first is what lets a send appear and animate at the moment it is pressed, with the trip to
     * Apple happening behind it.
     *
     * The row goes in with a blank [MessageEntity.wireGuid] and no delivery status, which together
     * are what "not away yet" looks like: nothing that addresses a message by guid — a receipt, an
     * edit, an inbound reply — can match it, and the bubble wears no tick it has not earned.
     * [markSent] gives it both once Apple names it.
     *
     * Unlike [receiveMessages] this does not deduplicate: two identical messages sent in a row are
     * two messages, and neither has a guid to tell them apart by yet.
     */
    suspend fun recordPendingOutgoing(
        identity: ChatIdentity,
        entity: MessageEntity,
        chatFields: IMessageChatFields,
    ) {
        writeMutex.withLock {
            upsertIdentity(identity)
            chatDao.setIMessageFields(
                identity.id,
                chatFields.participants,
                chatFields.senderHandle,
                chatFields.groupId,
                chatFields.service,
            )
            // Placed after everything already in the conversation rather than at the clock's
            // reading — see [showingAt].
            val row = entity.copy(createdAt = showingAt(identity.id, entity.createdAt))
            messageDao.insert(row)
            chatDao.bumpAfterMessage(
                identity.id,
                row.body.ifEmpty { "Attachment" },
                row.createdAt,
                unreadDelta = 0,
            )
        }
    }

    /** Apple has accepted a [recordPendingOutgoing] message and named it; from here it is sent. */
    suspend fun markSent(messageId: String, wireGuid: String) {
        writeMutex.withLock {
            messageDao.setWireGuid(messageId, wireGuid, DeliveryStatus.SENT.name)
            // The guid exists now, so anything that arrived for it before this moment can finally
            // be filed. Applied after the write above, which resets the row to SENT.
            heldReceipts.remove(wireGuid)?.forEach {
                applyReceipt(wireGuid, it.sender, it.status, it.at)
            }
        }
    }

    /**
     * The send never left. The bubble keeps its place in the conversation and says so, which is the
     * whole point of having drawn it before the send was attempted.
     */
    suspend fun markSendFailed(messageId: String, error: String, wireGuid: String? = null) {
        writeMutex.withLock {
            messageDao.setErrorById(messageId, error)
            if (wireGuid.isNullOrBlank()) return@withLock
            // Named even though it failed, so a receipt proving it arrived can still find it — and
            // anything that proved so while the send was still waiting is applied now. See
            // [applyReceipt], which is what takes the failure back off.
            messageDao.nameUnsent(messageId, wireGuid)
            heldReceipts.remove(wireGuid)?.forEach { applyReceipt(wireGuid, it.sender, it.status, it.at) }
        }
    }

    /** The send is being attempted again; the bubble drops its badge until the trip comes back. */
    suspend fun markSendRetrying(messageId: String) {
        messageDao.clearError(messageId)
    }

    /**
     * One stored message as it sits on disk.
     *
     * The row rather than its display form, because the caller that needs it — retrying a send —
     * has to re-send the message's stored body and reply target, neither of which survives the trip
     * through [Message] in the shape a send needs them in.
     */
    suspend fun storedRow(messageId: String): MessageEntity? = messageDao.findById(messageId)

    /**
     * Note a conversation event in the thread — a rename, someone joining or leaving.
     *
     * Deduplicated twice over: by [wireGuid], because Apple re-sends these until they are
     * acknowledged and a rename that arrived three times would read as three renames; and by text
     * within a short window, because a change we made ourselves is noted optimistically and Apple
     * may also echo it back under a guid of its own.
     *
     * Never marks the conversation unread: nobody wrote to you, and a group that quietly renames
     * itself should not put a badge on the list.
     */
    suspend fun recordSystemMessage(chatId: String, wireGuid: String, text: String, mine: Boolean, at: Long) {
        if (text.isBlank()) return
        writeMutex.withLock {
            if (messageDao.existsWirePart(wireGuid, 0)) return@withLock
            if (messageDao.hasRecentSystemMessage(chatId, text, at - SYSTEM_ECHO_WINDOW_MS)) return@withLock
            messageDao.insert(
                Message.System(id = wireGuid, mine = mine, time = formatClock(at), createdAt = at, text = text)
                    .toEntity(chatId, at)
                    .copy(wireGuid = wireGuid),
            )
            chatDao.bumpAfterMessage(chatId, text, at, unreadDelta = 0)
        }
    }

    /**
     * Record that Apple accepted one of our own sends.
     *
     * Distinct from [receiveReceipt] because nobody has acknowledged anything yet: this is the
     * hand-off, not a participant's receipt, so it carries no handle to attribute and only ever
     * moves a message forward to SENT.
     */
    suspend fun receiveSendAccepted(wireGuid: String) {
        // Blank would match every message that has not been named yet — see [receiveSendFailure].
        if (wireGuid.isBlank()) return
        messageDao.advanceStatus(wireGuid, DeliveryStatus.SENT.name, DeliveryStatus.SENT.ordinal)
    }

    /**
     * Apply one participant's delivery or read receipt to one of our own messages.
     *
     * Apple reports receipts per person. Folding them straight into a single status meant the first
     * member of a group to open a message marked it read for the whole group; the handles are kept
     * instead, and the tick is re-derived from them — so a group only reads as READ once everyone
     * has seen it, and the thread can say how many that is.
     *
     * Every stored part of the message moves together: a receipt names the message, not the part,
     * and a captioned photo whose caption read as seen while its image did not would be nonsense.
     */
    suspend fun receiveReceipt(wireGuid: String, sender: String, status: DeliveryStatus, at: Long) {
        // Blank would match every message that has not been named yet — see [receiveSendFailure].
        if (wireGuid.isBlank()) return
        writeMutex.withLock {
            // A receipt for a message whose guid has not been stored yet is held rather than
            // dropped — see [heldReceipts]. Everything else applies immediately.
            if (!applyReceipt(wireGuid, sender, status, at)) {
                // A message that failed before failures carried their GUID is still recognisable by
                // the one it was sent under. Named now, the receipt applies to it like any other.
                val unnamed = messageDao.findUnnamedFailures().firstOrNull { wireGuidFor(it.id) == wireGuid }
                if (unnamed != null) {
                    messageDao.nameUnsent(unnamed.id, wireGuid)
                    applyReceipt(wireGuid, sender, status, at)
                } else {
                    hold(wireGuid, HeldReceipt(sender, status, at))
                }
            }
        }
    }

    /**
     * Record that someone has demonstrably seen one of our messages.
     *
     * Read receipts are a single best-effort push. Nothing replays one that was missed while the
     * socket was down, so a message whose receipt arrived during a network flap or a spell in Doze
     * keeps its old tick forever — which is what leaves a message reading "Delivered" here while
     * the same message reads "Read" on an iPhone that was awake to hear it.
     *
     * Some later events prove the same fact on their own: nobody reacts to a message or replies to
     * it quoting its text without having seen it. Folded in as that person's read receipt, so the
     * proof lands in the same per-participant map an actual receipt would — accurate in a group
     * too, where it credits only the person who acted rather than the whole roster.
     *
     * Unlike [receiveReceipt] this never holds: a tapback on someone else's message is not evidence
     * about anything of ours, and there is no row for it to be waiting on.
     */
    suspend fun noteSeen(wireGuid: String, sender: String, at: Long) {
        if (wireGuid.isBlank()) return
        writeMutex.withLock { applyReceipt(wireGuid, sender, DeliveryStatus.READ, at) }
    }

    /**
     * A receipt that named a message this device had not finished sending.
     *
     * Apple can answer "delivered" before `sendText` has returned the guid it is answering about:
     * the send round trip and the receipt arrive on different paths, and the row on disk carries an
     * empty `wire_guid` until the first of those finishes. A receipt landing in that window matched
     * no row and was thrown away, which is why a message could sit on "Sent" while the recipient's
     * phone had already shown it — and why the next message, sent a second later, was fine. Read
     * receipts were never affected, since a person takes far longer to read than a socket takes to
     * answer, which is what made this look like Apple not sending delivery receipts at all.
     */
    private data class HeldReceipt(val sender: String, val status: DeliveryStatus, val at: Long)

    /**
     * Receipts waiting for their message's guid, oldest first.
     *
     * Bounded, and in insertion order so the bound drops the least recent: a guid that never
     * arrives — a send that failed outright — must not pin a receipt here for the life of the
     * process. Guarded by [writeMutex], like everything else that touches a message's state.
     */
    private val heldReceipts = LinkedHashMap<String, MutableList<HeldReceipt>>()

    private fun hold(wireGuid: String, receipt: HeldReceipt) {
        heldReceipts.getOrPut(wireGuid) { mutableListOf() }.add(receipt)
        while (heldReceipts.size > HELD_RECEIPT_LIMIT) {
            heldReceipts.remove(heldReceipts.keys.first())
        }
    }

    /**
     * Fold one receipt into every row of its message, or report that there is no such message yet.
     *
     * Assumes [writeMutex] is already held, and re-reads the rows itself so that applying several
     * held receipts in a row sees each other's writes.
     */
    private suspend fun applyReceipt(
        wireGuid: String,
        sender: String,
        status: DeliveryStatus,
        at: Long,
    ): Boolean {
        val rows = messageDao.findMineByWire(wireGuid)
        if (rows.isEmpty()) return false
        val handle = Handles.key(sender).ifBlank { sender }
        val chatId = rows.first().chatId
        val recipients = recipientCount(chatId)
        // A delivery into somebody's Focus is a quiet one: it reached their phone without making a
        // sound there. Written at the moment the receipt lands rather than worked out on read —
        // whether they had a Focus on *then* is not a question the conversation can answer later,
        // and it is the whole of what the line under the bubble reports. A break-through in the
        // last few minutes has already opened them up, so those deliveries are ordinary ones.
        if (status == DeliveryStatus.DELIVERED) {
            val chat = chatDao.findById(chatId)
            val quiet = chat != null && chat.notifsSilenced &&
                System.currentTimeMillis() - chat.dateNotifiedAnyways > NOTIFY_ANYWAY_WINDOW_MS
            if (quiet) messageDao.setDeliveredQuietly(wireGuid, true)
        }
        rows.forEach { row ->
            // Proof it got there. A send that timed out on this end is very often one whose answer
            // went missing rather than one that never left, and the row went on saying "Not
            // Delivered" — offering to delete, or to send again, a message the other person already
            // has. A delivered or read receipt settles it.
            if (row.errorText != null && status != DeliveryStatus.SENT) messageDao.clearError(row.id)
            val current = decodeReceipts(row.receipts) ?: MessageReceipts()
            val next = when (status) {
                DeliveryStatus.PLAYED -> current.withPlayed(handle, at)
                DeliveryStatus.READ -> current.withRead(handle, at)
                DeliveryStatus.DELIVERED -> current.withDelivered(handle, at)
                DeliveryStatus.SENT -> current
            }
            // Never walk a receipt backwards: a delivered notice can arrive after a read one,
            // and a status the simulated timer already advanced must not be undone either.
            val stored = row.status?.let { runCatching { DeliveryStatus.valueOf(it) }.getOrNull() }
            val aggregate = next.summarize(recipients)
            val resolved = if (stored != null && stored.ordinal > aggregate.ordinal) stored else aggregate
            messageDao.setReceipts(row.id, next.toJsonOrNull(), resolved.name)
        }
        return true
    }

    /** How many people a conversation's messages go to — 1 unless it is a group with a roster. */
    private suspend fun recipientCount(chatId: String): Int {
        val chat = chatDao.findById(chatId) ?: return 1
        if (!chat.isGroup) return 1
        val members = chat.members
            ?.let { runCatching { appJson.decodeFromString(ListSerializer(GroupMember.serializer()), it) }.getOrNull() }
            ?: return 1
        return members.size.coerceAtLeast(1)
    }

    /**
     * Record that a send failed.
     *
     * Clears the delivery status as well as storing the reason: a message that never arrived must
     * not keep the tick it optimistically picked up when it was handed to Apple. A message that
     * *did* arrive keeps both — see [MessageDao.setError], which decides that and reports whether
     * it applied.
     *
     * A blank guid is refused outright. Every one of our messages spends its first moments on disk
     * with an empty `wire_guid`, so a failure arriving without one would not fail a message — it
     * would fail every message currently in flight, in every conversation.
     */
    suspend fun receiveSendFailure(messageId: String, error: String) {
        if (messageId.isBlank()) {
            Log.w(TAG, "ignoring a send failure that named no message: $error")
            return
        }
        if (messageDao.setError(messageId, error) == 0) {
            // Either the message is not ours to fail, or it has already been delivered and this is
            // one recipient's device reporting late. Worth a line, because it is also what a
            // genuinely lost failure would look like.
            Log.i(TAG, "send failure for $messageId not applied (delivered or unknown): $error")
        }
    }

    /** Apply an edit that arrived from Apple. */
    suspend fun receiveEdit(messageId: String, part: Long, text: String) {
        writeMutex.withLock {
            val row = messageDao.findByWirePart(messageId, part) ?: return@withLock
            val chatId = row.chatId
            messageDao.editMessage(messageId, part, text, historyAfterEdit(messageId, part))
            refreshPreview(chatId)
        }
    }

    /**
     * Treats the other end typing as proof they have read what is already there.
     *
     * Read receipts are a courtesy the other party can switch off, and when they do, a conversation
     * you are visibly in the middle of leaves your last message reading "Delivered" forever. Someone
     * composing a reply has by definition seen what they are replying to, so the app says so rather
     * than reporting a state it knows to be stale.
     *
     * It is an inference, so it is only drawn where it is safe to: one-to-one conversations, and
     * only far enough to move the aggregate tick. Nothing is written into the per-participant
     * receipts — in a group one person typing says nothing about the other four, and inventing a
     * name against a receipt would make the "read by" list lie about who has seen it.
     */
    suspend fun markReadFromTyping(chatId: String) {
        writeMutex.withLock { messageDao.markMineRead(chatId) }
    }

    /** Apply an unsend, which leaves the same tombstone as a local "delete for everyone". */
    suspend fun receiveUnsend(messageId: String, part: Long) {
        writeMutex.withLock {
            val row = messageDao.findByWirePart(messageId, part) ?: return@withLock
            val chatId = row.chatId
            messageDao.tombstone(chatId, listOf(row.id))
            refreshPreview(chatId)
        }
    }

    /**
     * A one-line preview of the message a reaction landed on, for the reaction's notification.
     *
     * Falls back to the message's first stored part for the same reason [applyRemoteReaction] does:
     * Apple's part index and ours do not always line up.
     */
    suspend fun reactionTargetPreview(wireGuid: String, part: Long): String? {
        val row = messageDao.findByWirePart(wireGuid, part)
            ?: messageDao.findByWire(wireGuid).firstOrNull()
            ?: return null
        return runCatching { row.toStoredMessage().message.preview() }.getOrNull()
    }

    /** Replaces an attachment's stored description, once its bytes have arrived. */
    suspend fun setAttachmentPayload(messageId: String, attachment: Attachment) {
        messageDao.setPayload(messageId, appJson.encodeToString(Attachment.serializer(), attachment))
    }

    /** Fills in a received voice message once its clip has been downloaded and made playable. */
    suspend fun setVoiceClip(messageId: String, uri: String, duration: Float, waveform: List<Float>) {
        messageDao.setAudioClip(
            messageId,
            uri,
            duration,
            appJson.encodeToString(ListSerializer(Float.serializer()), waveform),
        )
    }

    /** Records what a voice message says, once it has been transcribed. */
    suspend fun setTranscript(messageId: String, transcript: String) {
        messageDao.setTranscript(messageId, transcript)
    }

    /** Gives up on a received voice message and shows what arrived as a file instead. */
    suspend fun demoteVoiceMessage(messageId: String, attachment: Attachment.File) {
        messageDao.demoteAudioToAttachment(
            messageId,
            attachment.name,
            appJson.encodeToString(Attachment.serializer(), attachment),
        )
    }

    /** Updates the previewPath of a GameInvite attachment after downloading the preview image. */
    suspend fun updateAttachmentPreviewPath(messageId: String, previewPath: String) {
        val row = messageDao.findById(messageId) ?: return
        val attachment = runCatching {
            appJson.decodeFromString(Attachment.serializer(), row.payload ?: "")
        }.getOrNull()
        if (attachment is Attachment.GameInvite) {
            val updated = attachment.copy(previewPath = previewPath)
            messageDao.setPayload(messageId, appJson.encodeToString(Attachment.serializer(), updated))
        }
    }

    /**
     * The stored row for a balloon message, addressed by Apple's guid for it.
     *
     * A game's turns all address the message that opened it, so this is how an update finds the
     * bubble it belongs to. Matched case-insensitively as a fallback for the same reason
     * [quotedMessage] does: a guid can come back spelled differently than it was stored.
     */
    suspend fun balloonRow(wireGuid: String): MessageEntity? {
        val guid = wireGuid.substringAfterLast('/').trim()
        val rows = messageDao.findByWire(guid).ifEmpty { messageDao.findByWireIgnoringCase(guid) }
        return rows.firstOrNull { it.payload != null } ?: rows.firstOrNull()
    }

    /**
     * The conversation a message belongs to, so a receipt or a tapback can be routed without the
     * sender having said which conversation it is about.
     *
     * Apple names the message by its GUID, so that is what is tried first. Our own outgoing
     * messages carry a locally generated `id` and pick up their GUID only once the send is
     * accepted — looking only at `id`, as this used to, found every message the other end had sent
     * us and none of the ones we had sent them, which is why a reaction to your own message never
     * appeared. The `id` lookup stays as the fallback for the callers that pass a row id.
     */
    suspend fun chatIdForMessage(messageId: String): String? =
        messageDao.chatIdOfWire(messageId) ?: messageDao.chatIdOf(messageId)

    /**
     * Resolves a display-row id to Apple's GUID and part address, for a reply or a tapback that has
     * to name the message it is about.
     *
     * Null for a message Apple has not named yet — one still on its way out, written by
     * [recordPendingOutgoing] — since a blank guid would address nothing.
     */
    suspend fun imessageMessageTarget(messageId: String): IMessageMessageTarget? =
        messageDao.findById(messageId)
            ?.takeIf { it.wireGuid.isNotBlank() }
            ?.let { IMessageMessageTarget(it.wireGuid, it.part, it.body.length) }

    /** When a stored message was sent, in epoch milliseconds, or null for one that is not stored. */
    suspend fun messageCreatedAt(messageId: String): Long? = messageDao.findById(messageId)?.createdAt

    /**
     * The message an inbound reply is answering, if we have it.
     *
     * Apple names it by wire guid and part; nothing about the quoted text travels with the reply,
     * so it has to be looked up locally or the quote renders as an empty bubble. Null when the
     * quoted message was never stored here — a reply to something sent before this device was
     * registered — in which case the reply is still shown, just without its quote.
     */
    suspend fun quotedMessage(wireGuid: String, part: String?): MessageEntity? {
        // iMessage names a message part as `p:<n>/<uuid>`, and the reply field it is embedded in is
        // split on ':' before we see it — which can leave the index still attached to the front of
        // the guid. Take the last path segment, and compare without regard to case, since the guid
        // is a UUID whose spelling is not guaranteed to survive either side unchanged.
        val guid = wireGuid.substringAfterLast('/').trim()
        val parts = messageDao.findByWire(guid).ifEmpty { messageDao.findByWireIgnoringCase(guid) }
        if (parts.isEmpty()) return null
        // The part is a colon-joined path whose first element indexes the part we want; anything
        // else means the reply points at something we did not store separately.
        val index = part?.substringBefore(':')?.toLongOrNull()
        return parts.firstOrNull { it.part == index && it.body.isNotBlank() }
            ?: parts.firstOrNull { it.body.isNotBlank() }
            ?: parts.first()
    }

    suspend fun pendingAttachmentDownloads(): List<MessageEntity> =
        messageDao.pendingAttachmentDownloads()

    /**
     * How to address `chatId` on Apple's side, or null when it has no Apple identity.
     *
     * Null is the normal answer for a conversation created before sign-in, or one that only ever
     * existed on this device — those have no participants to send to, so callers fall back to
     * local-only behavior rather than failing.
     */
    suspend fun imessageTarget(chatId: String): IMessageTarget? {
        val entity = chatDao.findById(chatId) ?: return null
        val sender = entity.senderHandle ?: return null
        val participants = entity.participants
            ?.let { runCatching { appJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return IMessageTarget(
            chatId = entity.id,
            name = entity.name,
            group = entity.isGroup,
            participants = participants,
            senderHandle = sender,
            groupId = entity.groupId,
            groupVersion = entity.groupVersion,
        )
    }

    /**
     * Re-points every conversation at what the address book currently says about the people in it —
     * their names, and the photos, monograms and fills that follow from them.
     *
     * Run whenever the address book is re-read rather than only on the message path, because
     * otherwise a conversation only learns about a contact when someone next writes in it. That
     * left a thread wearing a stale name — or bare initials, or a deleted photo — indefinitely for
     * anyone you were not actively talking to.
     *
     * [contactFor] answers for one Apple handle, returning null when nothing in the address book
     * matches; the handle's own address is the name then, which is what an unknown number has
     * always shown. **Callers must not pass a lookup backed by an empty address book** — a contacts
     * read that failed or was denied would otherwise be indistinguishable from every contact having
     * been deleted, and would rename every conversation to a phone number.
     */
    suspend fun refreshFromAddressBook(localKeys: Set<String>, contactFor: (String) -> Contact?) {
        writeMutex.withLock {
            chatDao.loadAll().forEach { chat ->
                if (chat.isGroup) refreshGroup(chat, contactFor) else refreshDirect(chat, localKeys, contactFor)
            }
        }
    }

    /**
     * A group's roster carries its faces, so each member is re-resolved in place.
     *
     * The group's own title only follows along when it was derived from the roster to begin with.
     * A group Apple named — or that someone renamed — keeps that name; one shown as "Sam, Alex"
     * because it has no name of its own gets re-derived, so renaming Sam in Contacts renames the
     * conversation too. Comparing against the name the *old* roster would have produced is what
     * distinguishes the two, since neither is recorded anywhere else.
     */
    private suspend fun refreshGroup(chat: ChatEntity, contactFor: (String) -> Contact?) {
        val members = chat.members
            ?.let { runCatching { appJson.decodeFromString(ListSerializer(GroupMember.serializer()), it) }.getOrNull() }
            ?: return
        val refreshed = members.map { member ->
            val contact = contactFor(member.id)
            val name = contact?.name ?: Handles.display(member.id)
            member.copy(
                name = name,
                initials = initialsFor(name),
                color = colorFor(name),
                photoUri = contact?.photoUri,
            )
        }
        if (refreshed == members) return
        val derived = chat.name == nameFromMembers(members)
        chatDao.setMembers(chat.id, refreshed.toJson(), if (derived) nameFromMembers(refreshed) else chat.name)
    }

    /** A one-to-one conversation *is* the person on the other end, so it takes their name outright. */
    private suspend fun refreshDirect(chat: ChatEntity, localKeys: Set<String>, contactFor: (String) -> Contact?) {
        val participants = chat.participants
            ?.let { runCatching { appJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            ?: return
        val handle = otherHandle(participants, chat.senderHandle, localKeys) ?: return
        val contact = contactFor(handle)
        val name = contact?.name ?: Handles.display(handle)
        val initials = initialsFor(name)
        val color = colorFor(name)
        if (name == chat.name && initials == chat.initials && color == chat.color &&
            contact?.photoUri == chat.photoUri
        ) {
            return
        }
        chatDao.setContactIdentity(chat.id, name, color, initials, contact?.photoUri)
    }

    /**
     * The person on the other end of a one-to-one conversation.
     *
     * The stored participant list is Apple's own, and Apple's own includes *us* — deliberately, so
     * that the count is what decides whether a conversation is a group. Every other part of the app
     * strips our handles back out before using it. This did not, and simply took the first entry:
     * on the send path that happens to be the other person, but a conversation *received* into
     * carries Apple's ordering, which can put our handle first. The conversation was then renamed
     * to our own address, wearing our own avatar, on the next address-book refresh — while
     * everything that read the participants properly went on showing the right person, which is
     * why the row was wrong and the contact page behind it was right.
     *
     * Excluded against every handle of ours rather than just the one this chat replies from, for
     * the reason [Handles.others] gives: we can be registered at several, and Apple lists whichever
     * the other end used.
     *
     * A conversation with nobody else in it is a real thing — the note to self — and there the
     * first participant is the right answer, because there we *are* the other end.
     */
    private fun otherHandle(participants: List<String>, senderHandle: String?, localKeys: Set<String>): String? {
        if (participants.isEmpty()) return null
        val mine = localKeys + setOfNotNull(senderHandle?.takeIf { it.isNotBlank() }?.let { Handles.key(it) })
        return participants.firstOrNull { Handles.key(it) !in mine } ?: participants.first()
    }

    /** Records the group version Apple was last told about. */
    suspend fun setGroupVersion(chatId: String, version: Long) {
        chatDao.setGroupVersion(chatId, version)
    }

    /**
     * Create or update a conversation that has an Apple identity but no messages yet.
     *
     * Needed because a new iMessage conversation exists as soon as the recipients are chosen — the
     * chat id is derived from the participants, not handed out by a server — so it has to be
     * storable before anything has been sent in it.
     */
    /**
     * Put a conversation on this device with nothing in it yet.
     *
     * What a recipient picker needs the moment Send is pressed: the screen it hands you off to
     * reads the store, so the conversation has to exist before the trip to Apple that registers it
     * — otherwise the page you arrive at is a conversation that does not exist, for as long as the
     * network takes to answer.
     */
    suspend fun ensureChat(identity: ChatIdentity) {
        writeMutex.withLock { upsertIdentity(identity) }
    }

    suspend fun createIMessageChat(identity: ChatIdentity, fields: IMessageChatFields) {
        writeMutex.withLock {
            upsertIdentity(identity)
            chatDao.setIMessageFields(
                identity.id,
                fields.participants,
                fields.senderHandle,
                fields.groupId,
                fields.service,
            )
        }
    }

    /**
     * Fold conversations that are the same people into one.
     *
     * Handles used to be compared as strings, so the same person reached at `tel:5551234567` and
     * `tel:+15551234567` produced two threads — one from sending, one from their reply. Now that a
     * handle has a canonical form, a conversation already on disk can be asked what its id *should*
     * be, and the ones that agree can be merged.
     *
     * Idempotent, and cheap when there is nothing to do, so it runs at every start rather than
     * behind a migration flag: that also repairs any split that manages to appear later.
     */
    suspend fun repairSplitChats(localKeys: Set<String>) {
        writeMutex.withLock {
            val duplicates = chatDao.loadAll()
                .mapNotNull { chat ->
                    val participants = chat.participants?.let {
                        runCatching { appJson.decodeFromString(ListSerializer(String.serializer()), it) }
                            .getOrNull()
                    }
                    // Local-only conversations have no Apple identity to re-derive an id from, and
                    // must be left exactly as they are.
                    if (!Handles.hasAppleIdentity(participants, chat.groupId)) return@mapNotNull null
                    val canonical = Handles.chatId(
                        participants = participants.orEmpty(),
                        sender = chat.senderHandle,
                        groupId = chat.groupId,
                        localKeys = localKeys,
                    )
                    canonical.takeIf { it.isNotBlank() }?.let { it to chat }
                }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size > 1 }

            duplicates.forEach { (canonical, chats) -> merge(canonical, chats) }
        }
    }

    /**
     * Drain every duplicate into the conversation that owns [canonical].
     *
     * Order matters and is not interchangeable: `messages.chat_id` is a foreign key onto `chats`
     * that cascades on delete, so the destination has to exist before anything moves to it, and
     * every message has to have moved before the drained row is removed. Deleting first would take
     * the history with it.
     */
    private suspend fun merge(canonical: String, chats: List<ChatEntity>) {
        val counts = chats.associateWith { messageDao.countForChat(it.id) }
        // Whichever holds the most history keeps its appearance and settings; it is the thread the
        // user has actually been reading.
        val richest = chats.maxByOrNull { counts.getValue(it) } ?: return

        val survivor = chats.firstOrNull { it.id == canonical }
            ?: chatDao.findById(canonical)
            // Re-keyed from whichever chat holds the most history. Counters start at zero because
            // the row it was copied from is itself absorbed below — carrying them over here would
            // count that chat's unread messages twice.
            ?: richest.copy(id = canonical, unread = 0).also { chatDao.insert(it) }

        chats.filterNot { it.id == survivor.id }.forEach { stale ->
            messageDao.reassignChat(source = stale.id, target = survivor.id)
            chatDao.absorb(
                id = survivor.id,
                unread = stale.unread,
                muted = stale.muted,
                preview = stale.preview,
                updatedAt = stale.updatedAt,
            )
            chatDao.deleteChat(stale.id)
        }

        Log.i(TAG, "merged ${chats.size} split conversations into $canonical")
    }

    /** Our own tapback on a message, so the sender knows whether tapping again adds or removes. */
    suspend fun reactionOf(chatId: String, messageId: String, sender: String): StoredReaction? {
        val row = messageDao.loadForChat(chatId).find { it.id == messageId } ?: return null
        return decodeReactions(row.reactions)[sender]
    }

    /** The identity half of a stored conversation, for re-upserting it on a write. */
    suspend fun identityOf(chatId: String): ChatIdentity? {
        val entity = chatDao.findById(chatId) ?: return null
        return ChatIdentity(
            id = entity.id,
            name = entity.name,
            color = entity.color,
            initials = entity.initials,
            group = entity.isGroup,
            members = null,
            photoUri = entity.photoUri,
        )
    }

    /** Rewrites a sent text message; it renders as edited afterwards. */
    fun editMessage(chatId: String, messageId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        scope.launch {
            writeMutex.withLock {
                val row = messageDao.findById(messageId) ?: return@withLock
                messageDao.editMessage(row.wireGuid, row.part, body, historyAfterEdit(row.wireGuid, row.part))
                refreshPreview(chatId)
            }
        }
    }

    /**
     * The edit log a message will have once the version currently in `body` has been replaced.
     *
     * Read and rebuilt here rather than appended in SQL, so both edit paths — one of ours going
     * out, and one of theirs arriving — extend the same list the same way. A blank body is not
     * recorded: there is nothing to read back, and a run of empty entries in the log would be worse
     * than a gap in it.
     */
    private suspend fun historyAfterEdit(wireGuid: String, part: Long): String? {
        val row = messageDao.findTextByWirePart(wireGuid, part) ?: return null
        val previous = row.body.takeIf { it.isNotBlank() } ?: return row.editHistory
        return (decodeEditHistory(row.editHistory) + previous).editHistoryJsonOrNull()
    }

    /** `forEveryone` leaves a tombstone in the thread; otherwise the messages are simply gone. */
    fun deleteMessages(chatId: String, messageIds: List<String>, forEveryone: Boolean) {
        if (messageIds.isEmpty()) return
        scope.launch {
            writeMutex.withLock {
                if (forEveryone) messageDao.tombstone(chatId, messageIds) else messageDao.deleteMessages(chatId, messageIds)
                refreshPreview(chatId)
            }
        }
    }

    /**
     * Rewrites the chat's denormalized preview from whatever message is now last.
     *
     * The chat list no longer reads this column — it derives the line from the same joined row it
     * gets the sender and the kind from, so the two can never describe different messages. It is
     * still what the forward sheet and search results show, and it is still what a conversation
     * with no messages left falls back to, so it is kept in step.
     */
    private suspend fun refreshPreview(chatId: String) {
        val last = messageDao.lastMessage(chatId)
        chatDao.setPreview(chatId, last?.let { previewTextFor(it.kind, it.body) } ?: "")
    }

    fun setChatTheme(chatId: String, theme: ChatThemeKey, hue: Float = ChatThemeKey.DEFAULT_HUE) {
        scope.launch { chatDao.setTheme(chatId, ChatThemeKey.storageKeyFor(theme, hue)) }
    }

    /**
     * Every handle that counts as us in this conversation.
     *
     * On the repository rather than on the sender, because two surfaces need it and only one of
     * them sends anything: a reaction is drawn in your own colour when you placed it, and the chat
     * list's peek draws the same reactions as the conversation does.
     */
    suspend fun myHandles(chatId: String): Set<String> =
        setOfNotNull(LOCAL_SENDER, imessageTarget(chatId)?.senderHandle)

    /** A group's stored roster, for working out what a membership change actually changed. */
    suspend fun membersOf(chatId: String): List<GroupMember> {
        val chat = chatDao.findById(chatId) ?: return emptyList()
        return chat.members
            ?.let { runCatching { appJson.decodeFromString(ListSerializer(GroupMember.serializer()), it) }.getOrNull() }
            ?: emptyList()
    }

    /** Replaces a group's roster; the group's name is re-derived from it unless `name` is given. */
    fun setMembers(chatId: String, members: List<GroupMember>, name: String? = null) {
        val resolved = name ?: nameFromMembers(members)
        scope.launch { chatDao.setMembers(chatId, members.toJson(), resolved) }
    }

    fun deleteChat(chatId: String) {
        receiptJobs.values.forEach { it.cancel() }
        scope.launch {
            writeMutex.withLock {
                messageDao.deleteForChat(chatId)
                chatDao.deleteChat(chatId)
            }
        }
    }

    /**
     * Messages across every conversation matching [query], newest first.
     *
     * The full-text index first, which is a lookup rather than a scan. It matches whole words and
     * word prefixes, so a query that is only the middle of a word finds nothing there — and only
     * then does the old substring scan run, so the common case stays fast and the uncommon one
     * still gets an answer.
     */
    suspend fun searchMessages(query: String, limit: Int = 50): List<MessageHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val match = SearchQuery.ftsMatch(needle)
        val indexed = match?.let { runCatching { messageDao.searchFts(it, limit) }.getOrNull() }.orEmpty()
        val rows = indexed.ifEmpty { messageDao.search(likePattern(needle), limit) }
        return rows.map { MessageHit(it.id, it.chatId, it.body, it.createdAt, it.mine) }
    }

    /** The same search confined to one conversation — reaching every message, not only the window. */
    suspend fun searchInChat(chatId: String, query: String, limit: Int = 500): List<MessageHit> {
        val needle = query.trim()
        if (needle.isEmpty() || chatId.isBlank()) return emptyList()
        val match = SearchQuery.ftsMatch(needle)
        val indexed = match?.let { runCatching { messageDao.searchFtsInChat(chatId, it, limit) }.getOrNull() }.orEmpty()
        val rows = indexed.ifEmpty { messageDao.searchInChat(chatId, likePattern(needle), limit) }
        return rows.map { MessageHit(it.id, it.chatId, it.body, it.createdAt, it.mine) }
    }

    /**
     * Takes back the stickers [sender] laid on [targetGuid].
     *
     * Apple's removal names the message and the person, not the sticker's own guid, so sticker rows
     * are stored under an id that starts with exactly those two — see [stickerRowId].
     */
    suspend fun removeStickers(chatId: String, targetGuid: String, sender: String) {
        writeMutex.withLock {
            messageDao.deleteByIdPrefix(chatId, likePattern(stickerRowPrefix(targetGuid, sender)).removeSuffix("%"))
        }
    }

    /**
     * Lays one of our own stickers on [targetId], ahead of the send, so it shows the moment it is
     * dropped. Stored the way a received one is — see [stickerRowId] — and so drawn the same way.
     */
    suspend fun addOwnSticker(
        chatId: String,
        targetId: String,
        targetGuid: String,
        targetPart: Long,
        sender: String,
        guid: String,
        image: Attachment.Image,
    ): String {
        val rowId = stickerRowId(targetGuid, sender, guid)
        writeMutex.withLock {
            messageDao.insertAll(
                listOf(
                    MessageEntity(
                        id = rowId,
                        chatId = chatId,
                        kind = "attachment",
                        body = "Sticker",
                        detail = null,
                        missed = null,
                        mine = true,
                        senderId = null,
                        status = DeliveryStatus.SENT.name,
                        replyToId = targetId,
                        replyToName = null,
                        replyToText = null,
                        audioUri = null,
                        audioDuration = null,
                        audioWaveform = null,
                        payload = appJson.encodeToString(Attachment.serializer(), image),
                        reactions = null,
                        edited = false,
                        createdAt = System.currentTimeMillis(),
                        handle = sender,
                        part = 0,
                        replyToPart = targetPart.toString(),
                        service = "iMessage",
                        effect = null,
                        subject = null,
                        errorText = null,
                        wireGuid = guid,
                        attachmentToken = null,
                    ),
                ),
            )
        }
        return rowId
    }

    /** Takes a sticker row off, by its own id — one of ours whose send did not go through. */
    suspend fun removeStickerRow(chatId: String, rowId: String) {
        writeMutex.withLock { messageDao.deleteMessages(chatId, listOf(rowId)) }
    }

    /** Puts a conversation away in Archived, or brings it back. */
    fun setArchived(chatId: String, archived: Boolean) {
        scope.launch { chatDao.setArchived(chatId, archived) }
    }

    /** Records the link card fetched for a message — see [MessageEntity.linkPreview]. */
    suspend fun setLinkPreview(messageId: String, preview: com.ruskcoder.messages.data.model.LinkPreview?) {
        messageDao.setLinkPreview(messageId, preview?.let { appJson.encodeToString(com.ruskcoder.messages.data.model.LinkPreview.serializer(), it) })
    }

    /** Sends that were started and never finished — see [MessageDao.findInterruptedSends]. */
    suspend fun interruptedSends(): List<MessageEntity> = messageDao.findInterruptedSends()

    /** Sends that failed recently enough to be worth one automatic retry. */
    suspend fun recentFailedSends(since: Long): List<MessageEntity> = messageDao.findRecentFailedSends(since)

    /** Escapes the LIKE wildcards so a literal `%` or `_` in the query stays literal. */
    private fun likePattern(query: String): String =
        "%${query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"

    /**
     * Every conversation and message, as the rows they are stored as.
     *
     * The raw entities rather than the `Chat`/`Message` models the UI reads: those are lossy in
     * both directions — the models drop columns the DAOs maintain, and rebuilding a row from one
     * would invent values for the rest. A backup exists to come back byte-identical.
     *
     * Attachment bodies are not included. They are files elsewhere on the device referenced by
     * uri, and a restore onto another phone cannot make those uris resolve; the messages come back
     * with their captions and their place in the thread, and the media does not.
     */
    suspend fun exportForBackup(): BackupMessages = writeMutex.withLock {
        BackupMessages(chats = chatDao.loadAllForBackup(), messages = messageDao.loadAllForBackup())
    }

    /**
     * Replace the conversation database with what a backup holds.
     *
     * Replace rather than merge: a restore is meant to reproduce the state the backup was taken
     * in, and merging two histories of the same conversations would interleave them by timestamp
     * into something that was never true. Chats go in first — `messages` has a foreign key onto
     * them that would reject any message whose conversation is not there yet.
     */
    suspend fun restoreFromBackup(backup: BackupMessages) {
        receiptJobs.values.forEach { it.cancel() }
        receiptJobs.clear()
        writeMutex.withLock {
            messageDao.clearAll()
            chatDao.clearAll()
            chatDao.insertAll(backup.chats)
            messageDao.insertAll(backup.messages)
        }
    }

    fun clearChats() {
        receiptJobs.values.forEach { it.cancel() }
        receiptJobs.clear()
        scope.launch {
            writeMutex.withLock {
                messageDao.clearAll()
                chatDao.clearAll()
            }
        }
    }

    companion object {
        private const val TAG = "ChatRepository"

        /** How close two identical conversation events have to be to count as the same one. */
        private const val SYSTEM_ECHO_WINDOW_MS = 60_000L

        /**
         * How far ahead of this phone's clock another end may be before [showingAt] stops
         * correcting for it. Generous for a clock disagreement between two devices and far short of
         * anything that would move a message to another day.
         */
        private const val SKEW_LIMIT_MS = 5 * 60_000L

        /** How long a sent message takes to pick up each receipt — no server, so it's simulated. */
        private const val DELIVERED_AFTER_MS = 900L
        private const val READ_AFTER_MS = 2800L

        /**
         * How many un-matched message guids may be holding receipts at once.
         *
         * Generous for what it covers — the gap between a send starting and its guid being stored,
         * measured in hundreds of milliseconds — and small enough that a run of failed sends cannot
         * grow it without bound.
         */
        private const val HELD_RECEIPT_LIMIT = 32

        /**
         * How long one "Notify Anyway" keeps the other end's Focus open, in millis.
         *
         * Apple treats a break-through as covering a short window rather than a single message, so
         * anything sent inside it lands normally and must not be labelled quiet. Matched to what
         * OpenBubbles uses, which is five minutes.
         */
        private const val NOTIFY_ANYWAY_WINDOW_MS = 5 * 60 * 1000L
    }
}

/** The id a sticker row is stored under: the message it sits on, who put it there, and its own guid. */
fun stickerRowId(targetGuid: String, sender: String, guid: String): String =
    stickerRowPrefix(targetGuid, sender) + guid

private const val STICKER_ROW_PREFIX = "sticker:"

private fun stickerRowPrefix(targetGuid: String, sender: String): String = "$STICKER_ROW_PREFIX$targetGuid:$sender:"

/** How long a conversation stays warm after the last screen showing it has gone. */
private const val ThreadCacheMs = 5L * 60 * 1000

/**
 * How many messages a conversation opens with, and how many more each scroll back loads.
 *
 * Several screens deep on any phone, so a conversation never visibly runs out on the way in, and
 * small enough that decoding and laying out the window stays well inside a frame budget.
 */
internal const val ThreadPageSize = 300

/** Extra rows loaded past a jump target, so it does not land on the very edge of what is loaded. */
private const val WindowSlack = 60

/** How long a jump waits for a widened window to come through before giving up on it. */
private const val EnsureLoadedTimeoutMs = 4_000L

/** The longest a background warm-up will wait for a conversation that may simply be empty. */
private const val PrefetchTimeoutMs = 8_000L
