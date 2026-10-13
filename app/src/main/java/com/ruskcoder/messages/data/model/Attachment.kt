package com.ruskcoder.messages.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The payloads the composer's "+" panel can produce. One `attachment` message kind carries any of
 * them; adding a new source only means adding a variant here and a renderer in AttachmentContent.
 */
@Serializable
sealed class Attachment {
    @Serializable
    @SerialName("image")
    data class Image(
        val uri: String,
        val width: Int,
        val height: Int,
        val caption: String? = null,
        /**
         * A sticker someone laid on a message rather than a photo someone sent.
         *
         * Drawn small and without a frame, the way a sticker is — see `StickerTile` — and named as
         * one in a preview line, where "Photo" would describe the wrong thing.
         */
        val sticker: Boolean = false,
        /** Where on its message a sticker sits. Null for a photo, and for a sticker sent without one. */
        val placement: StickerPlacement? = null,
        /**
         * Set when this is a Genmoji rather than a picture somebody sent: the sender's short
         * description of it ("a cat wearing sunglasses"), blank when none came with it.
         *
         * A Genmoji is part of the sentence, not an enclosure — the sender typed it where a
         * character goes and their phone draws it like one. So it is drawn as a glyph, unframed and
         * roughly the size of a large emoji, and named as one in a preview line. Null for every
         * ordinary photo, which is what makes this and not a boolean: the description is the only
         * words there are for it, and a notification and a screen reader both need them.
         */
        val genmoji: String? = null,
    ) : Attachment()

    @Serializable
    @SerialName("video")
    data class Video(
        val uri: String,
        val width: Int,
        val height: Int,
        val durationMs: Long? = null,
        val caption: String? = null,
    ) : Attachment()

    @Serializable
    @SerialName("file")
    data class File(
        val uri: String,
        val name: String,
        val size: Long? = null,
        val mimeType: String? = null,
    ) : Attachment()

    @Serializable
    @SerialName("location")
    data class Location(
        val latitude: Double,
        val longitude: Double,
        val label: String? = null,
    ) : Attachment()

    /** `contactId` is the address-book id, so the card's Message action opens that person's thread. */
    @Serializable
    @SerialName("contact")
    data class ContactCard(
        val contactId: String,
        val name: String,
        val phone: String? = null,
        val initials: String,
        val color: String,
    ) : Attachment()

    /**
     * A poll, or an update to one — Apple's Polls balloon.
     *
     * The same shape as [GameInvite] and for the same reasons: a balloon is a balloon, `url` is the
     * extension's opaque payload, `sessionId` threads the exchange, and `rootGuid` names the bubble
     * every later update addresses. What differs is only what the payload means — see [PollPayload].
     *
     * [updateOf] is what makes this two things rather than one. A poll with it null is somebody
     * asking; a poll with it set is somebody answering (or adding a choice), and is not a message
     * the conversation should draw at all — the thread folds those back into the bubble they update
     * rather than stacking them under it.
     */
    @Serializable
    @SerialName("poll")
    data class Poll(
        val sessionId: String,
        /** The `data:,<base64>?src=p&c=<n>` payload. Decode with [decodePollUrl]. */
        val url: String,
        val bundleId: String? = null,
        /** The options run together with commas — what a client with no Polls plugin shows. */
        val ldText: String? = null,
        /** Apple's guid for the message that asked the poll; the target of every update. */
        val rootGuid: String? = null,
        /** Set on an update: the guid of the poll it answers. Null on the poll itself. */
        val updateOf: String? = null,
    ) : Attachment()

    /**
     * A game invite / turn bubble, produced by an installed iMessage extension ("Madrid
     * extension" — OpenPigeon and the like) and sent on the wire as a real balloon message.
     *
     * `sessionId` is the extension's game-thread id: it is preserved verbatim for the whole life
     * of a game so each turn lands in the same session rather than starting a new one. `url` is
     * the extension's opaque `data:` payload — never re-encode or trim it. `bundleId` names the
     * balloon plugin that draws it (`…:com.gamerdelights.gamepigeon.ext`). The other fields are
     * for drawing the bubble on a device without the extension installed.
     *
     * `gameId`/`gameName` are the picker-catalog fallback and may be blank for a received invite
     * whose payload this build could not classify — [ldText] is the reliable display name then.
     */
    @Serializable
    @SerialName("game")
    data class GameInvite(
        val gameId: String,
        val gameName: String,
        val sessionId: String,
        /**
         * The GamePigeon `data:` payload this invite rides in — opaque, position-sensitive.
         * Decode with [com.ruskcoder.messages.games.GamePigeon.decode]; never trim or re-encode it.
         */
        val url: String? = null,
        /** Balloon-plugin bundle id, e.g. `com.apple.messages.MSMessageExtensionBalloonPlugin:EWFNLB79LQ:com.gamerdelights.gamepigeon.ext`. */
        val bundleId: String? = null,
        /** The game's display name for a bubble drawn without the game state, e.g. "Knockout". */
        val ldText: String? = null,
        /** `MSMessageTemplateLayout` status lines, e.g. "Your move." */
        val caption: String? = null,
        val subcaption: String? = null,
        val imageTitle: String? = null,
        val imageSubtitle: String? = null,
        val secondaryCaption: String? = null,
        val tertiaryCaption: String? = null,
        /** Local path to the JPEG preview the extension supplied for the bubble, if any. */
        val previewPath: String? = null,
        /** Whether the bubble updates in place as the game continues. */
        val isLive: Boolean = true,
        /**
         * Apple's guid for the message that opened this game — the association target every later
         * turn addresses. Null on a turn whose opening message was never stored here, and on one
         * still on its way out.
         */
        val rootGuid: String? = null,
        /**
         * Display only: an older turn of a game that has since moved on, drawn as a one-line
         * summary rather than a board. Never stored — the thread sets it as it lays the
         * conversation out, since which turn is the newest is a question about the whole
         * conversation and not about any one message in it.
         */
        val superseded: Boolean = false,
    ) : Attachment() {
        /** What to show under/next to the tile. */
        val displayName: String get() = ldText?.takeIf { it.isNotBlank() } ?: gameName
    }

    /**
     * Several photos and videos sent together, drawn as one bubble.
     *
     * Display only, like [GameInvite.superseded]: nothing sends or stores one. The thread folds a
     * run of back-to-back media messages into it as it lays the conversation out — see
     * MessageThread's collapse pass.
     */
    @Serializable
    @SerialName("media-group")
    data class MediaGroup(
        val items: List<Attachment>,
        /** The message each item arrived in, in the same order — what a like or an unsend addresses. */
        val messageIds: List<String> = emptyList(),
    ) : Attachment()
}

/**
 * What the source of an attachment was, as the panel offers it.
 *
 * `AUDIO` and `LOCATION` are gone. Audio was the document picker with a narrower MIME filter on
 * it, and the composer already has a real recorder in it. A pin was a pair of coordinates read off
 * the last known fix, which is the one attachment nobody sends.
 */
enum class AttachmentSource {
    PHOTOS,
    CAMERA,
    VIDEO,
    DOCUMENT,
    CONTACT,
}

/** Human-readable size, used on file bubbles and in the picker feedback. */
fun formatBytes(bytes: Long?): String? {
    if (bytes == null) return null
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024.0
        unit += 1
    }
    val rounded = if (value < 10) "%.1f".format(value) else Math.round(value).toString()
    return "$rounded ${units[unit]}"
}

/** `12.9130, 77.6340` — the fallback label for a pin with no reverse-geocoded address. */
fun formatCoordinates(latitude: Double, longitude: Double): String =
    "%.4f, %.4f".format(latitude, longitude)

/** The one-line summary the chat list preview and message search index use. */
fun Attachment.preview(): String = when (this) {
    is Attachment.Image -> when {
        genmoji != null -> genmojiLabel(genmoji)
        sticker -> "Sticker"
        else -> caption?.trim()?.takeIf { it.isNotEmpty() } ?: "Photo"
    }
    is Attachment.Video -> caption?.trim()?.takeIf { it.isNotEmpty() } ?: "Video"
    is Attachment.File -> name
    is Attachment.Location -> label ?: formatCoordinates(latitude, longitude)
    is Attachment.ContactCard -> name
    is Attachment.GameInvite -> displayName
    is Attachment.MediaGroup -> if (items.all { it is Attachment.Video }) "${items.size} videos" else "${items.size} photos"
    // Everywhere this is used is one line long — the chat list, a reply quote, a forward — so a
    // poll gives its title rather than its choices, and an update says what happened rather than
    // repeating the poll. `ldText`, the choices run together, is for a client that has to *draw*
    // the poll without being able to, which is a different job.
    is Attachment.Poll -> when {
        updateOf == null ->
            decodePollUrl(url)?.item?.title?.takeIf { it.isNotBlank() } ?: Polls.DEFAULT_TITLE
        decodePollUrl(url)?.item?.orderedPollOptions.orEmpty().isNotEmpty() -> "Added a poll choice"
        else -> "Voted in a poll"
    }
}

/**
 * What a Genmoji reads as on one line — a notification, a chat-list preview, a reply quote.
 *
 * The sender's own description when there is one, since that is what the glyph shows; otherwise its
 * name, because "Photo" describes the wrong thing and an empty line describes nothing.
 */
fun genmojiLabel(description: String?): String =
    description?.trim()?.takeIf { it.isNotEmpty() } ?: "Genmoji"

/**
 * Where a sticker sits on the bubble it was put on, in the terms Apple sends it in.
 *
 * [x] and [y] are its centre as a fraction of the bubble's width and height, so it lands on the
 * same spot of the same message however wide that bubble is drawn here. [scale] is its size against
 * [StickerBaseSize]; [rotation] is in radians, clockwise.
 */
@Serializable
data class StickerPlacement(
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val rotation: Float = 0f,
)
