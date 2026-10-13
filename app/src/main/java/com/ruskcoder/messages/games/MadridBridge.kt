package com.ruskcoder.messages.games

import android.util.Base64
import com.bluebubbles.messaging.MadridMessage
import com.ruskcoder.messages.data.model.Attachment
import java.io.File

/**
 * Translation between this app's stored game bubble and the message shape a Madrid extension
 * speaks.
 *
 * The two describe the same thing — an iMessage balloon carrying an app payload — so this is
 * almost field-for-field. The parts that are not: `session` is what an extension keys a running
 * game by (never blank, or it cannot tell two games apart), and the preview travels as base64
 * rather than as a path, because the extension is another app and cannot read our files.
 */
object MadridBridge {

    /**
     * The stored bubble as an extension sees it.
     *
     * [guid] is Apple's id for the row; an extension only uses it to tell messages apart, so a
     * local id serves for a turn still on its way out.
     */
    fun toMadrid(invite: Attachment.GameInvite, guid: String, withPreview: Boolean = false): MadridMessage =
        MadridMessage().apply {
            messageGuid = guid
            ldText = invite.ldText ?: invite.gameName
            url = invite.url.orEmpty()
            // A game with no session of its own is keyed by the message that opened it, which is
            // the same value on every turn of that game and so is a session in all but name.
            session = invite.sessionId.takeIf { it.isNotBlank() } ?: invite.rootGuid ?: guid
            caption = invite.caption
            subcaption = invite.subcaption
            imageTitle = invite.imageTitle
            imageSubtitle = invite.imageSubtitle
            secondaryCaption = invite.secondaryCaption
            tertiaryCaption = invite.tertiaryCaption
            isLive = invite.isLive
            imageBase64 = if (withPreview) invite.previewPath?.let(::encodePreview) else null
        }

    /**
     * An extension's message as a bubble to store and send.
     *
     * [previous] is the invite this turn continues, when there is one: the extension does not know
     * or care which row a game was opened from, and the association target has to survive the trip.
     */
    fun fromMadrid(
        message: MadridMessage,
        bundleId: String,
        previous: Attachment.GameInvite? = null,
    ): Attachment.GameInvite {
        val state = GamePigeon.decode(message.url.orEmpty())
        val gameId = state["game"]?.takeIf { it.isNotBlank() }
            ?: previous?.gameId
            ?: ""
        val gameName = state["game_name"]?.takeIf { it.isNotBlank() }
            ?: message.ldText?.takeIf { it.isNotBlank() }
            ?: previous?.gameName
            ?: gameId
        return Attachment.GameInvite(
            gameId = gameId,
            gameName = gameName,
            sessionId = message.session.orEmpty().ifBlank { previous?.sessionId.orEmpty() },
            url = message.url,
            bundleId = bundleId,
            ldText = message.ldText ?: gameName,
            caption = message.caption,
            subcaption = message.subcaption,
            imageTitle = message.imageTitle,
            imageSubtitle = message.imageSubtitle,
            secondaryCaption = message.secondaryCaption,
            tertiaryCaption = message.tertiaryCaption,
            // The extension draws the bubble itself when it is installed; this is only the
            // fallback tile for a thread read without it.
            previewPath = previous?.previewPath,
            isLive = message.isLive,
            rootGuid = previous?.rootGuid,
        )
    }

    /**
     * The artwork the extension drew for this message, as JPEG bytes.
     *
     * Every message an extension composes carries one — a live render of the board for a game that
     * can draw one, its poster otherwise (see OpenPigeon's `Game.buildGameMessage`). Ignoring it and
     * staging our own placeholder card, as this used to, threw away the only picture of the game
     * that actually shows the game.
     */
    fun artwork(message: MadridMessage): ByteArray? {
        val encoded = message.imageBase64?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
    }

    private fun encodePreview(path: String): String? = runCatching {
        val file = File(path)
        if (!file.isFile) return null
        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }.getOrNull()
}
