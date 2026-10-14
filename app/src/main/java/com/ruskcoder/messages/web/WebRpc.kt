package com.ruskcoder.messages.web

import android.content.Context
import android.net.Uri
import android.os.Build
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.Chat
import com.ruskcoder.messages.data.model.ChatIdentity
import com.ruskcoder.messages.data.model.ChatThemeKey
import com.ruskcoder.messages.data.model.BubbleStyle
import com.ruskcoder.messages.data.model.DoubleTapAction
import com.ruskcoder.messages.data.model.GalleryMode
import com.ruskcoder.messages.data.model.MAX_TEXT_SCALE
import com.ruskcoder.messages.data.model.MIN_TEXT_SCALE
import com.ruskcoder.messages.data.model.MemberView
import com.ruskcoder.messages.data.model.MultiMediaStyle
import com.ruskcoder.messages.data.model.PageTransition
import com.ruskcoder.messages.data.model.ReplyMode
import com.ruskcoder.messages.data.model.WallpaperTint
import com.ruskcoder.messages.data.model.MediaAutoDownload
import com.ruskcoder.messages.data.model.MessageTimer
import com.ruskcoder.messages.data.model.REACTION_EMOJI
import com.ruskcoder.messages.data.model.Settings
import com.ruskcoder.messages.data.model.Visibility
import com.ruskcoder.messages.data.model.displayInitials
import com.ruskcoder.messages.data.model.displayName
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.LOCAL_SENDER
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.MessageEffect
import com.ruskcoder.messages.data.model.Polls
import com.ruskcoder.messages.data.model.ReplyPreview
import com.ruskcoder.messages.data.model.RunFormat
import com.ruskcoder.messages.data.model.TextEffectKind
import com.ruskcoder.messages.data.model.TextRun
import com.ruskcoder.messages.data.model.colorFor
import com.ruskcoder.messages.data.model.initialsFor
import com.ruskcoder.messages.data.model.isPollUpdate
import com.ruskcoder.messages.data.model.optionsPayload
import com.ruskcoder.messages.data.model.pollStatesIn
import com.ruskcoder.messages.data.model.preview
import com.ruskcoder.messages.data.model.readReceiptsOn
import com.ruskcoder.messages.data.model.senderId
import com.ruskcoder.messages.data.model.votePayload
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.data.repository.ContactsRepository
import com.ruskcoder.messages.data.repository.RecentEmoji
import com.ruskcoder.messages.data.model.reactionEmoji
import com.ruskcoder.messages.data.model.textScale
import com.ruskcoder.messages.transcribe.VoiceTranscriptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonArray
import com.ruskcoder.messages.imessage.Handles
import com.ruskcoder.messages.imessage.IMessageSender
import com.ruskcoder.messages.util.appJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** An error whose message is fit to show in the browser as-is. */
internal class WebRpcException(message: String) : Exception(message)

/**
 * What a browser can ask this phone to do.
 *
 * Everything goes through the same repository and sender the app's own screens use, so a message
 * sent from the web is indistinguishable from one typed on the phone: same pending row, same
 * delivery tracking, same retry path. Where a screen holds a rule of its own — who a poll vote is
 * counted under, what a new conversation is filed as — it is repeated here rather than approximated.
 */
internal class WebRpc(
    private val context: Context,
    private val chats: ChatRepository,
    private val contacts: ContactsRepository,
    private val settings: SettingsRepository,
    private val sender: () -> IMessageSender,
    /** Voice transcription; a lambda because building it prepares the speech model store. */
    private val transcriptions: () -> VoiceTranscriptions?,
) {
    private val media = WebMedia(context)

    /** Ticks whenever a transcription starts, finishes or fails, so an open thread can be re-sent. */
    fun transcriptionChanges(): Flow<Unit> =
        transcriptions()?.let { t -> combine(t.running, t.failed) { _, _ -> } } ?: flowOf(Unit)

    private fun transcriptionState(message: Message.Audio): String {
        val t = transcriptions() ?: return "unsupported"
        return when {
            !t.isSupported -> "unsupported"
            message.id in t.running.value -> "running"
            message.id in t.failed.value -> "failed"
            else -> "idle"
        }
    }

    /** Whether a request must be answered before the next frame is read. See `WebBridge.Link.onSealed`. */
    fun isOrdered(method: String) = method == "thread.watch"

    suspend fun handle(method: String, p: JsonObject, watch: (String?, Int) -> Unit): JsonElement = when (method) {
        "hello" -> buildJsonObject {
            put("device", deviceName())
            put("protocol", PROTOCOL_VERSION)
        }

        "chats.list" -> {
            withTimeoutOrNull(READY_TIMEOUT_MS) { chats.ready.first { it } }
            appJson.encodeToJsonElement(ListSerializer(WireChat.serializer()), chats.chats.value.map { it.toWire(settings.settings.value) })
        }

        "thread.get" -> threadSnapshot(p.str("chatId"), p.limit())

        "thread.watch" -> {
            watch(p.optStr("chatId"), p.limit())
            JsonNull
        }

        "thread.locate" -> {
            val messages = loadThread(p.str("chatId"))
            val index = messages.indexOfFirst { it.id == p.str("messageId") }
            if (index < 0) throw WebRpcException("That message is no longer on your phone")
            buildJsonObject { put("fromEnd", messages.size - index) }
        }

        "message.send" -> {
            val chat = p.chat()
            val text = p.str("text")
            if (text.isBlank()) throw WebRpcException("Message is empty")
            if (text.length > MAX_SEND_CHARS) throw WebRpcException("Message is too long")
            val replyTo = p.optStr("replyToId")?.let { replyPreview(chat, it) }
            val effect = p.optStr("effect")?.let { name -> MessageEffect.entries.firstOrNull { it.name == name } }
            val sent = sender().sendText(chat.id, chat.identity(), text, replyTo, p.runs(text), effect)
                ?: throw WebRpcException("Message is empty")
            buildJsonObject { put("id", sent.id) }
        }

        "message.edit" -> {
            val text = p.str("text").trim()
            if (text.isEmpty()) throw WebRpcException("Message is empty")
            sender().edit(p.str("chatId"), p.str("messageId"), text)
            JsonNull
        }

        "message.unsend" -> {
            val chatId = p.str("chatId")
            // A grid of photos is one bubble standing for several messages; unsending it takes
            // back all of them, as the phone's own menu does.
            val message = findMessage(chatId, p.str("messageId"))
            // Said out loud rather than quietly ignored, so the page can tell whoever pressed it.
            if (!sender().canUnsend(message)) throw WebRpcException("Messages can only be unsent within 2 minutes")
            val group = (message as? Message.AttachmentMessage)?.attachment as? Attachment.MediaGroup
            val ids = group?.messageIds?.ifEmpty { null } ?: listOf(message.id)
            ids.forEach { sender().unsend(chatId, it) }
            JsonNull
        }

        "message.delete" -> {
            val ids = p["messageIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            chats.deleteMessages(p.str("chatId"), ids, forEveryone = false)
            JsonNull
        }

        "message.retry" -> {
            sender().retrySend(context, p.str("chatId"), p.str("messageId"))
            JsonNull
        }

        "reaction.toggle" -> {
            val chatId = p.str("chatId")
            val message = findMessage(chatId, p.str("messageId"))
            sender().toggleTapback(chatId, message.id, message.preview(), p.str("emoji"))
            JsonNull
        }

        "typing.set" -> {
            sender().setTyping(p.str("chatId"), p.bool("active"))
            JsonNull
        }

        "poll.send" -> {
            val chat = p.chat()
            val options = p["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (options.size < 2) throw WebRpcException("A poll needs at least two choices")
            if (options.size > Polls.MAX_OPTIONS) throw WebRpcException("A poll can have up to ${Polls.MAX_OPTIONS} choices")
            val replyTo = p.optStr("replyToId")?.let { replyPreview(chat, it) }
            if (!sender().sendPoll(chat.id, chat.identity(), p.optStr("title").orEmpty(), options, replyTo)) {
                throw WebRpcException("Your phone couldn't send that poll")
            }
            JsonNull
        }

        "poll.vote" -> {
            val chat = p.chat()
            val (state, handle) = pollState(chat, p.str("messageId"))
            val option = state.options.firstOrNull { it.optionIdentifier == p.str("optionId") }
                ?: throw WebRpcException("That choice is gone")
            sender().sendPollUpdate(chat.id, chat.identity(), p.str("messageId"), votePayload(state.toggled(option), handle))
            JsonNull
        }

        "poll.addOption" -> {
            val chat = p.chat()
            val text = p.str("text").trim()
            if (text.isEmpty()) throw WebRpcException("Choice is empty")
            val (state, handle) = pollState(chat, p.str("messageId"))
            if (state.options.size >= Polls.MAX_OPTIONS) throw WebRpcException("This poll is full")
            val options = state.options + Polls.newOption(text, handle)
            sender().sendPollUpdate(chat.id, chat.identity(), p.str("messageId"), optionsPayload(state, options, handle))
            JsonNull
        }

        "chat.markRead" -> {
            val chatId = p.str("chatId")
            chats.markRead(chatId)
            // The receipt Apple sees follows the same two switches the phone's own screen obeys:
            // opening a chat on the web must not tell someone you read it when the phone wouldn't.
            val chat = chats.chats.value.firstOrNull { it.id == chatId }
            if (chat != null && chat.readReceiptsOn(settings.settings.value)) {
                loadThread(chatId).lastOrNull { !it.mine }?.let { sender().markRead(chatId, it.id) }
            }
            JsonNull
        }

        "voice.transcribe" -> {
            val t = transcriptions()?.takeIf { it.isSupported }
                ?: throw WebRpcException("Transcription isn't available on this phone")
            val message = findMessage(p.str("chatId"), p.str("messageId")) as? Message.Audio
                ?: throw WebRpcException("That isn't a voice message")
            if (message.uri.isBlank()) throw WebRpcException("That voice message hasn't downloaded yet")
            t.transcribe(message)
            JsonNull
        }

        "settings.get" -> settingsSnapshot()

        "settings.set" -> {
            val patch = p["patch"] as? JsonObject ?: throw WebRpcException("Missing patch")
            settings.update { it.patched(patch) }
            settingsSnapshot()
        }

        "profile.photo" -> {
            val uri = settings.settings.value.profilePhotoUri ?: throw WebRpcException("No photo")
            jpeg(media.thumbnail(Uri.parse(uri), "image/jpeg", PROFILE_SIDE))
        }

        "emoji.used" -> JsonArray(RecentEmoji.push(context, p.str("emoji")).map(::JsonPrimitive))

        "chat.snooze" -> JsonNull.also {
            chats.setSnoozedUntil(p.str("chatId"), p["until"]?.jsonPrimitive?.longOrNull ?: 0L)
        }

        // What the group page's "Leave group" does: the conversation goes from this device.
        "chat.leave" -> JsonNull.also { chats.deleteChat(p.str("chatId")) }

        "chat.markUnread" -> JsonNull.also { chats.markUnread(p.str("chatId")) }
        "chat.pin" -> JsonNull.also { chats.setPinned(p.str("chatId"), p.bool("pinned")) }
        "chat.mute" -> JsonNull.also { chats.setMuted(p.str("chatId"), p.bool("muted")) }
        "chat.delete" -> JsonNull.also { chats.deleteChat(p.str("chatId")) }
        "chat.block" -> JsonNull.also { chats.setBlocked(p.str("chatId"), p.bool("blocked")) }
        // A missing or null `enabled` hands the switch back to Settings, as "Reset" does on the phone.
        "chat.readReceipts" -> JsonNull.also { chats.setReadReceipts(p.str("chatId"), p.optBool("enabled")) }
        "chat.typingIndicators" -> JsonNull.also { chats.setTypingIndicators(p.str("chatId"), p.optBool("enabled")) }
        "chat.archive" -> JsonNull.also { chats.setArchived(p.str("chatId"), p.bool("archived")) }
        "chats.clear" -> JsonNull.also { chats.clearChats() }

        "chat.theme" -> {
            val theme = ChatThemeKey.fromStorageKey(p.str("theme"))
            val hue = p["hue"]?.jsonPrimitive?.floatOrNull ?: ChatThemeKey.DEFAULT_HUE
            chats.setChatTheme(p.str("chatId"), theme, hue)
            JsonNull
        }

        "chat.rename" -> {
            val chat = p.chat()
            val name = p.str("name").trim()
            if (!chat.group) throw WebRpcException("Only groups can be renamed")
            if (name.isEmpty()) throw WebRpcException("Name is empty")
            // Both halves, as GroupScreen.commitName does: the local row, then Apple.
            chats.setMembers(chat.id, chat.members, name)
            sender().renameGroup(chat.id, name)
            JsonNull
        }

        "chat.setMembers" -> {
            val chat = p.chat()
            if (!chat.group) throw WebRpcException("Only groups have members to change")
            val members = p["members"]?.jsonArray?.map { it.jsonObject }.orEmpty().map { m ->
                val address = m.str("address")
                chat.members.firstOrNull { it.phone == address || it.id == m.optStr("id") }
                    ?: m.optStr("name").orEmpty().ifBlank { address }.let { name ->
                        GroupMember(m.optStr("id") ?: address, name, initialsFor(name), colorFor(name), address)
                    }
            }
            if (members.isEmpty()) throw WebRpcException("A group needs at least one other person")
            sender().setParticipants(chat.id, members, chat.name)
            JsonNull
        }

        "chat.photo" -> {
            val chat = p.chat()
            val uri = p.optStr("memberId")
                ?.let { id -> chat.members.firstOrNull { it.id == id }?.photoUri }
                ?: chat.photoUri
                ?: throw WebRpcException("No photo")
            jpeg(media.thumbnail(Uri.parse(uri), "image/jpeg", PHOTO_SIDE))
        }

        "chat.wallpaper" -> {
            val uri = p.chat().wallpaperUri ?: throw WebRpcException("No wallpaper")
            jpeg(media.thumbnail(Uri.parse(uri), "image/jpeg", WALLPAPER_SIDE))
        }

        "chat.clearWallpaper" -> JsonNull.also { chats.setWallpaper(p.str("chatId"), null) }

        "contacts.list" -> {
            val list = runCatching { contacts.loadContacts() }.getOrDefault(emptyList())
            appJson.encodeToJsonElement(ListSerializer(WireContact.serializer()), list.mapNotNull { it.toWire() })
        }

        "conversation.start" -> startConversation(p)

        "search.messages" -> {
            // Confined to one conversation for find-in-chat, which needs every hit in it rather than
            // the first fifty across the whole phone.
            val chatId = p.optStr("chatId")
            val hits = if (chatId == null) {
                chats.searchMessages(p.str("query"), SEARCH_LIMIT)
            } else {
                chats.searchMessages(p.str("query"), CHAT_SEARCH_LIMIT).filter { it.chatId == chatId }
            }.map { it.toWire() }
            appJson.encodeToJsonElement(ListSerializer(WireHit.serializer()), hits)
        }

        "attachment.thumb" -> {
            val source = p.source()
            val side = (p["max"]?.jsonPrimitive?.intOrNull ?: 640).coerceIn(64, 2048)
            jpeg(media.thumbnail(source.uri, source.mime, side))
        }

        "attachment.read" -> {
            val source = p.source()
            val offset = p["offset"]?.jsonPrimitive?.longOrNull ?: 0L
            val length = (p["length"]?.jsonPrimitive?.intOrNull ?: WebMedia.MAX_READ_BYTES).coerceIn(1, WebMedia.MAX_READ_BYTES)
            val (bytes, size) = media.read(source.uri, offset, length)
            buildJsonObject {
                put("data", WebCrypto.b64(bytes))
                put("size", size)
                put("mime", source.mime)
                put("name", source.name)
            }
        }

        "upload.begin" -> {
            val chat = p.chat()
            val id = media.begin(
                chatId = chat.id,
                name = p.str("name"),
                mime = p.optStr("mime") ?: "application/octet-stream",
                size = p["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                width = p["width"]?.jsonPrimitive?.intOrNull,
                height = p["height"]?.jsonPrimitive?.intOrNull,
                durationMs = p["durationMs"]?.jsonPrimitive?.longOrNull,
                replyToId = p.optStr("replyToId"),
                purpose = p.optStr("purpose") ?: "message",
            )
            buildJsonObject { put("uploadId", id) }
        }

        "upload.chunk" -> {
            media.chunk(p.str("uploadId"), p["offset"]?.jsonPrimitive?.longOrNull ?: 0L, WebCrypto.unb64(p.str("data")))
            JsonNull
        }

        "upload.finish" -> {
            val upload = media.finish(p.str("uploadId"))
            val chat = chats.chats.value.firstOrNull { it.id == upload.chatId }
                ?: throw WebRpcException("That conversation isn't on your phone")
            if (upload.purpose == "wallpaper") {
                setWallpaper(chat, upload)
            } else {
                val uri = Uri.fromFile(upload.file)
                val attachment = when {
                    upload.mime.startsWith("image/") ->
                        Attachment.Image(uri.toString(), upload.width ?: 1080, upload.height ?: 1440)
                    upload.mime.startsWith("video/") ->
                        Attachment.Video(uri.toString(), upload.width ?: 1080, upload.height ?: 1920, upload.durationMs)
                    else -> Attachment.File(uri.toString(), upload.name, upload.size, upload.mime)
                }
                val replyTo = upload.replyToId?.let { replyPreview(chat, it) }
                val ok = sender().sendAttachment(context, chat.id, chat.identity(), attachment, uri, replyTo = replyTo)
                if (!ok) throw WebRpcException("Your phone couldn't send that file")
            }
            JsonNull
        }

        else -> throw WebRpcException("Unknown request: $method")
    }

    /**
     * The same identity rules the new-chat screen uses, so a conversation started on the web is
     * filed under exactly the id the phone — and every reply coming back in — would derive.
     */
    private suspend fun startConversation(p: JsonObject): JsonElement {
        val people = p["people"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val text = p.str("text")
        if (people.isEmpty()) throw WebRpcException("Pick someone to message")
        if (text.isBlank()) throw WebRpcException("Message is empty")
        val addresses = people.map { it.str("address") }

        val identity = if (people.size == 1) {
            val address = addresses.first()
            val name = people.first().optStr("name")?.takeIf { it.isNotBlank() } ?: address
            ChatIdentity(Handles.key(Handles.normalize(address)), name, colorFor(name), initialsFor(name), false)
        } else {
            val members = people.mapIndexed { i, person ->
                val name = person.optStr("name")?.takeIf { it.isNotBlank() } ?: addresses[i]
                GroupMember(person.optStr("contactId") ?: addresses[i], name, initialsFor(name), colorFor(name), addresses[i])
            }
            val name = members.joinToString(", ") { it.name.substringBefore(' ') }
            ChatIdentity(UUID.randomUUID().toString(), name, colorFor(name), initialsFor(name), true, members)
        }

        val existing = chats.chats.value.firstOrNull { it.id == identity.id }
        if (existing != null) {
            sender().sendText(existing.id, existing.identity(), text)
        } else {
            sender().startConversationAndSend(identity, addresses, text, null)
        }
        return buildJsonObject { put("chatId", identity.id) }
    }

    /**
     * Every setting the browser draws from or offers, in one object.
     *
     * The whole of it rather than the handful a thread needs: the web's Settings pages are the
     * phone's own, row for row, so they read the same values the phone's do.
     */
    fun settingsSnapshot(): JsonElement {
        val s = settings.settings.value
        return buildJsonObject {
            put("profileName", s.displayName())
            put("profileInitials", s.displayInitials())
            put("hasProfilePhoto", !s.profilePhotoUri.isNullOrBlank())
            put("device", deviceName())
            put("mode", s.mode.name)
            put("bubbleStyle", s.bubbleType.name)
            put("reactionBar", JsonArray(s.reactionEmoji().map(::JsonPrimitive)))
            put("reactionSlots", JsonArray(s.reactionBar.map(::JsonPrimitive)))
            put("wallpaperTint", s.wallpaperTint.name)
            put("wallpaperPattern", s.wallpaperPattern)
            put("textScale", s.textScale())
            put("appTheme", s.appTheme.storageKey)
            put("appThemeHue", s.appThemeHue)
            put("memberView", s.memberView.name)
            put("replyMode", s.replyMode.name)
            put("doubleTapAction", s.doubleTapAction.name)
            put("longPressMenu", s.longPressMenu)
            put("pageTransition", s.pageTransition.name)
            put("multiMediaStyle", s.multiMediaStyle.name)
            put("galleryMode", s.galleryMode.name)
            put("notifications", s.notifications)
            put("readReceipts", s.readReceipts)
            put("typingIndicators", s.typingIndicators)
            put("swipeTimestamps", s.swipeTimestamps)
            put("enterToSend", s.enterToSend)
            put("haptics", s.haptics)
            put("autoSaveMedia", s.autoSaveMedia)
            put("linkPreviews", s.linkPreviews)
            put("appLock", s.appLock)
            put("appLockTimeoutSeconds", s.appLockTimeoutSeconds)
            put("hideNotificationContent", s.hideNotificationContent)
            put("lastSeenVisibility", s.lastSeenVisibility.name)
            put("profilePhotoVisibility", s.profilePhotoVisibility.name)
            put("aboutVisibility", s.aboutVisibility.name)
            put("messageTimer", s.messageTimer.name)
            put("mediaAutoDownload", s.mediaAutoDownload.name)
            put("securityNotifications", s.securityNotifications)
            put("speechModelWifiOnly", s.speechModelWifiOnly)
            put("recentEmoji", JsonArray(RecentEmoji.load(context).map(::JsonPrimitive)))
        }
    }

    /**
     * The settings a browser may change, applied one key at a time.
     *
     * Appearance included: a browser's Appearance page is the phone's own, and a choice made on
     * either is the choice. What a browser has no business changing — the profile, the lock, the
     * things that are really Android permissions — is simply not named below, and anything not
     * named is ignored rather than refused, so an older phone and a newer page still get along.
     */
    private fun Settings.patched(patch: JsonObject): Settings {
        var next = this
        fun flag(name: String, apply: (Settings, Boolean) -> Settings) {
            (patch[name] as? JsonPrimitive)?.booleanOrNull?.let { next = apply(next, it) }
        }
        fun <E : Enum<E>> choice(name: String, values: List<E>, apply: (Settings, E) -> Settings) {
            val picked = (patch[name] as? JsonPrimitive)?.contentOrNull ?: return
            values.firstOrNull { it.name == picked }?.let { next = apply(next, it) }
        }
        flag("notifications") { s, v -> s.copy(notifications = v) }
        flag("readReceipts") { s, v -> s.copy(readReceipts = v) }
        flag("typingIndicators") { s, v -> s.copy(typingIndicators = v) }
        flag("swipeTimestamps") { s, v -> s.copy(swipeTimestamps = v) }
        flag("enterToSend") { s, v -> s.copy(enterToSend = v) }
        flag("haptics") { s, v -> s.copy(haptics = v) }
        flag("autoSaveMedia") { s, v -> s.copy(autoSaveMedia = v) }
        flag("longPressMenu") { s, v -> s.copy(longPressMenu = v) }
        flag("linkPreviews") { s, v -> s.copy(linkPreviews = v) }
        flag("hideNotificationContent") { s, v -> s.copy(hideNotificationContent = v) }
        flag("securityNotifications") { s, v -> s.copy(securityNotifications = v) }
        flag("speechModelWifiOnly") { s, v -> s.copy(speechModelWifiOnly = v) }
        flag("wallpaperPattern") { s, v -> s.copy(wallpaperPattern = v) }
        choice("bubbleStyle", BubbleStyle.entries) { s, v -> s.copy(bubbleType = v) }
        choice("wallpaperTint", WallpaperTint.entries) { s, v -> s.copy(wallpaperTint = v) }
        choice("memberView", MemberView.entries) { s, v -> s.copy(memberView = v) }
        choice("replyMode", ReplyMode.entries) { s, v -> s.copy(replyMode = v) }
        // `NONE` is no longer offered — see AppearanceScreen — so it is not accepted from here either.
        choice("pageTransition", PageTransition.entries.filter { it != PageTransition.NONE }) { s, v -> s.copy(pageTransition = v) }
        choice("multiMediaStyle", MultiMediaStyle.entries) { s, v -> s.copy(multiMediaStyle = v) }
        choice("galleryMode", GalleryMode.entries) { s, v -> s.copy(galleryMode = v) }
        (patch["appTheme"] as? JsonPrimitive)?.contentOrNull?.let { key ->
            val hue = (patch["appThemeHue"] as? JsonPrimitive)?.floatOrNull ?: next.appThemeHue
            next = next.copy(appTheme = ChatThemeKey.fromStorageKey(key), appThemeHue = hue.mod(360f))
        }
        (patch["textScale"] as? JsonPrimitive)?.floatOrNull?.let { scale ->
            next = next.copy(messageTextScale = scale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE))
        }
        choice("doubleTapAction", DoubleTapAction.entries) { s, v -> s.copy(doubleTapAction = v) }
        choice("lastSeenVisibility", Visibility.entries) { s, v -> s.copy(lastSeenVisibility = v) }
        choice("profilePhotoVisibility", Visibility.entries) { s, v -> s.copy(profilePhotoVisibility = v) }
        choice("aboutVisibility", Visibility.entries) { s, v -> s.copy(aboutVisibility = v) }
        choice("messageTimer", MessageTimer.entries) { s, v -> s.copy(messageTimer = v) }
        choice("mediaAutoDownload", MediaAutoDownload.entries) { s, v -> s.copy(mediaAutoDownload = v) }
        (patch["appLockTimeoutSeconds"] as? JsonPrimitive)?.intOrNull?.let { next = next.copy(appLockTimeoutSeconds = it.coerceAtLeast(0)) }
        // The bar keeps its fixed length and its holes — see `EMPTY_REACTION_SLOT`.
        (patch["reactionSlots"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.let { slots ->
            if (slots.size == REACTION_EMOJI.size) next = next.copy(reactionBar = slots)
        }
        return next
    }

    /** Copied somewhere permanent: a wallpaper outlives the upload cache it arrived in. */
    private suspend fun setWallpaper(chat: Chat, upload: WebMedia.Upload) = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "web-wallpapers").apply { mkdirs() }
        val target = File(dir, "${chat.id.hashCode().toUInt()}-${System.currentTimeMillis()}.${upload.name.substringAfterLast('.', "jpg")}")
        upload.file.copyTo(target, overwrite = true)
        upload.file.parentFile?.deleteRecursively()
        chats.setWallpaper(chat.id, Uri.fromFile(target).toString())
    }

    suspend fun threadSnapshot(chatId: String, limit: Int = DEFAULT_LIMIT): JsonElement {
        val all = loadThread(chatId)
        return encodeThread(chatId, all.takeLast(limit), all)
    }

    /**
     * [window] is what the browser sees; [all] is what poll standings are counted over, since a vote
     * sent long ago can still be what decides a poll that is on screen.
     */
    suspend fun encodeThread(chatId: String, window: List<Message>, all: List<Message> = window): JsonElement {
        val chat = chats.chats.value.firstOrNull { it.id == chatId }
        val handles = chats.myHandles(chatId)
        val nameOf = namer(chat, handles)
        val polls = pollStatesIn(all, handles) { senderHandle(it, chat, handles) }
        val wire = window.filterNot { it.isPollUpdate() }.map { it.toWire(chat, polls[it.id], nameOf, ::transcriptionState) }
        return appJson.encodeToJsonElement(ListSerializer(WireMessage.serializer()), wire)
    }

    /** Names for the handles typing in a chat, as the phone would label them. */
    fun typingNames(chatId: String, handles: List<String>): List<String> {
        val chat = chats.chats.value.firstOrNull { it.id == chatId } ?: return emptyList()
        return handles.map { handle ->
            chat.members.firstOrNull { it.id == handle || it.phone == handle }?.name?.substringBefore(' ')
                ?: if (!chat.group) chat.name.substringBefore(' ') else handle
        }
    }

    private fun namer(chat: Chat?, mine: Set<String>): (String) -> String = { handle ->
        when {
            handle in mine || handle == LOCAL_SENDER -> "You"
            else -> chat?.members?.firstOrNull { it.id == handle || it.phone == handle }?.name
                ?: if (chat?.group == false) chat.name else handle
        }
    }

    /** Mirrors `ChatScreen.senderHandleOf`: who a poll update is counted under. */
    private fun senderHandle(message: Message, chat: Chat?, mine: Set<String>): String = when {
        message.mine -> mine.firstOrNull() ?: LOCAL_SENDER
        else -> message.senderId() ?: chat?.members?.firstOrNull { it.id !in mine }?.id ?: chat?.name.orEmpty()
    }

    private suspend fun pollState(chat: Chat, messageId: String): Pair<com.ruskcoder.messages.data.model.PollState, String> {
        val handles = chats.myHandles(chat.id)
        val state = pollStatesIn(loadThread(chat.id), handles) { senderHandle(it, chat, handles) }[messageId]
            ?: throw WebRpcException("That poll is gone")
        return state to (handles.firstOrNull() ?: LOCAL_SENDER)
    }

    /**
     * The whole conversation, read once.
     *
     * Not the chat screen's live window: a browser looking a message up by id — to quote it, to vote
     * on a poll — can be pointing at anything the conversation has ever held.
     */
    private suspend fun loadThread(chatId: String): List<Message> =
        withTimeoutOrNull(READY_TIMEOUT_MS) { chats.allMessagesFlow(chatId).first() }.orEmpty()

    private suspend fun findMessage(chatId: String, messageId: String): Message =
        loadThread(chatId).firstOrNull { it.id == messageId } ?: throw WebRpcException("That message is gone")

    private suspend fun replyPreview(chat: Chat, messageId: String): ReplyPreview {
        val quoted = findMessage(chat.id, messageId)
        val name = when {
            quoted.mine -> "You"
            else -> quoted.senderId()
                ?.let { id -> chat.members.firstOrNull { it.id == id || it.phone == id }?.name }
                ?: chat.name
        }
        return ReplyPreview(quoted.id, name, quoted.preview(), quoted.mine)
    }

    private fun jpeg(bytes: ByteArray?): JsonElement {
        bytes ?: throw WebRpcException("No preview")
        return buildJsonObject {
            put("mime", "image/jpeg")
            put("data", WebCrypto.b64(bytes))
        }
    }

    /** Formatting runs, dropped unless they spell the text exactly — the sender trims them against it. */
    private fun JsonObject.runs(text: String): List<TextRun> {
        val runs = this["runs"]?.jsonArray?.map { it.jsonObject }.orEmpty().map { r ->
            TextRun(
                text = r.str("text"),
                format = RunFormat(
                    bold = r.bool("bold"),
                    italic = r.bool("italic"),
                    underline = r.bool("underline"),
                    strikethrough = r.bool("strike"),
                    effect = r.optStr("effect")?.let { name -> TextEffectKind.entries.firstOrNull { it.name == name } },
                ),
            )
        }
        return runs.takeIf { it.joinToString("") { run -> run.text } == text }.orEmpty()
    }

    private fun Chat.identity() = ChatIdentity(id, name, color, initials, group, members, photoUri)

    private fun JsonObject.chat(): Chat {
        val chatId = str("chatId")
        return chats.chats.value.firstOrNull { it.id == chatId } ?: throw WebRpcException("That conversation isn't on your phone")
    }

    private suspend fun JsonObject.source(): WebMedia.Source {
        val message = findMessage(str("chatId"), str("messageId"))
        return media.sourceOf(message, this["item"]?.jsonPrimitive?.intOrNull ?: 0)
            ?: throw WebRpcException("That file hasn't downloaded to your phone yet")
    }

    private fun JsonObject.str(name: String): String = optStr(name) ?: throw WebRpcException("Missing $name")
    private fun JsonObject.optStr(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonObject.optBool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.limit(): Int = (this["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    companion object {
        const val PROTOCOL_VERSION = 3
        const val DEFAULT_LIMIT = 100
        private const val MAX_LIMIT = 5000
        private const val MAX_SEND_CHARS = 20_000
        private const val READY_TIMEOUT_MS = 2_000L
        private const val SEARCH_LIMIT = 50
        private const val CHAT_SEARCH_LIMIT = 1000
        private const val PHOTO_SIDE = 160
        private const val PROFILE_SIDE = 256
        private const val WALLPAPER_SIDE = 1600

        fun deviceName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android phone"
    }
}
