package com.ruskcoder.messages.data.model

/**
 * The parts of an attachment that matter when sending it somewhere off this device.
 *
 * Two kinds of attachment behave differently over iMessage: those with a file behind them, which
 * are uploaded, and those without — a dropped pin, a contact card — which have no bytes to upload
 * and are sent as text instead.
 */

/** The content URI to upload, or null for an attachment that is not backed by a file. */
fun Attachment.fileUri(): String? = when (this) {
    is Attachment.Image -> uri
    is Attachment.Video -> uri
    is Attachment.File -> uri
    // A poll has no bytes either: it is a document inside a balloon, sent by its own path — see
    // [IMessageSender.sendPoll] — and never through the file upload this feeds.
    is Attachment.Location, is Attachment.ContactCard, is Attachment.GameInvite,
    is Attachment.Poll, is Attachment.MediaGroup -> null
}

/** The caption travelling with a file attachment, if the picker collected one. */
fun Attachment.caption(): String? = when (this) {
    is Attachment.Image -> caption
    is Attachment.Video -> caption
    else -> null
}

/**
 * What to send when there is no file to attach.
 *
 * A map link rather than raw coordinates, because that is something the recipient can actually
 * open — on an iPhone it resolves in Maps, and everywhere else in a browser.
 */
fun Attachment.asShareableText(): String = when (this) {
    is Attachment.Location ->
        listOfNotNull(label, "https://maps.apple.com/?ll=$latitude,$longitude").joinToString("\n")
    is Attachment.ContactCard -> listOfNotNull(name, phone).joinToString("\n")
    // Only reached for a conversation with no iMessage identity — a game invite to a local-only
    // chat, which has no balloon to carry it. Over iMessage the invite goes as a real balloon
    // message; see IMessageSender.sendGameInvite.
    is Attachment.GameInvite -> "🎮 ${displayName}${caption?.let { " — $it" } ?: ""}"
    else -> preview()
}
