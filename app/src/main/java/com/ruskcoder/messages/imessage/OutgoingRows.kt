package com.ruskcoder.messages.imessage

import android.content.Context
import android.net.Uri
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
import com.ruskcoder.messages.data.model.Message
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
import uniffi.imessage_core.OutgoingTapback
import uniffi.imessage_core.OutgoingText
import com.ruskcoder.messages.util.wireGuidFor
import com.ruskcoder.messages.util.saveAttachmentToDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The rows our own messages are written as before they set off for Apple.
 *
 * Every send draws its bubble first and goes to the network second — a message that appeared only
 * once Apple had accepted it looked, for the length of the round trip, like a tap the app had missed.
 * So each kind of send begins by writing a row with no guid and no delivery state, which the send then
 * names, marks sent, or marks failed when it comes back; and a row left in neither state is one
 * [IMessageSender.resumeInterruptedSends] picks up again.
 *
 * Split out of [IMessageSender], which had grown to nearly two thousand lines: this half is pure
 * bookkeeping against the store and has nothing to do with the protocol.
 */
internal class OutgoingRows(private val chats: ChatRepository) {

    /**
     * Stores a message we are about to hand to Apple, and hands back the display form of it.
     *
     * The row carries a local id and no wire guid: Apple has not seen the message yet and so has no
     * name for it, and the tick it will wear is not owed until it does. Both arrive together in
     * [ChatRepository.markSent] when the send returns.
     */
    suspend fun recordPending(
        target: IMessageTarget,
        body: String,
        replyTo: ReplyPreview?,
        /** How the body is dressed, so the bubble drawn here matches the one that goes out. */
        runs: List<TextRun> = emptyList(),
        /** And what it should do when it lands, at this end as much as the other. */
        effect: MessageEffect? = null,
    ): Message? {
        val identity = chats.identityOf(target.chatId) ?: return null
        val createdAt = System.currentTimeMillis()
        val localId = makeId()
        chats.recordPendingOutgoing(
            identity = identity,
            entity = MessageEntity(
                id = localId,
                chatId = target.chatId,
                kind = "text",
                body = body,
                detail = null,
                missed = null,
                mine = true,
                senderId = null,
                status = null,
                replyToId = replyTo?.id,
                replyToName = replyTo?.name,
                replyToText = replyTo?.text,
                audioUri = null,
                audioDuration = null,
                audioWaveform = null,
                payload = null,
                reactions = null,
                edited = false,
                createdAt = createdAt,
                handle = target.senderHandle,
                part = 0,
                replyToPart = replyTo?.let { "0" },
                service = SERVICE_IMESSAGE,
                effect = effect?.wireId,
                subject = null,
                errorText = null,
                // Blank rather than the row's own id: this is the mark of a message Apple has not
                // named yet, and it must not collide with a guid anything could look up.
                wireGuid = "",
                textRuns = runs.toJsonOrNull(),
            ),
            chatFields = IMessageChatFields(
                participants = appJson.encodeToString(
                    ListSerializer(String.serializer()),
                    target.participants,
                ),
                senderHandle = target.senderHandle,
                groupId = target.groupId,
                service = SERVICE_IMESSAGE,
            ),
        )

        return Message.Text(
            id = localId,
            text = body,
            runs = runs,
            effect = effect,
            mine = true,
            time = formatClock(createdAt),
            createdAt = createdAt,
            status = null,
            replyTo = replyTo,
        )
    }

    /**
     * Store one of our own attachments before it has been sent, the way [recordPending] does for
     * text: a blank guid, no delivery status, and the local payload already in place so the bubble
     * can draw the picture the instant it exists.
     */
    suspend fun recordPendingAttachment(
        target: IMessageTarget,
        attachment: Attachment,
        caption: String?,
        replyTo: ReplyPreview?,
    ): String? {
        val identity = chats.identityOf(target.chatId) ?: return null
        val localId = makeId()
        chats.recordPendingOutgoing(
            identity = identity,
            entity = MessageEntity(
                id = localId,
                chatId = target.chatId,
                kind = "attachment",
                body = caption?.takeIf { it.isNotBlank() } ?: attachment.preview(),
                detail = null,
                missed = null,
                mine = true,
                senderId = null,
                status = null,
                replyToId = replyTo?.id,
                replyToName = replyTo?.name,
                replyToText = replyTo?.text,
                audioUri = null,
                audioDuration = null,
                audioWaveform = null,
                payload = appJson.encodeToString(Attachment.serializer(), attachment),
                reactions = null,
                edited = false,
                createdAt = System.currentTimeMillis(),
                handle = target.senderHandle,
                part = 0,
                replyToPart = replyTo?.let { "0" },
                service = SERVICE_IMESSAGE,
                effect = null,
                subject = null,
                errorText = null,
                wireGuid = "",
            ),
            chatFields = IMessageChatFields(
                participants = appJson.encodeToString(
                    ListSerializer(String.serializer()),
                    target.participants,
                ),
                senderHandle = target.senderHandle,
                groupId = target.groupId,
                service = SERVICE_IMESSAGE,
            ),
        )
        return localId
    }

    /**
     * Stores a voice message we are about to hand to Apple, and hands back its local id.
     *
     * The audio twin of [recordPending], down to the blank wire guid: Apple has no name for this
     * clip yet, and the tick it will wear is not owed until it does.
     */
    suspend fun recordPendingAudio(
        target: IMessageTarget,
        uri: String,
        duration: Float,
        waveform: List<Float>,
        replyTo: ReplyPreview?,
    ): String? {
        val identity = chats.identityOf(target.chatId) ?: return null
        val createdAt = System.currentTimeMillis()
        val localId = makeId()
        chats.recordPendingOutgoing(
            identity = identity,
            entity = MessageEntity(
                id = localId, chatId = target.chatId, kind = "audio", body = "", detail = null,
                missed = null, mine = true, senderId = null, status = null,
                replyToId = replyTo?.id, replyToName = replyTo?.name, replyToText = replyTo?.text,
                audioUri = uri, audioDuration = duration,
                audioWaveform = appJson.encodeToString(ListSerializer(Float.serializer()), waveform),
                payload = null, reactions = null, edited = false, createdAt = createdAt,
                handle = target.senderHandle, part = 0, replyToPart = replyTo?.let { "0" },
                service = SERVICE_IMESSAGE, effect = null, subject = null, errorText = null,
                wireGuid = "",
            ),
            chatFields = IMessageChatFields(
                participants = appJson.encodeToString(ListSerializer(String.serializer()), target.participants),
                senderHandle = target.senderHandle,
                groupId = target.groupId,
                service = SERVICE_IMESSAGE,
            ),
        )
        return localId
    }
}

private const val SERVICE_IMESSAGE = "iMessage"
