package com.ruskcoder.messages.data.db

import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.Chat
import com.ruskcoder.messages.data.model.ChatRecord
import com.ruskcoder.messages.data.model.ChatThemeKey
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.data.model.DELETED_KIND
import com.ruskcoder.messages.data.model.GroupMember
import com.ruskcoder.messages.data.model.LastMessage
import com.ruskcoder.messages.data.model.LinkPreview
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.MessageEffect
import com.ruskcoder.messages.data.model.MessageReceipts
import com.ruskcoder.messages.data.model.Reactions
import com.ruskcoder.messages.data.model.ReplyPreview
import com.ruskcoder.messages.data.model.TextRun
import com.ruskcoder.messages.data.model.StoredMessage
import com.ruskcoder.messages.data.model.StoredReaction
import com.ruskcoder.messages.data.model.deliveredQuietly
import com.ruskcoder.messages.data.model.preview
import com.ruskcoder.messages.data.model.reactions
import com.ruskcoder.messages.data.model.summarize
import com.ruskcoder.messages.data.model.replyTo
import com.ruskcoder.messages.data.model.senderId
import com.ruskcoder.messages.data.model.status
import com.ruskcoder.messages.imessage.Handles
import com.ruskcoder.messages.util.appJson
import com.ruskcoder.messages.util.formatChatTime
import com.ruskcoder.messages.util.formatClock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString

/**
 * Resolves the joined last-message columns against the conversation's own roster.
 *
 * The sender arrives as an Apple handle and the row wants a name, which only the roster can supply
 * — and only for a group, since naming the one person you are talking to on every row of a
 * one-to-one conversation is noise.
 */
fun ChatListRow.toChatRecord(): ChatRecord {
    val record = chat.toChatRecord()
    val kind = lastKind ?: return record
    val senderName = lastSenderId
        ?.takeIf { record.group }
        ?.let { handle -> record.members.firstOrNull { Handles.key(it.id) == Handles.key(handle) }?.name }
    return record.copy(
        // Read off the same joined row as everything else on the line — see [ChatListRow.lastBody].
        preview = previewTextFor(kind, lastBody.orEmpty()),
        last = LastMessage(
            mine = lastMine == true,
            kind = kind,
            // An unsent message has no delivery state to report: it is the absence of a
            // message, not a message that got somewhere. The stored row keeps the status column it
            // had before it was retracted, which is why this has to be dropped explicitly rather
            // than simply read.
            status = lastStatus
                ?.takeIf { kind != DELETED_KIND }
                ?.let { runCatching { DeliveryStatus.valueOf(it) }.getOrNull() },
            senderName = senderName,
        ),
    )
}

/**
 * How a stored message reads on one line of the chat list.
 *
 * Some kinds have no words of their own to show. A voice note's body is deliberately left empty (it
 * is not searchable text), a tombstone keeps the body it had so the thread can still offer to
 * reveal it, and an attachment that arrived before its own label was known has nothing yet — each
 * of those has to be named rather than printed.
 */
fun previewTextFor(kind: String, body: String): String = when {
    kind == "audio" -> "Voice message"
    kind == DELETED_KIND -> "This message was deleted"
    body.isNotBlank() -> body
    kind == "attachment" -> "Attachment"
    else -> ""
}

fun ChatEntity.toChatRecord(): ChatRecord = ChatRecord(
    id = id,
    name = name,
    color = color,
    initials = initials,
    group = isGroup,
    muted = muted,
    unread = unread,
    preview = preview,
    updatedAt = updatedAt,
    theme = ChatThemeKey.fromStorageKey(theme),
    themeHue = ChatThemeKey.hueFromStorageKey(theme),
    members = members?.let { appJson.decodeFromString(ListSerializer(GroupMember.serializer()), it) } ?: emptyList(),
    photoUri = photoUri,
    pinned = pinned,
    snoozedUntil = snoozedUntil,
    wallpaperUri = wallpaperUri,
    readReceipts = readReceipts.toOverride(),
    typingIndicators = typingIndicators.toOverride(),
    blocked = blocked,
    notifsSilenced = notifsSilenced,
    dateNotifiedAnyways = dateNotifiedAnyways,
    archived = archived,
)

fun List<GroupMember>.toJson(): String = appJson.encodeToString(ListSerializer(GroupMember.serializer()), this)

private val reactionsSerializer = MapSerializer(String.serializer(), StoredReaction.serializer())

private val editHistorySerializer = ListSerializer(String.serializer())

/** Tolerant of unreadable JSON, for the same reason the reactions map is. */
fun decodeEditHistory(json: String?): List<String> =
    json?.let { runCatching { appJson.decodeFromString(editHistorySerializer, it) }.getOrNull() }
        ?: emptyList()

/** Encodes to null when empty, so "never edited" has one representation rather than two. */
fun List<String>.editHistoryJsonOrNull(): String? =
    takeIf { it.isNotEmpty() }?.let { appJson.encodeToString(editHistorySerializer, it) }

/** Tolerant of unreadable JSON: a corrupt tapback map must not make its message unreadable. */
fun decodeReactions(json: String?): Reactions =
    json?.let { runCatching { appJson.decodeFromString(reactionsSerializer, it) }.getOrNull() }
        ?: emptyMap()

/** Encodes to null when empty, so "no reactions" is one representation rather than two. */
fun Reactions.toJsonOrNull(): String? =
    takeIf { it.isNotEmpty() }?.let { appJson.encodeToString(reactionsSerializer, it) }

/** Null for a message nobody has acknowledged yet, so "no receipts" has one representation. */
fun decodeReceipts(json: String?): MessageReceipts? =
    json?.let { runCatching { appJson.decodeFromString(MessageReceipts.serializer(), it) }.getOrNull() }
        ?.takeIf { !it.isEmpty }

fun MessageReceipts.toJsonOrNull(): String? =
    takeIf { !it.isEmpty }?.let { appJson.encodeToString(MessageReceipts.serializer(), it) }

/**
 * The stored formatting, checked against the text it claims to describe.
 *
 * Runs that do not spell [body] are dropped rather than drawn. They can only come from a row
 * written by an older build or one whose body was rewritten by an edit — and a set of runs that is
 * a character out of step does not degrade into slightly-wrong formatting, it mis-styles the wrong
 * words. Plain text is the safe answer to "I no longer know how this was dressed".
 */
fun decodeTextRuns(json: String?, body: String): List<TextRun> {
    val runs = json?.let {
        runCatching { appJson.decodeFromString(ListSerializer(TextRun.serializer()), it) }.getOrNull()
    } ?: return emptyList()
    return runs.takeIf { it.joinToString("") { run -> run.text } == body }.orEmpty()
}

/** Written only where something is actually formatted; plain text stores nothing at all. */
fun List<TextRun>.toJsonOrNull(): String? =
    takeIf { runs -> runs.any { !it.format.plain } }
        ?.let { appJson.encodeToString(ListSerializer(TextRun.serializer()), it) }

fun MessageEntity.toStoredMessage(): StoredMessage {
    val time = formatClock(createdAt)
    val replyTo = replyToId?.let { ReplyPreview(it, replyToName ?: "", replyToText ?: "") }
    // Grouped by glyph, senders kept: a group needs to know how many people picked the same
    // reaction and which of them was you, and neither survives a flat list of characters.
    val reactionList = reactions?.let { decodeReactions(it).summarize() }?.takeIf { it.isNotEmpty() }
    val status = status?.let { runCatching { DeliveryStatus.valueOf(it) }.getOrNull() }
    val receiptMap = decodeReceipts(receipts)

    val message: Message = when (kind) {
        "call" -> Message.Call(id = id, mine = mine, time = time, createdAt = createdAt, title = body, detail = detail ?: "", missed = missed == true)
        "deleted" -> Message.Deleted(
            id = id,
            mine = mine,
            time = time,
            createdAt = createdAt,
            text = body,
            attachment = payload?.let { runCatching { appJson.decodeFromString(Attachment.serializer(), it) }.getOrNull() },
        )
        "system" -> Message.System(id = id, mine = mine, time = time, createdAt = createdAt, text = body)
        "attachment" -> Message.AttachmentMessage(
            id = id,
            mine = mine,
            senderId = senderId,
            time = time,
            createdAt = createdAt,
            attachment = payload?.let { appJson.decodeFromString(Attachment.serializer(), it) }
                ?: Attachment.File(uri = "", name = ""),
            status = status,
            deliveredQuietly = deliveredQuietly,
            receipts = receiptMap,
            replyTo = replyTo,
            reactions = reactionList,
            verificationFailed = verificationFailed,
            error = errorText,
        )
        "audio" -> Message.Audio(
            id = id,
            mine = mine,
            senderId = senderId,
            time = time,
            createdAt = createdAt,
            uri = audioUri ?: "",
            duration = audioDuration ?: 0f,
            waveform = audioWaveform?.let { appJson.decodeFromString(ListSerializer(Float.serializer()), it) } ?: emptyList(),
            transcript = transcript,
            status = status,
            deliveredQuietly = deliveredQuietly,
            receipts = receiptMap,
            replyTo = replyTo,
            reactions = reactionList,
            verificationFailed = verificationFailed,
            error = errorText,
        )
        else -> Message.Text(
            id = id,
            text = body,
            runs = decodeTextRuns(textRuns, body),
            // An id this build does not know arrives as an ordinary message — see [MessageEffect].
            effect = MessageEffect.fromWire(effect),
            mine = mine,
            senderId = senderId,
            time = time,
            createdAt = createdAt,
            status = status,
            deliveredQuietly = deliveredQuietly,
            receipts = receiptMap,
            replyTo = replyTo,
            reactions = reactionList,
            edited = edited,
            history = decodeEditHistory(editHistory),
            verificationFailed = verificationFailed,
            error = errorText,
            link = decodeLinkPreview(linkPreview),
        )
    }
    return StoredMessage(message, createdAt)
}

/** Tolerant of unreadable JSON: a broken card must not make the words above it unreadable. */
fun decodeLinkPreview(json: String?): LinkPreview? =
    json?.let { runCatching { appJson.decodeFromString(LinkPreview.serializer(), it) }.getOrNull() }

fun LinkPreview.toJson(): String = appJson.encodeToString(LinkPreview.serializer(), this)

/** Display model shown by the chat list — adds the formatted `time` label. */
fun ChatRecord.toChat(): Chat = Chat(
    id = id,
    name = name,
    preview = preview,
    time = formatChatTime(updatedAt),
    color = color,
    initials = initials,
    unread = unread,
    group = group,
    muted = muted,
    theme = theme,
    themeHue = themeHue,
    members = members,
    pinned = pinned,
    photoUri = photoUri,
    last = last,
    snoozedUntil = snoozedUntil,
    wallpaperUri = wallpaperUri,
    readReceipts = readReceipts,
    typingIndicators = typingIndicators,
    blocked = blocked,
    notifsSilenced = notifsSilenced,
    dateNotifiedAnyways = dateNotifiedAnyways,
    archived = archived,
)

/**
 * `body` doubles as the search index and the chat-list preview, so every kind puts something
 * readable in it — an attachment its label, a voice message nothing to match on (mirrors the
 * store's own `isAudio ? '' : messagePreview(message)`).
 */
fun Message.toEntity(chatId: String, createdAt: Long): MessageEntity {
    val kind = when (this) {
        is Message.Text -> "text"
        is Message.Call -> "call"
        is Message.Deleted -> "deleted"
        is Message.Audio -> "audio"
        is Message.AttachmentMessage -> "attachment"
        is Message.System -> "system"
    }
    val replyTo = replyTo()
    return MessageEntity(
        id = id,
        chatId = chatId,
        kind = kind,
        body = if (this is Message.Audio) "" else preview(),
        detail = (this as? Message.Call)?.detail,
        missed = (this as? Message.Call)?.missed,
        mine = mine,
        senderId = senderId(),
        status = status()?.name,
        deliveredQuietly = deliveredQuietly(),
        replyToId = replyTo?.id,
        replyToName = replyTo?.name,
        replyToText = replyTo?.text,
        audioUri = (this as? Message.Audio)?.uri,
        audioDuration = (this as? Message.Audio)?.duration,
        audioWaveform = (this as? Message.Audio)?.waveform?.let { appJson.encodeToString(ListSerializer(Float.serializer()), it) },
        transcript = (this as? Message.Audio)?.transcript,
        payload = (this as? Message.AttachmentMessage)?.attachment?.let { appJson.encodeToString(Attachment.serializer(), it) },
        // Never written here. The display model carries only glyphs, so encoding it back would
        // throw away the sender each tapback belongs to; reactions are written by their own DAO
        // calls, and a message being inserted has none yet.
        reactions = null,
        edited = (this as? Message.Text)?.edited ?: false,
        createdAt = createdAt,
        textRuns = (this as? Message.Text)?.runs?.toJsonOrNull(),
        effect = (this as? Message.Text)?.effect?.wireId,
        linkPreview = (this as? Message.Text)?.link?.toJson(),
    )
}
