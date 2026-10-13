package com.ruskcoder.messages.web

import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.Chat
import com.ruskcoder.messages.data.model.Contact
import com.ruskcoder.messages.data.model.DeliveryStatus
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.MessageHit
import com.ruskcoder.messages.data.model.PollState
import com.ruskcoder.messages.data.model.Settings
import com.ruskcoder.messages.data.model.readReceiptsOn
import com.ruskcoder.messages.data.model.typingIndicatorsOn
import com.ruskcoder.messages.data.model.edited
import com.ruskcoder.messages.data.model.effect
import com.ruskcoder.messages.data.model.error
import com.ruskcoder.messages.data.model.preview
import com.ruskcoder.messages.data.model.reactions
import com.ruskcoder.messages.data.model.replyTo
import com.ruskcoder.messages.data.model.senderId
import com.ruskcoder.messages.data.model.status
import com.ruskcoder.messages.data.model.textRuns
import kotlinx.serialization.Serializable

/**
 * What the browser is sent. Mirrors `packages/protocol/src/types.ts` in messages-web.
 *
 * Deliberately flatter than the app's own models: the browser draws from these and nothing else,
 * so anything it would have to derive — a sender's display name, a one-line summary of a photo,
 * where a poll stands — is resolved here, where the data to resolve it already is.
 */
@Serializable
data class WireChat(
    val id: String,
    val name: String,
    val color: String,
    val initials: String,
    val group: Boolean,
    val unread: Int,
    val pinned: Boolean,
    val muted: Boolean,
    val preview: String,
    val time: String,
    val lastMine: Boolean,
    val lastKind: String?,
    val lastStatus: String?,
    val lastSender: String?,
    val hasPhoto: Boolean,
    val members: List<WireMember>,
    /** `ChatThemeKey` storage key: default, ocean, indigo, teal, violet, olive, rust, custom. */
    val theme: String,
    val themeHue: Float,
    val hasWallpaper: Boolean,
    val readReceipts: Boolean,
    val typingIndicators: Boolean,
    val blocked: Boolean,
    val snoozedUntil: Long,
)

@Serializable
data class WireMember(val id: String, val name: String, val initials: String, val color: String, val address: String?, val hasPhoto: Boolean)

@Serializable
data class WireReply(val id: String, val name: String, val text: String, val mine: Boolean)

@Serializable
data class WireReaction(val emoji: String, val count: Int, val senders: List<String>)

@Serializable
data class WireRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** `TextEffectKind` name: BIG, SMALL, SHAKE, NOD, EXPLODE, RIPPLE, BLOOM, JITTER. */
    val effect: String? = null,
)

@Serializable
data class WirePollOption(val id: String, val text: String, val votes: Int, val voters: List<String>, val picked: Boolean)

@Serializable
data class WirePoll(val title: String, val options: List<WirePollOption>, val total: Int)

@Serializable
data class WireAttachment(
    /** image, video, file, audio, location, contact, group, poll, app */
    val type: String,
    val name: String? = null,
    val mime: String? = null,
    val size: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    /** Whether the bytes are on the phone yet — false while an incoming file is still downloading. */
    val available: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val label: String? = null,
    val phone: String? = null,
    val waveform: List<Float> = emptyList(),
    val items: List<WireAttachment> = emptyList(),
    /** Voice messages: the stored transcript ("" when it had no speech), and where a run has got to. */
    val transcript: String? = null,
    /** idle, running, failed, unsupported */
    val transcription: String? = null,
)

@Serializable
data class WireMessage(
    val id: String,
    val kind: String,
    val mine: Boolean,
    val text: String,
    val runs: List<WireRun>,
    val time: String,
    val createdAt: Long,
    val status: String?,
    val error: String?,
    val senderId: String?,
    val senderName: String?,
    val replyTo: WireReply?,
    val reactions: List<WireReaction>,
    val edited: Boolean,
    /** `MessageEffect` name: SLAM, LOUD, GENTLE, INVISIBLE_INK, ECHO, SPOTLIGHT, BALLOONS, … */
    val effect: String?,
    val attachment: WireAttachment?,
    val poll: WirePoll?,
)

@Serializable
data class WireContact(val id: String, val name: String, val address: String, val color: String, val initials: String)

@Serializable
data class WireHit(val messageId: String, val chatId: String, val text: String, val createdAt: Long)

private fun DeliveryStatus.wire(): String = name.lowercase()

/** [settings] resolves the two switches a conversation may leave unset, so the browser sees what is in force. */
internal fun Chat.toWire(settings: Settings) = WireChat(
    id = id,
    name = name,
    color = color,
    initials = initials,
    group = group,
    unread = unread,
    pinned = pinned,
    muted = muted,
    preview = preview,
    time = time,
    lastMine = last?.mine ?: false,
    lastKind = last?.kind,
    lastStatus = last?.status?.wire(),
    lastSender = last?.senderName,
    hasPhoto = !photoUri.isNullOrBlank(),
    members = members.map { WireMember(it.id, it.name, it.initials, it.color, it.phone, !it.photoUri.isNullOrBlank()) },
    theme = theme.storageKey,
    themeHue = themeHue,
    hasWallpaper = !wallpaperUri.isNullOrBlank(),
    readReceipts = readReceiptsOn(settings),
    typingIndicators = typingIndicatorsOn(settings),
    blocked = blocked,
    snoozedUntil = snoozedUntil,
)

/** [nameOf] turns a handle into the name the phone would show for it. */
internal fun Message.toWire(
    chat: Chat?,
    poll: PollState?,
    nameOf: (String) -> String,
    transcription: (Message.Audio) -> String = { "unsupported" },
): WireMessage {
    val senderId = senderId()
    return WireMessage(
        id = id,
        kind = when (this) {
            is Message.Text -> "text"
            is Message.AttachmentMessage -> "attachment"
            is Message.Audio -> "audio"
            is Message.Call -> "call"
            is Message.System -> "system"
            is Message.Deleted -> "deleted"
        },
        mine = mine,
        text = when (this) {
            is Message.Text -> text
            is Message.AttachmentMessage -> when (val carried = attachment) {
                is Attachment.Image -> carried.caption.orEmpty()
                is Attachment.Video -> carried.caption.orEmpty()
                else -> ""
            }
            is Message.Audio -> ""
            else -> preview()
        }.take(MAX_TEXT),
        runs = textRuns().map {
            WireRun(it.text, it.format.bold, it.format.italic, it.format.underline, it.format.strikethrough, it.format.effect?.name)
        },
        time = time,
        createdAt = createdAt,
        status = status()?.wire(),
        error = error(),
        senderId = senderId,
        senderName = senderId?.let(nameOf),
        replyTo = replyTo()?.let { WireReply(it.id, it.name, it.text.take(300), it.mine == true) },
        reactions = reactions().orEmpty().map { r -> WireReaction(r.emoji, r.count, r.senders.map(nameOf)) },
        edited = edited(),
        effect = effect()?.name,
        attachment = when (this) {
            is Message.AttachmentMessage -> attachment.toWire()
            is Message.Audio -> WireAttachment(
                type = "audio",
                durationMs = (duration * 1000).toLong(),
                available = uri.isNotBlank(),
                waveform = waveform.take(64),
                transcript = transcript,
                transcription = transcription(this),
            )
            else -> null
        },
        poll = poll?.let { state ->
            WirePoll(
                title = state.title,
                options = state.options.map { option ->
                    WirePollOption(option.optionIdentifier, option.text, state.count(option), state.voters(option).map(nameOf), state.picked(option))
                },
                total = state.total,
            )
        },
    )
}

internal fun Attachment.toWire(): WireAttachment = when (this) {
    is Attachment.Image -> WireAttachment("image", width = width, height = height, available = uri.isNotBlank())
    is Attachment.Video -> WireAttachment("video", width = width, height = height, durationMs = durationMs, available = uri.isNotBlank())
    is Attachment.File -> WireAttachment("file", name = name, size = size, mime = mimeType, available = uri.isNotBlank())
    is Attachment.Location -> WireAttachment("location", latitude = latitude, longitude = longitude, label = label)
    is Attachment.ContactCard -> WireAttachment("contact", name = name, phone = phone, label = initials)
    is Attachment.MediaGroup -> WireAttachment("group", items = items.map { it.toWire() })
    is Attachment.Poll -> WireAttachment("poll", name = ldText)
    else -> WireAttachment("app", name = preview())
}

internal fun Contact.toWire(): WireContact? =
    about?.takeIf { it.isNotBlank() }?.let { WireContact(id, name, it, color, initials) }

internal fun MessageHit.toWire() = WireHit(messageId, chatId, text.take(300), createdAt)

/** Keeps one pathological message from pushing a thread snapshot past the relay's frame cap. */
private const val MAX_TEXT = 20_000
